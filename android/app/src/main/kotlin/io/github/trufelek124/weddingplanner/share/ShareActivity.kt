package io.github.trufelek124.weddingplanner.share

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.trufelek124.weddingplanner.AppContainer
import io.github.trufelek124.weddingplanner.BuildConfig
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.data.Share
import io.github.trufelek124.weddingplanner.session.SessionState
import io.github.trufelek124.weddingplanner.signin.SignInScreen
import io.github.trufelek124.weddingplanner.signin.SignInViewModel
import io.github.trufelek124.weddingplanner.ui.PlannerTheme

/**
 * Opened by Android when the planner is chosen in another app's share sheet, for a link or for
 * pictures. It asks for a board and a note, keeps the share, and closes: the person is back where
 * they came from, and the share goes to the server behind their back, now or when there is a
 * connection.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WeddingPlannerApp).container
        // The request stays with this screen while it lives, a sign-in in between included.
        val shared = sharedOf(intent)
        setContent {
            PlannerTheme {
                ShareRoot(container, shared, onDone = ::finish)
            }
        }
    }
}

/**
 * Someone who is not signed in signs in first, on this same screen, and then finds what they
 * shared still waiting to be saved.
 */
@Composable
fun ShareRoot(container: AppContainer, shared: Shared, onDone: () -> Unit, debugBuild: Boolean = BuildConfig.DEBUG) {
    val state by container.session.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.session.restore() }

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
                lead = stringResource(R.string.share_sign_in_first),
            )
        }

        is SessionState.SignedIn -> {
            val model: ShareViewModel = viewModel(
                key = "share-${session.user.id}",
                factory = viewModelFactory { initializer { ShareViewModel(container.planner, container.sendLater, container.background) } },
            )
            val ui by model.ui.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val saved = stringResource(R.string.share_saved)
            LaunchedEffect(ui.saved) {
                if (ui.saved) {
                    Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }
            ShareScreen(
                shared = shared,
                ui = ui,
                onSave = { boardId, newBoard, note ->
                    model.save(
                        Share(link = shared.link, boardId = boardId, note = note, newBoard = newBoard),
                        shared.pictures.map { picture -> { context.contentResolver.openInputStream(picture) } },
                    )
                },
                onCancel = onDone,
            )
        }
    }
}
