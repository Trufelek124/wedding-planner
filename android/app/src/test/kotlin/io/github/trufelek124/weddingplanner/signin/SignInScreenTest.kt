package io.github.trufelek124.weddingplanner.signin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import io.github.trufelek124.weddingplanner.ui.picture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the sign-in screen looks like in each of its states; the pictures are there to be looked at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(ui: SignInUi, ended: Boolean = false) {
        compose.setContent {
            PlannerTheme {
                SignInScreen(ui = ui, ended = ended, server = "https://planner.example", onGoogle = {}, onDevelopmentSignIn = {}, onTryAgain = {})
            }
        }
    }

    private val ready = SignInUi(ready = true, googleClientId = "client-1.apps.example", devLoginOffered = true)

    @Test
    fun `offers Google, and the development sign-in below it`() {
        show(ready)

        compose.onNodeWithText("Sign in with Google").assertIsDisplayed()
        compose.onNodeWithText("Development sign-in").assertIsDisplayed()
        compose.onNodeWithText("Server: https://planner.example").assertIsDisplayed()
        compose.picture("sign-in")
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `reads in the dark as well`() {
        show(ready.copy(message = SignInMessage.NOT_ALLOWED))

        compose.onNodeWithText("Sign in with Google").assertIsDisplayed()
        compose.picture("sign-in-dark")
    }

    @Test
    fun `while a sign-in is under way nothing can be pressed twice`() {
        show(ready.copy(busy = true))

        compose.onNodeWithText("Signing in…").assertIsNotEnabled()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.picture("sign-in-busy")
    }

    @Test
    fun `only Google where there is no development sign-in`() {
        show(ready.copy(devLoginOffered = false))

        compose.onNodeWithText("Sign in with Google").assertIsDisplayed()
        compose.onNodeWithText("Development sign-in").assertDoesNotExist()
        compose.picture("sign-in-release")
    }
}
