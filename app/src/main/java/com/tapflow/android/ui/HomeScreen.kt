package com.tapflow.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapflow.android.BuildConfig
import com.tapflow.android.R
import com.tapflow.android.data.AppMode
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.DocStore
import com.tapflow.android.data.RecentDoc
import com.tapflow.android.data.Recents
import com.tapflow.android.data.Repo
import com.tapflow.android.data.suggestedFileName
import com.tapflow.android.engine.CrashLog
import com.tapflow.android.engine.EngineState
import com.tapflow.android.engine.Session
import com.tapflow.android.engine.Workspace
import com.tapflow.android.text.clipSummary
import com.tapflow.android.text.defaultFlowName
import com.tapflow.android.text.flowSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The launch screen.
 *
 * Not a mode selector, and not the only way to open something. Its job is to get you into a mode with
 * something in it and then get out of the way — because everything you actually *do* with a clip happens
 * on top of another app, where the toolbar is. So opening here closes this screen.
 *
 * ### The lists are a memory, not a library
 *
 * What used to be here was every clip and flow found by walking one folder the user had granted. This is the
 * files they have recently had open, with the summary of each one cached — so the screen does **no** IO to
 * draw itself, and there is no folder that has to be configured before anything works. Nothing here claims to
 * be everything you own: your clips are wherever you put them, and "open a file" is the way to anything not
 * on the list.
 *
 * A row whose file has gone is greyed and marked rather than dropped. The user is the one who moved it, so the
 * row is how they find out — and it still offers a way off the list and a way to delete what is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenFlow: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = rememberServiceState()
    val status = service.status
    val overlayEnabled by Repo.overlayEnabled.collectAsStateWithLifecycle()
    val needsOverlayPermission by EngineState.needsOverlayPermission.collectAsStateWithLifecycle()
    val recent by Recents.docs.collectAsStateWithLifecycle()
    val openFlow by Repo.openFlow.collectAsStateWithLifecycle()
    val source by Workspace.source.collectAsStateWithLifecycle()
    val mode by Repo.mode.collectAsStateWithLifecycle()
    val crashRevision by CrashLog.revision.collectAsStateWithLifecycle()
    val crashed = remember(crashRevision) { CrashLog.read() != null }
    var closingToolbar by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Whatever is waiting on "this will throw away unsaved steps". One holder rather than one flag per
    // action, because every one of them is the same question about the same workspace.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val clips = recent.filter { it.kind == DocKind.CLIP }
    val flows = recent.filter { it.kind == DocKind.FLOW }

    // After the list is on screen, not before it. Twenty existence checks are twenty provider round trips,
    // and the rows are already drawable without them — which is the whole point of caching the summaries.
    LaunchedEffect(Unit) {
        val gone = withContext(Dispatchers.IO) {
            Recents.docs.value.filterNot { DocStore.exists(it.ref) }.map { it.ref }.toSet()
        }
        Recents.setMissing(gone)
    }

    /**
     * Hands over to the toolbar.
     *
     * Turning the overlay on is part of opening, not a separate step the user has to remember: opening
     * means "I am about to use this", and the toolbar is how it gets used.
     *
     * The check is `!= DISABLED`, **not** `== RUNNING`, for the same reason the toolbar switch below
     * uses that form. `ENABLED_NOT_RUNNING` is a routine, temporary state — the process gets killed by
     * swiping the app off recents, and there is a window right after enabling the service where it has
     * not bound yet — so treating it as "not enabled" tells someone who *did* enable it that they did
     * not, which is a dead end. Turning the overlay on records an intention; the service reads it when
     * it connects. Only a genuinely disabled service is worth staying on this screen for, because then
     * the card at the top is the thing that helps.
     */
    fun handOver() {
        if (status == ServiceStatus.DISABLED) {
            Toast.makeText(context, context.getString(R.string.toast_service_off), Toast.LENGTH_SHORT).show()
            return
        }
        Repo.setOverlayEnabled(true)
        onClose()
    }

    fun startFresh() {
        Session.startFresh()
        handOver()
    }

    /**
     * Runs something that would replace the workspace, asking first if that costs unsaved steps.
     *
     * At the point of *doing* rather than before choosing, which is why the file pickers below wrap their
     * result and not their launch: backing out of a picker is common, and a question asked before one opens
     * would mostly be answered for nothing.
     */
    fun guarded(action: () -> Unit) = guardDiscard(action) { pending = action }

    /** Reads a clip and hands over to the toolbar with it open. */
    fun openClip(ref: String) {
        busy = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { Repo.openClip(ref) }
            busy = false
            if (loaded == null) {
                context.toast(context.getString(R.string.toast_open_clip_failed))
                return@launch
            }
            Session.openClip(loaded)
            context.toast(context.getString(R.string.toast_loaded, loaded.file.name))
            handOver()
        }
    }

    /**
     * Reads a flow, then either hands over to the toolbar or opens the editor.
     *
     * Both are "open the flow" now — the editor works on the flow that is open, so arranging one loads it
     * exactly as playing it does. It used to be possible to arrange without loading, which is why both routes
     * are guarded against discarding unsaved steps.
     */
    fun openFlowFile(ref: String, arrange: Boolean) {
        busy = true
        scope.launch {
            val opened = withContext(Dispatchers.IO) { Repo.openFlow(ref) }
            busy = false
            if (opened == null) {
                context.toast(context.getString(R.string.toast_open_flow_failed))
                return@launch
            }
            Session.openFlow(opened)
            if (arrange) {
                onOpenFlow(ref, false)
            } else {
                context.toast(context.getString(R.string.toast_flow_loaded, opened.file.name))
                handOver()
            }
        }
    }

    fun createFlow(ref: String) {
        busy = true
        scope.launch {
            val created = withContext(Dispatchers.IO) { Repo.createFlow(ref) }
            busy = false
            if (created == null) {
                context.toast(context.getString(R.string.toast_save_failed))
                return@launch
            }
            Session.openFlow(created)
            // Straight into the editor, because a flow with no clips in it does nothing.
            onOpenFlow(created.file.ref, true)
        }
    }

    val clipOpener = rememberFilePicker(DocKind.CLIP) { ref ->
        ref?.let { picked -> guarded { openClip(picked) } }
    }
    val flowOpener = rememberFilePicker(DocKind.FLOW) { ref ->
        ref?.let { picked -> guarded { openFlowFile(picked, arrange = false) } }
    }
    val flowCreator = rememberFilePicker(DocKind.FLOW) { ref ->
        ref?.let { picked -> guarded { createFlow(picked) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        // Under the app name, where it cannot be missed, and only on a debug build — which
                        // is every build that gets hand-installed for testing. It exists because "are you
                        // on the build I just sent" was costing a round trip each time it came up: a sha
                        // needs looking up, a timestamp does not.
                        if (BuildConfig.DEBUG) {
                            Text(
                                buildStamp(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                AccessibilityCard(service) { context.openAccessibilitySettings() }
            }

            // Only worth mentioning once the accessibility overlay has actually been refused, which
            // is the only situation where SYSTEM_ALERT_WINDOW matters.
            if (needsOverlayPermission) {
                item {
                    OverlayPermissionCard { context.openOverlaySettings() }
                }
            }

            item {
                ToolbarSwitch(
                    // Usable as soon as the service is enabled in settings, not only once it has
                    // bound. The switch records an intention; the service reads it when it connects
                    // and puts the toolbar up then. Requiring a live binding turned a normal,
                    // temporary state into "the switch does nothing".
                    enabled = status != ServiceStatus.DISABLED,
                    checked = overlayEnabled,
                    onCheckedChange = { on ->
                        // Turning it off is a deliberate exit, so it empties the workspace — the same act
                        // as the toolbar's own dismiss, and the same question first.
                        if (!on) {
                            guardDiscard({ Session.close(); Repo.setOverlayEnabled(false) }) {
                                closingToolbar = true
                            }
                        } else {
                            Repo.setOverlayEnabled(true)
                        }
                    },
                )
            }

            item {
                // Which mode opening a row would land you in, and which one is already in force. Read-only:
                // switching lives on the toolbar alone, for the same reason opening lives in one place —
                // one act, one entry point. Opening a row below is what changes it from here.
                Text(
                    stringResource(
                        if (mode == AppMode.FLOW) R.string.home_mode_flow else R.string.home_mode_clip
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item {
                OutlinedButton(onClick = { guarded(::startFresh) }) {
                    Text(stringResource(R.string.home_start_fresh))
                }
            }

            item {
                Text(
                    stringResource(R.string.home_recent_clips),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (clips.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.home_no_clips),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(clips, key = { it.ref }) { doc ->
                    RecentRow(
                        doc = doc,
                        summary = clipSummary(context.resources, doc.stepCount, doc.pauseCount, doc.durationMs),
                        open = mode == AppMode.CLIP && doc.ref == source?.ref,
                        onOpen = { guarded { openClip(doc.ref) } },
                    )
                }
            }

            // The way to anything the list does not hold, and on a fresh install the only way at all. Not
            // hidden behind a menu for that reason: with no folder to configure, this *is* the storage UI.
            item {
                OutlinedButton(onClick = { clipOpener.open() }) {
                    Text(stringResource(R.string.home_open_clip))
                }
            }

            item {
                Text(
                    stringResource(R.string.home_recent_flows),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            if (flows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.home_no_flows),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(flows, key = { it.ref }) { doc ->
                    RecentRow(
                        doc = doc,
                        summary = flowSummary(context.resources, doc.clipCount, doc.durationMs),
                        open = mode == AppMode.FLOW && doc.ref == openFlow?.file?.ref,
                        onOpen = { guarded { openFlowFile(doc.ref, arrange = false) } },
                        onArrange = { guarded { openFlowFile(doc.ref, arrange = true) } },
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { flowOpener.open() }) {
                        Text(stringResource(R.string.home_open_flow))
                    }
                    // Flow mode's own "start without loading": a flow is made by arranging clips, which needs
                    // a list on a real screen, so unlike a clip it cannot begin on the toolbar.
                    OutlinedButton(
                        onClick = {
                            flowCreator.create(
                                suggestedFileName(
                                    defaultFlowName(context.resources, System.currentTimeMillis()),
                                    DocKind.FLOW,
                                )
                            )
                        }
                    ) { Text(stringResource(R.string.flow_new_title)) }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                // Also here, at the conventional place, so a release build still identifies itself once the
                // debug subtitle above is gone.
                Text(
                    stringResource(R.string.home_version, buildStamp()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                // Said here because otherwise nobody would know to look. A crash report that sits in a
                // screen you have no reason to open is the same as no crash report.
                if (crashed) {
                    Text(
                        stringResource(R.string.home_crashed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedButton(onClick = onOpenDiagnostics) {
                    Text(stringResource(R.string.diag_title))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (busy) BusyDialog()

    pending?.let { action ->
        DiscardConfirmDialog(onDismiss = { pending = null }) {
            pending = null
            action()
        }
    }

    if (closingToolbar) {
        DiscardConfirmDialog(onDismiss = { closingToolbar = false }) {
            closingToolbar = false
            Session.close()
            Repo.setOverlayEnabled(false)
        }
    }
}

@Composable
private fun AccessibilityCard(service: ServiceState, onOpen: () -> Unit) {
    val status = service.status
    val crashed = status != ServiceStatus.RUNNING && service.error != null
    val problem = status == ServiceStatus.ENABLED_NOT_RUNNING

    Card(
        colors = if (problem) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(
                    when {
                        status == ServiceStatus.RUNNING -> R.string.onboarding_service_ready
                        crashed -> R.string.onboarding_service_error_title
                        problem -> R.string.onboarding_service_stalled_title
                        else -> R.string.onboarding_accessibility_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
            )

            if (status == ServiceStatus.RUNNING) return@Column

            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when {
                        crashed -> R.string.onboarding_service_error_body
                        problem -> R.string.onboarding_service_stalled_body
                        else -> R.string.onboarding_accessibility_body
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            // The actual exception, so it can be read off the screen and reported. Toggling the
            // service off and on will not help if it crashes every time it starts.
            if (crashed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    service.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpen) {
                Text(stringResource(R.string.onboarding_accessibility_action))
            }
        }
    }
}

@Composable
private fun OverlayPermissionCard(onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.onboarding_overlay_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.onboarding_overlay_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpen) {
                Text(stringResource(R.string.onboarding_overlay_action))
            }
        }
    }
}

@Composable
private fun ToolbarSwitch(enabled: Boolean, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.home_show_toolbar),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = checked && enabled, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * One recently-opened file, of either kind.
 *
 * One row type for clips and flows, where there used to be two. What differed between them was the summary
 * line and one menu entry, and both are now parameters — everything else is the same three acts on a file:
 * open it, rename it, get rid of it.
 *
 * Tapping the row opens it. Renaming and the two ways of getting rid of it live in the `⋮`, because they are
 * maintenance and one of them needs a keyboard.
 *
 * @param onArrange present only for flows, whose contents are arranged on a screen rather than captured by
 *   gesture. The asymmetry is real: a clip has no equivalent, since its steps are recorded on top of another
 *   app.
 */
@Composable
private fun RecentRow(
    doc: RecentDoc,
    summary: String,
    open: Boolean,
    onOpen: () -> Unit,
    onArrange: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // Greyed rather than removed. The file was moved by the person reading this screen, so the row is the
    // only thing that can tell them which one — and it still has a rename, a delete and a way off the list.
    val muted = doc.missing
    val nameColour = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !muted, onClick = onOpen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (muted) stringResource(R.string.home_row_missing, doc.name) else doc.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = nameColour,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // At most one row in either list ever carries this, which is what switching modes emptying
            // both sides buys: the badge is the whole answer to "what does play run".
            if (open) {
                Text(
                    stringResource(R.string.home_loaded),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onArrange != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.flow_action_arrange)) },
                        enabled = !muted,
                        onClick = { menuOpen = false; onArrange() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_action_rename)) },
                    enabled = !muted,
                    onClick = { menuOpen = false; renaming = true },
                )
                HorizontalDivider()
                // Two different acts, and the difference is the whole reason the file model is worth having:
                // one forgets a file, the other destroys it.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.row_forget)) },
                    onClick = { menuOpen = false; Recents.forget(doc.ref) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.row_delete_file)) },
                    onClick = { menuOpen = false; confirmingDelete = true },
                )
            }
        }
    }

    if (renaming) {
        RenameDialog(
            initial = doc.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                if (name.isNotBlank()) {
                    scope.writeFile(context) { Repo.renameFile(doc.ref, doc.kind, name.trim()) != null }
                }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.clip_delete_title, doc.name)) },
            // No count of the flows that use this clip, because there is nothing to count: a flow points at
            // a file, and nothing knows which flows point here. Deleting it leaves those rows showing `!`
            // with a button to point them somewhere else — the same as moving the file in a file manager.
            text = { Text(stringResource(R.string.clip_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.writeFile(context) { Repo.deleteFile(doc.ref) }
                    }
                ) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clip_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // Said because it is genuinely surprising, and it is the one place the model shows through:
                // the name of a clip is the name of its file, so a flow that points at the old name breaks.
                Text(
                    stringResource(R.string.rename_breaks_flows),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

private fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

private fun Context.openAccessibilitySettings() =
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun Context.openOverlaySettings() = startActivity(
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
