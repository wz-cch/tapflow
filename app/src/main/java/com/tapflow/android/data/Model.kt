package com.tapflow.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Layer 1 — atomic actions.
//
// Taps, long presses, swipes and multi-touch gestures are all stored as
// strokes; there are deliberately no separate Tap/Swipe types. A tap is one
// stroke with a single sample, a swipe is one stroke with many samples, and a
// two-finger pinch is two strokes. The replay engine therefore has exactly one
// code path (one addStroke call per stroke) instead of three, and multi-touch
// needs no extra model. Semantics are recovered for display via
// GestureStep.kind and rendered by text/StepText.kt.
//
// This file must stay free of Android framework dependencies and of any
// user-facing text: it has to be unit-testable on the plain JVM, and every
// string the user reads has to live in strings.xml for localisation.
// ---------------------------------------------------------------------------

/** One sample on a gesture path. [t] is the millisecond offset from the start of its stroke. */
@Serializable
data class Pt(val x: Float, val y: Float, val t: Long)

/**
 * The complete path of a single finger.
 *
 * [startOffset] is the millisecond offset from the start of the whole gesture; it is non-zero
 * for multi-touch, where fingers touch down at different moments.
 */
@Serializable
data class Stroke(
    val points: List<Pt>,
    val startOffset: Long = 0,
) {
    val start: Pt get() = points.first()
    val end: Pt get() = points.last()
    val duration: Long get() = points.last().t

    /** Distance from the start to the farthest sample. Used to tell a tap from a swipe. */
    val travel: Float get() = points.maxOf { hypot(it.x - start.x, it.y - start.y) }
}

/**
 * One action in a script.
 *
 * [id] is a stable identity; on-screen dragging, selection, deletion and reordering all key off
 * it. [delayBefore] is how long to wait before running this step; it is derived at record time
 * from the gap between consecutive touches, which is what makes replay keep the original rhythm.
 */
@Serializable
sealed interface Step {
    val id: String
    val delayBefore: Long
}

/**
 * A step that can run more than once in place.
 *
 * Only the kinds that *do* something implement this. A timed wait repeated ten times is just a longer
 * wait, and a manual pause repeated ten times is ten stops — neither is a thing anyone means, so
 * [PauseStep] deliberately does not get these fields rather than getting them and ignoring them.
 *
 * [repeatIntervalMs] is separate from [Step.delayBefore] on purpose, and the distinction is worth
 * keeping straight: `delayBefore` is the rhythm between two *different* actions, measured at record
 * time; this is the gap between repetitions of the *same* action. Folding them into one field would
 * make `delayBefore` mean one thing at [repeat] 1 and another above it — the same field with two
 * meanings depending on a second field — and would make "wait 2s after the previous step, then tap ten
 * times 200ms apart" inexpressible.
 *
 * Both default to the no-op values, so clips saved before these existed load unchanged and behave
 * identically: at [repeat] 1 the interval is never read.
 */
sealed interface RepeatableStep : Step {
    /** How many times to run in place. 1 is once. */
    val repeat: Int

    /** Gap between repetitions. Only read when [repeat] is above 1. */
    val repeatIntervalMs: Long

    /** Repetitions beyond the first, which is what the interval is paid for. */
    val extraPasses: Int get() = (repeat - 1).coerceAtLeast(0)
}

/** What a [GestureStep] means to a human. Drives both the label and the on-screen marker style. */
enum class GestureKind { TAP, LONG_PRESS, SWIPE, MULTI_TOUCH }

@Serializable
@SerialName("gesture")
data class GestureStep(
    override val id: String = newId(),
    val strokes: List<Stroke>,
    override val delayBefore: Long = 0,
    override val repeat: Int = 1,
    override val repeatIntervalMs: Long = 0,
) : Step, RepeatableStep {

    /** From the first finger touching down to the last one lifting. */
    val duration: Long get() = strokes.maxOf { it.startOffset + it.duration }

    /** Where the sequence-number marker is drawn. */
    val anchor: Pt get() = strokes.first().start

    val kind: GestureKind
        get() = when {
            strokes.size > 1 -> GestureKind.MULTI_TOUCH
            strokes[0].travel >= SWIPE_TRAVEL_PX -> GestureKind.SWIPE
            duration >= LONG_PRESS_MS -> GestureKind.LONG_PRESS
            else -> GestureKind.TAP
        }

    companion object {
        const val LONG_PRESS_MS = 500L

        /**
         * Only decides the label and marker style, never replay behaviour, so a fixed pixel
         * threshold is good enough and saves threading display density through the model.
         */
        const val SWIPE_TRAVEL_PX = 24f
    }
}

// --- Editing transforms -----------------------------------------------------
//
// Pure functions on the model, so on-screen editing has no geometry logic of its own and this can
// all be exercised without a device.

