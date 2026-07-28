package com.tapflow.android

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapflow.android.data.Clip
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Repo
import com.tapflow.android.engine.Workspace
import com.tapflow.android.text.clipSummary
import com.tapflow.android.text.defaultClipName
import com.tapflow.android.ui.TapFlowTheme

/**
 * The workspace operations that need a keyboard: save, load, start a new one, and write a pause note.
 *
 * An activity rather than more overlay panels. Every floating window here is FLAG_NOT_FOCUSABLE on
 * purpose — a focusable overlay takes input focus from the app underneath, which is what makes a
 * pause point usable — and a window that cannot take focus cannot raise a keyboard for a text field.
 * An activity gets focus and IME for free, and it is the natural shape for a modal question.
 *
 * One activity with four modes rather than four activities: same theme, same scaffolding, one
 * manifest entry. Each mode does exactly one thing.
 *
 * The number pad for a timed wait deliberately does *not* come here — digits need no IME, so it stays
 * an overlay and does not push the app being recorded into the background. A note is free text and
 * genuinely needs the keyboard, so it does.
 */
class WorkspaceDialogActivity : ComponentActivity() {

    enum class Mode { SAVE, LOAD, NEW, NOTE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)

        val mode = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }
            .getOrNull()
        if (mode == null) {
            finish()
            return
        }

        setContent {
            TapFlowTheme {
                when (mode) {
                    Mode.SAVE -> SaveDialog(::finish) { name, asNew -> save(name, asNew) }
                    Mode.LOAD -> LoadDialog(::finish) { clip -> load(clip) }
                    Mode.NEW -> NewDialog(::finish) { startNew() }
                    Mode.NOTE -> {
                        val step = Workspace.stepById(intent.getStringExtra(EXTRA_STEP_ID)) as? PauseStep
                        if (step == null) finish() else NoteDialog(step, ::finish) { note -> saveNote(step, note) }
                    }
                }
            }
        }
    }

    private fun save(name: String, asNew: Boolean) {
        val clip = Workspace.commit(name, System.currentTimeMillis(), asNew)
        toast(
            if (clip == null) getString(R.string.toast_nothing_to_save)
            else getString(R.string.toast_saved, clip.name)
        )
        finish()
    }

    private fun load(clip: Clip) {
        Workspace.load(clip)
        toast(getString(R.string.toast_loaded, clip.name))
        finish()
    }

    /**
     * Blank is a valid note, not a cancellation — the prompt falls back to a generic line, and
     * clearing one you no longer want should be possible without deleting the step.
     */
    private fun saveNote(step: PauseStep, note: String) {
        Workspace.updateStep(step.copy(note = note.trim()))
        finish()
    }

    private fun startNew() {
        Workspace.clear()
        toast(getString(R.string.toast_workspace_cleared))
        finish()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_MODE = "com.tapflow.android.WORKSPACE_MODE"
        const val EXTRA_STEP_ID = "com.tapflow.android.STEP_ID"
    }
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onSave: (name: String, asNew: Boolean) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val source = Repo.clipById(Workspace.sourceClipId)
    var name by remember {
        mutableStateOf(source?.name ?: defaultClipName(resources, System.currentTimeMillis()))
    }
    // Saving over the clip the workspace came from is the expected default; the tick opts out.
    var asNew by remember { mutableStateOf(source == null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.save_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (source != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = asNew, onCheckedChange = { asNew = it })
                        Text(
                            stringResource(R.string.save_as_new),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, asNew) }) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun LoadDialog(onDismiss: () -> Unit, onPick: (Clip) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val clips = Repo.clips.value
    val dirty = Workspace.dirty.value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.load_title)) },
        text = {
            if (clips.isEmpty()) {
                Text(stringResource(R.string.load_empty))
            } else {
                Column {
                    // Loading replaces what is on screen, so say so before it happens rather than
                    // after.
                    if (dirty) {
                        Text(
                            stringResource(R.string.load_unsaved_warning, Workspace.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(clips, key = { it.id }) { clip ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(clip) }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    clip.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    clipSummary(resources, clip),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun NewDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val count = Workspace.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_title)) },
        text = {
            Text(
                if (count == 0) stringResource(R.string.new_body_empty)
                else stringResource(R.string.new_body, count)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.new_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}


/**
 * Types the note shown when a replay stops on this step.
 *
 * Not asked for at insert time on purpose. Inserting a pause point is one tap with no dialog (SPEC
 * 10.1), and the moment after it is exactly when the user wants the target app in front of them to do
 * the step by hand — throwing an activity up then would be in the way.
 */
@Composable
private fun NoteDialog(step: PauseStep, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf(step.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.note_explain),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    singleLine = false,
                    maxLines = 3,
                    label = { Text(stringResource(R.string.note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
