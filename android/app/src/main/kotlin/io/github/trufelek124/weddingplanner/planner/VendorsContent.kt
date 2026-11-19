package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Vendor
import io.github.trufelek124.weddingplanner.data.VendorStatus

/**
 * Everyone the wedding is made with: who is being considered, who is booked, and what is agreed and
 * paid, narrowed down by status. The same facts and words as the web's vendors page; it is only
 * shown here, vendors are added and changed in the web app.
 *
 * @param onOpen leads to one vendor
 * @param foot what stands under everything: when this was loaded
 */
@Composable
fun VendorsContent(planner: Planner, onOpen: (Vendor) -> Unit, modifier: Modifier = Modifier, foot: @Composable () -> Unit = {}) {
    val all = planner.vendorList
    // The status is kept across turning the phone, as its name; "" is all.
    var chosen by rememberSaveable { mutableStateOf("") }
    val only = VendorStatus.entries.firstOrNull { it.name == chosen }
    val offered = statusesOf(all, only)
    val shown = vendorsShown(all, only)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        if (all.isEmpty()) {
            item(key = "empty") { NothingHere(stringResource(R.string.vendors_empty)) }
        } else {
            item(key = "which") {
                StatusChips(stringResource(R.string.vendors_all), offered, only, label = ::vendorStatusLabel, onChoose = { chosen = it?.name.orEmpty() })
            }
            if (shown.isEmpty()) {
                item(key = "nothing") { NothingHere(stringResource(R.string.vendors_no_match)) }
            }
            items(shown, key = { it.id }) { vendor ->
                VendorLine(vendor, planner, onOpen = { onOpen(vendor) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item(key = "foot") { foot() }
    }
}

/** One vendor: its name, its status in words, its category and contact person, and its money where there is any. */
@Composable
private fun VendorLine(vendor: Vendor, planner: Planner, onOpen: () -> Unit) {
    val category = vendor.categoryId?.let { planner.categories[it] }
    val facts = listOfNotNull(vendorStatusLabel(vendor.status), category, vendor.contactPerson.takeIf { it.isNotEmpty() }) +
        vendorMoneyFacts(vendor, planner.currency)
    EntryLine(
        title = vendor.name,
        facts = facts.map { Fact(it) },
        closed = vendor.status == VendorStatus.DROPPED,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.vendor_open), role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = vendorLineInWords(vendor, category, planner.currency) },
    )
}
