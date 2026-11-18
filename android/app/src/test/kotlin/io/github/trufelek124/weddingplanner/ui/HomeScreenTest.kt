package io.github.trufelek124.weddingplanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.trufelek124.weddingplanner.ServerAddress
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the screen on the JVM (Robolectric), so it is checked without a phone or an emulator. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows the app's name and the server it was built for`() {
        compose.setContent {
            MaterialTheme {
                HomeScreen(ServerAddress.parse("https://planner.example"))
            }
        }

        compose.onNodeWithText("Wedding planner").assertIsDisplayed()
        compose.onNodeWithText("Server: https://planner.example").assertIsDisplayed()
    }

    @Test
    fun `can be looked at as a picture`() {
        compose.setContent {
            MaterialTheme {
                HomeScreen(ServerAddress.parse("https://planner.example"))
            }
        }

        compose.picture("home")

        val picture = File("build/outputs/roborazzi/home.png")
        assertTrue("the picture was written", picture.isFile && picture.length() > 5_000)
    }
}
