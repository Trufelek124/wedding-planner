package io.github.trufelek124.weddingplanner.planner

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.reminders.Ahead
import io.github.trufelek124.weddingplanner.ui.AppRoot
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import io.github.trufelek124.weddingplanner.ui.PlannerTheme
import io.github.trufelek124.weddingplanner.ui.picture
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * The reminders screen as a person uses it: the row in More, the switch that asks the phone for the permission, the
 * time, how far ahead, and what is said where the phone does not allow notifications. The phone is a stand-in whose
 * answer to the question of the permission a test chooses. Everything here is made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class RemindersScreenTest : SignedInPhoneTest() {

    /** The phone's answer to "may the planner show notifications?"; what it was asked is written down. */
    private class Asked(var grant: Boolean, val allow: () -> Unit) : ActivityResultRegistry() {
        val questions = mutableListOf<String>()

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            questions += input.toString()
            if (grant) allow()
            dispatchResult(requestCode, grant)
        }
    }

    private lateinit var asked: Asked

    @Before
    fun theirPlanner() {
        asked = Asked(grant = true, allow = { phone.notifications.allowed = true })
        LivingPlanner(server, entries = listOf(StandIn.entry("t1", "Pay the venue's deposit", dueDate = "2026-11-18")))
        phone.notifications.allowed = false
    }

    private fun openApp(fontScale: Float = 1f) {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner { override val activityResultRegistry = asked },
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
            ) {
                PlannerTheme { AppRoot(phone.container, debugBuild = false) }
            }
        }
        waitFor("Anna & Marek")
    }

    private fun openReminders() {
        goTo("More")
        compose.onNode(hasText("Reminders") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Reminders") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private val theSwitch get() = compose.onNode(isToggleable())

    private fun ahead(words: String) = hasText(words) and isSelectable()

    private fun settings() = runBlocking { phone.container.reminding.settings.first() }

    @Test
    fun `More has a row for the reminders, which say Off to begin with`() {
        openApp()
        goTo("More")

        compose.onNode(line("Reminders", "Off") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `the screen begins off, at nine, for today and tomorrow, and says what a reminder is`() {
        openApp()
        openReminders()

        theSwitch.assertIsOff()
        compose.onNodeWithText("Remind me of what is due").assertIsDisplayed()
        compose.onNodeWithText("Around 9:00 every day").assertIsDisplayed()
        compose.onNode(ahead("Due today or tomorrow")).assertIsSelected()
        for (other in listOf("Due today", "Due within 3 days", "Due within 7 days")) {
            compose.onNode(ahead(other)).assertIsNotSelected()
        }
        compose.onNode(hasText("one notification names the tasks and steps that are overdue or due soon", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("The phone decides the exact minute", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("The phone does not allow notifications", substring = true).assertDoesNotExist()
    }

    @Test
    fun `switching it on asks the phone for the permission, then switches on and says so in More`() {
        openApp()
        openReminders()

        theSwitch.performClick()

        waitUntil { settings().on }
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked.questions)
        theSwitch.assertIsOn()
        assertEquals(listOf(LocalTime.of(9, 0)), phone.reminderWork.setFor)
        compose.onNodeWithText("The phone does not allow notifications", substring = true).assertDoesNotExist()
        compose.picture("reminders-on")

        pressBackButton()
        goTo("More")
        compose.onNode(line("Reminders", "On — around 9:00 every day") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `where the phone already allows them it is not asked`() {
        phone.notifications.allowed = true
        openApp()
        openReminders()

        theSwitch.performClick()

        waitUntil { settings().on }
        assertTrue(asked.questions.isEmpty())
    }

    @Test
    fun `refused, the switch stays off, the screen says so and leads to the phone's settings`() {
        asked.grant = false
        openApp()
        openReminders()

        theSwitch.performClick()

        waitFor("The phone does not allow notifications from the planner, so no reminder can be shown. You can allow them in the phone's settings.")
        theSwitch.assertIsOff()
        assertFalse(settings().on)
        assertTrue(phone.reminderWork.setFor.isEmpty())
        compose.picture("reminders-refused")

        compose.onNode(hasText("Open the notification settings") and hasClickAction()).performClick()

        val opened: Intent = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(ApplicationProvider.getApplicationContext<Application>().packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `allowed, then taken away in the phone's settings, the screen says so when it is looked at again`() {
        openApp()
        openReminders()
        theSwitch.performClick()
        waitUntil { settings().on }
        compose.onNodeWithText("The phone does not allow notifications", substring = true).assertDoesNotExist()

        phone.notifications.allowed = false
        pressBackButton()
        goTo("More")
        compose.onNode(hasText("Reminders") and hasClickAction()).performClick()

        waitFor("The phone does not allow notifications from the planner, so no reminder can be shown. You can allow them in the phone's settings.")
        // It is still what the person chose; the phone does not let it be shown.
        theSwitch.assertIsOn()
        compose.onNode(hasText("Open the notification settings") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `how far ahead is chosen from four, and kept`() {
        openApp()
        openReminders()

        for ((words, chosen) in listOf("Due within 3 days" to Ahead.THREE_DAYS, "Due within 7 days" to Ahead.SEVEN_DAYS, "Due today" to Ahead.TODAY, "Due today or tomorrow" to Ahead.TOMORROW)) {
            compose.onNode(ahead(words)).performClick()
            waitUntil { settings().ahead == chosen }
            compose.onNode(ahead(words)).assertIsSelected()
        }
    }

    @Test
    fun `the time is chosen on a clock, said in words, and sets the work again`() {
        phone.notifications.allowed = true
        openApp()
        openReminders()
        theSwitch.performClick()
        waitUntil { settings().on }

        compose.onNode(hasText("Change the time") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.picture("reminders-time")
        compose.onNode(hasContentDescription("18 hours") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasContentDescription("30 minutes") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("30 minutes") and hasClickAction()).performClick()
        compose.onNode(hasText("Set the time") and hasClickAction()).performClick()

        waitUntil { settings().at == LocalTime.of(18, 30) }
        compose.onNodeWithText("Around 18:30 every day").assertIsDisplayed()
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 30)), phone.reminderWork.setFor)
        // The person sees it in More as well, and it stays when the app is started again.
        pressBackButton()
        goTo("More")
        compose.onNode(line("Reminders", "On — around 18:30 every day") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `leaving the clock without setting changes nothing`() {
        openApp()
        openReminders()

        compose.onNode(hasText("Change the time") and hasClickAction()).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("18 hours") and hasClickAction()).performClick()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()

        compose.waitUntil(WAIT) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Around 9:00 every day").assertIsDisplayed()
        assertEquals(LocalTime.of(9, 0), settings().at)
    }

    @Test
    fun `the screen reads in the dark, and at a larger size`() {
        phone.notifications.allowed = true
        openApp(fontScale = 1.5f)
        openReminders()
        theSwitch.performClick()
        waitUntil { settings().on }

        compose.picture("reminders-large")
    }

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `at night too`() {
        asked.grant = false
        openApp()
        openReminders()
        theSwitch.performClick()
        waitFor("The phone does not allow notifications from the planner, so no reminder can be shown. You can allow them in the phone's settings.")

        compose.picture("reminders-night")
    }

    @Test
    fun `nothing of the plan is written to the log while the reminders are set`() {
        ShadowLog.clear()
        openApp()
        openReminders()
        theSwitch.performClick()
        waitUntil { settings().on }
        compose.onNode(ahead("Due within 7 days")).performClick()
        waitUntil { settings().ahead == Ahead.SEVEN_DAYS }

        val written = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString().orEmpty()}" }
        for (theirs in listOf("deposit", "venue", "Anna", "Marek", "access-1")) {
            assertFalse("the log holds \"$theirs\"", written.contains(theirs, ignoreCase = true))
        }
    }

    private fun pressBackButton() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
}
