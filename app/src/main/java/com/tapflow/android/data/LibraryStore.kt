package com.tapflow.android.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Where saved clips and flows are kept, outside the app's own storage so they survive reinstalling.
 *
 * Two implementations, split by Android version, because the platform genuinely offers two different
 * mechanisms rather than one with a compatibility shim:
 *
 * - **API 29 and up** ([SafLibrary]) — scoped storage means the only route is a folder the user grants
 *   through the document picker. We cannot create the outer folder; they pick one that exists.
 * - **API 28 and below** ([FileLibrary]) — scoped storage does not exist yet, so `WRITE_EXTERNAL_STORAGE`
 *   is plain read/write access to shared storage. No picker, no grant that a reinstall revokes, and we
 *   *can* create the folder.
 *
 * The old backend is the simpler of the two, which is worth stating because the reverse is assumed: no
 * id-to-locator table (the path *is* the location), no truncating-mode trap (`writeText` truncates), and
 * no grant for a reinstall to revoke.
 *
 * ### The folder is a tree, and the file names its own kind
 *
 * Whatever the user put in the chosen folder is theirs to arrange, so a saved thing may sit at the root or
 * in any subfolder they made, and [list] walks the whole tree. That is why the kind is an **extension**
 * (`.clip` / `.flow`) rather than a directory: once files can be anywhere, the parent folder no longer says
 * what a file is.
 *
 * It also settles an inconsistency. A clip's identity has always been the `id` *inside* the JSON, with the
 * file name explicitly decorative — but the kind used to be encoded in the path, which made the location
 * load-bearing for exactly one thing. Now nothing about where a file sits carries meaning to the app.
 */
interface LibraryStore {

    enum class Kind(val extension: String) {
        CLIP(".clip"),
        FLOW(".flow"),
        ;

        /**
         * Whether [fileName] is one of these.
         *
         * `<name>.clip.json` is accepted as well as `<name>.clip` because a document provider may append
         * an extension of its own when a file is created — `ExternalStorageProvider` derives one from the
         * MIME type — and a file we wrote but then could not see would be the worst outcome available.
         * Writing aims for the short form; see the MIME type each backend passes.
         */
        fun matches(fileName: String): Boolean =
            fileName.endsWith(extension, ignoreCase = true) ||
                fileName.endsWith("$extension.json", ignoreCase = true)

        companion object {
            /** Which kind [fileName] is, or null when it is none of ours. */
            fun of(fileName: String): Kind? = entries.firstOrNull { it.matches(fileName) }
        }
    }

    /**
     * One stored file's contents, with an opaque handle back to it.
     *
     * [locator] is a Uri string or an absolute path depending on the implementation, and only ever
     * travels from [list] back into [remember] — the caller never interprets it. Held as a string
     * because the alternative is a type parameter threaded through Repo for a value it never reads.
     *
     * [folder] is the opposite: the one thing about a file's location that the app *does* read, and only
     * so browsing can group by it. Relative to the chosen folder, "" for the root, and reported by the
     * walk because the walk already knows it — deriving it from [locator] afterwards would mean parsing
     * the value that is documented as opaque.
     */
    class Entry(val kind: Kind, val json: String, val locator: String, val folder: String)

    /**
     * What one walk of the folder found.
     *
     * @param unread how many files or directories were **there** but could not be read or listed.
     *   Reported rather than swallowed, and it matters more than it looks: flows resolve their clips
     *   against the in-memory library, so a directory this walk failed to enter does not present as an
     *   unreadable folder — it presents as a flow claiming one of its clips no longer exists.
     */
    class Listing(val entries: List<Entry>, val unread: Int)

    /**
     * Whether saving would work right now. Observable, because a failed write clears it and the screen
     * that was mid-save has to turn into "fix the storage" by itself.
     */
    val usable: StateFlow<Boolean>

    /** Whether there is somewhere to save at all, granted or not. Always true where the path is fixed. */
    val isConfigured: Boolean

    /** True when the only thing missing is a runtime permission, which the UI can ask for. */
    val needsPermission: Boolean

    /** Whether "forget this folder" is a meaningful action. It is not when the location is fixed. */
    val canForget: Boolean

    /** Re-probes [usable]. Does IO, so keep it off the main thread. */
    fun refreshUsable(): Boolean

    /**
     * Everything the folder holds, of both kinds, from **one** walk of the tree.
     *
     * Both kinds together rather than one call each, because the walk is the expensive part and every
     * screen that wants flows also wants clips — a flow row reads "3 clips, 47 actions". Two calls would
     * mean walking the same directories twice, and on SAF each directory is an IPC round trip.
     */
    fun list(): Listing

    /**
     * Names the immediate subfolders of [within], relative to the chosen folder, for browsing. Not
     * recursive; "" is the root.
     *
     * A folder that cannot be listed yields an empty list rather than an error. Browsing into it and
     * seeing nothing is a fair account of what happened, and [list] is where that shortfall is counted.
     */
    fun folders(within: String): List<String>

    /**
     * Writes one saved thing. False means it did not land, and the caller must not pretend otherwise.
     *
     * [folder] is where a **new** file goes, relative to the chosen folder, "" for the root. Something
     * already known — see [remember] — is overwritten wherever it already lives, so moving a file in a
     * file manager keeps working and saving does not relocate what you were editing.
     *
     * One exception, and only on [SafLibrary]: if the known document has vanished, the overwrite fails and
     * the file is recreated — in [folder], because a document Uri does not hand back its parent, so there
     * is nothing to recreate it *next to*. So a save that has to recreate can move a clip to the working
     * folder. Recovering the clip is worth that; the alternative is threading a folder through [remember]
     * for a path only a deleted-from-under-us save reaches. [FileLibrary] has no such path — a failed
     * write there simply fails, because the target is a plain path that stays valid.
     */
    fun write(kind: Kind, id: String, name: String, json: String, folder: String = ""): Boolean

    fun delete(id: String): Boolean

    /**
     * Renames the file to match a renamed clip. Best effort by design — the name on disk is a label and
     * the name that counts is inside the file, so a failure here is untidy rather than wrong.
     */
    fun rename(id: String, name: String)

    /** Records where [id] lives, so a later write or delete does not have to search for it. */
    fun remember(id: String, locator: String)

    /** Something to show the user. A real path where there is one; SAF only exposes a document id. */
    fun displayName(): String?

    companion object {
        /**
         * How many directories one walk will enter.
         *
         * A ceiling rather than none, because the chosen folder is the user's and could be enormous.
         * Whatever it stops short of is added to [Listing.unread]: a cap that reports is bounded, while a
         * silent one would read as "your library is this and nothing more".
         */
        const val MAX_DIRECTORIES = 200
    }
}
