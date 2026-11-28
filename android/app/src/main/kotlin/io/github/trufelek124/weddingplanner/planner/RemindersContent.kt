package io.github.trufelek124.weddingplanner.planner

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.reminders.Ahead
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("H:mm")

/** "9:00": the time of day as the reminders say it, on the 24-hour clock the rest of the app uses. */
fun timeOfDay(at: LocalTime): String = at.format(TIME)

/** What More says under "Reminders". */
@Composable
fun remindersSummary(ui: RemindersUi): String =
    if (ui.settings.on) stringResource(R.string.reminders_summary_on, timeOfDay(ui.settings.at)) else stringResource(R.string.reminders_summary_off)

private val AHEAD_WORDS = listOf(
    Ahead.TODAY to R.string.reminders_ahead_today,
    Ahead.TOMORROW to R.string.reminders_ahead_tomorrow,
    Ahead.THREE_DAYS to R.string.reminders_ahead_three,
    Ahead.SEVEN_DAYS to R.string.reminders_ahead_seven,
)

/**
 * The settings of the daily reminder: a switch, the time of day, and how far ahead it looks. Switching it on asks the
 * phone for the permission to show notifications where the phone wants that asked; where the phone does not allow them,
 * the screen says so and leads to the phone's settings for the app.
 *
 * @param foot what stands under everything
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersContent(
    ui: RemindersUi,
    model: RemindersModel,
    modifier: Modifier = Modifier,
    foot: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    // The phone's own switch for the app's notifications may be used while the screen is away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.lookAgain() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val asking = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.switchOn() else model.refused()
    }
    var choosingTime by rememberSaveable { mutableStateOf(false) }
    var noSettings by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = ui.settings.on,
                    role = Role.Switch,
                    onValueChange = { on ->
                        when {
                            !on -> model.switchOff()
                            model.allowed() -> model.switchOn()
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> asking.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else -> model.refused()
                        }
                    },
                ),
        ) {
            Text(stringResource(R.string.reminders_switch), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = ui.settings.on, onCheckedChange = null)
        }
        Text(stringResource(R.string.reminders_what), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (ui.notAllowed) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(stringResource(R.string.reminders_not_allowed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                QuietButton(
                    text = stringResource(R.string.reminders_open_settings),
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        noSettings = !startOrSayNo(context, intent)
                    },
                )
                if (noSettings) {
                    Text(stringResource(R.string.reminders_no_settings), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Text(
            stringResource(R.string.reminders_time),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Text(stringResource(R.string.reminders_time_chosen, timeOfDay(ui.settings.at)), style = MaterialTheme.typography.bodyLarge)
        QuietButton(text = stringResource(R.string.reminders_time_pick), onClick = { choosingTime = true })

        Text(
            stringResource(R.string.reminders_ahead),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Text(stringResource(R.string.reminders_ahead_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.selectableGroup()) {
            for ((ahead, words) in AHEAD_WORDS) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = ui.settings.ahead == ahead, role = Role.RadioButton) { model.setAhead(ahead) },
                ) {
                    RadioButton(selected = ui.settings.ahead == ahead, onClick = null)
                    Text(stringResource(words), modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        Column(modifier = Modifier.padding(top = 8.dp)) { foot() }
    }

    if (choosingTime) {
        val picked = rememberTimePickerState(initialHour = ui.settings.at.hour, initialMinute = ui.settings.at.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { choosingTime = false },
            title = { Text(stringResource(R.string.reminders_time_dialog)) },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { TimePicker(state = picked) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.setTime(LocalTime.of(picked.hour, picked.minute))
                        choosingTime = false
                    },
                ) { Text(stringResource(R.string.reminders_time_ok)) }
            },
            dismissButton = { TextButton(onClick = { choosingTime = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun Modifier.selectableGroup(): Modifier = this.fillMaxWidth()
