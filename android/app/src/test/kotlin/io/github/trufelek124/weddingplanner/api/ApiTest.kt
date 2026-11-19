package io.github.trufelek124.weddingplanner.api

import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.model.AppInfo
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse

class ApiTest {

    private val server = StandInServer()
    private var token: String? = "access-1"
    private var newer: String? = null
    private var renewals = 0

    private val api = Api(
        server.engine,
        ServerAddress.parse("https://planner.example"),
        object : Api.AccessTokens {
            override suspend fun current(): String? = token

            override suspend fun renewed(refused: String): String? {
                renewals += 1
                return newer
            }
        },
    )

    private suspend fun failure(block: suspend () -> Unit): ApiFailure =
        try {
            block()
            fail("the call went through")
            throw IllegalStateException()
        } catch (expected: ApiFailure) {
            expected
        }

    @Test
    fun `sends the access token and reads the answer`() = runTest {
        server.json("GET /api/app-info", """{"name":"wedding-planner","version":"0.1.0","addedLater":true}""")

        val info: AppInfo = api.get("/api/app-info")

        assertEquals(AppInfo(name = "wedding-planner", version = "0.1.0"), info)
        assertEquals("Bearer access-1", server.calls.single().authorization)
        // A request that says where it comes from is taken for a browser's: the app never does.
        assertNull(server.calls.single().origin)
    }

