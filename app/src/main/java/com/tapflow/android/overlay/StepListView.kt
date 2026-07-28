package com.tapflow.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tapflow.android.R

/**
 * The step list, as a window you can scroll and tap.
 *
 * Selecting by marker breaks down long before a hundred steps: the markers overlap, and playback
 * reports which step failed as a *number* — "47 / 100" — with no way to turn that number back into a
 * marker. This is the surface that closes that gap.
 *
 * Its own window rather than painted on the canvas, for two reasons. The painted list can only show
 * the tail that fits and cannot scroll, and a scrollable region inside the canvas would fight the
 * canvas for vertical drags — the same conflict that keeps the toolbar's drag handle outside its
 * ScrollView.
 *
 * Rows are rebuilt only when the text actually changes. This view is refreshed from the same sync
 * pass as everything else, which runs several times a second during playback, and rebuilding a hundred
 * TextViews at that rate is exactly the kind of waste the panels here have been bitten by before.
 */
@SuppressLint("ViewConstructor")
class StepListView(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun onSelectIndex(index: Int)
        fun onPrevious()
        fun onNext()

        /** Opens the number pad to jump straight to a step by its number. */
        fun onJumpToStep()
        fun onClose()

        /** Dragged by the header. The panel covers a strip of screen, so it has to be movable. */
        fun onDrag(dx: Int, dy: Int)
        fun onDragEnd()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

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

    private val jump = textButton(R.string.step_list_jump) { actions.onJumpToStep() }

    /** Last rendered text, so an unchanged list does not rebuild its rows. */
    private var renderedLines: List<String> = emptyList()
    private var selectedIndex = -1

    init {
        orientation = VERTICAL
        background = panelBackground(dp(14f))
        setPadding(dp(10f), dp(8f), dp(10f), dp(10f))

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(position, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(jump)
                addView(iconButton(R.drawable.ic_undo) { actions.onPrevious() })
                addView(iconButton(R.drawable.ic_add) { actions.onNext() })
                addView(iconButton(R.drawable.ic_close) { actions.onClose() })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        scroller.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, dp(150f)))

        // Dragged by the position label, which is the one part of the header that is not a button.
        // Deliberately not the list itself: the ScrollView needs vertical drags, and letting the panel
        // move on those would make scrolling impossible — the mistake already recorded for the
        // toolbar's drag handle.
        attachDrag(position)
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
                    if (!dragging && kotlin.math.hypot(totalX, totalY) > touchSlop) dragging = true
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
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
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

    fun applyAppearance(scale: Float, opacity: Float) {
        alpha = opacity.coerceIn(0.3f, 1f)
        val clamped = scale.coerceIn(0.7f, 1.5f)
        if (clamped == appliedScale) return
        appliedScale = clamped
        position.textSize = 13f * clamped
    }

    private var appliedScale = Float.NaN

    private fun textButton(labelRes: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(labelRes)
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
        textSize = 12f
        gravity = Gravity.CENTER
        background = rowHighlight()
        setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun iconButton(resId: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.overlay_icon))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        layoutParams = LayoutParams(dp(32f), dp(32f)).apply { setMargins(dp(2f), 0, dp(2f), 0) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun rowHighlight() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8f).toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel_pressed))
    }

    private fun panelBackground(radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel))
    }

    private fun dp(value: Float) = (value * displayDensity).toInt()
}
