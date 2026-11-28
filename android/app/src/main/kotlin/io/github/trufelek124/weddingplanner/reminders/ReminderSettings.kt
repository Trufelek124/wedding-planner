package io.github.trufelek124.weddingplanner.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The reminders as this phone has them set: the other owner's phone has its own, and the server knows nothing of them.
 *
 * @param on off until the owner switches it on
 * @param at the time of day to remind around: 9:00 to begin with
 * @param ahead how far ahead to look besides what is overdue: today and tomorrow to begin with
 * @param lastShown the day a reminder was last shown, as the day of the wedding's place; null before the first
 */
data class ReminderSettings(
    val on: Boolean = false,
    val at: LocalTime = LocalTime.of(9, 0),
    val ahead: Ahead = Ahead.TOMORROW,
    val lastShown: LocalDate? = null,
)

/** Keeps [ReminderSettings] in a preferences file of its own, like the session; signing out removes it. */
class ReminderSettingsStore(private val file: DataStore<Preferences>) {

    val settings: Flow<ReminderSettings> = file.data.map(::settingsOf)

    suspend fun read(): ReminderSettings = settings.first()

    /** Changes what is kept in one go; what [change] leaves alone stays. Returns what is kept afterwards. */
    suspend fun update(change: (ReminderSettings) -> ReminderSettings): ReminderSettings {
        var kept = ReminderSettings()
        file.edit { saved ->
            val now = change(settingsOf(saved))
            saved[ON] = now.on
            saved[MINUTE_OF_DAY] = now.at.hour * 60 + now.at.minute
            saved[AHEAD] = now.ahead.name
            val shown = now.lastShown
            if (shown == null) saved.remove(LAST_SHOWN) else saved[LAST_SHOWN] = shown.toString()
            kept = now
        }
        return kept
    }

    suspend fun clear() {
        file.edit { it.clear() }
    }

    private fun settingsOf(saved: Preferences): ReminderSettings {
        val begin = ReminderSettings()
        val minutes = saved[MINUTE_OF_DAY]?.takeIf { it in 0 until 24 * 60 }
        return ReminderSettings(
            on = saved[ON] ?: begin.on,
            at = minutes?.let { LocalTime.of(it / 60, it % 60) } ?: begin.at,
            // A choice this version does not know is the one it begins with.
            ahead = Ahead.entries.firstOrNull { it.name == saved[AHEAD] } ?: begin.ahead,
            lastShown = saved[LAST_SHOWN]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    private companion object {
        val ON = booleanPreferencesKey("on")
        val MINUTE_OF_DAY = intPreferencesKey("minute_of_day")
        val AHEAD = stringPreferencesKey("ahead")
        val LAST_SHOWN = stringPreferencesKey("last_shown")
    }
}
