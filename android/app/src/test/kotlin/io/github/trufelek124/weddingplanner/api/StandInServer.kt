package io.github.trufelek124.weddingplanner.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for the planner's server in tests. Each "METHOD /path" gets its own answer; a route the
 * test did not name fails the way an unreachable server does.
 */
class StandInServer {

    /** What the app sent, in order. */
    data class Call(val route: String, val authorization: String?, val origin: String?, val body: String, val contentType: String? = null, val query: String = "")

    // The app asks for several things at once, and each is answered on a thread of its own: what is
    // written here while answering has to bear that, or a call goes missing from the list now and then.
    val calls: MutableList<Call> = CopyOnWriteArrayList()
    private val answers = ConcurrentHashMap<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()

    /** Asked when no answer was named for a route; null from it means "unreachable" as before. */
    @Volatile
    var otherwise: (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?)? = null

    /** No connection at all: nothing gets an answer, whatever was named. */
    @Volatile
    var down = false

    val engine = MockEngine { request ->
        val route = "${request.method.value} ${request.url.encodedPath}"
        calls += Call(
            route, request.headers[HttpHeaders.Authorization], request.headers[HttpHeaders.Origin], String(request.body.toByteArray()),
            request.body.contentType?.withoutParameters()?.toString(), request.url.encodedQuery,
        )
        if (down) {
            throw IOException("Failed to connect")
        }
        val answer = answers[route]
        if (answer != null) {
            answer(request)
        } else {
            otherwise?.invoke(this, request) ?: throw IOException("Failed to connect")
        }
    }

    fun routes(route: String): List<Call> = calls.filter { it.route == route }

    /** Answers a route with JSON from now on. */
    fun json(route: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
        answers[route] = { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
    }

    /** Answers a route with an RFC 9457 problem. */
    fun problem(route: String, status: Int, kind: String, extra: String = "") {
        answers[route] = {
            respond(
                """{"type":"urn:wedding-planner:problem:$kind","title":"Problem","status":$status$extra}""",
                HttpStatusCode.fromValue(status),
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
    }

    fun nothing(route: String) {
        answers[route] = { respond("", HttpStatusCode.NoContent) }
    }

    fun unreachable(route: String) {
        answers.remove(route)
    }

    /** Answers by a rule of the test's own. */
    fun answer(route: String, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        answers[route] = handler
    }

    companion object {
        /** What the server answers when someone signs in or renews the session. Made-up tokens and people. */
        fun session(accessToken: String = "access-1", refreshToken: String = "refresh-1", name: String = "Anna"): String =
            """{"accessToken":"$accessToken","accessTokenExpiresAt":"2026-11-18T12:15:00Z","refreshToken":"$refreshToken",""" +
                """"user":{"id":"11111111-1111-4111-8111-111111111111","displayName":"$name","email":"anna@example.com"}}"""
    }
}
