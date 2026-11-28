package io.github.trufelek124.weddingplanner.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.trufelek124.weddingplanner.reminders.Ahead
import io.github.trufelek124.weddingplanner.reminders.ReminderSettings
import io.github.trufelek124.weddingplanner.reminders.Reminding
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the reminders screen shows.
 *
 * @param read false until the settings were read
 * @param allowed whether the phone lets the app show notifications, as it was when last asked
 * @param refused the phone was asked to allow them, or does not ask, and the answer was no: they stay off
 */
data class RemindersUi(
    val read: Boolean = false,
    val settings: ReminderSettings = ReminderSettings(),
    val allowed: Boolean = true,
    val refused: Boolean = false,
) {
    /** The phone does not allow notifications from the planner and that matters: the person asked for reminders, or tried to. */
    val notAllowed: Boolean get() = !allowed && (settings.on || refused)
}

/** The settings of the reminders, as the screen changes them. */
class RemindersModel(private val reminding: Reminding) : ViewModel() {

    private val changes = MutableStateFlow(RemindersUi(allowed = reminding.allowed()))
    val ui: StateFlow<RemindersUi> = changes.asStateFlow()

    init {
        viewModelScope.launch {
            reminding.settings.collect { settings -> changes.update { it.copy(read = true, settings = settings) } }
        }
    }

    /** Whether the phone lets the app show notifications now. */
    fun allowed(): Boolean = reminding.allowed()

    /** The person came back to the screen: the phone's own switch for the app's notifications may have been used meanwhile. */
    fun lookAgain() {
        changes.update { it.copy(allowed = reminding.allowed()) }
    }

    /** Switched on; the phone's permission has been asked for where it has to be. Where it is still not given, they stay off. */
    fun switchOn() {
        viewModelScope.launch {
            val on = reminding.switchOn()
            changes.update { it.copy(allowed = reminding.allowed(), refused = !on) }
        }
    }

    fun switchOff() {
        viewModelScope.launch {
            reminding.switchOff()
            changes.update { it.copy(refused = false) }
        }
    }

    /** The phone is not to be asked and does not allow them, or the person said no when asked. */
    fun refused() {
        changes.update { it.copy(allowed = reminding.allowed(), refused = true) }
    }

    fun setTime(at: LocalTime) {
        viewModelScope.launch { reminding.setTime(at) }
    }

    fun setAhead(ahead: Ahead) {
        viewModelScope.launch { reminding.setAhead(ahead) }
    }
}
