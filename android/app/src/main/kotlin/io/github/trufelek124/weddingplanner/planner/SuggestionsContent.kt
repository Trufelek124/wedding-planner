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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.trufelek124.weddingplanner.data.SuggestionState
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.ZoneId

/**
 * What the e-mail import proposes and nobody has decided, and beside it what it added by itself: a card for each, the
 * newest message first. The part "Added by itself" is drawn only where something was added, under what waits. A card says
 * what is proposed, where it comes from, the passage it rests on (set apart as a quotation) and how sure the import
 * is, and it opens the message in Gmail where that is the signed-in person's own mailbox. Only shown here: whatever can be done with a suggestion stands in [actions].
 *
 * Everything that came out of a mail is plain text: it is drawn letter for letter, and nothing in it can be pressed.
 *
 * @param zone the phone's own, for saying when a message arrived
 * @param scrollToAdded come to the part "Added by itself" at once: where Home's line about it leads
 * @param actions what stands at the foot of a card: [SuggestionActions] on the phone's screen
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun SuggestionsContent(
    planner: Planner,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    scrollToAdded: Boolean = false,
    actions: @Composable (Suggestion) -> Unit = {},
    foot: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    // Whether the last press on "Open the e-mail" found no app to take it; the next press that works takes it away.
    var noApp by remember { mutableStateOf(false) }
    val open: (Intent) -> Unit = { noApp = !startOrSayNo(context, it) }
    val listState = rememberLazyListState()
    val both = planner.added.isNotEmpty()
    // The part's title is the item after the title of what waits, its note (or the empty line), and each card.
    val addedAt = 1 + (if (planner.suggestions.isEmpty()) 1 else planner.suggestions.size)
    LaunchedEffect(scrollToAdded) {
        if (scrollToAdded && both) listState.scrollToItem(addedAt)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Said only where there are two parts to tell apart.
        if (both) {
            item(key = "waiting-title") { GroupTitle(WAITING_PART) }
        }
        if (planner.suggestions.isEmpty()) {
            item(key = "empty") { NothingHere(stringResource(R.string.suggestions_empty)) }
        }
        items(planner.suggestions, key = { "suggestion-${it.id}" }) { suggestion ->
            SuggestionCard(suggestion, planner, zone, open, actions)
        }
        if (both) {
            item(key = "added-title") { GroupTitle(ADDED_PART) }
            item(key = "added-intro") { Text(ADDED_INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(planner.added, key = { "suggestion-${it.id}" }) { suggestion ->
                SuggestionCard(suggestion, planner, zone, open, actions)
            }
        }
        if (noApp) {
            item(key = "no-app") { NoApp() }
        }
        item(key = "foot") { foot() }
    }
}

/**
 * @param standing how it stands, for the source page; null in the inbox, where the part says it
 */
@Composable
internal fun SuggestionCard(
    suggestion: Suggestion,
    planner: Planner,
    zone: ZoneId,
    open: (Intent) -> Unit,
    actions: @Composable (Suggestion) -> Unit,
    standing: String? = null,
) {
    val shape = MaterialTheme.shapes.medium
    // A message in the other owner's mailbox cannot be opened from here.
    val link = mailLinkIntent(suggestion.mailLink).takeIf { planner.inYourInbox(suggestion) }
    val fromDocument = stringResource(R.string.suggestion_from_document)
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
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = suggestionInWords(suggestion, planner, zone, standing, fromDocument) },
        ) {
            Text(
                suggestionKindLabel(suggestion.kind),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(suggestionName(suggestion), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (standing != null) {
                Text(standing, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
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
            if (suggestion.fromDocument) {
                // The words are not in the mail that "Open the e-mail" shows: said beside the passage, as plain text.
                Text(fromDocument, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * What can be done with a suggestion: accept it as it was proposed or reject it where it waits, keep it or undo it where the
 * import added it by itself. A decision takes effect on the phone at once: the card then says what was decided and that it
 * waits to be sent, and offers to take it back until it is sent. Nothing of a proposal can be changed here; that is the web's.
 * One that was accepted has nothing to decide.
 *
 * @param notTakenBack the person tried to take the decision back while it was being sent
 */
@Composable
fun SuggestionActions(suggestion: Suggestion, notTakenBack: Boolean, onDecide: (Decision) -> Unit, onTakeBack: () -> Unit) {
    val decided = suggestion.decided
    when {
        // A decision of the phone's that was not taken: the notice at the top deals with it.
        suggestion.needsLook -> Unit
        suggestion.state == SuggestionState.ACCEPTED -> Unit
        decided == null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val added = suggestion.state == SuggestionState.ADDED
            for (decision in if (added) listOf(Decision.UNDO, Decision.KEEP) else listOf(Decision.ACCEPT, Decision.REJECT)) {
                val description = decideDescription(decision, suggestion)
                if (decision == Decision.ACCEPT || decision == Decision.KEEP) {
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
                Text(decidedOnCard(decided, suggestion), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                waitingInWords(suggestion.waiting)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                if (notTakenBack) {
                    Text(ON_ITS_WAY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            QuietButton(TAKE_BACK, onClick = onTakeBack, modifier = Modifier.semantics { contentDescription = takeBackDescription(suggestion) })
        }
    }
}
