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
 * id-to-Uri table (the path *is* the location), no truncating-mode trap (`writeText` truncates), no
 * duplicate-directory trap (`mkdirs` on an existing directory does nothing).
 *
 * The on-disk layout is identical either way — `clips/<name>.json`, `flows/<name>.json` — so a folder
 * copied from one device to the other just works.
 */
interface LibraryStore {

    enum class Kind(val folder: String) { CLIP("clips"), FLOW("flows") }

    /**
     * One stored file's contents, with an opaque handle back to it.
     *
     * [locator] is a Uri string or an absolute path depending on the implementation, and only ever
     * travels from [list] back into [remember] — the caller never interprets it. Held as a string
     * because the alternative is a type parameter threaded through Repo for a value it never reads.
     */
    class Entry(val json: String, val locator: String)

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

    /** Everything of one kind, skipping whatever will not parse. */
    fun list(kind: Kind): List<Entry>

    /** Writes one saved thing. False means it did not land, and the caller must not pretend otherwise. */
    fun write(kind: Kind, id: String, name: String, json: String): Boolean

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
}
