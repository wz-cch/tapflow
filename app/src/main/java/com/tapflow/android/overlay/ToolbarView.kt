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
        fun onSaveAsNew()
        fun onCycleDensity()
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
    private val save = icon(R.drawable.ic_save)
    private val eye = icon(R.drawable.ic_eye)
    private val dismiss = icon(R.drawable.ic_close)
    private val collapse = icon(R.drawable.ic_collapse)

    private val allButtons = listOf(
        grip, primary, secondary, insertPause, undo, edit, addTap, deleteStep, save, eye, dismiss, collapse,
    )

    private val ball = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
    }

    var ballIntent: BallIntent = BallIntent.EXPAND

    init {
        allButtons.forEach { expanded.addView(it) }

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
        dismiss.setOnClickListener { actions.onDismiss() }
        collapse.setOnClickListener { actions.onCollapse() }

        // Tap saves over the source clip; long press always creates a new one. That avoids putting
        // a text dialog on an overlay, which would need input focus and fight with the IME.
        save.setOnClickListener { actions.onSave() }
        save.setOnLongClickListener { actions.onSaveAsNew(); true }

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

        primary.setImageResource(if (mode == Mode.PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        setActionEnabled(primary, hasSteps && !recording)

        secondary.setImageResource(if (recording || replaying) R.drawable.ic_stop else R.drawable.ic_record)
        // Recording tints red so it is obvious at a glance that touches are being intercepted.
        secondary.imageTintList = android.content.res.ColorStateList.valueOf(
            if (mode == Mode.IDLE) ContextCompat.getColor(context, R.color.state_recording) else iconIdle
        )
        setActionEnabled(secondary, true)

        edit.imageTintList = android.content.res.ColorStateList.valueOf(
            if (editing) ContextCompat.getColor(context, R.color.marker_highlight) else iconIdle
        )
        setActionEnabled(edit, hasSteps && !recording && !replaying)

        setActionEnabled(insertPause, !replaying)
        setActionEnabled(undo, hasSteps && !replaying)
        setActionEnabled(deleteStep, hasSelection)
        setActionEnabled(save, hasSteps && !replaying)
        setActionEnabled(dismiss, !recording && !replaying)

        // The eye dims progressively as fewer markers are shown, so the current setting is readable
        // without a label.
        eye.alpha = when (density) {
            MarkerDensity.ALL -> 1f
            MarkerDensity.RECENT -> 0.7f
            MarkerDensity.HIDDEN -> 0.35f
        }
    }

    fun applyAppearance(scale: Float, opacity: Float) {
        alpha = opacity.coerceIn(0.3f, 1f)
        val size = (dp(44f) * scale.coerceIn(0.7f, 1.5f)).toInt()
        allButtons.forEach { it.layoutParams = LinearLayout.LayoutParams(size, size) }
        val ballSize = (dp(BALL_DP) * scale.coerceIn(0.7f, 1.5f)).toInt()
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
        eye.contentDescription = context.getString(R.string.action_density)
        dismiss.contentDescription = context.getString(R.string.action_dismiss)
        collapse.contentDescription = context.getString(R.string.action_collapse)
    }

    private fun dp(value: Float) = (value * displayDensity).toInt()

    private companion object {
        /** Collapsed ball diameter. Comfortably above the 48dp minimum touch target. */
        const val BALL_DP = 56f
    }
}
