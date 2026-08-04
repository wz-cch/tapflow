package com.tapflow.android.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// Without this the single-argument reified form is invisible and the call resolves to the
// two-argument member encodeToString(serializer, value) instead.
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Which noun the toolbar is working on.
 *
 * Explicit state, rather than "is a flow loaded" — which is what this used to be, spelled
 * `currentFlowId != null`. Deriving it had three costs. There was no way to *be* in flow mode with
 * nothing loaded, so creating the first flow was unreachable. Every load and unload changed mode as a
 * side effect, which meant every one of them was a hidden mode change someone had to remember to get
 * right. And "switch mode" could not be expressed at all, because mode was not a thing you could set.
 */
enum class AppMode { CLIP, FLOW }

/**
 * The single source of truth.
 *
 * MainActivity and TapFlowService live in the same process, so both sides share the StateFlows on
 * this object directly and no IPC is needed.
 *
 * ### There is no library here any more
 *
 * This object used to hold every saved clip and flow, read out of one folder the user had granted, and
 * everything downstream looked things up in those two lists by id. All of it is gone. A clip is a file the
 * user picked; opening one reads it, and what is held is only *what is open* — the workspace's source file
 * and, in flow mode, one [OpenFlow]. Files the user has been in and out of are a list of names and counts
 * ([Recents]), not a copy of their contents.
 *
 * That removes a whole class of state rather than moving it. There is no configured-folder gate, no walk of
 * a directory tree, no id-to-location table to keep in step with the disk, and no way for the app's idea of
 * the library to disagree with what is actually there — because it no longer has one.
 */
object Repo {

    private const val TAG = "Repo"
    private const val PREFS = "tapflow"
    private const val KEY_MODE = "app_mode"
    private const val KEY_OVERLAY_ON = "overlay_on"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val settingsFile: File get() = File(appContext.filesDir, "settings.json")
    private val workspaceFile: File get() = File(appContext.filesDir, "workspace.json")
    private val recentFile: File get() = File(appContext.filesDir, "recent.json")

    private val _settings = MutableStateFlow(Settings.DEFAULT)
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /**
     * Which noun the toolbar works on. See [AppMode].
     *
     * The only piece of this state that survives a restart. What was *open* in the mode deliberately does
     * not: coming back into the mode you left costs nothing to remember and matches where you were, whereas
     * reopening a file by itself is the thing an app should not do — and reopening is one tap.
     */
    val mode = MutableStateFlow(AppMode.CLIP)

    /**
     * The flow the toolbar's play button runs, read into memory. Null in clip mode and in flow mode with
     * nothing opened yet, which is a real state — it is where creating your first flow starts.
     *
     * Not persisted, for the reason [mode] gives. Named for what it holds rather than for the act, so that
     * `currentFlow.value` and `openFlow(ref)` cannot be misread for one another.
     */
    val currentFlow = MutableStateFlow<OpenFlow?>(null)

    /** Whether the user wants the floating toolbar shown. */
    val overlayEnabled = MutableStateFlow(false)

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        mode.value = runCatching { AppMode.valueOf(prefs.getString(KEY_MODE, "").orEmpty()) }
            .getOrDefault(AppMode.CLIP)
        overlayEnabled.value = prefs.getBoolean(KEY_OVERLAY_ON, false)

