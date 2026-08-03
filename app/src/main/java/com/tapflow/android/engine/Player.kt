package com.tapflow.android.engine

import android.content.res.Resources
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

    val isActive: Boolean get() = job?.isActive == true

    /**
     * @param loops 0 runs until stopped.
     * @param startIndex first step of the *first* pass. Later passes always start from the beginning:
     *   "start at 47 and run three times" almost never means skipping 1–46 on every pass, it means
     *   picking up where the problem was and then behaving normally.
     * @param plan set when the steps came from expanding a flow, so progress can be reported as "clip 2
     *   of 5, step 10 of 30" instead of one number counted across the whole flow. Null for a recording,
     *   where there is only one thing being run and the global index is the answer.
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
        cursor = plan?.let { Cursor(it) }
        skipped = 0

        Diag.log("player: play ${steps.size} step(s), loops=$loops, recordedScreen=$recordedScreen")
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
                        EngineState.progress.value = Progress(loop, loops, 0, steps.size)
                        delay(Timing.replayDelay(current.loopIntervalMs, current))
                        // So pause and stop land during the gap rather than only at the next step.
                        gate()
                    }
                    val from = if (loop == 1) startIndex.coerceIn(0, steps.lastIndex) else 0
                    for ((index, step) in steps.withIndex()) {
                        if (index < from) continue
                        val passes = (step as? RepeatableStep)?.repeat?.coerceAtLeast(1) ?: 1
                        report(loop, loops, index, steps.size, 1, passes)
                        Diag.log(
                            "player: loop $loop step ${index + 1}/${steps.size} " +
                                step::class.java.simpleName +
                                if (passes > 1) " x$passes" else ""
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
                        val startedHere = loop == 1 && index == from && from > 0
                        if (!startedHere) delay(Timing.replayDelay(step.delayBefore, current))
                        gate()

                        if (step is PauseStep) {
                            delay(Timing.replayDelay(step.ms, current))
                            continue
                        }

                        val scale = ScaleSpec.of(recordedScreen, currentScreen())
                        val interval = (step as? RepeatableStep)?.repeatIntervalMs ?: 0
                        for (pass in 1..passes) {
                            // Between passes only, and after the lead delay has already been paid. The
                            // interval is what stops ten taps arriving close enough together for the app
                            // below to read them as one multi-tap — or to drop them.
                            if (pass > 1) {
                                report(loop, loops, index, steps.size, pass, passes)
                                delay(Timing.replayDelay(interval, current))
                                // Checked every pass, so pause and stop work in the middle of a repeat
                                // rather than only between steps.
                                gate()
                            }
                            if (!attempt(step, scale, current, index + 1)) return@launch
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
     * Where a global step index falls in an expanded flow.
     *
     * Walks forward from the previous answer rather than searching, because playback only ever moves
     * forwards through the list, so this is a single comparison per step in the common case.
     */
    private class Cursor(val plan: FlowPlan.Expanded) {
        private var segment = 0
        private var segmentStart = 0

        fun locate(index: Int): Pair<Int, Int> {
            if (index < segmentStart) {
                segment = 0
                segmentStart = 0
            }
            while (segment < plan.segments.lastIndex &&
                index >= segmentStart + plan.segments[segment].stepCount
            ) {
                segmentStart += plan.segments[segment].stepCount
                segment++
            }
            val here = plan.segments.getOrNull(segment) ?: return 0 to 0
            return here.clipPosition to here.stepCount
        }

        fun stepWithin(index: Int): Int = index - segmentStart + 1
    }

    private var cursor: Cursor? = null

    /**
     * Publishes progress, expressed in whatever unit the run has.
     *
     * A recording counts steps across the whole run. A flow counts them **within the current clip**, and
     * adds the clip's own position — which is why a flow of one clip reads exactly like running that clip
     * on its own, once the transport hides a total of 1.
     */
    private fun report(
        loop: Int,
        loops: Int,
        index: Int,
        total: Int,
        repeatPass: Int,
        repeatTotal: Int,
    ) {
        val here = cursor
        if (here == null) {
            EngineState.progress.value =
                Progress(loop, loops, index + 1, total, repeatPass, repeatTotal)
            return
        }
        val (clipPosition, stepsInClip) = here.locate(index)
        EngineState.progress.value = Progress(
            loop = loop,
            totalLoops = loops,
            step = here.stepWithin(index),
            totalSteps = stepsInClip,
            repeatPass = repeatPass,
            repeatTotal = repeatTotal,
            clip = clipPosition,
            totalClips = here.plan.clipCount,
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
    }
}
