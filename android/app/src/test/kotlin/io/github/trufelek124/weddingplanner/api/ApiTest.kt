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
import org.junit.Test

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
}
