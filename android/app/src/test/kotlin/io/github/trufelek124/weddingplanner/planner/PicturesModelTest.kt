package io.github.trufelek124.weddingplanner.planner

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.api.respondPicture
import io.github.trufelek124.weddingplanner.api.respondText
import io.github.trufelek124.weddingplanner.data.Decoded
import io.github.trufelek124.weddingplanner.data.Held
import io.github.trufelek124.weddingplanner.data.PictureDecoding
import io.github.trufelek124.weddingplanner.data.PictureSize
import io.github.trufelek124.weddingplanner.data.PictureStore
import io.github.trufelek124.weddingplanner.data.TestPictures
import io.github.trufelek124.weddingplanner.data.WaitingShare
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** What the screens get of the pictures: decoded for their place, off the main thread, and kept for a while. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PicturesModelTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val server = StandInServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val api = Api(
        server.engine,
        ServerAddress.parse("https://planner.example"),
        object : Api.AccessTokens {
            override suspend fun current(): String? = "access-1"

            override suspend fun renewed(refused: String): String? = null
        },
    )
    private val store by lazy { PictureStore({ api }, File(temporary.root, "pictures"), scope) }
    private val threads = CopyOnWriteArrayList<Thread>()
    private val decodes = AtomicInteger(0)

    private fun modelOf(shareFile: (WaitingShare, Int) -> File? = { _, _ -> null }) = PicturesModel(
        store, shareFile,
        decode = { file, width, height ->
            threads += Thread.currentThread()
            decodes.incrementAndGet()
            PictureDecoding.decode(file, width, height)
        },
    )

    @After
    fun stop() {
        scope.cancel()
    }

    @Test
    fun `a picture is decoded for its place and off the main thread, and only once while it is kept`() = runBlocking {
        server.answer("GET /api/inspirations/i1/pictures/a") { respondPicture(TestPictures.jpeg(1600, 1200)) }
        val model = modelOf()

        val shown = model.picture("i1", "a", PictureSize.THUMBNAIL, 400, 300)
        val again = model.picture("i1", "a", PictureSize.THUMBNAIL, 400, 300)

        assertEquals(400, (shown as Shown.Ready).bitmap.width)
        assertTrue(again is Shown.Ready)
        assertEquals(1, decodes.get())
        assertTrue("decoded on the thread that asked", threads.none { it === Thread.currentThread() })
        assertTrue(model.quick("i1", "a", PictureSize.THUMBNAIL, 400, 300) is Shown.Ready)
        // Another place is another size to read it at.
        assertEquals(Shown.Loading, model.quick("i1", "a", PictureSize.THUMBNAIL, 200, 150))
    }

    @Test
    fun `letting go of the model empties what it keeps in memory`() = runBlocking {
        server.answer("GET /api/inspirations/i1/pictures/a") { respondPicture(TestPictures.jpeg(800, 600)) }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val model = ViewModelProvider.create(owner, viewModelFactory { initializer { modelOf() } })[PicturesModel::class.java]
        model.picture("i1", "a", PictureSize.THUMBNAIL, 400, 300)
        assertTrue(model.heldBytes > 0)

        owner.viewModelStore.clear()

        assertEquals(0, model.heldBytes)
    }

    @Test
    fun `a file that is no picture is refused and removed, and says so at once the next time`() = runBlocking {
        server.answer("GET /api/inspirations/i1/pictures/a") { respondText("<html>sorry</html>", 200) }
        val model = modelOf()

        assertEquals(Shown.Missing, model.picture("i1", "a", PictureSize.THUMBNAIL, 400, 300))

        assertTrue(store.look("i1", "a", PictureSize.THUMBNAIL) is Held.CannotBeHad)
        assertEquals(emptyList<String>(), File(temporary.root, "pictures").list()?.toList().orEmpty())
        assertEquals(Shown.Missing, model.quick("i1", "a", PictureSize.THUMBNAIL, 400, 300))
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `a picture that cannot be had is missing, and what the phone holds is shown without asking`() = runBlocking {
        val model = modelOf()
        assertEquals(Shown.Loading, model.held("i1", "a", PictureSize.THUMBNAIL, 400, 300))

        server.down = true
        assertEquals(Shown.Missing, model.picture("i1", "a", PictureSize.FULL, 400, 300))

        server.down = false
        server.answer("GET /api/inspirations/i1/pictures/b") { respondPicture(TestPictures.jpeg(800, 600)) }
        store.file("i1", "b", PictureSize.THUMBNAIL)
        server.calls.clear()
        assertTrue(model.held("i1", "b", PictureSize.THUMBNAIL, 400, 300) is Shown.Ready)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `the picture of a share comes from the phone's own copy, which is never removed even when it cannot be read`() = runBlocking {
        val good = File(temporary.root, "good").also { it.writeBytes(TestPictures.jpeg(800, 600)) }
        val bad = File(temporary.root, "bad").also { it.writeBytes("not a picture".toByteArray()) }
        val share = WaitingShare("s1", null, 2, null)
        val model = modelOf { _, number -> if (number == 0) good else bad }

        val first = model.ofShare(share, 0, 400, 300)
        val second = model.ofShare(share, 1, 400, 300)
        val third = model.ofShare(share, 2, 400, 300)

        assertTrue(first is Shown.Ready)
        assertEquals(Shown.Missing, second)
        assertTrue(bad.exists())
        assertNotEquals(Shown.Loading, third)
        assertTrue(server.calls.isEmpty())
    }
}
