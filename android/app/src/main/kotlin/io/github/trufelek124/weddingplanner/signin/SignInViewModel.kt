package io.github.trufelek124.weddingplanner.signin

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.trufelek124.weddingplanner.api.Api
import io.github.trufelek124.weddingplanner.api.ApiFailure
import io.github.trufelek124.weddingplanner.api.model.AuthConfig
import io.github.trufelek124.weddingplanner.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the sign-in screen has to say when something did not work. */
enum class SignInMessage {
    UNREACHABLE,
    NOT_ALLOWED,
    NO_GOOGLE_ACCOUNT,
    FAILED,
    EMAIL_NEEDED,
}

/**
 * @param ready false until the server has said how one can sign in
 * @param googleClientId the OAuth client to sign in with; null when the server has none
 * @param devLoginOffered the development login: a debug build and a server that offers it
 * @param busy a sign-in is under way
 */
data class SignInUi(
    val ready: Boolean = false,
    val googleClientId: String? = null,
    val devLoginOffered: Boolean = false,
    val busy: Boolean = false,
    val message: SignInMessage? = null,
)

class SignInViewModel(
    private val api: Api,
    private val session: Session,
    private val google: GoogleAccounts,
    private val debugBuild: Boolean,
) : ViewModel() {

    private val changes = MutableStateFlow(SignInUi())
    val ui: StateFlow<SignInUi> = changes.asStateFlow()

    init {
        askTheServer()
    }

    /** Asks the server how one can sign in; also what "Try again" does. */
    fun askTheServer() {
        changes.update { it.copy(message = null, busy = true) }
        viewModelScope.launch {
            try {
                val config: AuthConfig = api.getOpen("/api/auth/config")
                changes.update {
                    it.copy(ready = true, busy = false, googleClientId = config.googleClientId, devLoginOffered = debugBuild && config.devLoginEnabled)
                }
            } catch (failure: ApiFailure) {
                changes.update { it.copy(busy = false, message = if (failure is ApiFailure.Unreachable) SignInMessage.UNREACHABLE else SignInMessage.FAILED) }
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        val clientId = changes.value.googleClientId ?: return
        attempt {
            when (val answer = google.idToken(activity, clientId)) {
                is GoogleAnswer.IdToken -> session.signInWithGoogle(answer.value)
                // Closing the picker is a decision, not an error: nothing is said.
                GoogleAnswer.Cancelled -> Unit
                GoogleAnswer.NoAccount -> changes.update { it.copy(message = SignInMessage.NO_GOOGLE_ACCOUNT) }
                GoogleAnswer.Failed -> changes.update { it.copy(message = SignInMessage.FAILED) }
            }
        }
    }

    fun signInForDevelopment(email: String) {
        if (email.isBlank()) {
            changes.update { it.copy(message = SignInMessage.EMAIL_NEEDED) }
            return
        }
        attempt { session.signInForDevelopment(email.trim()) }
    }

    private fun attempt(signIn: suspend () -> Unit) {
        if (changes.value.busy) {
            return
        }
        changes.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                signIn()
            } catch (failure: ApiFailure) {
                changes.update { it.copy(message = messageFor(failure)) }
            }
            changes.update { it.copy(busy = false) }
        }
    }

    private fun messageFor(failure: ApiFailure): SignInMessage = when {
        failure is ApiFailure.Unreachable -> SignInMessage.UNREACHABLE
        failure is ApiFailure.Problem && failure.isOf(ApiFailure.ACCOUNT_NOT_ALLOWED) -> SignInMessage.NOT_ALLOWED
        // Only the development login sends something a person typed: what the server calls not valid is the address.
        failure is ApiFailure.Problem && failure.isOf(ApiFailure.BAD_REQUEST) -> SignInMessage.EMAIL_NEEDED
        else -> SignInMessage.FAILED
    }
}
