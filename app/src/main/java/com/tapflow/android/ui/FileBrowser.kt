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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapflow.android.R
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.DocStore
import com.tapflow.android.data.suggestedFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Browses the chosen folder.
 *
 * **The app's only file UI, on every version of Android.** It replaced two platform pickers that disagreed
 * with each other: on API 29+ the document picker cannot filter to `.clip` — it filters by MIME type and
 * `.clip` has none — so it showed every file and the wrong kind had to be refused after the fact, while the
 * Android 7 device this has to work on could not show a storage root at all. Listing the folder ourselves
 * answers both, and makes "only clips" and "only flows" simply true.
 *
 * Deliberately not a file manager. It walks into folders and picks a file; creating folders, renaming and
 * moving belong to the file manager the user already has, which does all of it better.
 *
 * @param startIn the folder to open in, relative to the root.
 * @param suggestedName non-null when saving, which is what adds the name field and turns tapping a file into
 *   filling that field in. Null when opening. Carries no extension — this dialog appends it on save, so the
 *   field only ever holds the part the user owns.
 */
@Composable
fun FileBrowserDialog(
    kind: DocKind,
    startIn: String,
    suggestedName: String?,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    var dir by remember { mutableStateOf(startIn) }
    var listing by remember { mutableStateOf<List<DocStore.Entry>>(emptyList()) }
    var name by remember { mutableStateOf(suggestedName.orEmpty()) }
    var overwriting by remember { mutableStateOf<String?>(null) }
    val saving = suggestedName != null

    // Off the main thread. Listing a folder on a slow card is not instant, and this dialog is often the
    // first thing on screen after a tap.
    LaunchedEffect(dir) {
        listing = withContext(Dispatchers.IO) { DocStore.list(dir, kind) }
    }

    fun pick(entry: DocStore.Entry) {
        if (!saving) {
            onPicked(entry.ref)
            return
        }
        // Tapping an existing file while saving fills the name in rather than saving over it immediately.
        // One tap must not overwrite a file, and the name is now on screen to be edited or confirmed.
        name = entry.name
    }

    fun save() {
        val target = DocStore.join(dir, suggestedFileName(name, kind))
        if (listing.any { !it.isFolder && it.ref == target }) overwriting = target else onPicked(target)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // An ✕ in the title as well as the cancel below it, and the duplication is deliberate: this dialog
            // is a list of folders and files, and a list of things to tap does not look like something you can
            // leave without tapping one. Every picker the user has ever used has a close in its corner.
            //
            // It also survives the case the bottom button does not: with the keyboard up while saving, the
            // button row can be pushed past the bottom of the screen. The title never is.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (saving) R.string.browse_title_save else R.string.browse_title_open),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dialog_cancel))
                }
            }
        },
        text = {
            Column {
                Text(
                    // The root's own name in front, so the line reads as a place rather than as a fragment.
                    DocStore.join(DocStore.rootLabel, dir),
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
                        // "Name", not "file name". The extension is not the user's to type, so asking for a
                        // *file* name invites typing one — and then it would be appended twice.
                        label = { Text(stringResource(R.string.browse_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (dir.isNotEmpty()) {
                        item {
                            BrowseRow("🗀", stringResource(R.string.browse_up)) {
                                dir = DocStore.parentOf(dir)
                            }
                        }
                    }
                    items(listing, key = { it.ref }) { entry ->
                        if (entry.isFolder) {
                            BrowseRow("🗀", entry.name) { dir = entry.ref }
                        } else {
                            BrowseRow("·", entry.name) { pick(entry) }
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
            title = { Text(stringResource(R.string.browse_overwrite_title, DocStore.label(target))) },
            confirmButton = {
                TextButton(onClick = { overwriting = null; onPicked(target) }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { overwriting = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

/**
 * Chooses the folder itself, on API 28 and below.
 *
 * Its own dialog rather than a mode of the browser above, because it works one level lower: there is no root
 * yet, so it walks the file system directly and answers with an absolute path. On API 29+ the platform's tree
 * picker does this job, and it is the only piece of the platform's file UI still in use.
 */
@Composable
fun FolderChooserDialog(onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val start = remember { DocStore.legacyStart }
    var dir by remember { mutableStateOf(start) }
    var listing by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(dir) {
        listing = withContext(Dispatchers.IO) {
            runCatching { dir.listFiles() }.getOrNull().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.root_choose_title), modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dialog_cancel))
                }
            }
        },
        text = {
            Column {
                Text(
                    dir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (dir.absolutePath != start.absolutePath) {
                        item {
                            BrowseRow("🗀", stringResource(R.string.browse_up)) {
                                dir = dir.parentFile ?: start
                            }
                        }
                    }
                    items(listing, key = { it.absolutePath }) { entry ->
                        BrowseRow("🗀", entry.name) { dir = entry }
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
        // Says what it will do rather than agreeing with a question, because the question — "which folder?" —
        // is answered by where you have navigated to, not by this button.
        confirmButton = {
            TextButton(onClick = { onPicked(dir.absolutePath) }) {
                Text(stringResource(R.string.root_choose_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
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
