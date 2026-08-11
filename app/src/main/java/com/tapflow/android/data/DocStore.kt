package com.tapflow.android.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * The one folder clips and flows live in, and everything read or written inside it.
 *
 * ### A ref is a path inside the root
 *
 * Everything here takes a **ref**: a relative path under the chosen folder, `/`-separated, extension
 * included — `clips/monster/stage3.clip`, or `stage3.clip` for a file sitting at the top. That string is the
 * whole identity of a saved clip or flow. There is no id inside the JSON and no table mapping ids to
 * locations; a clip *is* the file, and a flow references its clips by where they sit relative to the root.
 *
 * **Relative, and that is the point.** Refs used to be absolute — a `content://` document Uri or an absolute
 * path — which tied every flow to one device and one location. Moving the folder, renaming it, syncing it, or
 * copying it to a second phone broke every reference in it. Against a root, all of those keep working: the
 * flow says "the clip two folders down", and the root says where down starts.
 *
 * ### One implementation, two kinds of root
 *
 * The root is a tree the user granted (API 29+) or a plain directory (API 28 and below, where scoped storage
 * does not exist yet and `WRITE_EXTERNAL_STORAGE` is real read/write access). Both are wrapped in
 * [DocumentFile] — `fromTreeUri` and `fromFile` — so listing, creating, renaming and deleting are one code
 * path. Only reading and writing the bytes branch, because `ContentResolver`'s truncating mode is not
 * something a `file://` Uri is required to honour.
 *
 * That is what replaced a version split that went much deeper: two pickers, two ideas of what a ref was, and
 * two answers to "which files exist".
 *
 * ### It is not a file manager
 *
 * There is no create-folder, no move, and no copy. The user has a file manager and it does all of that
 * better; organising the folder is done there, and this only has to walk into what is already organised.
 */
object DocStore {

    private const val TAG = "DocStore"
    private const val KEY_ROOT = "doc_root"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    /** Where the folder is. A tree Uri on API 29+, an absolute path below that. */
    private var rootRef: String = ""

