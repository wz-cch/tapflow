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
import com.tapflow.android.data.Clip
import com.tapflow.android.data.FolderStore
import com.tapflow.android.data.Flow
import com.tapflow.android.data.Repo
import com.tapflow.android.text.flowSummary
import com.tapflow.android.engine.EngineState
import com.tapflow.android.engine.Session
import com.tapflow.android.text.clipSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The launch screen.
 *
 * Not a mode selector, and not the only way to load something. Its job is to get you into a mode with
 * something in it and then get out of the way — because everything you actually *do* with a clip happens
 * on top of another app, where the toolbar is. So loading here closes this screen.
 *
 * Loading lives on the row itself rather than in the `⋮` menu. It used to be the other way round, which
 * left tapping a clip row doing nothing at all while tapping a flow row opened an editor: the main action
 * buried in a menu, and the most direct gesture meaning two different things. The menu keeps what is left,
 * which is maintenance.
 *
 * "Start without loading" exists because neither list answers the commonest opening move. Making the lists
 * the way into a mode leaves a hole exactly where a first-time user stands — there is no row to tap when
 * what you want is to record something new.
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
    val service = rememberServiceState()
    val status = service.status
    val overlayEnabled by Repo.overlayEnabled.collectAsStateWithLifecycle()
    val needsOverlayPermission by EngineState.needsOverlayPermission.collectAsStateWithLifecycle()
    val clips by Repo.clips.collectAsStateWithLifecycle()
    val flows by Repo.flows.collectAsStateWithLifecycle()
    val currentFlowId by Repo.currentFlowId.collectAsStateWithLifecycle()
    val currentClipId by Repo.currentClipId.collectAsStateWithLifecycle()
    val mode by Repo.mode.collectAsStateWithLifecycle()
    val folderUsable by FolderStore.usable.collectAsStateWithLifecycle()
    val unreadable by Repo.unreadable.collectAsStateWithLifecycle()
    var creatingFlow by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var closingToolbar by remember { mutableStateOf(false) }

    // The library is read here rather than at startup, because reading it means walking a folder over
    // IPC — on whichever thread happens to start the app or bind the service, for a list that
    // recording, editing and replaying never look at. This screen is the first thing that shows it.
    LaunchedEffect(folderUsable) {
        if (FolderStore.isConfigured && !Repo.libraryLoaded.value) {
            withContext(Dispatchers.IO) { Repo.loadLibrary() }
        }
    }

    /**
     * Hands over to the toolbar.
     *
     * Turning the overlay on is part of loading, not a separate step the user has to remember: loading
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
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
                // Which mode a load would land you in, and which one is already in force. Read-only:
                // switching lives on the toolbar alone, for the same reason loading lives in one place —
                // one act, one entry point. Loading a row below is what changes it from here.
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
                OutlinedButton(onClick = { guardDiscard(::startFresh) { pendingStart = true } }) {
                    Text(stringResource(R.string.home_start_fresh))
                }
            }

            // Said once, above both lists, because it explains both of them being empty. No picker
            // button here on purpose: the folder gets chosen where it is needed — on the first save or
            // load — and changed in the settings. A third entry point would just be a third place to
            // keep consistent.
            if (!FolderStore.isConfigured) {
                item {
                    Text(
                        stringResource(R.string.home_no_folder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // In the error colour and above both lists, because it explains a gap *in* them. Without it a
            // damaged file and a clip that was never saved look identical from here — and they call for
            // opposite responses, since the file is still in the folder.
            if (unreadable > 0) {
                item {
                    Text(
                        stringResource(R.string.library_unreadable, unreadable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                Text(
                    stringResource(R.string.home_tab_clips),
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
                items(clips, key = { it.id }) { clip ->
                    ClipRow(
                        clip = clip,
                        loaded = mode == AppMode.CLIP && clip.id == currentClipId,
                        onLoad = {
                            Session.loadClip(clip)
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_loaded, clip.name),
                                Toast.LENGTH_SHORT,
                            ).show()
                            handOver()
                        },
                    )
                }
            }

            item {
                Text(
                    stringResource(R.string.home_tab_flows),
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
                items(flows, key = { it.id }) { flow ->
                    FlowRow(
                        flow = flow,
                        clips = clips,
                        loaded = mode == AppMode.FLOW && flow.id == currentFlowId,
                        onLoad = {
                            Session.loadFlow(flow)
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_flow_loaded, flow.name),
                                Toast.LENGTH_SHORT,
                            ).show()
                            handOver()
                        },
                        onArrange = { onOpenFlow(flow.id, false) },
                    )
                }
            }

            // Flow mode's own "start without loading": a flow is made by arranging clips, which needs a
            // list on a real screen, so unlike a clip it cannot begin on the toolbar.
            item {
                OutlinedButton(onClick = { creatingFlow = true }) {
                    Text(stringResource(R.string.flow_new_title))
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                // Shown so a bug report identifies its build. Every CI APK used to claim 0.1.0.
                Text(
                    stringResource(R.string.home_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenDiagnostics) {
                    Text(stringResource(R.string.diag_title))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (creatingFlow) {
        NewFlowDialog(onDismiss = { creatingFlow = false }) { name ->
            val flow = Flow(
                name = name.trim(),
                clips = emptyList(),
                createdAt = System.currentTimeMillis(),
            )
            // Nothing after this if the write did not land. Loading and opening the editor for a flow
            // that was never written would show an empty screen for a flow that does not exist, and the
            // first edit would fail for the same reason the create did.
            if (!context.wroteToLibrary { Repo.upsertFlow(flow) }) return@NewFlowDialog
            creatingFlow = false
            // Loaded as well as created, the same as picking an existing row: "new flow" is flow mode's
            // way in, so it would be odd for it to leave you in clip mode. Then straight into the editor,
            // because a flow with no clips in it does nothing — naming it is half of what you came to do.
            Session.loadFlow(flow)
            onOpenFlow(flow.id, true)
        }
    }

    if (pendingStart) {
        DiscardConfirmDialog(onDismiss = { pendingStart = false }) {
            pendingStart = false
            startFresh()
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
 * One saved clip. Tapping it loads it and hands over to the toolbar.
 *
 * The `⋮` keeps only what is left after load moved onto the row: rename and delete, which are library
 * maintenance and need a keyboard or a confirmation. There is nothing else a clip row could offer, because
 * everything you do *to* a clip — record over it, edit its steps, play it — happens on the target app.
 */
