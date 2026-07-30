package com.tapflow.android.data

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.StateFlow

/**
 * The library, whichever way this Android version provides one.
 *
 * A facade rather than a base class, so every caller keeps one name to talk to and neither backend has to
 * know the other exists. The split is by version and not by "try SAF and fall back", because the two
 * mechanisms are not alternatives to the same thing:
 *
 * - **API 29+** — scoped storage, so the only route is a granted folder ([SafLibrary]).
 * - **API 28 and below** — no scoped storage, so `WRITE_EXTERNAL_STORAGE` is plain access to a real path
 *   ([FileLibrary]), and there is no picker to go wrong.
 *
 * The line is at 28/29 rather than 29/30 deliberately. Android 10 can still ask for legacy access, but
 * only with a flag that Android 11 ignores — pinning the boundary where the platform actually changed
 * avoids a backend whose behaviour depends on a request that may be refused.
 *
 * That the old path is the simpler one is the opposite of the usual expectation, and it is why the Android
 * 7 device that could not use the picker at all is better served here than by anything built on SAF: the
 * document picker on that release listed no storage roots, and no intent extra changed it.
 */
object FolderStore {

    private lateinit var backend: LibraryStore

    /** SAF only. Null on the file backend, where the location is fixed and needs no remembering. */
    private var saf: SafLibrary? = null

    fun init(context: Context, storedTree: String?) {
        val app = context.applicationContext
        backend = if (Build.VERSION.SDK_INT <= LEGACY_MAX) {
            FileLibrary(app)
        } else {
            SafLibrary(app, storedTree).also { saf = it }
        }
    }

    val usable: StateFlow<Boolean> get() = backend.usable
    val isConfigured: Boolean get() = backend.isConfigured
    val needsPermission: Boolean get() = backend.needsPermission
    val canForget: Boolean get() = backend.canForget

    /** True where access comes from the document picker, so the UI knows which question to ask. */
    val picksFolder: Boolean get() = saf != null

    fun refreshUsable(): Boolean = backend.refreshUsable()
    fun list(kind: LibraryStore.Kind): List<LibraryStore.Entry> = backend.list(kind)
    fun write(kind: LibraryStore.Kind, id: String, name: String, json: String): Boolean =
        backend.write(kind, id, name, json)

    fun delete(id: String): Boolean = backend.delete(id)
    fun rename(id: String, name: String) = backend.rename(id, name)
    fun remember(id: String, locator: String) = backend.remember(id, locator)
    fun displayName(): String? = backend.displayName()

    // --- SAF only ---

    /** Takes the persistable grant on a folder the user picked. No-op where there is no picker. */
    fun adopt(uri: Uri): Boolean = saf?.adopt(uri) ?: false

    fun forget() = saf?.forget() ?: Unit

    fun treeUriString(): String? = saf?.treeUriString()

    fun prefsKey(): String = KEY_TREE

    private const val KEY_TREE = "library_tree_uri"

    /** The last release without scoped storage. */
    private const val LEGACY_MAX = 28
}
