package com.tapflow.android.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Reading and writing the files a clip or a flow lives in.
 *
 * ### A reference is a location, and the location is the identity
 *
 * Everything here takes a **ref**: a `content://` Uri string on API 29 and up, an absolute path on API 28
 * and below. That string is the whole identity of a saved clip or flow. There is no id inside the JSON, no
 * table mapping ids to files, and no library folder — a clip *is* the file you picked, in whatever folder
 * you keep it in, and a flow references its clips by their refs.
 *
 * This replaced a model with one granted library folder, and the reason was that the folder defeated the
 * purpose it was introduced for. Access had to be arranged in advance, so a save could only land in the one
 * folder that had been chosen; worse, a flow could not reference clips from two different folders at once,
 * which is exactly what a folder is *for* — `common/delay.clip` alongside `battle/stage3.clip`.
 *
 * ### One store, both mechanisms, chosen by the ref
 *
 * The split is by scheme rather than by version, and that is what keeps it small. Scoped storage means API
 * 29+ can only touch documents the user handed over through a picker; API 28 and below has plain read/write
 * access to shared storage. But once a file has been picked, "read this ref" is one function with two
 * branches, not two backends with parallel bookkeeping.
 *
 * The version *does* decide which picker is used — see [usesSystemPicker], and `ui/FilePicker.kt` — because
 * the system document picker lists no storage roots at all on the Android 7 device this has to work on.
 */
object DocStore {

    private const val TAG = "DocStore"

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    /**
     * Whether the platform's own document picker is the way to choose a file.
     *
     * False on API 28 and below, where the app browses shared storage itself. Not a preference: the document
     * picker on the Android 7 device this was tested against listed only "Recent", which shows no folders
     * and therefore nothing, and no intent extra changed that. There is nothing to fall back *to* on that
     * release except a browser of our own — and once written, it is also the only route that needs no
     * per-file grant, so on those versions it is simply better.
     */
    val usesSystemPicker: Boolean get() = Build.VERSION.SDK_INT > LEGACY_MAX

    /** Only ever asked for on API 28 and below; scoped storage replaced it with per-file grants. */
    val legacyPermission: String get() = Manifest.permission.WRITE_EXTERNAL_STORAGE

    val needsLegacyPermission: Boolean
        get() = !usesSystemPicker &&
            ContextCompat.checkSelfPermission(appContext, legacyPermission) !=
            PackageManager.PERMISSION_GRANTED

    /** Where the legacy browser starts. Meaningless on API 29+, which has no path to browse. */
    val legacyRoot: File get() = Environment.getExternalStorageDirectory()

    // --- Grants --------------------------------------------------------------

