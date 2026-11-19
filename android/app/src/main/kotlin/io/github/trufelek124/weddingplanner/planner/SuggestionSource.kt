package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Suggestion
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.ZoneId

/**
 * Where a task or a step of the plan came from: the suggestion that made it, with who wrote the mail, its subject, when it
 * arrived, whose inbox it was in, the passage, how sure the import was and how it stands now. Where the import added it by
 * itself and nobody has decided, it can still be undone or kept from here.
 *
 * @param actions what stands at the foot of the card: [SuggestionActions] on the phone's screen
 */
@Composable
fun SuggestionSourceContent(
    planner: Planner,
    suggestion: Suggestion,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    actions: @Composable (Suggestion) -> Unit = {},
    foot: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var noApp by remember { mutableStateOf(false) }
    val open: (Intent) -> Unit = { noApp = !startOrSayNo(context, it) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "source") { SuggestionCard(suggestion, planner, zone, open, actions, standing = suggestionStanding(suggestion, planner, zone)) }
        if (noApp) {
            item(key = "no-app") { NoApp() }
        }
        item(key = "foot") { foot() }
    }
}

/** What the source page says for a suggestion the copy does not have any more: it was undone since, or the copy is older than the mail. */
@Composable
fun SuggestionSourceGone(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.source_gone), style = MaterialTheme.typography.bodyLarge)
        QuietButton(stringResource(R.string.back), onClick = onBack)
    }
}
