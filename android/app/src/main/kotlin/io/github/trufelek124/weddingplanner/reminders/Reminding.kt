package io.github.trufelek124.weddingplanner.reminders

import android.util.Log
import io.github.trufelek124.weddingplanner.data.PlannerRepository
import io.github.trufelek124.weddingplanner.session.Session
import io.github.trufelek124.weddingplanner.session.SessionState
import io.github.trufelek124.weddingplanner.planner.todayAt
import java.time.Clock
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * What the phone itself does for reminders and the app cannot: showing a notification, knowing whether the phone allows
 * them, and doing work once a day. They come into the container from outside, as the network and the Keystore do.
 */
interface Notifications {

    /** Whether the phone lets the app show notifications: the permission (Android 13 and later) and the phone's own switch for the app. */
    fun allowed(): Boolean

    /** Shows the reminder, in the place of the one that may still be shown from yesterday. */
    fun show(reminder: Reminder)

    /** Takes the reminder away if it is still shown. */
    fun clear()
}

/** The daily work, as the phone does it. */
interface ReminderWork {

    /** Remind once a day from now on, around [at]; the first time at the next [at]. Replaces what was set before. */
    fun everyDay(at: LocalTime)

    /**
     * Makes sure the daily work is set, around [at]; one that is set already is left as it is. The widget (S12) is brought
     * up to date by the same work, so it goes on whether or not reminders are switched on.
     */
    fun keepEveryDay(at: LocalTime)

    /** Stops reminding. */
    fun stop()
}

/** What a run of the daily work came to. */
enum class Reminded {
    /** Reminders are switched off. */
    OFF,

    /** Nobody is signed in on this phone. */
    SIGNED_OUT,

    /** The phone does not allow the app's notifications. */
    NOT_ALLOWED,

    /** The phone holds nothing of the planner yet, so there is nothing to say. */
    NO_COPY,

    /** Nothing is due: a reminder that was still shown has been taken away. */
    NOTHING_DUE,

    /** One was shown for this day already. */
    ALREADY_SHOWN,

    /** A reminder was shown. */
    SHOWN,
}

/**
 * Reminders of what is due (S11): the switch, the time and the days, and the daily work.
 *
 * The phone's own parts come in from outside ([notifications], [work]); what is due is worked out by [dueThings] from the
 * planner's copy, after the copy has been brought up to date if the server can be reached.
 */
class Reminding(
    private val store: ReminderSettingsStore,
    private val notifications: Notifications,
    private val work: ReminderWork,
    private val session: Session,
    private val planner: PlannerRepository,
    private val clock: Clock,
    private val resources: android.content.res.Resources,
    /** Draws the widget anew: the daily work does it too, so that a new day reaches it whether or not reminders are on. */
    private val redrawWidget: () -> Unit = {},
) {

    val settings: Flow<ReminderSettings> = store.settings

    /** Whether the phone lets the app show notifications at this moment. */
    fun allowed(): Boolean = notifications.allowed()

    /**
     * Switches reminders on and sets the daily work going. False, and nothing changes, while the phone does not allow
     * notifications: the screen has to ask for that first.
     */
    suspend fun switchOn(): Boolean {
        if (!notifications.allowed()) {
            return false
        }
        val now = store.update { it.copy(on = true) }
        work.everyDay(now.at)
        return true
    }

    /** No more reminders. The daily work goes on: it also keeps the widget up to date (S12). */
    suspend fun switchOff() {
        store.update { it.copy(on = false) }
        notifications.clear()
    }

    /** Makes sure the daily work is set while somebody is signed in: reminders or not, it brings the widget up to date. */
    suspend fun keepDailyWork() {
        val at = store.read().at
        // Signing out may have come in between: its sign-out has stopped the work, and it stays stopped.
        if (session.state.value is SessionState.SignedIn) {
            work.keepEveryDay(at)
        }
    }

    /** Holds from the next run on: the daily work is set again for the new time. */
    suspend fun setTime(at: LocalTime) {
        val now = store.update { it.copy(at = at) }
        work.everyDay(now.at)
    }

    suspend fun setAhead(ahead: Ahead) {
        store.update { it.copy(ahead = ahead) }
    }

    /**
     * What the daily work does. Nothing without a signed-in person. Otherwise the copy is brought up to date (no
     * connection, or no answer, is no reason not to go on) and the widget is drawn anew — whether or not reminders are
     * on or allowed, because the widget is brought up to date by this work too. With reminders off or not allowed by the
     * phone that is all; otherwise one notification is shown if something is due and none was shown for today yet, and
     * with nothing due one that is still shown is taken away.
     */
    suspend fun remind(): Reminded {
        val on = store.read().on
        if (session.state.value is SessionState.Restoring) {
            session.restore()
        }
        if (session.state.value !is SessionState.SignedIn) {
            return if (on) Reminded.SIGNED_OUT else Reminded.OFF
        }
        try {
            planner.sync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            // Only its kind is said: what it carries may be the server's words.
            Log.i(TAG, "The planner could not be brought up to date: ${failed.javaClass.simpleName}")
        }
        // A new day is a reason to draw it even where nothing was loaded.
        redrawWidget()
        if (!on) {
            return Reminded.OFF
        }
        if (!notifications.allowed()) {
            return Reminded.NOT_ALLOWED
        }
        if (session.state.value !is SessionState.SignedIn) {
            return Reminded.SIGNED_OUT
        }
        val copy = planner.planner.first() ?: return Reminded.NO_COPY
        val today = todayAt(copy, clock.instant())
        val things = dueThings(copy, today, store.read().ahead)
        val reminder = reminderOf(things, resources)
        if (reminder == null) {
            notifications.clear()
            return Reminded.NOTHING_DUE
        }
        if (store.read().lastShown == today) {
            return Reminded.ALREADY_SHOWN
        }
        // Signing out, or switching off, may have happened while the server was being asked.
        if (session.state.value !is SessionState.SignedIn || !store.read().on) {
            return Reminded.SIGNED_OUT
        }
        notifications.show(reminder)
        store.update { it.copy(lastShown = today) }
        Log.i(TAG, "A reminder was shown for ${reminder.count} things")
        return Reminded.SHOWN
    }

    /** Signing out: no daily work, no reminder still shown, no settings. */
    suspend fun forget() {
        // Whatever of this goes wrong, the rest is still done: signing out leaves nothing of the account behind.
        try {
            work.stop()
        } finally {
            try {
                notifications.clear()
            } finally {
                store.clear()
            }
        }
    }

    private companion object {
        const val TAG = "Reminders"
    }
}
