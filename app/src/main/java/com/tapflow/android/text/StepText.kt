package com.tapflow.android.text

import android.content.res.Resources
import com.tapflow.android.R
import com.tapflow.android.data.Clip
import com.tapflow.android.data.Flow
import com.tapflow.android.data.GestureKind
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.GlobalKind
import com.tapflow.android.data.GlobalStep
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.RepeatableStep
import com.tapflow.android.data.Step
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every piece of user-facing text derived from the data model lives here.
 *
 * The data package is deliberately free of Android dependencies and of hard-coded strings, so all
 * formatting happens in this layer against [Resources]. Both the Compose UI and the plain-View
 * overlays call into it, which is why it sits in its own package rather than under ui/.
 */

fun Step.label(res: Resources): String {
    val base = when (this) {
        // Named differently from this function on purpose: two same-named extensions on a type and its
        // supertype invite the compiler to resolve the wrong one and recurse forever.
        is GestureStep -> gestureLabel(res)
        is GlobalStep -> res.getString(R.string.step_global, kind.label(res))
        is PauseStep -> pauseLabel(res)
    }
    // Suffixed rather than woven into each label, so one repeated step reads as one row. Ten identical
    // rows is what this replaces, and a shorter list is the only thing that helps a hundred-step script.
    val repeat = (this as? RepeatableStep)?.repeat ?: 1
    return if (repeat > 1) res.getString(R.string.step_repeat_suffix, base, repeat) else base
}

private fun PauseStep.pauseLabel(res: Resources): String = when {
    isTimed && note.isBlank() -> res.getString(R.string.step_wait, secondsText(ms))
    isTimed -> res.getString(R.string.step_wait_with_note, secondsText(ms), note)
    note.isBlank() -> res.getString(R.string.step_pause)
    else -> res.getString(R.string.step_pause_with_note, note)
}

/**
 * Milliseconds as seconds, dropping a trailing ".0".
 *
 * Stored in milliseconds like every other duration in the model, but entered and read in seconds —
 * "3" is what the user typed and "3000 ms" is not what they want to read back.
 */
fun secondsText(ms: Long): String {
    val whole = ms / 1000
    val tenths = (ms % 1000) / 100
    return if (tenths == 0L) whole.toString() else "$whole.$tenths"
}

fun GestureStep.gestureLabel(res: Resources): String {
    val stroke = strokes.first()
    val start = stroke.start
    return when (kind) {
        GestureKind.TAP ->
            res.getString(R.string.step_tap, start.x.toInt(), start.y.toInt())

        GestureKind.LONG_PRESS ->
            res.getString(R.string.step_long_press, start.x.toInt(), start.y.toInt(), duration)

        GestureKind.SWIPE -> {
            val end = stroke.end
            res.getString(
                R.string.step_swipe,
                start.x.toInt(), start.y.toInt(),
                end.x.toInt(), end.y.toInt(),
                duration,
            )
        }

        GestureKind.MULTI_TOUCH ->
            res.getString(R.string.step_multi_touch, strokes.size, duration)
    }
}

/** What the transport panel shows while paused on this step. */
fun PauseStep.prompt(res: Resources): String =
    note.ifBlank { res.getString(R.string.pause_default_prompt) }

fun GlobalKind.label(res: Resources): String = res.getString(
    when (this) {
        GlobalKind.BACK -> R.string.global_back
        GlobalKind.HOME -> R.string.global_home
        GlobalKind.RECENTS -> R.string.global_recents
        GlobalKind.NOTIFICATIONS -> R.string.global_notifications
    }
)


fun MarkerDensity.label(res: Resources): String = res.getString(
    when (this) {
        MarkerDensity.ALL -> R.string.density_all
        MarkerDensity.RECENT -> R.string.density_recent
        MarkerDensity.HIDDEN -> R.string.density_hidden
    }
)

/**
 * One-line description of a clip for a list row.
 *
 * Takes counts rather than a [Clip], because the home screen has the counts and *not* the clip: the recent
 * list caches what each file held so that drawing twenty rows costs no reads. The overload below is for the
 * places that do have the clip in hand, so both routes produce the same sentence.
 */
fun clipSummary(res: Resources, stepCount: Int, pauseCount: Int, durationMs: Long): String {
    val duration = formatDuration(durationMs)
    return if (pauseCount > 0) {
        res.getString(R.string.clip_summary_with_pauses, stepCount, pauseCount, duration)
    } else {
        res.getString(R.string.clip_summary, stepCount, duration)
    }
}

fun clipSummary(res: Resources, clip: Clip): String =
    clipSummary(res, clip.stepCount, clip.pauseCount, clip.estimatedDurationMs)

/**
 * One line for a flow in a list: how many clips, and roughly how long one pass takes.
 *
 * Counts again rather than the flow, for the reason above.
 */
fun flowSummary(res: Resources, clipCount: Int, durationMs: Long): String =
    res.getString(R.string.flow_summary, clipCount, formatDuration(durationMs))

/**
 * The same line for a flow that is open, whose clips have been read.
 *
 * The estimate is the sum of each clip's own estimate multiplied by its repeat count, plus the gaps. Same
 * shape as a clip's estimate one level up — which is the point of the two layers having the same knobs. A
 * clip that could not be read counts as nothing; the row it belongs to says `!` for itself.
 */
fun flowSummary(res: Resources, flow: Flow, clips: Map<String, Clip>): String {
    val total = flow.clips.sumOf { node ->
        val clip = clips[node.ref] ?: return@sumOf 0L
        val passes = node.repeat.coerceAtLeast(1)
        node.delayBefore + clip.estimatedDurationMs * passes + node.extraPasses * node.repeatIntervalMs
    }
    return flowSummary(res, flow.clips.size, total)
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

/**
 * Name suggested for a clip that has never been saved, e.g. "Recording 07-25 14:32".
 *
 * A suggestion in the document picker's own name field, nothing more — the user types over it, and the file
 * they end up with is the clip's name. Timestamped because the alternative is every unnamed recording
 * colliding with the last one, and a date is the one thing about a fresh recording that is worth saying.
 */
fun defaultClipName(res: Resources, now: Long): String =
    res.getString(R.string.clip_default_name, stamp(now))

/** The same, for a new flow. */
fun defaultFlowName(res: Resources, now: Long): String =
    res.getString(R.string.flow_default_name, stamp(now))

private fun stamp(millis: Long): String =
    CLIP_NAME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private val CLIP_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
