package io.github.trufelek124.weddingplanner.api

import io.github.trufelek124.weddingplanner.ServerAddress
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.streams.asInput
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    suspend inline fun <reified T> get(path: String): T = read { answer(HttpMethod.Get, path, null, signedIn = true).body() }

    suspend inline fun <reified T> send(method: HttpMethod, path: String, body: Any?): T =
        read { answer(method, path, body, signedIn = true).body() }

    /** A call anyone may make: the sign-in endpoints themselves. */
    suspend inline fun <reified T> getOpen(path: String): T = read { answer(HttpMethod.Get, path, null, signedIn = false).body() }

    suspend inline fun <reified T> postOpen(path: String, body: Any?): T =
        read { answer(HttpMethod.Post, path, body, signedIn = false).body() }

    /**
     * Sends files as the parts of a form, all under one name. They are read from the disk while
     * they are sent, never all into memory, and read again when the request has to be repeated.
     */
    suspend inline fun <reified T> sendFiles(path: String, part: String, files: List<File>): T = read {
        answer(HttpMethod.Post, path, FilesToSend(part, files), signedIn = true).body()
    }


    /**
     * Fetches a file, a picture, into [file]: the token sent and renewed once like any call. The body goes to the disk
     * as it arrives and is never held whole in memory; more than [mostBytes] of it is refused, and what was written
     * is removed again whenever this does not end well, so that a half-fetched file is never left behind. The server
     * is asked for what it has as a picture, not as JSON.
     *
     * @throws ApiFailure for anything that goes wrong; a file the disk could not take counts as [ApiFailure.Unreachable]
     */
    suspend fun fetchTo(path: String, file: File, mostBytes: Long) {
        try {
            val token = tokens.current() ?: throw ApiFailure.SignedOut()
            if (!streamed(path, token, file, mostBytes, mayRenew = true)) {
                val newer = tokens.renewed(token) ?: throw ApiFailure.SignedOut()
                streamed(path, newer, file, mostBytes, mayRenew = false)
            }
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    /** One try of [fetchTo]; false when the server refused the token and a newer one may be tried. */
    private suspend fun streamed(path: String, token: String, file: File, mostBytes: Long, mayRenew: Boolean): Boolean =
        try {
            http.prepareGet(server.resolve(path)) {
                header(HttpHeaders.Accept, "image/*, application/problem+json")
                header(HttpHeaders.Authorization, "Bearer $token")
                // A large picture on a slow line takes longer than a question does.
                timeout { requestTimeoutMillis = PICTURE_TIMEOUT_MILLIS }
            }.execute { response ->
                if (response.status.value == 401 && mayRenew) {
                    return@execute false
                }
                if (!response.status.isSuccess()) {
                    throw problemOf(response)
                }
                if ((response.contentLength() ?: 0L) > mostBytes) {
                    throw ApiFailure.Unreadable(IOException("the file is larger than the limit"))
                }
                val channel = response.bodyAsChannel()
                file.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var taken = 0L
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        taken += read
                        if (taken > mostBytes) {
                            throw ApiFailure.Unreadable(IOException("the file is larger than the limit"))
                        }
                        out.write(buffer, 0, read)
                    }
                }
                true
            }
        } catch (failure: ApiFailure) {
            throw failure
        } catch (failure: IOException) {
            throw ApiFailure.Unreachable(failure)
        }

    /** An answer that is not what the contract says is a failure like any other, not a crash. */
    @PublishedApi
    internal inline fun <T> read(answer: () -> T): T =
        try {
            answer()
        } catch (unreadable: ContentConvertException) {
            throw ApiFailure.Unreadable(unreadable)
        } catch (unreadable: SerializationException) {
            throw ApiFailure.Unreadable(unreadable)
        } catch (notJson: NoTransformationFoundException) {
            throw ApiFailure.Unreadable(notJson)
        }

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
                if (body is FilesToSend) {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                for (file in body.files) {
                                    append(
                                        key = body.part,
                                        value = InputProvider(file.length()) { file.inputStream().asInput() },
                                        // What kind of picture it is the server works out from the file itself.
                                        headers = Headers.build {
                                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                            append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                        },
                                    )
                                }
                            },
                        ),
                    )
                } else if (body is JsonElement) {
                    // JSON put together by hand is sent as it is written; left to the converter it is taken for a map and refused.
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                } else if (body != null) {
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

    /** Files to go out as the parts of one form. */
    @PublishedApi
    internal class FilesToSend(val part: String, val files: List<File>)

    companion object {
        private const val PICTURE_TIMEOUT_MILLIS = 120_000L

        /** A newer server may send fields this app does not know yet; every field it knows is always sent. */
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }
    }
}
