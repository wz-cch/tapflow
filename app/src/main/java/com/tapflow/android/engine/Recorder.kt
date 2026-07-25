package com.tapflow.android.engine

import android.os.SystemClock
import android.view.Choreographer
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Settings
import com.tapflow.android.data.Stroke
import com.tapflow.android.overlay.CanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Turns captured touches into steps, and pushes each one back down so the screen advances.
 *
 * The accessibility API gives no access to raw touch coordinates from other apps, so recording has
 * to intercept them with a full-screen overlay — which means the target app never sees them. Left
 * like that, the screen would stay frozen on the first page and any multi-step flow would be
 * impossible to record. So each finished gesture is immediately re-issued downwards with the canvas
 * temporarily non-touchable, which also avoids the injected events echoing back into the capture.
 */
class Recorder(
    private val scope: CoroutineScope,
    private val dispatcher: GestureDispatcher,
    private val canvas: CanvasView,
    private val setCanvasTouchable: (Boolean) -> Unit,
    private val currentScreen: () -> ScreenSpec,
    private val settings: () -> Settings,
) {

    private var lastEndUptime = 0L

    /** True until the first gesture of a session, and after every resume. See [restartTiming]. */
    private var timingRestarted = true

    private var busy = false

    /**
     * Called whenever recording starts or resumes.
     *
     * Resets the timing baseline so the first gesture after resuming does not inherit however long
     * the user spent typing a verification code — replay would otherwise sit there for 30 seconds.
     */
    fun restartTiming() {
        timingRestarted = true
        lastEndUptime = SystemClock.uptimeMillis()
    }

    fun onGesture(strokes: List<Stroke>, downUptime: Long, upUptime: Long) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                record(strokes, downUptime, upUptime)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun record(strokes: List<Stroke>, downUptime: Long, upUptime: Long) {
        val current = settings()

        val delayBefore = if (timingRestarted) {
            current.defaultGapMs
        } else {
            (downUptime - lastEndUptime).coerceIn(0L, Timing.MAX_RECORDED_GAP_MS)
        }
        timingRestarted = false

        val step = GestureStep(strokes = strokes, delayBefore = delayBefore)
        Workspace.append(step, currentScreen())
        lastEndUptime = upUptime

        if (!current.replayEachGesture) return

        canvas.replaying = true
        setCanvasTouchable(false)
        awaitWindowSettled()

        // A faithful re-issue of what the user just did: no jitter, no speed scaling, no rescaling.
        dispatcher.perform(
            step,
            ScaleSpec.identity(currentScreen()),
            current.copy(jitterRadiusPx = 0, jitterTimePercent = 0, speed = 1f),
        )

        delay(current.replayDelayMs)
        setCanvasTouchable(true)
        canvas.replaying = false

        // Charging the replay time to the user would inflate the next step's delayBefore.
        lastEndUptime = SystemClock.uptimeMillis()
    }

    /**
     * Waits for the non-touchable flag to actually take effect.
     *
     * Changing window flags is an IPC to system_server, so a single frame is not a guarantee. One
     * frame plus a short grace period is cheap and has proven enough in practice; if a device turns
     * out to still echo the injected gesture back into capture, this is the knob to turn.
     */
    private suspend fun awaitWindowSettled() {
        awaitFrame()
        delay(WINDOW_SETTLE_MS)
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        Choreographer.getInstance().postFrameCallback {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    private companion object {
        const val WINDOW_SETTLE_MS = 24L
    }
}
