package com.tapflow.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tapflow.android.R

/**
 * Pick one of a handful of named things.
 *
 * The sibling of [NumberPadView], and here for the same reason: an insertion that has to ask something
 * first must ask on an overlay, not in an Activity. An Activity would push the app being recorded into
 * the background, which is exactly the screen the next recorded step is meant to land on.
 *
 * Self-drawn rows rather than a Spinner or a dialog, because every overlay here is
 * `FLAG_NOT_FOCUSABLE` and neither of those works without focus.
 *
 * It knows nothing about what the options mean — the caller supplies the labels and gets back the index
 * it chose. One pad serves whichever question needs it.
 */
@SuppressLint("ViewConstructor")
class OptionPadView(context: Context, private val onCancel: () -> Unit) : LinearLayout(context) {

    private var onPick: (Int) -> Unit = {}

    private val title = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 13f
    }

    private val options = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f).toFloat()
            setColor(ContextCompat.getColor(context, R.color.overlay_panel))
        }
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))

        addView(title)
        addView(options, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            textButton(R.string.dialog_cancel) { onCancel() },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10f) },
        )
    }

    /** @param pick receives the index of the chosen label. */
    fun open(titleText: String, labels: List<String>, pick: (Int) -> Unit) {
        onPick = pick
        title.text = titleText
        options.removeAllViews()
        labels.forEachIndexed { index, text ->
            options.addView(
                TextView(context).apply {
                    this.text = text
                    setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
                    textSize = 15f
                    gravity = Gravity.CENTER
                    background = keyBackground()
                    setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                    isClickable = true
                    setOnClickListener { onPick(index) }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6f) },
            )
        }
    }

    private var appliedScale = Float.NaN

    fun applyAppearance(scale: Float, opacity: Float) {
        alpha = opacity.coerceIn(0.3f, 1f)
        val clamped = scale.coerceIn(0.7f, 1.5f)
        if (clamped == appliedScale) return
        appliedScale = clamped
        title.textSize = 13f * clamped
    }

    private fun textButton(labelRes: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(labelRes)
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 13f
        gravity = Gravity.CENTER
        background = keyBackground()
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun keyBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10f).toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel_pressed))
    }

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).toInt()
}
