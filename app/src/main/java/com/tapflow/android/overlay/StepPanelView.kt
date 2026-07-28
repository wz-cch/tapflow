package com.tapflow.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tapflow.android.R
import com.tapflow.android.data.GestureKind
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Step
import com.tapflow.android.text.secondsText

/**
 * Everything about one step that is not its position on screen.
 *
 * Centred and wide, deliberately unrelated to where the step's marker is. It shows numbers and
 * buttons; none of that has anything to do with the coordinate, and anchoring it beside the marker
 * only made it small, made it cover the marker, and made it collide with the step list. There is
 * exactly one of these and one of the list up at a time, which is what removes the overlap problem
 * rather than working around it with a drag handle.
 *
 * It is opened on a step and never without one — from the toolbar it opens on the selection, which
 * always exists while editing. That is why the navigation row here is the panel's own: there is no
 * "opened on nothing, default to the first" case for prev/next to rescue, so prev/next is free to
 * mean simply "the same panel, one step over".
 *
 * Closing always returns to the canvas. One exit, so the panel needs to remember nothing about who
 * opened it; to get back to the list, press the list button again.
 *
 * Duplicate and insert-before are here as well as (or instead of) on the toolbar, but delete is not,
 * and the line between them is not taste. Both of the first two *create* a step, and the panel then
 * lands on the new one, so you carry straight on editing what you just made. Delete destroys the very
 * step the panel is showing, which would force it to jump somewhere or close itself — and that is a
 * second behaviour for one action, which is exactly what keeping it in one place avoids.
 *
 * It stays non-focusable like every other overlay here, so it never steals input from the app
 * underneath. That rules out a text field, which is why the note on a pause is typed in the main app
 * and the seconds on a wait go through the number pad.
 */
