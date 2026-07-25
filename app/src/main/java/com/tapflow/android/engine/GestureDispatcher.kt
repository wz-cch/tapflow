package com.tapflow.android.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.GlobalKind
import com.tapflow.android.data.GlobalStep
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Settings
import com.tapflow.android.data.Step
import com.tapflow.android.data.Stroke
import com.tapflow.android.data.WaitStep
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * How recorded coordinates map onto the current screen.
 *
 * Only linear scaling is done — there is no image matching — so a clip recorded on one device is
 * approximate on another. [maxX] and [maxY] exist because StrokeDescription rejects paths whose
 * bounds fall outside the display.
 */
data class ScaleSpec(val sx: Float, val sy: Float, val maxX: Float, val maxY: Float) {
    companion object {
        fun of(recorded: ScreenSpec?, current: ScreenSpec): ScaleSpec {
            val sx = if (recorded == null || recorded.width <= 0) 1f
            else current.width.toFloat() / recorded.width
            val sy = if (recorded == null || recorded.height <= 0) 1f
            else current.height.toFloat() / recorded.height
            return ScaleSpec(sx, sy, (current.width - 1).toFloat(), (current.height - 1).toFloat())
        }

        fun identity(current: ScreenSpec) = ScaleSpec(1f, 1f, (current.width - 1).toFloat(), (current.height - 1).toFloat())
    }
}

/** Turns a [Step] into an actual system gesture. The only place that calls dispatchGesture. */
class GestureDispatcher(private val service: AccessibilityService) {

    /** Returns false when the system refused or cancelled the gesture. */
    suspend fun perform(step: Step, scale: ScaleSpec, settings: Settings): Boolean = when (step) {
        is GestureStep -> dispatch(step, scale, settings)
        is GlobalStep -> service.performGlobalAction(step.kind.toGlobalAction())
        // Waits are handled by the caller so it can honour pause requests mid-wait, and pause
        // points are not something to dispatch at all.
        is WaitStep, is PauseStep -> true
    }

    private suspend fun dispatch(step: GestureStep, scale: ScaleSpec, settings: Settings): Boolean {
        val gesture = build(step, scale, settings) ?: return false
        return await(gesture)
    }

    private fun build(step: GestureStep, scale: ScaleSpec, settings: Settings): GestureDescription? {
        val strokes = step.strokes.take(GestureDescription.getMaxStrokeCount())
        if (strokes.isEmpty()) return null

        // One offset for the whole gesture, not per sample: jittering each point individually would
        // turn a swipe into a zigzag, and would break the geometry of a pinch.
        val offset = randomOffset(settings.jitterRadiusPx)

        val builder = GestureDescription.Builder()
        var added = 0
        for (stroke in strokes) {
            val description = strokeDescription(stroke, scale, offset, settings) ?: continue
            builder.addStroke(description)
            added++
        }
        if (added == 0) return null

        return runCatching { builder.build() }
            .onFailure { Log.w(TAG, "Rejected gesture description", it) }
            .getOrNull()
    }

    private fun strokeDescription(
        stroke: Stroke,
        scale: ScaleSpec,
        offset: Pair<Float, Float>,
        settings: Settings,
    ): GestureDescription.StrokeDescription? {
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            val x = (point.x * scale.sx + offset.first).coerceIn(0f, scale.maxX)
            val y = (point.y * scale.sy + offset.second).coerceIn(0f, scale.maxY)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // A path holding only a moveTo counts as empty and StrokeDescription throws on it, so a
        // single-sample stroke needs a degenerate line to become a real segment. The recorder
        // normally closes every stroke with a release sample, so this only covers manually added
        // taps and older drafts.
        if (stroke.points.size == 1) {
            val point = stroke.points[0]
            path.lineTo(
                (point.x * scale.sx + offset.first).coerceIn(0f, scale.maxX),
                (point.y * scale.sy + offset.second).coerceIn(0f, scale.maxY),
            )
        }

        val duration = Timing.replayDuration(
            stroke.duration.coerceAtLeast(settings.defaultTapMs),
            settings,
        ).coerceAtMost(GestureDescription.getMaxGestureDuration())

        return runCatching {
            GestureDescription.StrokeDescription(path, stroke.startOffset.coerceAtLeast(0), duration)
        }.onFailure { Log.w(TAG, "Rejected stroke", it) }.getOrNull()
    }

    private suspend fun await(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(description: GestureDescription) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            val accepted = runCatching { service.dispatchGesture(gesture, callback, null) }
                .onFailure { Log.w(TAG, "dispatchGesture threw", it) }
                .getOrDefault(false)

            // When the call is refused outright the callback never fires, so resume here or the
            // player would hang forever on this step.
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    private fun randomOffset(radiusPx: Int): Pair<Float, Float> {
        if (radiusPx <= 0) return 0f to 0f
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val distance = Random.nextDouble(0.0, radiusPx.toDouble())
        return (cos(angle) * distance).toFloat() to (sin(angle) * distance).toFloat()
    }

    private fun GlobalKind.toGlobalAction(): Int = when (this) {
        GlobalKind.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        GlobalKind.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        GlobalKind.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        GlobalKind.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
    }

    private companion object {
        const val TAG = "GestureDispatcher"
    }
}
