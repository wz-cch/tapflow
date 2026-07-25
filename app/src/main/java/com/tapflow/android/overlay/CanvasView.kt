package com.tapflow.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.tapflow.android.R
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.data.Pt
import com.tapflow.android.data.Stroke
import kotlin.math.hypot

/** What the canvas is currently for. Editing (dragging markers) arrives in M2. */
enum class CanvasMode { READ_ONLY, RECORDING }

/**
 * Full-screen window that paints the step markers and, while recording, captures touches.
 *
 * One view covers both jobs because they share all the drawing. The difference is purely whether
 * the window has FLAG_NOT_TOUCHABLE: in [CanvasMode.READ_ONLY] every touch passes straight through
 * to the app underneath, which is also what makes a pause point usable.
 */
@SuppressLint("ViewConstructor")
class CanvasView(context: Context) : View(context) {

    /** Called with a finished gesture: the strokes, and the down/up times on the uptime clock. */
    var onGesture: ((strokes: List<Stroke>, downUptime: Long, upUptime: Long) -> Unit)? = null

    var mode: CanvasMode = CanvasMode.READ_ONLY
        set(value) {
            if (field == value) return
            field = value
            if (value == CanvasMode.READ_ONLY) discardInProgress()
            invalidate()
        }

    /** True while a captured gesture is being replayed downwards; new touches are ignored. */
    var replaying: Boolean = false

    var markers: List<Marker> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var density: MarkerDensity = MarkerDensity.RECENT
        set(value) {
            field = value
            invalidate()
        }

    /** Pre-formatted step list lines, oldest first. */
    var stepLines: List<String> = emptyList()
        set(value) {
            val grew = value.size > field.size
            field = value
            if (grew) highlightUntil = SystemClock.uptimeMillis() + HIGHLIGHT_MS
            invalidate()
        }

    /** Step number currently being replayed, drawn larger. */
    var highlightNumber: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Black layer opacity for idling with the screen on but unreadable. 0 disables it. */
    var dimAlpha: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Screen areas covered by the toolbar and transport windows.
     *
     * Touches there go to those windows, so they can neither be recorded nor replayed. Hatching
     * them is the whole mitigation — there is no automatic dodging, by design.
     */
    var blockedAreas: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val painter = MarkerPainter(context)
    private val displayDensity = context.resources.displayMetrics.density

    private val minSampleDistance = dp(4f)
    private val cornerRadius = dp(10f)

