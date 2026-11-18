package io.github.trufelek124.weddingplanner.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.trufelek124.weddingplanner.StandInGoogle
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.SwitchedOffAfterwards
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.signin.GoogleAnswer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Signing in, the first screen after it, and signing out, as a person goes through them, against a
 * stand-in server. People, addresses and tokens are made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class AppRootTest {

    @get:Rule(order = 0)
    val folder = TemporaryFolder()

    @get:Rule(order = 1)
    val switchedOff = SwitchedOffAfterwards { if (::phone.isInitialized) phone else null }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var phone: StandInPhone
    private val server get() = phone.server

    @Before
    fun aPhone() {
        phone = StandInPhone(folder.root)
        server.json("GET /api/auth/config", """{"googleClientId":"client-1.apps.example","devLoginEnabled":true}""")
        server.json("POST /api/auth/google", StandInServer.session())
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        server.planner()
    }

    /** Someone signed in earlier, and the app was closed since: nothing is left in memory. */
    private fun aPhoneThatWasSignedIn() {
        runBlocking { phone.container.session.signInForDevelopment("anna@example.com") }
        phone = phone.restarted()
        server.calls.clear()
    }

    private fun open(debugBuild: Boolean = true) {
        compose.setContent { PlannerTheme { AppRoot(phone.container, debugBuild) } }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitUntilGone(text: String) {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }
    }

    private fun signInForDevelopment(email: String = "anna@example.com") {
        waitFor("Development sign-in")
        compose.onNodeWithText("E-mail address").performTextInput(email)
        compose.onNodeWithText("Sign in").performClick()
    }

    // ---------------------------------------------------------------- signing in

    @Test
    fun `signing in with Google hands Google's proof to the server and opens the planner`() {
        phone.google.answer = GoogleAnswer.IdToken("proof-from-google")
        open()

        waitFor("Sign in with Google")
        compose.onNodeWithText("Sign in with Google").performClick()

        waitFor("Anna & Marek")
        compose.onNodeWithText("Saturday 19 June 2027").assertIsDisplayed()
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()
        // The picker is opened for the server's own OAuth client, or the server could not check the proof.
        assertEquals(listOf("client-1.apps.example"), phone.google.askedFor)
        assertEquals("""{"idToken":"proof-from-google"}""", server.routes("POST /api/auth/google").single().body)
        assertEquals("Bearer access-1", server.routes("GET /api/wedding").single().authorization)
        compose.onNodeWithText("213 days to go").assertIsDisplayed()
    }

    @Test
    fun `a debug build signs in with an address where the server offers it`() {
        open()

        signInForDevelopment(" anna@example.com ")

        waitFor("Anna & Marek")
        assertEquals("""{"email":"anna@example.com"}""", server.routes("POST /api/auth/dev").single().body)
    }

    @Test
    fun `a release build never offers the development sign-in`() {
        open(debugBuild = false)

        waitFor("Sign in with Google")

        compose.onNodeWithText("Development sign-in").assertDoesNotExist()
        compose.onNodeWithText("E-mail address").assertDoesNotExist()
    }

    @Test
    fun `a server without the development login shows only Google`() {
        server.json("GET /api/auth/config", """{"googleClientId":"client-1.apps.example","devLoginEnabled":false}""")
        open()

        waitFor("Sign in with Google")

        compose.onNodeWithText("Development sign-in").assertDoesNotExist()
    }

    @Test
    fun `a server without Google sign-in says so instead of showing a button that cannot work`() {
        server.json("GET /api/auth/config", """{"googleClientId":null,"devLoginEnabled":true}""")
        open()

        waitFor("Signing in with Google is not set up on the server yet.")

        compose.onNodeWithText("Sign in with Google").assertDoesNotExist()
        compose.onNodeWithText("Development sign-in").assertIsDisplayed()
        compose.picture("sign-in-without-google")
    }

    @Test
    fun `someone who is not one of the owners is told so and stays signed out`() {
        server.problem("POST /api/auth/google", 403, "account-not-allowed")
        phone.google.answer = GoogleAnswer.IdToken("proof-of-a-stranger")
        open()
        waitFor("Sign in with Google")

        compose.onNodeWithText("Sign in with Google").performClick()

        waitFor("This account may not use this planner. Sign in with the account the planner was set up for.")
        compose.onNodeWithText("Sign in with Google").assertIsEnabled()
        assertTrue(server.routes("GET /api/wedding").isEmpty())
        assertNull(runBlocking { phone.container.session.accessTokens.current() })
        compose.picture("sign-in-not-allowed")
    }

    @Test
    fun `closing Google's picker says nothing, and a phone without a Google account is told what to do`() {
        open()
        waitFor("Sign in with Google")

        compose.onNodeWithText("Sign in with Google").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Signing in did not work. Try again.").assertDoesNotExist()
        compose.onNodeWithText("Sign in with Google").assertIsEnabled()

        phone.google.answer = GoogleAnswer.NoAccount
        compose.onNodeWithText("Sign in with Google").performClick()
        waitFor("No Google account was found on this phone. Add one in the phone's settings, then try again.")

        phone.google.answer = GoogleAnswer.Failed
        compose.onNodeWithText("Sign in with Google").performClick()
        waitFor("Signing in did not work. Try again.")
        assertTrue(server.routes("POST /api/auth/google").isEmpty())
    }

    @Test
    fun `the development sign-in asks for an address before it asks the server`() {
        open()
        waitFor("Development sign-in")

        compose.onNodeWithText("Sign in").performClick()

        waitFor("Enter an e-mail address.")
        assertTrue(server.routes("POST /api/auth/dev").isEmpty())

        server.problem("POST /api/auth/dev", 400, "bad-request")
        compose.onNodeWithText("E-mail address").performTextInput("not an address")
        compose.onNodeWithText("Sign in").performClick()
        compose.waitUntil(WAIT) { server.routes("POST /api/auth/dev").isNotEmpty() }
        waitFor("Enter an e-mail address.")
    }

    @Test
    fun `a server that cannot be reached is said in words, and Try again asks it once more`() {
        server.unreachable("GET /api/auth/config")
        open()

        waitFor("The server cannot be reached. Check your connection and try again.")
        compose.onNodeWithText("Sign in with Google").assertDoesNotExist()
        compose.picture("sign-in-unreachable")

        server.json("GET /api/auth/config", """{"googleClientId":"client-1.apps.example","devLoginEnabled":false}""")
        compose.onNodeWithText("Try again").performClick()

        waitFor("Sign in with Google")
        compose.onNodeWithText("The server cannot be reached. Check your connection and try again.").assertDoesNotExist()
    }

    @Test
    fun `losing the connection in the middle of signing in is said in words too`() {
        server.unreachable("POST /api/auth/dev")
        open()

        signInForDevelopment()

        waitFor("The server cannot be reached. Check your connection and try again.")
        compose.onNodeWithText("Sign in").assertIsEnabled()
    }

    // ---------------------------------------------------------------- signed in

    @Test
    fun `a wedding that is not set up yet is said in words`() {
        server.problem("GET /api/wedding", 404, "wedding-not-set-up")
        open()

        signInForDevelopment()

        waitFor("The wedding is not set up yet. Set it up in the web app first, then come back here.")
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
        compose.picture("nothing-yet-wedding-not-set-up")
    }

    @Test
    fun `a third person is told that the planner belongs to two others`() {
        server.problem("GET /api/wedding", 403, "wedding-full")
        open()

        signInForDevelopment()

        waitFor("This planner already belongs to two people, and you are not one of them.")
        compose.onNodeWithText("Sign out").assertIsDisplayed()
    }

    @Test
    fun `a wedding without a day says that the day is not fixed`() {
        server.planner(wedding = StandIn.wedding(date = null), home = StandIn.home(daysLeft = null, weddingDate = null))
        open()

        signInForDevelopment()

        waitFor("Anna & Marek")
        compose.onNodeWithText("The day is not fixed yet.").assertIsDisplayed()
    }

    @Test
    fun `without a connection the wedding waits, and Try again loads it`() {
        server.unreachable("GET /api/wedding")
        open()
        signInForDevelopment()
        waitFor("The server cannot be reached. What you see here will load once there is a connection.")

        server.planner()
        compose.onNodeWithText("Try again").performClick()

        waitFor("Anna & Marek")
    }

    @Test
    fun `an error the app does not know is still an error one can retry`() {
        server.problem("GET /api/wedding", 500, "something-from-a-newer-server")
        open()

        signInForDevelopment()

        waitFor("Your wedding could not be loaded. Try again.")
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- staying signed in, and signing out

    @Test
    fun `whoever was signed in still is when the app is opened again`() {
        aPhoneThatWasSignedIn()

        open()

        waitFor("Anna & Marek")
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()
        assertTrue(server.routes("POST /api/auth/dev").isEmpty())
        // The access token was only in memory: the first call renews the session with what the phone kept.
        assertEquals("""{"refreshToken":"refresh-1"}""", server.routes("POST /api/auth/refresh").single().body)
        assertEquals("Bearer access-2", server.routes("GET /api/wedding").single().authorization)
    }

    @Test
    fun `signing out tells the server and removes the account from the phone`() {
        open()
        signInForDevelopment()
        waitFor("Anna & Marek")

        compose.onNodeWithText("Sign out").performClick()

        waitFor("Sign in with Google")
        compose.onNodeWithText("Anna & Marek").assertDoesNotExist()
        compose.onNodeWithText("You were signed out. Sign in again.").assertDoesNotExist()
        compose.waitUntil(WAIT) { server.routes("POST /api/auth/sign-out").isNotEmpty() }
        assertEquals("""{"refreshToken":"refresh-1"}""", server.routes("POST /api/auth/sign-out").single().body)
        assertEquals(1, phone.google.forgotten)
        val kept = phone.everythingKept()
        assertFalse(kept.contains("Anna"))
        assertFalse(kept.contains("Marek"))
        assertFalse(kept.contains("11111111"))
        assertFalse(kept.contains("refresh"))
    }

    @Test
    fun `the next person to sign in sees nothing of the one before`() {
        open()
        signInForDevelopment()
        waitFor("Anna & Marek")
        compose.onNodeWithText("Sign out").performClick()
        waitFor("Development sign-in")

        server.json("POST /api/auth/dev", StandInServer.session(accessToken = "access-of-olga", refreshToken = "refresh-of-olga", name = "Olga"))
        server.problem("GET /api/wedding", 403, "wedding-full")
        signInForDevelopment("olga@example.com")

        waitFor("This planner already belongs to two people, and you are not one of them.")
        compose.onNodeWithText("Signed in as Olga").assertIsDisplayed()
        compose.onNodeWithText("Anna & Marek").assertDoesNotExist()
        assertEquals("Bearer access-of-olga", server.routes("GET /api/wedding").last().authorization)
    }

    @Test
    fun `when the server ends the session the sign-in comes back and says why`() {
        aPhoneThatWasSignedIn()
        // The server no longer accepts this phone's sign-in.
        server.problem("POST /api/auth/refresh", 401, "unauthenticated")

        open()

        waitFor("You were signed out. Sign in again.")
        compose.onNodeWithText("Sign in with Google").assertIsDisplayed()
        compose.onNodeWithText("Signed in as Anna").assertDoesNotExist()
        assertTrue(server.routes("GET /api/wedding").isEmpty())
        assertEquals(1, phone.google.forgotten)
        assertFalse(phone.stored.readBytes().toString(Charsets.ISO_8859_1).contains("Anna"))
        compose.picture("sign-in-after-the-session-ended")
    }

    @Test
    fun `a connection that is missing does not sign anyone out`() {
        aPhoneThatWasSignedIn()
        server.unreachable("POST /api/auth/refresh")

        open()

        waitFor("The server cannot be reached. What you see here will load once there is a connection.")
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()

        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        compose.onNodeWithText("Try again").performClick()
        waitFor("Anna & Marek")
    }

    // ---------------------------------------------------------------- nothing personal in the log

    @Test
    fun `nothing of the person or the session is written to the log`() {
        ShadowLog.clear()
        phone.google.answer = GoogleAnswer.IdToken("proof-from-google")
        open()
        waitFor("Sign in with Google")
        compose.onNodeWithText("Sign in with Google").performClick()
        waitFor("Anna & Marek")
        compose.onNodeWithText("Sign out").performClick()
        waitFor("Development sign-in")
        server.problem("POST /api/auth/dev", 403, "account-not-allowed")
        signInForDevelopment("stranger@example.com")
        waitFor("This account may not use this planner. Sign in with the account the planner was set up for.")
        server.json("POST /api/auth/dev", StandInServer.session())
        compose.onNodeWithText("Sign in").performClick()
        waitFor("Anna & Marek")

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (personal in listOf("Anna", "Marek", "example.com", "proof-from-google", "access-1", "refresh-1", "11111111-1111")) {
            assertFalse("the log holds \"$personal\"", written.contains(personal))
        }
    }

    private companion object {
        const val WAIT = 10_000L
    }
}
