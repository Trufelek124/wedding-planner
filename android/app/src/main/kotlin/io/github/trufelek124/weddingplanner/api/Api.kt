package io.github.trufelek124.weddingplanner.api

import io.github.trufelek124.weddingplanner.ServerAddress
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one way the app talks to the planner's server. It adds the access token, renews it once when
 * the server refuses it, and turns every failure into an [ApiFailure].
 *
 * Nothing here writes a request or an answer to the log: both carry tokens and personal data.
 */
class Api(engine: HttpClientEngine, private val server: ServerAddress, private val tokens: AccessTokens) {

    /** Where the access token comes from. The session implements it. */
    interface AccessTokens {
        /** The token to send, or null when nobody is signed in. */
        suspend fun current(): String?

        /**
         * Called when the server refused [refused]. Returns a newer token to try once more with,
         * or null when there is none to be had.
         */
        suspend fun renewed(refused: String): String?
    }

    val http: HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(JSON) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    /** A call that needs a signed-in person. */
    suspend inline fun <reified T> get(path: String): T = answer(HttpMethod.Get, path, null, signedIn = true).body()

    suspend inline fun <reified T> send(method: HttpMethod, path: String, body: Any?): T =
        answer(method, path, body, signedIn = true).body()

    /** A call anyone may make: the sign-in endpoints themselves. */
    suspend inline fun <reified T> getOpen(path: String): T = answer(HttpMethod.Get, path, null, signedIn = false).body()

    suspend inline fun <reified T> postOpen(path: String, body: Any?): T =
        answer(HttpMethod.Post, path, body, signedIn = false).body()

    /** The same for an answer without content. */
    suspend fun postOpenForNothing(path: String, body: Any?) {
        answer(HttpMethod.Post, path, body, signedIn = false)
    }

    @PublishedApi
    internal suspend fun answer(method: HttpMethod, path: String, body: Any?, signedIn: Boolean): HttpResponse {
        val token = if (signedIn) tokens.current() ?: throw ApiFailure.SignedOut() else null
        var response = attempt(method, path, body, token)
        if (signedIn && response.status.value == 401 && token != null) {
            // The access token ran out, or the server no longer knows it: one newer token, one more try.
            val newer = tokens.renewed(token) ?: throw ApiFailure.SignedOut()
            response = attempt(method, path, body, newer)
        }
        if (!response.status.isSuccess()) {
            throw problemOf(response)
        }
        return response
    }

    private suspend fun attempt(method: HttpMethod, path: String, body: Any?, token: String?): HttpResponse =
        try {
            http.request(server.resolve(path)) {
                this.method = method
                accept()
                if (token != null) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (failure: IOException) {
            // No connection, a refused or reset one, a name that does not resolve, or no answer in time.
            throw ApiFailure.Unreachable(failure)
        }

    private fun HttpRequestBuilder.accept() {
        header(HttpHeaders.Accept, "application/json, application/problem+json")
    }

    /**
     * Reads an error by hand, not into the generated model: a kind of error this version of the
     * app does not know must still come out as "some error", with its status.
     */
    private suspend fun problemOf(response: HttpResponse): ApiFailure.Problem {
        val status = response.status.value
        val described = try {
            JSON.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (unreadable: Exception) {
            null
        }
        val fields = described?.get("fieldProblems")?.let { problems ->
            runCatching {
                problems.jsonArray.associate { one ->
                    one.jsonObject.getValue("field").jsonPrimitive.content to one.jsonObject.getValue("message").jsonPrimitive.content
                }
            }.getOrNull()
        }
        return ApiFailure.Problem(
            status = status,
            type = described?.get("type")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: "about:blank",
            detail = described?.get("detail")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            fieldMessages = fields.orEmpty(),
        )
    }

    companion object {
        /** A newer server may send fields this app does not know yet; every field it knows is always sent. */
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }
    }
}
