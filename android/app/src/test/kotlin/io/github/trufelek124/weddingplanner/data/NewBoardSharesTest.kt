package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.StandInServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * A share that names a board the planner does not have yet (S09 section 4): the board comes first when the share is
 * sent, there is never a second one, and a name the server does not take costs the share nothing. A living stand-in
 * server that keeps what it is sent, a real database and folder. Every name here is made up.
 */
@RunWith(RobolectricTestRunner::class)
class NewBoardSharesTest {

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
    private lateinit var theirs: LivingPlanner
    private lateinit var shares: File

    @Before
    fun aPhoneThatLoaded() = runBlocking {
        shares = folder.root.resolve("shares")
        database = Room.databaseBuilder(context, PlannerDatabase::class.java, folder.root.resolve("planner.db").path).build()
        planner = PlannerRepository({ api }, database, clock, shares)
        theirs = LivingPlanner(server, entries = emptyList(), boards = listOf(StandIn.board("b1", "Dress"), StandIn.board("b2", "Flowers")))
        assertEquals(Load.LOADED, planner.sync())
        server.calls.clear()
    }

    @After
    fun close() {
        database.close()
    }

    private suspend fun held(): Planner = planner.planner.first()!!

    private fun picture(text: String): () -> InputStream? = { ByteArrayInputStream(text.toByteArray()) }

    private suspend fun kept(newBoard: String?, boardId: String? = null, link: String? = "https://www.instagram.com/p/Cabc123/", note: String = ""): String =
        (planner.keepShare(Share(link = link, boardId = boardId, note = note, newBoard = newBoard), emptyList()) as Kept.Yes).inspirationId

    /** What was asked of the server that is not a plain read, in order. */
    private fun sent(): List<String> = server.calls.map { it.route }.filterNot { it.startsWith("GET") }

    private fun namesOnTheServer(): List<String> = theirs.boards.values.map { it.getValue("name").jsonPrimitive.content }

    private fun boardOfIdeaOnTheServer(id: String): String? = theirs.inspirations.getValue(id).getValue("boardId").let { if (it.toString() == "null") null else it.jsonPrimitive.content }

    // ---------------------------------------------------------------- kept while it waits

