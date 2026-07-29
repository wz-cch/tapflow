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
import com.tapflow.android.data.Flow
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Repo
import com.tapflow.android.engine.Workspace
import com.tapflow.android.text.clipSummary
import com.tapflow.android.text.flowSummary
import com.tapflow.android.text.defaultClipName
import com.tapflow.android.ui.TapFlowTheme

/**
 * The operations that need a keyboard or a list: save, load, start a new one, write a pause note, and the
 * flow equivalents of new and load.
 *
 * An activity rather than more overlay panels. Every floating window here is FLAG_NOT_FOCUSABLE on
 * purpose — a focusable overlay takes input focus from the app underneath, which is what makes a
 * pause point usable — and a window that cannot take focus cannot raise a keyboard for a text field.
 * An activity gets focus and IME for free, and it is the natural shape for a modal question.
 *
 * One activity with several modes rather than one activity each: same theme, same scaffolding, one
 * manifest entry. Each mode does exactly one thing.
 *
 * Deleting a flow is *not* here. That is a yes/no question, it needs no keyboard, and asking it on an
 * overlay avoids pushing the app underneath into the background for it.
 *
 * The number pad for a timed wait deliberately does *not* come here — digits need no IME, so it stays
 * an overlay and does not push the app being recorded into the background. A note is free text and
 * genuinely needs the keyboard, so it does.
 */
class WorkspaceDialogActivity : ComponentActivity() {

    enum class Mode { SAVE, LOAD, NEW, NOTE, NEW_FLOW }

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
                    Mode.LOAD -> LoadDialog(::finish, { clip -> load(clip) }) { flow -> loadFlow(flow) }
                    Mode.NEW -> NewDialog(::finish) { startNew() }
                    Mode.NOTE -> {
                        val step = Workspace.stepById(intent.getStringExtra(EXTRA_STEP_ID)) as? PauseStep
                        if (step == null) finish() else NoteDialog(step, ::finish) { note -> saveNote(step, note) }
                    }

                    Mode.NEW_FLOW -> NewFlowDialog(::finish) { name -> createFlow(name) }
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
        // Loading a clip means the workspace is the thing being worked on, so any flow is unloaded. Free
        // in this direction: a flow is already saved.
        Repo.setCurrentFlow(null)
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

    /**
     * Creates a flow and loads it, so the toolbar is already pointing at it when you go back.
     *
     * Loading clears the workspace, which is why the dialog says so first when there is unsaved work.
     */
    private fun createFlow(name: String) {
        val flow = Flow(name = name.trim(), clips = emptyList(), createdAt = System.currentTimeMillis())
        Repo.upsertFlow(flow)
        Workspace.clear()
        Repo.setCurrentFlow(flow.id)
        toast(getString(R.string.toast_flow_created, flow.name))
        finish()
    }

    /**
     * Loads a flow, which unloads the workspace.
     *
     * The two are exclusive on purpose: only one thing is ever loaded, so the toolbar's play button has
     * exactly one meaning and there is no "which is current" to get wrong. This direction is the one that
     * can destroy work, so it is the one that warns.
     */
    private fun loadFlow(flow: Flow) {
        Workspace.clear()
        Repo.setCurrentFlow(flow.id)
        toast(getString(R.string.toast_flow_loaded, flow.name))
        finish()
    }

    private fun startNew() {
        Repo.setCurrentFlow(null)
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

/**
 * Loads one thing, and what you pick decides the mode.
 *
 * Clips and flows in one dialog rather than two, because loading is one act with one meaning: exactly one
 * of the two is ever loaded (SPEC 10.5), so "load" needs no noun and the toolbar needs only one button for
 * it. Two separate load buttons would also have had to share an icon in the same mode, which is the
 * ambiguity that keeping them mode-exclusive was meant to avoid — and that exclusivity is what made flow
 * mode unreachable in the first place, since you cannot load a flow from a toolbar that only offers clips.
 */
@Composable
private fun LoadDialog(
    onDismiss: () -> Unit,
    onPickClip: (Clip) -> Unit,
    onPickFlow: (Flow) -> Unit,
) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val clips = Repo.clips.value
    val flows = Repo.flows.value
    val dirty = Workspace.dirty.value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.load_title)) },
        text = {
            if (clips.isEmpty() && flows.isEmpty()) {
                Text(stringResource(R.string.load_empty))
            } else {
                Column {
                    // Loading replaces what is on screen, so say so before it happens rather than after.
                    if (dirty) {
                        Text(
                            stringResource(R.string.load_unsaved_warning, Workspace.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        if (flows.isNotEmpty()) {
                            item {
                                SectionLabel(stringResource(R.string.home_tab_flows))
                            }
                            items(flows, key = { "flow-" + it.id }) { flow ->
                                PickRow(
                                    title = flow.name,
                                    subtitle = flowSummary(resources, flow, clips),
                                ) { onPickFlow(flow) }
                            }
                        }
                        if (clips.isNotEmpty()) {
                            item {
                                SectionLabel(stringResource(R.string.home_tab_clips))
                            }
                            items(clips, key = { "clip-" + it.id }) { clip ->
                                PickRow(
                                    title = clip.name,
                                    subtitle = clipSummary(resources, clip),
                                ) { onPickClip(clip) }
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
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun PickRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

/**
 * Names a new flow.
 *
 * Creating one loads it, and loading clears the workspace — so the warning belongs here, before the name
 * is even typed, rather than as a surprise afterwards.
 */
@Composable
private fun NewFlowDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val dirty = Workspace.dirty.value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flow_new_title)) },
        text = {
            Column {
                if (dirty) {
                    Text(
                        stringResource(R.string.flow_unsaved_warning, Workspace.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.flow_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.flow_new_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

