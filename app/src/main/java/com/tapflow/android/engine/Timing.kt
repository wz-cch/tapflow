package com.tapflow.android.engine

import com.tapflow.android.data.Settings
import kotlin.math.roundToLong
import kotlin.random.Random

/** Speed scaling and time randomisation, shared by the recorder and the player. */
object Timing {

    /** Longest gap we are willing to record between two touches. */
    const val MAX_RECORDED_GAP_MS = 30_000L

    /** Apply the speed multiplier, then randomise by the configured percentage. */
    fun replayDelay(ms: Long, settings: Settings): Long =
        jitter(scaleBySpeed(ms, settings.speed), settings.jitterTimePercent)

    fun replayDuration(ms: Long, settings: Settings): Long =
        jitter(scaleBySpeed(ms, settings.speed), settings.jitterTimePercent).coerceAtLeast(1L)

    private fun scaleBySpeed(ms: Long, speed: Float): Long =
        if (speed <= 0f) ms else (ms / speed).roundToLong()

    private fun jitter(ms: Long, percent: Int): Long {
        if (percent <= 0 || ms <= 0) return ms
        val spread = ms * percent / 100.0
        val offset = Random.nextDouble(-spread, spread)
        return (ms + offset).roundToLong().coerceAtLeast(0L)
    }
}