/** Shifts every sample of every stroke. Used when a marker is dragged by its number badge. */
fun GestureStep.translated(dx: Float, dy: Float): GestureStep = copy(
    strokes = strokes.map { stroke ->
        stroke.copy(points = stroke.points.map { it.copy(x = it.x + dx, y = it.y + dy) })
    }
)

/** Puts the anchor at an absolute position, keeping the shape of the gesture. */
fun GestureStep.movedTo(x: Float, y: Float): GestureStep =
    translated(x - anchor.x, y - anchor.y)

/**
 * Rescales the timeline so the whole gesture takes [ms].
 *
 * A stroke captured as a single sample has no timeline to scale, so it gains an explicit end sample
 * instead — which is also what makes it a valid non-empty path at dispatch time.
 */
fun GestureStep.withDuration(ms: Long): GestureStep {
    val target = ms.coerceAtLeast(1L)
    val current = duration

    if (current <= 0L) {
        return copy(
            strokes = strokes.map { stroke ->
                stroke.copy(points = stroke.points + stroke.points.last().copy(t = target))
            }
        )
    }

    val factor = target.toDouble() / current
    return copy(
        strokes = strokes.map { stroke ->
            stroke.copy(
                startOffset = (stroke.startOffset * factor).roundToLong(),
                points = stroke.points.map { it.copy(t = (it.t * factor).roundToLong()) },
            )
        }
    )
}

/**
 * Moves the end of a single-stroke gesture to an absolute position.
 *
 * The path is rotated and scaled about its start rather than merely stretched along one axis, so a
 * curved swipe keeps its shape when its direction or length is changed. Multi-finger gestures are
 * left alone: there is no single end point to speak of.
 */
fun GestureStep.withEndAt(x: Float, y: Float): GestureStep {
    val stroke = strokes.singleOrNull() ?: return this
    val start = stroke.start
    val oldDx = stroke.end.x - start.x
    val oldDy = stroke.end.y - start.y
    val oldLengthSquared = oldDx * oldDx + oldDy * oldDy

    // A tap has no direction to rotate about, so it becomes a straight two-sample swipe.
    if (oldLengthSquared < MIN_DIRECTION_LENGTH_SQUARED) {
        return copy(
            strokes = listOf(
                stroke.copy(points = listOf(start, Pt(x, y, stroke.duration.coerceAtLeast(1L))))
            )
        )
    }

    // Complex division: the rotation-and-scale that maps the old end vector onto the new one.
    val newDx = x - start.x
    val newDy = y - start.y
    val a = (newDx * oldDx + newDy * oldDy) / oldLengthSquared
    val b = (newDy * oldDx - newDx * oldDy) / oldLengthSquared

    return copy(
        strokes = listOf(
            stroke.copy(
                points = stroke.points.map { point ->
                    val px = point.x - start.x
                    val py = point.y - start.y
                    point.copy(x = start.x + a * px - b * py, y = start.y + b * px + a * py)
                }
            )
        )
    )
}

/**
 * Sets the repeat count and its interval.
 *
 * An extension rather than a member because `copy` is generated per data class and cannot be declared
 * on the interface. Exhaustive over the sealed hierarchy, so a third repeatable kind would not compile
 * until it was handled here.
 */
fun RepeatableStep.withRepeat(repeat: Int, intervalMs: Long): Step = when (this) {
    is GestureStep -> copy(repeat = repeat, repeatIntervalMs = intervalMs)
    is GlobalStep -> copy(repeat = repeat, repeatIntervalMs = intervalMs)
}

private const val MIN_DIRECTION_LENGTH_SQUARED = 1f

// There is deliberately no synthesise-a-tap-at-a-position helper. The toolbar's add button used to
// conjure one at the centre of the screen for the user to drag into place, which was two acts to get
// one point and restricted a manually added step to a plain tap. It now captures a real gesture
// through the same path as recording, so a step created by hand can be anything a recorded one can.

/**
 * A system-level action: back, home, recents, notifications.
 *
 * Repeatable like a gesture, and one of the better uses for it — "press back three times" to unwind a
 * few screens is a single step rather than three identical ones.
 */
@Serializable
@SerialName("global")
data class GlobalStep(
    override val id: String = newId(),
    val kind: GlobalKind,
    override val delayBefore: Long = 0,
    override val repeat: Int = 1,
    override val repeatIntervalMs: Long = 0,
) : Step, RepeatableStep

/**
 * A step that waits. Either for a stretch of time, or for the user.
 *
 * One type covers both because they are the same idea — stop here for a moment — differing only in
 * what releases it: a timer, or a finger. A separate WaitStep existed and was folded in here; two
 * types meant two of everything (marker, list text, execution branch) for one concept, which cuts
 * against the rule in §1.4 that there is exactly one pause mechanism.
 *
 * [ms] of zero means wait for the user, and is the default so that clips saved before this field
 * existed keep loading unchanged. A non-zero value just delays and never enters PAUSED, so PAUSED
 * still means only one thing: something needs a human.
 *
 * The manual form is also why inserting one stops a recording — you are about to do that step by
 * hand, and the canvas has to let your touches through. A timed one does not stop recording, because
 * there is nothing for you to do.
 *
 * [note] defaults to blank and is not prompted for on insert. It exists because a script with
 * several of these makes it easy to forget what each one is for — fill it in later if you want.
 */
