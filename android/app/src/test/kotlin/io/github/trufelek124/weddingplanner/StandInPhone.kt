package io.github.trufelek124.weddingplanner

import android.app.Activity
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
 * A phone for tests: the app's container with a stand-in server, a key that is not in a Keystore,
 * and a stand-in for Google. What it stores goes to [folder], so that a second phone made on the
 * same folder is the same phone after a restart.
 */
class StandInPhone(
    private val folder: File,
    val server: StandInServer = StandInServer(),
    val google: StandInGoogle = StandInGoogle(),
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
) {

    private val running = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val stored: File = File(folder, "session.preferences_pb")

    val container = AppContainer(
        server = ServerAddress.parse("https://planner.example"),
        engine = server.engine,
        sessionFile = PreferenceDataStoreFactory.create(scope = running) { stored },
        cipher = AesGcmCipher { key },
        googleAccounts = google,
    )

    /** Switches the phone off. What it stored stays in the folder. */
    fun off() {
        runBlocking { running.coroutineContext.job.cancelAndJoin() }
    }

    /** The same phone, switched on again: the same storage, key and server, nothing in memory. */
    fun restarted(): StandInPhone {
        off()
        return StandInPhone(folder, server, google, key)
    }
}
