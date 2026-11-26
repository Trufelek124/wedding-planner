package io.github.trufelek124.weddingplanner.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.trufelek124.weddingplanner.data.Decision
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.GuestStatus
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Load
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.PlannerRepository
import io.github.trufelek124.weddingplanner.data.SendLater
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.data.TakenBack
import io.github.trufelek124.weddingplanner.data.TaskFields
import io.github.trufelek124.weddingplanner.session.Session
import io.github.trufelek124.weddingplanner.session.SessionState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the screens of a signed-in person are drawn from.
 *
 * @param read false until the phone has looked at what it holds
 * @param planner what the phone holds, with its own waiting changes in it; null while nothing was ever loaded
 * @param loading the person asked for the server to be asked, or the phone is loading for the first time
 * @param trouble why the server last could not be brought up to date with; null when it worked
 * @param notTakenBack the suggestions whose decision the person tried to take back while it was on its way
 */
data class PlannerUi(
    val read: Boolean = false,
    val planner: Planner? = null,
    val loading: Boolean = false,
    val trouble: Load? = null,
    val notTakenBack: Set<String> = emptySet(),
)

/**
 * One model for Home, the plan, the tasks and the task's form: they show the same load, made at
 * the same moment. A change is kept on the phone first and shows at once; then the phone and the
 * server are brought together, now if there is a connection and later if there is not.
 */
class PlannerViewModel(
    private val repository: PlannerRepository,
    private val session: Session,
    private val sendLater: SendLater,
) : ViewModel() {

    private val changes = MutableStateFlow(PlannerUi())
    val ui: StateFlow<PlannerUi> = changes.asStateFlow()

    private var talking = false
    private var again = false

    init {
        viewModelScope.launch {
            repository.planner.collect { planner -> changes.update { it.copy(read = true, planner = planner) } }
        }
        load()
    }

    /** Sends what waits and asks the server again; what is on the screen stays until the answer is there. */
    fun load() {
        bringTogether(shown = true)
    }

    /** Ticks a task off, or takes the tick away. */
    fun setStatus(task: Entry, done: Boolean) = change { repository.setStatus(task.id, if (done) Status.DONE else Status.NOT_STARTED) }

    /** Adds a task, or a step of the plan. */
    fun add(fields: TaskFields, kind: Kind) = change { repository.add(fields, kind) }

    fun edit(taskId: String, fields: TaskFields) = change { repository.edit(taskId, fields) }

    /** Chooses a guest's status. */
    fun setGuestStatus(guestId: String, status: GuestStatus) = change { repository.setGuestStatus(guestId, status) }

    /** Accepts or rejects a suggestion, as proposed. */
    fun decideSuggestion(suggestionId: String, decision: Decision) = change {
        changes.update { it.copy(notTakenBack = it.notTakenBack - suggestionId) }
        repository.decideSuggestion(suggestionId, decision)
    }

    /** Takes back a decision that was not sent yet; one that is on its way stands, and the screen says so. */
    fun takeBackSuggestionDecision(suggestionId: String) {
        viewModelScope.launch {
            val result = repository.takeBackSuggestionDecision(suggestionId)
            changes.update { it.copy(notTakenBack = if (result == TakenBack.ON_ITS_WAY) it.notTakenBack + suggestionId else it.notTakenBack - suggestionId) }
        }
    }

    /** Gives up a decision the server did not take: what the server has stands. */
    fun letGoOfSuggestionDecision(suggestionId: String) {
        viewModelScope.launch { repository.letGoOfSuggestionDecision(suggestionId) }
    }

    /** Gives up a guest's status that could not be sent: what the server has stands. */
    fun letGoOfGuestChange(guestId: String) {
        viewModelScope.launch { repository.letGoOfGuestChange(guestId) }
    }

    /** Gives up a change that could not be sent: what the server has stands. */
    fun letGo(taskId: String) {
        viewModelScope.launch { repository.letGo(taskId) }
    }

    /**
     * Signing out lets go of this model as soon as the session is over, which is in the middle of
     * it: the rest, telling the server, is not to be cut short by that.
     */
    /** Gives up something shared to the planner that waits or that the server did not take. */
    fun letGoOfShare(shareId: String) {
        viewModelScope.launch { repository.letGoOfShare(shareId) }
    }

    fun signOut() {
        viewModelScope.launch { withContext(NonCancellable) { session.signOut() } }
    }

    private fun change(make: suspend () -> Unit) {
        viewModelScope.launch {
            make()
            // Asked for before anything is sent: should the app be closed in a moment, the change still goes out.
            sendLater.whenConnected()
            bringTogether(shown = false)
        }
    }

    /**
     * @param shown whether the screen says that it is loading; after a tick it does not, the tick
     *     is on the screen already
     */
    private fun bringTogether(shown: Boolean) {
        if (shown) {
            changes.update { it.copy(loading = true) }
        }
        if (talking) {
            // Something changed while the phone was talking to the server: once more afterwards.
            again = true
            return
        }
        talking = true
        viewModelScope.launch {
            var result: Load
            do {
                again = false
                result = repository.sync()
            } while (again)
            talking = false
            val trouble = when {
                result == Load.LOADED -> null
                // When the session ended, the sign-in takes over by itself: nothing to say here.
                result == Load.SIGNED_OUT && session.state.value !is SessionState.SignedIn -> null
                // Someone is signed in and still nothing came of it: said, so that it can be tried again.
                result == Load.SIGNED_OUT -> Load.FAILED
                else -> result
            }
            // Whatever was on its way has landed or is waiting again: what was said of it no longer holds.
            changes.update { it.copy(loading = false, trouble = trouble, notTakenBack = emptySet()) }
        }
    }
}