    fun init(context: Context, preferences: SharedPreferences) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = preferences
        rootRef = prefs.getString(KEY_ROOT, "").orEmpty()
    }

    // --- Where the panel opens ------------------------------------------------

    /**
     * The folder the panel was last left in, for [kind], or "" for the root.
     *
     * **One memory per kind, shared between opening and saving.** Clips and flows do not live in the same
     * place — `clips/monster` and `flows` — so a single memory would send you walking every other time. But
     * opening and saving *are* the same folder: they are one panel now, and remembering them separately
     * would mean the list moved under you depending on which half you used last.
     *
     * Not checked here, deliberately: reading a preference is free and confirming a folder is a provider
     * query, and this is read on the main thread as the panel opens. The panel walks back to the root when
     * the folder turns out not to be there — which it has to do anyway, since the folder can go missing
     * while the panel is standing in it.
     */
    fun lastFolder(kind: DocKind): String = prefs.getString(keyLastFolder(kind), "").orEmpty()

    fun rememberFolder(kind: DocKind, dir: String) {
        prefs.edit().putString(keyLastFolder(kind), dir).apply()
    }

    private fun keyLastFolder(kind: DocKind) = "last_folder_${kind.name.lowercase()}"

    // --- The root ------------------------------------------------------------

    /** Whether a folder has been chosen. Cheap — it does not go near the disk. */
    val hasRoot: Boolean get() = rootRef.isNotEmpty()

    /**
     * What to call the chosen folder on screen.
     *
     * The last path segment of either form, which for a tree Uri is the document id — `primary:tapflow`
     * — so the part after the colon is the folder as the user knows it.
     */
    val rootLabel: String
        get() = Uri.decode(rootRef).substringAfterLast('/').substringAfterLast(':').ifEmpty { rootRef }

    /**
     * Whether choosing the root goes through the platform's tree picker.
     *
     * False on API 28 and below, where the app browses the file system itself. Not a preference: the document
     * picker on the Android 7 device this was tested against listed only "Recent", which shows no folders and
     * therefore nothing, and no intent extra changed that. There is nothing to fall back *to* on that release
     * except a browser of our own.
     */
    val usesTreePicker: Boolean get() = Build.VERSION.SDK_INT > LEGACY_MAX

    /** Only ever asked for on API 28 and below; scoped storage replaced it with granted trees. */
    val legacyPermission: String get() = Manifest.permission.WRITE_EXTERNAL_STORAGE

    val needsLegacyPermission: Boolean
        get() = !usesTreePicker &&
            ContextCompat.checkSelfPermission(appContext, legacyPermission) !=
            PackageManager.PERMISSION_GRANTED

    /** Where the legacy folder chooser starts. Meaningless on API 29+, which has no path to browse. */
    val legacyStart: File get() = Environment.getExternalStorageDirectory()

    /**
     * Remembers the folder, and asks the system to remember the grant behind it.
     *
     * Persisting is silent and may fail: the grant was already given by the act of choosing, and a provider
     * that refuses to make it permanent costs only the next launch. One grant now covers everything, however
     * many files are in there — which is the other reason this shape is better than per-file grants, whose
     * system-wide ceiling used to surface as a flow's clip going missing for no reason the user could see.
     */
    fun setRoot(value: String) {
        if (value.startsWith(CONTENT)) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    Uri.parse(value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { Log.w(TAG, "Could not persist access to $value; this session only", it) }
        }
        rootRef = value
        // The remembered folders go with it: they are paths inside the folder that was there before.
        prefs.edit()
            .putString(KEY_ROOT, value)
            .remove(keyLastFolder(DocKind.CLIP))
            .remove(keyLastFolder(DocKind.FLOW))
            .apply()
        folders.clear()
    }

    /** Whether the chosen folder is actually there. Does IO, so not for drawing a row. */
    fun rootReadable(): Boolean = root() != null

    // --- Listing -------------------------------------------------------------

    /** One row of a folder: a subfolder, or a file of the kind being looked for. */
    data class Entry(val name: String, val ref: String, val isFolder: Boolean)

    /**
     * What is inside [dir], as the browser should show it: folders first, then the files of [kind].
     *
     * Hidden entries are skipped — `.thumbnails` and friends are noise in a list whose only job is to get you
     * to your own folder, and nothing this app writes is hidden.
     *
     * Filtering by kind here is what makes "only clips" and "only flows" true on both versions of Android. It
     * used to be impossible on API 29+, where the platform picker filters by MIME type and `.clip` has none.
     */
    fun list(dir: String, kind: DocKind?): List<Entry> {
        val here = folder(dir) ?: return emptyList()
        val children = runCatching { here.listFiles() }.getOrNull().orEmpty()
        val (dirs, files) = children
            .mapNotNull { child -> child.name?.let { it to child } }
            .filterNot { (name, _) -> name.startsWith(".") }
            .partition { (_, child) -> child.isDirectory }
        return dirs.map { (name, _) -> Entry(name, join(dir, name), isFolder = true) }
            .sortedBy { it.name.lowercase() } +
            files.map { (name, _) -> name }
                .filter { kind == null || kind.matches(it) }
                .sorted()
                .map { Entry(displayName(it), join(dir, it), isFolder = false) }
    }

    // --- Files ---------------------------------------------------------------

    /**
     * The file's name, extension included. Pure string work: the ref *is* the location and the name.
     *
     * That it needs no IO is a consequence of refs being relative, and a welcome one — the home screen used to
     * ask a provider for twenty names before it could draw twenty rows.
     */
    fun fileName(ref: String): String = ref.substringAfterLast('/')

    /** The name without its extension. What a person calls it. */
    fun label(ref: String): String = displayName(fileName(ref))

    /** The file's whole contents, or null when it cannot be read. */
    fun read(ref: String): String? = runCatching {
        val doc = docAt(ref) ?: return null
        if (doc.uri.scheme == FILE) {
            File(doc.uri.path!!).readText()
        } else {
            appContext.contentResolver.openInputStream(doc.uri)!!.use { it.readBytes().decodeToString() }
        }
    }.onFailure { Log.w(TAG, "Could not read $ref", it) }.getOrNull()

    /**
     * Writes [text] to [ref], creating the file when it is not there yet. False means it did not land.
     *
     * `"wt"` rather than `"w"` on the provider side, and this is the single most common way SAF persistence
     * corrupts data: plain `"w"` is not required to truncate, so writing shorter JSON over longer leaves the
     * tail of the previous version behind and the file stops parsing. It is not a race — deleting a step from
     * a clip shrinks the file, so it would happen on demand.
     */
    fun write(ref: String, text: String): Boolean = runCatching {
        val doc = docAt(ref) ?: create(ref) ?: return false
        if (doc.uri.scheme == FILE) {
            // writeText truncates, so the shorter-over-longer trap above simply does not exist here.
            File(doc.uri.path!!).writeText(text)
        } else {
            appContext.contentResolver.openOutputStream(doc.uri, "wt")!!.use {
                it.write(text.encodeToByteArray())
            }
        }
        true
    }.onFailure { Log.e(TAG, "Could not write $ref", it) }.getOrDefault(false)

    /**
     * Makes an empty file at [ref], or null when it could not be made.
     *
     * **The name we ask for has to be the name we get**, because the ref *is* the name — a provider that
     * appends something of its own would leave every reference to this file pointing at nothing. [MIME] is
     * unmapped precisely so nothing is appended; this checks anyway, puts the name back if it can, and fails
     * loudly rather than handing back a ref that does not resolve.
     */
    private fun create(ref: String): DocumentFile? {
        val parent = folder(parentOf(ref)) ?: return null
        val wanted = fileName(ref)
        val made = parent.createFile(MIME, wanted) ?: return null
        if (made.name == wanted) return made
        Log.w(TAG, "The provider named it ${made.name} rather than $wanted")
        if (made.renameTo(wanted) && made.name == wanted) return made
        made.delete()
        return null
    }

    /**
     * Renames the file, returning the ref it now lives at, or null on failure.
     *
     * **Renaming deliberately does not touch any other file.** A flow that referenced the old name breaks and
     * shows `!`, which is the same thing that happens when a file is renamed outside the app — and that has to
     * keep working, so making the in-app route special would buy a difference nobody can rely on.
     */
    fun rename(ref: String, fileName: String): String? = runCatching {
        val doc = docAt(ref) ?: return null
        val to = join(parentOf(ref), fileName)
        if (docAt(to) != null) return null
        if (!doc.renameTo(fileName)) null else to
    }.onFailure { Log.w(TAG, "Could not rename $ref", it) }.getOrNull()

    fun delete(ref: String): Boolean = runCatching {
        docAt(ref)?.delete() == true
    }.onFailure { Log.w(TAG, "Could not delete $ref", it) }.getOrDefault(false)

    /** Whether the file is still there. Does IO. */
    fun exists(ref: String): Boolean = runCatching { docAt(ref) != null }.getOrDefault(false)

    /** Whether a folder is still there. Does IO. Only worth asking when a listing came back empty. */
    fun folderExists(dir: String): Boolean = runCatching { folder(dir) != null }.getOrDefault(false)

    // --- Paths ---------------------------------------------------------------

    /** The folder a ref sits in, or "" for the root itself. */
    fun parentOf(ref: String): String = ref.substringBeforeLast('/', "")

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    // --- Resolution ----------------------------------------------------------

    /**
     * Folders resolved so far, keyed by ref.
     *
     * A lookup costs a listing of every folder on the way down, so a flow of five clips two folders deep would
     * pay for the same two walks five times over. Folders are the stable part of the tree, so they are the
     * safe part to remember; files are not cached, and the whole thing is dropped whenever this object changes
     * anything structural or the root moves.
     */
    private val folders = HashMap<String, DocumentFile>()

    private fun root(): DocumentFile? {
        if (rootRef.isEmpty()) return null
        val doc = if (rootRef.startsWith(CONTENT)) {
            DocumentFile.fromTreeUri(appContext, Uri.parse(rootRef))
        } else {
            DocumentFile.fromFile(File(rootRef))
        }
        return doc?.takeIf { it.isDirectory }
    }

    private fun folder(dir: String): DocumentFile? {
        if (dir.isEmpty()) return root()
        folders[dir]?.let { return it }
        val parent = folder(parentOf(dir)) ?: return null
        val here = parent.findFile(fileName(dir))?.takeIf { it.isDirectory } ?: return null
        folders[dir] = here
        return here
    }

    private fun docAt(ref: String): DocumentFile? {
        if (ref.isEmpty()) return null
        val parent = folder(parentOf(ref)) ?: return null
        return parent.findFile(fileName(ref))?.takeIf { it.isFile }
    }

    /** The last release without scoped storage, and so the last one that can browse a path itself. */
    private const val LEGACY_MAX = 28

    private const val CONTENT = "content://"
    private const val FILE = "file"

    /**
     * A MIME type no `MimeTypeMap` knows, so the name we ask for is the name we get.
     *
     * Both creation paths derive an extension from the MIME type and append it when the given name does not
     * already end in it — `ExternalStorageProvider.createDocument` on one side and `DocumentFile.fromFile` on
     * the other — so `"application/json"` would turn `Login.clip` into `Login.clip.json`. An unmapped type
     * leaves the name alone. [create] verifies it anyway, because a name that came back different is a file
     * nothing can reach: the ref *is* the name.
     */
    const val MIME = "application/vnd.tapflow"
}
