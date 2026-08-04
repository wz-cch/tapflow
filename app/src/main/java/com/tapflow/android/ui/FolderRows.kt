package com.tapflow.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.tapflow.android.data.FolderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists one folder's subfolders, off the main thread.
 *
 * **Not** `remember { FolderStore.folders(it) }`, which is how this was first written and was wrong. On SAF
 * that call is a ContentProvider round trip, and `DocumentFile.findFile` enumerates a whole directory on the
 * way — so doing it during composition blocks the UI thread for as long as the provider takes, on a folder
 * whose size is the user's business and not ours. Reading it in a `LaunchedEffect` costs one frame showing an
 * empty list and cannot wedge the dialog.
 */
@Composable
fun rememberSubfolders(current: String): List<String> {
    var folders by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(current) {
        folders = withContext(Dispatchers.IO) { FolderStore.folders(current) }
    }
    return folders
}

/**
 * Navigation rows for one folder: a way back up, then each subfolder.
 *
 * A [LazyListScope] extension rather than a composable that owns a list, so the rows sit in the caller's list
 * above its own items. Anything else would mean two scrolling areas in one dialog, and the folders and the
 * things in them are one list to read.
 *
 * Where the *items* come from is not this function's business. Clips and flows are already in memory, whole,
 * because a flow resolves its clips by id from wherever they are — so the caller filters what it already has
 * rather than reading the folder again. Reading again would also let the two disagree.
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
