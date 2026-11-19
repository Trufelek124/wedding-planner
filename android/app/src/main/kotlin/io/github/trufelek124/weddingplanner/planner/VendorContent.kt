package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Vendor
import io.github.trufelek124.weddingplanner.ui.Meter
import io.github.trufelek124.weddingplanner.ui.QuietButton

/**
 * One vendor: how to reach them, what was agreed and what is paid, and the plan items, tasks and
 * payments that concern them. The same facts and words as the web's vendor page; it is only shown
 * here (a task can be ticked off and opened, as on the tasks). A phone number, an e-mail address,
 * an address and a website open in the phone's own apps.
 *
 * @param actions what can be done with a task from here
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun VendorContent(planner: Planner, vendor: Vendor, actions: TaskActions, modifier: Modifier = Modifier, foot: @Composable () -> Unit = {}) {
    val currency = planner.currency
    val context = LocalContext.current
    // What was last pressed that no app on the phone could take; the next press that works takes it away.
    var noApp by remember { mutableStateOf(false) }
    val open: (Intent) -> Unit = { noApp = !startOrSayNo(context, it) }
    val entries = planner.entriesOf(vendor)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item(key = "facts") { Facts(vendor, vendor.categoryId?.let { planner.categories[it] }) }

        item(key = "contact") { GroupTitle(stringResource(R.string.vendor_contact)) }
        contact(vendor, open)
        if (noApp) {
            item(key = "no-app") {
                Text(
                    text = stringResource(R.string.contact_no_app),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (vendor.notes.isNotEmpty()) {
            item(key = "notes-title") { GroupTitle(stringResource(R.string.vendor_notes)) }
            item(key = "notes") { Text(vendor.notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp)) }
        }

        item(key = "money-title") { GroupTitle(stringResource(R.string.vendor_money)) }
        item(key = "money") { Money(vendor, currency) }

        item(key = "plan-title") { GroupTitle(stringResource(R.string.vendor_plan_items)) }
        val planItems = entries.filter { it.kind == Kind.PLAN_ITEM }
        val tasks = entries.filter { it.kind == Kind.TASK }
        concerning(planItems, "plan", planner, null)
        item(key = "tasks-title") { GroupTitle(stringResource(R.string.vendor_tasks)) }
        concerning(tasks, "task", planner, actions)

        item(key = "foot") { foot() }
    }
}

/** What the vendor is: its status in words, its category, and how many inspirations belong to it. */
@Composable
private fun Facts(vendor: Vendor, category: String?) {
    val facts = listOfNotNull(vendorStatusLabel(vendor.status), category, inspirationsInWords(vendor.inspirations))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) {},
    ) {
        for (fact in facts) {
            Text(fact, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What is filled in, one row each; a row that can be pressed opens an app. Nothing filled in is said. */
private fun LazyListScope.contact(vendor: Vendor, open: (Intent) -> Unit) {
    val rows = listOfNotNull(
        vendor.contactPerson.takeIf { it.isNotEmpty() }?.let { ContactRow("person", R.string.vendor_contact_person, it, null, null, "") },
        vendor.phone.takeIf { it.isNotEmpty() }?.let {
            ContactRow("phone", R.string.vendor_phone, it, callIntent(it), callInWords(it), "call")
        },
        vendor.email.takeIf { it.isNotEmpty() }?.let {
            ContactRow("email", R.string.vendor_email, it, mailIntent(it), mailInWords(it), "mail")
        },
        vendor.website.takeIf { it.isNotEmpty() }?.let {
            // A website that is not an http or https address is shown as text and opens nothing.
            ContactRow("website", R.string.vendor_website, siteName(it), siteIntent(it), siteInWords(siteName(it)), "site")
        },
        vendor.address.takeIf { it.isNotEmpty() }?.let {
            ContactRow("address", R.string.vendor_address, it, mapIntent(it), mapInWords(it), "map")
        },
    )
    if (rows.isEmpty()) {
        item(key = "no-contact") { NothingHere(stringResource(R.string.vendor_no_contact)) }
    }
    items(rows, key = { it.key }) { row ->
        ContactLine(row, open)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** @param intent what pressing it starts; null where it only shows */
private class ContactRow(val key: String, val label: Int, val value: String, val intent: Intent?, val says: String?, val what: String)

@Composable
private fun ContactLine(row: ContactRow, open: (Intent) -> Unit) {
    val label = stringResource(row.label)
    val intent = row.intent
    val onClickLabel = when (row.what) {
        "call" -> stringResource(R.string.contact_call)
        "mail" -> stringResource(R.string.contact_mail)
        "map" -> stringResource(R.string.contact_map)
        else -> stringResource(R.string.contact_site)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (intent == null) Modifier else Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button) { open(intent) })
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = if (intent != null && row.says != null) row.says else "$label: ${row.value}" },
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (intent != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** What was agreed, what is paid and what is still to pay, with a bar and where the payments stand against the price. */
@Composable
private fun Money(vendor: Vendor, currency: String) {
    if (!hasVendorMoney(vendor)) {
        Text(stringResource(R.string.vendor_no_money), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val against = againstAgreed(vendor.agreedPrice, vendor.committed, currency)
    val over = overAgreed(vendor.agreedPrice, vendor.committed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(agreedInWords(vendor.agreedPrice, currency), style = MaterialTheme.typography.bodyLarge)
            Text(paidSentence(vendor.paid, vendor.committed, currency), style = MaterialTheme.typography.bodyLarge)
        }
        if (vendor.committed > 0) {
            Meter(
                limit = vendor.agreedPrice,
                committed = vendor.committed,
                paid = vendor.paid,
                inWords = meterSentence(vendor.name, vendor.agreedPrice, vendor.committed, vendor.paid, "agreed", currency),
            )
        }
        if (against != null) {
            // Over what was agreed is said in words; the colour and the weight only go with them.
            Text(
                text = against,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (over) FontWeight.SemiBold else null,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The lines of what concerns the vendor, or that nothing does. */
private fun LazyListScope.concerning(entries: List<Entry>, name: String, planner: Planner, actions: TaskActions?) {
    if (entries.isEmpty()) {
        item(key = "$name-none") {
            Text(stringResource(R.string.vendor_none_yet), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
    items(entries, key = { "$name-${it.id}" }) { entry ->
        EntryLine(entry, planner, actions = actions, withVendor = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A vendor that is no longer in the copy: removed on the web meanwhile. */
@Composable
fun VendorGone(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vendor_not_there), style = MaterialTheme.typography.bodyLarge)
        QuietButton(stringResource(R.string.back), onClick = onBack)
    }
}
