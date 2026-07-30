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
 */
object Repo {

    private const val TAG = "Repo"
    private const val PREFS = "tapflow"
    private const val KEY_MODE = "app_mode"
    private const val KEY_CURRENT_CLIP = "current_clip_id"
    private const val KEY_OVERLAY_ON = "overlay_on"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val settingsFile: File get() = File(appContext.filesDir, "settings.json")
    private val workspaceFile: File get() = File(appContext.filesDir, "workspace.json")

    // --- The library ---
    //
    // Saved clips and flows live in the folder the user chose (see FolderStore), and these two flows
    // are a session-lifetime cache of what was read from it — not a second copy on disk. Empty until
    // something asks, because reading the folder is IPC and startup is not a moment anyone asked for
    // it: recording, editing and replaying the workspace never need the library at all.

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    private val _flows = MutableStateFlow<List<Flow>>(emptyList())
    val flows: StateFlow<List<Flow>> = _flows.asStateFlow()

    /** Whether [loadLibrary] has run against the current folder. */
    val libraryLoaded = MutableStateFlow(false)

    private val _settings = MutableStateFlow(Settings.DEFAULT)
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /**
     * Which noun the toolbar works on. See [AppMode].
     *
     * The only piece of this state that survives a restart. What was *loaded* in the mode deliberately
     * does not: coming back into the mode you left costs nothing to remember and matches where you were,
     * whereas reopening a file by itself is the thing an app should not do — and reloading is one tap.
     */
    val mode = MutableStateFlow(AppMode.CLIP)

    /** Which flow the toolbar play button runs. Not persisted — see [mode]. */
    val currentFlowId = MutableStateFlow<String?>(null)

    /** Which clip is loaded into the workspace; the save key overwrites it by default. */
    val currentClipId = MutableStateFlow<String?>(null)

    /** Whether the user wants the floating toolbar shown. */
    val overlayEnabled = MutableStateFlow(false)

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        mode.value = runCatching { AppMode.valueOf(prefs.getString(KEY_MODE, "").orEmpty()) }
            .getOrDefault(AppMode.CLIP)
        currentClipId.value = prefs.getString(KEY_CURRENT_CLIP, null)
        overlayEnabled.value = prefs.getBoolean(KEY_OVERLAY_ON, false)

