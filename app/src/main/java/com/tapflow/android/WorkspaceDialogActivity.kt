package com.tapflow.android

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tapflow.android.data.AppMode
import com.tapflow.android.data.FolderStore
import com.tapflow.android.data.Clip
import com.tapflow.android.data.Flow
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Repo
import com.tapflow.android.engine.Session
import com.tapflow.android.engine.Workspace
import com.tapflow.android.text.clipSummary
import com.tapflow.android.text.flowSummary
import com.tapflow.android.text.defaultClipName
import com.tapflow.android.ui.DiscardConfirmDialog
import com.tapflow.android.ui.TapFlowTheme
import com.tapflow.android.ui.guardDiscard
import com.tapflow.android.ui.wroteToLibrary

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

    enum class Mode { SAVE, LOAD, NOTE, NEW_FLOW }

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
                // Saving and loading are the only two things that touch the library folder, and both
                // arrive here — which is why the folder picker never has to be raised from the
                // accessibility service. Doing it from there would need FLAG_ACTIVITY_NEW_TASK and
                // would push whatever is being recorded into the background.
                // NEW_FLOW writes to the folder too, so it needs the gate as much as save does.
                val needsFolder = mode != Mode.NOTE
                LibraryGate(needsFolder = needsFolder, onGiveUp = ::finish) {
                    when (mode) {
                        Mode.SAVE -> SaveDialog(::finish) { name, asNew -> save(name, asNew) }
                        Mode.LOAD -> LoadDialog(::finish, { clip -> load(clip) }) { flow -> loadFlow(flow) }
                        Mode.NOTE -> {
                            val step = Workspace.stepById(intent.getStringExtra(EXTRA_STEP_ID)) as? PauseStep
                            if (step == null) finish() else NoteDialog(step, ::finish) { note -> saveNote(step, note) }
                        }

                        Mode.NEW_FLOW -> NewFlowDialog(::finish) { name -> createFlow(name) }
                    }
                }
            }
        }
    }

    /**
     * Saves, and says which of the three things happened.
     *
     * A failure deliberately does not close this screen: the folder is unreachable, the workspace is
     * still dirty, and offering the picker again here is the whole remedy. Closing would leave the
     * user holding an unsaved recording with nothing on screen explaining it.
     */
    private fun save(name: String, asNew: Boolean) {
        when (val result = Workspace.commit(name, System.currentTimeMillis(), asNew)) {
            is Workspace.Saved.Ok -> {
                toast(getString(R.string.toast_saved, result.clip.name))
                finish()
            }

            Workspace.Saved.Nothing -> {
                toast(getString(R.string.toast_nothing_to_save))
                finish()
            }

            // No finish(), and no extra state to make the gate come back: the failed write already
            // cleared FolderStore.usable, and the gate observes it — so this screen turns into "pick
            // the folder again" on its own.
            Workspace.Saved.Failed -> toast(getString(R.string.toast_save_failed))
        }
    }

    private fun load(clip: Clip) {
        Session.loadClip(clip)
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
     * Stops on a failed write rather than loading a flow that was never stored. Not closing is the point:
     * the gate takes over and offers the folder again, which is the only thing that would help.
     */
    private fun createFlow(name: String) {
        val flow = Flow(name = name.trim(), clips = emptyList(), createdAt = System.currentTimeMillis())
        if (!wroteToLibrary { Repo.upsertFlow(flow) }) return
        Session.loadFlow(flow)
        toast(getString(R.string.toast_flow_created, flow.name))
        finish()
    }

    private fun loadFlow(flow: Flow) {
        Session.loadFlow(flow)
        toast(getString(R.string.toast_flow_loaded, flow.name))
        finish()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_MODE = "com.tapflow.android.WORKSPACE_MODE"
        const val EXTRA_STEP_ID = "com.tapflow.android.STEP_ID"
    }
}

