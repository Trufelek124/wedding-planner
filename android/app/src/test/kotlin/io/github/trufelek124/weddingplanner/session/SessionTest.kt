package io.github.trufelek124.weddingplanner.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.trufelek124.weddingplanner.ServerAddress
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.ApiFailure
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.api.model.AppInfo
import io.github.trufelek124.weddingplanner.api.respondJson
import io.github.trufelek124.weddingplanner.api.respondProblem
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Signing in, staying signed in and signing out, against a stand-in server. Tokens and people are made up. */
class SessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = StandInServer()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var forgotten = 0

    private lateinit var file: File
    private lateinit var store: SessionStore
    private lateinit var session: Session
    private lateinit var api: Api

    private fun TestScope.aPhone() {
        file = File(folder.root, "session.preferences_pb")
        val data: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store = SessionStore(data, AesGcmCipher { key })
        session = Session(api = { api }, store = store, forgetEverything = { forgotten += 1 })
        api = Api(server.engine, ServerAddress.parse("https://planner.example"), session.accessTokens)
    }

    private suspend fun failure(block: suspend () -> Unit): ApiFailure =
        try {
            block()
            fail("the call went through")
            throw IllegalStateException()
        } catch (expected: ApiFailure) {
            expected
        }

    // ---------------------------------------------------------------- signing in

    @Test
    fun `signing in with Google exchanges the ID token and keeps the session on the phone`() = runTest {
        aPhone()
        server.json("POST /api/auth/google", StandInServer.session())
        session.restore()
        assertEquals(SessionState.SignedOut(), session.state.value)

        session.signInWithGoogle("the-id-token-from-google")

        assertEquals(SessionState.SignedIn(SignedInUser("11111111-1111-4111-8111-111111111111", "Anna")), session.state.value)
        val sent = server.calls.single()
        assertEquals("""{"idToken":"the-id-token-from-google"}""", sent.body)
        // The app is no browser: with an Origin the server would keep the refresh token in a cookie instead.
        assertNull(sent.origin)
        assertNull(sent.authorization)
        assertEquals("refresh-1", store.read()?.refreshToken)
    }

    @Test
    fun `the refresh token is not readable in what the phone stores, and the address is not stored at all`() = runTest {
        aPhone()
        server.json("POST /api/auth/google", StandInServer.session(refreshToken = "refresh-secret-1"))
        session.restore()

        session.signInWithGoogle("id-token")

        val stored = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(stored.contains("refresh-secret-1"))
        assertFalse(stored.contains("access-1"))
        assertFalse(stored.contains("anna@example.com"))
        assertTrue(stored.contains("Anna"))
    }

    @Test
    fun `the development login signs in with an address`() = runTest {
        aPhone()
        server.json("POST /api/auth/dev", StandInServer.session(name = "Piotr"))
        session.restore()

        session.signInForDevelopment("piotr@example.com")

        assertEquals("""{"email":"piotr@example.com"}""", server.calls.single().body)
        assertEquals("Piotr", (session.state.value as SessionState.SignedIn).user.displayName)
    }

    @Test
    fun `an account that is not one of the owners is refused and nothing is kept`() = runTest {
        aPhone()
        server.problem("POST /api/auth/google", 403, "account-not-allowed")
        session.restore()

        val refused = failure { session.signInWithGoogle("a-strangers-id-token") } as ApiFailure.Problem

        assertTrue(refused.isOf(ApiFailure.ACCOUNT_NOT_ALLOWED))
        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.read())
    }

    @Test
    fun `a server that cannot be reached leaves nobody signed in`() = runTest {
        aPhone()
        session.restore()

        assertTrue(failure { session.signInWithGoogle("id-token") } is ApiFailure.Unreachable)
        assertEquals(SessionState.SignedOut(), session.state.value)
    }

    // ---------------------------------------------------------------- staying signed in

    @Test
    fun `whoever was signed in still is after a restart, without asking the server`() = runTest {
        aPhone()
        server.json("POST /api/auth/google", StandInServer.session())
        session.restore()
        session.signInWithGoogle("id-token")
        server.calls.clear()

        // The app starts again: a new session object over the same storage, and no connection.
        val restarted = Session(api = { api }, store = store)
        assertEquals(SessionState.Restoring, restarted.state.value)
        restarted.restore()

        assertEquals("Anna", (restarted.state.value as SessionState.SignedIn).user.displayName)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `the first call after a restart renews the session, and the new refresh token replaces the old one`() = runTest {
        aPhone()
        store.write(StoredSession(SignedInUser("11111111-1111-4111-8111-111111111111", "Anna"), "refresh-1"))
        session.restore()
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.json("GET /api/app-info", """{"name":"wedding-planner","version":"0.1.0"}""")

        api.get<AppInfo>("/api/app-info")

        assertEquals(listOf("POST /api/auth/refresh", "GET /api/app-info"), server.calls.map { it.route })
        assertEquals("""{"refreshToken":"refresh-1"}""", server.calls[0].body)
        assertEquals("Bearer access-2", server.calls[1].authorization)
        // A refresh token is good once: the next renewal must present the new one.
        assertEquals("refresh-2", store.read()?.refreshToken)
    }

    @Test
    fun `several calls refused at the same moment share one renewal`() = runTest {
        aPhone()
        server.json("POST /api/auth/google", StandInServer.session(accessToken = "access-1"))
        session.restore()
        session.signInWithGoogle("id-token")
        val renewal = CompletableDeferred<Unit>()
        server.answer("POST /api/auth/refresh") {
            renewal.await()
            respondJson(StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        }
        server.answer("GET /api/app-info") { request ->
            if (request.headers["Authorization"] == "Bearer access-2") respondJson("""{"name":"wedding-planner","version":"0.1.0"}""") else respondProblem(401, "unauthenticated")
        }

        val three = List(3) { async { api.get<AppInfo>("/api/app-info") } }
        testScheduler.runCurrent()
        renewal.complete(Unit)
        three.awaitAll()

        assertEquals(1, server.routes("POST /api/auth/refresh").size)
        assertEquals("refresh-2", store.read()?.refreshToken)
    }

    @Test
    fun `when the server ends the session the phone signs out and forgets everything`() = runTest {
        aPhone()
        store.write(StoredSession(SignedInUser("11111111-1111-4111-8111-111111111111", "Anna"), "refresh-revoked"))
        session.restore()
        server.problem("POST /api/auth/refresh", 401, "unauthenticated")
        server.json("GET /api/app-info", """{"name":"wedding-planner","version":"0.1.0"}""")

        val ended = failure { api.get<AppInfo>("/api/app-info") }

        assertTrue(ended is ApiFailure.SignedOut)
        assertEquals(SessionState.SignedOut(ended = true), session.state.value)
        assertNull(store.read())
        assertEquals(1, forgotten)
        // The request that needed the session was never sent.
        assertTrue(server.routes("GET /api/app-info").isEmpty())
    }

    @Test
    fun `no connection while renewing leaves the session as it is`() = runTest {
        aPhone()
        store.write(StoredSession(SignedInUser("11111111-1111-4111-8111-111111111111", "Anna"), "refresh-1"))
        session.restore()

        assertTrue(failure { api.get<AppInfo>("/api/app-info") } is ApiFailure.Unreachable)

        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals("refresh-1", store.read()?.refreshToken)
        assertEquals(0, forgotten)
    }

    // ---------------------------------------------------------------- signing out

    @Test
    fun `signing out tells the server and removes the account from the phone`() = runTest {
        aPhone()
        server.json("POST /api/auth/google", StandInServer.session(refreshToken = "refresh-1"))
        server.nothing("POST /api/auth/sign-out")
        session.restore()
        session.signInWithGoogle("id-token")

        session.signOut()

        assertEquals(SessionState.SignedOut(ended = false), session.state.value)
        assertEquals("""{"refreshToken":"refresh-1"}""", server.routes("POST /api/auth/sign-out").single().body)
        assertNull(store.read())
        assertEquals(1, forgotten)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("Anna"))
        // Nothing of the old session is used again.
        assertTrue(failure { api.get<AppInfo>("/api/app-info") } is ApiFailure.SignedOut)
    }

    @Test
    fun `signing out without a connection still removes everything`() = runTest {
        aPhone()
        server.json("POST /api/auth/google", StandInServer.session())
        session.restore()
        session.signInWithGoogle("id-token")
        server.unreachable("POST /api/auth/sign-out")

        session.signOut()

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.read())
        assertEquals(1, forgotten)
    }

    // ---------------------------------------------------------------- nothing personal in what gets printed

    @Test
    fun `while a sign-out is removing the account no token is handed out and none is asked for`() = runTest {
        val removing = CompletableDeferred<Unit>()
        val mayGoOn = CompletableDeferred<Unit>()
        file = File(folder.root, "session.preferences_pb")
        store = SessionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, AesGcmCipher { key })
        session = Session(
            api = { api },
            store = store,
            forgetEverything = {
                removing.complete(Unit)
                mayGoOn.await()
            },
        )
        api = Api(server.engine, ServerAddress.parse("https://planner.example"), session.accessTokens)
        server.json("POST /api/auth/google", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        session.restore()
        session.signInWithGoogle("id-token")

        val signingOut = async { session.signOut() }
        removing.await()
        // Something of the app still asks for the token in this moment.
        assertNull(session.accessTokens.current())
        assertNull(session.accessTokens.renewed("access-1"))
        mayGoOn.complete(Unit)
        signingOut.await()

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertTrue(server.routes("POST /api/auth/refresh").isEmpty())
    }

    @Test
    fun `a sign-out whose clearing up fails is a sign-out all the same`() = runTest {
        file = File(folder.root, "session.preferences_pb")
        store = SessionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, AesGcmCipher { key })
        session = Session(api = { api }, store = store, forgetEverything = { throw IllegalStateException("the database is busy") })
        api = Api(server.engine, ServerAddress.parse("https://planner.example"), session.accessTokens)
        server.json("POST /api/auth/google", StandInServer.session())
        session.restore()
        session.signInWithGoogle("id-token")

        runCatching { session.signOut() }

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.read())
        assertNull(session.accessTokens.current())
    }

    @Test
    fun `what the session holds prints no name and no token`() {
        val user = SignedInUser("11111111-1111-4111-8111-111111111111", "Anna Kowalska")
        val printed = listOf(user, StoredSession(user, "refresh-secret"), SessionState.SignedIn(user)).joinToString { it.toString() }

        assertFalse(printed.contains("Kowalska"))
        assertFalse(printed.contains("refresh-secret"))
        assertTrue(printed.contains("11111111-1111-4111-8111-111111111111"))
    }
}
