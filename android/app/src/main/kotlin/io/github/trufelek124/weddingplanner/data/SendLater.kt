package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.session.SessionState
import java.util.concurrent.TimeUnit

/**
 * Asks for the changes that wait on the phone to be sent as soon as there is a connection, also
 * when the app has been closed by then. The app sends by itself while it is open; this is for
 * the time it is not.
 */
fun interface SendLater {
    fun whenConnected()
}

/** The phone's own way of doing work later: it waits for a connection and survives a restart. */
class SendWithWorkManager(private val context: Context) : SendLater {

    override fun whenConnected() {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // One piece of work however often it is asked for: it sends everything that waits.
        WorkManager.getInstance(context).enqueueUniqueWork(SendWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

/** Sends what waits, and loads the planner again while it is at it. Asks to be run again while something still waits. */
class SendWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as WeddingPlannerApp).container
        if (container.session.state.value is SessionState.Restoring) {
            container.session.restore()
        }
        if (container.session.state.value !is SessionState.SignedIn) {
            // Signing out removed what was waiting: there is nothing of anyone's to send.
            return Result.success()
        }
        container.planner.sync()
        return if (container.planner.stillToSend() > 0) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "send-waiting-changes"
    }
}
