package io.github.trufelek124.weddingplanner.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import io.github.trufelek124.weddingplanner.ui.picture
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class AccountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(wedding: WeddingUi) {
        compose.setContent {
            PlannerTheme { AccountScreen(userName = "Anna", wedding = wedding, onTryAgain = {}, onSignOut = {}) }
        }
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `shows whose wedding it is and when, in the dark as well`() {
        show(WeddingUi.Ready(listOf("Anna", "Marek"), LocalDate.of(2027, 6, 19)))

        compose.onNodeWithText("Anna & Marek").assertIsDisplayed()
        compose.onNodeWithText("Saturday, 19 June 2027").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
        compose.picture("account-dark")
    }

    @Test
    fun `says that it waits for a connection, with a way to try again`() {
        show(WeddingUi.Unreachable)

        compose.onNodeWithText("The server cannot be reached. What you see here will load once there is a connection.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Signed in as Anna").assertIsDisplayed()
        compose.picture("account-unreachable")
    }

    @Test
    fun `a wedding one person has set up so far shows that person`() {
        show(WeddingUi.Ready(listOf("Anna"), null))

        compose.onNodeWithText("Anna").assertIsDisplayed()
        compose.onNodeWithText("The day is not fixed yet.").assertIsDisplayed()
    }
}
