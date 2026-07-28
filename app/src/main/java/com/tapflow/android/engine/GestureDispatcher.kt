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
enum class GestureOutcome {
    COMPLETED,
    REFUSED,
    CANCELLED,

    /**
     * The framework never got hold of a MotionEventInjector.
     *
     * AccessibilityManagerService waits up to WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS — one second — for
     * the injector, then reports the gesture as failed. From this side that is indistinguishable from
     * a cancellation except by its timing: it arrives at almost exactly 1000ms no matter how long the
     * gesture was, so a 60ms tap and a 1100ms swipe both fail after the same interval.
     *
     * The injector is installed with the accessibility input filter when the system sees a service
     * declaring canPerformGestures, so this means the filter chain was not rebuilt for this binding.
     * No amount of retrying the gesture helps; the service has to be re-registered.
     */
    INJECTOR_MISSING,

    SKIPPED,
}

/** Turns a [Step] into an actual system gesture. The only place that calls dispatchGesture. */
class GestureDispatcher(
    private val service: AccessibilityService,
    /** Called for every dispatched gesture so failures can be surfaced rather than swallowed. */
    private val report: (GestureOutcome) -> Unit = {},
    /**
     * Asked to re-register the service when the injector is missing, which nudges the framework into
     * rebuilding the accessibility input filter. Returns true if it was worth waiting and retrying.
     */
    private val renewRegistration: () -> Boolean = { false },
) {

    suspend fun perform(step: Step, scale: ScaleSpec, settings: Settings): GestureOutcome {
        val outcome = when (step) {
            is GestureStep -> dispatch(step, scale, settings)
            is GlobalStep ->
                if (service.performGlobalAction(step.kind.toGlobalActionId())) {
                    GestureOutcome.COMPLETED
                } else {
                    GestureOutcome.REFUSED
                }
            // Waits are handled by the caller so it can honour pause requests mid-wait, and pause
            // points are not something to dispatch at all.
            is PauseStep -> GestureOutcome.SKIPPED
        }
        if (outcome != GestureOutcome.SKIPPED) report(outcome)
        return outcome
    }

    private suspend fun dispatch(step: GestureStep, scale: ScaleSpec, settings: Settings): GestureOutcome {
        val gesture = build(step, scale, settings) ?: return GestureOutcome.REFUSED

        Diag.log("dispatch ${gesture.describeForLog()} scale=${"%.2f".format(scale.sx)}x${"%.2f".format(scale.sy)}")

        val expected = gesture.strokeDuration()
        val started = SystemClock.uptimeMillis()
        val first = await(gesture)
        val elapsed = SystemClock.uptimeMillis() - started
        Diag.log("  -> $first after ${elapsed}ms of ${expected}ms")
        if (first != GestureOutcome.CANCELLED) return first

        // Timing tells the two failures apart. The framework's wait for a missing MotionEventInjector
        // lands in a narrow band around one second whatever the gesture was, so a tap failing after
        // the same interval as a long swipe is the giveaway.
        if (elapsed in INJECTOR_TIMEOUT_LOW..INJECTOR_TIMEOUT_HIGH) {
            Diag.log("  looks like the MotionEventInjector was missing (framework waits ~1000ms)")
            if (!renewRegistration()) return GestureOutcome.INJECTOR_MISSING

            Diag.log("  re-registered the service; retrying once")
            delay(REREGISTER_DELAY_MS)
            val healed = await(gesture)
            Diag.log("  -> after re-register $healed")
            return if (healed == GestureOutcome.COMPLETED) healed else GestureOutcome.INJECTOR_MISSING
        }

        // A cancellation that arrives almost immediately means the gesture never really ran: a stray
        // real touch, a window change, or another gesture took the stream. Those are transient, so one
        // retry recovers instead of failing the whole step. A cancellation that arrives part way is
        // left alone — retrying would replay half a swipe on top of itself.
        if (elapsed > expected / 3 + EARLY_CANCEL_GRACE_MS) return first

        Log.i(TAG, "Gesture cancelled after ${elapsed}ms of ${expected}ms; retrying once")
        Diag.log("  retrying once (cancelled early)")
        delay(RETRY_DELAY_MS)
        val retryStarted = SystemClock.uptimeMillis()
        val second = await(gesture)
        Diag.log("  -> retry $second after ${SystemClock.uptimeMillis() - retryStarted}ms")
        return second
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

    /**
     * Stroke bounds, so a logcat line is enough to tell whether the coordinates were sane.
     *
     * computeBounds(RectF, Boolean) is deprecated as of API 35, but its replacement — the
     * single-argument overload — only exists from API 36, and this project compiles against 35. The
     * boolean was ignored by the platform long before the deprecation, so there is nothing to lose
     * by keeping it. Drop the suppression once compileSdk reaches 36.
     */
    @Suppress("DEPRECATION")
    fun GestureDescription.describeForLog(): String = buildString {
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

    private companion object {
        const val TAG = "GestureDispatcher"

        /** Allowance on top of a third of the duration before a cancellation counts as "part way". */
        const val EARLY_CANCEL_GRACE_MS = 40L
        const val RETRY_DELAY_MS = 60L

        /** Band around the framework's one-second wait for the injector. */
        const val INJECTOR_TIMEOUT_LOW = 850L
        const val INJECTOR_TIMEOUT_HIGH = 1250L
        const val REREGISTER_DELAY_MS = 350L
    }
}

/**
 * The framework constant for a global action.
 *
 * Top-level rather than private to the dispatcher, because two places carry one out: replay, and
 * recording — where inserting a system key also performs it so the screen keeps up. A second copy of
 * this mapping is a second thing to keep in step.
 */
fun GlobalKind.toGlobalActionId(): Int = when (this) {
    GlobalKind.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
    GlobalKind.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
    GlobalKind.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
    GlobalKind.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
}
