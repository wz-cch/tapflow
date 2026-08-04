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

/**
 * Moves the *start* of a single-stroke gesture to an absolute position, keeping the end where it is.
 *
 * The mirror of [withEndAt]: the same rotate-and-scale, taken about the end instead of the start, so a
 * curved swipe keeps its shape when its beginning is moved. Multi-finger gestures are left alone, since
 * there is no single start to speak of.
 */
fun GestureStep.withStartAt(x: Float, y: Float): GestureStep {
    val stroke = strokes.singleOrNull() ?: return this
    val end = stroke.end
    val oldDx = stroke.start.x - end.x
    val oldDy = stroke.start.y - end.y
    val oldLengthSquared = oldDx * oldDx + oldDy * oldDy

    // Nothing to rotate about, so it becomes a straight two-sample swipe from the new start.
    if (oldLengthSquared < MIN_DIRECTION_LENGTH_SQUARED) {
        return copy(
            strokes = listOf(
                stroke.copy(points = listOf(Pt(x, y, 0), end.copy(t = stroke.duration.coerceAtLeast(1L))))
            )
        )
    }

    val newDx = x - end.x
    val newDy = y - end.y
    val a = (newDx * oldDx + newDy * oldDy) / oldLengthSquared
    val b = (newDy * oldDx - newDx * oldDy) / oldLengthSquared

    return copy(
        strokes = listOf(
            stroke.copy(
                points = stroke.points.map { point ->
                    val px = point.x - end.x
                    val py = point.y - end.y
                    point.copy(x = end.x + a * px - b * py, y = end.y + b * px + a * py)
                }
            )
        )
    )
}

/**
 * Scales every sample of every stroke about the screen origin.
 *
 * Used when expanding a flow: each clip was recorded on its own screen, while the player takes a single
 * recorded screen for a whole run, so the coordinates are brought to a common frame first. Running one
 * clip inside a flow then behaves exactly like running it on its own, which is all "faithfully" means
 * here — this is the same linear scaling the dispatcher already applies, just applied per clip.
 *
 * Not to be confused with remapping between genuinely different devices. That is a harder problem and it
 * belongs where a clip is converted and saved, not in the middle of a run.
 */
