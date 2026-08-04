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
import com.tapflow.android.data.DocFile
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.DocStore
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Repo
import com.tapflow.android.data.suggestedFileName
import com.tapflow.android.engine.Session
import com.tapflow.android.engine.Workspace
import com.tapflow.android.text.defaultClipName
import com.tapflow.android.text.defaultFlowName
import com.tapflow.android.ui.BusyDialog
import com.tapflow.android.ui.DiscardConfirmDialog
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
 * **Three of the four modes are now nothing but a picker.** Opening, saving as, and creating a flow used to be
 * dialogs of our own, listing a library folder's contents and asking for a name; a clip is a file now, so the
 * platform's picker does all three and does them better — it has search, other storage providers, and the
 * user's own idea of where things belong. What is left here is the wrapper that turns "the user chose a file"
 * into an open workspace, and one text field for a note.
 *
 * There is no folder gate any more. It used to stand in front of everything that touched storage, asking for
 * a folder to be granted before the first save could happen; nothing needs to be arranged in advance now, so
 * the whole state — configured, unreachable, checking — is gone.
 */
class WorkspaceDialogActivity : ComponentActivity() {

    enum class Mode { SAVE_AS, LOAD, NOTE, NEW_FLOW }

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
                    Mode.SAVE_AS -> SaveAs(::finish)
                    Mode.LOAD -> Open(::finish)
                    Mode.NEW_FLOW -> NewFlow(::finish)
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
 * Asks for a file, then does one thing with it and closes.
 *
 * @param guarded whether going ahead would throw away unsaved steps. Asked **after** a file has been chosen,
 *   which is the same rule the discard helper documents and the same one the home screen follows: backing out
 *   of a picker is ordinary, so a question in front of one is mostly answered for nothing.
 * @param suggestedName non-null to create a file rather than open one.
 * @param act what to do with the ref. Runs on the main thread and does its own IO, because each of the three
 *   callers needs a different mix — a read, a write, or both — and then has to touch [Session], which must not
 *   be touched off it.
 */
@Composable
private fun PickThen(
    kind: DocKind,
    suggestedName: String?,
    guarded: Boolean,
    onFinish: () -> Unit,
    act: suspend (String) -> Unit,
) {
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun proceed(ref: String) {
        busy = true
        scope.launch {
            act(ref)
            onFinish()
        }
    }

    val picker = rememberFilePicker(kind) { ref ->
        when {
            ref == null -> onFinish()
            guarded && Session.needsConfirm -> pending = ref
            else -> proceed(ref)
        }
    }

    // Straight into the picker: this activity has nothing of its own to show. It exists because an overlay
    // cannot host one — every window the service puts up is FLAG_NOT_FOCUSABLE.
    LaunchedEffect(Unit) {
        if (suggestedName != null) picker.create(suggestedName) else picker.open()
    }

    pending?.let { ref ->
        DiscardConfirmDialog(onDismiss = onFinish) {
            pending = null
            proceed(ref)
        }
    }
    if (busy) BusyDialog()
}

/**
 * Opens a clip or a flow, whichever the current mode is about.
 *
 * Only the current mode's kind, which is the same rule the old list dialog followed and for the same reason:
 * opening a flow from clip mode would empty the workspace without ever passing the mode button, and that
 * button is the one place a mode change gets questioned.
 */
@Composable
private fun Open(onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val flowMode = Repo.mode.value == AppMode.FLOW

    PickThen(
        kind = if (flowMode) DocKind.FLOW else DocKind.CLIP,
        suggestedName = null,
        guarded = true,
        onFinish = onFinish,
    ) { ref ->
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
    }
}

/**
 * Writes the workspace to a file the user names.
 *
 * The name is suggested, not asked for: whatever the picker is given is what appears in its own name field,
 * so there is no naming dialog of ours in front of it. The file that comes back becomes the one `💾`
 * overwrites from then on — the same as every editor's save-as.
 */
@Composable
private fun SaveAs(onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val suggested = remember {
        val current = Workspace.source.value?.name
            ?: defaultClipName(context.resources, System.currentTimeMillis())
        suggestedFileName(current, DocKind.CLIP)
    }

    PickThen(
        kind = DocKind.CLIP,
        suggestedName = suggested,
        guarded = false,
        onFinish = onFinish,
    ) { ref ->
        val result = withContext(Dispatchers.IO) {
            Workspace.commit(DocFile(ref, DocStore.label(ref)))
        }
        context.toast(
            when (result) {
                is Workspace.Saved.Ok -> context.getString(R.string.toast_saved, result.file.name)
                Workspace.Saved.Nothing -> context.getString(R.string.toast_nothing_to_save)
                Workspace.Saved.Failed -> context.getString(R.string.toast_save_failed)
            }
        )
    }
}

/**
 * Creates an empty flow file and opens it.
 *
 * Guarded, because opening a flow switches mode and so empties the workspace. Creating one is flow mode's way
 * in, and it would be odd for it to leave you in clip mode.
 */
@Composable
private fun NewFlow(onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val suggested = remember {
        suggestedFileName(defaultFlowName(context.resources, System.currentTimeMillis()), DocKind.FLOW)
    }

    PickThen(
        kind = DocKind.FLOW,
        suggestedName = suggested,
        guarded = true,
        onFinish = onFinish,
    ) { ref ->
        val opened = withContext(Dispatchers.IO) { Repo.createFlow(ref) }
        if (opened == null) {
            context.toast(context.getString(R.string.toast_save_failed))
        } else {
            Session.openFlow(opened)
            context.toast(context.getString(R.string.toast_flow_created, opened.file.name))
        }
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

private fun android.content.Context.toast(text: String) =
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
