package com.tapflow.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.tapflow.android.data.FolderStore

/**
 * Navigation rows for one folder: a way back up, then each subfolder.
 *
 * A [LazyListScope] extension rather than a composable that owns a list, so the rows sit in the caller's
 * list above its own items. Anything else would mean two scrolling areas in one dialog, and the folders and
 * the things in them are one list to read.
 *
 * Where the *items* come from is not this function's business. Clips and flows are already in memory, whole,
 * because a flow resolves its clips by id from wherever they are — so the caller filters what it already has
 * by [Repo.folderOf] rather than reading the folder again. Reading again would also let the two disagree.
 *
 * @param current folder being shown, relative to the chosen one. "" is the root.
 * @param subfolders names, relative to the chosen folder, as [FolderStore.folders] returns them.
 */
fun LazyListScope.folderRows(
    current: String,
    subfolders: List<String>,
    onOpen: (String) -> Unit,
) {
    if (current.isNotEmpty()) {
        item {
            FolderRow(
                label = stringResource(R.string.folder_up),
                // One segment off the end. Not a stack of visited folders: the path *is* the history, so
                // there is nothing to get out of step with when a folder is entered twice by two routes.
                onClick = { onOpen(current.substringBeforeLast('/', "")) },
            )
        }
    }
    items(subfolders) { path ->
        FolderRow(label = path.substringAfterLast('/'), onClick = { onOpen(path) })
    }
}

@Composable
private fun FolderRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🗀", modifier = Modifier.padding(end = 12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The chosen folder's own name for the root, so a breadcrumb never reads as blank. */
@Composable
fun folderLabel(current: String): String =
    if (current.isEmpty()) FolderStore.displayName() ?: stringResource(R.string.folder_root)
    else current

/**
 * Picks a folder to save into. Folders only — nothing here can be opened, only entered.
 *
 * Separate from the load dialog rather than the same browser with the files hidden, because the two answer
 * different questions: loading asks "which one", and this asks "which place", so the confirm button belongs
 * to the folder you are standing in rather than to a row.
 */
@Composable
fun FolderPickDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var here by remember { mutableStateOf(initial) }
    val subfolders = remember(here) { FolderStore.folders(here) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_pick_where)) },
        text = {
            Column {
                Text(
                    folderLabel(here),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    folderRows(here, subfolders) { here = it }
                    if (subfolders.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.folder_no_subfolders),
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
            TextButton(onClick = { onPick(here); onDismiss() }) {
                Text(stringResource(R.string.folder_save_here))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
