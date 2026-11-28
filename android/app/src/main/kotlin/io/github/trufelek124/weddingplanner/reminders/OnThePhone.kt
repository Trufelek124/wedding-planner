package io.github.trufelek124.weddingplanner.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.trufelek124.weddingplanner.MainActivity
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** How long from [now] until the next [at] on the phone's own clock: today's if it is still to come, otherwise tomorrow's. */
fun untilNext(now: ZonedDateTime, at: LocalTime): Duration {
    var next = ZonedDateTime.of(now.toLocalDate(), at, now.zone)
    if (!next.isAfter(now)) {
        next = ZonedDateTime.of(now.toLocalDate().plusDays(1), at, now.zone)
    }
    return Duration.between(now, next)
}

/**
 * The daily work under WorkManager: a periodic piece of work of one day, whose first run is at the next [LocalTime] chosen.
 * No exact alarm, so no permission for one; the phone decides the minute (some later, more while it saves its battery).
 */
class RemindWithWorkManager(private val context: Context, private val clock: Clock = Clock.systemDefaultZone()) : ReminderWork {

    override fun everyDay(at: LocalTime) {
        val first = untilNext(ZonedDateTime.now(clock), at)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(first.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        // A new time starts the day over from that time: the old schedule is cancelled, not bent.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(ReminderWorker.NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    override fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork(ReminderWorker.NAME)
    }
}

/** Once a day: brings the copy up to date if it can, and reminds of what is due (see [Reminding.remind]). */
class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        (applicationContext as WeddingPlannerApp).container.reminding.remind()
        // A day with nothing to say, or a phone that is off the net, is no failure: tomorrow it runs again.
        return Result.success()
    }

    companion object {
        const val NAME = "remind-of-what-is-due"
    }
}

/** What an intent that starts the app says it wants to open. */
object OpenAt {
    const val EXTRA = "io.github.trufelek124.weddingplanner.OPEN_AT"
    const val TASKS = "tasks"
}

/** Notifications as the phone shows them. */
class NotificationsOnThePhone(private val context: Context) : Notifications {

    private val manager = NotificationManagerCompat.from(context)

    override fun allowed(): Boolean = manager.areNotificationsEnabled()

    override fun show(reminder: Reminder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // The channel is the phone's to make loud, quiet or silent: it is made once, with the usual importance.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.reminders_channel_description)
            },
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .putExtra(OpenAt.EXTRA, OpenAt.TASKS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // What a phone set to hide private content shows instead: how many, and no names.
        val hidden = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tasks)
            .setContentTitle(reminder.publicTitle)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tasks)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (reminder.lines.isNotEmpty()) {
                    setStyle(NotificationCompat.InboxStyle().also { style -> reminder.lines.forEach(style::addLine) })
                }
            }
            .build()
        manager.notify(ID, notification)
    }

    override fun clear() {
        manager.cancel(ID)
    }

    companion object {
        const val CHANNEL = "reminders"

        /** The one place a reminder takes: today's is shown where yesterday's was. */
        const val ID = 1
    }
}
