package com.tapflow.android.text

import android.content.res.Resources
import com.tapflow.android.R
import com.tapflow.android.data.AwaitTextNode
import com.tapflow.android.data.Clip
import com.tapflow.android.data.ClipNode
import com.tapflow.android.data.GestureKind
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.GlobalKind
import com.tapflow.android.data.GlobalNode
import com.tapflow.android.data.GlobalStep
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.data.MatchMode
import com.tapflow.android.data.Node
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Step
import com.tapflow.android.data.WaitNode
import com.tapflow.android.data.WaitStep
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

fun Step.label(res: Resources): String = when (this) {
    // Named differently from this function on purpose: two same-named extensions on a type and its
    // supertype invite the compiler to resolve the wrong one and recurse forever.
    is GestureStep -> gestureLabel(res)
    is GlobalStep -> res.getString(R.string.step_global, kind.label(res))
    is WaitStep -> res.getString(R.string.step_wait, ms)
    is PauseStep -> if (note.isBlank()) {
        res.getString(R.string.step_pause)
    } else {
        res.getString(R.string.step_pause_with_note, note)
    }
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

fun MatchMode.label(res: Resources): String = res.getString(
    when (this) {
        MatchMode.CONTAINS -> R.string.match_contains
        MatchMode.EXACT -> R.string.match_exact
        MatchMode.VIEW_ID -> R.string.match_view_id
    }
)

fun MarkerDensity.label(res: Resources): String = res.getString(
    when (this) {
        MarkerDensity.ALL -> R.string.density_all
        MarkerDensity.RECENT -> R.string.density_recent
        MarkerDensity.HIDDEN -> R.string.density_hidden
    }
)

fun Node.label(res: Resources, clips: List<Clip>): String = when (this) {
    is ClipNode -> {
        val name = clips.firstOrNull { it.id == clipId }?.name
            ?: res.getString(R.string.node_clip_missing)
        if (repeat > 1) res.getString(R.string.node_clip_repeat, name, repeat) else name
    }

    is WaitNode -> res.getString(R.string.step_wait, ms)
    is AwaitTextNode -> res.getString(R.string.node_await_text, text)
    is GlobalNode -> res.getString(R.string.step_global, kind.label(res))
}

/** Default name for a freshly saved clip, e.g. "Recording 07-25 14:32". */
fun defaultClipName(res: Resources, createdAt: Long): String {
    val stamp = CLIP_NAME_FORMAT.format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()))
    return res.getString(R.string.clip_default_name, stamp)
}

private val CLIP_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
