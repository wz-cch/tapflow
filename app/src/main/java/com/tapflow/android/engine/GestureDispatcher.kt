package com.tapflow.android.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
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
import kotlinx.coroutines.delay
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

/**
 * What became of a dispatched gesture.
 *
 * The distinction matters for diagnosis. [REFUSED] means the system would not accept the gesture at
 * all — the service is not ready, another gesture is still running, or the description was invalid.
 * [CANCELLED] means it was accepted and then interrupted, which is what a target app blocking
 * injected or obscured touches looks like from here. Both used to be swallowed silently, so
 * "nothing happened" was indistinguishable from "it ran and the app ignored it".
 */
enum class GestureOutcome { COMPLETED, REFUSED, CANCELLED, SKIPPED }

/** Turns a [Step] into an actual system gesture. The only place that calls dispatchGesture. */
class GestureDispatcher(
    private val service: AccessibilityService,
    /** Called for every dispatched gesture so failures can be surfaced rather than swallowed. */
    private val report: (GestureOutcome) -> Unit = {},
) {

    suspend fun perform(step: Step, scale: ScaleSpec, settings: Settings): GestureOutcome {
        val outcome = when (step) {
            is GestureStep -> dispatch(step, scale, settings)
            is GlobalStep ->
                if (service.performGlobalAction(step.kind.toGlobalAction())) {
                    GestureOutcome.COMPLETED
                } else {
                    GestureOutcome.REFUSED
                }
            // Waits are handled by the caller so it can honour pause requests mid-wait, and pause
            // points are not something to dispatch at all.
            is WaitStep, is PauseStep -> GestureOutcome.SKIPPED
        }
        if (outcome != GestureOutcome.SKIPPED) report(outcome)
        return outcome
    }

    private suspend fun dispatch(step: GestureStep, scale: ScaleSpec, settings: Settings): GestureOutcome {
        val gesture = build(step, scale, settings) ?: return GestureOutcome.REFUSED

        val started = SystemClock.uptimeMillis()
        val first = await(gesture)
        if (first != GestureOutcome.CANCELLED) return first

        // A cancellation that arrives almost immediately means the gesture never really ran: a stray
        // real touch, a window change, or another gesture took the stream. Those are transient, so one
        // retry recovers instead of failing the whole step. A cancellation that arrives part way is
        // left alone — retrying would replay half a swipe on top of itself.
        val elapsed = SystemClock.uptimeMillis() - started
        val expected = gesture.strokeDuration()
        if (elapsed > expected / 3 + EARLY_CANCEL_GRACE_MS) return first

        Log.i(TAG, "Gesture cancelled after ${elapsed}ms of ${expected}ms; retrying once")
        delay(RETRY_DELAY_MS)
        return await(gesture)
    }

    /** Longest stroke in the gesture, which is how long a completed dispatch should have taken. */
    private fun GestureDescription.strokeDuration(): Long {
        var longest = 0L
        for (index in 0 until strokeCount) {
            val stroke = getStroke(index)
            longest = maxOf(longest, stroke.startTime + stroke.duration)
        }
        return longest
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

    private suspend fun await(gesture: GestureDescription): GestureOutcome =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription) {
                    if (continuation.isActive) continuation.resume(GestureOutcome.COMPLETED)
                }

                override fun onCancelled(description: GestureDescription) {
                    Log.w(TAG, "Gesture cancelled: ${gesture.describeForLog()}")
                    if (continuation.isActive) continuation.resume(GestureOutcome.CANCELLED)
                }
            }

            val accepted = runCatching { service.dispatchGesture(gesture, callback, null) }
                .onFailure { Log.w(TAG, "dispatchGesture threw", it) }
                .getOrDefault(false)

            // When the call is refused outright the callback never fires, so resume here or the
            // player would hang forever on this step.
            if (!accepted) {
                Log.w(TAG, "Gesture refused: ${gesture.describeForLog()}")
                if (continuation.isActive) continuation.resume(GestureOutcome.REFUSED)
            }
        }

    /** Stroke bounds, so a logcat line is enough to tell whether the coordinates were sane. */
    private fun GestureDescription.describeForLog(): String = buildString {
        append("strokes=").append(strokeCount)
        for (index in 0 until strokeCount) {
            val bounds = android.graphics.RectF()
            getStroke(index).path.computeBounds(bounds, false)
            append(" [").append(bounds.left.toInt()).append(',').append(bounds.top.toInt())
            append("..").append(bounds.right.toInt()).append(',').append(bounds.bottom.toInt())
            append(" ").append(getStroke(index).duration).append("ms]")
        }
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

        /** Allowance on top of a third of the duration before a cancellation counts as "part way". */
        const val EARLY_CANCEL_GRACE_MS = 40L
        const val RETRY_DELAY_MS = 60L
    }
}
