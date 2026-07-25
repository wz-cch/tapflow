package com.tapflow.android.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.tapflow.android.R
import com.tapflow.android.data.Repo
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Settings
import com.tapflow.android.data.Step
import com.tapflow.android.overlay.BallIntent
import com.tapflow.android.overlay.CanvasMode
import com.tapflow.android.overlay.CanvasView
import com.tapflow.android.overlay.OverlayHost
import com.tapflow.android.overlay.ToolbarView
import com.tapflow.android.overlay.TransportView
import com.tapflow.android.overlay.buildMarkers
import com.tapflow.android.text.defaultClipName
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var host: OverlayHost
    private lateinit var canvas: CanvasView
    private lateinit var toolbar: ToolbarView
    private lateinit var transport: TransportView

    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var toolbarParams: WindowManager.LayoutParams
    private lateinit var transportParams: WindowManager.LayoutParams

    private lateinit var dispatcher: GestureDispatcher
    private lateinit var recorder: Recorder
    private lateinit var player: Player

    /** Toolbar shape to go back to once a pause is over. */
    private var formBeforePause = ToolbarForm.EXPANDED
    private var toolbarYBeforePause = 0

    private var volumeLongPressJob: Job? = null

    private val settings: Settings get() = Repo.settings.value

    // --- Lifecycle -----------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        Repo.init(this)
        Workspace.restore()

        // Needed for the volume-key fallback. Declared in the config XML too, but setting it here
        // as well keeps it working if the XML is ever trimmed.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        buildOverlay()
        EngineState.serviceRunning.value = true

        observe()
        if (Repo.overlayEnabled.value) attachOverlay()
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
        dispatcher = GestureDispatcher(this)

        canvas = CanvasView(this).apply {
            onGesture = { strokes, down, up -> recorder.onGesture(strokes, down, up) }
        }
        toolbar = ToolbarView(this, ToolbarActions())
        transport = TransportView(this, TransportActions())

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
            onPausedChanged = { paused -> onPausedChanged(paused) },
        )

        val refreshBlocked = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncBlockedAreas() }
        toolbar.addOnLayoutChangeListener(refreshBlocked)
        transport.addOnLayoutChangeListener(refreshBlocked)
    }

    private fun attachOverlay() {
        // Order matters: same-type overlays stack in the order they are added, so the canvas has to
        // go on first or it would swallow every toolbar press while recording.
        updateCanvasFlags()
        host.add(canvas, canvasParams)
        host.add(transport, transportParams)
        host.add(toolbar, toolbarParams)
        syncOverlay()
    }

    private fun detachOverlay() {
        stopRecording(collapseForInput = false)
        player.stop()
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

        toolbar.applyAppearance(current.uiScale, current.uiOpacity)
        toolbar.render(mode, EngineState.toolbarForm.value, steps.size, current.markerDensity)
        host.update(toolbar, toolbarParams)

        canvas.markers = buildMarkers(steps)
        canvas.density = current.markerDensity
        canvas.stepLines = stepLines(steps)
        canvas.highlightNumber = EngineState.progress.value?.step
        canvas.dimAlpha = if (current.dimOverlay && mode == Mode.PLAYING) current.dimAlpha else 0f
        canvas.mode = if (mode == Mode.RECORDING) CanvasMode.RECORDING else CanvasMode.READ_ONLY
        updateCanvasFlags()
        syncBlockedAreas()

        syncTransport()
    }

    private fun syncTransport() {
        if (!host.isAttached(transport)) return
        val current = settings
        val mode = EngineState.mode.value

        val visible = mode != Mode.IDLE
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

    /**
     * Tells the canvas which areas the other two windows cover.
     *
     * Those areas can be neither recorded nor replayed, because Android hands a touch to the topmost
     * window only. Hatching them is the entire mitigation — dodging automatically was considered and
     * rejected as more moving parts than it is worth.
     */
    private fun syncBlockedAreas() {
        val areas = mutableListOf<RectF>()
        listOf(toolbar to toolbarParams, transport to transportParams).forEach { (view, params) ->
            if (!host.isAttached(view) || view.visibility != View.VISIBLE) return@forEach
            if (view.width == 0 || view.height == 0) return@forEach
            areas += RectF(
                params.x.toFloat(),
                params.y.toFloat(),
                (params.x + view.width).toFloat(),
                (params.y + view.height).toFloat(),
            )
        }
        canvas.blockedAreas = areas
    }

    private fun updateCanvasFlags() {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        if (canvas.mode != CanvasMode.RECORDING || canvas.replaying) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (settings.keepScreenOn && EngineState.mode.value != Mode.IDLE) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

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

        EngineState.toolbarForm.value = ToolbarForm.EXPANDED
        toolbar.ballIntent = BallIntent.EXPAND
        EngineState.mode.value = Mode.RECORDING
        canvas.mode = CanvasMode.RECORDING
        canvas.replaying = false
        recorder.restartTiming()
        updateCanvasFlags()

        // The canvas is now intercepting the whole screen, so re-stack the other two windows above
        // it or their buttons become unreachable.
        host.bringToFront(transport, transportParams)
        host.bringToFront(toolbar, toolbarParams)
        syncOverlay()
    }

    private fun stopRecording(collapseForInput: Boolean) {
        if (!EngineState.isRecording) return

        canvas.mode = CanvasMode.READ_ONLY
        canvas.replaying = false
        EngineState.mode.value = Mode.IDLE
        updateCanvasFlags()

        if (collapseForInput) collapseToBall(BallIntent.RESUME_RECORDING)
        syncOverlay()
    }

    // --- Pause handling ------------------------------------------------------

    /**
     * Gets the toolbar out of the way of the on-screen keyboard while paused.
     *
     * TYPE_ACCESSIBILITY_OVERLAY sits above TYPE_INPUT_METHOD, so the expanded toolbar — roughly 40%
     * of the screen height hugging one edge — lands squarely on the keyboard's Q/A/Z column. Without
     * this, pressing A would hit a toolbar button and typing a verification code would be
     * impossible, which would make pause points useless.
     */
    private fun onPausedChanged(paused: Boolean) {
        if (paused) collapseToBall(BallIntent.RESUME_PLAYBACK) else restoreFromBall()
    }

    private fun collapseToBall(intent: BallIntent) {
        formBeforePause = EngineState.toolbarForm.value
        toolbarYBeforePause = toolbarParams.y

        toolbar.ballIntent = intent
        EngineState.toolbarForm.value = ToolbarForm.BALL

        // Keyboards occupy the lower half, so park the ball in the upper quarter.
        val limit = host.displaySize().y / 4
        toolbarParams.y = min(toolbarParams.y, limit)
        host.update(toolbar, toolbarParams)
    }

    private fun restoreFromBall() {
        toolbar.ballIntent = BallIntent.EXPAND
        EngineState.toolbarForm.value = formBeforePause
        toolbarParams.y = toolbarYBeforePause
        host.update(toolbar, toolbarParams)
    }

    // --- Window movement -----------------------------------------------------

    private fun moveToolbar(dx: Int, dy: Int) {
        toolbarParams.x += dx
        toolbarParams.y += dy
        clampWindows()
        host.update(toolbar, toolbarParams)
        syncBlockedAreas()
    }

    private fun moveTransport(dx: Int, dy: Int) {
        transportParams.x += dx
        transportParams.y += dy
        clampWindows()
        host.update(transport, transportParams)
        syncBlockedAreas()
    }

    private fun clampWindows() {
        val size = host.displaySize()
        toolbarParams.x = toolbarParams.x.coerceIn(0, max(0, size.x - max(toolbar.width, 1)))
        toolbarParams.y = toolbarParams.y.coerceIn(0, max(0, size.y - max(toolbar.height, 1)))
        transportParams.x = transportParams.x.coerceIn(0, max(0, size.x - max(transport.width, 1)))
        transportParams.y = transportParams.y.coerceIn(0, max(0, size.y - max(transport.height, 1)))
    }

    /** Snaps the toolbar to whichever side edge is closer, and remembers where it ended up. */
    private fun settleToolbar() {
        val size = host.displaySize()
        val centre = toolbarParams.x + toolbar.width / 2
        toolbarParams.x = if (centre < size.x / 2) 0 else max(0, size.x - toolbar.width)
        host.update(toolbar, toolbarParams)
        Repo.writeInt(PREF_TOOLBAR_X, toolbarParams.x)
        Repo.writeInt(PREF_TOOLBAR_Y, toolbarParams.y)
        syncBlockedAreas()
    }

    private fun settleTransport() {
        Repo.writeInt(PREF_TRANSPORT_X, transportParams.x)
        Repo.writeInt(PREF_TRANSPORT_Y, transportParams.y)
        syncBlockedAreas()
    }

    // --- Actions -------------------------------------------------------------

    private fun currentScreen(): ScreenSpec {
        val size = host.displaySize()
        return ScreenSpec(size.x, size.y, host.rotation())
    }

    private fun startPlayback() {
        val steps = Workspace.steps.value
        if (steps.isEmpty()) return
        player.play(steps, Workspace.screen, settings.defaultLoopCount)
    }

    private fun save(asNew: Boolean) {
        val now = System.currentTimeMillis()
        val clip = Workspace.commit(defaultClipName(resources, now), now, asNew)
        if (clip == null) {
            toast(getString(R.string.toast_nothing_to_save))
        } else {
            toast(getString(R.string.toast_saved, clip.name))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
            Workspace.appendPausePoint()
            toast(getString(R.string.toast_pause_inserted))
            // Stopping is the point: the user is about to do this step by hand, so the canvas has to
            // let touches through and the toolbar has to clear the keyboard area.
            if (EngineState.isRecording) stopRecording(collapseForInput = true)
        }

        override fun onUndo() {
            if (Workspace.undo()) toast(getString(R.string.toast_undone))
        }

        override fun onSave() = save(asNew = false)

        override fun onSaveAsNew() = save(asNew = true)

        override fun onCycleDensity() =
            Repo.updateSettings { it.copy(markerDensity = it.markerDensity.next()) }

        override fun onDismiss() {
            EngineState.toolbarForm.value = ToolbarForm.HANDLE
            settleToolbar()
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
