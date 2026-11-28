package io.github.trufelek124.weddingplanner.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.trufelek124.weddingplanner.StandInClock
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the phone is asked for: one piece of work that comes back every day, first at the next chosen time, which waits
 * for nothing (it reminds from the copy when there is no connection). The phone's clock stands at 14:05 in Warsaw.
 */
@RunWith(RobolectricTestRunner::class)
class RemindWithWorkManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = StandInClock()
    private val work get() = WorkManager.getInstance(context)

    @Before
    fun aWorkManagerForTests() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun asked(): List<WorkInfo> = work.getWorkInfosForUniqueWork(ReminderWorker.NAME).get().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `it asks for one piece of work every day, first at the next time chosen, with no connection needed`() {
        RemindWithWorkManager(context, clock).everyDay(LocalTime.of(9, 0))

        val one = asked().single()
        assertEquals(TimeUnit.DAYS.toMillis(1), one.periodicityInfo!!.repeatIntervalMillis)
        // 14:05 to 9:00 the next morning.
        assertEquals(TimeUnit.MINUTES.toMillis(18 * 60 + 55), one.initialDelayMillis)
        assertEquals(NetworkType.NOT_REQUIRED, one.constraints.requiredNetworkType)
    }

    @Test
    fun `a time still to come today is today's`() {
        RemindWithWorkManager(context, clock).everyDay(LocalTime.of(18, 30))

        assertEquals(TimeUnit.MINUTES.toMillis(4 * 60 + 25), asked().single().initialDelayMillis)
    }

    @Test
    fun `a new time replaces the old one, however often it is asked`() {
        val phone = RemindWithWorkManager(context, clock)

        phone.everyDay(LocalTime.of(9, 0))
        phone.everyDay(LocalTime.of(18, 30))
        phone.everyDay(LocalTime.of(18, 30))

        assertEquals(TimeUnit.MINUTES.toMillis(4 * 60 + 25), asked().single().initialDelayMillis)
    }

    @Test
    fun `stopping leaves nothing asked for`() {
        val phone = RemindWithWorkManager(context, clock)
        phone.everyDay(LocalTime.of(9, 0))

        phone.stop()

        assertTrue(asked().isEmpty())
    }
}
