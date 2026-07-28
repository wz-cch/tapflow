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

/**
 * The step list: one large centred window whose only job is reaching a step you cannot pick by sight.
 *
 * Selecting by marker breaks down long before a hundred steps, because the markers overlap. Typing a
 * number solves that when you know the number, and the settings panel offers exactly that — so what is
 * left for a list is the other case: finding the wrong step by *reading*, when all you know is that
 * something looks off. Walking there one step at a time is no use for that, so this is a whole page of
 * rows rather than a strip.
 *
 * Tapping a row selects that step and opens its settings panel. There is deliberately nothing else on
 * a row: no duplicate, no delete. Those act on the selection and live on the toolbar, so putting them
 * here as well would be a second place for the same act to drift in.
 *
 * Not draggable, and it does not need to be. It was a small floating strip once, and the drag handle
 * existed only to get it off whatever it was covering — a problem it had because it shared the screen
 * with the parameter card. Only one of the two is ever up now, so there is nothing to dodge.
 *
 * Rows are rebuilt only when the text actually changes. This view is refreshed from the same sync pass
 * as everything else, which runs several times a second during playback, and rebuilding a hundred
 * TextViews at that rate is exactly the kind of waste the panels here have been bitten by before.
 */
@SuppressLint("ViewConstructor")
class StepListView(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        /** Selects this step and opens its settings panel. */
        fun onSelectIndex(index: Int)
        fun onClose()
    }

    private val displayDensity = context.resources.displayMetrics.density

    private val position = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 13f
        typeface = Typeface.MONOSPACE
    }

    private val rows = LinearLayout(context).apply { orientation = VERTICAL }

    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
    }

    /** Last rendered text, so an unchanged list does not rebuild its rows. */
    private var renderedLines: List<String> = emptyList()
    private var selectedIndex = -1

    init {
        orientation = VERTICAL
        background = panelBackground()
        setPadding(dp(12f), dp(10f), dp(12f), dp(12f))

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(position, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(R.drawable.ic_close) { actions.onClose() })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        scroller.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Weighted rather than a fixed height: the window itself is sized by the service, and the rows
        // should take whatever is left after the header.
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * @param lines one entry per step, already formatted for display.
     * @param selected index of the selected step, or -1 for none.
     */
    fun render(lines: List<String>, selected: Int) {
        if (lines != renderedLines) {
            renderedLines = lines
            rebuild(lines)
        }
        if (selected != selectedIndex) {
            selectedIndex = selected
            highlight()
            scrollToSelection()
        }
        position.text = if (selected >= 0) {
            context.getString(R.string.step_list_position, selected + 1, lines.size)
        } else {
            context.getString(R.string.step_list_count, lines.size)
        }
    }

    private fun rebuild(lines: List<String>) {
        rows.removeAllViews()
        lines.forEachIndexed { index, line ->
            rows.addView(
                TextView(context).apply {
                    text = line
                    setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(8f), dp(9f), dp(8f), dp(9f))
                    isClickable = true
                    setOnClickListener { actions.onSelectIndex(index) }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        highlight()
    }

    private fun highlight() {
        for (index in 0 until rows.childCount) {
            val row = rows.getChildAt(index) as? TextView ?: continue
            val isSelected = index == selectedIndex
            row.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.overlay_text else R.color.overlay_text_dim,
                )
            )
            row.background = if (isSelected) rowHighlight() else null
        }
    }

    /** Puts the selected row near the middle, so its neighbours are visible for context. */
    private fun scrollToSelection() {
        val row = rows.getChildAt(selectedIndex) ?: return
        post { scroller.smoothScrollTo(0, row.top - scroller.height / 2 + row.height / 2) }
    }

    private var appliedScale = Float.NaN

    fun applyAppearance(scale: Float, opacity: Float) {
        alpha = opacity.coerceIn(0.3f, 1f)
        val clamped = scale.coerceIn(0.7f, 1.5f)
        if (clamped == appliedScale) return
        appliedScale = clamped
        position.textSize = 13f * clamped
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

    private fun rowHighlight() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8f).toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel_pressed))
    }

    private fun panelBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16f).toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel))
    }

    private fun dp(value: Float) = (value * displayDensity).toInt()
}