/**
 * Stands in front of anything that touches the library folder.
 *
 * Three states, and each one exists because it actually happens. **Not configured** is the first save
 * or load, which is where asking belongs — not at first launch, where a folder picker in front of an
 * app nobody has tried yet is a toll gate. **Configured but unreachable** is a reinstall (the grant
 * goes with the app while the files stay), a deleted folder, or an ejected card. **Checking** is the
 * IO those two answers need, which is why it cannot be a plain `if`.
 *
 * The library is read here as well, once, off the main thread. Reading it at startup would have put a
 * folder-wide scan on whichever thread happened to bind the service, for a list that recording,
 * editing and replaying never look at.
 */
@Composable
private fun LibraryGate(needsFolder: Boolean, onGiveUp: () -> Unit, content: @Composable () -> Unit) {
    if (!needsFolder) {
        content()
        return
    }

    val usable by FolderStore.usable.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        checking = true
        scope.launch {
            withContext(Dispatchers.IO) { Repo.useFolder(uri) }
            checking = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (FolderStore.isConfigured && FolderStore.refreshUsable() && !Repo.libraryLoaded.value) {
                Repo.loadLibrary()
            }
        }
        checking = false
    }

    when {
        checking -> BusyDialog()
        usable -> content()
        else -> PickFolderDialog(onPick = { picker.launch(null) }, onDismiss = onGiveUp)
    }
}

@Composable
private fun BusyDialog() {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.folder_reading))
            }
        },
        confirmButton = {},
    )
}

/**
 * Asks for the folder.
 *
 * The body has to name a *subfolder* rather than just saying "pick a folder", because Android 11 and
 * up refuse the root of internal storage and refuse Download outright. A user who picks one of those
 * gets a system refusal and reads it as this app being broken.
 */
@Composable
private fun PickFolderDialog(onPick: () -> Unit, onDismiss: () -> Unit) {
    val configured = FolderStore.isConfigured
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (configured) R.string.folder_lost_title else R.string.folder_pick_title))
        },
        text = {
            Text(stringResource(if (configured) R.string.folder_lost_body else R.string.folder_pick_body))
        },
        confirmButton = {
            TextButton(onClick = onPick) { Text(stringResource(R.string.folder_pick_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
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
 * Loads one thing, of whichever kind the current mode is about.
 *
 * Only that kind is listed. It used to show clips *and* flows in one dialog, from a time when picking one
 * was what set the mode — but with the mode explicit, offering the other kind here is a hole: picking a
 * flow from clip mode would empty the workspace without ever passing the mode button, which is the one
 * place that asks before discarding. So the noun follows the mode, and this dialog only ever offers one.
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
    val flowMode = Repo.mode.value == AppMode.FLOW
    // Whichever kind was picked, held until the discard question is answered.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Both kinds, not just clips. What is at risk is the *workspace*, and loading either one empties it —
    // it is not the picked thing that might be unsaved. Today a dirty workspace cannot coexist with flow
    // mode, so the flow branch happens never to fire; guarding on "happens to be unreachable" is how a
    // guard goes missing the moment the surrounding rules move.
    fun pick(load: () -> Unit) = guardDiscard(load) { pending = load }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.load_title)) },
        text = {
            val empty = if (flowMode) flows.isEmpty() else clips.isEmpty()
            if (empty) {
                Text(stringResource(R.string.load_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (flowMode) {
                        items(flows, key = { it.id }) { flow ->
                            PickRow(flow.name, flowSummary(resources, flow, clips)) {
                                pick { onPickFlow(flow) }
                            }
                        }
                    } else {
                        items(clips, key = { it.id }) { clip ->
                            PickRow(clip.name, clipSummary(resources, clip)) {
                                pick { onPickClip(clip) }
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

    pending?.let { load ->
        DiscardConfirmDialog(onDismiss = { pending = null }) { load() }
    }
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
 * Names a new flow, then loads it.
 *
 * Loading switches to flow mode, which empties the workspace — so the shared discard question comes after
 * the name is typed, on confirm. It used to be a red line inside this dialog, before the name was even
 * entered; the same words as everywhere else, at the moment it actually applies, is worth more.
 */
@Composable
private fun NewFlowDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flow_new_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.flow_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { guardDiscard({ onCreate(name) }) { confirming = true } },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.flow_new_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )

    if (confirming) {
        DiscardConfirmDialog(onDismiss = { confirming = false }) { onCreate(name) }
    }
}

