package com.tapflow.android.engine

import android.content.res.Resources
import android.os.SystemClock
import com.tapflow.android.R
import com.tapflow.android.data.FailurePolicy
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.RepeatableStep
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Settings
import com.tapflow.android.data.Step
import com.tapflow.android.data.TouchPolicy
import com.tapflow.android.text.prompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs a list of steps.
 *
 * Pausing does not cancel the job. It suspends it at a checkpoint instead, so the position in the
 * loop, the step index and everything else stays where it is as ordinary local state — no cursor to
 * serialise and restore. Only stopping cancels, which is exactly why stopping means "start over".
 */
class Player(
    private val scope: CoroutineScope,
    private val dispatcher: GestureDispatcher,
    private val resources: Resources,
    private val currentScreen: () -> ScreenSpec,
    private val settings: () -> Settings,
    /**
     * Called as the run ends, with how many steps were skipped under [FailurePolicy.SKIP].
     *
     * A callback rather than a flag on [EngineState] because the tally has exactly one reader at exactly
     * one moment, and parking it in shared state would mean deciding when to clear it — with [EngineState]
     * being reset in the same `finally` that would need to have already reported it.
     */
    private val onFinished: (skipped: Int) -> Unit = {},
) {

    private var job: Job? = null
    private var timerJob: Job? = null
    private val pauseRequested = MutableStateFlow(false)
    private val skipRequested = MutableStateFlow(false)

    val isActive: Boolean get() = job?.isActive == true

    /**
     * @param loops 0 runs until stopped.
     * @param startIndex first step of the *first* pass. Later passes always start from the beginning:
     *   "start at 47 and run three times" almost never means skipping 1–46 on every pass, it means
     *   picking up where the problem was and then behaving normally.
     * @param plan set when the steps came from expanding a flow. It does two things: progress is reported as
     *   "clip 2 of 5, step 10 of 30" instead of one number counted across the whole flow, and it carries the
     *   *order* — which slice of [steps] to run, how many times, and what to wait first. Null for a
     *   recording, where the list is the order and the global index is the answer.
     */
    fun play(
        steps: List<Step>,
        recordedScreen: ScreenSpec?,
        loops: Int,
        startIndex: Int = 0,
        plan: FlowPlan.Expanded? = null,
    ) {
        if (isActive || steps.isEmpty()) return
        pauseRequested.value = false
        skipped = 0

        val runLength = plan?.totalSteps ?: steps.size
        Diag.log("player: play $runLength step(s), loops=$loops, recordedScreen=$recordedScreen")
        job = scope.launch {
            try {
                countDown(settings().startDelayMs)
                EngineState.mode.value = Mode.PLAYING
                EngineState.elapsedMs.value = 0
                startTimer()

                var loop = 0
                while (loops <= 0 || loop < loops) {
                    loop++
                    // Between passes, never after the last one — which is what putting it at the top of
                    // the body and skipping the first pass gets for free. It matters most when looping
                    // forever, where without it the script simply hammers the app continuously.
                    if (loop > 1) {
                        val current = settings()
                        // Step 0: between passes, nothing running. The transport reads it as a wait.
                        EngineState.progress.value = Progress(loop, loops, 0, runLength)
                        delay(Timing.replayDelay(current.loopIntervalMs, current))
                        // So pause and stop land during the gap rather than only at the next step.
                        gate()
                    }
                    val from = if (loop == 1) startIndex.coerceIn(0, runLength - 1) else 0
                    // Generated per pass rather than held, which is the whole point: a clip repeated 999
                    // times is 999 visits to one slice of `steps`, not 999 copies of it.
                    for ((position, visit) in visits(steps, plan).withIndex()) {
                        if (position < from) continue
                        val step = steps[visit.index]
                        val repeatable = step as? RepeatableStep
                        val onClock = repeatable?.repeatsForTime == true
                        // Unknown ahead of time on a clock: how many passes fit depends on how long each
                        // dispatch takes. Reported as it goes instead — see [report].
                        val passes = if (onClock) 0 else repeatable?.repeat?.coerceAtLeast(1) ?: 1
                        // The step's own clock if it has one, otherwise the clip's. Innermost wins, the
                        // same order the skip button follows — so the number on screen is always the one
                        // that button would end.
                        report(
                            loop, loops, visit, plan, 1, passes,
                            if (onClock) repeatable.repeatForMs else visit.clipClockLeftMs,
                        )
                        Diag.log(
                            "player: loop $loop step ${position + 1}/$runLength " +
                                step::class.java.simpleName +
                                when {
                                    onClock -> " for ${repeatable?.repeatForMs}ms"
                                    passes > 1 -> " x$passes"
                                    else -> ""
                                }
                        )

                        // Only the manual form asks for a pause. A timed one is just a delay, so it
                        // stays in RUNNING and the progress readout keeps moving — PAUSED continues
                        // to mean exactly one thing: something needs a human.
                        if (step is PauseStep && !step.isTimed) {
                            EngineState.pausePrompt.value = step.prompt(resources)
                            pauseRequested.value = true
                        }

                        gate()
                        if (step is PauseStep && !step.isTimed) continue

                        val current = settings()
                        // The lead delay of the step started from is skipped: it is measured against the
                        // step before it, and that one did not run. The start countdown covers the gap.
                        val startedHere = loop == 1 && position == from && from > 0
                        // A clip's own lead-in, or the gap before going round again, wins over the step's
                        // recorded one — it replaces it rather than adding to it, which is what the
                        // copy-per-pass expansion did by overwriting the field on its private copy.
                        val lead = if (visit.leadMs > 0) visit.leadMs else step.delayBefore
                        if (!startedHere) delay(Timing.replayDelay(lead, current))
                        gate()

                        if (step is PauseStep) {
                            timedWait(Timing.replayDelay(step.ms, current))
                            continue
                        }

                        val scale = ScaleSpec.of(recordedScreen, currentScreen())
                        val interval = repeatable?.repeatIntervalMs ?: 0
                        // Wall clock, deliberately: "keep tapping for ten minutes" is a statement about
                        // the world, not about the script's rhythm, so the speed multiplier does not
                        // shorten it. It makes the taps closer together inside the same ten minutes.
                        val until = if (onClock) SystemClock.elapsedRealtime() + repeatable.repeatForMs else 0
                        // Cleared on the way in, the same as a timed wait does, because the two share one
                        // flag: a press landing as a wait ends must not carry into the next step's repeat.
                        if (onClock) skipRequested.value = false
                        var pass = 0
                        try {
                            while (true) {
                                pass++
                                // Between passes only, and after the lead delay has already been paid. The
                                // interval is what stops ten taps arriving close enough together for the app
                                // below to read them as one multi-tap — or to drop them.
                                if (pass > 1) {
                                    // Rounded up to a whole second before publishing, for the reason [tick]
                                    // gives: the raw figure changes every tick, and every change is a redraw
                                    // of a panel that only ever shows seconds.
                                    val left = {
                                        val raw = (until - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                                        (raw + 999) / 1000 * 1000
                                    }
                                    report(
                                        loop, loops, visit, plan, pass, passes,
                                        if (onClock) left() else visit.clipClockLeftMs,
                                    )
                                    // Ticked rather than one delay(), so a pause lands inside the gap
                                    // instead of at the end of it — a three-second interval used to mean
                                    // three seconds of an unresponsive pause button — and so a skip does
                                    // too. Not skippable on a count: the number is a specification, not a
                                    // guess about the world.
                                    val ran = tick(Timing.replayDelay(interval, current), skippable = onClock) {
                                        if (onClock) {
                                            report(loop, loops, visit, plan, pass, passes, left())
                                        }
                                    }
                                    if (!ran) break
                                }
                                if (!attempt(step, scale, current, position + 1)) return@launch
                                // Checked after the action, never during one: a ten-minute repeat overruns
                                // by up to one gesture rather than dispatching half a swipe and calling it
                                // time. A skip is read the same way, so pressing it finishes the tap in
                                // flight rather than cutting it.
                                if (onClock && skipRequested.value) {
                                    Diag.log("player: timed repeat skipped with ${until - SystemClock.elapsedRealtime()}ms left")
                                    break
                                }
                                val more =
                                    if (onClock) SystemClock.elapsedRealtime() < until else pass < passes
                                if (!more) break
                            }
                        } finally {
                            // Also on cancellation, which is what stopping a run mid-repeat is.
                            if (onClock) skipRequested.value = false
                        }
                    }
                }
            } finally {
                timerJob?.cancel()
                timerJob = null
                pauseRequested.value = false
                // Before reset, and a plain call rather than anything suspending: this also runs when the
                // job was cancelled, where a suspending call would be refused.
                onFinished(skipped)
                EngineState.reset()
            }
        }
    }

    /**
     * One step of the run, and everything about it that depends on *where in the run* it is.
     *
     * The same step object can be visited many times — that is the point of not copying it — so nothing
     * position-dependent may live on the step. It lives here instead, for the length of one visit.
     */
    private class Visit(
        /** Index into the step list handed to [play]. */
        val index: Int,
        /** Replaces the step's own lead when above zero. Only ever set on a clip's first step. */
        val leadMs: Long,
        /** 1-based clip position, or 0 for a recording, where there is no clip to be in. */
        val clipPosition: Int,
        val stepInClip: Int,
        val stepsInClip: Int,
        /**
         * Time left on the clip's own repeat clock, or 0 when this clip is not on one.
         *
         * Read at the moment the visit is produced, which is the moment before the step runs — so it is
         * refreshed once per step rather than continuously. A clip repeating for half an hour is not a
         * number anyone watches to the second.
         */
        val clipClockLeftMs: Long = 0,
    )

    /**
     * The run, in order, generated as it goes.
     *
     * **Lazy on purpose.** A flow's repeats are expressed here rather than in the step list, so a clip
     * repeated 999 times costs 999 of these — each alive for one step and then garbage — instead of 999
     * copies of its steps held for the whole run. The player already ran a *step* N times without holding
     * N copies of it; this is the same bargain one level up.
     *
     * A recording has no segments, so it is simply its own order.
     */
    private fun visits(steps: List<Step>, plan: FlowPlan.Expanded?): Sequence<Visit> {
        if (plan == null) {
            return steps.indices.asSequence().map {
                Visit(
                    index = it,
                    leadMs = 0,
                    clipPosition = 0,
                    stepInClip = it + 1,
                    stepsInClip = steps.size,
                )
            }
        }
        return sequence {
            for (segment in plan.segments) {
                if (segment.repeatsForTime) clipOnClock(segment) else clipByCount(segment)
            }
        }
    }

    private suspend fun SequenceScope<Visit>.clipByCount(segment: FlowPlan.Segment) {
        for (pass in 1..segment.repeat) {
            // The lead-in belongs to arriving at this clip, the interval to going round again.
            val lead = if (pass == 1) segment.delayBefore else segment.repeatIntervalMs
            for (offset in 0 until segment.stepCount) {
                yield(visitOf(segment, pass, offset, lead, clockLeftMs = 0))
            }
        }
    }

    /**
     * A clip that runs over and over for a length of time.
     *
     * **How many passes fit is not knowable in advance**, so this is generated as it goes — which only works
     * because the sequence is pulled one step at a time by the run itself, so the clock read here is the real
     * elapsed time. The flattened expansion this replaced could not have done it at all: a list has to know
     * its own length.
     *
     * ### Where the skip lands
     *
     * There can be two clocks running at once — this one, and a timed wait or a repeating step inside the
     * clip — and one button. **The innermost one wins**, which falls out of where each side looks rather than
     * from a rule anyone has to enforce: the inner ones tick *during* a step and clear the flag as they take
     * it, and this looks only *between* steps. So a press that landed on a wait is already gone by the time
     * this asks, and a press that landed on nothing in particular is still here.
     *
     * Skipping abandons the rest of the pass rather than finishing it. The point of "keep tapping until the
     * tickets appear" is that the moment they appear you want the next thing, and a clip that politely
     * completes its remaining twelve steps first has missed it. Whatever state that leaves the app in is the
     * user's to sort out — they are the one who saw the reason to press it.
     */
    private suspend fun SequenceScope<Visit>.clipOnClock(segment: FlowPlan.Segment) {
        val until = SystemClock.elapsedRealtime() + segment.repeatForMs
        // Cleared on the way in, like every other consumer of this flag, so a press that arrived as the
        // previous clip ended cannot carry into this one.
        skipRequested.value = false
        var pass = 1
        while (true) {
            val lead = if (pass == 1) segment.delayBefore else segment.repeatIntervalMs
            for (offset in 0 until segment.stepCount) {
                if (skipRequested.value) {
                    skipRequested.value = false
                    Diag.log("player: clip ${segment.clipPosition} skipped, ${until - SystemClock.elapsedRealtime()}ms left")
                    return
                }
                val left = (until - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                // Rounded up to a whole second before it is published, so the panel redraws once a second
                // rather than on every step of a fast clip.
                yield(visitOf(segment, pass, offset, lead, (left + 999) / 1000 * 1000))
            }
            // Between passes, never mid-clip: the clock decides whether to go round again, and a pass cut in
            // half is not a pass. Skipping is the one thing that may cut it, and only because it is a person
            // saying so.
            if (SystemClock.elapsedRealtime() >= until) return
            pass++
        }
    }

    private fun visitOf(
        segment: FlowPlan.Segment,
        pass: Int,
        offset: Int,
        lead: Long,
        clockLeftMs: Long,
    ) = Visit(
        index = segment.from + offset,
        leadMs = if (offset == 0) lead else 0,
        clipPosition = segment.clipPosition,
        stepInClip = offset + 1,
        stepsInClip = segment.stepCount,
        clipClockLeftMs = clockLeftMs,
    )

    /**
     * Publishes progress, expressed in whatever unit the run has.
     *
     * A recording counts steps across the whole run. A flow counts them **within the current clip**, and
     * adds the clip's own position — which is why a flow of one clip reads exactly like running that clip
     * on its own, once the transport hides a total of 1. Both readings come off the visit, so there is no
     * index to map back: it was generated knowing where it was.
     */
    private fun report(
        loop: Int,
        loops: Int,
        visit: Visit,
        plan: FlowPlan.Expanded?,
        repeatPass: Int,
        repeatTotal: Int,
        repeatRemainingMs: Long,
    ) {
        EngineState.progress.value = Progress(
            loop = loop,
            totalLoops = loops,
            step = visit.stepInClip,
            totalSteps = visit.stepsInClip,
            repeatPass = repeatPass,
            repeatTotal = repeatTotal,
            repeatRemainingMs = repeatRemainingMs,
            clip = visit.clipPosition,
            totalClips = plan?.clipCount ?: 0,
        )
    }

    /**
     * Dispatches one step, retrying and applying the user's policies. False means end the run.
     *
     * The two ways a step fails arrive through the same callback and need opposite treatment, which is why
     * this reads them apart before doing anything:
     *
     * - **Cancelled** — the framework cancels an in-flight injected gesture the moment real input arrives,
     *   because the two streams are not merged and real input wins. So the app below received our gesture
     *   as far as it had got *and* the finger. Never retried: re-issuing a half-delivered swipe replays
     *   half of it on top of itself, and the event is a person reaching for the screen rather than a
     *   malfunction. [TouchPolicy] decides.
     * - **Refused or injector-missing** — nothing was delivered at all, so retrying is safe and is the
     *   only one of the three answers that can actually recover. [Settings.failureRetries] then
     *   [FailurePolicy].
     *
     * The cause is inferred, not reported: `onCancelled` carries no reason. The remaining causes of a
     * cancellation are ones this app controls and avoids — it never dispatches two gestures at once, and
     * deliberately does not move or resize the canvas around a replay — and the missing injector is told
     * apart by its timing inside [GestureDispatcher] before it reaches here.
     *
     * [GestureDispatcher] has two retries of its own, for narrow conditions it can identify: one after
     * re-registering the service when the injector is missing, and one for a cancellation that arrived too
     * early to have delivered anything. Those stay where they are — they mend a specific fault rather than
     * express a preference — and this loop sits above them.
     */
    private suspend fun attempt(
        step: Step,
        scale: ScaleSpec,
        settings: Settings,
        stepNumber: Int,
    ): Boolean {
        val retries = settings.failureRetries.coerceIn(0, Settings.MAX_FAILURE_RETRIES)
        // Attempt 0 is the step itself; the rest are retries, so 0 retries still runs it once.
        for (tryIndex in 0..retries) {
            if (tryIndex > 0) {
                Diag.log("player: step $stepNumber retry $tryIndex of $retries")
                // Checked between attempts so stop and pause work during a long run of failing retries,
                // which on a missing injector is a second each.
                gate()
            }
            when (dispatcher.perform(step, scale, settings)) {
                GestureOutcome.COMPLETED, GestureOutcome.SKIPPED -> return true

                GestureOutcome.CANCELLED -> return touched(stepNumber, settings)

                // Nothing landed. Fall out of the when and let the loop try again.
                GestureOutcome.REFUSED, GestureOutcome.INJECTOR_MISSING -> Unit
            }
        }
        return failed(stepNumber, settings)
    }

    /** A real finger interrupted the step. See [TouchPolicy]. */
    private suspend fun touched(stepNumber: Int, settings: Settings): Boolean {
        Diag.log("player: step $stepNumber cancelled part way (a real touch is the likely cause) -> ${settings.onRealTouch}")
        return when (settings.onRealTouch) {
            TouchPolicy.PAUSE -> {
                EngineState.pausePrompt.value =
                    resources.getString(R.string.pause_touch_interrupted, stepNumber)
                pauseRequested.value = true
                gate()
                true
            }

            TouchPolicy.IGNORE -> true

            TouchPolicy.STOP -> false
        }
    }

    /** The gesture never reached the app, and the retries are used up. See [FailurePolicy]. */
    private suspend fun failed(stepNumber: Int, settings: Settings): Boolean {
        Diag.log("player: step $stepNumber never landed -> ${settings.onGestureFailure}")
        return when (settings.onGestureFailure) {
            FailurePolicy.PAUSE -> {
                EngineState.pausePrompt.value =
                    resources.getString(R.string.pause_gesture_failed, stepNumber)
                pauseRequested.value = true
                gate()
                true
            }

            // Counted, not silent. Skipping is chosen for scripts where one missed tap does not matter,
            // and that holds only while you believe the taps happened — a run that quietly skipped
            // thirty-eight of forty steps and then reported finishing is the failure that looks like
            // success. The tally is reported once, at the end, so it never interrupts.
            FailurePolicy.SKIP -> {
                skipped++
                true
            }
        }
    }

    private var skipped = 0

    fun pause() {
        if (isActive) pauseRequested.value = true
    }

    fun resume() {
        pauseRequested.value = false
    }

    fun togglePause() {
        if (!isActive) return
        pauseRequested.value = !pauseRequested.value
    }

    /**
     * Ends whichever clock is running early and carries straight on.
     *
     * Two things can be on a clock — a timed wait, and a step repeating for a length of time — and both are
     * the same kind of guess: a length decided in advance, and always decided generously. So one button ends
     * either, and it means the same thing in both: stop waiting for this, go on.
     *
     * Safe to call at any moment, including when nothing is on a clock: both consumers clear the flag on
     * entry, so a press that arrives between two of them cannot leak into the second.
     */
    fun skipAhead() {
        if (isActive) skipRequested.value = true
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * The single pause checkpoint. Called before every step, and again after every wait, so both a
     * user-requested pause and a [PauseStep] land in the same place.
     */
    private suspend fun gate() {
        if (!pauseRequested.value) return

        EngineState.mode.value = Mode.PAUSED

        pauseRequested.first { !it }

        EngineState.pausePrompt.value = null
        EngineState.mode.value = Mode.PLAYING
    }

    /**
     * A wait asked for by a [PauseStep] with a length: counted down on screen, and skippable.
     *
     * Only this one of the run's waits gets either. The others — a step's lead delay, the gap between
     * repetitions, the gap between passes — are the script's rhythm, and there is nothing to decide about
     * them. This one is a step the user inserted, almost always because something below has to finish
     * loading, and the length is a guess made in advance and set generously. So it is the one wait where
     * watching the number and deciding to go early is the point.
     *
     * Ticked rather than one `delay(ms)`, which buys three things beyond the display:
     *
     * - **Skip lands within a tick** instead of at the end of the wait.
     * - **So does pausing**, and that fixes a real defect: [gate] used to be reached only *after* the wait,
     *   so pausing during a 30-second wait left the button unchanged for 30 seconds and read as broken.
     * - **Pausing freezes the count** rather than draining it, because the remaining time is held here and
     *   nothing moves while [gate] is suspended. A countdown that ran to zero while paused and then still
     *   had 12 seconds to serve would be lying.
     *
     * The tick is short and the published value is in whole seconds, so most ticks assign the value the
     * flow already holds — and a `StateFlow` does not emit on an equal value, so the transport still
     * redraws once a second rather than ten times.
     */
    private suspend fun timedWait(totalMs: Long) {
        if (totalMs <= 0) return
        skipRequested.value = false
        try {
            tick(totalMs, skippable = true) { left ->
                EngineState.waitRemaining.value = ((left + 999) / 1000).toInt()
            }
        } finally {
            // Also on cancellation, which is what stopping a run in the middle of a wait is.
            EngineState.waitRemaining.value = 0
            skipRequested.value = false
        }
    }

    /**
     * Waits [totalMs] in slices, checking the pause gate and the skip flag on every one.
     *
     * The reason nothing here uses a plain `delay()` for a length the user chose: a checkpoint reached only
     * *after* the wait means the pause button does nothing for as long as the wait lasts, which reads as
     * broken rather than as pending. At a tick this fine both pause and skip land inside a tenth of a second.
     *
     * @param publish called with the milliseconds left, every tick. Publish whole seconds from it — a
     *   `StateFlow` does not emit an equal value, so a per-second figure redraws the panel once a second
     *   rather than ten times.
     * @return false when a skip ended it early, true when it ran out.
     */
    private suspend fun tick(totalMs: Long, skippable: Boolean, publish: (Long) -> Unit): Boolean {
        var remaining = totalMs
        while (remaining > 0) {
            gate()
            if (skippable && skipRequested.value) {
                Diag.log("player: skipped with ${remaining}ms left")
                return false
            }
            publish(remaining)
            val slice = minOf(remaining, WAIT_TICK_MS)
            delay(slice)
            remaining -= slice
        }
        return true
    }

    private suspend fun countDown(delayMs: Long) {
        if (delayMs <= 0) return
        EngineState.mode.value = Mode.COUNTDOWN
        var remaining = delayMs
        while (remaining > 0) {
            EngineState.countdown.value = ((remaining + 999) / 1000).toInt()
            delay(minOf(remaining, 1000L))
            remaining -= 1000
        }
        EngineState.countdown.value = 0
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(TIMER_TICK_MS)
                if (EngineState.mode.value == Mode.PLAYING) {
                    EngineState.elapsedMs.value += TIMER_TICK_MS
                }
            }
        }
    }

    private companion object {
        const val TIMER_TICK_MS = 200L

        /** How soon skip and pause take effect inside a timed wait. Not the display rate; see [timedWait]. */
        const val WAIT_TICK_MS = 100L
    }
}
