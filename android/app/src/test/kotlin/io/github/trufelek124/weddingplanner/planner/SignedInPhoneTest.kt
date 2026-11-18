package io.github.trufelek124.weddingplanner.planner

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.SwitchedOffAfterwards
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.ui.AppRoot
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * What the tests of a signed-in person's screens share: a stand-in phone on which someone is
 * signed in, and the ways a person moves about on it. A test class sets up its own wedding.
 */
abstract class SignedInPhoneTest {

    @get:Rule(order = 0)
    val folder = TemporaryFolder()

    @get:Rule(order = 1)
    val switchedOff = SwitchedOffAfterwards { if (::phone.isInitialized) phone else null }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected lateinit var phone: StandInPhone
    protected val server get() = phone.server

    @Before
    fun aSignedInPhone() {
        phone = StandInPhone(folder.root)
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        server.json("GET /api/auth/config", """{"googleClientId":"client-1.apps.example","devLoginEnabled":false}""")
        server.planner()
        runBlocking { phone.container.session.signInForDevelopment("anna@example.com") }
    }

    /** Opens the app and waits for Home. */
    protected fun open(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PlannerTheme { AppRoot(phone.container, debugBuild = false) }
            }
        }
        waitFor("Anna & Marek")
    }

    /** The planner was loaded, and the app was closed since: the same phone, nothing in memory. */
    protected fun aPhoneThatLoadedEarlier() {
        runBlocking { phone.container.planner.load() }
        phone = phone.restarted()
    }

    /**
     * Waits for something that is not on the screen, such as a request having been sent. The app's
     * own work runs between the looks: left alone, it would wait for the test and the test for it.
     */
    protected fun waitUntil(what: () -> Boolean) {
        compose.waitUntil(WAIT) {
            compose.waitForIdle()
            what()
        }
        compose.waitForIdle()
    }

    protected fun waitFor(text: String) {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    protected fun waitUntilGone(text: String) {
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    /** One line of a list, told by what it says: a screen reader gets all of it in one go. */
    protected fun line(vararg says: String): SemanticsMatcher = says.map { hasText(it) }.reduce { all, one -> all and one }

    /** Presses a place in the bar at the bottom and waits until its screen is there. */
    protected fun goTo(place: String) {
        compose.onNode(hasText(place) and isSelectable()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText(place) and isHeading()).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Scrolls the list on the screen until what is asked for is in view. */
    protected fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    protected companion object {
        const val WAIT = 10_000L
    }
}
