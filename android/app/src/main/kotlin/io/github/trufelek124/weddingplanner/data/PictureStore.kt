package io.github.trufelek124.weddingplanner.data

import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.ApiFailure
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Which version of a picture: the server's small one (a JPEG of a bounded size) or the picture itself. */
enum class PictureSize(val parameter: String) {
    THUMBNAIL("THUMBNAIL"),
    FULL("FULL"),
}

/** What the store knows of a picture without asking anyone. */
sealed interface Held {
    /** The phone has the file. */
    class Yes(val file: File) : Held

    /** It was asked for since the last time the phone was in touch with the server, and could not be had. */
    data object CannotBeHad : Held

    /** Nobody has asked yet, or the connection may have come back since. */
    data object NotYet : Held
}

/**
 * The pictures of the inspirations, kept on the phone: a file for each picture and size, in a folder of the app's
 * own. A picture never changes under its id (the server says so), so a file that is there stays right for good.
 *
 * - A picture is fetched through [Api], so with the access token, streamed to a file under a temporary name and
 *   moved into place when it is whole: what stands under its real name is always a complete download.
 * - Two askers for one picture wait for one fetch. The fetch belongs to the store, not to whoever asked first:
 *   the first asker going away (a card scrolled off) does not take the second one's picture with it.
 * - What could not be had is not asked for again until a load has shown that the server answers; the screens
 *   would otherwise ask again at every turn of the phone or scroll of the board.
 * - [forget] removes the folder, cancels what is on its way, and makes whatever still comes back keep nothing.
 *
 * Nothing here is written to the log: not a picture's address, not an id.
 *
 * @param scope where the fetches run; they are not tied to whoever asks
 */
