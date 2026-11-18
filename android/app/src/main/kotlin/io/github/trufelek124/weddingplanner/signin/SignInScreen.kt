package io.github.trufelek124.weddingplanner.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.ui.QuietButton

/**
 * Where a signed-out person lands: one button for Google, and in a debug build the development
 * login when the server offers it. What went wrong is said in words, and read out when it appears.
 *
 * @param ended true when the server ended a session that was running
 */
@Composable
fun SignInScreen(
    ui: SignInUi,
    ended: Boolean,
    server: String,
    onGoogle: () -> Unit,
    onDevelopmentSignIn: (String) -> Unit,
    onTryAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.sign_in_lead), style = MaterialTheme.typography.bodyLarge)

            val said = when {
                ui.message != null -> stringResource(textOf(ui.message))
                ended -> stringResource(R.string.sign_in_ended)
                else -> null
            }
            if (said != null) {
                Text(
                    text = said,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (!ui.ready) {
                if (ui.busy) {
                    Text(text = stringResource(R.string.starting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    QuietButton(text = stringResource(R.string.try_again), onClick = onTryAgain)
                }
            } else {
                if (ui.googleClientId != null) {
                    Button(onClick = onGoogle, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(if (ui.busy) R.string.sign_in_working else R.string.sign_in_google))
                    }
                } else {
                    Text(text = stringResource(R.string.sign_in_google_not_set_up), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ui.devLoginOffered) {
                    HorizontalDivider()
                    DevelopmentSignIn(busy = ui.busy, onSignIn = onDevelopmentSignIn)
                }
            }

            Text(
                text = stringResource(R.string.server_address, server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DevelopmentSignIn(busy: Boolean, onSignIn: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dev_sign_in_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.dev_sign_in_email)) },
            supportingText = { Text(stringResource(R.string.dev_sign_in_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSignIn(email) }),
            modifier = Modifier.fillMaxWidth(),
        )
        QuietButton(text = stringResource(R.string.dev_sign_in_button), onClick = { onSignIn(email) }, enabled = !busy)
    }
}

private fun textOf(message: SignInMessage): Int = when (message) {
    SignInMessage.UNREACHABLE -> R.string.sign_in_unreachable
    SignInMessage.NOT_ALLOWED -> R.string.sign_in_not_allowed
    SignInMessage.NO_GOOGLE_ACCOUNT -> R.string.sign_in_no_account
    SignInMessage.FAILED -> R.string.sign_in_failed
    SignInMessage.EMAIL_NEEDED -> R.string.dev_sign_in_email_needed
}
