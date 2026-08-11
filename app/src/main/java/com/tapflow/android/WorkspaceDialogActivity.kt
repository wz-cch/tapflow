package com.tapflow.android

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tapflow.android.data.AppMode
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Repo
import com.tapflow.android.engine.Session
import com.tapflow.android.engine.Workspace
import com.tapflow.android.text.defaultClipName
import com.tapflow.android.text.defaultFlowName
import com.tapflow.android.ui.BusyDialog
import com.tapflow.android.ui.DiscardConfirmDialog
import com.tapflow.android.ui.Picked
import com.tapflow.android.ui.TapFlowTheme
import com.tapflow.android.ui.rememberFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The toolbar's errands that need a screen: choosing a file, and typing a pause note.
 *
 * An activity rather than more overlay panels. Every floating window in this app is FLAG_NOT_FOCUSABLE on
 * purpose — a focusable overlay takes input focus from the app underneath, which is what makes a pause point
 * usable — and a window that cannot take focus can neither raise a keyboard nor host the document picker.
 *
 * **Two modes, and one of them is the whole of storage.** Opening, saving as and creating a flow used to be
 * three of them, which meant the user answered "which of these am I doing" on the toolbar before being shown
 * the list they could have answered it from. They are one panel now (`ui/FileBrowser.kt`); what is left here
 * is the wrapper that turns its answer into an open workspace or a written file, and one text field for a
 * note.
 */
class WorkspaceDialogActivity : ComponentActivity() {

    enum class Mode { STORAGE, NOTE }

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
                    Mode.STORAGE -> Storage(::finish)
                    Mode.NOTE -> {
                        val step = Workspace.stepById(intent.getStringExtra(EXTRA_STEP_ID)) as? PauseStep
                        if (step == null) finish() else NoteDialog(step, ::finish) { note -> saveNote(step, note) }
                    }
                }
            }
        }
    }

    /**
     * Blank is a valid note, not a cancellation — the prompt falls back to a generic line, and
     * clearing one you no longer want should be possible without deleting the step.
     */
    private fun saveNote(step: PauseStep, note: String) {
        Workspace.updateStep(step.copy(note = note.trim()))
        finish()
    }

    companion object {
        const val EXTRA_MODE = "com.tapflow.android.WORKSPACE_MODE"
        const val EXTRA_STEP_ID = "com.tapflow.android.STEP_ID"
    }
}

/**
 * Opens the storage panel, then does one thing with what came back and closes.
 *
 * @param guardSave whether *writing* would throw away unsaved steps as well. Opening always would, so it is
 *   not a parameter; saving a clip never does, and creating a flow does because it changes mode. Asked
 *   **after** the panel is done with, which is the same rule the discard helper documents and the same one
 *   the home screen follows: backing out is ordinary, so a question in front of the panel is mostly answered
 *   for nothing.
 * @param onOpen what to do with a file to read. Runs on the main thread and does its own IO, because it has
 *   to touch [Session] afterwards, which must not be touched off it.
 * @param onSave the same, for a file to write.
 */
@Composable
private fun PickThen(
    kind: DocKind,
    suggestedName: String?,
    onFinish: () -> Unit,
    onOpen: suspend (String) -> Unit,
    onSave: suspend (String) -> Unit,
) {
    var pending by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun proceed(act: suspend () -> Unit) {
        busy = true
        scope.launch {
            // Finishes even if the work throws. This activity is transparent and has nothing of its own on
            // screen, so a coroutine that dies quietly would leave a spinner over the app underneath with no
            // way out at all.
            try {
                act()
            } finally {
                onFinish()
            }
        }
    }

    fun take(guard: Boolean, act: suspend () -> Unit) {
        if (guard && Session.needsConfirm) pending = act else proceed(act)
    }

    val picker = rememberFilePicker(kind) { picked ->
        when (picked) {
            Picked.Cancelled -> onFinish()
            is Picked.Open -> take(true) { onOpen(picked.ref) }
            // Saving a clip is the opposite of throwing work away, so it never asks.
            is Picked.Save -> take(false) { onSave(picked.ref) }
        }
    }

    // Straight into the panel: this activity has nothing of its own to show. It exists because an overlay
    // cannot host one — every window the service puts up is FLAG_NOT_FOCUSABLE.
    LaunchedEffect(Unit) {
        if (suggestedName == null) picker.open() else picker.browse(suggestedName)
    }

    pending?.let { act ->
        DiscardConfirmDialog(onDismiss = onFinish) {
            pending = null
            proceed(act)
        }
    }
    // Cancellable, and here it matters more than anywhere: there is no screen of ours underneath to go back
    // to, so without this the only exit from a slow read is force-stopping the app.
    if (busy) BusyDialog(onCancel = onFinish)
}

/**
 * Storage, for whichever noun the toolbar is on: open one, or write one.
 *
 * **Only the current mode's kind**, which is the same rule the old list dialog followed and for the same
 * reason: opening a flow from clip mode would empty the workspace without ever passing the mode button, and
 * that button is the one place a mode change gets questioned.
 *
 * The two halves are not symmetrical, and one parameter says how. Opening replaces what is open, so it asks
 * before throwing away unsaved steps. Saving a clip is the opposite of throwing work away, so it does not.
 * Creating a flow asks again, because having a flow open *is* a mode.
 */
@Composable
private fun Storage(onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val flowMode = Repo.mode.value == AppMode.FLOW
    // The name only, with no extension: the field is for the part the user owns. An already-saved clip
    // suggests its own name, so save-as starts from "this thing, somewhere else".
    val suggested = remember {
        Workspace.source.value?.name ?: defaultClipName(context.resources, System.currentTimeMillis())
    }

    PickThen(
        kind = if (flowMode) DocKind.FLOW else DocKind.CLIP,
        // Flows are not written from here any more. One is arranged on the editor screen and named when it
        // is saved, so a name field in front of the list would be asking what to call something that does
        // not exist yet — which is exactly the order this stopped doing.
        suggestedName = suggested.takeUnless { flowMode },
        onFinish = onFinish,
        onOpen = { ref ->
            if (flowMode) {
                val opened = withContext(Dispatchers.IO) { Repo.openFlow(ref) }
                if (opened == null) {
                    context.toast(context.getString(R.string.toast_open_flow_failed))
                } else {
                    Session.openFlow(opened)
                    context.toast(context.getString(R.string.toast_flow_loaded, opened.file.name))
                }
            } else {
                val loaded = withContext(Dispatchers.IO) { Repo.openClip(ref) }
                if (loaded == null) {
                    context.toast(context.getString(R.string.toast_open_clip_failed))
                } else {
                    Session.openClip(loaded)
                    context.toast(context.getString(R.string.toast_loaded, loaded.file.name))
                }
            }
        },
        onSave = { ref ->
            // The file written to becomes the one `💾` overwrites from then on — the same as every editor's
            // save-as. Only reachable in clip mode; see suggestedName above.
            val result = withContext(Dispatchers.IO) { Workspace.commit(Repo.fileAt(ref)) }
            context.toast(
                when (result) {
                    is Workspace.Saved.Ok -> context.getString(R.string.toast_saved, result.file.name)
                    Workspace.Saved.Nothing -> context.getString(R.string.toast_nothing_to_save)
                    Workspace.Saved.Failed -> context.getString(R.string.toast_save_failed)
                }
            )
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

private fun android.content.Context.toast(text: String) =
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
