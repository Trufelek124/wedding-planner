package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What is shared to the planner from another app: kept on the phone, with its pictures, and saved
 * as an inspiration when the server can be reached. A stand-in server, a real database and folder.
 */
@RunWith(RobolectricTestRunner::class)
class SharesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = StandInServer()
    private val clock = Clock.fixed(Instant.parse("2026-11-18T13:05:00Z"), ZoneId.of("Europe/Warsaw"))
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tokens = object : Api.AccessTokens {
        override suspend fun current(): String? = "access-1"
        override suspend fun renewed(refused: String): String? = null
    }
    private val api = Api(server.engine, ServerAddress.parse("https://planner.example"), tokens)
    private lateinit var database: PlannerDatabase
    private lateinit var planner: PlannerRepository
    private lateinit var shares: File

    private fun open(): PlannerDatabase =
        Room.databaseBuilder(context, PlannerDatabase::class.java, folder.root.resolve("planner.db").path).build()

    @Before
    fun aPhoneThatLoaded() = runBlocking {
        shares = folder.root.resolve("shares")
        database = open()
        planner = PlannerRepository({ api }, database, clock, shares)
        server.planner(boards = listOf(StandIn.board("b1", "Dress"), StandIn.board("b2", "Flowers")))
        assertEquals(Load.LOADED, planner.sync())
        server.calls.clear()
    }

    @After
    fun close() {
        database.close()
    }

    private suspend fun held(): Planner = planner.planner.first()!!

    private fun sent(): List<String> = server.calls.map { it.route }.filterNot { it.startsWith("GET") }

    private fun picture(text: String): () -> InputStream? = { ByteArrayInputStream(text.toByteArray()) }

    private fun filesKept(): List<File> = shares.walkTopDown().filter { it.isFile }.toList()

    private suspend fun kept(share: Share, pictures: List<() -> InputStream?> = emptyList()): String =
        (planner.keepShare(share, pictures) as Kept.Yes).inspirationId

    // ---------------------------------------------------------------- the boards

    @Test
    fun `the boards are loaded with the planner, in the server's order`() = runBlocking {
        assertEquals(listOf(Board("b1", "Dress"), Board("b2", "Flowers")), held().boards)
    }

    // ---------------------------------------------------------------- a link

    @Test
    fun `a shared link waits, and is saved as an inspiration with the id the phone gave it`() = runBlocking {
        val id = kept(Share(link = "https://www.instagram.com/p/Cabc123/", boardId = "b1", note = "Lace sleeves"))

        assertEquals(listOf(WaitingShare(id, "https://www.instagram.com/p/Cabc123/", 0, null, "b1")), held().shares)
        assertEquals(1, planner.stillToSend())
        assertTrue(server.calls.isEmpty())

        server.json("POST /api/inspirations", StandIn.inspiration(id, url = "https://www.instagram.com/p/Cabc123/", boardId = "b1", note = "Lace sleeves"))
        planner.sync()

        assertEquals(
            Json.parseToJsonElement(
                """{"id":"$id","url":"https://www.instagram.com/p/Cabc123/","boardId":"b1","title":"","note":"Lace sleeves","tags":[],"planItemId":null,"vendorId":null}""",
            ),
            Json.parseToJsonElement(server.routes("POST /api/inspirations").single().body),
        )
        assertEquals(listOf("POST /api/inspirations"), sent())
        assertTrue(held().shares.isEmpty())
        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `without a connection a share stays, also after the app was closed, and is saved once later`() = runBlocking {
        server.plannerUnreachable()
        val id = kept(Share(link = "https://vm.tiktok.com/ZMabc/", boardId = null, note = ""))
        assertEquals(Load.UNREACHABLE, planner.sync())

        database.close()
        database = open()
        planner = PlannerRepository({ api }, database, clock, shares)
        assertEquals(listOf(WaitingShare(id, "https://vm.tiktok.com/ZMabc/", 0, null)), held().shares)

        // The first try arrives and its answer is lost; the second is answered with what is already there.
        server.planner()
        planner.sync()
        server.json("POST /api/inspirations", StandIn.inspiration(id, url = "https://vm.tiktok.com/ZMabc/"))
        planner.sync()

        val ids = server.routes("POST /api/inspirations").map { Json.parseToJsonElement(it.body).toString().substringAfter("\"id\":\"").substringBefore("\"") }
        assertEquals(setOf(id), ids.toSet())
        assertTrue(held().shares.isEmpty())
    }

    // ---------------------------------------------------------------- pictures

    @Test
    fun `shared pictures are copied to the phone, sent after the inspiration itself, and removed once they are with the server`() = runBlocking {
        val id = kept(Share(link = null, boardId = "b2", note = "From the florist"), listOf(picture("first-picture"), picture("second-picture")))

        assertEquals(listOf("first-picture", "second-picture"), filesKept().sortedBy { it.name }.map { it.readText() })
        assertEquals(listOf(WaitingShare(id, null, 2, null, "b2")), held().shares)

        server.json("POST /api/inspirations", StandIn.inspiration(id, boardId = "b2", note = "From the florist"))
        server.json("POST /api/inspirations/$id/pictures", StandIn.inspiration(id, boardId = "b2", pictures = 2))
        planner.sync()

        assertEquals(listOf("POST /api/inspirations", "POST /api/inspirations/$id/pictures"), sent())
        val upload = server.routes("POST /api/inspirations/$id/pictures").single()
        assertTrue(upload.contentType!!.startsWith("multipart/form-data"))
        // Both pictures, each as a part called "files", in the order they were shared.
        assertEquals(2, Regex("name=\"?files\"?").findAll(upload.body).count())
        assertTrue(upload.body.indexOf("first-picture") in 0 until upload.body.indexOf("second-picture"))
        assertEquals("Bearer access-1", upload.authorization)
        assertTrue(filesKept().isEmpty())
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `pictures the server already holds, because only its answer was lost, are not sent again`() = runBlocking {
        val id = kept(Share(link = null, boardId = null, note = ""), listOf(picture("one"), picture("two")))
        // Last time the pictures arrived and the answer did not. Now the server says what the inspiration holds.
        server.json("POST /api/inspirations", StandIn.inspiration(id, pictures = 2))

        planner.sync()

        assertEquals(listOf("POST /api/inspirations"), sent())
        assertTrue(held().shares.isEmpty())
        assertTrue(filesKept().isEmpty())
    }

    @Test
    fun `a picture the server fetched from a link by itself does not count as one that was sent`() = runBlocking {
        val id = kept(Share(link = null, boardId = null, note = ""), listOf(picture("one")))
        server.json("POST /api/inspirations", StandIn.inspiration(id, previews = 1))
        server.json("POST /api/inspirations/$id/pictures", StandIn.inspiration(id, pictures = 1, previews = 1))

        planner.sync()

        assertEquals(listOf("POST /api/inspirations", "POST /api/inspirations/$id/pictures"), sent())
    }

    @Test
    fun `pictures that could not be sent for lack of a connection stay, and go out the next time`() = runBlocking {
        val id = kept(Share(link = null, boardId = null, note = ""), listOf(picture("one")))
        server.json("POST /api/inspirations", StandIn.inspiration(id))
        // The inspiration is saved; the connection is gone before the picture went out.
        planner.sync()
        assertEquals(1, filesKept().size)
        assertEquals(1, held().shares.size)

        server.json("POST /api/inspirations/$id/pictures", StandIn.inspiration(id, pictures = 1))
        planner.sync()

        // Tried twice: once into the void, once with an answer.
        assertEquals(2, server.routes("POST /api/inspirations/$id/pictures").size)
        assertTrue(held().shares.isEmpty())
        assertTrue(filesKept().isEmpty())
    }

    // ---------------------------------------------------------------- what the server does not take

    @Test
    fun `a share the server refuses says why and is not tried again, until it is let go of`() = runBlocking {
        val id = kept(Share(link = "https://www.instagram.com/p/Cabc123/", boardId = "b9", note = ""))
        server.problem("POST /api/inspirations", 400, "bad-request", ""","fieldProblems":[{"field":"boardId","message":"Choose one of your boards."}]""")

        planner.sync()

        assertEquals(listOf(WaitingShare(id, "https://www.instagram.com/p/Cabc123/", 0, "Choose one of your boards.", "b9")), held().shares)
        assertEquals(0, planner.stillToSend())
        server.calls.clear()
        planner.sync()
        assertTrue(sent().isEmpty())

        planner.letGoOfShare(id)
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `pictures the server refuses leave no empty card behind - the inspiration is taken back and the share says why`() = runBlocking {
        val id = kept(Share(link = null, boardId = null, note = ""), listOf(picture("not really a picture")))
        server.json("POST /api/inspirations", StandIn.inspiration(id))
        server.problem("POST /api/inspirations/$id/pictures", 400, "bad-request", ""","fieldProblems":[{"field":"files","message":"0 is not a picture this planner can show."}]""")
        server.nothing("DELETE /api/inspirations/$id")

        planner.sync()

        assertEquals(listOf("POST /api/inspirations", "POST /api/inspirations/$id/pictures", "DELETE /api/inspirations/$id"), sent())
        assertEquals("0 is not a picture this planner can show.", held().shares.single().trouble)

        planner.letGoOfShare(id)
        assertTrue(filesKept().isEmpty())
    }

    @Test
    fun `an inspiration someone deleted before its pictures arrived is let be`() = runBlocking {
        val id = kept(Share(link = null, boardId = null, note = ""), listOf(picture("one")))
        server.json("POST /api/inspirations", StandIn.inspiration(id))
        server.problem("POST /api/inspirations/$id/pictures", 404, "not-found")

        planner.sync()

        assertTrue(held().shares.isEmpty())
        assertTrue(filesKept().isEmpty())
    }

    @Test
    fun `a server in trouble does not lose the share - it is tried again`() = runBlocking {
        val id = kept(Share(link = "https://www.instagram.com/p/Cabc123/", boardId = null, note = ""))
        server.problem("POST /api/inspirations", 500, "something-went-wrong")
        planner.sync()
        assertNull(held().shares.single().trouble)
        assertEquals(1, planner.stillToSend())

        server.json("POST /api/inspirations", StandIn.inspiration(id, url = "https://www.instagram.com/p/Cabc123/"))
        planner.sync()
        assertTrue(held().shares.isEmpty())
    }

    // ---------------------------------------------------------------- what the phone does not take

    @Test
    fun `more pictures than an inspiration holds are not kept at all`() = runBlocking {
        val result = planner.keepShare(Share(null, null, ""), List(11) { picture("picture $it") })

        assertEquals(Kept.TooMany, result)
        assertTrue(filesKept().isEmpty())
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `a picture larger than the server takes is not kept, and neither are the ones shared with it`() = runBlocking {
        val large: () -> InputStream? = {
            object : InputStream() {
                private var left = 15L * 1024 * 1024 + 1
                override fun read(): Int = if (left-- > 0) 0 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (left <= 0) return -1
                    val read = minOf(length.toLong(), left).toInt()
                    left -= read
                    return read
                }
            }
        }

        val result = planner.keepShare(Share(null, null, ""), listOf(picture("small"), large))

        assertEquals(Kept.TooLarge, result)
        assertTrue(filesKept().isEmpty())
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `a picture the other app no longer lends is said to be unreadable`() = runBlocking {
        assertEquals(Kept.Unreadable, planner.keepShare(Share(null, null, ""), listOf(picture("one"), { null })))
        assertEquals(Kept.Unreadable, planner.keepShare(Share(null, null, ""), listOf({ throw IOException("gone") })))
        assertEquals(Kept.Unreadable, planner.keepShare(Share(null, null, ""), listOf({ throw SecurityException("not yours") })))

        assertTrue(filesKept().isEmpty())
        assertTrue(held().shares.isEmpty())
    }

    // ---------------------------------------------------------------- signing out

    @Test
    fun `signing out removes the waiting shares and their pictures`() = runBlocking {
        kept(Share(link = "https://www.instagram.com/p/Csecret/", boardId = null, note = "For the surprise"), emptyList())
        kept(Share(link = null, boardId = null, note = ""), listOf(picture("a-picture-nobody-else-should-see")))

        planner.forget()

        assertNull(planner.planner.first())
        val onDisk = folder.root.walkTopDown().filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("Csecret"))
        assertFalse(onDisk.contains("For the surprise"))
        assertFalse(onDisk.contains("a-picture-nobody-else-should-see"))
        server.planner()
        planner.sync()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun `a waiting share counts as something a sign-out would lose`() = runBlocking {
        kept(Share(link = "https://www.instagram.com/p/Cabc123/", boardId = null, note = ""))

        assertEquals(1, held().unsent)
    }
}
