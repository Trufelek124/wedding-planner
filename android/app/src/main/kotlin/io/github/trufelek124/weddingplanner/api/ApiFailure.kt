package io.github.trufelek124.weddingplanner.api

import java.io.IOException

/**
 * Why a call to the server did not give what was asked for. Screens decide what to say from the
 * kind, never from the server's wording.
 */
sealed class ApiFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /**
     * The server answered with an error (RFC 9457).
     *
     * @param type the stable identifier, such as "urn:wedding-planner:problem:not-found"; an
     *     identifier a newer server sends and this app does not know is still kept as it came
     * @param fieldMessages what is wrong with single fields of what was sent, in words for the person
     */
    class Problem(
        val status: Int,
        val type: String,
        val detail: String?,
        val fieldMessages: Map<String, String>,
    ) : ApiFailure("the server answered $status ($type)") {

        fun isOf(kind: String): Boolean = type == PREFIX + kind
    }

    /** No answer: no connection, the server is down, or it took too long. */
    class Unreachable(cause: Throwable) : ApiFailure("the server cannot be reached", cause)

    /** The server answered, but not with what this version of the app can read. */
    class Unreadable(cause: Throwable) : ApiFailure("the answer cannot be read", cause)

    /** The session is over: the server no longer accepts this phone's sign-in. */
    class SignedOut : ApiFailure("signed out")

    companion object {
        const val PREFIX = "urn:wedding-planner:problem:"
        const val BAD_REQUEST = "bad-request"
        const val CHANGED_MEANWHILE = "changed-meanwhile"
        const val ALREADY_DECIDED = "already-decided"
        const val UNAUTHENTICATED = "unauthenticated"
        const val ACCOUNT_NOT_ALLOWED = "account-not-allowed"
        const val WEDDING_NOT_SET_UP = "wedding-not-set-up"
        const val WEDDING_FULL = "wedding-full"
    }
}
