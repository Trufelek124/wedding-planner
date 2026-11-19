package io.github.trufelek124.weddingplanner.planner

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import io.github.trufelek124.weddingplanner.data.Decoded
import io.github.trufelek124.weddingplanner.data.Held
import io.github.trufelek124.weddingplanner.data.PictureDecoding
import io.github.trufelek124.weddingplanner.data.PictureSize
import io.github.trufelek124.weddingplanner.data.PictureStore
import io.github.trufelek124.weddingplanner.data.WaitingShare
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** What a place for a picture shows. */
sealed interface Shown {
    /** The picture is on its way: the place keeps its size and shows nothing. */
    data object Loading : Shown

    class Ready(val bitmap: Bitmap) : Shown

    /** The phone does not hold it and cannot get it: the place says so in words. */
    data object Missing : Shown
}

/**
 * The pictures as the screens of a signed-in person need them: decoded, no larger than the place they are shown in,
 * and kept for a while so that scrolling the board back and forth does not decode again. It lives with the
 * signed-in person's models and is let go of with them: signing out empties the memory it holds.
 *
 * @param decode reads a file for a place of the given pixels; runs on [decoding], never on the main thread
 * @param shareFile the phone's own copy of a picture of a share that waits to be sent
 */
class PicturesModel(
    private val store: PictureStore,
    private val shareFile: (WaitingShare, Int) -> File?,
    private val decode: (File, Int, Int) -> Decoded = PictureDecoding::decode,
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
    memoryBytes: Int = defaultMemory(),
) : ViewModel() {

    private val decoded = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Counts what a place that is waiting would like to hear of: a picture that arrived, a load that made asking again worthwhile. */
    val arrived: StateFlow<Int> get() = store.arrived

    /** What is known at once, without a file or the network: for the first frame of a place. */
    fun quick(inspirationId: String, pictureId: String, size: PictureSize, width: Int, height: Int): Shown =
        decoded[key(inspirationId, pictureId, size, width, height)]?.let { Shown.Ready(it) }
            ?: if (store.couldNotBeHad(inspirationId, pictureId, size)) Shown.Missing else Shown.Loading

    /** What is on the phone already, for a place to show while the better version comes: never fetches. */
    suspend fun held(inspirationId: String, pictureId: String, size: PictureSize, width: Int, height: Int): Shown {
        val file = (store.look(inspirationId, pictureId, size) as? Held.Yes)?.file ?: return Shown.Loading
        return read(key(inspirationId, pictureId, size, width, height), file, width, height) { store.refuse(inspirationId, pictureId, size) }
    }

    /** The picture of an idea, fetched if the phone lacks it. */
    suspend fun picture(inspirationId: String, pictureId: String, size: PictureSize, width: Int, height: Int): Shown {
        val file = store.file(inspirationId, pictureId, size) ?: return Shown.Missing
        return read(key(inspirationId, pictureId, size, width, height), file, width, height) { store.refuse(inspirationId, pictureId, size) }
    }

    /** What is known at once of a share's picture. */
    fun quickOfShare(share: WaitingShare, number: Int, width: Int, height: Int): Shown =
        decoded[shareKey(share, number, width, height)]?.let { Shown.Ready(it) } ?: Shown.Loading

    /** The phone's own copy of a picture of a share that waits to be sent. Never removed here: it is the only one there is. */
    suspend fun ofShare(share: WaitingShare, number: Int, width: Int, height: Int): Shown {
        val file = withContext(Dispatchers.IO) { shareFile(share, number) } ?: return Shown.Missing
        return read(shareKey(share, number, width, height), file, width, height) {}
    }

    private suspend fun read(key: String, file: File, width: Int, height: Int, notAPicture: () -> Unit): Shown {
        decoded[key]?.let { return Shown.Ready(it) }
        return when (val result = withContext(decoding) { decode(file, width, height) }) {
            is Decoded.Ok -> {
                decoded.put(key, result.bitmap)
                Shown.Ready(result.bitmap)
            }
            Decoded.NotAPicture -> {
                withContext(Dispatchers.IO) { notAPicture() }
                Shown.Missing
            }
            Decoded.NoRoom -> Shown.Missing
        }
    }

    private fun key(inspirationId: String, pictureId: String, size: PictureSize, width: Int, height: Int) =
        "$inspirationId/$pictureId/${size.name}@${width}x$height"

    private fun shareKey(share: WaitingShare, number: Int, width: Int, height: Int) = "share/${share.id}/$number@${width}x$height"

    override fun onCleared() {
        decoded.evictAll()
    }

    /** How many bytes of decoded pictures are held: for tests. */
    val heldBytes: Int get() = decoded.size()

    companion object {
        /** A sixteenth of what the app may use, and no more than 24 MiB: a handful of cards' pictures. */
        fun defaultMemory(): Int = (Runtime.getRuntime().maxMemory() / 16).coerceIn(4L * 1024 * 1024, 24L * 1024 * 1024).toInt()
    }
}
