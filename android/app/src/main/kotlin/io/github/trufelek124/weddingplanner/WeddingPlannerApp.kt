package io.github.trufelek124.weddingplanner

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.session.AesGcmCipher
import io.github.trufelek124.weddingplanner.session.KeystoreKey
import io.github.trufelek124.weddingplanner.session.Session
import io.github.trufelek124.weddingplanner.session.SessionStore
import io.github.trufelek124.weddingplanner.session.TokenCipher
import io.github.trufelek124.weddingplanner.signin.CredentialManagerGoogleAccounts
import io.github.trufelek124.weddingplanner.signin.GoogleAccounts
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Everything the screens share, made once and handed out by hand (D-005: no dependency-injection
 * framework). A screen's view model takes what it needs from here.
 *
 * The parts that only a phone has (the network, the Keystore, the Google account picker) come in
 * from outside, so that tests put stand-ins in their place.
 */
class AppContainer(
    val server: ServerAddress,
    engine: HttpClientEngine,
    sessionFile: DataStore<Preferences>,
    cipher: TokenCipher,
    val googleAccounts: GoogleAccounts,
) {

    /** Signing out removes the account's data: what later tasks store on the phone is cleared here as well. */
    val session: Session = Session(
        api = { api },
        store = SessionStore(sessionFile, cipher),
        forgetEverything = { googleAccounts.forget() },
    )

    val api: Api = Api(engine, server, session.accessTokens)

    companion object {

        fun onThePhone(context: Context): AppContainer = AppContainer(
            server = ServerAddress.parse(BuildConfig.SERVER_URL),
            engine = OkHttp.create(),
            sessionFile = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("session") },
            cipher = AesGcmCipher(KeystoreKey::get),
            googleAccounts = CredentialManagerGoogleAccounts(context),
        )
    }
}

class WeddingPlannerApp : Application() {

    /** Made when first asked for, which is when the first screen opens. */
    val container: AppContainer by lazy { AppContainer.onThePhone(this) }
}
