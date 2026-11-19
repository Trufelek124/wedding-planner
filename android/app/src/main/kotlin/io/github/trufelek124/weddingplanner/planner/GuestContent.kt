package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Guest
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.ui.QuietButton

/**
 * One guest: everything the list says of them, the household, how to reach them and the notes. The same
 * facts and words as the web's guests page; it is only shown here. A phone number and an e-mail address
 * open in the phone's own apps.
 *
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun GuestContent(planner: Planner, guest: Guest, modifier: Modifier = Modifier, foot: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    // What was last pressed that no app on the phone could take; the next press that works takes it away.
    var noApp by remember { mutableStateOf(false) }
    val open: (Intent) -> Unit = { noApp = !startOrSayNo(context, it) }
    val household = guest.householdId?.let { id -> planner.households.firstOrNull { it.id == id } }
    val facts = guestFacts(guest, guestSide(guest, planner::memberName), withDietary = false)
    val about = listOfNotNull(
        ContactRow("household", R.string.guest_household, household?.name ?: stringResource(R.string.guests_on_their_own), null, null, ""),
        guest.dietaryNeeds.takeIf { it.isNotEmpty() }?.let { ContactRow("dietary", R.string.guest_dietary, it, null, null, "") },
    )
    val reach = listOfNotNull(
        guest.phone.takeIf { it.isNotEmpty() }?.let { ContactRow("phone", R.string.guest_phone, it, callIntent(it), callInWords(it), "call") },
        guest.email.takeIf { it.isNotEmpty() }?.let { ContactRow("email", R.string.guest_email, it, mailIntent(it), mailInWords(it), "mail") },
    )

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item(key = "facts") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
            ) {
                for (fact in facts) {
                    Text(fact.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(about, key = { it.key }) { row ->
            ContactLine(row, open)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        item(key = "contact-title") { GroupTitle(stringResource(R.string.guest_contact)) }
        if (reach.isEmpty()) {
            item(key = "no-contact") { NothingHere(stringResource(R.string.guest_no_contact)) }
        }
        items(reach, key = { it.key }) { row ->
            ContactLine(row, open)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (noApp) {
            item(key = "no-app") { NoApp() }
        }

        if (guest.notes.isNotEmpty()) {
            item(key = "notes-title") { GroupTitle(stringResource(R.string.guest_notes)) }
            item(key = "notes") { Text(guest.notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        item(key = "foot") { foot() }
    }
}

/** A guest that is no longer in the copy: removed on the web meanwhile. */
@Composable
fun GuestGone(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.guest_not_there), style = MaterialTheme.typography.bodyLarge)
        QuietButton(stringResource(R.string.back), onClick = onBack)
    }
}
