package com.tapflow.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tapflow.android.R
import com.tapflow.android.engine.Mode
import com.tapflow.android.engine.Progress
import kotlin.math.hypot

/**
 * The narrow panel along the top: stop, pause, loop and step counters, elapsed time.
 *
 * Kept deliberately short and thin, because anything it covers cannot receive a replayed touch.
 * Only shown while recording, counting down, playing or paused.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TransportView(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun onStop()
        fun onPauseOrResume()
        fun onSkipAhead()
        fun onDrag(dx: Int, dy: Int)
        fun onDragEnd()
    }

    private val displayDensity = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val stop = icon(R.drawable.ic_stop)
    private val pause = icon(R.drawable.ic_pause)

    /**
     * Ends whichever clock is running: a timed wait, or a step repeating for a length of time.
     *
     * One button for both, because it means the same thing in both — stop waiting for this, go on — and a
     * second one that looked like it would cost panel width every time either was on screen. What the panel
     * covers cannot receive a replayed touch, so width is the currency here.
     *
     * **Last in the row, and that is the reason it is here rather than beside pause.** It appears and
     * disappears during a run, so anything after it would move under the user's finger; at the end, the
     * panel grows and shrinks to the right — and the panel is TOP|START anchored — so stop and pause stay
     * put. Reaching for skip as the wait ends therefore cannot land on stop.
     *
     * With one exception: a panel dragged hard against the right edge gets nudged left by up to this
     * button's width when it appears, because widening it trips the service's clamp. Once, not persisted,
     * and cheaper than the alternative — reserving the space with INVISIBLE would widen the panel for every
     * run, and what the panel covers cannot receive a replayed touch.
     */
    private val skip = icon(R.drawable.ic_skip)

    private val status = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 12f
        gravity = Gravity.CENTER
    }

    private val timer = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 12f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val textColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10f), 0, dp(10f), 0)
        addView(status)
        addView(timer)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12f).toFloat()
            setColor(ContextCompat.getColor(context, R.color.overlay_panel))
        }
        setPadding(dp(6f), dp(4f), dp(6f), dp(4f))

        addView(stop)
        addView(pause)
        addView(textColumn)
        addView(skip)

        stop.setOnClickListener { actions.onStop() }
        pause.setOnClickListener { actions.onPauseOrResume() }
        skip.setOnClickListener { actions.onSkipAhead() }

        // The text column is the drag handle: it has no tap action of its own, so there is nothing
        // to disambiguate against.
        attachDrag(textColumn)

        stop.contentDescription = context.getString(R.string.action_stop)
        pause.contentDescription = context.getString(R.string.action_pause)
        skip.contentDescription = context.getString(R.string.action_skip_ahead)
    }

    /** @param waitRemaining seconds left on a timed wait step, 0 when none is running. */
    fun render(
        mode: Mode,
        progress: Progress?,
        countdown: Int,
        waitRemaining: Int,
        elapsedMs: Long,
        showTimer: Boolean,
        pausePrompt: String?,
    ) {
        pause.setImageResource(if (mode == Mode.PAUSED) R.drawable.ic_play else R.drawable.ic_pause)
        pause.contentDescription = context.getString(
            if (mode == Mode.PAUSED) R.string.action_resume else R.string.action_pause
        )

        val recording = mode == Mode.RECORDING
        pause.visibility = if (recording) GONE else VISIBLE

        status.text = when {
            mode == Mode.COUNTDOWN -> context.getString(R.string.transport_countdown, countdown)
            recording -> context.getString(R.string.transport_recording, progress?.totalSteps ?: 0)
            mode == Mode.PAUSED -> pausePrompt ?: context.getString(R.string.action_pause)
            progress != null -> buildString {
                append(loopText(progress))
                // Only above one. A flow of a single clip should read exactly like running that clip on
                // its own, and "1 / 1" in front of the step counter is noise, not information.
                if (progress.totalClips > 1) {
                    append("   ")
                    append(
                        context.getString(
                            R.string.transport_clip,
                            progress.clip,
                            progress.totalClips,
                        )
                    )
                }
                // Step 0 means "between passes, in the gap before the next one starts". There is no step
                // running, and printing "0 / 100" would read as a broken counter rather than as a wait.
                if (progress.step > 0) {
                    append("   ")
                    append(context.getString(R.string.transport_step, progress.step, progress.totalSteps))
                }
                // Only when there is actually a repeat, and only a counted one. A step running ten times
                // with a gap between each otherwise holds the same number for ten seconds and reads as
                // frozen — while anything on a clock is counted down on the timer line instead, beside the
                // button that ends it.
                if (progress.repeatTotal > 1) {
                    append(" ")
                    append(
                        context.getString(
                            R.string.transport_repeat,
                            progress.repeatPass,
                            progress.repeatTotal,
                        )
                    )
                }
            }
            else -> ""
        }

        // **This line always shows the clock the skip button would end**, and that is the whole of how the
        // button explains itself. Three things can be counting — a timed wait, a step repeating for a
        // length of time, a clip in a flow doing the same — and only one can win. The innermost does, and
        // the player has already resolved which that is; here it is only a matter of putting it where the
        // button is looking.
        //
        // The timer's line rather than the status line, so the step position stays readable while a
        // 30-second wait runs — "waiting, at step 12 of 40" is worth more than either half on its own. It
        // also cannot widen the panel: that line is already there, and its digits are monospaced. The
        // elapsed clock is the only casualty, and it comes straight back.
        val waiting = waitRemaining > 0
        val clock = progress?.repeatRemainingMs ?: 0
        val counting = waiting || clock > 0
        timer.visibility = if ((showTimer || counting) && !recording) VISIBLE else GONE
        timer.text = when {
            waiting -> context.getString(R.string.transport_wait, waitRemaining)
            clock > 0 -> context.getString(R.string.transport_repeat_left, formatMinutes(clock))
            else -> formatElapsed(elapsedMs)
        }

        // Gone while paused. The number stays on screen, frozen, which is the honest reading of pausing
        // during a wait — and a skip pressed then would sit there doing nothing visible until resume.
        //
        // Anything on a clock gets the same button, and the line above says which one it is about.
        skip.visibility = if (counting && mode != Mode.PAUSED) VISIBLE else GONE
    }

    private var appliedScale = Float.NaN
    private var appliedOpacity = Float.NaN

    /** Same reasoning as the toolbar: this runs on the timer tick, so it must not relayout blindly. */
    fun applyAppearance(scale: Float, opacity: Float) {
        val clampedScale = scale.coerceIn(0.7f, 1.5f)
        val clampedOpacity = opacity.coerceIn(0.3f, 1f)
        if (clampedScale == appliedScale && clampedOpacity == appliedOpacity) return
        appliedScale = clampedScale
        appliedOpacity = clampedOpacity

        alpha = clampedOpacity
        val size = (dp(40f) * clampedScale).toInt()
        listOf(stop, pause, skip).forEach { it.layoutParams = LayoutParams(size, size) }
        val text = 12f * clampedScale
        status.textSize = text
        timer.textSize = text
        requestLayout()
    }

    private fun loopText(progress: Progress): String =
        if (progress.totalLoops <= 0) {
            context.getString(R.string.transport_loop_infinite, progress.loop)
        } else {
            context.getString(R.string.transport_loop, progress.loop, progress.totalLoops)
        }

    /** Minutes and seconds, rounded up, so a countdown never shows 0:00 while it is still going. */
    private fun formatMinutes(ms: Long): String {
        val total = (ms + 999) / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d : %02d : %02d".format(hours, minutes, seconds)
    }

    private fun icon(resId: Int): ImageView = ImageView(context).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.overlay_icon))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        layoutParams = LayoutParams(dp(40f), dp(40f))
        isClickable = true
    }

    private fun attachDrag(view: View) {
        var lastX = 0f
        var lastY = 0f
        var totalX = 0f
        var totalY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    totalX = 0f
                    totalY = 0f
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    totalX += dx
                    totalY += dy
                    if (!dragging && hypot(totalX, totalY) > touchSlop) dragging = true
                    if (dragging) {
                        actions.onDrag(dx.toInt(), dy.toInt())
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) actions.onDragEnd()
                    true
                }

                else -> false
            }
        }
    }

    private fun dp(value: Float) = (value * displayDensity).toInt()
}
