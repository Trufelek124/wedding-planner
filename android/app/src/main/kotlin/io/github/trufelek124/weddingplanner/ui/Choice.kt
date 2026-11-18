package io.github.trufelek124.weddingplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R

/**
 * One thing chosen out of a list that may be long: a category, a vendor, a step of the plan. The
 * field shows what is chosen; pressing it opens the list. A screen reader hears the field as
 * "Category: Venue".
 *
 * @param options what can be chosen, by id, in the order to show them
 * @param none what choosing nothing is called: "No category"
 * @param problem what is wrong with the choice, in words; null when nothing is
 */
@Composable
fun Choice(
    label: String,
    chosen: String?,
    options: List<Pair<String, String>>,
    none: String,
    onChoose: (String?) -> Unit,
    modifier: Modifier = Modifier,
    problem: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == chosen }?.second ?: none
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        QuietButton(text = shown, onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "$label: $shown" })
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for ((id, name) in listOf<Pair<String?, String>>(null to none) + options) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = id == chosen, role = Role.RadioButton) {
                                    onChoose(id)
                                    open = false
                                },
                        ) {
                            RadioButton(selected = id == chosen, onClick = null)
                            Text(name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
