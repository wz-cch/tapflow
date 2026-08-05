package com.tapflow.android.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapflow.android.R
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.DocStore
import com.tapflow.android.data.displayName
import com.tapflow.android.data.suggestedFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Browses shared storage on API 28 and below.
 *
 * **Written because the platform's picker cannot be relied on there.** On the Android 7 device this has to
 * work on, `ACTION_OPEN_DOCUMENT` lists only "Recent" — no storage root at all — and no intent extra changes
 * it: that DocumentsUI never exposed `ExternalStorageProvider`'s roots. There is no lever left, so on those
 * releases the app browses the file system itself, which it may do because scoped storage does not exist yet
 * and `WRITE_EXTERNAL_STORAGE` is plain read/write access.
 *
 * Deliberately not a general file manager. It lists folders and the one kind of file being asked for, and it
 * has no create-folder, no rename and no delete — a file manager exists on every device and does all of that
 * better. What it must do is let any folder be reached, since that is the whole promise of the model.
 *
 * @param suggestedName non-null when saving, which is what adds the name field and turns tapping a file into
 *   filling that field in. Null when opening. Carries no extension — this dialog appends it on save, so the
 *   field only ever holds the part the user owns.
 */
@Composable
fun FileBrowserDialog(
    kind: DocKind,
    suggestedName: String?,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val root = remember { DocStore.legacyRoot }
    var dir by remember { mutableStateOf(root) }
    var listing by remember { mutableStateOf<List<File>>(emptyList()) }
    var name by remember { mutableStateOf(suggestedName.orEmpty()) }
    var overwriting by remember { mutableStateOf<File?>(null) }
    val saving = suggestedName != null

    // Off the main thread. Listing a folder on a slow card is not instant, and this dialog is often the
    // first thing on screen after a tap.
    LaunchedEffect(dir) {
        listing = withContext(Dispatchers.IO) { browse(dir, kind) }
    }

    fun pick(file: File) {
        if (!saving) {
            onPicked(file.absolutePath)
            return
        }
        // Tapping an existing file while saving fills the name in rather than saving over it immediately.
        // One tap must not overwrite a file, and the name is now on screen to be edited or confirmed.
        name = displayName(file.name)
    }

    fun save() {
        val target = File(dir, suggestedFileName(name, kind))
        if (target.exists()) overwriting = target else onPicked(target.absolutePath)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (saving) R.string.browse_title_save else R.string.browse_title_open))
        },
        text = {
            Column {
                Text(
                    dir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // The end of a path says where you are; the beginning is the same for every folder.
                    overflow = TextOverflow.Ellipsis,
                )
                if (saving) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.browse_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (dir.absolutePath != root.absolutePath) {
                        item {
                            BrowseRow("🗀", stringResource(R.string.browse_up)) {
                                dir = dir.parentFile ?: root
                            }
                        }
                    }
                    items(listing, key = { it.absolutePath }) { entry ->
                        if (entry.isDirectory) {
                            BrowseRow("🗀", entry.name) { dir = entry }
                        } else {
                            BrowseRow("·", displayName(entry.name)) { pick(entry) }
                        }
                    }
                    if (listing.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.browse_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (saving) {
                TextButton(onClick = ::save, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )

    overwriting?.let { target ->
        AlertDialog(
            onDismissRequest = { overwriting = null },
            title = { Text(stringResource(R.string.browse_overwrite_title, displayName(target.name))) },
            confirmButton = {
                TextButton(onClick = { overwriting = null; onPicked(target.absolutePath) }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { overwriting = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun BrowseRow(glyph: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(glyph, modifier = Modifier.padding(end = 12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One folder's contents: folders first, then the files of the kind being asked for.
 *
 * Hidden entries are skipped. `.thumbnails` and friends are noise in a list whose only job is to get you to
 * your own folder, and nothing this app writes is hidden.
 */
private fun browse(dir: File, kind: DocKind): List<File> {
    val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
    val (dirs, files) = children
        .filterNot { it.name.startsWith(".") }
        .partition { it.isDirectory }
    return dirs.sortedBy { it.name.lowercase() } +
        files.filter { kind.matches(it.name) }.sortedBy { it.name.lowercase() }
}
