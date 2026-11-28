package io.github.trufelek124.weddingplanner

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.data.PlannerDatabase
import io.github.trufelek124.weddingplanner.data.PictureStore
import io.github.trufelek124.weddingplanner.data.PlannerRepository
import io.github.trufelek124.weddingplanner.data.SendLater
import io.github.trufelek124.weddingplanner.data.SendWithWorkManager
import io.github.trufelek124.weddingplanner.reminders.NotificationsOnThePhone
import io.github.trufelek124.weddingplanner.reminders.Notifications
import io.github.trufelek124.weddingplanner.reminders.ReminderSettingsStore
import io.github.trufelek124.weddingplanner.reminders.ReminderWork
import io.github.trufelek124.weddingplanner.reminders.RemindWithWorkManager
import io.github.trufelek124.weddingplanner.reminders.Reminding
import io.github.trufelek124.weddingplanner.session.AesGcmCipher
import io.github.trufelek124.weddingplanner.session.KeystoreKey
import io.github.trufelek124.weddingplanner.session.Session
import io.github.trufelek124.weddingplanner.session.SessionStore
import io.github.trufelek124.weddingplanner.session.SessionState
import io.github.trufelek124.weddingplanner.session.TokenCipher
import io.github.trufelek124.weddingplanner.signin.CredentialManagerGoogleAccounts
import io.github.trufelek124.weddingplanner.signin.GoogleAccounts
import io.github.trufelek124.weddingplanner.widget.WidgetActions
import io.github.trufelek124.weddingplanner.widget.WidgetDrawing
import io.github.trufelek124.weddingplanner.widget.WidgetModels
import io.github.trufelek124.weddingplanner.widget.WidgetOnThePhone
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

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
    database: PlannerDatabase,
    val sendLater: SendLater,
    /** Where the phone keeps its reminder settings: a file of its own, like the session's. */
    reminderFile: DataStore<Preferences>,
    /** Showing a notification, and whether the phone allows them: the phone's own. */
    val notifications: Notifications,
    /** The once-a-day work: the phone's own. */
    reminderWork: ReminderWork,
    /** For the words of a reminder and of the widget. */
    resources: Resources,
    /** Drawing the widget on the home screen anew (S12): the phone's own. */
    val widgetDrawing: WidgetDrawing,
    /** The app's own folder for the pictures of what was shared to the planner and waits to be sent. */
    shareFolder: File,
    /** The app's own folder for the pictures of the inspirations that were fetched; the system may empty it when space is short. */
    pictureFolder: File,
    val clock: Clock = Clock.systemDefaultZone(),
) {

    /**
     * For what has to go on after the screen that started it is gone: a share is sent while the
     * person is already back in the app they shared from.
     */
    val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Signing out removes the account's data from the phone: whatever a task keeps here is cleared in [forgetEverything]. */
    val session: Session = Session(
        api = { api },
        store = SessionStore(sessionFile, cipher),
        forgetEverything = {
            reminding.forget()
            pictures.forget()
            planner.forget()
            googleAccounts.forget()
            // Nothing of the plan is left to draw: the widget is emptied at once, not at the next daily run.
            widgetDrawing.redraw()
        },
    )

    val api: Api = Api(engine, server, session.accessTokens)

    val planner: PlannerRepository = PlannerRepository(
        { api }, database, clock, shareFolder,
        afterLoad = { pictures.afterLoad(it.inspirations) },
        // Every write the repository makes, a load included: the widget shows the copy and follows it.
        afterWrite = widgetDrawing::redraw,
    )

    /** Reminders of what is due: the settings, and what the daily work does. Signing out removes them. */
    val reminding: Reminding = Reminding(ReminderSettingsStore(reminderFile), notifications, reminderWork, session, planner, clock, resources, widgetDrawing::redraw)

    /** What the widget shows, and the one thing a press on it changes (S12). */
    val widgetModels: WidgetModels = WidgetModels(session, planner, clock, resources)
    val widgetActions: WidgetActions = WidgetActions(session, planner, sendLater)

    /** The pictures of the inspirations on the phone: fetched with the access token, kept in files, let go of at sign-out. */
    val pictures: PictureStore = PictureStore({ api }, pictureFolder, background)

    init {
        // A sign-in or a sign-out changes what the widget says; and while somebody is signed in the daily work is set,
        // reminders or not, because it brings the widget up to date.
        background.launch {
            session.state.filter { it !is SessionState.Restoring }.collect { state ->
                widgetDrawing.redraw()
                if (state is SessionState.SignedIn) {
                    reminding.keepDailyWork()
                }
            }
        }
    }

    companion object {

        fun onThePhone(context: Context): AppContainer = AppContainer(
            server = ServerAddress.parse(BuildConfig.SERVER_URL),
            engine = OkHttp.create(),
            sessionFile = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("session") },
            cipher = AesGcmCipher(KeystoreKey::get),
            googleAccounts = CredentialManagerGoogleAccounts(context),
            database = PlannerDatabase.onThePhone(context),
            sendLater = SendWithWorkManager(context.applicationContext),
            reminderFile = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("reminders") },
            notifications = NotificationsOnThePhone(context.applicationContext),
            reminderWork = RemindWithWorkManager(context.applicationContext),
            resources = context.resources,
            widgetDrawing = WidgetOnThePhone(context.applicationContext),
            shareFolder = File(context.filesDir, "shares"),
            pictureFolder = File(context.cacheDir, "pictures"),
        )
    }
}

class WeddingPlannerApp : Application() {

    private var made: AppContainer? = null

    /**
     * Made when first asked for, which is when the first screen opens or work in the background
     * starts. A test puts its own in before anything asks.
     */
    var container: AppContainer
        get() = made ?: AppContainer.onThePhone(this).also { made = it }
        set(value) {
            made = value
        }
}