    private val scrimColor = ContextCompat.getColor(context, R.color.record_scrim)
    private val hatchColor = ContextCompat.getColor(context, R.color.warn_hatch)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_panel)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_text)
        textSize = dp(12f)
    }
    private val lineDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_text_dim)
        textSize = dp(12f)
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.state_recording)
        alpha = 160
    }
    private val dimPaint = Paint().apply { color = Color.BLACK }

    private val livePath = Path()
    private val panelRect = RectF()
    private val origin = IntArray(2)

    private var highlightUntil = 0L

    // --- Gesture capture -----------------------------------------------------

    private class StrokeBuilder(val startOffset: Long) {
        val points = ArrayList<Pt>()
    }

    /** Insertion-ordered so the first finger down ends up as the first stroke. */
    private val builders = LinkedHashMap<Int, StrokeBuilder>()
    private var gestureDownUptime = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != CanvasMode.RECORDING) return false

        // While a captured gesture is being pushed down to the app below, the window is already
        // non-touchable; this guard only catches events the system had already queued.
        if (replaying) return true

        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                builders.clear()
                gestureDownUptime = now
                beginStroke(event, event.actionIndex, now)
            }

            MotionEvent.ACTION_POINTER_DOWN -> beginStroke(event, event.actionIndex, now)

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) sample(event, index, now, force = false)
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> sample(event, event.actionIndex, now, force = true)

            MotionEvent.ACTION_UP -> {
                sample(event, event.actionIndex, now, force = true)
                finishGesture(now)
            }

            MotionEvent.ACTION_CANCEL -> discardInProgress()
        }
        return true
    }

    private fun beginStroke(event: MotionEvent, pointerIndex: Int, now: Long) {
        val id = event.getPointerId(pointerIndex)
        builders[id] = StrokeBuilder(startOffset = (now - gestureDownUptime).coerceAtLeast(0))
        sample(event, pointerIndex, now, force = true)
    }

    private fun sample(event: MotionEvent, pointerIndex: Int, now: Long, force: Boolean) {
        val id = event.getPointerId(pointerIndex)
        val builder = builders[id] ?: return

        getLocationOnScreen(origin)
        val x = event.getX(pointerIndex) + origin[0]
        val y = event.getY(pointerIndex) + origin[1]

        val last = builder.points.lastOrNull()
        if (!force && last != null && hypot(x - last.x, y - last.y) < minSampleDistance) return

        val t = (now - gestureDownUptime - builder.startOffset).coerceAtLeast(0)
        builder.points.add(Pt(x, y, t))
    }

    private fun finishGesture(upUptime: Long) {
        val strokes = builders.values
            .filter { it.points.isNotEmpty() }
            .map { Stroke(points = it.points.toList(), startOffset = it.startOffset) }
        val downUptime = gestureDownUptime
        builders.clear()
        livePath.reset()
        invalidate()
        if (strokes.isNotEmpty()) onGesture?.invoke(strokes, downUptime, upUptime)
    }

    private fun discardInProgress() {
        builders.clear()
        livePath.reset()
        invalidate()
    }

    // --- Drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (dimAlpha > 0f && mode == CanvasMode.READ_ONLY) {
            dimPaint.alpha = (dimAlpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }

        if (mode == CanvasMode.RECORDING) {
            canvas.drawColor(scrimColor)
            blockedAreas.forEach { painter.drawBlockedArea(canvas, it, hatchColor) }
        }

        if (density != MarkerDensity.HIDDEN || markers.isNotEmpty()) {
            painter.draw(canvas, markers.forDensity(density), highlightNumber, scale = 1f)
        }

        drawInProgressStrokes(canvas)

        if (density != MarkerDensity.HIDDEN && stepLines.isNotEmpty()) drawStepList(canvas)
    }

    private fun drawInProgressStrokes(canvas: Canvas) {
        builders.values.forEach { builder ->
            if (builder.points.size < 2) return@forEach
            livePath.reset()
            livePath.moveTo(builder.points[0].x, builder.points[0].y)
            for (index in 1 until builder.points.size) {
                livePath.lineTo(builder.points[index].x, builder.points[index].y)
            }
            canvas.drawPath(livePath, livePaint)
        }
    }

    /**
     * Step list along the bottom edge.
     *
     * It is painted onto this canvas rather than being its own window, so it never intercepts a
     * touch — it obscures the view but blocks nothing. Only the tail that fits is shown; scrolling
     * would fight with gesture capture, so it is left to the main app.
     */
    private fun drawStepList(canvas: Canvas) {
        val lineHeight = dp(18f)
        val padding = dp(10f)
        val maxHeight = height * 0.3f
        val visibleCount = ((maxHeight - padding * 2) / lineHeight).toInt().coerceAtLeast(1)
        val lines = stepLines.takeLast(visibleCount)

        val panelHeight = lines.size * lineHeight + padding * 2
        val margin = dp(12f)
        panelRect.set(
            margin,
            height - panelHeight - margin - dp(24f),
            width - margin,
            height - margin - dp(24f),
        )
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, panelPaint)

        val highlightActive = SystemClock.uptimeMillis() < highlightUntil
        var y = panelRect.top + padding + lineHeight * 0.75f
        lines.forEachIndexed { index, line ->
            val isLast = index == lines.lastIndex
            val paint = if (isLast && highlightActive) linePaint else lineDimPaint
            canvas.drawText(line, panelRect.left + padding, y, paint)
            y += lineHeight
        }

        // Fade the highlight out without a running animator.
        if (highlightActive) postInvalidateDelayed(HIGHLIGHT_MS)
    }

    private fun dp(value: Float) = value * displayDensity

    private companion object {
        const val HIGHLIGHT_MS = 800L
    }
}
