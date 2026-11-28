package io.github.trufelek124.weddingplanner

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.data.PlannerDatabase
import io.github.trufelek124.weddingplanner.reminders.Notifications
import io.github.trufelek124.weddingplanner.reminders.Reminder
import io.github.trufelek124.weddingplanner.reminders.ReminderWork
import java.time.Clock
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.session.AesGcmCipher
import io.github.trufelek124.weddingplanner.signin.GoogleAccounts
import io.github.trufelek124.weddingplanner.signin.GoogleAnswer
import io.github.trufelek124.weddingplanner.widget.WidgetDrawing
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf

/** Google's account picker as a test wants it to answer. */
class StandInGoogle(var answer: GoogleAnswer = GoogleAnswer.Cancelled) : GoogleAccounts {

    /** The OAuth clients the picker was opened for. */
    val askedFor = mutableListOf<String>()
    var forgotten = 0

    override suspend fun idToken(activity: Activity, serverClientId: String): GoogleAnswer {
        askedFor += serverClientId
        return answer
    }

    override suspend fun forget() {
        forgotten += 1
    }
}

/**
 * The phone's notifications as a test wants them: what was shown stays here, whether the phone allows them is set by hand.
 * The reminder that is shown is the one in [shown]; showing another takes its place, as on the phone.
 */
class StandInNotifications(@Volatile var allowed: Boolean = true) : Notifications {

    /** Every reminder that was shown, the first first. */
    val shownEver = java.util.concurrent.CopyOnWriteArrayList<Reminder>()

    /** The reminder that is on the screen of the phone now; null when there is none. */
    @Volatile
    var shown: Reminder? = null
        private set

    override fun allowed(): Boolean = allowed

    override fun show(reminder: Reminder) {
        shownEver += reminder
        shown = reminder
    }

    override fun clear() {
        shown = null
    }
}

/** The once-a-day work as a test wants it: what it was asked for is written down. */
class StandInReminderWork : ReminderWork {

    /** The times it was set for, in order; [stopped] is counted from the last of them. */
    val setFor = java.util.concurrent.CopyOnWriteArrayList<LocalTime>()

    @Volatile
    var running = false
        private set

    var stops = 0
        private set

    override fun everyDay(at: LocalTime) {
        setFor += at
        running = true
    }

    /** The times the work was asked to be kept for, in order. */
    val keptFor = java.util.concurrent.CopyOnWriteArrayList<LocalTime>()

    override fun keepEveryDay(at: LocalTime) {
        keptFor += at
        running = true
    }

    override fun stop() {
        stops += 1
        running = false
    }
}

/** The widget's drawing as a test wants it: nothing is drawn, and how often the phone was asked to draw it is counted. */
class StandInWidget : WidgetDrawing {

    private val asked = java.util.concurrent.atomic.AtomicInteger()

    /** How often the widget was asked to be drawn anew. */
    val redraws: Int get() = asked.get()

    override fun redraw() {
        asked.incrementAndGet()
    }
}

/** A clock a test sets by hand. The phone stands in Warsaw, where it is an hour later than this says in winter. */
class StandInClock(var now: Instant = Instant.parse("2026-11-18T13:05:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneId.of("Europe/Warsaw")
    override fun withZone(zone: ZoneId): Clock = this
}

/**
 * A phone for tests: the app's container with a stand-in server, a key that is not in a Keystore,
 * a stand-in for Google and a clock set by hand. What it stores goes to [folder], so that a second
 * phone made on the same folder is the same phone after a restart.
 */
class StandInPhone(
    private val folder: File,
    val server: StandInServer = StandInServer(),
    val google: StandInGoogle = StandInGoogle(),
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
    val clock: StandInClock = StandInClock(),
    val notifications: StandInNotifications = StandInNotifications(),
    val reminderWork: StandInReminderWork = StandInReminderWork(),
    val widget: StandInWidget = StandInWidget(),
) {

    private val running = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** How often the app asked for what waits to be sent once there is a connection. */
    var askedToSendLater = 0
        private set

    val stored: File = File(folder, "session.preferences_pb")

    val reminderSettings: File = File(folder, "reminders.preferences_pb")

    private val database: PlannerDatabase =
        Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PlannerDatabase::class.java, File(folder, "planner.db").path).build()

    val container = AppContainer(
        server = ServerAddress.parse("https://planner.example"),
        engine = server.engine,
        sessionFile = PreferenceDataStoreFactory.create(scope = running) { stored },
        cipher = AesGcmCipher { key },
        googleAccounts = google,
        database = database,
        sendLater = { askedToSendLater += 1 },
        reminderFile = PreferenceDataStoreFactory.create(scope = running) { reminderSettings },
        notifications = notifications,
        reminderWork = reminderWork,
        resources = ApplicationProvider.getApplicationContext<Context>().resources,
        widgetDrawing = widget,
        shareFolder = File(folder, "shares"),
        pictureFolder = File(folder, "pictures"),
        clock = clock,
    )

    /** Everything the phone keeps in its files, read as text: to look for what must not be there. */
    fun everythingKept(): String = folder.walkTopDown().filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }

    /**
     * Switches the phone off once the app has come to rest: with the screen gone, whatever it was
     * still doing with the server or the database is given a moment to end, so that the database
     * is not closed under it.
     */
    fun offWhenAtRest() {
        val until = System.currentTimeMillis() + 5_000
        var restful = 0
        while (restful < 5 && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            restful = if (container.planner.quiet && container.pictures.quiet) restful + 1 else 0
            Thread.sleep(10)
        }
        off()
    }

    /** Switches the phone off. What it stored stays in the folder. */
    fun off() {
        runBlocking { running.coroutineContext.job.cancelAndJoin() }
        database.close()
    }

    /** The same phone, switched on again: the same storage, key, clock and server, nothing in memory. */
    fun restarted(): StandInPhone {
        off()
        return StandInPhone(folder, server, google, key, clock, notifications, reminderWork, widget)
    }
}

/**
 * Switches a test's phone off after the test, and after the screen is gone: give it an order
 * between the folder's and the screen's rule. Closing the phone while the screen is still up
 * closes the database under an app that is still at work.
 */
class SwitchedOffAfterwards(private val phone: () -> StandInPhone?) : ExternalResource() {
    override fun after() {
        phone()?.offWhenAtRest()
    }
}