@SuppressLint("ViewConstructor")
class StepPanelView(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun onPrevious()
        fun onNext()

        /** Opens the number pad to reach a step by its number. */
        fun onJumpToStep()
        fun onAdjustDuration(deltaMs: Long)
        fun onAdjustDelay(deltaMs: Long)

        /** Opens the number pad for how long a timed wait should last. */
        fun onEditWaitSeconds()

        /** Opens somewhere focusable to type the pause note. An overlay cannot raise a keyboard. */
        fun onEditNote()

        /** Captures this step's gesture again, keeping its lead delay. */
        fun onReRecord()

        /**
         * Copies this step, puts the copy straight after it, and moves to the copy.
         *
         * Also on the toolbar, and deliberately the same call underneath. Two entry points for one
         * behaviour is fine — what is not fine is two implementations, which is how the delete button
         * ended up living in one place only.
         *
         * Worth having here because this panel is somewhere you work *in*: on step 5, wanting another
         * one like it, closing the panel to press the toolbar and reopening it is three acts for one.
         * And landing on the copy means you are already looking at the numbers you came to change.
         */
        fun onDuplicate()

        /**
         * Captures a gesture and inserts it *before* this step.
         *
         * Labelled, and only here. The toolbar inserts after, matching the direction recording grows
         * in; this is the named exception for the one thing inserting after cannot express.
         */
        fun onInsertBefore()
        fun onClose()
    }

    private val displayDensity = context.resources.displayMetrics.density

    private val position = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
    }

    private val title = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val coordinates = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 13f
        typeface = Typeface.MONOSPACE
    }

    private val noteValue = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 13f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val waitValue = valueLabel()
    private val durationValue = valueLabel()
    private val delayValue = valueLabel()

    private val reRecord = textButton(R.string.param_rerecord) { actions.onReRecord() }
    private val editNote = textButton(R.string.param_edit_note) { actions.onEditNote() }
    private val editWait = textButton(R.string.param_change) { actions.onEditWaitSeconds() }
    private val duplicate = textButton(R.string.param_duplicate) { actions.onDuplicate() }
    private val insertBefore = textButton(R.string.param_insert_before) { actions.onInsertBefore() }

    private val coordinateRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(coordinates, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(reRecord)
    }

    private val noteRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(noteValue, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(editNote)
    }

    /**
     * How long a timed wait lasts.
     *
     * Its absence was the whole reason a wait's length could be set once, at insert, and never again —
     * the panel hid every row for a step with no coordinates, which is exactly what a wait is.
     */
    private val waitRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            label(R.string.param_wait_length),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(waitValue)
        addView(editWait)
    }

    private val durationRow = stepperRow(R.string.param_duration, durationValue, DURATION_STEP_MS) {
        actions.onAdjustDuration(it)
    }
    private val delayRow = stepperRow(R.string.param_delay, delayValue, DELAY_STEP_MS) {
        actions.onAdjustDelay(it)
    }

    /**
     * A ScrollView that wraps its content but never grows past [maxHeightPx].
     *
     * Same shape as the toolbar's, and for the same reason: the rows come to roughly 340dp, which does
     * not fit a phone in landscape. Without the cap the bottom row — "往前插" — ends up below the screen
     * edge and cannot be reached at all, which is the mistake §3.1 records for the toolbar column.
     *
     * A ScrollView is safe here where it would not be on the canvas: the panel is not draggable and
     * holds nothing else that wants vertical gestures, so there is nothing for it to fight with.
     */
    private class CappedScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx: Int = 0

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val capped = if (maxHeightPx > 0) {
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            } else {
                heightSpec
            }
            super.onMeasure(widthSpec, capped)
        }
    }

    private val body = LinearLayout(context).apply { orientation = VERTICAL }

    private val scroller = CappedScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    init {
        orientation = VERTICAL
        background = panelBackground()
        setPadding(dp(16f), dp(12f), dp(16f), dp(14f))

        // The navigation row stays outside the scroller, so paging and closing are reachable without
        // scrolling back up to find them.
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconButton(R.drawable.ic_chevron_left) { actions.onPrevious() })
                addView(position, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(R.drawable.ic_chevron_right) { actions.onNext() })
                addView(textButton(R.string.step_list_jump) { actions.onJumpToStep() })
                addView(iconButton(R.drawable.ic_close) { actions.onClose() })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        body.addView(title, rowParams())
        body.addView(coordinateRow, rowParams())
        body.addView(noteRow, rowParams())
        body.addView(waitRow, rowParams())
        body.addView(durationRow, rowParams())
        body.addView(delayRow, rowParams())
        // Both of these create a step; they sit together, in the order of where the new step lands.
        // There is no delete down here — see the class comment.
        body.addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.END
                addView(insertBefore)
                addView(Space(context), LayoutParams(dp(8f), 1))
                addView(duplicate)
            },
            rowParams(),
        )

        scroller.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * @param availableHeightPx how tall the rows may be before they start scrolling. Recomputed on every
     *   refresh, so a rotation or a change of UI scale re-caps it.
     */
    fun setAvailableHeight(availableHeightPx: Int) {
        val forScroller = (availableHeightPx - dp(64f)).coerceAtLeast(dp(120f))
        if (scroller.maxHeightPx == forScroller) return
        scroller.maxHeightPx = forScroller
        scroller.requestLayout()
    }

    /**
     * @param number the 1-based position of the step, matching the on-screen marker.
     * @param total how many steps there are, so the header reads the same as playback's progress.
     */
    fun render(step: Step, number: Int, total: Int) {
        position.text = context.getString(R.string.step_list_position, number, total)
        title.text = typeLabel(step)

        val gesture = step as? GestureStep
        coordinateRow.visibility = if (gesture != null) VISIBLE else GONE
        durationRow.visibility = if (gesture != null) VISIBLE else GONE
        if (gesture != null) {
            coordinates.text = context.getString(
                R.string.param_coordinates,
                gesture.anchor.x.toInt(),
                gesture.anchor.y.toInt(),
            )
            durationValue.text = context.getString(R.string.param_ms, gesture.duration)
        }

        // Only a pause carries a note: it is there to remind you what this stop is for.
        val pause = step as? PauseStep
        noteRow.visibility = if (pause != null) VISIBLE else GONE
        if (pause != null) {
            noteValue.text = pause.note.ifBlank { context.getString(R.string.param_note_empty) }
        }

        // Shown for a timed wait only. A manual pause has no length to set, and offering one here
        // would quietly turn it into a timed one — a type change dressed up as a number.
        val timed = pause?.takeIf { it.isTimed }
        waitRow.visibility = if (timed != null) VISIBLE else GONE
        if (timed != null) {
            waitValue.text = context.getString(R.string.param_seconds, secondsText(timed.ms))
        }

        delayValue.text = context.getString(R.string.param_ms, step.delayBefore)
    }

    private fun typeLabel(step: Step): String = context.getString(
        when (step) {
            is PauseStep -> if (step.isTimed) R.string.param_type_wait else R.string.param_type_pause
            is GestureStep -> when (step.kind) {
                GestureKind.TAP -> R.string.param_type_tap
                GestureKind.LONG_PRESS -> R.string.param_type_long_press
                GestureKind.SWIPE -> R.string.param_type_swipe
                GestureKind.MULTI_TOUCH -> R.string.param_type_multi_touch
            }

            else -> R.string.param_type_other
        }
    )

    private var appliedScale = Float.NaN

    fun applyAppearance(scale: Float, opacity: Float) {
        alpha = opacity.coerceIn(0.3f, 1f)
        val clamped = scale.coerceIn(0.7f, 1.5f)
        if (clamped == appliedScale) return
        appliedScale = clamped
        position.textSize = 13f * clamped
        title.textSize = 15f * clamped
    }

    private fun stepperRow(
        labelRes: Int,
        value: TextView,
        stepMs: Long,
        onAdjust: (Long) -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(labelRes), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
        addView(iconButton(R.drawable.ic_remove) { onAdjust(-stepMs) })
        addView(iconButton(R.drawable.ic_add) { onAdjust(stepMs) })
    }

    private fun label(labelRes: Int) = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 13f
        setText(labelRes)
    }

    private fun valueLabel() = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.END
        minWidth = dp(72f)
    }

    private fun iconButton(resId: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.overlay_icon))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        layoutParams = LayoutParams(dp(40f), dp(40f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun textButton(labelRes: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(labelRes)
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f).toFloat()
            setColor(ContextCompat.getColor(context, R.color.overlay_panel_pressed))
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun panelBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16f).toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel))
    }

    private fun rowParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(8f) }

    private fun dp(value: Float) = (value * displayDensity).toInt()

    private companion object {
        const val DURATION_STEP_MS = 25L
        const val DELAY_STEP_MS = 100L
    }
}
