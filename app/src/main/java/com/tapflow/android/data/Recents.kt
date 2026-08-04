package com.tapflow.android.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * One line of the recently-opened list.
 *
 * **The counts are cached on purpose.** Without them the home screen would have to open and parse twenty
 * files to draw twenty rows — twenty provider round trips before anything is on screen, for a screen whose
 * whole job is to be the way in. So opening or saving a file records what it contained, and the list draws
 * with no IO at all.
 *
 * They are cached as *numbers*, not as the finished sentence. A pre-rendered "3 actions · 12s" would freeze
 * the locale it was written in, so the wording still comes from `text/StepText.kt` at display time.
 *
 * Which fields mean anything depends on [kind] — a clip has steps, a flow has clips — and both share
 * [durationMs]. Two record types would be more honest and would double every function here for a difference
 * only the row's own formatter cares about.
 */
@Serializable
data class RecentDoc(
    val ref: String,
    val kind: DocKind,
    val name: String,
    val openedAt: Long,
    /** Clips. */
    val stepCount: Int = 0,
    val pauseCount: Int = 0,
    /** Flows. */
    val clipCount: Int = 0,
    /** Both kinds, and the reason the counts are worth caching at all: it costs a whole parse. */
    val durationMs: Long = 0,
    /**
     * Whether the file has gone.
     *
     * `@Transient`, and that is the point rather than a detail: the check runs in the background after the
     * list is already on screen, and a ref that was unreachable last week may be a mounted card today. A
     * persisted failure would make the row lie until something re-checked it.
     */
    @Transient val missing: Boolean = false,
)

/**
 * The recently-opened files, newest first.
 *
 * This is the closest thing to a library that is left, and the difference from one matters: it is a list of
 * *files the user opened*, not a description of what exists. Nothing is discovered by scanning, nothing is
 * owned, and dropping a row deletes nothing. That is what makes "keep your clips wherever you like" true
 * rather than a claim — the app has no folder that is the right one.
 *
 * Both kinds live in one list and are split for display, so the cap applies per kind: twenty clips cannot
 * push the flows off the screen.
 */
object Recents {

    private const val TAG = "Recents"

    /** Enough that the list is a memory, few enough that the screen stays scannable. */
    private const val MAX_PER_KIND = 20

    private lateinit var file: File

    private val _docs = MutableStateFlow<List<RecentDoc>>(emptyList())
    val docs: StateFlow<List<RecentDoc>> = _docs.asStateFlow()

    /** Reads the list. One small file in `filesDir`, so unlike the old library walk this is cheap. */
    fun init(store: File) {
        file = store
        _docs.value = runCatching {
            if (!store.exists()) emptyList() else AppJson.decodeFromString<List<RecentDoc>>(store.readText())
        }.onFailure { Log.w(TAG, "Could not read the recent list, starting empty", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Records that a file was just opened or written.
     *
     * Also the one place a stale name gets corrected: whatever the file is called *now* wins, so renaming it
     * in a file manager and reopening it fixes the row rather than adding a second one for the same file.
     */
    fun touch(doc: RecentDoc) = update { list ->
        val kept = list.filterNot { it.ref == doc.ref }
        // Capped per kind, so a run of saved clips cannot push every flow off the screen.
        (listOf(doc) + kept)
            .groupBy { it.kind }
            .flatMap { (_, ofKind) -> ofKind.take(MAX_PER_KIND) }
            .sortedByDescending { it.openedAt }
    }

    /** Takes a row out of the list without touching the file it points at. */
    fun forget(ref: String) = update { list -> list.filterNot { it.ref == ref } }

    /** After a rename: same file, new ref and name. */
    fun renamed(from: String, to: String, name: String) = update { list ->
        list.map { if (it.ref == from) it.copy(ref = to, name = name) else it }
    }

    /**
     * Flags the rows whose files could not be found.
     *
     * Nothing is removed, and nothing is written to disk — see [RecentDoc.missing]. A missing file is worth
     * showing precisely because the user is the one who moved it: the row is how they find out, and it still
     * has a name, a delete and a way off the list.
     */
    fun setMissing(refs: Set<String>) {
        _docs.value = _docs.value.map { doc ->
            val gone = doc.ref in refs
            if (doc.missing == gone) doc else doc.copy(missing = gone)
        }
    }

    private fun update(transform: (List<RecentDoc>) -> List<RecentDoc>) {
        _docs.value = transform(_docs.value)
        persist()
    }

    private fun persist() {
        if (!::file.isInitialized) return
        runCatching { file.writeText(AppJson.encodeToString(_docs.value)) }
            .onFailure { Log.w(TAG, "Could not write the recent list", it) }
    }
}
