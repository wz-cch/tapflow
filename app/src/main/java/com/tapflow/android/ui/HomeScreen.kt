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
import com.tapflow.android.data.Clip
import com.tapflow.android.data.Flow
import com.tapflow.android.data.Repo
import com.tapflow.android.text.flowSummary
import com.tapflow.android.engine.EngineState
import com.tapflow.android.engine.Workspace
import com.tapflow.android.text.clipSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenFlow: (String) -> Unit,
) {
    val context = LocalContext.current
    val service = rememberServiceState()
    val status = service.status
    val overlayEnabled by Repo.overlayEnabled.collectAsStateWithLifecycle()
    val needsOverlayPermission by EngineState.needsOverlayPermission.collectAsStateWithLifecycle()
    val clips by Repo.clips.collectAsStateWithLifecycle()
    val flows by Repo.flows.collectAsStateWithLifecycle()
    val currentFlowId by Repo.currentFlowId.collectAsStateWithLifecycle()
    var creatingFlow by remember { mutableStateOf(false) }

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
                    onCheckedChange = { Repo.setOverlayEnabled(it) },
                )
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
                    ClipRow(clip)
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
                        loaded = flow.id == currentFlowId,
                        onOpen = { onOpenFlow(flow.id) },
                    )
                }
            }

            // The only way to make the *first* one. The toolbar can create flows too, but only in flow
            // mode, and flow mode needs a flow to be loaded — so without this there was no way in at all.
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
            Repo.upsertFlow(flow)
            creatingFlow = false
            // Straight into the editor: a flow with no clips in it does nothing, so naming it is only
            // half of what you came to do.
            onOpenFlow(flow.id)
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

@Composable
private fun ClipRow(clip: Clip) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Card {
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

            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_action_load)) },
                    onClick = {
                        menuOpen = false
                        // Unload any flow: exactly one of the two is ever loaded, so play has one
                        // meaning. Free in this direction, since a flow is already saved.
                        Repo.setCurrentFlow(null)
                        Workspace.load(clip)
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_loaded, clip.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
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

    if (renaming) {
        RenameDialog(
            initial = clip.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                if (name.isNotBlank()) {
                    Repo.upsertClip(clip.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
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

/**
 * One flow in the home list.
 *
 * Tapping it opens the editor. There is no play button here: playing is the toolbar's job, in the target
 * app, which is where you actually want to be when a flow runs. The badge says whether this is the flow the
 * toolbar will play — with a flow and the workspace being mutually exclusive, that is the whole answer to
 * "what happens when I press play".
 */
/**
 * Names and creates a flow.
 *
 * Deliberately does **not** load it, unlike the toolbar's version. Creating a flow from the app should not
 * clear the workspace out from under whatever you were recording; the toolbar's create happens when a flow
 * is already loaded, so there is nothing left to protect there. Opening the editor straight afterwards is
 * what you wanted anyway — a flow with no clips in it does nothing.
 */
@Composable
private fun NewFlowDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
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
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.flow_new_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun FlowRow(
    flow: Flow,
    clips: List<Clip>,
    loaded: Boolean,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingLoad by remember { mutableStateOf(false) }

    fun loadNow() {
        Workspace.clear()
        Repo.setCurrentFlow(flow.id)
        Toast.makeText(
            context,
            context.getString(R.string.toast_flow_loaded, flow.name),
            Toast.LENGTH_SHORT,
        ).show()
    }

    Card(Modifier.fillMaxWidth().clickable { onOpen() }) {
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
                    stringResource(R.string.home_flow_loaded),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }

            // The same three a clip row offers, which is what "flows work like clips" has to mean if it
            // means anything. Load was missing here, and it is the only way into flow mode from the app —
            // tapping the row opens the editor, which is a different thing entirely.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.flow_action_load)) },
                    onClick = {
                        menuOpen = false
                        // Loading a flow clears the workspace, so unsaved work gets a question first.
                        if (Workspace.dirty.value) confirmingLoad = true else loadNow()
                    },
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
        AlertDialog(
            onDismissRequest = { confirmingLoad = false },
            title = { Text(stringResource(R.string.flow_action_load)) },
            text = { Text(stringResource(R.string.flow_unsaved_warning, Workspace.size)) },
            confirmButton = {
                TextButton(onClick = { confirmingLoad = false; loadNow() }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLoad = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    if (renaming) {
        RenameDialog(
            initial = flow.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                if (name.isNotBlank()) {
                    Repo.upsertFlow(
                        flow.copy(name = name.trim(), updatedAt = System.currentTimeMillis())
                    )
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
