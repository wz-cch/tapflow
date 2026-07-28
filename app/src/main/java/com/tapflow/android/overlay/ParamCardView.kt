package com.tapflow.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tapflow.android.R
import com.tapflow.android.data.GestureKind
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Step

/**
 * Editing panel for the selected step.
 *
 * A real window with real buttons rather than a card painted onto the canvas. Hand-rolling hit
 * testing for a dozen controls on top of the canvas's own marker hit testing would be a lot of
 * fiddly geometry for no gain, and this way the touch targets are the platform's problem.
 *
 * It stays non-focusable like every other overlay here, so it never steals input from the app
 * underneath. That rules out a text field, which is why the note on a pause point is edited in the
 * main app instead.
 */
@SuppressLint("ViewConstructor")
class ParamCardView(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun onAdjustDuration(deltaMs: Long)
        fun onAdjustDelay(deltaMs: Long)
        fun onPickCoordinate()
        fun onDelete()
        fun onDone()
    }

    private val displayDensity = context.resources.displayMetrics.density

    private val title = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val coordinates = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 12f
        typeface = Typeface.MONOSPACE
    }

    private val pick = textButton(R.string.param_repick)
    private val durationValue = valueLabel()
    private val delayValue = valueLabel()
    private val delete = textButton(R.string.clip_action_delete)
    private val done = textButton(R.string.param_done)

    private val durationRow = stepperRow(R.string.param_duration, durationValue, DURATION_STEP_MS) {
        actions.onAdjustDuration(it)
    }
    private val delayRow = stepperRow(R.string.param_delay, delayValue, DELAY_STEP_MS) {
        actions.onAdjustDelay(it)
    }
    private val coordinateRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(coordinates, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(pick)
    }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(ContextCompat.getColor(context, R.color.overlay_panel))
        }
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))

        addView(title)
        addView(coordinateRow, rowParams())
        addView(durationRow, rowParams())
        addView(delayRow, rowParams())

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.END
                addView(delete)
                addView(Space(context), LayoutParams(dp(8f), 1))
                addView(done)
            },
            rowParams(),
        )

        pick.setOnClickListener { actions.onPickCoordinate() }
        delete.setOnClickListener { actions.onDelete() }
        done.setOnClickListener { actions.onDone() }
    }

    /** @param number the 1-based position of the step, matching the on-screen marker. */
    fun render(step: Step, number: Int) {
        title.text = context.getString(R.string.param_title, number, typeLabel(step))

        val gesture = step as? GestureStep
        val editable = gesture != null

        coordinateRow.visibility = if (editable) VISIBLE else GONE
        durationRow.visibility = if (editable) VISIBLE else GONE

        if (gesture != null) {
            coordinates.text = context.getString(
                R.string.param_coordinates,
                gesture.anchor.x.toInt(),
                gesture.anchor.y.toInt(),
            )
            durationValue.text = context.getString(R.string.param_ms, gesture.duration)
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

    private fun stepperRow(
        labelRes: Int,
        value: TextView,
        stepMs: Long,
        onAdjust: (Long) -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
                textSize = 12f
                setText(labelRes)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(value)
        addView(iconButton(R.drawable.ic_remove) { onAdjust(-stepMs) })
        addView(iconButton(R.drawable.ic_add) { onAdjust(stepMs) })
    }

    private fun valueLabel() = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.END
        minWidth = dp(64f)
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

    private fun textButton(labelRes: Int) = TextView(context).apply {
        setText(labelRes)
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f).toFloat()
            setColor(ContextCompat.getColor(context, R.color.overlay_panel_pressed))
        }
        isClickable = true
    }

    private fun rowParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(6f) }

    private fun dp(value: Float) = (value * displayDensity).toInt()

    private companion object {
        const val DURATION_STEP_MS = 25L
        const val DELAY_STEP_MS = 100L
    }
}
