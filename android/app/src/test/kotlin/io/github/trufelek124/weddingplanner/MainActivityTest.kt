package io.github.trufelek124.weddingplanner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.trufelek124.weddingplanner.ui.PHONE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app as the phone starts it: the application class from the manifest, the real container and
 * the real storage. It does not wait for a server; there is none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class MainActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `starts, and shows the sign-in to someone who is not signed in`() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Sign in to see your plan.")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Wedding planner").assertIsDisplayed()
        compose.onNodeWithText("Server: ${BuildConfig.SERVER_URL}").assertIsDisplayed()
    }
}
