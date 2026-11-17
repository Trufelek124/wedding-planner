package io.github.trufelek124.weddingplanner

import java.net.URI
import java.net.URISyntaxException

/**
 * The address of the planner's server, as set when the app was built.
 * Kept in one normalised form so that every request is built the same way.
 */
@JvmInline
value class ServerAddress private constructor(val value: String) {

    /** The full address of an API path such as "/api/plan". */
    fun resolve(path: String): String {
        require(path.startsWith("/")) { "a path starts with '/': $path" }
        return value + path
    }

    override fun toString(): String = value

    companion object {

        /**
         * Accepts "https://host", "http://host:8080/" and the like; refuses anything that is
         * not an http(s) address of a server (no path, query, fragment or user info).
         */
        fun parse(raw: String): ServerAddress {
            val trimmed = raw.trim().trimEnd('/')
            val uri = try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("not a server address: $raw", e)
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { "a server address starts with http:// or https://: $raw" }
            require(!uri.host.isNullOrEmpty()) { "a server address names a host: $raw" }
            require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
                "a server address is only scheme, host and port: $raw"
            }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return ServerAddress("$scheme://${uri.host.lowercase()}$port")
        }
    }
}