class PictureStore(
    private val api: () -> Api,
    private val folder: File,
    private val scope: CoroutineScope,
    /** How much of one picture is taken; more is refused. */
    private val mostBytes: Long = MOST_BYTES,
    /** How many pictures are fetched at the same time in the background. */
    private val together: Int = TOGETHER,
) {

    private val lock = Any()

    /** The fetches on their way, by file name. Guarded by [lock]. */
    private val onTheWay = HashMap<String, Deferred<File?>>()

    /** The temporary files being written, by name; not to be taken for leftovers. Guarded by [lock]. */
    private val written = HashSet<String>()

    /** What could not be had, by file name, until the next load. Guarded by [lock]. */
    private val cannotBeHad = HashSet<String>()

    /** How often everything was removed; what a fetch started before that finds changed keeps nothing. Guarded by [lock]. */
    private var generation = 0

    /** Whether the last fetch failed for want of a connection (or a session), as opposed to the server not having the picture. */
    @Volatile
    private var noConnection = false

    /** The server is asked for a few pictures at a time, whoever asks: the board, a card, an idea with ten of them. */
    private val turns = Semaphore(together)

    private val busy = AtomicInteger(0)

    private val arrivals = MutableStateFlow(0)

    /**
     * Counts what a screen that waited for a picture would like to hear of: a file that landed, and a load that
     * made what could not be had worth asking for again.
     */
    val arrived: StateFlow<Int> = arrivals.asStateFlow()

    /** True while nothing is being fetched. */
    val quiet: Boolean get() = busy.get() == 0

    /** What is known without asking: the file when it is there. */
    fun look(inspirationId: String, pictureId: String, size: PictureSize): Held {
        val name = nameOf(inspirationId, pictureId, size) ?: return Held.CannotBeHad
        val file = File(folder, name)
        return when {
            file.isFile -> Held.Yes(file)
            synchronized(lock) { name in cannotBeHad } -> Held.CannotBeHad
            else -> Held.NotYet
        }
    }

    /** Whether the picture was asked for since the last load and could not be had. Looks at no file: for the main thread. */
    fun couldNotBeHad(inspirationId: String, pictureId: String, size: PictureSize): Boolean {
        val name = nameOf(inspirationId, pictureId, size) ?: return true
        return synchronized(lock) { name in cannotBeHad }
    }

    /**
     * The file is not a picture the phone can read: it is removed, so that it is not tried again, and the picture counts
     * as one that cannot be had until the next load.
     */
    fun refuse(inspirationId: String, pictureId: String, size: PictureSize) {
        val name = nameOf(inspirationId, pictureId, size) ?: return
        synchronized(lock) {
            File(folder, name).delete()
            cannotBeHad += name
        }
    }

    /**
     * The picture as a file on the phone, fetched when it is not there; null when it cannot be had (no connection, the
     * server does not have it, it is too large). Only the first of several askers for the same picture causes a request.
     */
    suspend fun file(inspirationId: String, pictureId: String, size: PictureSize): File? {
        val name = nameOf(inspirationId, pictureId, size) ?: return null
        val file = File(folder, name)
        if (file.isFile) {
            return file
        }
        val fetching = synchronized(lock) {
            if (name in cannotBeHad) {
                return null
            }
            onTheWay.getOrPut(name) {
                val since = generation
                busy.incrementAndGet()
                scope.async(Dispatchers.IO) { fetch(inspirationId, pictureId, size, name, since) }.also { started ->
                    started.invokeOnCompletion {
                        synchronized(lock) { onTheWay.remove(name, started) }
                        busy.decrementAndGet()
                    }
                }
            }
        }
        return try {
            fetching.await()
        } catch (cancelled: CancellationException) {
            // Either the asker is gone, which is passed on, or the fetch was called off by [forget]: nothing to show.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private suspend fun fetch(inspirationId: String, pictureId: String, size: PictureSize, name: String, since: Int): File? {
        val temporary = File(folder, "$name.${UUID.randomUUID()}$PART")
        val started = synchronized(lock) {
            if (generation != since) {
                false
            } else {
                folder.mkdirs()
                written += temporary.name
                true
            }
        }
        if (!started) {
            return null
        }
        try {
            turns.withPermit {
                api().fetchTo("/api/inspirations/$inspirationId/pictures/$pictureId?size=${size.parameter}", temporary, mostBytes)
            }
            val file = File(folder, name)
            val kept = synchronized(lock) {
                // A sign-out while the picture was on its way: what came back is nobody's any more.
                generation == since && temporary.renameTo(file)
            }
            if (kept) {
                noConnection = false
                arrivals.update { it + 1 }
                return file
            }
            synchronized(lock) { if (generation == since) cannotBeHad += name }
            return null
        } catch (failure: ApiFailure) {
            noConnection = failure is ApiFailure.Unreachable || failure is ApiFailure.SignedOut
            synchronized(lock) { if (generation == since) cannotBeHad += name }
            return null
        } finally {
            temporary.delete()
            synchronized(lock) { written -= temporary.name }
        }
    }

    /**
     * What the phone does after a load of the planner: removes the files of pictures the copy no longer has, and fetches
     * the small version of what the board shows (the first picture of each idea) where the phone lacks it, a few at a
     * time. Without a connection it stops at the first picture that cannot be reached, and the rest is not asked for.
     * It does not wait: the work goes on in the store's own scope.
     */
    fun afterLoad(ideas: List<Inspiration>) {
        synchronized(lock) {
            cannotBeHad.clear()
        }
        // What waits on the board's faces is worth asking again: the server answered.
        arrivals.update { it + 1 }
        val since = synchronized(lock) { generation }
        busy.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                removeWhatIsGone(ideas)
                fetchBoard(ideas, since)
            } finally {
                busy.decrementAndGet()
            }
        }
    }

    private suspend fun fetchBoard(ideas: List<Inspiration>, since: Int) {
        val lacking = ideas.mapNotNull { idea -> idea.pictures.firstOrNull()?.let { idea.id to it.id } }
            .filter { (idea, picture) -> look(idea, picture, PictureSize.THUMBNAIL) !is Held.Yes }
        if (lacking.isEmpty()) {
            return
        }
        val beforeTheRest = Semaphore(together)
        val stopped = MutableStateFlow(false)
        coroutineScope {
            for ((idea, picture) in lacking) {
                launch {
                    beforeTheRest.withPermit {
                        if (stopped.value || synchronized(lock) { generation } != since) {
                            return@withPermit
                        }
                        if (file(idea, picture, PictureSize.THUMBNAIL) == null && noConnection) {
                            // No connection: the rest would only fail the same way.
                            stopped.value = true
                        }
                    }
                }
            }
        }
    }

    /** Removes what is no longer in the copy: the files of pictures, and the half-written ones nobody is writing. */
    private fun removeWhatIsGone(ideas: List<Inspiration>) {
        val wanted = ideas.flatMap { idea -> idea.pictures.flatMap { picture -> PictureSize.entries.mapNotNull { nameOf(idea.id, picture.id, it) } } }.toSet()
        val files = folder.listFiles() ?: return
        for (file in files) {
            val leftover = file.name.endsWith(PART) && synchronized(lock) { file.name !in written }
            if (leftover || (!file.name.endsWith(PART) && file.name !in wanted)) {
                file.delete()
            }
        }
    }

    /**
     * Removes everything from the phone, for a sign-out: the files, what is on its way, what is known. A fetch that
     * comes back later keeps nothing. It does not fail: whatever cannot be removed is left, and the sign-out goes on.
     */
    fun forget() {
        val cancelled: List<Deferred<File?>>
        synchronized(lock) {
            generation += 1
            cannotBeHad.clear()
            cancelled = onTheWay.values.toList()
            onTheWay.clear()
        }
        for (fetch in cancelled) {
            fetch.cancel()
        }
        synchronized(lock) {
            folder.deleteRecursively()
        }
        arrivals.update { it + 1 }
    }

    /** A picture of an inspiration has a name only when its ids are the kind the server makes, so that no id can name another folder. */
    private fun nameOf(inspirationId: String, pictureId: String, size: PictureSize): String? =
        if (SAFE.matches(inspirationId) && SAFE.matches(pictureId)) "$inspirationId-$pictureId-${size.name.lowercase()}" else null

    companion object {
        /** A picture larger than this is refused; the server takes 15 MiB from a person (see ShareLimits). */
        const val MOST_BYTES = 20L * 1024 * 1024

        /** How many small pictures are fetched at once after a load. */
        const val TOGETHER = 3

        private const val PART = ".part"
        private val SAFE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
