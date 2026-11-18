package io.github.trufelek124.weddingplanner.api

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

fun MockRequestHandleScope.respondJson(body: String, status: Int = 200): HttpResponseData =
    respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))

fun MockRequestHandleScope.respondText(body: String, status: Int): HttpResponseData =
    respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "text/html"))

fun MockRequestHandleScope.respondProblem(status: Int, kind: String): HttpResponseData =
    respond(
        """{"type":"urn:wedding-planner:problem:$kind","title":"Problem","status":$status}""",
        HttpStatusCode.fromValue(status),
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )
