package io.github.trufelek124.weddingplanner.session

import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.ApiFailure
import io.github.trufelek124.weddingplanner.api.model.DevSignInRequest
import io.github.trufelek124.weddingplanner.api.model.GoogleSignInRequest
import io.github.trufelek124.weddingplanner.api.model.RefreshRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.trufelek124.weddingplanner.api.model.Session as SessionAnswer

/** Where the phone stands with the server. */
sealed interface SessionState {

    /** Just started: the phone is looking at what it remembers. */
    data object Restoring : SessionState

    /** @param ended true when the server ended a session that was running, as opposed to nobody having signed in */
    data class SignedOut(val ended: Boolean = false) : SessionState

    data class SignedIn(val user: SignedInUser) : SessionState
}

/**
 * The session: signing in, staying signed in, and signing out.
 *
 * The access token is held in memory only. The refresh token is used once: each renewal gives a new
 * one, which is written to the phone before the old one is forgotten.
 *
 * @param api talks to the server; it asks this session for the access token (see [accessTokens])
 * @param forgetEverything removes whatever else of the account is on the phone; called on every sign-out
 */
class Session(
    private val api: () -> Api,
    private val store: SessionStore,
    private val forgetEverything: suspend () -> Unit = {},
) {

    private val changes = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = changes.asStateFlow()

    private var accessToken: String? = null
    private val renewing = Mutex()

    /** True while a sign-out is removing the account from the phone. */
    @Volatile
    private var ending = false

    /** At start-up: whoever was signed in still is, without asking the server. Works without a connection. */
    suspend fun restore() {
        val saved = store.read()
        changes.value = if (saved == null) SessionState.SignedOut() else SessionState.SignedIn(saved.user)
    }

    /** @throws ApiFailure when the server refuses the account or cannot be reached; nobody is signed in then */
    suspend fun signInWithGoogle(idToken: String) {
        begin(api().postOpen("/api/auth/google", GoogleSignInRequest(idToken = idToken)))
    }

    /** The development login: for debug builds against a server that offers it. */
    suspend fun signInForDevelopment(email: String) {
        begin(api().postOpen("/api/auth/dev", DevSignInRequest(email = email)))
    }

    /**
     * Tells the server, if it can be reached, and removes the account from the phone whether or not
     * it could be told.
     */
    suspend fun signOut() {
        val token = store.read()?.refreshToken
        end(byTheServer = false)
        if (token != null) {
            try {
                api().postOpenForNothing("/api/auth/sign-out", RefreshRequest(refreshToken = token))
            } catch (notTold: ApiFailure) {
                // The token is gone from this phone; on the server it runs out by itself.
            }
        }
    }

    /** What [Api] asks for the access token. */
    val accessTokens: Api.AccessTokens = object : Api.AccessTokens {

        override suspend fun current(): String? {
            if (state.value !is SessionState.SignedIn) {
                return null
            }
            return accessToken ?: renew(refused = null)
        }

        override suspend fun renewed(refused: String): String? = renew(refused)
    }

    /**
     * Gets a new access token with the refresh token. Several calls that were refused at the same
     * moment share one renewal: the refresh token can be used only once.
     */
    private suspend fun renew(refused: String?): String? = renewing.withLock {
        // A sign-out has taken the token away and is still clearing up: none is fetched in its place.
        if (ending) {
            return null
        }
        val current = accessToken
        if (current != null && current != refused) {
            // Someone else renewed it while this call was waiting.
            return current
        }
        val saved = store.read() ?: run {
            end(byTheServer = true)
            return null
        }
        val answer: SessionAnswer = try {
            api().postOpen("/api/auth/refresh", RefreshRequest(refreshToken = saved.refreshToken))
        } catch (refusedByServer: ApiFailure.Problem) {
            if (refusedByServer.status == 401 || refusedByServer.status == 403) {
                end(byTheServer = true)
                return null
            }
            throw refusedByServer
        }
        keep(answer)
        return answer.accessToken
    }

    private suspend fun begin(answer: SessionAnswer) {
        val user = keep(answer)
        changes.value = SessionState.SignedIn(user)
    }

    /** The new refresh token is on the phone before anything else happens with the answer. */
    private suspend fun keep(answer: SessionAnswer): SignedInUser {
        val refreshToken = answer.refreshToken
            ?: throw ApiFailure.Problem(500, "about:blank", "The server sent no refresh token.", emptyMap())
        val user = SignedInUser(answer.user.id, answer.user.displayName)
        store.write(StoredSession(user, refreshToken))
        accessToken = answer.accessToken
        return user
    }

    /**
     * Everything of the account goes from the phone first, and only then does the sign-in appear:
     * whoever signs in next finds nothing. While that takes its time no token is handed out any
     * more, and whatever goes wrong in between, the session is over at the end.
     */
    private suspend fun end(byTheServer: Boolean) {
        ending = true
        try {
            accessToken = null
            store.clear()
            forgetEverything()
        } finally {
            changes.value = SessionState.SignedOut(ended = byTheServer)
            ending = false
        }
    }
}
