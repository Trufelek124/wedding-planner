package io.github.trufelek124.weddingplanner.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.trufelek124.weddingplanner.AppContainer
import io.github.trufelek124.weddingplanner.BuildConfig
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.planner.PlannerScreens
import io.github.trufelek124.weddingplanner.planner.PlannerViewModel
import io.github.trufelek124.weddingplanner.session.SessionState
import io.github.trufelek124.weddingplanner.signin.SignInScreen
import io.github.trufelek124.weddingplanner.signin.SignInViewModel

/**
 * Decides the screen from the session, as the web app does: nobody sees anything of the planner
 * without being signed in.
 */
@Composable
fun AppRoot(container: AppContainer, debugBuild: Boolean = BuildConfig.DEBUG) {
    val state by container.session.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.session.restore() }

    val ofTheSignedIn: SignedInModels = viewModel()
    LaunchedEffect(state) {
        if (state is SessionState.SignedOut) {
            ofTheSignedIn.forget()
        }
    }

    when (val session = state) {
        SessionState.Restoring -> Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.starting), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        is SessionState.SignedOut -> {
            val model: SignInViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { SignInViewModel(container.api, container.session, container.googleAccounts, debugBuild) }
                },
            )
            val ui by model.ui.collectAsStateWithLifecycle()
            val activity = LocalActivity.current
            SignInScreen(
                ui = ui,
                ended = session.ended,
                server = container.server.value,
                onGoogle = { activity?.let(model::signInWithGoogle) },
                onDevelopmentSignIn = model::signInForDevelopment,
                onTryAgain = model::askTheServer,
            )
        }

        is SessionState.SignedIn -> CompositionLocalProvider(LocalViewModelStoreOwner provides ofTheSignedIn) {
            val model: PlannerViewModel = viewModel(
                factory = viewModelFactory { initializer { PlannerViewModel(container.planner, container.session) } },
            )
            val ui by model.ui.collectAsStateWithLifecycle()
            PlannerScreens(ui = ui, userName = session.user.displayName, clock = container.clock, onLoad = model::load, onSignOut = model::signOut)
        }
    }
}

/**
 * Keeps the view models of the screens a signed-in person sees. They live through a turn of the
 * phone like any other, and go when that person signs out: the next one to sign in starts with
 * nothing of the last one's in memory.
 */
class SignedInModels : ViewModel(), ViewModelStoreOwner {

    override val viewModelStore = ViewModelStore()

    fun forget() {
        viewModelStore.clear()
    }

    override fun onCleared() {
        viewModelStore.clear()
    }
}
