package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.Priority
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.data.TaskFields
import io.github.trufelek124.weddingplanner.data.Waiting
import io.github.trufelek124.weddingplanner.money.currencySign
import io.github.trufelek124.weddingplanner.money.moneyToType
import io.github.trufelek124.weddingplanner.money.moneyTyped
import io.github.trufelek124.weddingplanner.ui.Choice
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The fields a refusal of the server is shown beside; what it says about any other field is listed at the top. */
private val SHOWN_BESIDE = setOf("title", "dueDate", "categoryId", "parentId", "vendorId", "amount", "paidOn")

/**
 * Adds a task or changes one, a payment's money included. What is saved is kept on the phone at
 * once and sent when the server can be reached; the form does not wait for that.
 *
 * @param task the task to change; null for a new one
 * @param parentId for a new task: the step of the plan it is added to
 * @param today the date where the wedding takes place, by the phone's clock
 * @param onLetGo for a task whose change could not be sent: give the change up and keep the latest version
 */
@Composable
fun TaskForm(
    planner: Planner,
    task: Entry?,
    parentId: String?,
    today: LocalDate,
    onSave: (TaskFields) -> Unit,
    onCancel: () -> Unit,
    onLetGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val from = task?.fields ?: TaskFields(title = "", parentId = parentId)
    val refused = task?.refusals.orEmpty()

    var title by rememberSaveable { mutableStateOf(from.title) }
    var due by rememberSaveable { mutableStateOf(from.dueDate?.let(::dayToType).orEmpty()) }
    var status by rememberSaveable { mutableStateOf(if (from.status == Status.OTHER) Status.NOT_STARTED else from.status) }
    var who by rememberSaveable { mutableStateOf(from.assigneeIds.joinToString(",")) }
    var category by rememberSaveable { mutableStateOf(from.categoryId) }
    var parent by rememberSaveable { mutableStateOf(from.parentId) }
    var vendor by rememberSaveable { mutableStateOf(from.vendorId) }
    var byHand by rememberSaveable { mutableStateOf(from.priorityByHand) }
    var payment by rememberSaveable { mutableStateOf(from.amount != null) }
    var amount by rememberSaveable { mutableStateOf(from.amount?.let { moneyToType(it, planner.currency) }.orEmpty()) }
    var part by rememberSaveable { mutableStateOf(from.paymentKind) }
    var paidOn by rememberSaveable { mutableStateOf(from.paidOn?.let(::dayToType).orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(from.notes) }
    // Said once Save was pressed and something could not be read.
    var tried by rememberSaveable { mutableStateOf(false) }

    val whoIds = who.split(",").filter { it.isNotEmpty() }
    val titleProblem = if (tried && title.isBlank()) stringResource(R.string.task_title_needed) else refused["title"]
    val dueProblem = if (tried && due.isNotBlank() && dayTyped(due) == null) stringResource(R.string.task_day_unreadable) else refused["dueDate"]
    val amountProblem = if (tried && payment && moneyTyped(amount, planner.currency).let { it == null || it == 0L }) {
        stringResource(R.string.task_amount_unreadable)
    } else {
        refused["amount"]
    }
    val paidProblem = if (tried && payment && status == Status.DONE && paidOn.isNotBlank() && dayTyped(paidOn) == null) {
        stringResource(R.string.task_day_unreadable)
    } else {
        refused["paidOn"]
    }

    fun save() {
        tried = true
        val dueDay = if (due.isBlank()) null else dayTyped(due) ?: return
        val costs = if (payment) moneyTyped(amount, planner.currency)?.takeIf { it > 0 } ?: return else null
        val paidDay = if (!payment || status != Status.DONE || paidOn.isBlank()) null else dayTyped(paidOn) ?: return
        if (title.isBlank()) {
            return
        }
        onSave(
            TaskFields(
                title = title.trim(), notes = notes.trim(), categoryId = category, dueDate = dueDay, status = status, assigneeIds = whoIds,
                priorityByHand = byHand, parentId = parent, vendorId = vendor, amount = costs, paymentKind = part.takeIf { payment }, paidOn = paidDay,
            ),
        )
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (task?.waiting) {
            Waiting.CHANGED_MEANWHILE -> FormNotice(stringResource(R.string.task_changed_meanwhile)) {
                QuietButton(stringResource(R.string.task_keep_latest), onClick = onLetGo)
            }
            Waiting.REFUSED -> FormNotice(
                listOf(stringResource(R.string.task_refused)).plus(refused.filterKeys { it !in SHOWN_BESIDE }.values.filter { it.isNotBlank() }).joinToString(" "),
            )
            Waiting.TO_BE_SENT -> FormNotice(stringResource(R.string.task_waiting))
            null -> Unit
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.task_title)) },
            isError = titleProblem != null,
            supportingText = titleProblem?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )

        DayField(stringResource(R.string.task_due), due, { due = it }, dueProblem, stringResource(R.string.task_due_hint), today)

        Group(stringResource(R.string.task_status)) {
            for (choice in listOf(Status.NOT_STARTED, Status.IN_PROGRESS, Status.DONE, Status.DROPPED)) {
                FilterChip(selected = status == choice, onClick = { status = choice }, label = { Text(statusLabel(choice).orEmpty()) })
            }
        }

        Group(stringResource(R.string.task_who)) {
            for (member in planner.members) {
                FilterChip(
                    selected = member.id in whoIds,
                    onClick = { who = (if (member.id in whoIds) whoIds - member.id else whoIds + member.id).joinToString(",") },
                    label = { Text(member.displayName) },
                )
            }
        }

        Choice(
            label = stringResource(R.string.task_parent),
            chosen = parent,
            options = planner.planItems.map { it.id to it.title },
            none = stringResource(R.string.task_no_parent),
            onChoose = { parent = it },
            problem = refused["parentId"],
        )
        Choice(
            label = stringResource(R.string.task_category),
            chosen = category,
            options = planner.categories.toList(),
            none = stringResource(R.string.task_no_category),
            onChoose = { category = it },
            problem = refused["categoryId"],
        )
        Choice(
            label = stringResource(R.string.task_vendor),
            chosen = vendor,
            options = planner.vendors.toList().sortedBy { it.second.lowercase() },
            none = stringResource(R.string.task_no_vendor),
            onChoose = { vendor = it },
            problem = refused["vendorId"],
        )

        Group(stringResource(R.string.task_urgency)) {
            FilterChip(selected = byHand == null, onClick = { byHand = null }, label = { Text(stringResource(R.string.task_urgency_worked_out)) })
            for (level in Priority.entries) {
                FilterChip(selected = byHand == level, onClick = { byHand = level }, label = { Text(priorityLabel(level)) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.task_is_payment), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            val says = stringResource(R.string.task_is_payment)
            Switch(checked = payment, onCheckedChange = { payment = it }, modifier = Modifier.semantics { contentDescription = says })
        }
        if (payment) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text(stringResource(R.string.task_amount, currencySign(planner.currency))) },
                isError = amountProblem != null,
                supportingText = { Text(amountProblem ?: stringResource(R.string.task_amount_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Group(stringResource(R.string.task_part)) {
                for ((kind, name) in listOf("DEPOSIT" to R.string.task_part_deposit, "INSTALMENT" to R.string.task_part_instalment, "FINAL" to R.string.task_part_final)) {
                    // Pressing the chosen one again takes the choice away: saying which part is not required.
                    FilterChip(selected = part == kind, onClick = { part = if (part == kind) null else kind }, label = { Text(stringResource(name)) })
                }
            }
            if (status == Status.DONE) {
                DayField(stringResource(R.string.task_paid_on), paidOn, { paidOn = it }, paidProblem, stringResource(R.string.task_paid_on_hint), today)
            }
        }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(stringResource(R.string.task_notes)) },
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )

        // Said beside the button, where the eye is after pressing it: what is marked may be far up the form.
        if (tried && (title.isBlank() || dueProblem != null || amountProblem != null || paidProblem != null)) {
            Text(
                text = stringResource(R.string.task_not_saved_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = ::save, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
            QuietButton(stringResource(R.string.cancel), onClick = onCancel)
        }
    }
}

/** A few choices under a name they share. */
@Composable
private fun Group(name: String, choices: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices() }
    }
}

/** What has to be known before the form is used: a change is waiting, was refused, or met someone else's. */
@Composable
private fun FormNotice(text: String, action: @Composable () -> Unit = {}) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        action()
    }
}

/**
 * A day, typed as day.month.year or picked from a calendar. Typing is the field's own way; the
 * calendar writes into the same field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayField(label: String, typed: String, onTyped: (String) -> Unit, problem: String?, hint: String, today: LocalDate) {
    var picking by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = typed,
        onValueChange = onTyped,
        label = { Text(label) },
        isError = problem != null,
        supportingText = { Text(problem ?: hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            IconButton(onClick = { picking = true }) {
                Icon(painterResource(R.drawable.ic_plan), contentDescription = stringResource(R.string.task_pick_day, label))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (picking) {
        // The calendar counts days in universal time; a day picked there is that day everywhere.
        val shown = rememberDatePickerState(initialSelectedDateMillis = (dayTyped(typed) ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        shown.selectedDateMillis?.let { onTyped(dayToType(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())) }
                        picking = false
                    },
                ) { Text(stringResource(R.string.task_use_day)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = shown)
        }
    }
}
