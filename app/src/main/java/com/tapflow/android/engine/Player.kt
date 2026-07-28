package com.tapflow.android.engine

import android.content.res.Resources
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Settings
import com.tapflow.android.data.Step
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
) {

    private var job: Job? = null
    private var timerJob: Job? = null
    private val pauseRequested = MutableStateFlow(false)

    val isActive: Boolean get() = job?.isActive == true

    /**
     * @param loops 0 runs until stopped.
     */
    fun play(steps: List<Step>, recordedScreen: ScreenSpec?, loops: Int) {
        if (isActive || steps.isEmpty()) return
        pauseRequested.value = false

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
                    for ((index, step) in steps.withIndex()) {
                        EngineState.progress.value = Progress(loop, loops, index + 1, steps.size)
                        Diag.log("player: loop $loop step ${index + 1}/${steps.size} ${step::class.java.simpleName}")

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
                        delay(Timing.replayDelay(step.delayBefore, current))
                        gate()

                        if (step is PauseStep) {
                            delay(Timing.replayDelay(step.ms, current))
                            continue
                        }

                        dispatcher.perform(step, ScaleSpec.of(recordedScreen, currentScreen()), current)
                    }
                }
            } finally {
                timerJob?.cancel()
                timerJob = null
                pauseRequested.value = false
                EngineState.reset()
            }
        }
    }

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
