package com.tapflow.android.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * The library on Android 9 and below: a real folder at a real path.
 *
 * `WRITE_EXTERNAL_STORAGE` is broad read/write access to shared storage on API 28 and below — scoped
 * storage does not arrive until API 29 — so there is nothing to negotiate with a document provider and
 * no grant that a reinstall takes away. One runtime permission, then plain files.
 *
 * **The path is fixed and we create it.** Choosing a folder was never the goal; surviving a reinstall and
 * being somewhere the user can back up was, and a known path satisfies both. Being able to choose was
 * forced by SAF's grant model, and this backend does not have that model — which is also why it does not
 * need the picker that turned out to list nothing at all on the Android 7 device this was written for.
 *
 * Uninstalling does not remove this. That is the whole point, and it is only true because the folder is
 * in shared storage rather than under `Android/data/<package>` — the app-specific external directories
 * need no permission but are deleted with the app, which would defeat the exercise.
 */
class FileLibrary(private val context: Context) : LibraryStore {

    private val root: File get() = File(Environment.getExternalStorageDirectory(), FOLDER)

    override val usable = MutableStateFlow(false)

    /** There is always somewhere to save. Whether it is reachable yet is [usable]. */
    override val isConfigured: Boolean get() = true

    override val needsPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

    /** Nothing to forget: the location is not a choice. */
    override val canForget: Boolean get() = false

    /**
     * Checks the three things that have to hold, and creates the folder while it is here.
     *
     * Creating on probe rather than on first write keeps the failure in one place. A missing folder and an
     * unwritable one are the same problem from the user's side — the storage is not ready — and finding
     * that out mid-save would mean reporting it from the middle of a save.
     */
    override fun refreshUsable(): Boolean {
        val ok = !needsPermission &&
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
            runCatching { root.isDirectory || root.mkdirs() }
                .onFailure { Log.e(TAG, "Could not create $root", it) }
                .getOrDefault(false)
        usable.value = ok
        return ok
    }

    override fun list(kind: LibraryStore.Kind): List<LibraryStore.Entry> {
        val dir = File(root, kind.folder)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file ->
                // Tolerant on purpose: these files sit where the user can open and copy them, so a
                // mangled one is an ordinary event and must cost that one clip rather than the list.
                val text = runCatching { file.readText() }
                    .onFailure { Log.e(TAG, "Failed to read $file", it) }
                    .getOrNull() ?: return@mapNotNull null
                LibraryStore.Entry(text, file.absolutePath)
            }
    }

    override fun write(kind: LibraryStore.Kind, id: String, name: String, json: String): Boolean {
        val dir = File(root, kind.folder)
        // mkdirs on an existing directory returns false rather than throwing, so ask about the outcome
        // and not the return value. This is the trap the SAF backend has to work around with a find-first
        // dance, and here it is simply not one.
        if (!dir.isDirectory && !runCatching { dir.mkdirs() }.getOrDefault(false) && !dir.isDirectory) {
            Log.e(TAG, "Could not create $dir")
            usable.value = false
            return false
        }

        val existing = located[id]?.let(::File)
        val target = existing ?: uniqueFile(dir, name)

        return runCatching {
            // writeText truncates, so there is no shorter-over-longer corruption to guard against.
            target.writeText(json)
            located[id] = target.absolutePath
            usable.value = true
            true
        }.onFailure {
            Log.e(TAG, "Failed to write $target", it)
            usable.value = false
        }.getOrDefault(false)
    }

    override fun delete(id: String): Boolean {
        val path = located.remove(id) ?: return true
        return runCatching { File(path).delete() }
            .onFailure { Log.e(TAG, "Failed to delete $path", it) }
            .getOrDefault(false)
    }

    override fun rename(id: String, name: String) {
        val from = located[id]?.let(::File) ?: return
        val to = uniqueFile(from.parentFile ?: return, name)
        runCatching { if (from.renameTo(to)) located[id] = to.absolutePath }
            .onFailure { Log.w(TAG, "Could not rename $from; the label is now stale", it) }
    }

    override fun remember(id: String, locator: String) {
        located[id] = locator
    }

    override fun displayName(): String? = root.absolutePath

    private val located = HashMap<String, String>()

    /**
     * A free file name based on the clip's name.
     *
     * Unlike SAF there is no provider to append a suffix on a collision, so it is done here — two clips
     * are allowed to share a name, and the file name is only a label either way.
     */
    private fun uniqueFile(dir: File, name: String): File {
        val base = safeName(name)
        var candidate = File(dir, "$base.json")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n).json")
            n++
        }
        return candidate
    }

    private fun safeName(name: String): String {
        val cleaned = name
            .map { if (it in ILLEGAL || it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .take(MAX_NAME)
            .trim()
        return cleaned.ifEmpty { FALLBACK }
    }

    companion object {
        const val PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

        /** Not under Android/data: that needs no permission but is deleted with the app. */
        private const val FOLDER = "TapFlow"

        private const val TAG = "FileLibrary"

        /** Reserved on FAT and exFAT as well as ext4, since shared storage may be either. */
        private const val ILLEGAL = "/\\:*?\"<>|"

        /** Well under the 255-byte limit, which CJK names reach three times faster than Latin ones. */
        private const val MAX_NAME = 60

        private const val FALLBACK = "untitled"
    }
}
