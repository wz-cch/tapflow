package com.tapflow.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.documentfile.provider.DocumentFile

/**
 * Saved clips and flows, in a folder the user picked.
 *
 * **The folder holds the only copy.** Nothing here is mirrored internally, because two copies can
 * disagree and nothing can adjudicate between them — the folder would look authoritative while
 * silently being older than what the app shows. One copy has no such state.
 *
 * It is touched at exactly two moments: saving writes one file, and listing reads them. Recording,
 * editing and replaying never come here at all; the working draft, the settings and the window
 * positions stay in internal storage where they always were, and the draft is written on every
 * single recorded step. So the cost of this being slower than a local file is paid only where the
 * user asked for something.
 *
 * ### The file name is decorative
 *
 * A clip's identity is its `id`, which lives *inside* the JSON. The file name is the clip's name,
 * purely so the folder is legible to whoever opens it — which is the point of letting them choose
 * it. Reading ignores the file name entirely and takes the name and id from the content.
 *
 * That one decision removes three problems rather than solving them. Two clips can share a name:
 * the provider appends its own suffix and we keep whatever it gave us. A name can contain path
 * separators: they are stripped, and if nothing survives there is a fallback. A rename can fail
 * halfway: the file keeps its old name and *nothing is wrong*, because the content is what counts.
 */
object FolderStore {

    private const val TAG = "FolderStore"
    private const val KEY_TREE = "library_tree_uri"

    /** Subfolder names. The outer folder is always the user's; these two are ours to create. */
    private const val CLIPS = "clips"
    private const val FLOWS = "flows"

    enum class Kind(val folder: String) { CLIP(CLIPS), FLOW(FLOWS) }

    private lateinit var appContext: Context
    private var treeUri: Uri? = null

    /**
     * Where each saved thing lives, keyed by the id inside it.
     *
     * Needed because the file name cannot be trusted to still match the content, and needed anyway
     * for speed: [DocumentFile.findFile] enumerates the whole directory on every call, so looking a
     * file up by name on each save would turn one write into a directory scan.
     */
    private val located = HashMap<String, Uri>()

    fun init(context: Context, prefsTree: String?) {
        appContext = context.applicationContext
        treeUri = prefsTree?.let(Uri::parse)
    }

    val isConfigured: Boolean get() = treeUri != null

    /**
     * True when the folder is configured *and* still reachable.
     *
     * Both halves are needed and neither implies the other. Uninstalling revokes the grant while
     * leaving the files; deleting the folder or ejecting the card leaves the grant pointing at
     * nothing. Cached rather than probed on every read, because answering it honestly means IO.
     *
     * A flow rather than a plain flag so the screens react. A write that fails clears it, and the
     * screen that was mid-save then has to turn into "pick the folder again" by itself — nobody is
     * going to remember to wire that up at each call site.
     */
    val usable = MutableStateFlow(false)

    private var isUsable: Boolean
        get() = usable.value
        set(value) { usable.value = value }

    /** Re-probes [usable]. Does IO, so call it off the main thread. */
    fun refreshUsable(): Boolean {
        val root = root()
        isUsable = root != null && root.isDirectory
        return isUsable
    }

    /**
     * Remembers a folder the user just picked, taking the permission that survives a reboot.
     *
     * Not an uninstall, though — the grant goes with the app, which is why picking the same folder
     * again is the documented way to get your library back rather than a fallback.
     */
    fun adopt(uri: Uri): Boolean = runCatching {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        treeUri = uri
        located.clear()
        refreshUsable()
    }.onFailure { Log.e(TAG, "Failed to adopt $uri", it) }.getOrDefault(false)

