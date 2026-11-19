package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Inspiration
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Vendor
import io.github.trufelek124.weddingplanner.ui.QuietButton

/**
 * One idea: its picture, where it comes from, its board, the plan item and the vendor it belongs to, who added it,
 * the note and the tags, and for a link a row that opens the original in the phone's own apps. The same facts as the
 * web's board; it is only shown here.
 *
 * @param onVendor leads to the vendor the idea belongs to
 * @param face what stands where the picture belongs; the default says it in words
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun InspirationContent(
    planner: Planner,
    idea: Inspiration,
    onVendor: (Vendor) -> Unit,
    modifier: Modifier = Modifier,
    face: @Composable (Inspiration) -> Unit = { WordsFace(faceInWords(it), large = true) },
    foot: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    // What was last pressed that no app on the phone could take; the next press that works takes it away.
    var noApp by remember { mutableStateOf(false) }
    val open: (Intent) -> Unit = { noApp = !startOrSayNo(context, it) }
    val board = idea.boardId?.let { id -> planner.boards.firstOrNull { it.id == id }?.name }
    val planItem = idea.planItemId?.let { planner.entry(it)?.title }
    val vendor = idea.vendorId?.let(planner::vendor)
    val addedBy = idea.addedById?.let(planner::memberName)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item(key = "face") { Column(modifier = Modifier.padding(bottom = 8.dp)) { face(idea) } }
        item(key = "from") { IdeaRow(stringResource(R.string.inspiration_from), sourceName(idea)) }
        item(key = "board") { IdeaRow(stringResource(R.string.inspiration_board), board ?: stringResource(R.string.inspiration_no_board)) }
        if (planItem != null) {
            item(key = "plan-item") { IdeaRow(stringResource(R.string.inspiration_plan_item), planItem) }
        }
        if (vendor != null) {
            item(key = "vendor") { IdeaRow(stringResource(R.string.inspiration_vendor), vendor.name, onClickLabel = stringResource(R.string.vendor_open)) { onVendor(vendor) } }
        }
        if (addedBy != null) {
            item(key = "added-by") {
                Text(
                    addedByInWords(addedBy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        if (idea.note.isNotEmpty()) {
            item(key = "note-title") { GroupTitle(stringResource(R.string.inspiration_note)) }
            item(key = "note") { Text(idea.note, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        if (idea.tags.isNotEmpty()) {
            item(key = "tags-title") { GroupTitle(stringResource(R.string.inspiration_tags)) }
            item(key = "tags") { TagLabels(idea.tags) }
        }
        val link = idea.url
        if (link != null) {
            item(key = "link") {
                // Only an http or https address opens: anything else is shown as text and opens nothing.
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    ContactLine(ContactRow("link", R.string.inspiration_link, link, siteIntent(link), openOriginalInWords(idea), "site"), open)
                }
            }
            if (noApp) {
                item(key = "no-app") { NoApp() }
            }
        }
        item(key = "foot") { foot() }
    }
}

/** What an idea is called, where it belongs: a label and its value, one statement; pressing it, where [onClick] is given, leads somewhere. */
@Composable
private fun IdeaRow(label: String, value: String, onClickLabel: String? = null, onClick: (() -> Unit)? = null) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick))
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The tags of an idea, shown and not pressable: choosing one is done on the board. */
@Composable
private fun TagLabels(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = "Tags: " + tags.joinToString(", ") },
    ) {
        for (tag in tags) {
            Text(
                tag,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** An idea that is no longer in the copy: removed on the web meanwhile. */
@Composable
fun InspirationGone(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.inspiration_not_there), style = MaterialTheme.typography.bodyLarge)
        QuietButton(stringResource(R.string.back), onClick = onBack)
    }
}
