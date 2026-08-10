package com.tapflow.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.DocStore

/**
 * What the storage panel was left with.
 *
 * Three outcomes rather than a nullable ref, because the panel can now be left in two different useful ways
 * — with a file to read, or with a place to write — and a caller that treats those the same is a bug the
 * compiler should be able to find.
 */
sealed interface Picked {
    /** Read this one. */
    data class Open(val ref: String) : Picked

    /** Write to this one, which may or may not exist yet. */
    data class Save(val ref: String) : Picked

    /**
     * Nothing was chosen.
     *
     * A result rather than silence, because two of the callers are activities that exist only to ask: with
     * nothing reported they would sit on screen showing an empty dialog behind a panel that has already gone.
     */
    data object Cancelled : Picked
}

/** Opens the storage panel, in whichever of its shapes the caller has a use for. */
class FilePicker internal constructor(private val start: (String?, Boolean, String?) -> Unit) {

    /**
     * Existing files only.
     *
     * @param near a ref to open the panel beside. Used when repointing a flow at a clip that moved, where the
     *   folder holding the flow is overwhelmingly the right place to start looking.
     */
    fun open(near: String? = null) = start(null, true, near)

    /**
     * Naming something new only. Tapping an existing file fills the name in rather than opening it.
     *
     * **[suggestedName] carries no extension.** The extension says what the file is, which makes it the app's
     * to add and not the user's to type — put in front of them it is something to edit or delete, decoration
     * made out of the one part of the name everything else depends on.
     */
    fun save(suggestedName: String) = start(suggestedName, false, null)

    /** Both: the panel the toolbar opens, where "save this" and "open that" are one screen. */
    fun browse(suggestedName: String, near: String? = null) = start(suggestedName, true, near)
}

/**
 * The one way this app gets at a file, in both directions and on both kinds of Android.
 *
 * ### Everything happens inside the chosen folder
 *
 * Clips and flows live under one folder the user picked once, and a ref is a path inside it. So this asks two
 * questions, not one: **where is the folder** (only ever asked when there is no answer yet) and **which file
 * in it**. The first is the platform's tree picker on API 29+ and [FolderChooserDialog] below that; the second
 * is always [StorageDialog], which is what makes the two versions of Android show the same list.
 *
 * The tree picker is the only piece of the platform's file UI still in use. The single-document picker is
 * gone, and with it the check that used to be needed on the way back: it cannot filter to `.clip` — it filters
 * by MIME type and `.clip` has none — so every file was offered and the wrong kind had to be refused after
 * being chosen. Our own list only ever contains the right kind, so there is nothing to refuse.
 *
 * @param kind what may be picked, what the panel lists, and what a written file is named with.
 * @param onResult see [Picked]. Backing out of choosing a folder reports [Picked.Cancelled] too — nothing can
 *   be picked without one, so from here the two are the same outcome.
 */
@Composable
fun rememberFilePicker(kind: DocKind, onResult: (Picked) -> Unit): FilePicker {
    var browsing by remember { mutableStateOf(false) }
    var startIn by remember { mutableStateOf("") }
    var suggested by remember { mutableStateOf<String?>(null) }
    var canOpen by remember { mutableStateOf(true) }

    val chooseRoot = rememberRootPicker { chosen ->
        if (chosen) browsing = true else onResult(Picked.Cancelled)
    }

    if (browsing) {
        StorageDialog(
            kind = kind,
            startIn = startIn,
            suggestedName = suggested,
            canOpen = canOpen,
            onDismiss = { browsing = false; onResult(Picked.Cancelled) },
            onOpen = { ref -> browsing = false; onResult(Picked.Open(ref)) },
            onSave = { ref -> browsing = false; onResult(Picked.Save(ref)) },
        )
    }

    // Not remembered. It holds one lambda over state that is itself remembered, so a fresh instance per
    // recomposition costs an object and removes the question of whether a captured one has gone stale.
    return FilePicker { suggestedName, opening, near ->
        suggested = suggestedName
        canOpen = opening
        startIn = near?.let(DocStore::parentOf).orEmpty()
        if (DocStore.hasRoot) browsing = true else chooseRoot()
    }
}

/**
 * Asks where the folder is, and remembers the answer. Returns the lambda that starts asking.
 *
 * Asked at most once in the life of an install — everything after it is a path inside the answer — and then
 * only again if the user deliberately changes it. Which is why it is not a first-run wizard: the question
 * arrives the first time it is actually needed, attached to the thing that needed it.
 *
 * @param onResult true when a folder was chosen. False covers both backing out and refusing the permission,
 *   because from the caller's side they are the same outcome.
 */
@Composable
fun rememberRootPicker(onResult: (Boolean) -> Unit): () -> Unit {
    var browsingFolders by remember { mutableStateOf(false) }

    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            onResult(false)
        } else {
            DocStore.setRoot(uri.toString())
            onResult(true)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) browsingFolders = true else onResult(false)
    }

    if (browsingFolders) {
        FolderChooserDialog(onDismiss = { browsingFolders = false; onResult(false) }) { path ->
            browsingFolders = false
            DocStore.setRoot(path)
            onResult(true)
        }
    }

    return {
        when {
            DocStore.usesTreePicker -> tree.launch(null)
            DocStore.needsLegacyPermission -> permission.launch(DocStore.legacyPermission)
            else -> browsingFolders = true
        }
    }
}
