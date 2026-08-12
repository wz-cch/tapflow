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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.DocStore
import com.tapflow.android.data.RecentDoc
import com.tapflow.android.data.Recents
import com.tapflow.android.data.Repo
import com.tapflow.android.data.suggestedFileName
import com.tapflow.android.text.clipSummary
import com.tapflow.android.text.flowSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The storage panel: one small screen that saves, opens and deletes.
 *
 * **One panel rather than a "save" one and an "open" one**, because splitting them made the user answer a
 * question before it was asked — which of the two am I about to do — and then offered the same list twice
 * with a different button under it. Naming a file and choosing a file are the same act on the same folder.
 *
 * **The app's only file UI, on every version of Android.** It replaced two platform pickers that disagreed
 * with each other: on API 29+ the document picker cannot filter to `.clip` — it filters by MIME type and
 * `.clip` has none — so it showed every file and the wrong kind had to be refused after the fact, while the
 * Android 7 device this has to work on could not show a storage root at all. Listing the folder ourselves
 * answers both, and makes "only clips" and "only flows" simply true.
 *
 * Deliberately not a file manager. It walks into folders, picks a file, writes one and deletes one; creating
 * folders, renaming and moving belong to the file manager the user already has, which does all of it better.
 *
 * @param startIn the folder to open in, relative to the root.
 * @param suggestedName non-null when a file may be written from here, which is what puts the name row on
 *   screen. It carries no extension — the extension says what the file is, so it is this dialog's to add and
 *   never the user's to type.
 * @param canOpen whether tapping a file opens it. False where the panel exists only to name something new,
 *   and then tapping a file fills the name in instead — which is how you overwrite deliberately rather than
 *   by a single tap.
 * @param onChooseFolder offered when the chosen folder cannot be reached. A folder the user picked can be
 *   deleted, renamed or live on a card that is not mounted, and an empty list is the wrong way to say so —
 *   "nothing here" and "here is gone" ask for completely different things next.
 */
