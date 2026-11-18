package io.github.trufelek124.weddingplanner.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.trufelek124.weddingplanner.data.Board
import io.github.trufelek124.weddingplanner.data.Kept
import io.github.trufelek124.weddingplanner.data.PlannerRepository
import io.github.trufelek124.weddingplanner.data.SendLater
import io.github.trufelek124.weddingplanner.data.Share
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a share could not be kept on the phone. */
enum class ShareProblem { TOO_MANY, TOO_LARGE, UNREADABLE }

/**
 * @param boards the boards to choose from, as the phone last loaded them
 * @param saving the share is being kept
 * @param saved it is kept on the phone, and on its way to the server
 */
data class ShareUi(
    val boards: List<Board> = emptyList(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val problem: ShareProblem? = null,
)

/**
 * Keeps a share on the phone and starts it on its way. The screen closes right after; sending
 * goes on in [background], and the phone is asked to send it later should that not work now.
 */
class ShareViewModel(
    private val repository: PlannerRepository,
    private val sendLater: SendLater,
    private val background: CoroutineScope,
) : ViewModel() {

    private val changes = MutableStateFlow(ShareUi())
    val ui: StateFlow<ShareUi> = changes.asStateFlow()

    init {
        viewModelScope.launch {
            repository.planner.collect { planner -> changes.update { it.copy(boards = planner?.boards.orEmpty()) } }
        }
        // A phone that has not loaded the planner yet has no boards to offer: it asks for them now.
        background.launch { repository.sync() }
    }

    /** @param pictures one for each shared picture: opens it for reading */
    fun save(share: Share, pictures: List<() -> InputStream?>) {
        if (changes.value.saving || changes.value.saved) {
            return
        }
        changes.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            when (repository.keepShare(share, pictures)) {
                is Kept.Yes -> {
                    sendLater.whenConnected()
                    background.launch { repository.sync() }
                    changes.update { it.copy(saving = false, saved = true) }
                }
                Kept.TooMany -> changes.update { it.copy(saving = false, problem = ShareProblem.TOO_MANY) }
                Kept.TooLarge -> changes.update { it.copy(saving = false, problem = ShareProblem.TOO_LARGE) }
                Kept.Unreadable -> changes.update { it.copy(saving = false, problem = ShareProblem.UNREADABLE) }
            }
        }
    }
}
