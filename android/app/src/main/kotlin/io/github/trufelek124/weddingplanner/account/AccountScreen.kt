package io.github.trufelek124.weddingplanner.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Saturday, 19 June 2027": the interface is in English whatever the phone's language. */
private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

/**
 * What a signed-in person sees until the plan and the tasks are on the phone: whose wedding it is,
 * who is signed in, and the way out.
 */
@Composable
fun AccountScreen(
    userName: String,
    wedding: WeddingUi,
    onTryAgain: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val title = when (wedding) {
                is WeddingUi.Ready -> if (wedding.names.size == 2) {
                    stringResource(R.string.wedding_names, wedding.names[0], wedding.names[1])
                } else {
                    wedding.names.joinToString()
                }
                else -> stringResource(R.string.app_name)
            }
            Text(text = title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })

            when (wedding) {
                WeddingUi.Loading -> Text(stringResource(R.string.wedding_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is WeddingUi.Ready -> {
                    Text(
                        text = wedding.date?.format(DAY) ?: stringResource(R.string.wedding_day_not_fixed),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.more_is_coming), style = MaterialTheme.typography.bodyLarge)
                }
                WeddingUi.NotSetUp -> Text(stringResource(R.string.wedding_not_set_up), style = MaterialTheme.typography.bodyLarge)
                WeddingUi.Full -> Text(stringResource(R.string.wedding_full), style = MaterialTheme.typography.bodyLarge)
                WeddingUi.Unreachable, WeddingUi.Failed -> {
                    Text(
                        text = stringResource(if (wedding == WeddingUi.Unreachable) R.string.wedding_unreachable else R.string.wedding_failed),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    QuietButton(text = stringResource(R.string.try_again), onClick = onTryAgain)
                }
            }

            Text(
                text = stringResource(R.string.signed_in_as, userName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuietButton(text = stringResource(R.string.sign_out), onClick = onSignOut)
        }
    }
}
