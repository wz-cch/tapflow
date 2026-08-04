package com.tapflow.android.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.tapflow.android.MainActivity
import com.tapflow.android.WorkspaceDialogActivity
import com.tapflow.android.R
import com.tapflow.android.data.AppMode
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.GlobalKind
import com.tapflow.android.data.GlobalStep
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.RepeatableStep
import com.tapflow.android.data.Repo
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Settings
import com.tapflow.android.data.Step
import com.tapflow.android.data.Stroke
import com.tapflow.android.data.movedTo
import com.tapflow.android.data.newId
import com.tapflow.android.data.withDuration
import com.tapflow.android.data.withRepeat
import com.tapflow.android.data.withStartAt
import com.tapflow.android.data.withEndAt
import com.tapflow.android.overlay.BallIntent
import com.tapflow.android.overlay.CanvasMode
import com.tapflow.android.overlay.CanvasView
import com.tapflow.android.overlay.Handle
import com.tapflow.android.overlay.NumberPadView
import com.tapflow.android.overlay.OptionPadView
import com.tapflow.android.overlay.OverlayHost
import com.tapflow.android.overlay.QuickSettingsView
import com.tapflow.android.overlay.StepListView
import com.tapflow.android.overlay.StepPanelView
import com.tapflow.android.overlay.ToolbarView
import com.tapflow.android.overlay.TransportView
import com.tapflow.android.overlay.buildMarkers
import com.tapflow.android.text.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The only thing that actually does anything.
 *
 * Everything lives here rather than in a foreground service because an accessibility service is
 * bound by the system and stays alive as long as its switch is on, it is the only component allowed
 * to call dispatchGesture, and it is the only one that can attach TYPE_ACCESSIBILITY_OVERLAY
 * windows — which is what lets the app work with no permissions beyond the accessibility toggle.
 */