@Composable
fun StorageDialog(
    kind: DocKind,
    startIn: String,
    suggestedName: String?,
    canOpen: Boolean,
    onDismiss: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpen: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    var dir by remember { mutableStateOf(startIn) }
    var listing by remember { mutableStateOf<List<DocStore.Entry>>(emptyList()) }
    var name by remember { mutableStateOf(suggestedName.orEmpty()) }
    var overwriting by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<DocStore.Entry?>(null) }
    // Bumped by anything that changes what is in the folder, which is only ever a delete: saving hands the
    // ref back and this panel goes away with it.
    var revision by remember { mutableIntStateOf(0) }
    // Null until the first listing has been done. Asked on the same trip as the listing, because it is only
    // worth asking when the listing came back empty and both answers cost a provider round trip.
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    val canSave = suggestedName != null
    val recent by Recents.docs.collectAsStateWithLifecycle()

    // Off the main thread. Listing a folder on a slow card is not instant, and this is often the first thing
    // on screen after a tap.
    LaunchedEffect(dir, revision) {
        val outcome = withContext(Dispatchers.IO) {
            val entries = DocStore.list(dir, kind)
            when {
                // A folder with things in it is reachable by definition, and each of the checks below is a
                // provider query of its own — so they are only asked when there is nothing to show.
                entries.isNotEmpty() -> Listing(entries, reachable = true, walkTo = dir)
                // Remembered from last time, or navigated into, and gone since. Fall back to the root rather
                // than stand in a path that lists nothing and cannot say why.
                dir.isNotEmpty() && !DocStore.folderExists(dir) -> Listing(walkTo = "")
                else -> Listing(entries, reachable = DocStore.rootReadable(), walkTo = dir)
            }
        }
        if (outcome.walkTo != dir) {
            dir = outcome.walkTo
            return@LaunchedEffect
        }
        listing = outcome.entries
        reachable = outcome.reachable
        // Remembered on arrival rather than on the way out, so backing out of the panel still leaves it
        // where you were looking — which is the whole of what "remember the last place" means.
        DocStore.rememberFolder(kind, dir)
    }

    fun save() {
        val target = DocStore.join(dir, suggestedFileName(name, kind))
        if (listing.any { !it.isFolder && it.ref == target }) overwriting = target else onSave(target)
    }

    fun tapped(entry: DocStore.Entry) {
        // Tapping a file while the panel cannot open one fills the name in rather than writing over it. One
        // tap must not replace a file, and the name is now on screen to be edited or confirmed.
        if (canOpen) onOpen(entry.ref) else name = entry.name
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // An ✕ in the title as well as the button below, and the duplication is deliberate: a list of
            // things to tap does not look like something you can leave without tapping one. Every picker the
            // user has ever used has a close in its corner.
            //
            // It also survives the case the bottom row does not: with the keyboard up, that row can be pushed
            // past the bottom of the screen. The title never is.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        if (kind == DocKind.CLIP) R.string.storage_title_clip else R.string.storage_title_flow
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dialog_cancel))
                }
            }
        },
        text = {
            Column {
                if (canSave) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            // "Name", not "file name". The extension is not the user's to type, so asking for
                            // a *file* name invites typing one — and it would then be appended twice.
                            label = { Text(stringResource(R.string.browse_name_label)) },
                            modifier = Modifier.weight(1f),
                        )
                        // Only when the panel also opens things. Then the list is the other half and the
                        // bottom button can only mean "leave", so writing needs its own control up here.
                        // When naming is the *whole* errand it moves to the bottom instead — see below.
                        if (canOpen) {
                            IconButton(onClick = ::save, enabled = name.isNotBlank()) {
                                Text("💾", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    // The root's own name in front, so the line reads as a place rather than as a fragment.
                    // It is also where a save lands, which is the other reason it is above the list.
                    DocStore.join(DocStore.rootLabel, dir),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // The end of a path says where you are; the beginning is the same for every folder.
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (dir.isNotEmpty()) {
                        item {
                            FolderRow(stringResource(R.string.browse_up)) { dir = DocStore.parentOf(dir) }
                        }
                    }
                    items(listing, key = { it.ref }) { entry ->
                        if (entry.isFolder) {
                            FolderRow(entry.name) { dir = entry.ref }
                        } else {
                            FileRow(
                                name = entry.name,
                                // Only what is already known. The counts are cached by opening or saving a
                                // file, so a folder full of clips this install has never touched draws with
                                // no IO at all rather than parsing every one of them to fill in a subtitle.
                                summary = recent.firstOrNull { it.ref == entry.ref }
                                    ?.let { summaryOf(context, it) },
                                onClick = { tapped(entry) },
                                onDelete = { deleting = entry },
                            )
                        }
                    }
                    if (listing.isEmpty()) {
                        item {
                            if (reachable == false) {
                                Text(
                                    stringResource(R.string.storage_root_gone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                                OutlinedButton(onClick = onChooseFolder) {
                                    Text(stringResource(R.string.root_change))
                                }
                            } else {
                                // Names the folder rather than saying "nothing here", because those are
                                // two different facts and only one of them is true: this list is a folder,
                                // and a folder with no clips in it says nothing about whether there are
                                // clips. Read as "you have none", it sends someone off to record a second
                                // copy of something they already own.
                                Text(
                                    stringResource(
                                        if (kind == DocKind.CLIP) {
                                            R.string.storage_empty_clip
                                        } else {
                                            R.string.storage_empty_flow
                                        },
                                        DocStore.join(DocStore.rootLabel, dir),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        // **Which button sits here depends on whether this panel has an action of its own.**
        //
        // When it opens as well as saves, both of those are done in the body — a tap on a row, the 💾 beside
        // the name — so the only thing left down here is leaving, and it says so.
        //
        // When naming is the whole errand there is no tap that finishes it, and a bottom button reading
        // "Done" is then a trap: it is the largest, most final-looking control on screen and it *discards*.
        // Reported as "I pressed save and the flow was still untitled". So it becomes Save, with Cancel
        // beside it.
        confirmButton = {
            if (canOpen) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_done)) }
            } else {
                TextButton(onClick = ::save, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.flow_action_save))
                }
            }
        },
        dismissButton = {
            if (!canOpen) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            }
        },
    )

    overwriting?.let { target ->
        AlertDialog(
            onDismissRequest = { overwriting = null },
            title = { Text(stringResource(R.string.browse_overwrite_title, DocStore.label(target))) },
            confirmButton = {
                TextButton(onClick = { overwriting = null; onSave(target) }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { overwriting = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.storage_delete_title, entry.name)) },
            text = { Text(stringResource(R.string.storage_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    // Straight through rather than reported back: deleting is the one action here that does
                    // not end the panel, because it is maintenance rather than the thing you came to do.
                    if (Repo.deleteFile(entry.ref)) revision++
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

/** One listing, and what it implies about where the panel should be standing. */
private class Listing(
    val entries: List<DocStore.Entry> = emptyList(),
    val reachable: Boolean = true,
    val walkTo: String,
)

/** What a row already knows about itself, in the wording of the kind it is. */
private fun summaryOf(context: android.content.Context, doc: RecentDoc): String =
    if (doc.kind == DocKind.CLIP) {
        clipSummary(context.resources, doc.stepCount, doc.pauseCount, doc.durationMs)
    } else {
        flowSummary(context.resources, doc.clipCount, doc.durationMs)
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

/** A folder, in either dialog. Tapping it walks in; there is nothing else a folder does here. */
@Composable
private fun FolderRow(label: String, onClick: () -> Unit) = BrowseRow("\uD83D\uDDC0", label, onClick)

/**
 * A file, with the one piece of maintenance that belongs next to it.
 *
 * The delete is on the row rather than behind a menu because there are only two things to do with a file
 * here and hiding one of them behind a second tap buys nothing. It is the only action in this panel that
 * leaves the panel open.
 */
@Composable
private fun FileRow(
    name: String,
    summary: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("\u00B7", modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Text("\uD83D\uDDD1", style = MaterialTheme.typography.titleMedium)
        }
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
