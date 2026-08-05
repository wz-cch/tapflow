package com.tapflow.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.DocStore
import com.tapflow.android.text.wrongKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Something that asks the user for a file, and reports the ref or that they backed out.
 *
 * Cancellation is a result rather than silence, because two of the four callers are activities that exist
 * only to ask: with nothing reported they would sit on screen showing an empty dialog behind a picker that
 * has already gone.
 */
class FilePicker internal constructor(private val start: (String?, String?) -> Unit) {

    /**
     * Asks for an existing file.
     *
     * @param near a ref to open the picker beside. Used when repointing a flow at a clip that moved, where
     *   the folder holding the flow is overwhelmingly the right place to start looking.
     */
    fun open(near: String? = null) = start(null, near)

    /**
     * Asks where to write a new file, with [suggestedName] filled in.
     *
     * **A bare name, without the extension.** The picker has one name field and no notion of an extension, so
     * a suggested `Login.clip` would put `.clip` in front of the user as something to edit or delete —
     * decoration made out of the one part of the name the app depends on. It is added afterwards, by
     * `DocStore.ensureExtension`.
     */
    fun create(suggestedName: String) = start(suggestedName, null)
}

/**
 * The one way this app gets at a file, in both directions and on both kinds of Android.
 *
 * There are exactly four places that need it — open, save-as, adding a clip to a flow, and repointing a
 * broken row — and every one of them goes through here. That is the whole storage UI: no library folder to
 * configure, no folder to browse inside the app on modern releases, and nothing to keep in step with the
 * disk. The user's own picker is the file manager.
 *
 * Two implementations behind one handle:
 *
 * - **API 29+** — the system document picker (`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`). Scoped
 *   storage leaves no alternative, and it is also the better UI: it has search, other providers, and the
 *   user's own idea of where things live.
 * - **API 28 and below** — [FileBrowserDialog], because the system picker on the Android 7 device this must
 *   work on lists no storage roots at all. Needs `WRITE_EXTERNAL_STORAGE` first, which is asked for here so
 *   that no caller has to know the difference.
 *
 * ### The extension decides, and this is the one place that enforces it
 *
 * The *list* cannot be narrowed on API 29+: the picker filters by MIME type, and `.clip` and `.flow` have no
 * registered type — both come back as `application/octet-stream`, and filtering on that would hide `.txt` and
 * `.json` as well. So every file shows up, and **what comes back is checked here, by extension**: pick a
 * `.flow` while opening a clip and it is refused, with a message that says which it was.
 *
 * That makes the extension mean exactly one thing — `.clip` is a clip — at the cost of a clip renamed to
 * `.txt` being unopenable until it is renamed back. Which is the right trade for two reasons. The rule is
 * predictable, and it is *already* what happens on API 28 and below, where [FileBrowserDialog] lists only
 * matching files: judging by content instead would have left the two Android versions disagreeing about which
 * files exist.
 *
 * The check is on picking, not on resolving. A flow whose clip file gets renamed to `.txt` keeps working,
 * because that reference was already checked when it was added.
 *
 * @param kind what may be picked, what the legacy browser lists, and what a created file is named with.
 * @param onResult the ref that was chosen, or null when the user backed out **or picked the wrong kind** —
 *   they have already been told which, so a caller only has to handle "nothing was chosen".
 */
@Composable
fun rememberFilePicker(kind: DocKind, onResult: (String?) -> Unit): FilePicker {
    // Held as state objects rather than captured values so the remembered contract below can read the
    // current one. Re-creating the contract per recomposition would re-register the launcher each time.
    val near = remember { mutableStateOf<String?>(null) }
    val suggested = remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * Reports a picked file, refusing it when its name says it is not [kind].
     *
     * Asking for the name is IO on a document Uri, which is why this is a coroutine rather than a plain check.
     * Refusing says why itself: the caller sees the same null it sees for a cancellation, and a silent refusal
     * would look exactly like a picker that failed to return.
     */
    fun report(ref: String?) {
        if (ref == null) {
            onResult(null)
            return
        }
        scope.launch {
            val actual = withContext(Dispatchers.IO) { DocStore.fileName(ref)?.let(DocKind::of) }
            if (actual == kind) {
                onResult(ref)
            } else {
                Toast.makeText(context, wrongKind(context.resources, kind, actual), Toast.LENGTH_LONG).show()
                onResult(null)
            }
        }
    }

    val openContract = remember { OpenDocumentNear(near) }
    val opener = rememberLauncherForActivityResult(openContract) { uri ->
        report(uri?.let { DocStore.persistAccess(it); it.toString() })
    }
    // Not checked on the way out: a file *created* through the picker is named by us afterwards — see
    // DocStore.ensureExtension — so its extension is not the user's to get wrong.
    val creator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocStore.MIME)) { uri ->
        onResult(uri?.let { DocStore.persistAccess(it); it.toString() })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A refusal is a cancellation, not an error state to sit in: nothing can be picked without it, and
        // the caller already knows what to do with "no file was chosen".
        if (granted) browsing = true else onResult(null)
    }

    if (browsing) {
        FileBrowserDialog(
            kind = kind,
            suggestedName = suggested.value,
            onDismiss = { browsing = false; onResult(null) },
        ) { ref ->
            browsing = false
            // Reported the same way, though this browser only ever lists matching files: one rule, one place.
            if (suggested.value != null) onResult(ref) else report(ref)
        }
    }

    return remember {
        FilePicker { suggestedName, openNear ->
            suggested.value = suggestedName
            near.value = openNear
            when {
                !DocStore.usesSystemPicker && DocStore.needsLegacyPermission ->
                    permission.launch(DocStore.legacyPermission)

                !DocStore.usesSystemPicker -> browsing = true
                suggestedName != null -> creator.launch(suggestedName)
                else -> opener.launch(arrayOf("*/*"))
            }
        }
    }
}

/**
 * `ACTION_OPEN_DOCUMENT`, optionally starting where a related file lives.
 *
 * A subclass because the contract takes no extras, and `EXTRA_INITIAL_URI` is worth the subclass: it is what
 * makes repointing a flow's broken row land in the folder that holds the flow, instead of wherever the picker
 * was last. Reads the location through a state object so the contract itself can be remembered once.
 */
private class OpenDocumentNear(
    private val near: MutableState<String?>,
) : ActivityResultContracts.OpenDocument() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DocStore.initialLocation(near.value)?.let { at ->
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, at)
                }
            }
        }
}
