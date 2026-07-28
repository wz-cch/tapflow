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

    /**
     * Whether the canvas is in editing mode.
     *
     * Editing has to intercept the whole screen: a window either takes every touch in its bounds or
     * none of them, so markers cannot be draggable while the app underneath stays usable. Making it
     * an explicit mode is the honest version of that constraint.
     */
    val editing = MutableStateFlow(false)

    /**
     * While editing, whether to draw only the selected step's marker.
     *
     * On by default, and reset on every entry into editing. A hundred markers at once is a hundred
     * overlapping crosshairs, and the ninety-nine that are not being changed have no bearing on the
     * one that is. The eye button turns it off for the case where the marker has to be found by sight,
     * because a marker that is not drawn cannot be tapped either.
     */
    val isolateSelection = MutableStateFlow(true)

    /** Step selected for editing, by id. Null when nothing is selected. */
    val selectedStepId = MutableStateFlow<String?>(null)

    /** True while the next canvas tap should be read as "put the selected step here". */
    val pickingCoordinate = MutableStateFlow(false)

    /**
     * Step whose gesture is being captured again, or null.
     *
     * Re-recording one step is the cheap alternative to re-recording a hundred: when one tap landed in
     * the wrong place, redoing that tap should not cost the whole script.
     */
    val reRecordingStepId = MutableStateFlow<String?>(null)

    /** Whether the in-place settings panel is showing. */
    val quickSettingsOpen = MutableStateFlow(false)

    /**
     * Whether the number pad is up. What it is asking for is held by whoever opened it.
     *
     * Transient like everything else here: if the service dies mid-entry the pad is simply gone and
     * nothing was applied, which is the right outcome.
     */
    val numberPadOpen = MutableStateFlow(false)

    /**
     * Whether the step list is up.
     *
     * Editing by marker cannot reach step 47 of 100 — the markers overlap and playback reports a
     * number, not a position. The list is the other way in.
     */
    val stepListOpen = MutableStateFlow(false)

    /** Whether the accessibility service is connected. Drives the onboarding card. */
    val serviceRunning = MutableStateFlow(false)

    /**
     * Set when the service threw while starting up.
     *
     * Shown in the app, because a service that fails here presents as "the toolbar cannot be turned
     * on" with the cause only in logcat — which is no help to anyone holding a phone. Distinguishing
     * "it crashed on startup" from "Android never bound it" is the whole point.
     */
    val serviceError = MutableStateFlow<String?>(null)

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
        editing.value = false
        selectedStepId.value = null
        pickingCoordinate.value = false
        quickSettingsOpen.value = false
    }
}