    fun forget() {
        treeUri?.let { uri ->
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        treeUri = null
        located.clear()
        isUsable = false
    }

    /** The stored value, for persisting. Null when nothing has been picked. */
    fun treeUriString(): String? = treeUri?.toString()

    fun prefsKey(): String = KEY_TREE

    /**
     * A human-readable-ish name for the chosen folder.
     *
     * Derived from the tree Uri because SAF gives no path. Fine for internal storage and SD cards,
     * where the last segment is `primary:Documents/TapFlow`; other providers are free to return
     * something opaque, and there is nothing to be done about that — it is what SAF exposes.
     */
    fun displayName(): String? {
        val uri = treeUri ?: return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.lastPathSegment
        return id.substringAfter(':', id)
    }

    // --- Reading -------------------------------------------------------------

    /** One file's contents, with where it came from. */
    class Entry(val json: String, val uri: Uri)

    /**
     * Everything of one kind, skipping whatever will not parse.
     *
     * Tolerant on purpose. These files sit in a folder the user opens, syncs and copies around, so a
     * truncated or hand-mangled one is a normal event rather than a corruption bug — and one bad
     * file must cost that one clip, not the whole list. The skip is logged; nothing is deleted.
     */
    fun list(kind: Kind): List<Entry> {
        val dir = subfolder(kind, create = false) ?: return emptyList()
        return dir.listFiles().mapNotNull { file ->
            if (!file.isFile || file.name?.endsWith(".json") != true) return@mapNotNull null
            val text = read(file.uri) ?: return@mapNotNull null
            Entry(text, file.uri)
        }
    }

    private fun read(uri: Uri): String? = runCatching {
        appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
    }.onFailure { Log.e(TAG, "Failed to read $uri", it) }.getOrNull()

    // --- Writing -------------------------------------------------------------

    /** Records where [id] lives, so a later save or delete does not have to search for it. */
    fun remember(id: String, uri: Uri) {
        located[id] = uri
    }

    /**
     * Writes one saved thing, returning false if it did not land.
     *
     * Failure is reported rather than swallowed because the caller has something to do with it: the
     * workspace stays dirty, so the unsaved-draft recovery already covers the data, and the user is
     * asked to pick the folder again.
     */
    fun write(kind: Kind, id: String, name: String, json: String): Boolean {
        val existing = located[id]
        if (existing != null && overwrite(existing, json)) {
            isUsable = true
            return true
        }

        val dir = subfolder(kind, create = true) ?: return false
        val created = runCatching { dir.createFile("application/json", fileName(name)) }
            .onFailure { Log.e(TAG, "Failed to create a file for '$name'", it) }
            .getOrNull() ?: return false

        if (!overwrite(created.uri, json)) return false
        located[id] = created.uri
        isUsable = true
        // The old document is a duplicate now — we got here because overwriting it failed, which
        // usually means it was deleted from under us, but if it is still there and still listed it
        // would come back as a second copy of this clip on the next read.
        if (existing != null) delete(existing)
        return true
    }

    /**
     * Overwrites in place, in truncating mode.
     *
     * `"wt"` rather than `"w"`, and this is the single most common way SAF persistence corrupts
     * data: plain `"w"` is not required to truncate, so writing shorter JSON over longer leaves the
     * tail of the previous version behind and the file stops parsing. It is not a race — deleting a
     * step from a clip shrinks the file, so it would happen on demand.
     */
    private fun overwrite(uri: Uri, json: String): Boolean = runCatching {
        appContext.contentResolver.openOutputStream(uri, "wt")!!.use {
            it.write(json.encodeToByteArray())
        }
        true
    }.onFailure {
        Log.e(TAG, "Failed to write $uri", it)
        // The folder was reachable when this began and is not now, or never was. Say so once here
        // rather than letting every subsequent write fail silently.
        isUsable = false
    }.getOrDefault(false)

    fun delete(id: String): Boolean {
        val uri = located.remove(id) ?: return true
        return delete(uri)
    }

    private fun delete(uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(appContext.contentResolver, uri)
    }.onFailure { Log.e(TAG, "Failed to delete $uri", it) }.getOrDefault(false)

    /**
     * Renames the file to match a renamed clip.
     *
     * Best effort, and a failure is deliberately not reported. The name on disk is a label; the name
     * that counts is inside the file. A stale label is untidy, not wrong, so it must not turn a
     * successful rename into a failed one.
     */
    fun rename(id: String, name: String) {
        val uri = located[id] ?: return
        runCatching {
            DocumentsContract.renameDocument(appContext.contentResolver, uri, fileName(name))
        }.onFailure { Log.w(TAG, "Could not rename the file for $id; the label is now stale", it) }
            .getOrNull()
            ?.let { located[id] = it }
    }

    // --- Layout --------------------------------------------------------------

    private fun root(): DocumentFile? {
        val uri = treeUri ?: return null
        return runCatching { DocumentFile.fromTreeUri(appContext, uri) }
            .onFailure { Log.e(TAG, "Failed to open the chosen folder", it) }
            .getOrNull()
            ?.takeIf { it.exists() }
    }

    /**
     * Finds our subfolder, creating it only if it is genuinely absent.
     *
     * The find has to come first. `createDirectory` on a name that already exists does not return
     * the existing one on every provider — `ExternalStorageProvider` makes `clips (1)` — and once
     * the library is split across two directories, half of it disappears from the list.
     */
    private fun subfolder(kind: Kind, create: Boolean): DocumentFile? {
        val root = root() ?: return null
        val existing = runCatching { root.findFile(kind.folder) }.getOrNull()
        if (existing != null && existing.isDirectory) return existing
        if (!create) return null
        return runCatching { root.createDirectory(kind.folder) }
            .onFailure { Log.e(TAG, "Failed to create ${kind.folder}/", it) }
            .getOrNull()
    }

    /**
     * A clip's name, made safe to be a file name.
     *
     * No collision handling: two clips may legitimately share a name, and the provider appends its
     * own suffix when one is taken. Whatever it hands back is what we store, because the name on
     * disk is not how anything is found.
     */
    private fun fileName(name: String): String {
        val cleaned = name
            .map { if (it in ILLEGAL || it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .take(MAX_NAME)
            .trim()
        return (cleaned.ifEmpty { FALLBACK }) + ".json"
    }

    /** Reserved on FAT/exFAT as well as ext4, since an SD card is a normal choice of folder. */
    private const val ILLEGAL = "/\\:*?\"<>|"

    /** Well under the 255-byte limit, which CJK names reach three times faster than Latin ones. */
    private const val MAX_NAME = 60

    private const val FALLBACK = "untitled"
}