@Serializable
@SerialName("pause")
data class PauseStep(
    override val id: String = newId(),
    val note: String = "",
    val ms: Long = 0,
    override val delayBefore: Long = 0,
) : Step {
    /** Whether this one releases itself, rather than waiting for the user. */
    val isTimed: Boolean get() = ms > 0
}

@Serializable
enum class GlobalKind {
    @SerialName("back") BACK,
    @SerialName("home") HOME,
    @SerialName("recents") RECENTS,
    @SerialName("notifications") NOTIFICATIONS,
}

// ---------------------------------------------------------------------------
// Layer 2 — clip: the set of actions saved when the user presses the save key.
// ---------------------------------------------------------------------------

/** Screen geometry at record time. Replay uses it to rescale coordinates. */
@Serializable
data class ScreenSpec(val width: Int, val height: Int, val rotation: Int)

@Serializable
data class Clip(
    val id: String = newId(),
    val name: String,
    val steps: List<Step>,
    val screen: ScreenSpec,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
) {
    val stepCount: Int get() = steps.size

    /** Only the ones that wait for a human — a timed one needs no attention. */
    val pauseCount: Int get() = steps.count { it is PauseStep && !it.isTimed }

    /**
     * Roughly how long one pass takes at normal speed. Display only.
     * A pause waiting for the user counts as zero, because its length is up to them.
     */
    val estimatedDurationMs: Long
        get() = steps.sumOf { step ->
            val once = when (step) {
                is GestureStep -> step.duration
                is GlobalStep -> GLOBAL_ACTION_COST_MS
                is PauseStep -> step.ms
            }
            // A repeated step pays for its action every pass and for the interval between them, but for
            // its lead delay only once — which is exactly the split that keeps the two fields distinct.
            val repeatable = step as? RepeatableStep
            val passes = repeatable?.repeat?.coerceAtLeast(1) ?: 1
            val gaps = (repeatable?.extraPasses ?: 0) * (repeatable?.repeatIntervalMs ?: 0)
            step.delayBefore + once * passes + gaps
        }

    private companion object {
        const val GLOBAL_ACTION_COST_MS = 300L
    }
}

// ---------------------------------------------------------------------------
// Layer 3 — flow: several clips chained into one run (M3).
// ---------------------------------------------------------------------------

@Serializable
sealed interface Node

@Serializable
@SerialName("clip")
data class ClipNode(val clipId: String, val repeat: Int = 1) : Node

@Serializable
@SerialName("wait")
data class WaitNode(val ms: Long) : Node

/**
 * Wait for given text to appear on screen before continuing.
 * On timeout it enters PAUSED rather than aborting, so the user can take over.
 */
@Serializable
@SerialName("await")
data class AwaitTextNode(
    val text: String,
    val matchMode: MatchMode = MatchMode.CONTAINS,
    val timeoutMs: Long = 15_000,
) : Node

@Serializable
@SerialName("global")
data class GlobalNode(val kind: GlobalKind) : Node

// There is deliberately NO pause node at the flow layer. To stop between clips, put a PauseStep at
// the end of the preceding clip — the app has exactly one pause mechanism. If it turns out that
// "same clip reused across flows, pausing in one but not the other" actually happens in practice,
// add a pauseAfter flag to ClipNode then. Do not build it up front.

@Serializable
enum class MatchMode {
    @SerialName("contains") CONTAINS,
    @SerialName("exact") EXACT,
    @SerialName("view_id") VIEW_ID,
}

/**
 * Autosaved workspace draft.
 *
 * The workspace is what the user is actually manipulating on screen before pressing save. It is
 * written out on every change so a hard-recorded sequence survives the service being restarted.
 *
 * [dirty] is what decides whether it comes back. The draft exists to protect work that was never
 * saved; restoring unconditionally meant that after saving a clip and reopening, the app looked like
 * it had loaded a file on its own — surprising, and not what the draft is for.
 */
@Serializable
data class WorkspaceSnapshot(
    val steps: List<Step> = emptyList(),
    val sourceClipId: String? = null,
    val screen: ScreenSpec? = null,
    val dirty: Boolean = false,
)

@Serializable
data class Flow(
    val id: String = newId(),
    val name: String,
    val nodes: List<Node>,
    /** 0 means loop forever until stopped. */
    val loopCount: Int = 1,
    /** Countdown after pressing play, so there is time to switch to the target app. */
    val startDelayMs: Long = 3000,
    val speed: Float = 1f,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)
