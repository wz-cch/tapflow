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
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tapflow.android.R
import com.tapflow.android.data.Settings
import kotlin.math.roundToInt

/**
 * The settings worth changing while standing in the target app.
 *
 * These all have an effect you want to see in place: how many times to repeat, how fast, whether to
 * black the screen out, how big the toolbar is. Having to switch back to the app, change one number,
 * and switch forward again was the complaint this exists to answer.
 *
 * Values that are only consulted when a new action is created — the default tap and swipe durations —
 * stay in the app, where there is room to explain them.
 */
@SuppressLint("ViewConstructor")
class QuickSettingsView(context: Context, private val actions: Actions) : ScrollView(context) {

    interface Actions {
        fun onAdjustLoopCount(delta: Int)
        fun onAdjustSpeed(delta: Float)
        fun onToggleReplayEachGesture()
        fun onToggleKeepScreenOn()
        fun onToggleDim()
        fun onToggleTimer()
        fun onAdjustUiScale(delta: Float)
        fun onAdjustUiOpacity(delta: Float)
        fun onOpenFullSettings()
        fun onClose()
    }

    private val displayDensity = context.resources.displayMetrics.density

    private val loopValue = valueLabel()
    private val speedValue = valueLabel()
    private val scaleValue = valueLabel()
    private val opacityValue = valueLabel()
    private val replayToggle = togglePill()
    private val keepScreenToggle = togglePill()
    private val dimToggle = togglePill()
    private val timerToggle = togglePill()

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
    }

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(ContextCompat.getColor(context, R.color.overlay_panel))
        }
        isFillViewport = true
        addView(rows, LayoutParams(dp(300f), LayoutParams.WRAP_CONTENT))

        rows.addView(header())
        rows.addView(stepperRow(R.string.settings_loop_count, loopValue, { actions.onAdjustLoopCount(-1) }) {
            actions.onAdjustLoopCount(1)
        })
        rows.addView(stepperRow(R.string.settings_speed, speedValue, { actions.onAdjustSpeed(-0.25f) }) {
            actions.onAdjustSpeed(0.25f)
        })
        rows.addView(toggleRow(R.string.settings_replay_each, replayToggle) {
            actions.onToggleReplayEachGesture()
        })
        rows.addView(toggleRow(R.string.settings_keep_screen_on, keepScreenToggle) {
            actions.onToggleKeepScreenOn()
        })
        rows.addView(toggleRow(R.string.settings_dim, dimToggle) { actions.onToggleDim() })
        rows.addView(toggleRow(R.string.settings_show_timer, timerToggle) { actions.onToggleTimer() })
        rows.addView(stepperRow(R.string.settings_ui_scale, scaleValue, { actions.onAdjustUiScale(-0.1f) }) {
            actions.onAdjustUiScale(0.1f)
        })
        rows.addView(stepperRow(R.string.settings_ui_opacity, opacityValue, { actions.onAdjustUiOpacity(-0.1f) }) {
            actions.onAdjustUiOpacity(0.1f)
        })
        rows.addView(fullSettingsButton())
    }

    fun render(settings: Settings) {
        loopValue.text = if (settings.defaultLoopCount <= 0) {
            context.getString(R.string.settings_loop_forever)
        } else {
            context.getString(R.string.value_times, settings.defaultLoopCount)
        }
        speedValue.text = context.getString(R.string.value_multiplier, "%.2f".format(settings.speed))
        scaleValue.text = context.getString(R.string.value_multiplier, "%.1f".format(settings.uiScale))
        opacityValue.text =
            context.getString(R.string.value_percent, (settings.uiOpacity * 100).roundToInt())

        setToggle(replayToggle, settings.replayEachGesture)
        setToggle(keepScreenToggle, settings.keepScreenOn)
        setToggle(dimToggle, settings.dimOverlay)
        setToggle(timerToggle, settings.showTimer)
    }

    private fun header() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(context).apply {
                setText(R.string.quick_title)
                setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(iconButton(R.drawable.ic_close) { actions.onClose() })
    }

    private fun stepperRow(
        labelRes: Int,
        value: TextView,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(labelRes), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
        addView(iconButton(R.drawable.ic_remove, onMinus))
        addView(iconButton(R.drawable.ic_add, onPlus))
    }

    private fun toggleRow(labelRes: Int, pill: TextView, onToggle: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(labelRes), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill)
            pill.setOnClickListener { onToggle() }
        }

    private fun fullSettingsButton() = TextView(context).apply {
        setText(R.string.quick_full_settings)
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(12f), dp(12f), dp(6f))
        isClickable = true
        setOnClickListener { actions.onOpenFullSettings() }
    }

    private fun label(labelRes: Int) = TextView(context).apply {
        setText(labelRes)
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 12f
        setPadding(0, dp(10f), dp(6f), dp(10f))
    }

    private fun valueLabel() = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.END
        minWidth = dp(72f)
    }

    /**
     * A pill rather than a Switch. Switch picks up the app theme, which is light, and would look
     * wrong on these dark panels; styling one for a service context is more trouble than a TextView.
     */
    private fun togglePill() = TextView(context).apply {
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(56f)
        setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
        isClickable = true
    }

    private fun setToggle(pill: TextView, on: Boolean) {
        pill.setText(if (on) R.string.quick_on else R.string.quick_off)
        pill.setTextColor(
            ContextCompat.getColor(context, if (on) R.color.overlay_panel else R.color.overlay_text_dim)
        )
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(
                ContextCompat.getColor(
                    context,
                    if (on) R.color.state_playing else R.color.overlay_panel_pressed,
                )
            )
        }
    }

    private fun iconButton(resId: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.overlay_icon))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(value: Float) = (value * displayDensity).toInt()
}
