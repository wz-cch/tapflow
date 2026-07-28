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

/**
 * @param repeatPass which repetition of the current step is running, 1-based.
 * @param repeatTotal how many repetitions that step has. 1 for an ordinary step, which is why the
 *   transport only shows this pair when it is above 1 — a step repeated ten times with a gap between
 *   each otherwise sits on the same number for ten seconds and reads as frozen.
 */
data class Progress(
    val loop: Int,
    val totalLoops: Int,
    val step: Int,
    val totalSteps: Int,
    val repeatPass: Int = 1,
    val repeatTotal: Int = 1,
)

/**
 * What the next captured gesture is for, when it is not simply being recorded.
 *
 * There is one capture path — the canvas intercepts the screen and reports strokes — and three things
 * worth doing with the result. Recording appends with the gap measured off the clock. The two editing
 * dispositions take the default gap or keep the existing one, and neither replays the gesture
 * downwards: per-gesture replay exists so the app under a recording walks forward with you, and while
 * editing there is no such walk to keep in step with.
 *
 * Adding a step, re-recording one, and recording are therefore the same act with three destinations,
 * which is why they share [com.tapflow.android.overlay.CanvasView]'s capture wholesale.
 */
sealed interface Capture {
    /**
     * Insert a new step immediately after [afterId], or at the end when it is null.
     *
     * The only insertion direction there is. Reaching the front is insert-then-move — see
     * [Workspace.moveStep].
     */
    data class InsertAfter(val afterId: String?) : Capture

    /** Replace [stepId]'s strokes, keeping its id and its lead delay. */
    data class Replace(val stepId: String) : Capture
}

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
     *
     * Isolation means exactly one marker, with no exceptions: the selected one. It needs no fallback
     * because [selectedStepId] has none either — see there.
     */
    val isolateSelection = MutableStateFlow(true)

    /**
     * Step selected for editing, by id.
     *
     * While editing this is never null for a non-empty workspace: it defaults to the last step, and a
     * deletion moves it to whatever is last afterwards. "Nothing selected" was a state that only ever
     * produced special cases — isolation had nothing to isolate to, insert and delete had no anchor,
     * and the settings panel had nothing to show — and the last step exists whenever the workspace
     * does, so the case is simply gone. The service resolves it on every refresh.
     *
     * Null outside editing, and while recording in particular: recorded steps append to the end, and
     * insertion keys off the selection.
     */
    val selectedStepId = MutableStateFlow<String?>(null)

    /**
     * What the next captured gesture should become, or null while it should just be recorded.
     *
     * Non-null puts the canvas into full-screen interception with the recording tint, because that is
     * honestly what the next touch does.
     */
    val pendingCapture = MutableStateFlow<Capture?>(null)

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

    /**
     * Whether the step settings panel is up.
     *
     * Explicitly asked for rather than implied by the selection. The panel used to appear on every
     * selection, which meant it was in the way for the most common editing act there is — dragging a
     * marker — and now that a selection always exists it would simply never be down.
     *
     * Mutually exclusive with [stepListOpen]: both are large centred windows, and two of them at once
     * is the overlap that made the first attempt at this unusable.
     */
    val paramPanelOpen = MutableStateFlow(false)

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
        pendingCapture.value = null
        quickSettingsOpen.value = false
        paramPanelOpen.value = false
        stepListOpen.value = false
    }
}
