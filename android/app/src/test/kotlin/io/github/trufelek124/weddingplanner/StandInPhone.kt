package io.github.trufelek124.weddingplanner

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.data.PlannerDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.session.AesGcmCipher
import io.github.trufelek124.weddingplanner.signin.GoogleAccounts
import io.github.trufelek124.weddingplanner.signin.GoogleAnswer
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
) {

    private val running = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** How often the app asked for what waits to be sent once there is a connection. */
    var askedToSendLater = 0
        private set

    val stored: File = File(folder, "session.preferences_pb")

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
        clock = clock,
    )

    /** Everything the phone keeps in its files, read as text: to look for what must not be there. */
    fun everythingKept(): String = folder.listFiles().orEmpty().filter { it.isFile }.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }

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
            restful = if (container.planner.quiet) restful + 1 else 0
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
        return StandInPhone(folder, server, google, key, clock)
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