@Composable
private fun ClipRow(clip: Clip, loaded: Boolean, onLoad: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingLoad by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().clickable { guardDiscard(onLoad) { confirmingLoad = true } }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    clip.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    clipSummary(context.resources, clip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // At most one row in either list ever carries this, which is what switching modes emptying
            // both sides buys: the badge is the whole answer to "what does play run".
            if (loaded) {
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_action_rename)) },
                    onClick = { menuOpen = false; renaming = true },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_action_delete)) },
                    onClick = { menuOpen = false; confirmingDelete = true },
                )
            }
        }
    }

    if (confirmingLoad) {
        DiscardConfirmDialog(onDismiss = { confirmingLoad = false }) {
            confirmingLoad = false
            onLoad()
        }
    }

    if (renaming) {
        RenameDialog(
            initial = clip.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                if (name.isNotBlank()) {
                    context.wroteToLibrary {
                        Repo.upsertClip(clip.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
                    }
                }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.clip_delete_title, clip.name)) },
            text = {
                // Deleting a clip edits every flow that used it, so say how many before it happens
                // rather than letting a flow quietly get shorter.
                val used = Repo.flowsUsing(clip.id)
                Text(
                    if (used == 0) stringResource(R.string.clip_delete_body)
                    else stringResource(R.string.clip_delete_body_in_flows, used)
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; Repo.deleteClip(clip.id) }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** Names a flow. Creating one loads it, so confirm on the way out if that costs unsaved steps. */
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

/**
 * One saved flow. Tapping it loads it, exactly as a clip row does.
 *
 * Arranging moved into the `⋮`, which is a demotion of the thing a flow needs most — but tapping the row
 * had to mean the same as tapping a clip row, and there are now two other ways to the editor: this menu
 * without loading, and the toolbar's pencil once it is loaded.
 */
@Composable
private fun FlowRow(
    flow: Flow,
    clips: List<Clip>,
    loaded: Boolean,
    onLoad: () -> Unit,
    onArrange: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingLoad by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().clickable { guardDiscard(onLoad) { confirmingLoad = true } }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    flow.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    flowSummary(context.resources, flow, clips),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loaded) {
                Text(
                    stringResource(R.string.home_loaded),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }

            // Arrange is the one a clip row has no equivalent of, and that asymmetry is real rather than
            // an oversight: a clip's content is captured by gesture on top of another app, a flow's is a
            // list of references arranged on a screen. Rename and delete are the same two either way.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.flow_action_arrange)) },
                    onClick = { menuOpen = false; onArrange() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_action_rename)) },
                    onClick = { menuOpen = false; renaming = true },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_action_delete)) },
                    onClick = { menuOpen = false; confirmingDelete = true },
                )
            }
        }
    }

    if (confirmingLoad) {
        DiscardConfirmDialog(onDismiss = { confirmingLoad = false }) {
            confirmingLoad = false
            onLoad()
        }
    }

    if (renaming) {
        RenameDialog(
            initial = flow.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                if (name.isNotBlank()) {
                    context.wroteToLibrary {
                        Repo.upsertFlow(flow.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
                    }
                }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.flow_delete_title, flow.name)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; Repo.deleteFlow(flow.id) }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
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
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

private fun Context.openAccessibilitySettings() =
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun Context.openOverlaySettings() = startActivity(
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
