package io.github.trufelek124.weddingplanner.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.ApiFailure
import io.github.trufelek124.weddingplanner.api.model.Wedding
import io.github.trufelek124.weddingplanner.session.Session
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the phone knows about the wedding of whoever is signed in. */
sealed interface WeddingUi {
    data object Loading : WeddingUi

    /** @param date null while the day is not fixed */
    data class Ready(val names: List<String>, val date: LocalDate?) : WeddingUi

    data object NotSetUp : WeddingUi

    /** The planner belongs to two other people. */
    data object Full : WeddingUi

    data object Unreachable : WeddingUi

    data object Failed : WeddingUi
}

class AccountViewModel(private val api: Api, private val session: Session) : ViewModel() {

    private val changes = MutableStateFlow<WeddingUi>(WeddingUi.Loading)
    val wedding: StateFlow<WeddingUi> = changes.asStateFlow()

    init {
        load()
    }

    fun load() {
        changes.value = WeddingUi.Loading
        viewModelScope.launch {
            changes.value = try {
                val wedding: Wedding = api.get("/api/wedding")
                WeddingUi.Ready(wedding.members.map { it.displayName }, wedding.date?.let { LocalDate.parse(it.toString()) })
            } catch (failure: ApiFailure) {
                when {
                    failure is ApiFailure.Unreachable -> WeddingUi.Unreachable
                    failure is ApiFailure.Problem && failure.isOf(ApiFailure.WEDDING_NOT_SET_UP) -> WeddingUi.NotSetUp
                    failure is ApiFailure.Problem && failure.isOf(ApiFailure.WEDDING_FULL) -> WeddingUi.Full
                    // The session ended while this was asked: the sign-in screen takes over by itself.
                    failure is ApiFailure.SignedOut -> WeddingUi.Loading
                    else -> WeddingUi.Failed
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }
}
