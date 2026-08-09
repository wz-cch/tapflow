package com.tapflow.android.engine

import com.tapflow.android.data.Clip
import com.tapflow.android.data.Flow
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.GlobalStep
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Step
import com.tapflow.android.data.newId
import com.tapflow.android.data.scaledBy

/**
 * A flow, expanded into the one thing that can be run.
 *
 * There is deliberately no second executor. A flow is a list of clips, a clip is a list of steps, and a
 * node *is* a step — so a flow expands into a step list and [Player] runs it exactly as it runs a
 * recording. Everything the player has learned comes along for free: the gap between loops, pausing when
 * a touch interrupts a gesture, per-step repeats, starting from step N. A FlowRunner would have had to
 * grow all four again, and then the two would drift.
 *
 * The expansion is throwaway — nothing edits it and nothing saves it.
 *
 * ### It expands the *order*, not the steps
 *
 * A clip repeated N times appears here once, with a segment saying "run this slice N times". It used to be
 * N copies of its steps, and that made the repeat count a memory limit: a 500-step clip repeated 999 times
 * is half a million step objects, which is an out-of-memory crash rather than a long run. The player already
 * knew how to run one step N times without holding N copies of it; this is the same idea one level up, and
 * it is what let the repeat cap rise from 50 to [com.tapflow.android.data.Settings.MAX_REPEAT].
 */
object FlowPlan {

    /**
     * One clip's place in the run: which steps, how many times, and what to wait first.
     *
     * The clip position is here so progress can be reported as "clip 2 of 5, step 10 of 30". Without it the
     * panel could only say "step 147 of 300", which does not tell you which clip a flow broke in — and
     * finding that out is most of what you want from a flow that misbehaves.
     *
     * The two waits are separate fields for the same reason they are separate on a step: the lead-in belongs
     * to arriving at this clip and the interval belongs to going round again, so at [repeat] 1 the interval
     * is simply never read. They are held here rather than written onto the first step, which is what the
     * copy-per-pass version did — with one shared copy there is no per-pass step to write onto.
     */
    data class Segment(
        /** 1-based position of this clip in the flow, counting each clip once however often it repeats. */
        val clipPosition: Int,
        /** First index into [Expanded.steps]. */
        val from: Int,
        val stepCount: Int,
        val repeat: Int,
        /** Before the first step of the first pass. Replaces that step's own lead when above zero. */
        val delayBefore: Long,
        /** Before the first step of every later pass, the same way. */
        val repeatIntervalMs: Long,
    )

    /**
     * @param steps every referenced clip's steps **once each**, in flow order, already scaled to the current
     *   screen. Not the run: [segments] says how many times each slice of this is used.
     * @param segments one per clip, in order.
     * @param clipCount how many clips the flow has, which is the denominator in "2 / 5".
     * @param missing the files the flow referenced that could not be read. Refusing to start on a non-empty
     *   list is the whole reason it is reported: a reference is a location now, so a moved or renamed clip is
     *   an ordinary thing to find here — and a flow that quietly runs four of its five clips fails while
     *   looking like it worked. The flow editor shows the same rows with `!` and offers to repoint them.
     */
    data class Expanded(
        val steps: List<Step>,
        val segments: List<Segment>,
        val clipCount: Int,
        val missing: List<String>,
    ) {
        /** How many steps one pass over the flow actually performs, repeats included. */
        val totalSteps: Int = segments.sumOf { it.stepCount * it.repeat }
    }

    /**
     * Expands [flow] against the clips read for it, keyed by file reference, onto a screen of [target] size.
     *
     * Coordinates are scaled **per clip**, from the screen that clip was recorded on. Every clip carries
     * its own [Clip.screen] while the player takes one recorded screen for a whole run, so without this a
     * flow mixing two recordings would be scaled by whichever one happened to be passed. Doing it here
     * means one clip behaves the same inside a flow as it does on its own, which is all that running it
     * faithfully means. It is the same linear scaling the dispatcher already applies — not the harder
     * job of remapping between genuinely different devices, which belongs where a clip is saved.
     */
    fun expand(flow: Flow, clips: Map<String, Clip>, target: ScreenSpec): Expanded {
        val steps = ArrayList<Step>()
        val segments = ArrayList<Segment>()
        val missing = ArrayList<String>()

        flow.clips.forEachIndexed { index, node ->
            val clip = clips[node.ref]
            if (clip == null) {
                missing += node.name.ifEmpty { node.ref }
                return@forEachIndexed
            }
            if (clip.steps.isEmpty()) return@forEachIndexed

            val from = steps.size
            steps += clip.steps.map { it.scaledFrom(clip.screen, target) }
            segments += Segment(
                clipPosition = index + 1,
                from = from,
                stepCount = steps.size - from,
                repeat = node.repeat.coerceAtLeast(1),
                delayBefore = node.delayBefore,
                repeatIntervalMs = node.repeatIntervalMs,
            )
        }

        return Expanded(
            steps = withFreshIds(steps),
            segments = segments,
            clipCount = flow.clips.size,
            missing = missing,
        )
    }

    /**
     * Fresh ids for the expansion.
     *
     * One clip used as two nodes of the same flow would otherwise put the same step id into the list twice,
     * and every on-screen marker keys off that id. Nothing in a run selects or drags, so it would break
     * nothing today — but a list holding duplicate identities is a trap for whoever next writes something
     * that assumes they are unique. A data class copy shares the stroke list, so this costs one small object
     * per step and no path data.
     *
     * Repeats no longer come into it: a clip repeated 999 times is one copy visited 999 times, so it has one
     * id, the same way a step repeated 999 times always has.
     */
    private fun withFreshIds(steps: List<Step>): List<Step> = steps.map { step ->
        when (step) {
            is GestureStep -> step.copy(id = newId())
            is PauseStep -> step.copy(id = newId())
            is GlobalStep -> step.copy(id = newId())
        }
    }
}

private fun Step.scaledFrom(from: ScreenSpec?, to: ScreenSpec): Step {
    if (this !is GestureStep || from == null || from.width <= 0 || from.height <= 0) return this
    return scaledBy(to.width.toFloat() / from.width, to.height.toFloat() / from.height)
}
