package io.github.trufelek124.weddingplanner.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.MainActivity
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.SwitchedOffAfterwards
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import io.github.trufelek124.weddingplanner.ui.PHONE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The notification as the phone gets it: the real one, drawn by Robolectric's notification manager, and what pressing it
 * does when the app starts from it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class NotificationsOnThePhoneTest {

    @get:Rule(order = 0)
    val folder = TemporaryFolder()

    @get:Rule(order = 1)
    val switchedOff = SwitchedOffAfterwards { if (::phone.isInitialized) phone else null }

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    private lateinit var phone: StandInPhone
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifications = NotificationsOnThePhone(context)

    private val several = Reminder("3 things are due", "Book the band — overdue since 12 Nov", listOf("Book the band — overdue since 12 Nov", "Choose the cake — tomorrow"), "3 things are due", 3)

    private fun shown(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun `it shows one notification on a channel called Reminders, private on a lock screen, that opens the tasks`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.show(several)

        val notification = shown().single()
        assertEquals("3 things are due", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Book the band — overdue since 12 Nov", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Book the band — overdue since 12 Nov", "Choose the cake — tomorrow"), notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() })
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(Notification.CATEGORY_REMINDER, notification.category)
        // What a locked phone may show instead has no names in it.
        assertEquals("3 things are due", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(null, notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        val channel = manager.getNotificationChannel(notification.channelId)
        assertEquals("Reminders", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        val opened = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, opened.component!!.className)
        assertEquals(OpenAt.TASKS, opened.getStringExtra(OpenAt.EXTRA))
    }

    @Test
    fun `a second one takes the place of the first, and clearing takes it away`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.show(several)
        notifications.show(Reminder("Choose the cake is due tomorrow", null, emptyList(), "1 thing is due", 1))

        assertEquals("Choose the cake is due tomorrow", shown().single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())

        notifications.clear()

        assertTrue(shown().isEmpty())
    }

    @Test
    fun `nothing is shown without the permission, and the phone's own switch is what allowed says`() {
        notifications.show(several)
        assertTrue(shown().isEmpty())

        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(notifications.allowed())
        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(notifications.allowed())
    }

    @Test
    fun `the app started from the notification opens at the tasks`() {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        phone.server.json("POST /api/auth/dev", StandInServer.session())
        phone.server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        phone.server.planner()
        LivingPlanner(phone.server, entries = listOf(StandIn.entry("t1", "Pay the venue's deposit", dueDate = "2026-11-18")))
        runBlocking { phone.container.session.signInForDevelopment("anna@example.com") }
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.show(several)
        val intent = shadowOf(shown().single().contentIntent).savedIntent

        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Tasks") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
            assertNotNull(compose.onAllNodes(hasText("Pay the venue's deposit", substring = true)).fetchSemanticsNodes().firstOrNull())
        }
    }
}
