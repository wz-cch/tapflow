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
 * The expansion is throwaway — nothing edits it and nothing saves it. That is what makes it safe for one
 * clip's steps to appear in it five times.
 */
object FlowPlan {

    /**
     * Where one step came from, so progress can be reported as "clip 2 of 5, step 10 of 30".
     *
     * Without this the panel could only say "step 147 of 300", which does not tell you which clip a flow
     * broke in — and finding that out is most of what you want from a flow that misbehaves.
     */
    data class Segment(
        /** 1-based position of this clip in the flow, counting each clip once however often it repeats. */
        val clipPosition: Int,
        val stepCount: Int,
    )

    /**
     * @param steps every step of every clip, in order, already scaled to the current screen.
     * @param segments one per clip *pass*, in the same order, so a global index maps back to a clip.
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
    )

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

            val scaled = clip.steps.map { it.scaledFrom(clip.screen, target) }
            for (pass in 1..node.repeat.coerceAtLeast(1)) {
                // The lead-in belongs to the first pass and the interval separates the ones after it. Both
                // land on that pass's first step, because once a flow is flattened there is no longer a
                // "between clips" for the player to wait at — a delay can only live on a step.
                val lead = if (pass == 1) node.delayBefore else node.repeatIntervalMs
                steps += scaled.mapIndexed { position, step ->
                    if (position == 0 && lead > 0) step.withDelayBefore(lead) else step
                }
                segments += Segment(clipPosition = index + 1, stepCount = scaled.size)
            }
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
     * A clip repeated five times would otherwise put the same step id into the list five times, and every
     * on-screen marker keys off that id. Nothing in a run selects or drags, so it would break nothing
     * today — but a list holding duplicate identities is a trap for whoever next writes something that
     * assumes they are unique. A data class copy shares the stroke list, so this costs one small object
     * per step and no path data.
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

private fun Step.withDelayBefore(ms: Long): Step = when (this) {
    is GestureStep -> copy(delayBefore = ms)
    is PauseStep -> copy(delayBefore = ms)
    is GlobalStep -> copy(delayBefore = ms)
}