    /**
     * Keeps access to a file the user just picked, so it can be reopened after a restart.
     *
     * Silent, and it has to be: the grant was already given by the act of picking, and this only asks the
     * system to remember it. Nothing is prompted, and a provider that refuses to make it persistable costs
     * only the next launch — the file stays readable for this session.
     *
     * There is a system-wide ceiling on how many of these one app may hold (128 on older releases). Past it
     * the oldest is dropped, which surfaces as a flow's clip showing `!` — the same way it would if the file
     * had been moved, and with the same one-tap remedy. Not worth pre-empting by releasing grants for old
     * entries: the recent-files list is not the set of files in use, since a flow references clips that may
     * never have been opened on their own.
     */
    fun persistAccess(uri: Uri) {
        if (!usesSystemPicker) return
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "Could not persist access to $uri; this session only", it) }
    }

    // --- Reading -------------------------------------------------------------

    /** The file's whole contents, or null when it cannot be read. */
    fun read(ref: String): String? = runCatching {
        val uri = contentUri(ref)
        if (uri != null) {
            appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        } else {
            File(ref).readText()
        }
    }.onFailure { Log.w(TAG, "Could not read $ref", it) }.getOrNull()

    /**
     * The file's name, extension included, or null when it cannot be asked.
     *
     * Null is the same answer as "the file is gone", and is treated that way, because from here there is no
     * difference worth acting on: a document whose provider has forgotten the grant and one that was deleted
     * both mean the ref no longer points at anything that can be opened.
     */
    private fun fileName(ref: String): String? = runCatching {
        val uri = contentUri(ref)
        if (uri != null) DocumentFile.fromSingleUri(appContext, uri)?.name else File(ref).name
    }.onFailure { Log.w(TAG, "Could not read the name of $ref", it) }.getOrNull()

    /** The file's name without its extension, or "" when it will not answer. What a person calls it. */
    fun label(ref: String): String = fileName(ref)?.let(::displayName).orEmpty()

    /** Whether the file is still there. Does IO. */
    fun exists(ref: String): Boolean = runCatching {
        val uri = contentUri(ref)
        if (uri != null) DocumentFile.fromSingleUri(appContext, uri)?.exists() == true else File(ref).isFile
    }.getOrDefault(false)

    // --- Writing -------------------------------------------------------------

    /**
     * Overwrites the file at [ref]. False means it did not land, and the caller must not pretend otherwise.
     *
     * `"wt"` rather than `"w"` on the SAF side, and this is the single most common way SAF persistence
     * corrupts data: plain `"w"` is not required to truncate, so writing shorter JSON over longer leaves the
     * tail of the previous version behind and the file stops parsing. It is not a race — deleting a step from
     * a clip shrinks the file, so it would happen on demand.
     */
    fun write(ref: String, text: String): Boolean = runCatching {
        val uri = contentUri(ref)
        if (uri != null) {
            appContext.contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(text.encodeToByteArray())
            }
        } else {
            // writeText truncates, so the shorter-over-longer trap above simply does not exist here.
            File(ref).writeText(text)
        }
        true
    }.onFailure { Log.e(TAG, "Could not write $ref", it) }.getOrDefault(false)

    /**
     * Renames the file, returning the ref it now lives at, or null on failure.
     *
     * The ref changes, and on the SAF side it changes into something unrelated to the name — which is why
     * this returns it rather than mutating anything: whoever asked for the rename is the only one who knows
     * what else refers to the old value.
     *
     * **Renaming deliberately does not touch any other file.** A flow that referenced the old name breaks
     * and shows `!`, which is the same thing that happens when a file is renamed outside the app — and that
     * has to keep working, so making the in-app route special would buy a difference nobody can rely on.
     */
    fun rename(ref: String, fileName: String): String? = runCatching {
        val uri = contentUri(ref)
        if (uri != null) {
            DocumentsContract.renameDocument(appContext.contentResolver, uri, fileName)?.toString()
        } else {
            val from = File(ref)
            val to = File(from.parentFile ?: return@runCatching null, fileName)
            if (to.exists() || !from.renameTo(to)) null else to.absolutePath
        }
    }.onFailure { Log.w(TAG, "Could not rename $ref", it) }.getOrNull()

    fun delete(ref: String): Boolean = runCatching {
        val uri = contentUri(ref)
        if (uri != null) {
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        } else {
            File(ref).delete()
        }
    }.onFailure { Log.w(TAG, "Could not delete $ref", it) }.getOrDefault(false)

    /**
     * The Uri a picker should open at when reopening something near [ref], or null when there is nothing to
     * suggest.
     *
     * A document's own Uri, handed to `EXTRA_INITIAL_URI`, lands the picker in the folder that holds it —
     * which is the whole point at the one place this is used: repointing a flow at a clip that moved almost
     * always means finding it next to the flow itself.
     */
    fun initialLocation(ref: String?): Uri? = ref?.let(::contentUri)

    /** A ref is a content Uri or a path; this is the one place that distinction is made. */
    private fun contentUri(ref: String): Uri? =
        if (ref.startsWith("content://")) Uri.parse(ref) else null

    /** The last release without scoped storage, and so the last one that can browse a path itself. */
    private const val LEGACY_MAX = 28

    /**
     * A MIME type no `MimeTypeMap` knows, so the name we ask for is the name we get.
     *
     * `ExternalStorageProvider.createDocument` derives an extension from the MIME type and appends it when
     * the given name does not already end in it — so `"application/json"` would turn `Login.clip` into
     * `Login.clip.json`. An unmapped type leaves the name alone. [DocKind.matches] accepts the appended form
     * anyway, in case some other provider appends regardless.
     */
    const val MIME = "application/vnd.tapflow"
}
