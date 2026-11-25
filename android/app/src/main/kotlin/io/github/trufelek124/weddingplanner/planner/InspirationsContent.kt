package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Inspiration
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Waiting
import io.github.trufelek124.weddingplanner.data.WaitingShare

/**
 * What the couple keeps for ideas: a card for each, one under the other, narrowed down by board and by tag. The
 * same facts and words as the web's board. What was shared on this phone and has not been sent stands first. Ideas
 * are added through the share sheet and changed in the web app; here they are only shown, and one opens on a
 * screen of its own.
 *
 * The place of the picture is a slot: [face] says what stands there, and the default says it in words.
 *
 * @param onOpen leads to one idea
 * @param face what stands where an idea's picture belongs
 * @param shareFace what stands where the picture of something that waits to be sent belongs
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun InspirationsContent(
    planner: Planner,
    onOpen: (Inspiration) -> Unit,
    modifier: Modifier = Modifier,
    face: @Composable (Inspiration) -> Unit = { WordsFace(faceInWords(it)) },
    shareFace: @Composable (WaitingShare) -> Unit = { WordsFace(shareFaceInWords(it)) },
    foot: @Composable () -> Unit = {},
) {
    val ideas = planner.inspirations
    // What the notice at the top says is not saved is not waiting: it cannot be opened and is not a card.
    val waiting = planner.shares.filter { it.trouble == null }
    // The choices are kept across turning the phone; "" is none.
    var boardKept by rememberSaveable { mutableStateOf("") }
    var tagKept by rememberSaveable { mutableStateOf("") }
    val board = boardChosen(boardKept, planner.boards, waiting)
    val tag = tagKept.takeIf { it.isNotEmpty() }
    val nothingKept = ideas.isEmpty() && waiting.isEmpty()
    val boards = boardsOffered(planner.boards, ideas.map { it.boardId } + waiting.map { shareBoardKey(it, planner.boards) }, board)
    val tags = tagsOffered(ideas, tag)
    val shownIdeas = ideasShown(ideas, board, tag)
    val shownShares = sharesShown(waiting, board, tag, planner.boards)
    val boardNames = planner.boards.associate { it.id to it.name }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (nothingKept) {
            item(key = "empty") { NothingHere(stringResource(R.string.inspirations_empty)) }
        } else {
            // With no board at all, and none to be made, there is nothing to choose between.
            if (planner.boards.isNotEmpty() || boards.any { it.startsWith(NEW_BOARD) }) {
                item(key = "boards") {
                    StatusChips(
                        stringResource(R.string.inspirations_all_boards), boards, board, label = { boardChipLabel(it, planner.boards, waiting) },
                        onChoose = { boardKept = it.orEmpty() },
                    )
                }
            }
            if (tags.isNotEmpty()) {
                item(key = "tags") {
                    StatusChips(stringResource(R.string.inspirations_any_tag), tags, tag, label = { it }, onChoose = { tagKept = it.orEmpty() })
                }
            }
            if (shownIdeas.isEmpty() && shownShares.isEmpty()) {
                item(key = "nothing") { NothingHere(stringResource(R.string.inspirations_no_match)) }
            }
            items(shownShares, key = { "share-${it.id}" }) { share ->
                IdeaCard(
                    statement = shareInWords(share),
                    name = shareName(share),
                    facts = shareFacts(share),
                    note = "",
                    tags = emptyList(),
                    mark = waitingInWords(Waiting.TO_BE_SENT),
                    face = { shareFace(share) },
                    onOpen = null,
                    onTag = {},
                )
            }
            items(shownIdeas, key = { "idea-${it.id}" }) { idea ->
                val planItem = idea.planItemId?.let { planner.entry(it)?.title }
                IdeaCard(
                    statement = inspirationInWords(idea, idea.boardId?.let(boardNames::get), planItem),
                    name = inspirationName(idea),
                    facts = inspirationFacts(idea, idea.boardId?.let(boardNames::get), planItem),
                    note = idea.note,
                    tags = idea.tags,
                    mark = null,
                    face = { face(idea) },
                    onOpen = { onOpen(idea) },
                    onTag = { tagKept = it },
                )
            }
        }
        item(key = "foot") { foot() }
    }
}

/**
 * One idea on the board. The whole card opens the idea, and each tag on it is a button of its own that chooses
 * that tag. A screen reader hears the card as one statement.
 *
 * @param mark what is said first and strongly, such as that it waits to be sent; null for nothing
 * @param onOpen null for a card that cannot be opened
 */
@Composable
private fun IdeaCard(
    statement: String,
    name: String,
    facts: List<String>,
    note: String,
    tags: List<String>,
    mark: String?,
    face: @Composable () -> Unit,
    onOpen: (() -> Unit)?,
    onTag: (String) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onOpen == null) Modifier else Modifier.clickable(onClickLabel = stringResource(R.string.inspiration_open), role = Role.Button, onClick = onOpen))
            .padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = statement },
    ) {
        face()
        if (mark != null) {
            Text(mark, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (facts.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (fact in facts) {
                    Text(fact, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (note.isNotEmpty()) {
            // The whole note is on the idea's own screen.
            Text(note, style = MaterialTheme.typography.bodyMedium, maxLines = NOTE_LINES, overflow = TextOverflow.Ellipsis)
        }
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tag in tags) {
                    AssistChip(onClick = { onTag(tag) }, label = { Text(tag) }, modifier = Modifier.semantics { contentDescription = showOnlyTagInWords(tag) })
                }
            }
        }
    }
}

/** How many lines of a note a card shows before it says no more. */
private const val NOTE_LINES = 4

/**
 * The place of a picture, filled with words: what is there instead of it. A real picture takes this place in the
 * same size, so the card does not change when it comes.
 *
 * @param large the idea's own screen gives the picture more room than a card does
 */
@Composable
fun WordsFace(words: List<String>, modifier: Modifier = Modifier, large: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (large) 200.dp else 96.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (line in words) {
                Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
