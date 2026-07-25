package com.tapflow.android.engine

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the engine is doing right now.
 *
 * There is deliberately no separate state for "recording paused": stopping a recording just goes
 * back to [IDLE], and pressing record again appends to the workspace, so stop and resume fall out
 * of the append semantics for free. Only the toolbar form differs, which [ToolbarForm] covers.
 */
enum class Mode { IDLE, RECORDING, COUNTDOWN, PLAYING, PAUSED }

/**
 * Which shape the toolbar window currently has.
 *
 * [BALL] is also entered automatically while paused, to clear the on-screen keyboard area — the
 * toolbar sits above TYPE_INPUT_METHOD and would otherwise swallow the Q/A/Z key column.
 *
 * There used to be a third, edge-handle form that the dismiss button collapsed into. It was removed
 * after device testing: a 6dp sliver is not a touch target, and because this state was never reset,
 * a user who hit dismiss could not get the toolbar back by any means — not by toggling the app
 * switch, not by restarting the accessibility service. Dismiss now turns the overlay off outright,
 * which the app switch reflects honestly.
 */
enum class ToolbarForm { EXPANDED, BALL }

data class Progress(val loop: Int, val totalLoops: Int, val step: Int, val totalSteps: Int)

/**
 * Runtime engine state, shared between the accessibility service and the Compose UI.
 *
 * Persisted data lives in Repo; this object holds only what is true for the current run and is
 * intentionally not written to disk.
 */
object EngineState {

    val mode = MutableStateFlow(Mode.IDLE)
    val toolbarForm = MutableStateFlow(ToolbarForm.EXPANDED)

    /** Whether the accessibility service is connected. Drives the onboarding card. */
    val serviceRunning = MutableStateFlow(false)

    /** Set when the toolbar had to fall back to SYSTEM_ALERT_WINDOW, so the UI can explain why. */
    val needsOverlayPermission = MutableStateFlow(false)

    /** Seconds remaining before playback starts. 0 when not counting down. */
    val countdown = MutableStateFlow(0)

    val progress = MutableStateFlow<Progress?>(null)

    /** Message shown while paused. Null when running. */
    val pausePrompt = MutableStateFlow<String?>(null)

    /** Wall-clock time spent actually running, excluding paused time. */
    val elapsedMs = MutableStateFlow(0L)

    val isRecording: Boolean get() = mode.value == Mode.RECORDING

    val isReplaying: Boolean
        get() = mode.value == Mode.PLAYING || mode.value == Mode.PAUSED || mode.value == Mode.COUNTDOWN

    fun reset() {
        mode.value = Mode.IDLE
        countdown.value = 0
        progress.value = null
        pausePrompt.value = null
        elapsedMs.value = 0
    }
}