        // Deliberately does not read the library. That is a folder over IPC, and doing it here would
        // put it on whichever thread starts the app or binds the service, for a list nothing needs
        // until you open a screen that shows it.
        FolderStore.init(appContext, prefs.getString(FolderStore.prefsKey(), null))
        _settings.value = readSettings()
    }

    val isReady: Boolean get() = ::appContext.isInitialized

    // --- The library ---

    /**
     * Reads the chosen folder. **Does IO — never call this on the main thread.**
     *
     * Whole library at once rather than one kind at a time, because every screen that wants flows
     * also wants clips: a flow row reads "3 clips, 47 actions", and running one needs their steps.
     * Splitting it would only create a state where half the library is present.
     *
     * A file that will not parse is skipped, not fatal. These live in a folder the user opens and
     * copies around, so one mangled file is an ordinary event — and it must cost that one clip
     * rather than the list.
     */
    fun loadLibrary() {
        if (!FolderStore.refreshUsable()) {
            _clips.value = emptyList()
            _flows.value = emptyList()
            libraryLoaded.value = false
            return
        }

        _clips.value = FolderStore.list(FolderStore.Kind.CLIP).mapNotNull { entry ->
            decode<Clip>(entry.json, "clip")?.also { FolderStore.remember(it.id, entry.uri) }
        }
        _flows.value = FolderStore.list(FolderStore.Kind.FLOW).mapNotNull { entry ->
            decode<Flow>(entry.json, "flow")?.also { FolderStore.remember(it.id, entry.uri) }
        }
        libraryLoaded.value = true
    }

    private inline fun <reified T> decode(json: String, what: String): T? = runCatching {
        AppJson.decodeFromString<T>(json)
    }.onFailure { Log.e(TAG, "Skipping an unreadable $what file", it) }.getOrNull()

    /** Forgets the library without touching the folder. Used when the folder changes. */
    private fun clearLibraryCache() {
        _clips.value = emptyList()
        _flows.value = emptyList()
        libraryLoaded.value = false
    }

    // --- Clips ---

    fun clipById(id: String?): Clip? = id?.let { key -> _clips.value.firstOrNull { it.id == key } }

    /**
     * Saves a clip, returning false when the folder would not take it.
     *
     * The result matters and must not be dropped. On a failure the caller keeps the workspace dirty,
     * so the unsaved-draft recovery already holds the data, and the user is asked to pick the folder
     * again — whereas a swallowed failure would report a successful save of nothing.
     */
    fun upsertClip(clip: Clip): Boolean {
        val previousName = clipById(clip.id)?.name
        // Content first, label second. Writing overwrites in place and so keeps the old file name; the
        // rename that follows is cosmetic, which is why its failure is not this function's failure.
        if (!FolderStore.write(FolderStore.Kind.CLIP, clip.id, clip.name, AppJson.encodeToString(clip))) {
            return false
        }
        if (previousName != null && previousName != clip.name) FolderStore.rename(clip.id, clip.name)

        val exists = _clips.value.any { it.id == clip.id }
        _clips.value = if (exists) {
            _clips.value.map { if (it.id == clip.id) clip else it }
        } else {
            _clips.value + clip
        }
        return true
    }

    /**
     * Deletes a clip, and takes it out of every flow that referenced it.
     *
     * Pruned rather than left as a "clip deleted" placeholder, which is what this used to do. A flow with a
     * dangling reference is a flow that looks runnable and is not, and the failure would arrive halfway
     * through a run; removing the row means the flow on screen is the flow that will run. The one thing
     * lost is "I only wanted to point that row at a different clip", which is two actions instead of one.
     */
    fun deleteClip(id: String) {
        FolderStore.delete(id)
        _clips.value = _clips.value.filterNot { it.id == id }

        // One file write per affected flow now, rather than one for all of them. If one fails, that
        // flow keeps a reference to a clip that is gone — and FlowPlan already refuses to run a flow
        // with a hole in it rather than quietly skipping the row, so the failure surfaces where it
        // matters instead of halfway through a replay.
        _flows.value.filter { flow -> flow.clips.any { it.clipId == id } }.forEach { flow ->
            upsertFlow(flow.copy(clips = flow.clips.filterNot { it.clipId == id }))
        }

        if (currentClipId.value == id) setCurrentClip(null)
    }

    // --- Flows ---

    fun flowById(id: String?): Flow? = id?.let { key -> _flows.value.firstOrNull { it.id == key } }

    /** How many flows use this clip. Shown before deleting, since deleting edits those flows. */
    fun flowsUsing(clipId: String): Int =
        _flows.value.count { flow -> flow.clips.any { it.clipId == clipId } }

    /**
     * The loaded flow, or null.
     *
     * No falling back to the first flow, which is what this used to do. Null is a real state — flow mode
     * with nothing picked yet — and a fallback would make the play button run a flow nobody chose.
     */
    fun currentFlow(): Flow? = flowById(currentFlowId.value)

    /**
     * Saves a flow, returning false when the folder would not take it.
     *
     * Deliberately does not load it. Loading a flow clears the workspace, so it has to be something
     * the user asks for — creating or editing one must never throw away an unsaved recording.
     */
    fun upsertFlow(flow: Flow): Boolean {
        val previousName = flowById(flow.id)?.name
        if (!FolderStore.write(FolderStore.Kind.FLOW, flow.id, flow.name, AppJson.encodeToString(flow))) {
            return false
        }
        if (previousName != null && previousName != flow.name) FolderStore.rename(flow.id, flow.name)

        val exists = _flows.value.any { it.id == flow.id }
        _flows.value = if (exists) {
            _flows.value.map { if (it.id == flow.id) flow else it }
        } else {
            _flows.value + flow
        }
        return true
    }

    fun deleteFlow(id: String) {
        FolderStore.delete(id)
        _flows.value = _flows.value.filterNot { it.id == id }
        // Nothing loaded, rather than whichever flow happens to be first: deleting the loaded flow is not
        // a request to run a different one.
        if (currentFlowId.value == id) setCurrentFlow(null)
    }

    // --- The folder ---

    /**
     * Adopts a folder the user picked and reads whatever is in it. **Does IO.**
     *
     * Reading is the whole behaviour, and there is no migration alongside it: pick a folder that
     * already holds a library and that library is yours, pick an empty one and you start empty.
     */
    fun useFolder(uri: android.net.Uri): Boolean {
        clearLibraryCache()
        if (!FolderStore.adopt(uri)) return false
        prefs.edit().putString(FolderStore.prefsKey(), FolderStore.treeUriString()).apply()
        loadLibrary()
        return libraryLoaded.value
    }

    fun forgetFolder() {
        FolderStore.forget()
        prefs.edit().remove(FolderStore.prefsKey()).apply()
        clearLibraryCache()
    }

    // --- Settings ---

    fun updateSettings(transform: (Settings) -> Settings) {
        _settings.value = transform(_settings.value)
        runCatching { settingsFile.writeText(AppJson.encodeToString(_settings.value)) }
            .onFailure { Log.e(TAG, "Failed to write settings", it) }
    }

    fun resetSettings() = updateSettings { Settings.DEFAULT }

    // --- Workspace draft ---
    //
    // All file access lives in this object, so the in-memory workspace (engine/Workspace.kt) calls
    // through here rather than touching filesDir itself.

    fun readWorkspace(): WorkspaceSnapshot = runCatching {
        if (!workspaceFile.exists()) WorkspaceSnapshot()
        else AppJson.decodeFromString<WorkspaceSnapshot>(workspaceFile.readText())
    }.onFailure { Log.e(TAG, "Failed to read workspace draft, starting empty", it) }
        .getOrDefault(WorkspaceSnapshot())

    fun writeWorkspace(snapshot: WorkspaceSnapshot) = write(workspaceFile, snapshot, "workspace draft")

    // --- Preferences ---

    /**
     * Both of these are single writes with no rules attached. The rules about what may be loaded
     * alongside what live in engine/Session.kt, which is the only thing that should be calling them.
     */
    fun setMode(next: AppMode) {
        mode.value = next
        prefs.edit().putString(KEY_MODE, next.name).apply()
    }

    fun setCurrentFlow(id: String?) {
        currentFlowId.value = id
    }

    fun setCurrentClip(id: String?) {
        currentClipId.value = id
        prefs.edit().putString(KEY_CURRENT_CLIP, id).apply()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled.value = enabled
        prefs.edit().putBoolean(KEY_OVERLAY_ON, enabled).apply()
    }

    /** Small scalar preferences, used for remembering where the floating windows were dragged to. */
    fun readInt(key: String, default: Int): Int = prefs.getInt(key, default)

    fun writeInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    // --- I/O ---

    private inline fun <reified T> write(file: File, value: T, what: String) {
        runCatching { file.writeText(AppJson.encodeToString(value)) }
            .onFailure { Log.e(TAG, "Failed to write $what", it) }
    }

    private fun readSettings(): Settings = runCatching {
        if (!settingsFile.exists()) Settings.DEFAULT
        else AppJson.decodeFromString<Settings>(settingsFile.readText())
    }.onFailure { Log.e(TAG, "Failed to read settings, falling back to defaults", it) }
        .getOrDefault(Settings.DEFAULT)
}