fun GestureStep.scaledBy(sx: Float, sy: Float): GestureStep {
    if (sx == 1f && sy == 1f) return this
    return copy(
        strokes = strokes.map { stroke ->
            stroke.copy(points = stroke.points.map { it.copy(x = it.x * sx, y = it.y * sy) })
        }
    )
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

/**
 * The actions of one saved clip — and nothing else.
 *
 * **No id and no name.** Both used to be here, and taking them out is the whole of this redesign. A clip is
 * a file; where that file is *is* its identity, and what it is called is the file's name. An id inside the
 * JSON meant a copy of a file was a different clip while a rename was the same one, which is backwards from
 * how anyone treats a document: duplicating `login.clip` in a file manager and getting two entries that the
 * app insists are one thing is not a subtle wrongness. Keeping a name in here too meant two places holding
 * one name, one of which the user can change from outside the app.
 *
 * There is no `createdAt` either, for the plainer reason that nothing ever displayed it. The file system
 * keeps timestamps.
 */
@Serializable
data class Clip(
    val steps: List<Step>,
    val screen: ScreenSpec,
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

}

/**
 * Roughly what a global action costs, for [Clip.estimatedDurationMs]. Display only.
 *
 * A top-level private const rather than a companion inside [Clip], and that is not a style choice.
 * `@Serializable` makes the compiler plugin put `serializer()` on the class's companion — the *same*
 * companion, if the class declares one. This was `private companion object`, so `Clip.Companion` was
 * private, and every `encodeToString(clip)` compiled into a read of a private field from another class:
 * legal at compile time because the plugin emits it inside an inlined function body with no synthetic
 * accessor, and an `IllegalAccessError` the moment a runtime actually enforces the check.
 *
 * Which is why it presented as a device-specific crash rather than a bug. Android 11 let the access
 * through; Android 10 did not, and saving died inside the save path with a stack frame pointing at a line
 * number past the end of Repo.kt — the inliner's marker for code that came from somewhere else.
 *
 * So: no `@Serializable` class here may declare a private companion. There is nothing to gain from it and
 * the failure it causes is invisible until it is not.
 */
private const val GLOBAL_ACTION_COST_MS = 300L

// ---------------------------------------------------------------------------
// Layer 3 — flow: several clips chained into one run.
//
// This layer has exactly one type. It used to have four, three of which restated concepts layer 1 already
// owned; see ClipNode for what went and why.
// ---------------------------------------------------------------------------

/**
 * One clip's place in a flow.
 *
 * A flow is a list of these and nothing else — that is the whole of layer 3. The same shape as layer 2
 * one level up: a container of members, each member carrying a lead-in delay and a repeat count, the
 * container carrying a loop count. So the knobs here are deliberately the same three a step has.
 *
 * There used to be a WaitNode, a GlobalNode and an AwaitTextNode alongside this, and they were duplicates
 * of [PauseStep] and [GlobalStep] one layer up — WaitNode and PauseStep were the same field rendered with
 * the same string. Layer 3 had a vocabulary of its own for concepts layer 1 already owned. A flow's
 * members are clips; to wait between two of them use [delayBefore], and to press back between them put a
 * [GlobalStep] at the end of the preceding clip.
 *
 * That also retires the `pauseAfter` flag this file used to anticipate needing. The problem it was for —
 * "same clip reused across flows, pausing in one but not the other" — is what [delayBefore] solves, and
 * more generally, being a length rather than a boolean.
 *
 * The conditional wait returns at M4 as a *step*, not a node: waiting for text is more useful in the
 * middle of a clip than only between clips, and "nodes are steps" is why it belongs down there.
 *
 * @param ref where the clip's file is — a `content://` Uri string or an absolute path. **This is the
 *   reference in full.** It used to be an id stored inside the clip's JSON, which made a flow's members
 *   findable only by walking a library folder and comparing ids; now a flow says where its clips are, the
 *   way a playlist names files. Move or rename one and this breaks, visibly, with a `!` row and a button to
 *   point it somewhere else — which is the same outcome as any other document that references a file, and
 *   the alternative was worse: matching by name would silently pick up a *different* clip that happens to
 *   share it.
 * @param name what the clip was called when it was added, kept only so a broken row can say which clip is
 *   missing. Never used while the file can be read — the name shown then comes from the file itself, so
 *   renaming a clip outside the app shows up the next time the flow is opened.
 * @param delayBefore how long to wait before this clip starts. 0 leaves the clip's own first-step delay
 *   alone; anything else replaces it, because that recorded value was never measured against a
 *   predecessor — the first step of a recording has none — so it carries nothing worth keeping.
 * @param repeat how many times to run this clip in place.
 * @param repeatIntervalMs gap between those runs. Its own field for the same reason it is at the step
 *   level: [delayBefore] is the gap *before* the clip, this is the gap *between* repetitions of it, and
 *   one field cannot mean both.
 */
@Serializable
@SerialName("clip")
data class ClipNode(
    val ref: String,
    val name: String = "",
    val delayBefore: Long = 0,
    val repeat: Int = 1,
    val repeatIntervalMs: Long = 0,
) {
    /** Repetitions beyond the first, which is what the interval is paid for. */
    val extraPasses: Int get() = (repeat - 1).coerceAtLeast(0)
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
 *
 * [sourceName] is carried alongside [sourceRef] rather than looked up from it. Asking a document provider
 * for a name is IO, and the two places that want it — the save toast and the name suggested for a save-as —
 * are both on the path where the user is already waiting. A stale label costs nothing: it is never what
 * decides which file gets written.
 */
@Serializable
data class WorkspaceSnapshot(
    val steps: List<Step> = emptyList(),
    val sourceRef: String? = null,
    val sourceName: String? = null,
    val screen: ScreenSpec? = null,
    val dirty: Boolean = false,
)

/**
 * Several clips chained into one run.
 *
 * Holds no speed and no start delay of its own. Both exist in Settings already, and two places holding
 * one number is two places for it to disagree. No id and no name either, for the reason [Clip] gives: a
 * flow is a file, and the file is both.
 *
 * There is also no "unsaved" state to speak of: a flow is a list of references, so editing one writes it
 * straight back. That is why the toolbar has no save button in flow mode — the act does not exist.
 */
@Serializable
data class Flow(
    val clips: List<ClipNode>,
    /** 0 means loop forever until stopped. */
    val loopCount: Int = 1,
)