class TapFlowService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapFlowService? = null
            private set

        private const val PREF_TOOLBAR_X = "toolbar_x"
        private const val PREF_TOOLBAR_Y = "toolbar_y"
        private const val PREF_TRANSPORT_X = "transport_x"
        private const val PREF_TRANSPORT_Y = "transport_y"

        /** How long the volume-down key has to be held to stop instead of pause. */
        private const val VOLUME_LONG_PRESS_MS = 1000L

        private const val TAG = "TapFlowService"

        /** Minimum gap between "gesture rejected" toasts. */
        private const val GESTURE_WARNING_INTERVAL_MS = 4000L

        /** An hour. Long enough for any real use, short enough that a mistyped digit is obvious. */
        private const val MAX_WAIT_SECONDS = 3600

        /** How much of the screen the settings panel may take before its rows start scrolling. */
        private const val PANEL_MAX_HEIGHT_FRACTION = 0.8f

        /** Enough for any real use, and low enough that a stray extra digit does not mean 1000 taps. */
        private const val MAX_REPEAT = 999
    }

    /**
     * Recreated on every connect.
     *
     * A service instance can be reconnected after onUnbind, and teardown cancels the scope. A
     * cancelled scope drops every collector without a word, which left the overlay unable to react
     * to the app switch for the rest of the process lifetime.
     */
    private var scope = createScope()

    private lateinit var host: OverlayHost
    private lateinit var canvas: CanvasView
    private lateinit var toolbar: ToolbarView
    private lateinit var transport: TransportView
    private lateinit var stepPanel: StepPanelView
    private lateinit var quickSettings: QuickSettingsView
    private lateinit var numberPad: NumberPadView
    private lateinit var optionPad: OptionPadView
    private lateinit var stepList: StepListView

    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var toolbarParams: WindowManager.LayoutParams
    private lateinit var transportParams: WindowManager.LayoutParams
    private lateinit var stepPanelParams: WindowManager.LayoutParams
    private lateinit var quickSettingsParams: WindowManager.LayoutParams
    private lateinit var numberPadParams: WindowManager.LayoutParams
    private lateinit var optionPadParams: WindowManager.LayoutParams
    private lateinit var stepListParams: WindowManager.LayoutParams

    private lateinit var dispatcher: GestureDispatcher
    private lateinit var recorder: Recorder
    private lateinit var player: Player

    private var volumeLongPressJob: Job? = null

    private var lastGestureWarningAt = 0L
    private var consecutiveGestureFailures = 0

    /** Re-registration is worth one attempt per binding, not one per gesture. */
    private var registrationRenewed = false

    private val settings: Settings get() = Repo.settings.value

    // --- Lifecycle -----------------------------------------------------------

    private fun createScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Wrapped in runCatching on purpose.
     *
     * An exception thrown out of here kills the service, and Android responds by switching the
     * accessibility service off — which the user experiences as the toolbar becoming permanently
     * impossible to turn on, with nothing on screen to explain it. Better to log, come up degraded,
     * and let the app report that the service is not running.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        EngineState.serviceError.value = null
        runCatching { connect() }.onFailure { failure ->
            Log.e(TAG, "onServiceConnected failed", failure)
            EngineState.serviceError.value = summarise(failure)
        }
    }

    private fun connect() {
        instance = this

        scope.cancel()
        scope = createScope()

        Repo.init(this)

        // Each step is isolated so one bad piece of saved state cannot stop the service coming up.
        // Whatever fails is reported in the app rather than only in logcat.
        // Both, because the service can reconnect without the process dying and the question belongs to
        // this startup rather than the last one.
        recoveryAsked = false
        step("restore workspace") { recoveredDraft = Workspace.restore() }

        // Deliberately nothing here touches serviceInfo.
        //
        // flagRequestFilterKeyEvents is already declared in accessibility_service_config.xml, so
        // setting it again at runtime bought nothing and carried two real risks. setServiceInfo makes
        // the system recompute its user state, and that recompute is when the accessibility input
        // filter — which owns the MotionEventInjector that dispatchGesture needs — gets installed;
        // poking it during binding is a way to race that installation. And if the getter ever returned
        // null, the fallback built a blank AccessibilityServiceInfo whose capabilities are zero, which
        // would drop canPerformGestures altogether. A race matches the symptom: fine most of the time,
        // then stuck once it goes wrong, surviving reinstalls of any version.

        buildOverlay()

        EngineState.serviceRunning.value = true

        // The collector fires with the current value straight away, so a toolbar that was on last
        // session comes back without a second explicit attach call.
        observe()
    }

    /**
     * Runs a startup step, recording rather than propagating a failure.
     *
     * Used for the parts that are not worth aborting for. Restoring a draft that will not parse, for
     * instance, should cost the draft and not the whole service.
     */
    private inline fun step(what: String, block: () -> Unit) {
        runCatching(block).onFailure {
            Log.e(TAG, "Startup step failed: $what", it)
            EngineState.serviceError.value = "$what — ${summarise(it)}"
        }
    }

    /** Exception class, message and the first frame in our own code. Enough to act on. */
    private fun summarise(failure: Throwable): String {
        val frame = failure.stackTrace.firstOrNull { it.className.startsWith("com.tapflow") }
        return buildString {
            append(failure.javaClass.simpleName)
            failure.message?.let { append(": ").append(it) }
            if (frame != null) {
                append(" @ ").append(frame.className.substringAfterLast('.'))
                append('.').append(frame.methodName).append(':').append(frame.lineNumber)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance !== this) return
        instance = null
        EngineState.serviceRunning.value = false

        // onServiceConnected may never have completed, in which case there is nothing built yet.
        if (::canvas.isInitialized) {
            stopRecording(collapseForInput = false)
            player.stop()
            host.removeAll()
        }

        EngineState.reset()
        scope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing to do yet. Window content is not retrieved in M1; that arrives with the
        // wait-for-text node in M4.
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Coordinates recorded before the rotation would not line up with anything afterwards, so
        // there is nothing sensible to do but stop and say so.
        if (EngineState.isRecording) {
            stopRecording(collapseForInput = false)
            toast(getString(R.string.warn_rotation_changed))
        }
        clampWindows()
        syncOverlay()
    }

    // --- Volume key fallback -------------------------------------------------

    /**
     * Volume down pauses and resumes; holding it stops.
     *
     * Only intercepted while replaying, so normal volume control still works the rest of the time.
     * This exists because the toolbar can end up under another window or dragged somewhere awkward,
     * and there has to be a way out that does not depend on hitting a floating button.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!EngineState.isReplaying) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                volumeLongPressJob = scope.launch {
                    delay(VOLUME_LONG_PRESS_MS)
                    player.stop()
                    volumeLongPressJob = null
                }
            }

            KeyEvent.ACTION_UP -> {
                val stillPending = volumeLongPressJob?.isActive == true
                volumeLongPressJob?.cancel()
                volumeLongPressJob = null
                if (stillPending) player.togglePause()
            }
        }
        return true
    }

    // --- Overlay construction ------------------------------------------------

    private fun buildOverlay() {
        host = OverlayHost(this)
        dispatcher = GestureDispatcher(
            service = this,
            report = { outcome -> onGestureOutcome(outcome) },
            renewRegistration = { renewRegistration() },
        )

        canvas = CanvasView(this).apply {
            onGesture = { strokes, down, up -> onGestureCaptured(strokes, down, up) }
            onSelect = { stepId -> selectFromCanvas(stepId) }
            onDragStep = { stepId, handle, x, y -> dragStep(stepId, handle, x, y) }
            onDragEnd = { Workspace.flush() }
            onReplayEcho = { onReplayEcho() }
        }
        toolbar = ToolbarView(this, ToolbarActions())
        transport = TransportView(this, TransportActions())
        stepPanel = StepPanelView(this, StepPanelActions())
        quickSettings = QuickSettingsView(this, QuickSettingsActions())
        numberPad = NumberPadView(this) { EngineState.numberPadOpen.value = false }
        optionPad = OptionPadView(this) { EngineState.optionPadOpen.value = false }
        stepList = StepListView(this, StepListActions())

        canvasParams = host.params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        toolbarParams = host.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        transportParams = host.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        stepPanelParams = host.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        quickSettingsParams = host.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        numberPadParams = host.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        optionPadParams = host.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        stepListParams = host.params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )

        val size = host.displaySize()
        toolbarParams.x = Repo.readInt(PREF_TOOLBAR_X, 0)
        toolbarParams.y = Repo.readInt(PREF_TOOLBAR_Y, size.y / 4)
        transportParams.x = Repo.readInt(PREF_TRANSPORT_X, size.x / 4)
        transportParams.y = Repo.readInt(PREF_TRANSPORT_Y, (24 * resources.displayMetrics.density).toInt())

        recorder = Recorder(
            scope = scope,
            dispatcher = dispatcher,
            canvas = canvas,
            setCanvasTouchable = { touchable -> setCanvasTouchable(touchable) },
            currentScreen = { currentScreen() },
            settings = { settings },
        )

        player = Player(
            scope = scope,
            dispatcher = dispatcher,
            resources = resources,
            currentScreen = { currentScreen() },
            settings = { settings },
            // Said once at the end rather than per step, because "skip" was chosen to avoid being
            // interrupted. Saying nothing at all is the part that is not on offer: skipping is fine while
            // you believe the taps happened, and a run that skipped most of a script and then reported
            // finishing is exactly the shape of failure that reads as success.
            onFinished = { skipped ->
                if (skipped > 0) toast(getString(R.string.toast_skipped_steps, skipped))
            },
        )

        // Clamping in attachOverlay happens before either view has been measured, so a position
        // saved on a wider screen (or before a rotation) can still be parked offscreen. Re-clamp
        // once the real sizes are known. Guarded on an actual change so this cannot loop.
        val onMeasured = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (clampWindows()) {
                host.update(toolbar, toolbarParams)
                host.update(transport, transportParams)
            }
        }
        toolbar.addOnLayoutChangeListener(onMeasured)
        transport.addOnLayoutChangeListener(onMeasured)
    }

    private fun attachOverlay() {
        // Always come back expanded. Turning the switch on is a request to see the toolbar, and a
        // collapsed state carried over from last time reads as "the switch does nothing".
        EngineState.toolbarForm.value = ToolbarForm.EXPANDED
        toolbar.ballIntent = BallIntent.EXPAND

        // The canvas is attached on demand, not here — see syncCanvasAttachment.
        host.add(transport, transportParams)

        if (!host.add(toolbar, toolbarParams)) {
            // Silence here would look identical to the switch being broken.
            toast(getString(R.string.toast_overlay_failed))
            Repo.setOverlayEnabled(false)
            return
        }

        // A window dragged to the edge on a larger screen, or before a rotation, can land offscreen.
        clampWindows()
        syncOverlay()
    }

    private fun detachOverlay() {
        stopRecording(collapseForInput = false)
        player.stop()
        exitEditing()
        // Every window explicitly, not just the ones a refresh would take down. Switching the overlay off
        // detaches the toolbar, and syncOverlay returns early without it — so anything left attached here
        // would stay on screen with nothing able to remove it.
        EngineState.numberPadOpen.value = false
        EngineState.optionPadOpen.value = false
        host.remove(numberPad)
        host.remove(optionPad)
        host.remove(quickSettings)
        host.remove(stepPanel)
        host.remove(stepList)
        host.remove(toolbar)
        host.remove(transport)
        host.remove(canvas)
    }

    private fun observe() {
        scope.launch {
            Repo.overlayEnabled.collect { enabled ->
                if (enabled) attachOverlay() else detachOverlay()
            }
        }
        scope.launch { Workspace.steps.collect { syncOverlay() } }
        // Both, not just the mode: flow mode with nothing open is a real state, and which one it is
        // decides whether play and the flow buttons are live.
        scope.launch { Repo.openFlow.collect { syncOverlay() } }
        scope.launch { Repo.mode.collect { syncOverlay() } }
        scope.launch { Repo.settings.collect { syncOverlay() } }
        scope.launch { EngineState.mode.collect { syncOverlay() } }
        scope.launch { EngineState.toolbarForm.collect { syncOverlay() } }
        scope.launch { EngineState.editing.collect { syncOverlay() } }
        scope.launch { EngineState.selectedStepId.collect { syncOverlay() } }
        scope.launch { EngineState.quickSettingsOpen.collect { syncOverlay() } }
        scope.launch { EngineState.numberPadOpen.collect { syncOverlay() } }
        scope.launch { EngineState.optionPadOpen.collect { syncOverlay() } }
        scope.launch { EngineState.isolateSelection.collect { syncOverlay() } }
        scope.launch { EngineState.stepListOpen.collect { syncOverlay() } }
        scope.launch { EngineState.paramPanelOpen.collect { syncOverlay() } }
        scope.launch { EngineState.pendingCapture.collect { syncOverlay() } }
        scope.launch {
            EngineState.progress.collect { progress ->
                // Updated here rather than in syncOverlay so the highlight follows playback without
                // rebuilding every marker on each step.
                canvas.highlightNumber = progress?.step
                syncTransport()
            }
        }
        scope.launch { EngineState.countdown.collect { syncTransport() } }
        scope.launch { EngineState.elapsedMs.collect { syncTransport() } }
        scope.launch { EngineState.pausePrompt.collect { syncTransport() } }
    }

    // --- Rendering -----------------------------------------------------------

    /**
     * Whether startup brought back unsaved steps, and whether that has been raised yet.
     *
     * Every deliberate way out empties the workspace, so a dirty draft on disk means the last process
     * died — which is the whole detection mechanism, no flag written anywhere.
     */
    private var recoveredDraft = false
    private var recoveryAsked = false

    /**
     * Asks once, the first time the toolbar is actually on screen.
     *
     * Not at startup: the service can connect long before anything is visible, and a question nobody can
     * see is a question that gets answered by whatever happens next. Not in the app either — the workspace
     * is only reachable through the toolbar, so this is where it can be acted on.
     *
     * Doing nothing on "keep" is correct: the steps are already in memory. Ignoring the question entirely
     * is also safe for the same reason, which is why it is asked after restoring rather than before.
     */
    private fun askAboutRecoveredDraft() {
        if (!recoveredDraft || recoveryAsked) return
        if (EngineState.optionPadOpen.value || EngineState.numberPadOpen.value) return
        recoveryAsked = true
        openOptionPad(
            OptionRequest(
                title = getString(R.string.recover_title),
                labels = listOf(getString(R.string.recover_keep), getString(R.string.recover_discard)),
            ) { index -> if (index == 1) Session.startFresh() }
        )
    }

    private fun syncOverlay() {
        if (!host.isAttached(toolbar)) return

        resolveSelection()

        val current = settings
        val steps = Workspace.steps.value
        val mode = EngineState.mode.value
        val editing = EngineState.editing.value
        val selectedId = EngineState.selectedStepId.value

        val screen = host.displaySize()

        toolbar.applyAppearance(current.uiScale, current.uiOpacity)
        // Recomputed here so a rotation, or a change of scale, re-caps the scrolling area.
        toolbar.setAvailableHeight(screen.y - dpToPx(32f).toInt())
        toolbar.render(
            mode = mode,
            form = EngineState.toolbarForm.value,
            workspaceSize = steps.size,
            density = current.markerDensity,
            editing = editing,
            hasSelection = selectedId != null,
            quickSettingsOpen = EngineState.quickSettingsOpen.value,
            canUndo = Workspace.canUndo,
            isolateSelection = EngineState.isolateSelection.value,
            stepListOpen = EngineState.stepListOpen.value,
            stepPanelOpen = EngineState.paramPanelOpen.value,
            flowMode = flowMode,
            hasFlow = Repo.openFlow.value != null,
            insideFlow = Session.returnToFlowRef != null,
        )
        host.update(toolbar, toolbarParams)

        askAboutRecoveredDraft()

        // One figure drives the drawn ring, the grab radius and the separation two ends need before both
        // get a ring of their own — see Settings.editHandleDp. Keeping them derived from one another is
        // what stops a ring from advertising a radius the hit test does not honour.
        val handleRadius = dpToPx(current.editHandleDp / 2f)
        canvas.markers = buildMarkers(
            steps,
            screen.x.toFloat(),
            screen.y.toFloat(),
            resources.displayMetrics.density,
            endHandleMinPx = handleRadius * 2f,
        )
        canvas.handleRadiusPx = handleRadius
        // Editing shows only the selected step by default. With a hundred markers up, "show
        // everything" is a hundred overlapping crosshairs and the other ninety-nine have no bearing on
        // the one being changed. The eye button still widens it when the marker has to be found by
        // sight — and until now that button did nothing at all while editing, because this line forced
        // ALL over whatever it had just set.
        canvas.density = current.markerDensity
        canvas.isolateSelection = editing && EngineState.isolateSelection.value
        canvas.stepLines = stepLines(steps)
        canvas.highlightNumber = EngineState.progress.value?.step
        canvas.selectedStepId = selectedId
        canvas.panelOpen = EngineState.paramPanelOpen.value
        canvas.dimAlpha = if (current.dimOverlay && mode == Mode.PLAYING) current.dimAlpha else 0f
        canvas.mode = when {
            mode == Mode.RECORDING -> CanvasMode.RECORDING
            // Capturing a gesture for an edit needs the same full-screen interception as recording, and
            // the recording tint is honest about what the next touch will do.
            EngineState.pendingCapture.value != null -> CanvasMode.RECORDING
            editing -> CanvasMode.EDIT
            else -> CanvasMode.READ_ONLY
        }
        updateCanvasFlags()
        syncCanvasAttachment()

        syncStepPanel()
        syncQuickSettings()
        syncStepList()
        syncNumberPad()
        syncOptionPad()
        syncTransport()
    }

    /**
     * Editing always has exactly one step selected.
     *
     * Defaults to the last step and follows a deletion backwards, so the selection is valid whenever the
     * workspace is non-empty. That removes "nothing is selected" as a state rather than handling it:
     * isolation always has a marker to show, insert and delete always have an anchor, and the settings
     * panel always has something to open on.
     *
     * Called at the top of every refresh rather than at each mutation site. Setting the flow here starts
     * one more pass, which finds the selection already resolved and stops — cheaper to reason about than
     * remembering to do it in the eight places that change the step list.
     */
    private fun resolveSelection() {
        if (!EngineState.editing.value) return
        val steps = Workspace.steps.value
        if (steps.isEmpty()) {
            EngineState.selectedStepId.value = null
            return
        }
        if (steps.none { it.id == EngineState.selectedStepId.value }) {
            EngineState.selectedStepId.value = steps.last().id
        }
    }

    private fun syncQuickSettings() {
        if (!EngineState.quickSettingsOpen.value) {
            host.remove(quickSettings)
            return
        }

        quickSettings.render(settings)
        if (!host.isAttached(quickSettings)) {
            val size = host.displaySize()
            // Roughly centred, biased upwards so the toolbar down one edge stays visible next to it.
            quickSettingsParams.x = (size.x * 0.5f - dpToPx(150f)).toInt().coerceAtLeast(0)
            quickSettingsParams.y = (size.y * 0.12f).toInt()
            host.add(quickSettings, quickSettingsParams)
        }
        host.update(quickSettings, quickSettingsParams)
    }

    /** What the number pad is currently asking for. Null whenever it is closed. */
    private class PadRequest(
        val title: String,
        val unit: String,
        val initialValue: Int,
        val max: Int,
        val confirm: (Int) -> Unit,
    )

    private var padRequest: PadRequest? = null

    /**
     * Opens the number pad for one question.
     *
     * The pad itself knows nothing about what the number means, so each caller supplies its own label
     * and handler. Two use it: how many seconds a wait should last, and which step to jump to.
     */
    private fun openNumberPad(request: PadRequest) {
        padRequest = request
        EngineState.numberPadOpen.value = true
    }

    /**
     * The number pad.
     *
     * Attached and detached rather than hidden, like the parameter card, so it is only over the screen
     * while it is actually being used.
     */
    private fun syncNumberPad() {
        val request = padRequest
        if (!EngineState.numberPadOpen.value || request == null) {
            host.remove(numberPad)
            padRequest = null
            return
        }

        numberPad.applyAppearance(settings.uiScale, settings.uiOpacity)
        if (!host.isAttached(numberPad)) {
            val size = host.displaySize()
            // Centred horizontally, in the upper half. Recording is a common caller, and the lower half
            // is where the thing being recorded tends to be.
            numberPadParams.x = (size.x * 0.5f - dpToPx(105f)).toInt().coerceAtLeast(0)
            numberPadParams.y = (size.y * 0.16f).toInt()
            numberPad.open(request.title, request.unit, request.initialValue, request.max) { value ->
                EngineState.numberPadOpen.value = false
                request.confirm(value)
            }
            host.add(numberPad, numberPadParams)
        }
        host.update(numberPad, numberPadParams)
    }

    /** What the option pad is currently asking. Null whenever it is closed. */
    private class OptionRequest(
        val title: String,
        val labels: List<String>,
        val pick: (Int) -> Unit,
    )

    private var optionRequest: OptionRequest? = null

    private fun openOptionPad(request: OptionRequest) {
        optionRequest = request
        EngineState.optionPadOpen.value = true
    }

    /**
     * The option pad, for an insertion that has to ask which kind first.
     *
     * Attached and detached like the number pad, and positioned the same way — centred horizontally in
     * the upper half, because the lower half is where whatever is being recorded tends to be.
     */
    private fun syncOptionPad() {
        val request = optionRequest
        if (!EngineState.optionPadOpen.value || request == null) {
            host.remove(optionPad)
            optionRequest = null
            return
        }

        optionPad.applyAppearance(settings.uiScale, settings.uiOpacity)
        if (!host.isAttached(optionPad)) {
            val size = host.displaySize()
            optionPadParams.x = (size.x * 0.5f - dpToPx(120f)).toInt().coerceAtLeast(0)
            optionPadParams.y = (size.y * 0.16f).toInt()
            optionPad.open(request.title, request.labels) { index ->
                EngineState.optionPadOpen.value = false
                request.pick(index)
            }
            host.add(optionPad, optionPadParams)
        }
        host.update(optionPad, optionPadParams)
    }

    /**
     * The step list: a large centred window, up only while editing and only when asked for.
     *
     * Sized and positioned on every refresh rather than once on attach, so a rotation re-centres it.
     * Nothing here is remembered between openings, which is the point — it is not a floating panel to
     * be parked somewhere, it is a page you open, pick from, and leave.
     */
    private fun syncStepList() {
        // Down while a gesture is being captured, for the same reason the settings panel is: the capture
        // takes the whole screen, and a window over it is in the way of the gesture it asked for.
        val wanted = EngineState.editing.value && EngineState.stepListOpen.value &&
            EngineState.pendingCapture.value == null
        if (!wanted) {
            host.remove(stepList)
            return
        }

        val steps = Workspace.steps.value
        stepList.applyAppearance(settings.uiScale, settings.uiOpacity)
        stepList.render(
            lines = stepLines(steps),
            selected = steps.indexOfFirst { it.id == EngineState.selectedStepId.value },
        )
        centreLargeWindow(stepListParams, heightFraction = 0.66f)
        if (!host.isAttached(stepList)) host.add(stepList, stepListParams)
        host.update(stepList, stepListParams)
    }

    /**
     * Centres one of the two large editing windows.
     *
     * Both are wide, centred and mutually exclusive, which is what stopped them covering each other.
     * Capped in dp as well as by the screen, so a tablet gets a readable column rather than one stretched
     * across ten inches.
     *
     * Centred by window gravity rather than by computing a top-left corner. A wrap-content window's
     * height is not known until it has been measured, so working out its y would mean either guessing or
     * re-clamping after layout — and `Gravity.CENTER` lets the window manager do it exactly.
     *
     * @param heightFraction fraction of the screen height to take, or null to wrap the content. The
     *   wrapping case relies on the view capping its own height; see StepPanelView.setAvailableHeight.
     */
    private fun centreLargeWindow(
        params: WindowManager.LayoutParams,
        heightFraction: Float? = null,
    ) {
        val size = host.displaySize()
        params.gravity = android.view.Gravity.CENTER
        params.x = 0
        params.y = 0
        params.width = min(size.x - dpToPx(24f).toInt(), dpToPx(400f).toInt())
        params.height = heightFraction
            ?.let { (size.y * it).toInt() }
            ?: WindowManager.LayoutParams.WRAP_CONTENT
    }

    /**
     * Keeps the full-screen canvas attached only while it is actually needed.
     *
     * FLAG_NOT_TOUCHABLE lets touches pass through, but it does not stop the window counting as an
     * obscuring one. While it is attached, every touch every app on the device receives carries
     * FLAG_WINDOW_IS_OBSCURED — and a view with filterTouchesWhenObscured set simply discards those.
     * Apps that do this become completely unresponsive, to the user's own finger as much as to an
     * injected gesture, merely because the toolbar is switched on. Launchers do not set it, which is
     * why this looks like "works on the home screen, dead inside an app".
     *
     * So the canvas goes up to intercept (recording, editing) or when the user has explicitly asked
     * for something painted over everything, and comes down otherwise.
     */
    private fun syncCanvasAttachment() {
        val needed = canvasNeeded()
        if (needed == host.isAttached(canvas)) return

        Diag.log("canvas ${if (needed) "attach" else "detach"} (mode=${EngineState.mode.value})")
        if (needed) {
            host.add(canvas, canvasParams)
            // Same-type overlays stack in the order they were added, so everything else has to be
            // re-added above the canvas or its buttons become unreachable.
            restackAboveCanvas()
        } else {
            host.remove(canvas)
        }
    }

    private fun canvasNeeded(): Boolean {
        if (EngineState.mode.value == Mode.RECORDING) return true
        if (EngineState.editing.value) return true
        if (settings.dimOverlay && EngineState.mode.value == Mode.PLAYING) return true
        return settings.showMarkersWhenIdle && Workspace.steps.value.isNotEmpty()
    }

    private fun restackAboveCanvas() {
        host.bringToFront(transport, transportParams)
        if (host.isAttached(stepPanel)) host.bringToFront(stepPanel, stepPanelParams)
        if (host.isAttached(quickSettings)) host.bringToFront(quickSettings, quickSettingsParams)
        if (host.isAttached(stepList)) host.bringToFront(stepList, stepListParams)
        if (host.isAttached(numberPad)) host.bringToFront(numberPad, numberPadParams)
        if (host.isAttached(optionPad)) host.bringToFront(optionPad, optionPadParams)
        host.bringToFront(toolbar, toolbarParams)
    }

    private fun dpToPx(dp: Float) = dp * resources.displayMetrics.density

    /**
     * Rounds to a multiple of [step] before clamping.
     *
     * Repeated float addition drifts, and a value like 0.7000001 fails an exact comparison against
     * a range end and shows up as "0.7" that will not move any further.
     */
    private fun snap(value: Float, step: Float, range: ClosedFloatingPointRange<Float>): Float {
        val snapped = (value / step).roundToInt() * step
        return snapped.coerceIn(range.start, range.endInclusive)
    }

    /**
     * The step settings panel: up only when explicitly opened, and never while a gesture is being
     * captured for it — the capture takes the whole screen, and the panel would be in the way of the
     * very gesture it asked for.
     *
     * Detaching rather than hiding also keeps it from counting as an obscuring window while it is down.
     */
    private fun syncStepPanel() {
        val steps = Workspace.steps.value
        val step = Workspace.stepById(EngineState.selectedStepId.value)
        val wanted = EngineState.editing.value && EngineState.paramPanelOpen.value &&
            step != null && EngineState.pendingCapture.value == null

        if (!wanted) {
            host.remove(stepPanel)
            return
        }

        stepPanel.applyAppearance(settings.uiScale, settings.uiOpacity)
        stepPanel.setAvailableHeight((host.displaySize().y * PANEL_MAX_HEIGHT_FRACTION).toInt())
        stepPanel.render(
            step = step!!,
            number = steps.indexOfFirst { it.id == step.id } + 1,
            total = steps.size,
        )
        centreLargeWindow(stepPanelParams)
        if (!host.isAttached(stepPanel)) host.add(stepPanel, stepPanelParams)
        host.update(stepPanel, stepPanelParams)
    }

    private fun syncTransport() {
        if (!host.isAttached(transport)) return
        val current = settings
        val mode = EngineState.mode.value

        // Exactly the modes where the toolbar hides itself, so the two windows are never both up.
        val visible = EngineState.isReplaying
        transport.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        transport.applyAppearance(current.uiScale, current.uiOpacity)
        transport.render(
            mode = mode,
            progress = EngineState.progress.value
                ?: Progress(0, 0, Workspace.size, Workspace.size).takeIf { mode == Mode.RECORDING },
            countdown = EngineState.countdown.value,
            elapsedMs = EngineState.elapsedMs.value,
            showTimer = current.showTimer,
            pausePrompt = EngineState.pausePrompt.value,
        )
        host.update(transport, transportParams)
    }

    private fun stepLines(steps: List<Step>): List<String> =
        steps.mapIndexed { index, step -> "${index + 1}  ${step.label(resources)}" }


    private fun updateCanvasFlags() {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val intercepting = when (canvas.mode) {
            // Never while a captured gesture is being replayed. The canvas would otherwise swallow
            // the event it just injected and the app below would see nothing at all.
            //
            // Keyed off canvas.replaying and nothing else. An earlier attempt tied this to an
            // optional "move the overlay aside" setting, and because that setting defaulted off the
            // flag was never applied and per-gesture replay did nothing anywhere.
            CanvasMode.RECORDING -> !canvas.replaying
            CanvasMode.EDIT -> true
            CanvasMode.READ_ONLY -> false
        }
        if (!intercepting) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (settings.keepScreenOn && EngineState.mode.value != Mode.IDLE) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

        // The window is never moved or resized around a replay. Shrinking it shifted the whole
        // coordinate space sideways, and moving it made the system cancel the gesture in flight —
        // both broke recording everywhere in exchange for helping apps that discard obscured
        // touches, which was never actually shown to work. Those apps stay a documented limitation.
        if (canvasParams.flags == flags) return
        canvasParams.flags = flags
        host.update(canvas, canvasParams)
    }

    private fun setCanvasTouchable(touchable: Boolean) {
        canvas.replaying = !touchable && canvas.mode == CanvasMode.RECORDING
        updateCanvasFlags()
    }

    // --- Recording -----------------------------------------------------------

    private fun startRecording() {
        if (EngineState.isReplaying || EngineState.isRecording) return
        Diag.clear()
        Diag.log("record start")

        // Recording belongs to clip mode. The button is not on the flow toolbar at all, so this guards a
        // second route in — the ball's resume-recording tap — rather than something the user can ask for.
        // It returns instead of switching mode, because switching silently here is precisely the hidden
        // transition the mode button was added to replace.
        if (flowMode) return

        exitEditing()
        EngineState.toolbarForm.value = ToolbarForm.EXPANDED
        toolbar.ballIntent = BallIntent.EXPAND
        canvas.replaying = false
        recorder.restartTiming()

        // syncOverlay derives the canvas mode, flags, attachment and stacking from the state.
        EngineState.mode.value = Mode.RECORDING
        syncOverlay()
    }

    private fun stopRecording(collapseForInput: Boolean) {
        if (!EngineState.isRecording) return

        canvas.replaying = false
        EngineState.mode.value = Mode.IDLE
        if (collapseForInput) collapseToBall(BallIntent.RESUME_RECORDING)
        syncOverlay()
    }

    // --- Pause handling ------------------------------------------------------

    /**
     * Gets the toolbar out of the way of the on-screen keyboard after a pause point is inserted.
     *
     * TYPE_ACCESSIBILITY_OVERLAY sits above TYPE_INPUT_METHOD, so the expanded toolbar — roughly 40%
     * of the screen height hugging one edge — lands squarely on the keyboard's Q/A/Z column. Without
     * this, pressing A would hit a toolbar button and typing a verification code would be
     * impossible, which would make pause points useless.
     *
     * Only the recording side needs this. A pause during replay hides the whole toolbar instead, so
     * there is nothing left over the keyboard to move. Resuming needs no counterpart either:
     * [startRecording] puts the toolbar back to expanded on its way in.
     */
    private fun collapseToBall(intent: BallIntent) {
        toolbar.ballIntent = intent
        EngineState.toolbarForm.value = ToolbarForm.BALL

        // Keyboards occupy the lower half, so park the ball in the upper quarter.
        val limit = host.displaySize().y / 4
        toolbarParams.y = min(toolbarParams.y, limit)
        host.update(toolbar, toolbarParams)
    }

    // --- Window movement -----------------------------------------------------

    private fun moveToolbar(dx: Int, dy: Int) {
        toolbarParams.x += dx
        toolbarParams.y += dy
        clampWindows()
        host.update(toolbar, toolbarParams)
    }

    private fun moveTransport(dx: Int, dy: Int) {
        transportParams.x += dx
        transportParams.y += dy
        clampWindows()
        host.update(transport, transportParams)
    }

    /** @return true when a position actually had to move, so callers can skip a pointless update. */
    private fun clampWindows(): Boolean {
        val size = host.displaySize()
        val before = listOf(toolbarParams.x, toolbarParams.y, transportParams.x, transportParams.y)

        toolbarParams.x = toolbarParams.x.coerceIn(0, max(0, size.x - max(toolbar.width, 1)))
        toolbarParams.y = toolbarParams.y.coerceIn(0, max(0, size.y - max(toolbar.height, 1)))
        transportParams.x = transportParams.x.coerceIn(0, max(0, size.x - max(transport.width, 1)))
        transportParams.y = transportParams.y.coerceIn(0, max(0, size.y - max(transport.height, 1)))

        return before != listOf(toolbarParams.x, toolbarParams.y, transportParams.x, transportParams.y)
    }

    /**
     * Remembers where the toolbar was dropped.
     *
     * It used to snap to whichever side edge was nearer, which meant it could not be put anywhere
     * else — drag it to the middle and it sprang back. Edge docking is easy enough to do by hand if
     * that is what you want, so the toolbar now simply stays where it is put.
     */
    private fun settleToolbar() {
        clampWindows()
        host.update(toolbar, toolbarParams)
        Repo.writeInt(PREF_TOOLBAR_X, toolbarParams.x)
        Repo.writeInt(PREF_TOOLBAR_Y, toolbarParams.y)
    }

    private fun settleTransport() {
        Repo.writeInt(PREF_TRANSPORT_X, transportParams.x)
        Repo.writeInt(PREF_TRANSPORT_Y, transportParams.y)
    }

    // --- Actions -------------------------------------------------------------

    private fun currentScreen(): ScreenSpec {
        val size = host.displaySize()
        return ScreenSpec(size.x, size.y, host.rotation())
    }

    private val flowMode: Boolean get() = Repo.mode.value == AppMode.FLOW

    private fun startPlayback() {
        if (flowMode) startFlowPlayback() else startWorkspacePlayback()
    }

    /**
     * Runs [proceed], asking first if that would throw away unsaved work.
     *
     * One helper behind every way of discarding the workspace — turning the toolbar off, switching mode,
     * loading something else, starting fresh. Each of those used to warn in its own words, with its own
     * step count, and two of them did not warn at all. The differences were not worth the four dialogs.
     *
     * There is no "save" option here on purpose. Saving a fresh recording needs a name, which needs an
     * activity, which means holding the pending action across a trip out to it and back — for every one of
     * the callers. Cancel and press save is one more tap and no state machine.
     *
     * Only the one label. [OptionPadView] draws its own cancel row, so passing another one produced a pad
     * offering cancel twice — and the label that is left has to name the outcome rather than agree with a
     * question, because with the cancel gone it carries the whole meaning on its own.
     */
    private fun confirmDiscard(proceed: () -> Unit) {
        if (!Session.needsConfirm) {
            proceed()
            return
        }
        openOptionPad(
            OptionRequest(
                title = getString(R.string.discard_warning),
                labels = listOf(getString(R.string.discard_action)),
            ) { index -> if (index == 0) proceed() }
        )
    }

    private fun startWorkspacePlayback() {
        val steps = Workspace.steps.value
        if (steps.isEmpty()) return
        Diag.clear()
        Diag.log("playback start")
        val from = startFromIndex
        startFromIndex = 0
        exitEditing()
        player.play(steps, Workspace.screen, settings.defaultLoopCount, from)
    }

    /**
     * Runs the loaded flow.
     *
     * Expanded into a step list and handed to the same player, so a flow inherits everything a recording
     * has — the gap between loops, pausing when a touch interrupts, per-step repeats. The coordinates come
     * out of the expansion already scaled, hence the null recorded screen: there is nothing left to scale.
     *
     * A flow's own loop count wins over the global one. That is the container's knob, and the point of
     * putting it on the flow was that this flow runs this many times.
     *
     * A missing clip refuses the whole run rather than skipping the row. Deleting a clip prunes it from
     * every flow, so this should be unreachable — but a flow that quietly runs four of its five clips
     * fails while looking like it succeeded, which is the worse of the two failures.
     */
    private fun startFlowPlayback() {
        val open = Repo.openFlow.value ?: return
        val plan = FlowPlan.expand(open.flow, open.clips, currentScreen())

        if (plan.missing.isNotEmpty()) {
            toast(getString(R.string.toast_flow_missing_clip, plan.missing.size))
            return
        }
        if (plan.steps.isEmpty()) {
            toast(getString(R.string.toast_flow_empty))
            return
        }

        Diag.clear()
        Diag.log(
            "playback start: flow '${open.file.name}', ${open.flow.clips.size} clip(s) " +
                "-> ${plan.steps.size} step(s)"
        )
        exitEditing()
        player.play(plan.steps, recordedScreen = null, loops = open.flow.loopCount, plan = plan)
    }

    /**
     * Where the next playback should begin. Consumed by [startPlayback] and reset immediately.
     *
     * Held here rather than passed through, because the request comes from the parameter card while the
     * play button is what actually starts a run.
     */
    private var startFromIndex = 0

    /**
     * Opens one of the workspace dialogs.
     *
     * These are activities rather than more overlay panels because naming a clip needs a text field,
     * a text field needs input focus, and every overlay here is deliberately FLAG_NOT_FOCUSABLE so it
     * never takes focus from the app underneath.
     */
    /**
     * Selects a freshly inserted step, but only while editing.
     *
     * While recording, nothing may be selected: insertion treats the selection as its anchor, and
     * recorded steps have to keep appending to the end. While editing the opposite is wanted — carry on
     * from what was just created, so a run of inserts goes forwards rather than piling up in one place.
     */
    private fun selectIfEditing(id: String) {
        if (EngineState.editing.value) EngineState.selectedStepId.value = id
    }

    /**
     * The guard is on saving only, and naming which mode it applies to matters.
     *
     * It used to read "anything except LOAD", back when starting a new clip was one of these too and an
     * empty workspace really did make three of the four pointless. That inverted default then swallowed
     * `⊕ new flow` on the toolbar: flow mode keeps the workspace empty, so the one button for creating a
     * flow from the toolbar answered "nothing to save" and never opened the dialog at all. Listing what
     * the rule is *for* cannot go wrong the same way when a fifth mode is added.
     */
    private fun openWorkspaceDialog(mode: WorkspaceDialogActivity.Mode, stepId: String? = null) {
        if (mode == WorkspaceDialogActivity.Mode.SAVE_AS && Workspace.isEmpty) {
            toast(getString(R.string.toast_nothing_to_save))
            return
        }
        EngineState.quickSettingsOpen.value = false
        EngineState.numberPadOpen.value = false
        startActivity(
            Intent(this, WorkspaceDialogActivity::class.java)
                .putExtra(WorkspaceDialogActivity.EXTRA_MODE, mode.name)
                .putExtra(WorkspaceDialogActivity.EXTRA_STEP_ID, stepId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /**
     * The canvas received the gesture it had just injected.
     *
     * FLAG_NOT_TOUCHABLE had not taken effect in time, so the replay was swallowed by our own window
     * and the app below saw nothing — which looks exactly like the replay doing nothing at all.
     * Moving the window aside sidesteps the race entirely, so that is what the message suggests.
     */
    private fun onReplayEcho() {
        Log.w(TAG, "Replayed gesture was swallowed by the canvas; FLAG_NOT_TOUCHABLE had not landed")
        Diag.log("!! canvas received a touch during replay: the flag had not landed")
        val now = SystemClock.uptimeMillis()
        if (now - lastGestureWarningAt < GESTURE_WARNING_INTERVAL_MS) return
        lastGestureWarningAt = now
        toast(getString(R.string.toast_replay_echo))
    }

    /**
     * Surfaces gestures the system would not deliver.
     *
     * This used to be swallowed, which made "the app ignored the tap" look exactly like "the tap was
     * never delivered" — two problems with nothing in common. Reported at most once every few
     * seconds, because a rejected run rejects every step and a toast per step would bury the screen.
     */
    /**
     * Re-applies the service info to prod the framework into rebuilding the accessibility input
     * filter, which is where the MotionEventInjector lives.
     *
     * setServiceInfo makes the system recompute its user state, and that recompute is what installs
     * the filter. It is a nudge, not a guarantee — if it does not take, only toggling the service in
     * system settings will — so it is attempted at most once per binding rather than per gesture.
     */
    private fun renewRegistration(): Boolean {
        if (registrationRenewed) return false
        registrationRenewed = true
        // Never fabricate a blank AccessibilityServiceInfo as a fallback: its capabilities are zero,
        // so setting one would drop canPerformGestures and guarantee the very failure this is trying
        // to recover from. If the current info cannot be read, decline instead of guessing.
        val current = runCatching { serviceInfo }.getOrNull()
        if (current == null) {
            Diag.log("cannot re-register: service info unavailable")
            return false
        }

        Diag.log("re-applying service info to rebuild the input filter")
        return runCatching { serviceInfo = current; true }
            .onFailure { Log.w(TAG, "Could not re-apply service info", it) }
            .getOrDefault(false)
    }

    /**
     * Routes a captured gesture to whichever of the three things asked for it.
     *
     * Recording, adding a step and re-recording one are the same act — put a finger on the screen and
     * have it become a step — differing only in where the result lands. So there is one capture path and
     * this one branch, rather than three ways of getting a gesture.
     *
     * The two editing dispositions share two properties. A replacement keeps the step's id and its lead
     * delay and swaps only the strokes: the delay is the rhythm around the step, measured against its
     * neighbour, and redoing the movement is no reason to lose it. And neither replays the gesture
     * downwards — per-gesture replay exists so the app under a recording moves forward with you, and
     * while editing there is no such walk to keep in step with.
     */
    private fun onGestureCaptured(strokes: List<Stroke>, downUptime: Long, upUptime: Long) {
        val pending = EngineState.pendingCapture.value
        if (pending == null) {
            recorder.onGesture(strokes, downUptime, upUptime)
            return
        }

        EngineState.pendingCapture.value = null
        when (pending) {
            is Capture.Replace -> {
                val existing = Workspace.stepById(pending.stepId) as? GestureStep ?: return
                Workspace.updateStep(existing.copy(strokes = strokes))
                Diag.log("re-record: step ${existing.id} replaced with ${strokes.size} stroke(s)")
                toast(getString(R.string.toast_step_rerecorded))
            }

            // The lead delay is the configured default rather than anything measured: the clock was
            // running while the user found the toolbar button, and charging that to the step would
            // insert a pause of however long the decision took.
            is Capture.InsertAfter -> {
                val step = GestureStep(strokes = strokes, delayBefore = settings.defaultGapMs)
                Workspace.insertAfter(pending.afterId, step, currentScreen())
                select(step.id)
                toast(getString(R.string.toast_step_inserted))
            }
        }
    }

    private fun onGestureOutcome(outcome: GestureOutcome) {
        if (outcome == GestureOutcome.COMPLETED || outcome == GestureOutcome.SKIPPED) {
            consecutiveGestureFailures = 0
            return
        }

        // While replaying, a cancellation is almost always a finger landing on the screen, and the player
        // pauses with a prompt that says so — see Player.interrupted. A toast on top of that would report
        // the same thing twice, and report it as a fault. It is not counted as a failure either: the run
        // of failures exists to tell "one cancelled gesture" from "every gesture cancelled", which is a
        // question about the system, not about the user's hand.
        if (outcome == GestureOutcome.CANCELLED && EngineState.isReplaying) return

        consecutiveGestureFailures++
        val now = SystemClock.uptimeMillis()
        if (now - lastGestureWarningAt < GESTURE_WARNING_INTERVAL_MS) return
        lastGestureWarningAt = now

        // The run of failures is in the message because one cancelled gesture and every gesture
        // being cancelled are different problems, and the toast is throttled so the count is the
        // only way to tell them apart.
        toast(
            getString(
                when (outcome) {
                    GestureOutcome.INJECTOR_MISSING -> R.string.toast_gesture_injector_missing
                    GestureOutcome.CANCELLED -> R.string.toast_gesture_cancelled
                    else -> R.string.toast_gesture_refused
                },
                consecutiveGestureFailures,
            )
        )
    }

    // --- Editing -------------------------------------------------------------

    private fun enterEditing() {
        if (EngineState.isRecording || EngineState.isReplaying) return
        if (Workspace.isEmpty) return
        EngineState.toolbarForm.value = ToolbarForm.EXPANDED
        // Reset rather than remembered: coming back into editing, the useful default is the quiet one.
        EngineState.isolateSelection.value = true
        // Editing is its own toolbar, and the quick settings are not part of it. Left open they would
        // sit over the canvas with no button left to close them.
        EngineState.quickSettingsOpen.value = false
        // Both large windows start down. Editing opens on a clean canvas showing the step you left off
        // at, which is the state dragging needs.
        EngineState.paramPanelOpen.value = false
        EngineState.stepListOpen.value = false
        // resolveSelection puts the selection on the last step; syncOverlay attaches the canvas and
        // re-stacks everything above it.
        EngineState.editing.value = true
        syncOverlay()
        toast(getString(R.string.toast_edit_mode_on))
    }

    private fun exitEditing() {
        EngineState.stepListOpen.value = false
        EngineState.paramPanelOpen.value = false
        EngineState.pendingCapture.value = null
        EngineState.editing.value = false
        EngineState.selectedStepId.value = null
    }

    /**
     * Moves the selection.
     *
     * A miss — tapping bare canvas — deliberately changes nothing while editing, because there is no
     * "nothing selected" state to move to. Clearing it and letting [resolveSelection] snap back to the
     * last step would read as the app jumping somewhere on its own.
     */
    private fun select(stepId: String?) {
        if (stepId == null && EngineState.editing.value) return
        EngineState.selectedStepId.value = stepId
    }

    /**
     * Tapping a marker on the canvas: select it, and bring its settings up.
     *
     * Only this route opens the card. [select] has seven other callers — deleting lands on a neighbour,
     * duplicating lands on the copy, the list, the steppers — and none of those is a request to see
     * parameters, so the open belongs here rather than inside [select].
     *
     * Without it a tap could be answered by nothing at all. A gesture marker at least responds to a drag,
     * but a wait, a pause and a system key are deliberately not draggable — their anchor is derived from
     * their neighbours, so moving them would move nothing — and on those a touch had no visible effect
     * whatever. A timed wait's seconds then looked like something the editor could not change. It always
     * could: the card was the only way in, and nothing on screen said so.
     *
     * The card is modal from here (see [CanvasView.panelOpen]). Its ◀ ▶ walk to the neighbouring steps
     * without closing it, and closing it is what hands the canvas back to dragging.
     */
    private fun selectFromCanvas(stepId: String?) {
        select(stepId)
        if (stepId != null) openStepPanel()
    }

    /**
     * Opens the settings panel on the current selection.
     *
     * Two callers — the toolbar button and a row in the list — and one behaviour, so the panel needs to
     * remember nothing about who opened it. Its close button always returns to the canvas; to get back
     * to the list, press the list button again.
     *
     * The re-stack is not optional. Same-type overlay windows sit in the order they were added, and the
     * canvas covers the whole screen while editing, so without this the panel would be underneath it and
     * every button on it unreachable.
     */
    private fun openStepPanel() {
        if (EngineState.selectedStepId.value == null) return
        EngineState.stepListOpen.value = false
        EngineState.paramPanelOpen.value = true
        scope.launch { host.bringToFront(stepPanel, stepPanelParams) }
    }

    private fun dragStep(stepId: String, handle: Handle, x: Float, y: Float) {
        val step = Workspace.stepById(stepId) as? GestureStep ?: return
        val updated = when (handle) {
            Handle.BODY -> step.movedTo(x, y)
            Handle.START -> step.withStartAt(x, y)
            Handle.END -> step.withEndAt(x, y)
        }
        // Not persisted per sample: a drag would otherwise write the draft file dozens of times.
        // onDragEnd flushes.
        Workspace.updateStep(updated, persist = false)
    }

    private fun adjustSelected(transform: (Step) -> Step?) {
        val step = Workspace.stepById(EngineState.selectedStepId.value) ?: return
        transform(step)?.let { Workspace.updateStep(it) }
    }

    /**
     * Deletes the selected step and steps the selection back one.
     *
     * Backwards, not forwards. Deleting the last step leaves you looking at the one before it, which is
     * the rule isolation is built on (§9.2) — and the same rule in the middle of a script keeps you
     * behind the gap you just made rather than on top of it. From the first step there is nothing behind,
     * so whatever slid into slot 0 is what you are left with. Emptying the workspace leaves editing,
     * because there is nothing to edit.
     *
     * Called from the toolbar and from the settings panel, written once, for the same reason
     * [duplicateSelected] is: two entry points, one behaviour.
     */
    private fun deleteSelected() {
        val before = Workspace.steps.value
        val index = before.indexOfFirst { it.id == EngineState.selectedStepId.value }
        if (index < 0) return

        Workspace.removeStep(before[index].id)
        val after = Workspace.steps.value
        if (after.isEmpty()) {
            exitEditing()
            return
        }
        // Always in range: after is exactly one shorter, so index - 1 is at most its last index.
        select(after[(index - 1).coerceAtLeast(0)].id)
    }

    /**
     * Copies the selected step, puts the copy straight after it, and moves the selection to the copy.
     *
     * Called from two places — the toolbar and the settings panel — and written once. Two entry points
     * for one behaviour is fine; two implementations is how they would slowly stop agreeing.
     *
     * The copy gets a new id, so the two are separate steps rather than one aliased twice: selection,
     * dragging, the marker list and undo all key off the id. Cheaper than re-capturing when what you
     * want is the same tap again, or a near-identical one to nudge.
     */
    private fun duplicateSelected() {
        val step = Workspace.stepById(EngineState.selectedStepId.value) ?: return
        val copy = when (step) {
            is GestureStep -> step.copy(id = newId())
            is PauseStep -> step.copy(id = newId())
            is GlobalStep -> step.copy(id = newId())
        }
        Workspace.insertAfter(step.id, copy, currentScreen())
        select(copy.id)
        toast(getString(R.string.toast_step_duplicated))
    }

    private inner class StepListActions : StepListView.Actions {
        /**
         * Picking a row is the list's whole job, so it hands straight over to the settings panel.
         *
         * The list closes on the way. They are the same size in the same place, and leaving one behind
         * the other is the overlap this arrangement exists to avoid. To go back, press the list button
         * again — the panel has one exit, and it is the canvas.
         */
        override fun onSelectIndex(index: Int) {
            val step = Workspace.steps.value.getOrNull(index) ?: return
            select(step.id)
            openStepPanel()
        }

        override fun onClose() {
            EngineState.stepListOpen.value = false
        }
    }

    private inner class StepPanelActions : StepPanelView.Actions {
        override fun onPrevious() = moveSelection(-1)

        override fun onNext() = moveSelection(1)

        /**
         * Walks the selection, clamped at both ends.
         *
         * No wrap-around: at step 1 of 100, "previous" landing on 100 is a jump, not a step back. And no
         * empty case to cover, because the panel is only ever open on a step.
         */
        private fun moveSelection(delta: Int) {
            val steps = Workspace.steps.value
            val current = steps.indexOfFirst { it.id == EngineState.selectedStepId.value }
            if (current < 0) return
            select(steps[(current + delta).coerceIn(0, steps.lastIndex)].id)
        }

        /**
         * Reaching a step by number, which is what playback gives you.
         *
         * It reports the failing step as "47 / 100". Finding marker 47 among a hundred overlapping
         * crosshairs is the problem; typing 47 is not — and it is why the list is for the other case,
         * where the number is not known and the step has to be recognised by reading.
         */
        override fun onJumpToStep() {
            val steps = Workspace.steps.value
            if (steps.isEmpty()) return
            openNumberPad(
                PadRequest(
                    title = getString(R.string.step_list_jump_title),
                    unit = getString(R.string.step_list_jump_unit),
                    initialValue = steps.indexOfFirst { it.id == EngineState.selectedStepId.value } + 1,
                    max = steps.size,
                ) { number ->
                    steps.getOrNull(number - 1)?.let { select(it.id) }
                }
            )
        }

        override fun onAdjustDuration(deltaMs: Long) = adjustSelected { step ->
            (step as? GestureStep)?.let { it.withDuration(it.duration + deltaMs) }
        }

        override fun onAdjustDelay(deltaMs: Long) = adjustSelected { step ->
            val next = (step.delayBefore + deltaMs).coerceIn(0L, Timing.MAX_RECORDED_GAP_MS)
            when (step) {
                is GestureStep -> step.copy(delayBefore = next)
                is PauseStep -> step.copy(delayBefore = next)
                is GlobalStep -> step.copy(delayBefore = next)
            }
        }

        /**
         * Changes how long a timed wait lasts.
         *
         * Until this existed the length could be set once, when the wait was inserted, and never again —
         * the panel showed no rows at all for a step without coordinates, which is exactly what a wait
         * is. Floored at one second, because zero is how a wait and a manual pause are told apart and
         * typing 0 here would silently change the step's type.
         */
        override fun onEditWaitSeconds() {
            val step = Workspace.stepById(EngineState.selectedStepId.value) as? PauseStep ?: return
            openNumberPad(
                PadRequest(
                    title = getString(R.string.wait_pad_title),
                    unit = getString(R.string.wait_pad_unit),
                    initialValue = (step.ms / 1000L).toInt(),
                    max = MAX_WAIT_SECONDS,
                ) { seconds ->
                    Workspace.updateStep(step.copy(ms = seconds.coerceAtLeast(1) * 1000L))
                }
            )
        }

        /**
         * Sets how many times this step runs in place.
         *
         * Raising it above one also stamps an interval when there is none. A repeat with a zero gap fires
         * every pass back to back, and the app underneath then reads ten taps as one multi-tap or drops
         * them outright — so a fresh repeat has to arrive with a usable gap already in it. Stamped rather
         * than defaulted at replay time, because the panel would otherwise show "0 ms" for a step that
         * does not behave that way.
         */
        override fun onEditRepeat() {
            val step = Workspace.stepById(EngineState.selectedStepId.value) as? RepeatableStep ?: return
            openNumberPad(
                PadRequest(
                    title = getString(R.string.repeat_pad_title),
                    unit = getString(R.string.repeat_pad_unit),
                    initialValue = step.repeat,
                    max = MAX_REPEAT,
                ) { times ->
                    val repeat = times.coerceAtLeast(1)
                    val interval = if (repeat > 1 && step.repeatIntervalMs <= 0) {
                        settings.defaultGapMs
                    } else {
                        step.repeatIntervalMs
                    }
                    Workspace.updateStep(step.withRepeat(repeat, interval))
                }
            )
        }

        override fun onEditRepeatInterval() {
            val step = Workspace.stepById(EngineState.selectedStepId.value) as? RepeatableStep ?: return
            openNumberPad(
                PadRequest(
                    title = getString(R.string.repeat_interval_pad_title),
                    unit = getString(R.string.repeat_interval_pad_unit),
                    initialValue = step.repeatIntervalMs.toInt(),
                    max = Timing.MAX_RECORDED_GAP_MS.toInt(),
                ) { ms ->
                    Workspace.updateStep(step.withRepeat(step.repeat, ms.toLong()))
                }
            )
        }

        override fun onEditNote() {
            val id = EngineState.selectedStepId.value ?: return
            openWorkspaceDialog(WorkspaceDialogActivity.Mode.NOTE, id)
        }

        /**
         * Captures this step's gesture again.
         *
         * Starts immediately, with no countdown, for the same reason resuming a recording does: undo is
         * cheaper than making everyone wait three seconds every time.
         */
        override fun onReRecord() {
            val step = Workspace.stepById(EngineState.selectedStepId.value) as? GestureStep ?: return
            EngineState.pendingCapture.value = Capture.Replace(step.id)
            toast(getString(R.string.toast_rerecord_prompt))
        }

        /** Both of these are the toolbar's calls verbatim, so the two entry points cannot disagree. */
        override fun onDuplicate() = duplicateSelected()

        override fun onDelete() = deleteSelected()

        override fun onMoveBack() = move(-1)

        override fun onMoveForward() = move(1)

        /**
         * Moves the selected step one slot.
         *
         * Deliberately only one slot. Absolute positioning was considered and cut: it serves
         * restructuring a script, and restructuring presupposes being able to tell what each step is —
         * which, with a hundred steps labelled by coordinate, you cannot. What is real is the nudge
         * right after inserting, where you know exactly what you just made.
         */
        private fun move(delta: Int) {
            val id = EngineState.selectedStepId.value ?: return
            Workspace.moveStep(id, delta)
        }

        override fun onClose() {
            EngineState.paramPanelOpen.value = false
        }
    }

    private inner class QuickSettingsActions : QuickSettingsView.Actions {
        override fun onAdjustLoopCount(delta: Int) = Repo.updateSettings { current ->
            // 0 means "until stopped" and simply sits at the bottom of the range, rather than being
            // a special case that has to be handled everywhere else.
            current.copy(
                defaultLoopCount = (current.defaultLoopCount + delta)
                    .coerceIn(0, Settings.MAX_LOOP_COUNT)
            )
        }

        override fun onAdjustSpeed(delta: Float) = Repo.updateSettings { current ->
            current.copy(speed = snap(current.speed + delta, 0.25f, Settings.SPEED_RANGE))
        }

        override fun onToggleReplayEachGesture() =
            Repo.updateSettings { it.copy(replayEachGesture = !it.replayEachGesture) }

        override fun onToggleKeepScreenOn() =
            Repo.updateSettings { it.copy(keepScreenOn = !it.keepScreenOn) }

        override fun onToggleDim() = Repo.updateSettings { it.copy(dimOverlay = !it.dimOverlay) }

        override fun onToggleTimer() = Repo.updateSettings { it.copy(showTimer = !it.showTimer) }

        override fun onAdjustUiScale(delta: Float) = Repo.updateSettings { current ->
            current.copy(uiScale = snap(current.uiScale + delta, 0.1f, Settings.UI_SCALE_RANGE))
        }

        override fun onAdjustUiOpacity(delta: Float) = Repo.updateSettings { current ->
            current.copy(uiOpacity = snap(current.uiOpacity + delta, 0.1f, Settings.UI_OPACITY_RANGE))
        }

        override fun onOpenFullSettings() {
            EngineState.quickSettingsOpen.value = false
            startActivity(
                Intent(this@TapFlowService, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        override fun onClose() {
            EngineState.quickSettingsOpen.value = false
        }
    }

    private inner class ToolbarActions : ToolbarView.Actions {
        override fun onPrimary() {
            when (EngineState.mode.value) {
                Mode.IDLE -> startPlayback()
                Mode.PLAYING, Mode.COUNTDOWN -> player.pause()
                Mode.PAUSED -> player.resume()
                Mode.RECORDING -> Unit
            }
        }

        override fun onSecondary() {
            when (EngineState.mode.value) {
                Mode.IDLE -> startRecording()
                Mode.RECORDING -> stopRecording(collapseForInput = false)
                Mode.COUNTDOWN, Mode.PLAYING, Mode.PAUSED -> player.stop()
            }
        }

        /**
         * Asks which step to start from, then plays.
         *
         * Lives with the play button rather than in the parameter card: choosing where a run begins is
         * a property of starting, not of editing. It prefills from the selection, which survives leaving
         * edit mode — so having just fixed step 47, the number is already there.
         */
        override fun onPlayFrom() {
            val steps = Workspace.steps.value
            if (steps.isEmpty()) return
            val selected = steps.indexOfFirst { it.id == EngineState.selectedStepId.value }
            openNumberPad(
                PadRequest(
                    title = getString(R.string.play_from_title),
                    unit = getString(R.string.step_list_jump_unit),
                    initialValue = if (selected >= 0) selected + 1 else 1,
                    max = steps.size,
                ) { number ->
                    startFromIndex = (number - 1).coerceIn(0, steps.lastIndex)
                    startPlayback()
                }
            )
        }

        override fun onInsertPausePoint() {
            if (EngineState.isReplaying) return
            // Nothing is ever selected while recording, so this appends then, and lands after the
            // selected marker when editing — one call covers both.
            val step = PauseStep()
            Workspace.insertAfter(EngineState.selectedStepId.value, step, currentScreen())
            selectIfEditing(step.id)
            toast(getString(R.string.toast_pause_inserted))
            // Stopping is the point: the user is about to do this step by hand, so the canvas has to
            // let touches through and the toolbar has to clear the keyboard area.
            if (EngineState.isRecording) stopRecording(collapseForInput = true)
        }

        /**
         * Asks for the length first, because a wait with no duration is not a wait.
         *
         * Unlike a manual pause this does **not** stop the recording. Stopping exists so the user can
         * carry out the step by hand; a timed wait needs nothing from them, so stopping would only be
         * in the way. The pad is an overlay rather than an Activity for the same reason — an Activity
         * would background the app being recorded.
         */
        override fun onInsertWait() {
            if (EngineState.isReplaying) return
            openNumberPad(
                PadRequest(
                    title = getString(R.string.wait_pad_title),
                    unit = getString(R.string.wait_pad_unit),
                    initialValue = 0,
                    max = MAX_WAIT_SECONDS,
                ) { seconds ->
                    val step = PauseStep(ms = seconds * 1000L)
                    Workspace.insertAfter(EngineState.selectedStepId.value, step, currentScreen())
                    selectIfEditing(step.id)
                    toast(getString(R.string.toast_wait_inserted, seconds))
                }
            )
        }

        /**
         * Asks which system key, then inserts it.
         *
         * **This is the only way one gets into a script.** Recording cannot capture back, home or
         * recents: an accessibility overlay does not see the navigation bar, and hardware keys are not
         * touch events at all. So without this button those four actions were reachable only by importing
         * JSON that nothing can yet export — the dispatcher, the labels and now the repeat count all
         * worked, with nothing able to create one.
         *
         * Asking first, on an overlay rather than in an Activity, for the same reason the wait length
         * does: an Activity would push the app being recorded into the background, which is the very
         * screen the next step is meant to land on.
         */
        override fun onInsertGlobalAction() {
            if (EngineState.isReplaying) return
            val kinds = GlobalKind.entries
            openOptionPad(
                OptionRequest(
                    title = getString(R.string.global_pad_title),
                    labels = kinds.map { it.label(resources) },
                ) { index ->
                    val kind = kinds.getOrNull(index) ?: return@OptionRequest
                    val step = GlobalStep(kind = kind, delayBefore = settings.defaultGapMs)
                    Workspace.insertAfter(EngineState.selectedStepId.value, step, currentScreen())
                    selectIfEditing(step.id)
                    toast(getString(R.string.toast_global_inserted, kind.label(resources)))

                    // While recording, carry it out as well — for the same reason each captured gesture is
                    // replayed downwards: the app underneath has to move with you, or every step after
                    // this one is recorded against the wrong screen. Editing deliberately does not, since
                    // there is no such walk to keep in step with.
                    if (EngineState.isRecording && settings.replayEachGesture) {
                        performGlobalAction(kind.toGlobalActionId())
                    }
                }
            )
        }

        /**
         * Shows or hides the step list.
         *
         * It and the settings panel are the same size in the same place, so opening either closes the
         * other. Two large windows over each other is the thing that made the first attempt at editing
         * unusable, and keeping them exclusive is cheaper than teaching them to dodge.
         */
        override fun onToggleStepList() {
            if (EngineState.stepListOpen.value) {
                EngineState.stepListOpen.value = false
                return
            }
            EngineState.paramPanelOpen.value = false
            EngineState.stepListOpen.value = true
            scope.launch { host.bringToFront(stepList, stepListParams) }
        }

        override fun onToggleStepPanel() {
            if (EngineState.paramPanelOpen.value) {
                EngineState.paramPanelOpen.value = false
                return
            }
            openStepPanel()
        }

        override fun onUndo() {
            if (Workspace.undo()) toast(getString(R.string.toast_undone))
        }

        override fun onToggleEdit() {
            if (EngineState.editing.value) exitEditing() else enterEditing()
        }

        /**
         * Adds a step by capturing one, and puts it after the selection.
         *
         * It used to conjure a tap at the centre of the screen for the user to then drag into place,
         * which is two acts to get one point and neither of them the act they already know. Making the
         * gesture is how every other step in the script was created; the only difference here is where
         * the result lands. So this is the same capture path, aimed at a different slot — and it is also
         * what lifts the old limitation that a manually added step could only ever be a plain tap.
         *
         * After the selection, not before, because that is the direction recording grows in: select 47
         * and the new step is 48. With nothing selected it appends, which is the same rule.
         */
        override fun onInsertStep() {
            EngineState.pendingCapture.value = Capture.InsertAfter(EngineState.selectedStepId.value)
            EngineState.paramPanelOpen.value = false
            EngineState.stepListOpen.value = false
            toast(getString(R.string.toast_capture_prompt))
        }

        override fun onDuplicateStep() = duplicateSelected()

        override fun onDeleteSelected() = deleteSelected()

        /**
         * Overwrites the file this clip came from, with no dialog at all.
         *
         * That is the whole of a plain save now: the workspace knows which file it was opened from, and
         * writing it back needs nothing typed and nowhere chosen. It used to open a screen asking for a name,
         * a tick box for "save as new" and a folder to browse — three questions, of which the answer to all
         * three was almost always "the same as last time".
         *
         * A clip that has never been saved has nothing to overwrite, so it falls through to save-as. Which is
         * what every editor does, and it means `💾` always does something.
         */
        override fun onSave() {
            val target = Workspace.source.value ?: return onSaveAs()
            if (Workspace.isEmpty) {
                toast(getString(R.string.toast_nothing_to_save))
                return
            }
            // Off the main thread: writing goes through a ContentProvider on API 29+, and a slow provider
            // showed up on a device as the whole UI locking up rather than as a save taking a moment.
            scope.launch {
                val result = withContext(Dispatchers.IO) { Workspace.commit(target) }
                toast(
                    when (result) {
                        is Workspace.Saved.Ok -> getString(R.string.toast_saved, result.file.name)
                        Workspace.Saved.Nothing -> getString(R.string.toast_nothing_to_save)
                        Workspace.Saved.Failed -> getString(R.string.toast_save_failed)
                    }
                )
            }
        }

        override fun onSaveAs() = openWorkspaceDialog(WorkspaceDialogActivity.Mode.SAVE_AS)

        /**
         * Naming a flow needs a keyboard, so it goes the same way saving a clip does — an activity, because
         * every overlay here is FLAG_NOT_FOCUSABLE and cannot raise one.
         */
        override fun onNewFlow() = openWorkspaceDialog(WorkspaceDialogActivity.Mode.NEW_FLOW)

        /**
         * Deletes the loaded flow, after asking.
         *
         * Asked on an overlay rather than in an activity: a yes/no question needs no keyboard, so there is
         * no reason to push the app underneath into the background for it. Asked at all because a flow has
         * no undo — `↩` restores steps, and a deleted flow is gone.
         */
        override fun onDeleteFlow() {
            val open = Repo.openFlow.value ?: return
            openOptionPad(
                OptionRequest(
                    title = getString(R.string.flow_delete_title, open.file.name),
                    labels = listOf(getString(R.string.clip_action_delete)),
                ) {
                    scope.launch {
                        val gone = withContext(Dispatchers.IO) { Repo.deleteFile(open.file.ref) }
                        toast(
                            if (gone) getString(R.string.toast_flow_deleted, open.file.name)
                            else getString(R.string.toast_delete_failed)
                        )
                    }
                }
            )
        }

        override fun onLoad() = openWorkspaceDialog(WorkspaceDialogActivity.Mode.LOAD)

        /**
         * Opens the loaded flow's arrangement screen.
         *
         * An activity, like every other list-and-text screen: the flow editor has sliders and a clip
         * picker, and it is the same screen the app's flow rows open — one editor, two ways in.
         */
        override fun onEditFlow() {
            val open = Repo.openFlow.value ?: return
            startActivity(
                Intent(this@TapFlowService, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_FLOW, open.file.ref)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /**
         * Switches mode, after asking if that would cost unsaved steps.
         *
         * No dialog of its own — the same [confirmDiscard] every other discard goes through. Switching
         * empties both sides, so the answer to "what does play do" stays readable off the mode alone.
         */
        override fun onToggleMode() = confirmDiscard {
            Session.switchMode(if (flowMode) AppMode.CLIP else AppMode.FLOW)
            toast(getString(if (flowMode) R.string.toast_mode_flow else R.string.toast_mode_clip))
        }

        /**
         * Done with a clip opened from a flow: save or not, then back to the flow.
         *
         * **Save is offered here, and it is the one discard question that can offer it.** The ordinary
         * prompt cannot, because saving a fresh recording needs a name, a name needs an activity, and every
         * caller would then have to carry a pending action out to it and back. Getting here means a clip was
         * *loaded*, so it already has a name and an id and overwriting it needs nothing typed — two labels
         * on the pad and no state machine.
         *
         * A clean workspace skips the question entirely: there is nothing to save and nothing to lose, so
         * asking would be a dialog with one meaningful answer.
         */
        override fun onFinishClip() {
            if (!Session.needsConfirm) {
                backToFlow()
                return
            }
            openOptionPad(
                OptionRequest(
                    title = getString(R.string.finish_clip_title),
                    labels = listOf(
                        getString(R.string.finish_clip_save),
                        getString(R.string.finish_clip_discard),
                    ),
                ) { index ->
                    if (index != 0) {
                        backToFlow()
                        return@OptionRequest
                    }
                    // Only on a successful save. A failed write leaves the workspace dirty and the draft
                    // recoverable, and walking back to the flow would strand the edit behind a screen that
                    // no longer explains it.
                    scope.launch { if (saveOverSource()) backToFlow() }
                }
            )
        }

        /**
         * Overwrites the file this workspace came from, with no naming step.
         *
         * This route exists to fix a clip in place, so writing it anywhere else would be the one thing nobody
         * asked for — and on a flow's clip it would be actively wrong: the flow points at the old file, so the
         * fix would appear not to have worked.
         */
        private suspend fun saveOverSource(): Boolean {
            val target = Workspace.source.value
            if (target == null) {
                // No source means the clip was never a file — unreachable from a flow, which only ever holds
                // clips that were read from one, and worth reporting rather than silently creating a file the
                // flow does not point at.
                toast(getString(R.string.toast_save_failed))
                return false
            }
            return when (val result = withContext(Dispatchers.IO) { Workspace.commit(target) }) {
                is Workspace.Saved.Ok -> {
                    toast(getString(R.string.toast_saved, result.file.name))
                    true
                }

                Workspace.Saved.Nothing -> true
                Workspace.Saved.Failed -> {
                    toast(getString(R.string.toast_save_failed))
                    false
                }
            }
        }

        private fun backToFlow() {
            // Leaving editing is not optional. Unlike the mode button — which is simply hidden while editing,
            // so it never has to think about this — finish stays available there, because "fix the node, then
            // go back" is the whole reason this button exists. Without it the canvas would keep intercepting
            // the entire screen after the mode changed under it.
            exitEditing()
            val ref = Session.consumeReturnRef()
            if (ref == null) {
                // Nothing to go back to, which should be unreachable — the button is only visible while the
                // breadcrumb exists. Land somewhere honest rather than nowhere: flow mode with nothing open.
                Session.switchMode(AppMode.FLOW)
                toast(getString(R.string.toast_flow_gone))
                return
            }
            // The editor re-reads the flow, which is how the edit that was just saved shows up in it. Reading
            // it here instead would mean file IO on this thread, for a screen that is about to do it anyway.
            startActivity(
                Intent(this@TapFlowService, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_FLOW, ref)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /**
         * Clears the workspace. No activity involved any more.
         *
         * It used to open one purely to show a "this will clear N actions" confirmation, which is what
         * [confirmDiscard] now says for every caller — and on a clean workspace there was nothing to
         * confirm, so the activity was a screen that appeared to ask a question with only one answer.
         */
        override fun onNewClip() = confirmDiscard {
            Session.startFresh()
            toast(getString(R.string.toast_workspace_cleared))
        }

        /**
         * While editing this is the isolate toggle, not the density cycle.
         *
         * Editing draws only the selected marker, so the three-way density has nothing to act on —
         * and it used to be worse than useless there, because the canvas forced ALL over whatever it
         * set. What editing actually needs is a way to see the others in order to pick one by sight.
         */
        override fun onCycleDensity() {
            if (EngineState.editing.value) {
                EngineState.isolateSelection.value = !EngineState.isolateSelection.value
            } else {
                Repo.updateSettings { it.copy(markerDensity = it.markerDensity.next()) }
            }
        }

        override fun onToggleQuickSettings() {
            val opening = !EngineState.quickSettingsOpen.value
            EngineState.quickSettingsOpen.value = opening
            // Re-stack above the canvas, which covers the whole screen while recording or editing.
            if (opening) scope.launch { host.bringToFront(quickSettings, quickSettingsParams) }
        }

        /**
         * Dismiss turns the overlay off rather than shrinking it.
         *
         * It used to collapse into a 6dp edge handle. On a real device that is not a touch target,
         * and because the form was never reset there was no way back — the app switch, and even
         * restarting the accessibility service, both restored the same unhittable sliver. Turning the
         * overlay off is honest, the app switch mirrors it, and the toast says how to undo it.
         *
         * This is the deliberate exit, so it empties the workspace. That is what lets a dirty draft on
         * disk mean "the process died" and nothing else — see [Session.close]. Use the collapse button
         * to put the toolbar out of the way without ending the session.
         */
        override fun onDismiss() = confirmDiscard {
            Session.close()
            Repo.setOverlayEnabled(false)
            toast(getString(R.string.toast_toolbar_hidden))
        }

        override fun onCollapse() {
            toolbar.ballIntent = BallIntent.EXPAND
            EngineState.toolbarForm.value = ToolbarForm.BALL
        }

        override fun onExpand() {
            toolbar.ballIntent = BallIntent.EXPAND
            EngineState.toolbarForm.value = ToolbarForm.EXPANDED
        }

        override fun onDrag(dx: Int, dy: Int) = moveToolbar(dx, dy)

        override fun onDragEnd() = settleToolbar()
    }

    private inner class TransportActions : TransportView.Actions {
        override fun onStop() {
            if (EngineState.isRecording) stopRecording(collapseForInput = false) else player.stop()
        }

        override fun onPauseOrResume() {
            if (EngineState.mode.value == Mode.PAUSED) player.resume() else player.pause()
        }

        override fun onDrag(dx: Int, dy: Int) = moveTransport(dx, dy)

        override fun onDragEnd() = settleTransport()
    }
}