    @Test
    fun `a share keeps the name of the new board while it waits, and counts as waiting`() = runBlocking {
        val id = kept("First dance", note = "Slow, with lights")

        assertEquals(listOf(WaitingShare(id, "https://www.instagram.com/p/Cabc123/", 0, null, null, "First dance")), held().shares)
        assertEquals(1, planner.stillToSend())
        assertEquals("First dance", database.planner().shares().single().newBoard)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `the name is kept as it was typed but for the spaces around it, and a name of only spaces is no name`() = runBlocking {
        kept("  First dance  ")
        kept("   ")

        assertEquals(listOf("First dance", null), database.planner().shares().map { it.newBoard })
    }

    @Test
    fun `a board that is known and a name for a new one are never kept together`() = runBlocking {
        kept("First dance", boardId = "b1")

        assertEquals("b1", database.planner().shares().single().boardId)
        assertNull(database.planner().shares().single().newBoard)
    }

    @Test
    fun `a share that waits for its board is not asked of the server before it is sent`() = runBlocking {
        kept("First dance")

        assertTrue(server.calls.isEmpty())
        assertEquals(listOf("Dress", "Flowers"), held().boards.map { it.name })
    }

    // ---------------------------------------------------------------- sending

    @Test
    fun `the board is made first, then the idea is saved on it, and the phone's copy has both`() = runBlocking {
        val id = kept("First dance", note = "Slow, with lights")

        planner.sync()

        // The board is looked for, made, and only then the idea is saved on it; the load comes after.
        assertEquals(listOf("POST /api/boards", "POST /api/inspirations"), sent())
        assertTrue(server.calls.map { it.route }.indexOf("GET /api/boards") < server.calls.map { it.route }.indexOf("POST /api/boards"))
        assertEquals("""{"name":"First dance"}""", Json.parseToJsonElement(server.routes("POST /api/boards").single().body).toString())
        assertEquals(listOf("Dress", "Flowers", "First dance"), namesOnTheServer())
        val made = theirs.boards.entries.last().key
        assertEquals(made, boardOfIdeaOnTheServer(id))
        assertEquals(made, Json.parseToJsonElement(server.routes("POST /api/inspirations").single().body).jsonObject.getValue("boardId").jsonPrimitive.content)

        assertEquals(listOf("Dress", "Flowers", "First dance"), held().boards.map { it.name })
        assertEquals(made, held().inspirations.single { it.id == id }.boardId)
        assertTrue(held().shares.isEmpty())
        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `a board of that name that the planner has already is used, whatever its capital letters and the spaces around the name`() = runBlocking {
        val id = kept("  FLOWERS ")

        planner.sync()

        assertTrue(server.routes("POST /api/boards").isEmpty())
        assertEquals(listOf("Dress", "Flowers"), namesOnTheServer())
        assertEquals("b2", boardOfIdeaOnTheServer(id))
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `a board the partner made since the phone loaded is found, and no second one is made`() = runBlocking {
        val id = kept("First dance")
        val theirBoard = theirs.makeBoard("first DANCE")

        planner.sync()

        assertTrue(server.routes("POST /api/boards").isEmpty())
        assertEquals(3, theirs.boards.size)
        assertEquals(theirBoard, boardOfIdeaOnTheServer(id))
    }

    @Test
    fun `a name that is taken between looking and making is the one that was meant - no second board, no failure`() = runBlocking {
        val id = kept("First dance")
        var theirBoard = ""
        // The partner makes it just as the phone is about to: the look found nothing, the making is refused as taken.
        theirs.holdUp = { request -> if (request.method.value == "POST" && request.url.encodedPath == "/api/boards" && theirBoard.isEmpty()) theirBoard = theirs.makeBoard("First dance") }

        planner.sync()

        assertEquals(3, theirs.boards.size)
        assertEquals(theirBoard, boardOfIdeaOnTheServer(id))
        assertTrue(held().shares.isEmpty())
        assertEquals(1, server.routes("POST /api/inspirations").size)
    }

    @Test
    fun `the answer to making the board is lost, the share is sent again, and there is one board and one idea`() = runBlocking {
        val id = kept("First dance")
        theirs.losingTheAnswerOf = "POST /api/boards"

        // The board is made, and the phone never hears of it: the share waits, with its name.
        planner.sync()
        assertEquals(1, held().shares.size)
        assertEquals("First dance", database.planner().shares().single().newBoard)
        assertEquals(3, theirs.boards.size)
        assertTrue(theirs.inspirations.isEmpty())

        planner.sync()

        assertEquals(3, theirs.boards.size)
        assertEquals(1, server.routes("POST /api/boards").size)
        assertEquals(theirs.boards.entries.last().key, boardOfIdeaOnTheServer(id))
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `sent twice because the answer for the idea was lost, it makes one board and one idea`() = runBlocking {
        val id = kept("First dance")
        theirs.losingTheAnswerOf = "POST /api/inspirations"

        planner.sync()
        assertEquals(1, held().shares.size)
        planner.sync()

        assertEquals(3, theirs.boards.size)
        assertEquals(1, theirs.inspirations.size)
        assertEquals(theirs.boards.entries.last().key, boardOfIdeaOnTheServer(id))
        // The board was found out once and is not asked about again: it is kept with the share.
        assertEquals(1, server.routes("POST /api/boards").size)
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `a name the server does not take leaves the idea on no board, and nothing of the share is lost`() = runBlocking {
        theirs.boardNamesRefused += "first dance"
        val id = kept("First dance", note = "Slow, with lights")

        planner.sync()

        assertEquals(3 - 1, theirs.boards.size)
        assertNull(boardOfIdeaOnTheServer(id))
        assertEquals("Slow, with lights", theirs.inspirations.getValue(id).getValue("note").jsonPrimitive.content)
        assertTrue(held().shares.isEmpty())
        assertNull(held().inspirations.single { it.id == id }.boardId)
        assertEquals(0, planner.stillToSend())
    }

    @Test
    fun `a name the server does not take costs the pictures of the share nothing`() = runBlocking {
        theirs.boardNamesRefused += "first dance"
        val id = (planner.keepShare(Share(link = null, boardId = null, note = "", newBoard = "First dance"), listOf(picture("one"))) as Kept.Yes).inspirationId
        server.json("POST /api/inspirations/$id/pictures", StandIn.inspiration(id, pictures = 1))

        planner.sync()

        assertEquals(listOf("POST /api/boards", "POST /api/inspirations", "POST /api/inspirations/$id/pictures"), sent())
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `without a connection the share waits with the name, the board is tried again with it, and it goes when the connection is back`() = runBlocking {
        val id = kept("First dance")
        server.down = true

        assertEquals(Load.UNREACHABLE, planner.sync())

        assertEquals("First dance", held().shares.single().newBoard)
        assertEquals(1, planner.stillToSend())
        assertEquals(2, theirs.boards.size)

        server.down = false
        planner.sync()

        assertEquals(3, theirs.boards.size)
        assertEquals(theirs.boards.entries.last().key, boardOfIdeaOnTheServer(id))
        assertTrue(held().shares.isEmpty())
    }

    @Test
    fun `two shares for one new board make it once`() = runBlocking {
        val first = kept("First dance")
        val second = kept(" first dance ", link = "https://vm.tiktok.com/ZMabc/")

        planner.sync()

        assertEquals(3, theirs.boards.size)
        assertEquals(boardOfIdeaOnTheServer(first), boardOfIdeaOnTheServer(second))
    }

    @Test
    fun `a share on a known board makes no board and asks for none`() = runBlocking {
        val id = kept(null, boardId = "b1")

        planner.sync()

        assertEquals(listOf("POST /api/inspirations"), sent())
        assertEquals("b1", boardOfIdeaOnTheServer(id))
    }

    // ---------------------------------------------------------------- signing out

    @Test
    fun `signing out removes the share and the name of its board`() = runBlocking {
        kept("Surprise honeymoon", link = "https://www.instagram.com/p/Csecret/")

        planner.forget()

        assertNull(planner.planner.first())
        assertTrue(database.planner().shares().isEmpty())
        val onDisk = folder.root.walkTopDown().filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("Surprise honeymoon"))
        assertFalse(onDisk.contains("Csecret"))
        planner.sync()
        assertTrue(sent().isEmpty())
    }

    // ---------------------------------------------------------------- what is printed

    @Test
    fun `nothing of a share, the name of its board included, is printed`() = runBlocking {
        val id = kept("Surprise honeymoon", link = "https://www.instagram.com/p/Csecret/", note = "For Marek")
        val row = database.planner().shares().single()

        for (printed in listOf(row.toString(), held().shares.single().toString(), Share("https://x.example/Csecret/", null, "For Marek", "Surprise honeymoon").toString())) {
            assertFalse(printed, printed.contains("Surprise honeymoon") || printed.contains("Csecret") || printed.contains("Marek"))
        }
        assertEquals(id, row.inspirationId)
    }
}
