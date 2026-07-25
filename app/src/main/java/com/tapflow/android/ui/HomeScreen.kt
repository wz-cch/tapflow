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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapflow.android.R
import com.tapflow.android.data.Clip
import com.tapflow.android.data.Repo
import com.tapflow.android.engine.EngineState
import com.tapflow.android.engine.Workspace
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val serviceRunning by EngineState.serviceRunning.collectAsStateWithLifecycle()
    val overlayEnabled by Repo.overlayEnabled.collectAsStateWithLifecycle()
    val needsOverlayPermission by EngineState.needsOverlayPermission.collectAsStateWithLifecycle()
    val clips by Repo.clips.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
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
                AccessibilityCard(serviceRunning) {
                    context.openAccessibilitySettings()
                }
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
                    enabled = serviceRunning,
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

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AccessibilityCard(running: Boolean, onOpen: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(
                    if (running) R.string.onboarding_service_ready
                    else R.string.onboarding_accessibility_title
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!running) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.onboarding_accessibility_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpen) {
                    Text(stringResource(R.string.onboarding_accessibility_action))
                }
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
                    clipSummary(context, clip),
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
            text = { Text(stringResource(R.string.clip_delete_body)) },
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

private fun clipSummary(context: Context, clip: Clip): String {
    val duration = formatDuration(clip.estimatedDurationMs)
    return if (clip.pauseCount > 0) {
        context.getString(R.string.clip_summary_with_pauses, clip.stepCount, clip.pauseCount, duration)
    } else {
        context.getString(R.string.clip_summary, clip.stepCount, duration)
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun Context.openAccessibilitySettings() =
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun Context.openOverlaySettings() = startActivity(
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
