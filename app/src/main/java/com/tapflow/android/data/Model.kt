package com.tapflow.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.hypot

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

/** What a [GestureStep] means to a human. Drives both the label and the on-screen marker style. */
enum class GestureKind { TAP, LONG_PRESS, SWIPE, MULTI_TOUCH }

@Serializable
@SerialName("gesture")
data class GestureStep(
    override val id: String = newId(),
    val strokes: List<Stroke>,
    override val delayBefore: Long = 0,
) : Step {

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

@Serializable
@SerialName("global")
data class GlobalStep(
    override val id: String = newId(),
    val kind: GlobalKind,
    override val delayBefore: Long = 0,
) : Step

@Serializable
@SerialName("wait")
data class WaitStep(
    override val id: String = newId(),
    val ms: Long,
    override val delayBefore: Long = 0,
) : Step

/**
 * Pause point: execution stops here until the user finishes something by hand and presses resume.
 *
 * This is the only pause mechanism in the app. Whether replay reaches this step, the user hits the
 * pause button, or a [AwaitTextNode] times out, they all enter the same PAUSED state.
 *
 * Recording and replay pauses are two sides of the same thing: while recording you do the step by
 * hand (so inserting this also stops recording and lets touches through, otherwise you could never
 * type the verification code), and while replaying you do it by hand again.
 *
 * [note] defaults to blank and is not prompted for on insert. It exists because a script with
 * several pause points makes it easy to forget what each one is for — fill it in later if you want.
 */
@Serializable
@SerialName("pause")
data class PauseStep(
    override val id: String = newId(),
    val note: String = "",
    override val delayBefore: Long = 0,
) : Step

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

    val pauseCount: Int get() = steps.count { it is PauseStep }

    /**
     * Roughly how long one pass takes at normal speed. Display only.
     * Pause points count as zero because their duration depends on the user.
     */
    val estimatedDurationMs: Long
        get() = steps.sumOf { step ->
            step.delayBefore + when (step) {
                is GestureStep -> step.duration
                is WaitStep -> step.ms
                is GlobalStep -> GLOBAL_ACTION_COST_MS
                is PauseStep -> 0L
            }
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
