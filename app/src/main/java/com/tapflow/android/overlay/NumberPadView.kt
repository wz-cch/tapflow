package com.tapflow.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tapflow.android.R

/**
 * A keypad for typing one number, drawn as ordinary buttons.
 *
 * Every floating window here is FLAG_NOT_FOCUSABLE, because taking focus would steal the input
 * method from the app underneath and make pause points useless. That rules out an EditText, which is
 * why naming a clip is an Activity instead ([com.tapflow.android.WorkspaceDialogActivity]).
 *
 * A number needs no IME though — ten digits, a backspace and a confirm are just views. Keeping it as
 * an overlay is what lets a wait be inserted **without leaving the app being recorded**: an Activity
 * would background the target app mid-recording while the canvas was still intercepting.
 *
 * Deliberately holds no idea of what the number means. The caller supplies the title, the unit and
 * what to do with the value, so the same pad serves both "how many seconds" and "jump to which step".
 */
@SuppressLint("ViewConstructor")
class NumberPadView(context: Context, private val onCancel: () -> Unit) : LinearLayout(context) {

    /** Set by [open]. Held here rather than in the constructor so one pad can serve several callers. */
    private var onConfirm: (Int) -> Unit = {}

    private val displayDensity = context.resources.displayMetrics.density

    private val title = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 13f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
    }

    private val readout = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 26f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setPadding(0, dp(6f), 0, dp(10f))
    }

    /** Digits as typed. Kept as text so leading zeros and an empty entry are representable. */
    private var entry = ""

    /** Clamp applied on confirm, so a slip of the finger cannot store an absurd wait. */
    private var maxValue = 9999

    private var unitSuffix = ""

    init {
        orientation = VERTICAL
        background = panelBackground(dp(16f))
        setPadding(dp(12f), dp(10f), dp(12f), dp(12f))

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(R.drawable.ic_close) { onCancel() })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(readout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 1-9 in reading order, then backspace / 0 / confirm — the layout of every phone dialler, so
        // there is nothing to learn.
        addView(digitRow(1, 2, 3))
        addView(digitRow(4, 5, 6))
        addView(digitRow(7, 8, 9))
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(padButton(icon = R.drawable.ic_undo) { backspace() })
                addView(digitButton(0))
                addView(padButton(icon = R.drawable.ic_check) { confirm() })
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
    }

    /**
     * Reopens the pad for a fresh value.
     *
     * @param initialValue prefilled and replaced by the first digit typed, not appended to — the
     *   common case is entering a different number, not editing the existing one digit by digit.
     */
    fun open(titleText: String, unit: String, initialValue: Int, max: Int, confirm: (Int) -> Unit) {
        onConfirm = confirm
        title.text = titleText
        unitSuffix = unit
        maxValue = max
        entry = if (initialValue > 0) initialValue.toString() else ""
        replaceOnNextDigit = initialValue > 0
        refresh()
    }

    private var replaceOnNextDigit = false

    private fun append(digit: Int) {
        if (replaceOnNextDigit) {
            entry = ""
            replaceOnNextDigit = false
        }
        // Cap the length rather than the value, so typing stays predictable — a digit either lands or
        // it does not, instead of silently rewriting what is already there.
        if (entry.length >= maxValue.toString().length) return
        if (entry.isEmpty() && digit == 0) return
        entry += digit.toString()
        refresh()
    }

    private fun backspace() {
        replaceOnNextDigit = false
        if (entry.isNotEmpty()) entry = entry.dropLast(1)
        refresh()
    }

    private fun confirm() {
        val value = entry.toIntOrNull() ?: return
        if (value <= 0) return
        onConfirm(value.coerceAtMost(maxValue))
    }

    private fun refresh() {
        readout.text = if (entry.isEmpty()) unitSuffix else "$entry $unitSuffix"
    }

    fun applyAppearance(scale: Float, opacity: Float) {
        alpha = opacity.coerceIn(0.3f, 1f)
        val clamped = scale.coerceIn(0.7f, 1.5f)
        if (clamped == appliedScale) return
        appliedScale = clamped
        readout.textSize = 26f * clamped
        title.textSize = 13f * clamped
    }

    private var appliedScale = Float.NaN

    private fun digitRow(vararg digits: Int) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        digits.forEach { addView(digitButton(it)) }
    }

    private fun digitButton(digit: Int) = TextView(context).apply {
        text = digit.toString()
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 20f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        background = keyBackground()
        layoutParams = LayoutParams(dp(54f), dp(46f)).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) }
        isClickable = true
        setOnClickListener { append(digit) }
    }

    private fun padButton(icon: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.overlay_icon))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = keyBackground()
        layoutParams = LayoutParams(dp(54f), dp(46f)).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun iconButton(resId: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.overlay_icon))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        layoutParams = LayoutParams(dp(32f), dp(32f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun keyBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10f).toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel_pressed))
    }

    private fun panelBackground(radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel))
    }

    private fun dp(value: Float) = (value * displayDensity).toInt()
}
