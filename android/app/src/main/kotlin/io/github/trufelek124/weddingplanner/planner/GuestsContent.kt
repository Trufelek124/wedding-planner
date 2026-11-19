package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Guest
import io.github.trufelek124.weddingplanner.data.GuestCounts
import io.github.trufelek124.weddingplanner.data.GuestStatus
import io.github.trufelek124.weddingplanner.data.Planner

/**
 * Who is invited, who is coming, and how many people that makes: the head count as the server counts
 * it, then the guests by household, narrowed down by status. The same facts and words as the web's
 * guests page; it is only shown here, guests are added and changed in the web app.
 *
 * @param onOpen leads to one guest
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun GuestsContent(planner: Planner, onOpen: (Guest) -> Unit, modifier: Modifier = Modifier, foot: @Composable () -> Unit = {}) {
    val all = planner.guests
    // The status is kept across turning the phone, as its name; "" is all.
    var chosen by rememberSaveable { mutableStateOf("") }
    val only = GuestStatus.entries.firstOrNull { it.name == chosen }
    val offered = guestStatusesOf(all, only)
    val groups = guestGroups(guestsShown(all, only), planner.households)
    val counts = planner.guestCounts?.takeIf { it.total.guests > 0 }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        // The head count stays whole whatever is chosen below; a copy that has none shows none.
        if (counts != null) {
            item(key = "head-count") { HeadCountLead(counts) }
        }
        if (all.isEmpty()) {
            item(key = "empty") { NothingHere(stringResource(R.string.guests_empty)) }
        } else {
            item(key = "which") {
                StatusChips(stringResource(R.string.guests_all), offered, only, label = ::guestStatusLabel, onChoose = { chosen = it?.name.orEmpty() })
            }
            if (groups.isEmpty()) {
                item(key = "nothing") { NothingHere(stringResource(R.string.guests_no_match)) }
            }
            // With no household at all the guests stand on their own, and say nothing of it.
            val named = groups.any { it.household != null }
            for (group in groups) {
                val household = group.household
                item(key = "group-${household?.id ?: "alone"}") {
                    if (household != null) {
                        GroupHeading(household.name, people(group.heads), householdInWords(household.name, group.heads))
                    } else if (named) {
                        GroupTitle(stringResource(R.string.guests_on_their_own))
                    }
                }
                items(group.guests, key = { "guest-${it.id}" }) { guest ->
                    GuestLine(guest, planner, onOpen = { onOpen(guest) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        // The breakdown stands after the list, so that the list is on the first screen.
        if (counts != null) {
            item(key = "breakdown") { HeadCountBreakdown(counts, planner) }
        }
        item(key = "foot") { foot() }
    }
}

/** The head count in a few lines: who is confirmed and what they are made of, and everyone on the list. */
@Composable
private fun HeadCountLead(counts: GuestCounts) {
    val lead = headCountLead(counts)
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        GroupTitle(stringResource(R.string.guests_head_count))
        if (lead != null) {
            Text(lead, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        }
        Text(headCountEveryone(counts), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A household's name with how many people the guests shown under it stand for: one heading. */
@Composable
private fun GroupHeading(name: String, count: String, inWords: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = inWords
            },
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f, fill = false))
        Text(count, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One guest: the name, the status in words, how many people, whose side, and what comes along. */
@Composable
private fun GuestLine(guest: Guest, planner: Planner, onOpen: () -> Unit) {
    val side = guestSide(guest, planner::memberName)
    EntryLine(
        title = guest.name,
        facts = guestFacts(guest, side),
        // Someone who cannot come is struck out, as on the web.
        closed = guest.status == GuestStatus.DECLINED,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.guest_open), role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = guestLineInWords(guest, side) },
    )
}

/**
 * How many people, status by status and side by side, as the server counted them. A block for each
 * status rather than a table, so that it reads at the largest letters too; each block is one statement
 * for a screen reader.
 */
@Composable
private fun HeadCountBreakdown(counts: GuestCounts, planner: Planner) {
    val lines = headCountLines(counts, planner::memberName)
    Column(modifier = Modifier.padding(top = 8.dp)) {
        GroupTitle(stringResource(R.string.guests_breakdown))
        for ((place, line) in lines.withIndex()) {
            // The last line is everyone: it stands apart.
            val everyone = place == lines.lastIndex
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = headLineInWords(line) },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = line.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (everyone) FontWeight.Bold else null,
                        modifier = Modifier.weight(1f),
                    )
                    Text(people(line.heads), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (side in line.sides) {
                        Text("${side.side} ${side.heads}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = if (everyone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant)
        }
        Text(
            text = stringResource(R.string.guests_head_count_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