    @Test
    fun `sends JSON that was put together by hand as it is written`() = runTest {
        server.json("PUT /api/entries/e1/status", """{"name":"wedding-planner","version":"0.1.0"}""")
        val body = buildJsonObject {
            put("status", "DONE")
            put("amount", 250000)
            put("paidOn", JsonNull)
            put("assigneeIds", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
        }

        api.send<AppInfo>(HttpMethod.Put, "/api/entries/e1/status", body)

        assertEquals("""{"status":"DONE","amount":250000,"paidOn":null,"assigneeIds":["a","b"]}""", server.calls.single().body)
        assertEquals("application/json", server.calls.single().contentType)
    }

    @Test
    fun `renews the token once when the server refuses it, and sends the request again`() = runTest {
        newer = "access-2"
        server.answer("GET /api/app-info") { request ->
            if (request.headers["Authorization"] == "Bearer access-2") {
                respondJson("""{"name":"wedding-planner","version":"0.1.0"}""")
            } else {
                respondProblem(401, "unauthenticated")
            }
        }

        val info: AppInfo = api.get("/api/app-info")

        assertEquals("0.1.0", info.version)
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), server.calls.map { it.authorization })
        assertEquals(1, renewals)
    }

    @Test
    fun `does not go round in circles when the newer token is refused too`() = runTest {
        newer = "access-2"
        server.problem("GET /api/app-info", 401, "unauthenticated")

        val refused = failure { api.get<AppInfo>("/api/app-info") }

        assertTrue(refused is ApiFailure.Problem && refused.status == 401)
        assertEquals(2, server.calls.size)
        assertEquals(1, renewals)
    }

    @Test
    fun `says signed out when there is no token, or none to be had after a refusal`() = runTest {
        server.problem("GET /api/app-info", 401, "unauthenticated")

        assertTrue(failure { api.get<AppInfo>("/api/app-info") } is ApiFailure.SignedOut)

        token = null
        assertTrue(failure { api.get<AppInfo>("/api/app-info") } is ApiFailure.SignedOut)
        // Without a token nothing was sent the second time.
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `reads an error by its kind, with what is wrong with single fields`() = runTest {
        server.problem(
            "POST /api/entries", 400, "bad-request",
            ""","detail":"The request is not valid.","fieldProblems":[{"field":"title","message":"Enter a title."}]""",
        )

        val refused = failure { api.send<AppInfo>(HttpMethod.Post, "/api/entries", mapOf("title" to "")) } as ApiFailure.Problem

        assertEquals(400, refused.status)
        assertTrue(refused.isOf("bad-request"))
        assertEquals("The request is not valid.", refused.detail)
        assertEquals(mapOf("title" to "Enter a title."), refused.fieldMessages)
        assertEquals("""{"title":""}""", server.calls.single().body)
    }

    @Test
    fun `takes a kind of error it does not know, or no description at all, as some error`() = runTest {
        server.problem("GET /api/app-info", 409, "added-later")
        val unknown = failure { api.get<AppInfo>("/api/app-info") } as ApiFailure.Problem
        assertEquals(409, unknown.status)
        assertEquals("urn:wedding-planner:problem:added-later", unknown.type)

        server.answer("GET /api/app-info") { respondText("<html>Bad gateway</html>", 502) }
        val bare = failure { api.get<AppInfo>("/api/app-info") } as ApiFailure.Problem
        assertEquals(502, bare.status)
        assertEquals("about:blank", bare.type)
        assertTrue(bare.fieldMessages.isEmpty())
    }

    @Test
    fun `says unreachable when there is no answer`() = runTest {
        val gone = failure { api.get<AppInfo>("/api/app-info") }

        assertTrue(gone is ApiFailure.Unreachable)
    }

    @Test
    fun `sends no token with the calls anyone may make`() = runTest {
        server.json("GET /api/auth/config", """{"googleClientId":null,"devLoginEnabled":true}""")
        server.nothing("POST /api/auth/sign-out")

        api.getOpen<io.github.trufelek124.weddingplanner.api.model.AuthConfig>("/api/auth/config")
        api.postOpenForNothing("/api/auth/sign-out", mapOf("refreshToken" to "refresh-1"))

        assertEquals(listOf(null, null), server.calls.map { it.authorization })
    }

    @Test
    fun `an api failure says nothing of what was sent`() {
        val refused = ApiFailure.Problem(400, "urn:wedding-planner:problem:bad-request", "detail with a name in it", mapOf("email" to "x"))

        assertEquals("the server answered 400 (urn:wedding-planner:problem:bad-request)", refused.message)
        assertThrows(ApiFailure.SignedOut::class.java) { throw ApiFailure.SignedOut() }
    }

    // ---------------------------------------------------------------- fetching a file

    @get:Rule
    val folder = TemporaryFolder()

    private val bytes = ByteArray(100_000) { (it % 251).toByte() }

    @Test
    fun `fetches a file with the token, into the file and nowhere else`() = runTest {
        server.answer("GET /api/inspirations/i1/pictures/p1") { respondPicture(bytes) }
        val into = File(folder.root, "picture")

        api.fetchTo("/api/inspirations/i1/pictures/p1?size=THUMBNAIL", into, 1_000_000)

        assertArrayEquals(bytes, into.readBytes())
        assertEquals("Bearer access-1", server.calls.single().authorization)
        assertEquals("size=THUMBNAIL", server.calls.single().query)
        assertEquals(listOf("picture"), folder.root.list()!!.toList())
    }

    @Test
    fun `renews the token once for a file too`() = runTest {
        newer = "access-2"
        server.answer("GET /api/inspirations/i1/pictures/p1") { request ->
            if (request.headers["Authorization"] == "Bearer access-2") respondPicture(bytes) else respondProblem(401, "unauthenticated")
        }
        val into = File(folder.root, "picture")

        api.fetchTo("/api/inspirations/i1/pictures/p1", into, 1_000_000)

        assertArrayEquals(bytes, into.readBytes())
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), server.calls.map { it.authorization })
        assertEquals(1, renewals)
    }

    @Test
    fun `a file that is refused leaves nothing, and says why as any call does`() = runTest {
        server.problem("GET /api/inspirations/i1/pictures/p1", 404, "not-found")
        val into = File(folder.root, "picture")

        val refused = failure { api.fetchTo("/api/inspirations/i1/pictures/p1", into, 1_000_000) }

        assertTrue(refused is ApiFailure.Problem && refused.status == 404)
        assertFalse(into.exists())
        // A refusal of the token the second time is not tried a third time.
        server.problem("GET /api/inspirations/i1/pictures/p2", 401, "unauthenticated")
        newer = "access-2"
        server.calls.clear()
        assertTrue(failure { api.fetchTo("/api/inspirations/i1/pictures/p2", into, 1_000_000) } is ApiFailure.Problem)
        assertEquals(2, server.calls.size)
        assertFalse(into.exists())
    }

    @Test
    fun `no connection leaves no file, and with no token nothing is asked`() = runTest {
        val into = File(folder.root, "picture")

        assertTrue(failure { api.fetchTo("/api/inspirations/i1/pictures/p1", into, 1_000_000) } is ApiFailure.Unreachable)
        assertFalse(into.exists())

        token = null
        server.calls.clear()
        assertTrue(failure { api.fetchTo("/api/inspirations/i1/pictures/p1", into, 1_000_000) } is ApiFailure.SignedOut)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `a file larger than the limit is refused, whether it says so beforehand or only shows it while it comes`() = runTest {
        val into = File(folder.root, "picture")
        server.answer("GET /api/inspirations/i1/pictures/p1") { respondPicture(bytes) }

        assertTrue(failure { api.fetchTo("/api/inspirations/i1/pictures/p1", into, 99_999) } is ApiFailure.Unreadable)
        assertFalse(into.exists())

        // One that does not say how long it is: the limit stops it part of the way, and what was written goes again.
        server.answer("GET /api/inspirations/i1/pictures/p2") {
            respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }
        assertTrue(failure { api.fetchTo("/api/inspirations/i1/pictures/p2", into, 50_000) } is ApiFailure.Unreadable)
        assertFalse(into.exists())

        // Exactly the limit is taken.
        api.fetchTo("/api/inspirations/i1/pictures/p2", into, 100_000)
        assertEquals(100_000L, into.length())
    }
}
