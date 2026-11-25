package io.github.trufelek124.weddingplanner.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.MOST_BOARD_NAME
import io.github.trufelek124.weddingplanner.data.sameBoardName
import io.github.trufelek124.weddingplanner.ui.QuietButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** At most this many of the shared pictures are shown small above the form; all of them are saved. */
private const val SHOWN = 4

/**
 * What stands between "Share" in another app and the planner: what is being saved, which board it
 * goes on, a note, Save. Nothing here has to be touched but Save.
 *
 * @param onSave the board that was chosen (null for none), the name of a board to be made instead (null for none; never
 *   together with a board, and never the name of a board that is known: that one is chosen), and the note
 */
@Composable
fun ShareScreen(
    shared: Shared,
    ui: ShareUi,
    onSave: (boardId: String?, newBoard: String?, note: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var board by rememberSaveable { mutableStateOf<String?>(null) }
    var makingNew by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    // Said beside the field once Save was pressed with it wrong; a name that is too long is said at once.
    var tried by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf(shared.text) }
    val nameField = remember { FocusRequester() }
    val name = newName.trim()
    val nameProblem = when {
        !makingNew -> null
        name.length > MOST_BOARD_NAME -> R.string.share_new_board_too_long
        name.isEmpty() -> R.string.share_new_board_blank
        else -> null
    }
    val nameProblemShown = nameProblem?.takeIf { it == R.string.share_new_board_too_long || tried }
    LaunchedEffect(makingNew) {
        if (makingNew) {
            nameField.requestFocus()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.share_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )

            if (shared.empty) {
                Text(stringResource(R.string.share_nothing), style = MaterialTheme.typography.bodyLarge)
                QuietButton(stringResource(R.string.share_close), onClick = onCancel)
                return@Column
            }

            when {
                shared.pictures.isNotEmpty() -> {
                    Text(pluralStringResource(R.plurals.share_pictures, shared.pictures.size, shared.pictures.size), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (picture in shared.pictures.take(SHOWN)) {
                            Small(picture)
                        }
                    }
                }
                shared.link != null -> Text(shared.link, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                else -> Text(stringResource(R.string.share_a_note), style = MaterialTheme.typography.bodyLarge)
            }

            // Also with no board known yet: a new one can be named.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.share_board), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = board == null && !makingNew,
                        onClick = {
                            board = null
                            makingNew = false
                        },
                        label = { Text(stringResource(R.string.share_no_board)) },
                    )
                    for (choice in ui.boards) {
                        FilterChip(
                            selected = board == choice.id && !makingNew,
                            onClick = {
                                board = choice.id
                                makingNew = false
                            },
                            label = { Text(choice.name) },
                        )
                    }
                    FilterChip(
                        selected = makingNew,
                        onClick = {
                            board = null
                            makingNew = true
                        },
                        label = { Text(stringResource(R.string.share_new_board)) },
                    )
                }
            }

            if (makingNew) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.share_new_board_name)) },
                    singleLine = true,
                    isError = nameProblemShown != null,
                    supportingText = nameProblemShown?.let { { Text(stringResource(it)) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(nameField).semantics {
                        if (nameProblemShown != null) {
                            liveRegion = LiveRegionMode.Assertive
                        }
                    },
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.share_note)) },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            ui.problem?.let { problem ->
                Text(
                    text = stringResource(
                        when (problem) {
                            ShareProblem.TOO_MANY -> R.string.share_too_many
                            ShareProblem.TOO_LARGE -> R.string.share_too_large
                            ShareProblem.UNREADABLE -> R.string.share_unreadable
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (nameProblem != null) {
                            // Not saved while the name is wrong: it is said beside the field, and the keyboard goes there.
                            tried = true
                            nameField.requestFocus()
                        } else if (makingNew) {
                            // A name a board has already simply chooses that board.
                            val known = ui.boards.firstOrNull { sameBoardName(it.name, name) }
                            onSave(known?.id, if (known == null) name else null, note.trim())
                        } else {
                            onSave(board, null, note.trim())
                        }
                    },
                    enabled = !ui.saving && !ui.saved,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (ui.saving) R.string.share_saving else R.string.save))
                }
                QuietButton(stringResource(R.string.cancel), onClick = onCancel)
            }
        }
    }
}

/** One shared picture, small. It is read at a fraction of its size; a picture that cannot be read leaves its place empty. */
@Composable
private fun Small(picture: Uri) {
    val context = LocalContext.current
    val small by produceState<Bitmap?>(initialValue = null, picture) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(picture)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 8 })
                }
            } catch (unreadable: Exception) {
                null
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val bitmap = small
    if (bitmap == null) {
        Surface(modifier = Modifier.size(72.dp).clip(shape).background(MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.outlineVariant) {}
    } else {
        // What the picture shows is the person's own to know: to a screen reader it is one of the count named above.
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(shape))
    }
}
