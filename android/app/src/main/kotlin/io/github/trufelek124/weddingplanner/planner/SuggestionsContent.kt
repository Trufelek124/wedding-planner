package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Decision
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Suggestion
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.ZoneId

/**
 * What the e-mail import proposes and nobody has decided: a card for each, the newest message first. A card says
 * what is proposed, where it comes from, the passage it rests on (set apart as a quotation) and how sure the import
 * is, and it opens the message in Gmail. Only shown here: whatever can be done with a suggestion stands in [actions].
 *
 * Everything that came out of a mail is plain text: it is drawn letter for letter, and nothing in it can be pressed.
 *
 * @param zone the phone's own, for saying when a message arrived
 * @param actions what stands at the foot of a card: [SuggestionActions] on the phone's screen
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun SuggestionsContent(
    planner: Planner,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    actions: @Composable (Suggestion) -> Unit = {},
    foot: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    // Whether the last press on "Open the e-mail" found no app to take it; the next press that works takes it away.
    var noApp by remember { mutableStateOf(false) }
    val open: (Intent) -> Unit = { noApp = !startOrSayNo(context, it) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (planner.suggestions.isEmpty()) {
            item(key = "empty") { NothingHere(stringResource(R.string.suggestions_empty)) }
        }
        items(planner.suggestions, key = { "suggestion-${it.id}" }) { suggestion ->
            SuggestionCard(suggestion, planner, zone, open, actions)
        }
        if (noApp) {
            item(key = "no-app") { NoApp() }
        }
        item(key = "foot") { foot() }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    planner: Planner,
    zone: ZoneId,
    open: (Intent) -> Unit,
    actions: @Composable (Suggestion) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val link = mailLinkIntent(suggestion.mailLink)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(12.dp),
    ) {
        // The words of the card are one statement for a screen reader; the buttons under it stay buttons of their own.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = suggestionInWords(suggestion, planner, zone) },
        ) {
            Text(
                suggestionKindLabel(suggestion.kind),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(suggestionName(suggestion), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            for (fact in suggestionFacts(suggestion, planner)) {
                Text(fact, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            if (suggestion.notes.isNotBlank()) {
                Text(suggestion.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                for (line in suggestionSource(suggestion, planner, zone)) {
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (suggestion.passage.isNotBlank()) {
                Quotation(suggestion.passage)
            }
            Text(confidenceInWords(suggestion.confidence), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        if (link != null) {
            QuietButton(stringResource(R.string.suggestion_open_email), onClick = { open(link) })
        }
        actions(suggestion)
    }
}

/** The passage a proposal rests on: set apart by a bar at its side, in italics, and as plain text. */
@Composable
private fun Quotation(passage: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall))
        Text(
            passage,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * What can be done with a suggestion that waits: accept it as it was proposed, or reject it. A decision takes effect on
 * the phone at once: the card then says what was decided and that it waits to be sent, and offers to take it back
 * until it is sent. Nothing of a proposal can be changed here; that is the web's.
 *
 * @param notTakenBack the person tried to take the decision back while it was being sent
 */
@Composable
fun SuggestionActions(suggestion: Suggestion, notTakenBack: Boolean, onDecide: (Decision) -> Unit, onTakeBack: () -> Unit) {
    val decided = suggestion.decided
    when {
        // A decision of the phone's that was not taken: the notice at the top deals with it.
        suggestion.needsLook -> Unit
        decided == null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (decision in Decision.entries) {
                val description = decideDescription(decision, suggestion)
                if (decision == Decision.ACCEPT) {
                    Button(onClick = { onDecide(decision) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
                        Text(decideButton(decision))
                    }
                } else {
                    QuietButton(decideButton(decision), onClick = { onDecide(decision) }, modifier = Modifier.semantics { contentDescription = description })
                }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(decidedInWords(decided), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                waitingInWords(suggestion.waiting)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                if (notTakenBack) {
                    Text(ON_ITS_WAY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            QuietButton(TAKE_BACK, onClick = onTakeBack, modifier = Modifier.semantics { contentDescription = takeBackDescription(suggestion) })
        }
    }
}
