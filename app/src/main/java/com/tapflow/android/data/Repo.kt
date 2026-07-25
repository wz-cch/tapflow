package com.tapflow.android.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The single source of truth.
 *
 * MainActivity and TapFlowService live in the same process, so both sides share the StateFlows on
 * this object directly and no IPC is needed.
 */
object Repo {

    private const val TAG = "Repo"
    private const val PREFS = "tapflow"
    private const val KEY_CURRENT_FLOW = "current_flow_id"
    private const val KEY_CURRENT_CLIP = "current_clip_id"
    private const val KEY_OVERLAY_ON = "overlay_on"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val clipsFile: File get() = File(appContext.filesDir, "clips.json")
    private val flowsFile: File get() = File(appContext.filesDir, "flows.json")
    private val settingsFile: File get() = File(appContext.filesDir, "settings.json")
    private val workspaceFile: File get() = File(appContext.filesDir, "workspace.json")

    // --- Persisted data ---

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    private val _flows = MutableStateFlow<List<Flow>>(emptyList())
    val flows: StateFlow<List<Flow>> = _flows.asStateFlow()

    private val _settings = MutableStateFlow(Settings.DEFAULT)
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Which flow the toolbar play button runs. */
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

        currentFlowId.value = prefs.getString(KEY_CURRENT_FLOW, null)
        currentClipId.value = prefs.getString(KEY_CURRENT_CLIP, null)
        overlayEnabled.value = prefs.getBoolean(KEY_OVERLAY_ON, false)

        _clips.value = readList(clipsFile, "clips")
        _flows.value = readList(flowsFile, "flows")
        _settings.value = readSettings()
    }

    val isReady: Boolean get() = ::appContext.isInitialized

    // --- Clips ---

    fun clipById(id: String?): Clip? = id?.let { key -> _clips.value.firstOrNull { it.id == key } }

    fun upsertClip(clip: Clip) {
        val exists = _clips.value.any { it.id == clip.id }
        _clips.value = if (exists) {
            _clips.value.map { if (it.id == clip.id) clip else it }
        } else {
            _clips.value + clip
        }
        write(clipsFile, _clips.value, "clips")
    }

    fun deleteClip(id: String) {
        _clips.value = _clips.value.filterNot { it.id == id }
        write(clipsFile, _clips.value, "clips")

        // Flow nodes referencing this clip are left in place and rendered as "clip deleted" by the
        // UI. They are not auto-removed: the user may only want to point the node at another clip.
        if (currentClipId.value == id) setCurrentClip(null)
    }

    // --- Flows ---

    fun flowById(id: String?): Flow? = id?.let { key -> _flows.value.firstOrNull { it.id == key } }

    fun currentFlow(): Flow? = flowById(currentFlowId.value) ?: _flows.value.firstOrNull()

    fun upsertFlow(flow: Flow) {
        val exists = _flows.value.any { it.id == flow.id }
        _flows.value = if (exists) {
            _flows.value.map { if (it.id == flow.id) flow else it }
        } else {
            _flows.value + flow
        }
        if (currentFlowId.value == null) setCurrentFlow(flow.id)
        write(flowsFile, _flows.value, "flows")
    }

    fun deleteFlow(id: String) {
        _flows.value = _flows.value.filterNot { it.id == id }
        if (currentFlowId.value == id) setCurrentFlow(_flows.value.firstOrNull()?.id)
        write(flowsFile, _flows.value, "flows")
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

    fun setCurrentFlow(id: String?) {
        currentFlowId.value = id
        prefs.edit().putString(KEY_CURRENT_FLOW, id).apply()
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

    private inline fun <reified T> readList(file: File, what: String): List<T> = runCatching {
        if (!file.exists()) emptyList() else AppJson.decodeFromString<List<T>>(file.readText())
    }.onFailure { Log.e(TAG, "Failed to read $what, falling back to empty list", it) }
        .getOrDefault(emptyList())

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
