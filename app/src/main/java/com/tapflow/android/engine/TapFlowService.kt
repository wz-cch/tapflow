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
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.GlobalStep
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Repo
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Settings
import com.tapflow.android.data.Step
import com.tapflow.android.data.Stroke
import com.tapflow.android.data.movedTo
import com.tapflow.android.data.tapStep
import com.tapflow.android.data.withDuration
import com.tapflow.android.data.withEndAt
import com.tapflow.android.overlay.BallIntent
import com.tapflow.android.overlay.CanvasMode
import com.tapflow.android.overlay.CanvasView
import com.tapflow.android.overlay.Handle
import com.tapflow.android.overlay.NumberPadView
import com.tapflow.android.overlay.OverlayHost
import com.tapflow.android.overlay.ParamCardView
import com.tapflow.android.overlay.QuickSettingsView
import com.tapflow.android.overlay.StepListView
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
    private lateinit var paramCard: ParamCardView
    private lateinit var quickSettings: QuickSettingsView
    private lateinit var numberPad: NumberPadView
    private lateinit var stepList: StepListView

    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var toolbarParams: WindowManager.LayoutParams
    private lateinit var transportParams: WindowManager.LayoutParams
    private lateinit var paramCardParams: WindowManager.LayoutParams
    private lateinit var quickSettingsParams: WindowManager.LayoutParams
    private lateinit var numberPadParams: WindowManager.LayoutParams
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
        step("restore workspace") { Workspace.restore() }

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
            onSelect = { stepId -> select(stepId) }
            onDragStep = { stepId, handle, x, y -> dragStep(stepId, handle, x, y) }
            onDragEnd = { Workspace.flush() }
            onPickCoordinate = { x, y -> pickCoordinate(x, y) }
            onReplayEcho = { onReplayEcho() }
        }
        toolbar = ToolbarView(this, ToolbarActions())
        transport = TransportView(this, TransportActions())
        paramCard = ParamCardView(this, ParamCardActions())
        quickSettings = QuickSettingsView(this, QuickSettingsActions())
        numberPad = NumberPadView(this) { EngineState.numberPadOpen.value = false }
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
        paramCardParams = host.params(
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
        host.remove(quickSettings)
        host.remove(paramCard)
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
        scope.launch { Repo.settings.collect { syncOverlay() } }
        scope.launch { EngineState.mode.collect { syncOverlay() } }
        scope.launch { EngineState.toolbarForm.collect { syncOverlay() } }
        scope.launch { EngineState.editing.collect { syncOverlay() } }
        scope.launch { EngineState.selectedStepId.collect { syncOverlay() } }
        scope.launch { EngineState.pickingCoordinate.collect { syncOverlay() } }
        scope.launch { EngineState.quickSettingsOpen.collect { syncOverlay() } }
        scope.launch { EngineState.numberPadOpen.collect { syncOverlay() } }
        scope.launch { EngineState.isolateSelection.collect { syncOverlay() } }
        scope.launch { EngineState.stepListOpen.collect { syncOverlay() } }
        scope.launch { EngineState.reRecordingStepId.collect { syncOverlay() } }
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

    private fun syncOverlay() {
        if (!host.isAttached(toolbar)) return

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
        )
        host.update(toolbar, toolbarParams)

        canvas.markers = buildMarkers(
            steps,
            screen.x.toFloat(),
            screen.y.toFloat(),
            resources.displayMetrics.density,
        )
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
        canvas.pickingCoordinate = EngineState.pickingCoordinate.value
        canvas.dimAlpha = if (current.dimOverlay && mode == Mode.PLAYING) current.dimAlpha else 0f
        canvas.mode = when {
            mode == Mode.RECORDING -> CanvasMode.RECORDING
            // Capturing a replacement gesture needs the same full-screen interception as recording, and
            // the recording tint is honest about what the next touch will do.
            EngineState.reRecordingStepId.value != null -> CanvasMode.RECORDING
            editing -> CanvasMode.EDIT
            else -> CanvasMode.READ_ONLY
        }
        updateCanvasFlags()
        syncCanvasAttachment()

        syncParamCard()
        syncQuickSettings()
        syncStepList()
        syncNumberPad()
        syncTransport()
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

    /**
     * The step list.
     *
     * Only up while editing, and only when asked for — it covers a strip of the screen, which is in
     * the way of dragging a marker underneath it.
     */
    private fun syncStepList() {
        val wanted = EngineState.editing.value && EngineState.stepListOpen.value
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
        if (!host.isAttached(stepList)) {
            val size = host.displaySize()
            // Low on the screen: the toolbar runs down one edge and the parameter card sits mid-screen.
            stepListParams.x = (size.x * 0.5f - dpToPx(150f)).toInt().coerceAtLeast(0)
            stepListParams.y = (size.y * 0.52f).toInt()
            host.add(stepList, stepListParams)
        }
        host.update(stepList, stepListParams)
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
        if (host.isAttached(paramCard)) host.bringToFront(paramCard, paramCardParams)
        if (host.isAttached(quickSettings)) host.bringToFront(quickSettings, quickSettingsParams)
        if (host.isAttached(stepList)) host.bringToFront(stepList, stepListParams)
        if (host.isAttached(numberPad)) host.bringToFront(numberPad, numberPadParams)
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
     * The card is only attached while a step is selected and its coordinate is not being re-picked.
     * Detaching rather than hiding keeps it out of the blocked-area list, which is computed from
     * attached windows.
     */
    private fun syncParamCard() {
        val step = Workspace.stepById(EngineState.selectedStepId.value)
        val wanted = EngineState.editing.value && step != null &&
            !EngineState.pickingCoordinate.value && EngineState.reRecordingStepId.value == null

        if (!wanted) {
            host.remove(paramCard)
            return
        }

        val number = Workspace.steps.value.indexOfFirst { it.id == step!!.id } + 1
        paramCard.render(step!!, number)

        if (!host.isAttached(paramCard)) {
            val size = host.displaySize()
            paramCardParams.x = (size.x * 0.18f).toInt()
            paramCardParams.y = (size.y * 0.62f).toInt()
            host.add(paramCard, paramCardParams)
        }
        host.update(paramCard, paramCardParams)
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

    private fun startPlayback() {
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
     * While recording, nothing may be selected: insertBefore treats the selection as the anchor, and
     * recorded steps have to keep appending to the end. While editing the opposite is wanted — the
     * parameter card opens on what was just created, which is where its note is typed.
     */
    private fun selectIfEditing(id: String) {
        if (EngineState.editing.value) EngineState.selectedStepId.value = id
    }

    private fun openWorkspaceDialog(mode: WorkspaceDialogActivity.Mode, stepId: String? = null) {
        if (mode != WorkspaceDialogActivity.Mode.LOAD && Workspace.isEmpty) {
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
     * Routes a captured gesture: normally it becomes a new step, but a re-record replaces one.
     *
     * The replacement keeps the step's id and its lead delay and swaps only the strokes. The delay is
     * the rhythm around the step, measured against its neighbour, and redoing the movement is no
     * reason to lose it — it stays separately editable.
     *
     * No per-gesture replay either. That exists so the app under a recording moves forward with you;
     * while editing there is no such walk to keep in step with, and dispatching would poke the app for
     * no reason.
     */
    private fun onGestureCaptured(strokes: List<Stroke>, downUptime: Long, upUptime: Long) {
        val id = EngineState.reRecordingStepId.value
        if (id == null) {
            recorder.onGesture(strokes, downUptime, upUptime)
            return
        }

        EngineState.reRecordingStepId.value = null
        val existing = Workspace.stepById(id) as? GestureStep ?: return
        Workspace.updateStep(existing.copy(strokes = strokes))
        Diag.log("re-record: step ${existing.id} replaced with ${strokes.size} stroke(s)")
        toast(getString(R.string.toast_step_rerecorded))
    }

    private fun onGestureOutcome(outcome: GestureOutcome) {
        if (outcome == GestureOutcome.COMPLETED || outcome == GestureOutcome.SKIPPED) {
            consecutiveGestureFailures = 0
            return
        }

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
        // syncOverlay attaches the canvas and re-stacks everything above it.
        EngineState.editing.value = true
        syncOverlay()
        toast(getString(R.string.toast_edit_mode_on))
    }

    private fun exitEditing() {
        EngineState.stepListOpen.value = false
        EngineState.reRecordingStepId.value = null
        EngineState.editing.value = false
        EngineState.selectedStepId.value = null
        EngineState.pickingCoordinate.value = false
    }

    private fun select(stepId: String?) {
        EngineState.selectedStepId.value = stepId
        // Re-attaching the card puts it above the canvas; without this it would be underneath and
        // its buttons unreachable.
        if (stepId != null) scope.launch { host.bringToFront(paramCard, paramCardParams) }
    }

    private fun dragStep(stepId: String, handle: Handle, x: Float, y: Float) {
        val step = Workspace.stepById(stepId) as? GestureStep ?: return
        val updated = when (handle) {
            Handle.BODY -> step.movedTo(x, y)
            Handle.END -> step.withEndAt(x, y)
        }
        // Not persisted per sample: a drag would otherwise write the draft file dozens of times.
        // onDragEnd flushes.
        Workspace.updateStep(updated, persist = false)
    }

    private fun pickCoordinate(x: Float, y: Float) {
        val step = Workspace.stepById(EngineState.selectedStepId.value) as? GestureStep
        EngineState.pickingCoordinate.value = false
        if (step == null) return
        Workspace.updateStep(step.movedTo(x, y))
    }

    private fun adjustSelected(transform: (Step) -> Step?) {
        val step = Workspace.stepById(EngineState.selectedStepId.value) ?: return
        transform(step)?.let { Workspace.updateStep(it) }
    }

    private inner class StepListActions : StepListView.Actions {
        override fun onSelectIndex(index: Int) {
            Workspace.steps.value.getOrNull(index)?.let { select(it.id) }
        }

        override fun onPrevious() = step(-1)

        override fun onNext() = step(1)

        /**
         * Moves the selection one step, starting from the end nearest the direction travelled when
         * nothing is selected yet.
         */
        private fun step(delta: Int) {
            val steps = Workspace.steps.value
            if (steps.isEmpty()) return
            val current = steps.indexOfFirst { it.id == EngineState.selectedStepId.value }
            val next = when {
                current < 0 && delta > 0 -> 0
                current < 0 -> steps.lastIndex
                else -> (current + delta).coerceIn(0, steps.lastIndex)
            }
            select(steps[next].id)
        }

        /**
         * Jumping by number is the point of this list.
         *
         * Playback reports the failing step as "47 / 100". Finding marker 47 among a hundred
         * overlapping crosshairs is the problem; typing 47 is not.
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

        override fun onClose() {
            EngineState.stepListOpen.value = false
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

    private inner class ParamCardActions : ParamCardView.Actions {
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
         * Plays from the selected step.
         *
         * The other half of finding a broken step by number: having fixed step 47, checking it should
         * not mean sitting through 1–46 again.
         */
        /**
         * Captures this step's gesture again.
         *
         * Starts immediately, with no countdown, for the same reason resuming a recording does: undo
         * is cheaper than making everyone wait three seconds every time.
         */
        override fun onReRecord() {
            val step = Workspace.stepById(EngineState.selectedStepId.value) as? GestureStep ?: return
            EngineState.reRecordingStepId.value = step.id
            toast(getString(R.string.toast_rerecord_prompt))
        }

        override fun onPlayFromHere() {
            val index = Workspace.steps.value.indexOfFirst { it.id == EngineState.selectedStepId.value }
            if (index < 0) return
            startFromIndex = index
            startPlayback()
        }

        override fun onEditNote() {
            val id = EngineState.selectedStepId.value ?: return
            openWorkspaceDialog(WorkspaceDialogActivity.Mode.NOTE, id)
        }

        override fun onPickCoordinate() {
            EngineState.pickingCoordinate.value = true
            toast(getString(R.string.toast_pick_coordinate))
        }

        override fun onDelete() {
            val id = EngineState.selectedStepId.value ?: return
            EngineState.selectedStepId.value = null
            Workspace.removeStep(id)
            if (Workspace.isEmpty) exitEditing()
        }

        override fun onDone() {
            EngineState.selectedStepId.value = null
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

        override fun onInsertPausePoint() {
            if (EngineState.isReplaying) return
            // Nothing is ever selected while recording, so this appends then, and lands after the
            // selected marker when editing — one call covers both.
            val step = PauseStep()
            Workspace.insertBefore(EngineState.selectedStepId.value, step, currentScreen())
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
                    Workspace.insertBefore(EngineState.selectedStepId.value, step, currentScreen())
                    selectIfEditing(step.id)
                    toast(getString(R.string.toast_wait_inserted, seconds))
                }
            )
        }

        /** Shows or hides the step list. Only meaningful while editing, which is where it is offered. */
        override fun onToggleStepList() {
            EngineState.stepListOpen.value = !EngineState.stepListOpen.value
        }

        override fun onUndo() {
            if (Workspace.undo()) toast(getString(R.string.toast_undone))
        }

        override fun onToggleEdit() {
            if (EngineState.editing.value) exitEditing() else enterEditing()
        }

        override fun onAddTap() {
            val size = host.displaySize()
            val current = settings
            val step = tapStep(
                x = size.x / 2f,
                y = size.y / 2f,
                holdMs = current.defaultTapMs,
                delayBefore = current.defaultGapMs,
            )
            // Inserted before the selection, then selected, so the parameter card is already open on
            // the thing just created — which is where its position gets set.
            Workspace.insertBefore(EngineState.selectedStepId.value, step, currentScreen())
            select(step.id)
        }

        override fun onDeleteSelected() {
            val id = EngineState.selectedStepId.value ?: return
            EngineState.selectedStepId.value = null
            Workspace.removeStep(id)
            if (Workspace.isEmpty) exitEditing()
        }

        override fun onSave() = openWorkspaceDialog(WorkspaceDialogActivity.Mode.SAVE)

        override fun onLoad() = openWorkspaceDialog(WorkspaceDialogActivity.Mode.LOAD)

        override fun onNewClip() = openWorkspaceDialog(WorkspaceDialogActivity.Mode.NEW)

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
         */
        override fun onDismiss() {
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
