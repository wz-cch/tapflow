package com.tapflow.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.tapflow.android.R
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.engine.Mode
import com.tapflow.android.engine.ToolbarForm
import kotlin.math.hypot

/** What tapping the collapsed ball should do. Set by the service, since only it knows why we collapsed. */
enum class BallIntent { EXPAND, RESUME_PLAYBACK, RESUME_RECORDING }

/**
 * The vertical toolbar, in its two shapes: expanded, and collapsed to a ball.
 *
 * Both live in one window so there is only one z-order and one saved position to manage. The
 * view stays dumb about what the buttons mean — [Actions.onPrimary] and [Actions.onSecondary] are
 * interpreted by the service according to the current mode, so the mapping lives in one place.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ToolbarView(context: Context, private val actions: Actions) : FrameLayout(context) {

    interface Actions {
        /** Play, pause or resume, depending on mode. */
        fun onPrimary()

        /** Start/stop recording when idle or recording; stop playback otherwise. */
        fun onSecondary()

        fun onInsertPausePoint()
        fun onUndo()
        fun onToggleEdit()
        fun onAddTap()
        fun onDeleteSelected()
        fun onSave()
        fun onLoad()
        fun onNewClip()
        fun onCycleDensity()
        fun onToggleQuickSettings()
        fun onDismiss()
        fun onCollapse()
        fun onExpand()

        /** Incremental drag in pixels. */
        fun onDrag(dx: Int, dy: Int)
        fun onDragEnd()
    }

    private val displayDensity = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val iconIdle = ContextCompat.getColor(context, R.color.overlay_icon)

    /**
     * A ScrollView that wraps its content but never grows past [maxHeightPx].
     *
     * The button column is around 480dp tall, which does not fit a phone in landscape — the collapse
     * button ended up below the bottom edge and could not be reached at all.
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

    /** The buttons, scrollable when they do not fit. */
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val scroller = CappedScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    private val expanded = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground(dp(14f))
        setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
    }

    private val grip = icon(R.drawable.ic_grip)
    private val primary = icon(R.drawable.ic_play)
    private val secondary = icon(R.drawable.ic_record)
    private val insertPause = icon(R.drawable.ic_pause_add)
    private val undo = icon(R.drawable.ic_undo)
    private val edit = icon(R.drawable.ic_edit)
    private val addTap = icon(R.drawable.ic_add)
    private val deleteStep = icon(R.drawable.ic_remove)
    private val newClip = icon(R.drawable.ic_new_clip)
    private val save = icon(R.drawable.ic_save)
    private val load = icon(R.drawable.ic_folder_open)
    private val eye = icon(R.drawable.ic_eye)
    private val quickSettings = icon(R.drawable.ic_tune)
    private val dismiss = icon(R.drawable.ic_close)
    private val collapse = icon(R.drawable.ic_collapse)

    /** Everything inside the scroller, in display order. The grip sits outside it. */
    private val scrollingButtons = listOf(
        primary, secondary, insertPause, undo, edit, addTap, deleteStep, newClip, save, load, eye,
        quickSettings, dismiss, collapse,
    )

    private val allButtons = listOf(grip) + scrollingButtons

    private val ball = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
    }

    var ballIntent: BallIntent = BallIntent.EXPAND

    init {
        scrollingButtons.forEach { column.addView(it) }
        scroller.addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // The grip is deliberately outside the scroller. Inside it, ScrollView would intercept the
        // vertical drag and the toolbar could no longer be moved.
        expanded.addView(grip)
        expanded.addView(scroller)

        addView(expanded, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(ball, LayoutParams(dp(BALL_DP), dp(BALL_DP)))

        primary.setOnClickListener { actions.onPrimary() }
        secondary.setOnClickListener { actions.onSecondary() }
        insertPause.setOnClickListener { actions.onInsertPausePoint() }
        undo.setOnClickListener { actions.onUndo() }
        edit.setOnClickListener { actions.onToggleEdit() }
        addTap.setOnClickListener { actions.onAddTap() }
        deleteStep.setOnClickListener { actions.onDeleteSelected() }
        eye.setOnClickListener { actions.onCycleDensity() }
        quickSettings.setOnClickListener { actions.onToggleQuickSettings() }
        dismiss.setOnClickListener { actions.onDismiss() }
        collapse.setOnClickListener { actions.onCollapse() }

        // Each of these opens one small screen that does one thing. Naming needs a text field, and
        // a text field needs input focus, which no overlay here may take.
        save.setOnClickListener { actions.onSave() }
        load.setOnClickListener { actions.onLoad() }
        newClip.setOnClickListener { actions.onNewClip() }

        attachDrag(grip, onTap = null)
        attachDrag(ball) {
            when (ballIntent) {
                BallIntent.EXPAND -> actions.onExpand()
                BallIntent.RESUME_PLAYBACK -> actions.onPrimary()
                BallIntent.RESUME_RECORDING -> actions.onSecondary()
            }
        }
        // No long-press on the ball: an OnTouchListener that consumes the event stops the view from
        // generating long clicks at all. The ball never needs to expand while paused anyway, because
        // the toolbar restores itself the moment playback resumes.

        setContentDescriptions()
    }

    /**
     * @param workspaceSize step count, used to disable actions that need a non-empty workspace.
     * @param editing whether the canvas is in editing mode, which swaps the button set.
     * @param hasSelection whether a step is selected, which is what delete acts on.
     */
    fun render(
        mode: Mode,
        form: ToolbarForm,
        workspaceSize: Int,
        density: MarkerDensity,
        editing: Boolean,
        hasSelection: Boolean,
        quickSettingsOpen: Boolean,
    ) {
        expanded.visibility = if (form == ToolbarForm.EXPANDED) VISIBLE else GONE
        ball.visibility = if (form == ToolbarForm.BALL) VISIBLE else GONE

        ball.background = ballBackground(stateColor(mode), dp(BALL_DP / 2))
        ball.setImageResource(
            when (ballIntent) {
                BallIntent.RESUME_RECORDING -> R.drawable.ic_record
                BallIntent.RESUME_PLAYBACK -> R.drawable.ic_play
                BallIntent.EXPAND -> R.drawable.ic_grip
            }
        )
        ball.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)

        val hasSteps = workspaceSize > 0
        val recording = mode == Mode.RECORDING
        val replaying = mode == Mode.PLAYING || mode == Mode.PAUSED || mode == Mode.COUNTDOWN

        // Editing shows a different, shorter set rather than a dozen greyed-out buttons. Playing and
        // recording make no sense alongside dragging markers, and add/delete make no sense outside
        // it, so nothing here is ever merely disabled when it could just be absent.
        primary.visibility = if (editing) GONE else VISIBLE
        secondary.visibility = if (editing) GONE else VISIBLE
        insertPause.visibility = if (editing) GONE else VISIBLE
        undo.visibility = if (editing) GONE else VISIBLE
        dismiss.visibility = if (editing) GONE else VISIBLE
        addTap.visibility = if (editing) VISIBLE else GONE
        deleteStep.visibility = if (editing) VISIBLE else GONE
        newClip.visibility = if (editing) GONE else VISIBLE
        load.visibility = if (editing) GONE else VISIBLE

        primary.setImageResource(if (mode == Mode.PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        setActionEnabled(primary, hasSteps && !recording)

        secondary.setImageResource(if (recording || replaying) R.drawable.ic_stop else R.drawable.ic_record)
        // Recording tints red so it is obvious at a glance that touches are being intercepted.
        secondary.imageTintList = android.content.res.ColorStateList.valueOf(
            if (mode == Mode.IDLE) ContextCompat.getColor(context, R.color.state_recording) else iconIdle
        )
        secondary.contentDescription = context.getString(
            when {
                recording -> R.string.action_record_stop
                replaying -> R.string.action_stop
                workspaceSize > 0 -> R.string.action_record_resume
                else -> R.string.action_record
            }
        )
        primary.contentDescription = context.getString(
            when (mode) {
                Mode.PLAYING -> R.string.action_pause
                Mode.PAUSED -> R.string.action_resume
                else -> R.string.action_play
            }
        )
        setActionEnabled(secondary, true)

        edit.imageTintList = android.content.res.ColorStateList.valueOf(
            if (editing) ContextCompat.getColor(context, R.color.marker_highlight) else iconIdle
        )
        setActionEnabled(edit, hasSteps && !recording && !replaying)

        quickSettings.imageTintList = android.content.res.ColorStateList.valueOf(
            if (quickSettingsOpen) ContextCompat.getColor(context, R.color.marker_highlight) else iconIdle
        )

        setActionEnabled(insertPause, !replaying)
        setActionEnabled(undo, hasSteps && !replaying)
        setActionEnabled(deleteStep, hasSelection)
        setActionEnabled(save, hasSteps && !replaying)
        setActionEnabled(load, !recording && !replaying)
        setActionEnabled(newClip, hasSteps && !recording && !replaying)
        setActionEnabled(dismiss, !recording && !replaying)

        // The eye dims progressively as fewer markers are shown, so the current setting is readable
        // without a label.
        eye.alpha = when (density) {
            MarkerDensity.ALL -> 1f
            MarkerDensity.RECENT -> 0.7f
            MarkerDensity.HIDDEN -> 0.35f
        }
    }

    /**
     * @param availableHeightPx how tall the toolbar may be before its buttons start scrolling.
     *   Landscape does not fit the whole column, and an unreachable collapse button is worse than
     *   having to scroll for it.
     */
    fun setAvailableHeight(availableHeightPx: Int) {
        val gripHeight = grip.layoutParams?.height?.takeIf { it > 0 } ?: dp(44f)
        val forScroller = availableHeightPx - gripHeight - dp(12f)
        if (scroller.maxHeightPx == forScroller) return
        scroller.maxHeightPx = forScroller.coerceAtLeast(dp(88f))
        scroller.requestLayout()
    }

    private var appliedScale = Float.NaN
    private var appliedOpacity = Float.NaN

    /**
     * Only does work when the appearance actually changed.
     *
     * It is called from every overlay refresh, and it used to rebuild fifteen sets of layout params
     * and force a relayout each time. A relayout leads to a window update, and a window update while
     * an injected gesture is in flight cancels that gesture.
     */
    fun applyAppearance(scale: Float, opacity: Float) {
        val clampedScale = scale.coerceIn(0.7f, 1.5f)
        val clampedOpacity = opacity.coerceIn(0.3f, 1f)
        if (clampedScale == appliedScale && clampedOpacity == appliedOpacity) return
        appliedScale = clampedScale
        appliedOpacity = clampedOpacity

        alpha = clampedOpacity
        val size = (dp(44f) * clampedScale).toInt()
        allButtons.forEach { it.layoutParams = LinearLayout.LayoutParams(size, size) }
        val ballSize = (dp(BALL_DP) * clampedScale).toInt()
        ball.layoutParams = LayoutParams(ballSize, ballSize)
        requestLayout()
    }

    private fun setActionEnabled(view: ImageView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.3f
    }

    private fun stateColor(mode: Mode): Int = ContextCompat.getColor(
        context,
        when (mode) {
            Mode.IDLE -> R.color.state_idle
            Mode.RECORDING -> R.color.state_recording
            Mode.COUNTDOWN, Mode.PLAYING -> R.color.state_playing
            Mode.PAUSED -> R.color.state_paused
        }
    )

    private fun icon(resId: Int): ImageView = ImageView(context).apply {
        setImageResource(resId)
        imageTintList = android.content.res.ColorStateList.valueOf(iconIdle)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
        isClickable = true
    }

    private fun panelBackground(radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(ContextCompat.getColor(context, R.color.overlay_panel))
    }

    private fun ballBackground(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun attachDrag(view: View, onTap: (() -> Unit)?) {
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

                MotionEvent.ACTION_UP -> {
                    // Decided on net displacement at release, not on whether the slop was ever
                    // crossed. A finger that wobbles past the threshold and comes back is a tap;
                    // latching a dragging flag made those taps vanish silently.
                    val moved = hypot(totalX, totalY) > touchSlop
                    if (moved || onTap == null) actions.onDragEnd() else onTap.invoke()
                    dragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) actions.onDragEnd()
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun setContentDescriptions() {
        primary.contentDescription = context.getString(R.string.action_play)
        secondary.contentDescription = context.getString(R.string.action_record)
        insertPause.contentDescription = context.getString(R.string.action_insert_pause)
        undo.contentDescription = context.getString(R.string.action_undo)
        edit.contentDescription = context.getString(R.string.action_edit_mode)
        addTap.contentDescription = context.getString(R.string.action_add_tap)
        deleteStep.contentDescription = context.getString(R.string.action_delete)
        save.contentDescription = context.getString(R.string.action_save)
        load.contentDescription = context.getString(R.string.action_load)
        newClip.contentDescription = context.getString(R.string.action_new_clip)
        eye.contentDescription = context.getString(R.string.action_density)
        quickSettings.contentDescription = context.getString(R.string.action_quick_settings)
        dismiss.contentDescription = context.getString(R.string.action_dismiss)
        collapse.contentDescription = context.getString(R.string.action_collapse)
    }

    private fun dp(value: Float) = (value * displayDensity).toInt()

    private companion object {
        /** Collapsed ball diameter. Comfortably above the 48dp minimum touch target. */
        const val BALL_DP = 56f
    }
}
