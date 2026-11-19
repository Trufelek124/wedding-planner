package io.github.trufelek124.weddingplanner.planner

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R

/*
 * What a vendor's screen and a guest's screen share: a row of contact details that opens the phone's
 * own app, and what is said when no app takes it.
 */

/** @param intent what pressing it starts; null where it only shows */
internal class ContactRow(val key: String, @StringRes val label: Int, val value: String, val intent: Intent?, val says: String?, val what: String)

@Composable
internal fun ContactLine(row: ContactRow, open: (Intent) -> Unit) {
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

/** Said where the last press on a contact row found no app on the phone to take it. */
@Composable
internal fun NoApp() {
    Text(
        text = stringResource(R.string.contact_no_app),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}
