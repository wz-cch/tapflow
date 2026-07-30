package com.tapflow.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.documentfile.provider.DocumentFile

/**
 * The library on Android 10 and up: a folder the user granted through the document picker.
 *
 * Scoped storage leaves no other route — broad file access is gone, so the app can only touch a tree it
 * was handed. That is also why the outer folder has to already exist: a grant is over something, so it
 * cannot be over a folder we would create.
 *
 * ### The file name is decorative
 *
 * A clip's identity is its `id`, which lives *inside* the JSON. The file name is the clip's name, purely
 * so the folder is legible to whoever opens it — which is the point of letting them choose it. Reading
 * ignores the file name entirely and takes the name and id from the content.
 *
 * That one decision removes three problems rather than solving them. Two clips can share a name: the
 * provider appends its own suffix and we keep whatever it gave us. A name can contain path separators:
 * they are stripped, and if nothing survives there is a fallback. A rename can fail halfway: the file
 * keeps its old name and *nothing is wrong*, because the content is what counts.
 */
class SafLibrary(private val appContext: Context, storedTree: String?) : LibraryStore {

    private var treeUri: Uri? = storedTree?.let(Uri::parse)

    /**
     * Where each saved thing lives, keyed by the id inside it.
     *
     * Needed because the file name cannot be trusted to still match the content, and needed anyway
     * for speed: [DocumentFile.findFile] enumerates the whole directory on every call, so looking a
     * file up by name on each save would turn one write into a directory scan.
     */
    private val located = HashMap<String, Uri>()

    override val isConfigured: Boolean get() = treeUri != null

    /** SAF has no runtime permission to ask for; access comes from the picker instead. */
    override val needsPermission: Boolean get() = false

    override val canForget: Boolean get() = true

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
    override val usable = MutableStateFlow(false)

    private var isUsable: Boolean
        get() = usable.value
        set(value) { usable.value = value }

    /** Re-probes [usable]. Does IO, so call it off the main thread. */
    override fun refreshUsable(): Boolean {
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

    /**
     * A human-readable-ish name for the chosen folder.
     *
     * Derived from the tree Uri because SAF gives no path. Fine for internal storage and SD cards,
     * where the last segment is `primary:Documents/TapFlow`; other providers are free to return
     * something opaque, and there is nothing to be done about that — it is what SAF exposes.
     */
    override fun displayName(): String? {
        val uri = treeUri ?: return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.lastPathSegment
        return id.substringAfter(':', id)
    }

    // --- Reading -------------------------------------------------------------

    /**
     * Everything of one kind, skipping whatever will not parse.
     *
     * Tolerant on purpose. These files sit in a folder the user opens, syncs and copies around, so a
     * truncated or hand-mangled one is a normal event rather than a corruption bug — and one bad
     * file must cost that one clip, not the whole list. The skip is logged; nothing is deleted.
     */
    override fun list(kind: LibraryStore.Kind): List<LibraryStore.Entry> {
        val dir = subfolder(kind, create = false) ?: return emptyList()
        return dir.listFiles().mapNotNull { file ->
            if (!file.isFile || file.name?.endsWith(".json") != true) return@mapNotNull null
            val text = read(file.uri) ?: return@mapNotNull null
            LibraryStore.Entry(text, file.uri.toString())
        }
    }

    private fun read(uri: Uri): String? = runCatching {
        appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
    }.onFailure { Log.e(TAG, "Failed to read $uri", it) }.getOrNull()

    // --- Writing -------------------------------------------------------------

    /** Records where [id] lives, so a later save or delete does not have to search for it. */
    override fun remember(id: String, locator: String) {
        located[id] = Uri.parse(locator)
    }

    /**
     * Writes one saved thing, returning false if it did not land.
     *
     * Failure is reported rather than swallowed because the caller has something to do with it: the
     * workspace stays dirty, so the unsaved-draft recovery already covers the data, and the user is
     * asked to pick the folder again.
     */
    override fun write(kind: LibraryStore.Kind, id: String, name: String, json: String): Boolean {
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

    override fun delete(id: String): Boolean {
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
    override fun rename(id: String, name: String) {
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
    private fun subfolder(kind: LibraryStore.Kind, create: Boolean): DocumentFile? {
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

    private companion object {
        const val TAG = "SafLibrary"

        /** Reserved on FAT/exFAT as well as ext4, since an SD card is a normal choice of folder. */
        const val ILLEGAL = "/\\:*?\"<>|"

        /** Well under the 255-byte limit, which CJK names reach three times faster than Latin ones. */
        const val MAX_NAME = 60

        const val FALLBACK = "untitled"
    }
}