        DocStore.init(appContext)
        // One small file in filesDir, unlike the folder walk this replaced — which is why it can be read
        // here, on whichever thread starts the app, instead of being deferred to the first screen.
        Recents.init(recentFile)
        _settings.value = readSettings()
    }

    val isReady: Boolean get() = ::appContext.isInitialized

    // --- Opening -------------------------------------------------------------
    //
    // Every one of these does IO. None of them may be called on the main thread.

    /**
     * Reads one clip. Null when the file is unreadable or is not a clip.
     *
     * A file that will not parse is a normal event rather than a bug: these live where the user can copy,
     * sync and edit them. What it is *not* is silent — the caller says so, because the alternative (an empty
     * clip) is indistinguishable from having opened something that was never saved.
     */
    fun openClip(ref: String): LoadedClip? {
        val name = fileLabel(ref)
        val clip = read<Clip>(ref, "clip") ?: return null
        Recents.touch(
            RecentDoc(
                ref = ref,
                kind = DocKind.CLIP,
                name = name,
                openedAt = System.currentTimeMillis(),
                stepCount = clip.stepCount,
                pauseCount = clip.pauseCount,
                durationMs = clip.estimatedDurationMs,
            )
        )
        return LoadedClip(DocFile(ref, name), clip)
    }

    /**
     * Reads one flow and every clip it references. Null when the flow file itself cannot be read.
     *
     * A clip that cannot be read is *not* a failure of this call. It comes back absent, the row shows `!`,
     * and the flow is otherwise entirely usable — which is the only sane answer when the reference is a
     * location and locations are the user's to rearrange. Refusing to open the flow at all would leave them
     * with no screen on which to fix it.
     */
    fun openFlow(ref: String): OpenFlow? {
        val name = fileLabel(ref)
        val flow = read<Flow>(ref, "flow") ?: return null
        // Distinct refs only: one clip used five times is one read. The map is keyed by ref for the same
        // reason — a flow may legitimately hold the same clip more than once.
        val resolved = flow.clips.map { it.ref }.distinct().mapNotNull { clipRef ->
            val clip = read<Clip>(clipRef, "clip") ?: return@mapNotNull null
            clipRef to LoadedClip(DocFile(clipRef, fileLabel(clipRef)), clip)
        }.toMap()

        val opened = OpenFlow(DocFile(ref, name), flow, resolved)
        Recents.touch(
            RecentDoc(
                ref = ref,
                kind = DocKind.FLOW,
                name = name,
                openedAt = System.currentTimeMillis(),
                clipCount = flow.clips.size,
                durationMs = flowDuration(flow, opened.clips),
            )
        )
        return opened
    }

    // --- Saving --------------------------------------------------------------
    //
    // These do IO too. Same rule: not on the main thread.

    /**
     * Writes a clip to [ref]. False means it did not land, and the caller must not pretend otherwise.
     *
     * On a failure the workspace stays dirty, so the unsaved-draft recovery already holds the data. Which is
     * why this returns a result at all: a swallowed failure would report a successful save of nothing.
     */
    fun saveClip(ref: String, clip: Clip, name: String): Boolean {
        if (!DocStore.write(ref, AppJson.encodeToString(clip))) return false
        Recents.touch(
            RecentDoc(
                ref = ref,
                kind = DocKind.CLIP,
                name = name,
                openedAt = System.currentTimeMillis(),
                stepCount = clip.stepCount,
                pauseCount = clip.pauseCount,
                durationMs = clip.estimatedDurationMs,
            )
        )
        return true
    }

    /**
     * Writes a flow back to its own file and makes it the open one.
     *
     * A flow has no unsaved state — it is a list of references, so every edit is written straight back — and
     * that is why this takes the whole [OpenFlow]: the in-memory copy and the file are meant to agree at all
     * times, so updating one without the other would be the bug.
     */
    fun saveFlow(updated: OpenFlow): Boolean {
        if (!DocStore.write(updated.file.ref, AppJson.encodeToString(updated.flow))) return false
        currentFlow.value = updated
        Recents.touch(
            RecentDoc(
                ref = updated.file.ref,
                kind = DocKind.FLOW,
                name = updated.file.name,
                openedAt = System.currentTimeMillis(),
                clipCount = updated.flow.clips.size,
                durationMs = flowDuration(updated.flow, updated.clips),
            )
        )
        return true
    }

    /** Writes a brand-new empty flow to a file the user just created. */
    fun createFlow(ref: String): OpenFlow? {
        val opened = OpenFlow(DocFile(ref, fileLabel(ref)), Flow(clips = emptyList()), emptyMap())
        return if (saveFlow(opened)) opened else null
    }

    // --- Maintenance ---------------------------------------------------------

    /**
     * Deletes the file behind a row.
     *
     * **Nothing else is touched, and that is the design.** A flow that referenced this clip will show `!` the
     * next time it is opened. Hunting down those flows would mean reading every file the app has ever heard
     * of, to edit files the user did not ask about, on the assumption that a reference to a missing file is
     * worth less than the reference itself — and it is not: `!` plus "point it somewhere else" keeps the
     * arrangement, while pruning silently shortens the flow.
     */
    fun deleteFile(ref: String): Boolean {
        val gone = DocStore.delete(ref)
        if (gone) Recents.forget(ref)
        if (gone && currentFlow.value?.file?.ref == ref) currentFlow.value = null
        return gone
    }

    /**
     * Renames the file, which renames the clip: the file's name is the only name there is.
     *
     * Flows pointing at the old name break, visibly. That is not a regression to be fixed later — it is the
     * same thing that happens when the file is renamed from a file manager, which cannot be prevented, so
     * making the in-app route quietly repair references would create a difference nobody can depend on.
     */
    fun renameFile(ref: String, kind: DocKind, name: String): String? {
        val to = DocStore.rename(ref, suggestedFileName(name, kind)) ?: return null
        val label = fileLabel(to)
        Recents.renamed(ref, to, label)
        // The open flow renaming itself under the editor is the one case where this object holds something
        // that has to follow. Its clips did not move, so they are carried over unread.
        currentFlow.value?.takeIf { it.file.ref == ref }
            ?.let { currentFlow.value = it.movedTo(DocFile(to, label)) }
        return to
    }

    // --- Settings ------------------------------------------------------------

    fun updateSettings(transform: (Settings) -> Settings) {
        _settings.value = transform(_settings.value)
        runCatching { settingsFile.writeText(AppJson.encodeToString(_settings.value)) }
            .onFailure { Log.e(TAG, "Failed to write settings", it) }
    }

    fun resetSettings() = updateSettings { Settings.DEFAULT }

    // --- Workspace draft -----------------------------------------------------
    //
    // The in-memory workspace (engine/Workspace.kt) calls through here rather than touching filesDir
    // itself, so all of the app's own storage stays in one object.

    fun readWorkspace(): WorkspaceSnapshot = runCatching {
        if (!workspaceFile.exists()) WorkspaceSnapshot()
        else AppJson.decodeFromString<WorkspaceSnapshot>(workspaceFile.readText())
    }.onFailure { Log.e(TAG, "Failed to read workspace draft, starting empty", it) }
        .getOrDefault(WorkspaceSnapshot())

    fun writeWorkspace(snapshot: WorkspaceSnapshot) {
        runCatching { workspaceFile.writeText(AppJson.encodeToString(snapshot)) }
            .onFailure { Log.e(TAG, "Failed to write the workspace draft", it) }
    }

    // --- Preferences ---------------------------------------------------------

    /**
     * Both of these are single writes with no rules attached. The rules about what may be loaded
     * alongside what live in engine/Session.kt, which is the only thing that should be calling them.
     */
    fun setMode(next: AppMode) {
        mode.value = next
        prefs.edit().putString(KEY_MODE, next.name).apply()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled.value = enabled
        prefs.edit().putBoolean(KEY_OVERLAY_ON, enabled).apply()
    }

    /** Small scalar preferences, used for remembering where the floating windows were dragged to. */
    fun readInt(key: String, default: Int): Int = prefs.getInt(key, default)

    fun writeInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    // --- I/O -----------------------------------------------------------------

    private fun fileLabel(ref: String): String = DocStore.label(ref)

    private inline fun <reified T> read(ref: String, what: String): T? {
        val text = DocStore.read(ref) ?: return null
        return runCatching { AppJson.decodeFromString<T>(text) }
            .onFailure { Log.w(TAG, "$ref is not a readable $what", it) }
            .getOrNull()
    }

    private fun readSettings(): Settings = runCatching {
        if (!settingsFile.exists()) Settings.DEFAULT
        else AppJson.decodeFromString<Settings>(settingsFile.readText())
    }.onFailure { Log.e(TAG, "Failed to read settings, falling back to defaults", it) }
        .getOrDefault(Settings.DEFAULT)
}

/**
 * Roughly how long one pass of a flow takes, for the row in the recent list.
 *
 * A clip that could not be read contributes nothing. Better than refusing to show an estimate: the row also
 * says how many clips there are, so a total that is short for a flow with a broken reference is consistent
 * with the `!` the flow itself shows.
 */
private fun flowDuration(flow: Flow, clips: Map<String, Clip>): Long = flow.clips.sumOf { node ->
    val clip = clips[node.ref] ?: return@sumOf 0L
    val passes = node.repeat.coerceAtLeast(1)
    node.delayBefore + clip.estimatedDurationMs * passes + node.extraPasses * node.repeatIntervalMs
}
