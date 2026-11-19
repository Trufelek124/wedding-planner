package io.github.trufelek124.weddingplanner.data

import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.api.respondPicture
import io.github.trufelek124.weddingplanner.api.respondProblem
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The pictures kept on the phone: fetched once and then served from the phone, one request for two askers, never a
 * half-fetched file under a name that is shown, and nothing left after a sign-out. The pictures are made-up bytes:
 * the store does not read them.
 */
class PictureStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val server = StandInServer()
    private var newer: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val api = Api(
        server.engine,
        ServerAddress.parse("https://planner.example"),
        object : Api.AccessTokens {
            override suspend fun current(): String? = "access-1"

            override suspend fun renewed(refused: String): String? = newer
        },
    )

    private val folder get() = File(temporary.root, "pictures")

    private fun storeOf(mostBytes: Long = PictureStore.MOST_BYTES, together: Int = PictureStore.TOGETHER) =
        PictureStore({ api }, folder, scope, mostBytes, together)

    @After
    fun stop() {
        scope.cancel()
    }

    private val small = ByteArray(5_000) { (it % 200).toByte() }
    private val big = ByteArray(80_000) { (it % 199).toByte() }

    private fun routeOf(idea: String, picture: String) = "GET /api/inspirations/$idea/pictures/$picture"

    /** Every picture of every idea answers with these bytes: the small ones for the thumbnail, the large for the rest. */
    private fun everythingAnswers() {
        for (idea in listOf("i1", "i2", "i3", "i4", "i5", "i6")) {
            for (picture in listOf("a", "b", "c")) {
                server.answer(routeOf(idea, picture)) { request -> respondPicture(if (request.url.parameters["size"] == "THUMBNAIL") small else big) }
            }
        }
    }

    private fun idea(id: String, vararg pictures: String) = Inspiration(
        id, null, InspirationSource.PHOTO, "", "", false, "", emptyList(), null, null, null, null,
        pictures.map { Picture(it, 800, 600, false) },
    )

    private fun waitUntil(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!what()) {
            check(System.currentTimeMillis() < until) { "it did not come about" }
            Thread.yield()
        }
    }

    @Test
    fun `a picture is fetched once with the token and then served from the phone, also when the server is gone`() = runBlocking {
        everythingAnswers()
        val store = storeOf()

        val first = store.file("i1", "a", PictureSize.THUMBNAIL)!!
        server.down = true
        val again = store.file("i1", "a", PictureSize.THUMBNAIL)

        assertArrayEquals(small, first.readBytes())
        assertEquals(first, again)
        assertEquals(1, server.calls.size)
        assertEquals("Bearer access-1", server.calls.single().authorization)
        assertEquals("size=THUMBNAIL", server.calls.single().query)
        // A picture of the other size is another file.
        assertTrue(store.look("i1", "a", PictureSize.FULL) is Held.NotYet)
        assertTrue(store.look("i1", "a", PictureSize.THUMBNAIL) is Held.Yes)
    }

    @Test
    fun `two askers for one picture share one request`() = runBlocking {
        val letGo = CompletableDeferred<Unit>()
        server.answer(routeOf("i1", "a")) {
            letGo.await()
            respondPicture(small)
        }
        val store = storeOf()

        val first = async(Dispatchers.Default) { store.file("i1", "a", PictureSize.THUMBNAIL) }
        waitUntil { server.calls.size == 1 }
        // The second asker runs as far as waiting before the answer is let go.
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.file("i1", "a", PictureSize.THUMBNAIL) }
        letGo.complete(Unit)

        val files = listOf(first, second).awaitAll()
        assertNotNull(files[0])
        assertEquals(files[0], files[1])
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `an asker that gives up does not take the picture from the other`() = runBlocking {
        val letGo = CompletableDeferred<Unit>()
        server.answer(routeOf("i1", "a")) {
            letGo.await()
            respondPicture(small)
        }
        val store = storeOf()
        val gone = launch(start = CoroutineStart.UNDISPATCHED) { store.file("i1", "a", PictureSize.THUMBNAIL) }
        val stays = async(start = CoroutineStart.UNDISPATCHED) { store.file("i1", "a", PictureSize.THUMBNAIL) }

        gone.cancel()
        letGo.complete(Unit)

        assertNotNull(stays.await())
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `a refused token is renewed once and the picture comes`() = runBlocking {
        newer = "access-2"
        server.answer(routeOf("i1", "a")) { request ->
            if (request.headers["Authorization"] == "Bearer access-2") respondPicture(small) else respondProblem(401, "unauthenticated")
        }

        val file = storeOf().file("i1", "a", PictureSize.THUMBNAIL)

        assertArrayEquals(small, file!!.readBytes())
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), server.calls.map { it.authorization })
    }

    @Test
    fun `a fetch that fails leaves no file, and is not asked again until a load shows the server answers`() = runBlocking {
        server.problem(routeOf("i1", "a"), 404, "not-found")
        val store = storeOf()

        assertNull(store.file("i1", "a", PictureSize.THUMBNAIL))
        assertNull(store.file("i1", "a", PictureSize.THUMBNAIL))

        assertEquals(1, server.calls.size)
        assertEquals(emptyList<String>(), folder.list()?.toList().orEmpty())
        assertTrue(store.look("i1", "a", PictureSize.THUMBNAIL) is Held.CannotBeHad)

        // The next load: the server may have it by now.
        server.answer(routeOf("i1", "a")) { respondPicture(small) }
        store.afterLoad(listOf(idea("i1", "a")))
        waitUntil { store.look("i1", "a", PictureSize.THUMBNAIL) is Held.Yes }
        assertEquals(2, server.calls.size)
    }

    @Test
    fun `a picture larger than the limit is refused and leaves no file`() = runBlocking {
        server.answer(routeOf("i1", "a")) { respondPicture(big) }

        val file = storeOf(mostBytes = 10_000).file("i1", "a", PictureSize.FULL)

        assertNull(file)
        assertEquals(emptyList<String>(), folder.list()?.toList().orEmpty())
    }

    @Test
    fun `what is half fetched is never under the name that is shown, and a fetch that breaks off leaves nothing`() = runBlocking {
        val letGo = CompletableDeferred<Unit>()
        val breaks = CompletableDeferred<Boolean>()
        server.answer(routeOf("i1", "a")) {
            val channel = ByteChannel(autoFlush = true)
            scope.launch {
                channel.writeFully(big, 0, 40_000)
                letGo.await()
                if (breaks.await()) {
                    channel.cancel(java.io.IOException("the connection went"))
                } else {
                    channel.writeFully(big, 40_000, big.size)
                    channel.close()
                }
            }
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }
        val store = storeOf()

        val asking = async(Dispatchers.Default) { store.file("i1", "a", PictureSize.FULL) }
        waitUntil { folder.listFiles().orEmpty().any { it.name.endsWith(".part") && it.length() > 0 } }
        // Half of it is on the disk, and it is not a picture that can be shown.
        assertTrue(store.look("i1", "a", PictureSize.FULL) is Held.NotYet)
        assertEquals(emptyList<String>(), folder.listFiles()!!.map { it.name }.filter { !it.endsWith(".part") })
        letGo.complete(Unit)
        breaks.complete(true)

        assertNull(asking.await())
        assertEquals(emptyList<String>(), folder.list()?.toList().orEmpty())
        assertTrue(store.look("i1", "a", PictureSize.FULL) !is Held.Yes)
    }

    @Test
    fun `a whole fetch is moved into place and no temporary file is left`() = runBlocking {
        everythingAnswers()

        val file = storeOf().file("i1", "a", PictureSize.FULL)!!

        assertEquals(listOf("i1-a-full"), folder.list()!!.toList())
        assertArrayEquals(big, file.readBytes())
    }

    @Test
    fun `after a load the pictures of ideas that are gone are removed, and those that stay are not`() = runBlocking {
        everythingAnswers()
        val store = storeOf()
        for (idea in listOf("i1", "i2")) {
            store.file(idea, "a", PictureSize.THUMBNAIL)
            store.file(idea, "a", PictureSize.FULL)
        }
        // A leftover of a fetch that was cut off by the phone's being switched off.
        File(folder, "i1-a-full.dead.part").writeBytes(small)
        assertEquals(5, folder.list()!!.size)
        server.calls.clear()

        store.afterLoad(listOf(idea("i2", "a")))
        waitUntil { store.quiet }

        assertEquals(setOf("i2-a-thumbnail", "i2-a-full"), folder.list()!!.toSet())
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `after a load exactly the small pictures the phone lacks are fetched, the first of each idea`() = runBlocking {
        everythingAnswers()
        val store = storeOf()
        // i2's small picture is on the phone already, and i3's only its large one.
        store.file("i2", "a", PictureSize.THUMBNAIL)
        store.file("i3", "a", PictureSize.FULL)
        server.calls.clear()

        store.afterLoad(listOf(idea("i1", "a", "b"), idea("i2", "a", "b"), idea("i3", "a"), idea("i4")))
        waitUntil { store.quiet }

        assertEquals(
            setOf("GET /api/inspirations/i1/pictures/a?size=THUMBNAIL", "GET /api/inspirations/i3/pictures/a?size=THUMBNAIL"),
            server.calls.map { it.route + "?" + it.query }.toSet(),
        )
        assertEquals(2, server.calls.size)
        assertEquals(setOf("i1-a-thumbnail", "i2-a-thumbnail", "i3-a-full", "i3-a-thumbnail"), folder.list()!!.toSet())
    }

    @Test
    fun `the background fetch asks for a few pictures at a time`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val most = AtomicInteger(0)
        for (id in listOf("i1", "i2", "i3", "i4", "i5", "i6")) {
            server.answer(routeOf(id, "a")) {
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                // Held until the others have come, or a moment: whichever is first; nothing is waited for by the clock alone.
                kotlinx.coroutines.yield()
                kotlinx.coroutines.delay(20)
                inFlight.decrementAndGet()
                respondPicture(small)
            }
        }
        val store = storeOf(together = 2)

        store.afterLoad(listOf("i1", "i2", "i3", "i4", "i5", "i6").map { idea(it, "a") })
        waitUntil { store.quiet }

        assertEquals(6, server.calls.size)
        assertTrue("at most two at once, saw ${most.get()}", most.get() <= 2)
    }

    @Test
    fun `without a connection the background fetch stops at the first picture and says nothing`() = runBlocking {
        server.down = true
        val store = storeOf(together = 1)

        store.afterLoad(listOf("i1", "i2", "i3", "i4", "i5").map { idea(it, "a") })
        waitUntil { store.quiet }

        assertEquals(1, server.calls.size)
        assertEquals(emptyList<String>(), folder.list()?.toList().orEmpty())
        // And it works again once the connection is back and a load has shown it.
        server.down = false
        everythingAnswers()
        store.afterLoad(listOf("i1", "i2").map { idea(it, "a") })
        waitUntil { store.quiet }
        assertEquals(setOf("i1-a-thumbnail", "i2-a-thumbnail"), folder.list()!!.toSet())
    }

    @Test
    fun `signing out removes every file`() = runBlocking {
        everythingAnswers()
        val store = storeOf()
        store.file("i1", "a", PictureSize.THUMBNAIL)
        store.file("i2", "b", PictureSize.FULL)
        assertEquals(2, folder.list()!!.size)

        store.forget()

        assertFalse(folder.exists())
        assertTrue(store.look("i1", "a", PictureSize.THUMBNAIL) is Held.NotYet)
    }

    @Test
    fun `a fetch on its way when someone signs out keeps nothing, however late it comes back`() = runBlocking {
        val letGo = CompletableDeferred<Unit>()
        server.answer(routeOf("i1", "a")) {
            try {
                letGo.await()
            } catch (called: kotlinx.coroutines.CancellationException) {
                throw called
            }
            respondPicture(small)
        }
        val store = storeOf()
        val asking = async(Dispatchers.Default) { store.file("i1", "a", PictureSize.THUMBNAIL) }
        waitUntil { server.calls.size == 1 }

        store.forget()
        assertFalse(folder.exists())
        // Whoever signs in next asks for a picture of their own, which makes the folder again; the late one must not land in it.
        server.answer(routeOf("i2", "a")) { respondPicture(small) }
        assertNotNull(store.file("i2", "a", PictureSize.THUMBNAIL))
        letGo.complete(Unit)

        assertNull(asking.await())
        waitUntil { store.quiet }
        assertEquals(listOf("i2-a-thumbnail"), folder.list()!!.toList())
    }

    @Test
    fun `a picture with an id that could name another folder is not asked for`() = runBlocking {
        val store = storeOf()

        assertNull(store.file("../x", "a", PictureSize.FULL))
        assertNull(store.file("i1", "a/../../b", PictureSize.FULL))

        assertTrue(server.calls.isEmpty())
    }
}
