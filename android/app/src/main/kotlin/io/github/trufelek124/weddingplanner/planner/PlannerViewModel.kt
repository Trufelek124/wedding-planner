package io.github.trufelek124.weddingplanner.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.trufelek124.weddingplanner.data.Load
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.PlannerRepository
import io.github.trufelek124.weddingplanner.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the screens of a signed-in person are drawn from.
 *
 * @param read false until the phone has looked at what it holds
 * @param planner what the phone holds; null while nothing was ever loaded
 * @param loading the server is being asked
 * @param trouble why the last load brought nothing new; null when it worked
 */
data class PlannerUi(
    val read: Boolean = false,
    val planner: Planner? = null,
    val loading: Boolean = false,
    val trouble: Load? = null,
)

/** One model for Home, the plan and the tasks: they show the same load, made at the same moment. */
class PlannerViewModel(private val repository: PlannerRepository, private val session: Session) : ViewModel() {

    private val changes = MutableStateFlow(PlannerUi())
    val ui: StateFlow<PlannerUi> = changes.asStateFlow()

    init {
        viewModelScope.launch {
            repository.planner.collect { planner -> changes.update { it.copy(read = true, planner = planner) } }
        }
        load()
    }

    /** Asks the server again; what is on the screen stays until the answer is there. */
    fun load() {
        if (changes.value.loading) {
            return
        }
        changes.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = repository.load()
            // When the session ended, the sign-in takes over by itself: nothing to say here.
            changes.update { it.copy(loading = false, trouble = result.takeUnless { it == Load.LOADED || it == Load.SIGNED_OUT }) }
        }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }
}
