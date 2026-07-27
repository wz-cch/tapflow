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
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.tapflow.android.R
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.data.Pt
import com.tapflow.android.data.Stroke
import kotlin.math.hypot

/** What the canvas is currently for. */
enum class CanvasMode { READ_ONLY, RECORDING, EDIT }

/** Which part of a marker was grabbed. */
enum class Handle {
    /** The numbered badge: moves the whole gesture. */
    BODY,

    /** A swipe's arrow tip: changes its direction and length, keeping the curve shape. */
    END,
}

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

    /** Editing: a marker was tapped, or empty space was tapped (null). */
    var onSelect: ((stepId: String?) -> Unit)? = null

    /** Editing: a handle is being dragged to an absolute screen position. */
    var onDragStep: ((stepId: String, handle: Handle, x: Float, y: Float) -> Unit)? = null

    /** Editing: the drag finished, so transient edits can be committed to the draft. */
    var onDragEnd: (() -> Unit)? = null

    /** Editing: a position was picked for the step whose coordinate is being re-specified. */
    var onPickCoordinate: ((x: Float, y: Float) -> Unit)? = null

    /**
     * A touch arrived while a gesture was being replayed.
     *
     * That should be impossible: the window is set FLAG_NOT_TOUCHABLE first. If it happens, the flag
     * had not taken effect yet and this canvas has just eaten the gesture it injected — which is
     * indistinguishable from the replay silently doing nothing. Worth reporting rather than guessing.
     */
    var onReplayEcho: (() -> Unit)? = null

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

    /** Step being edited, drawn with a corner frame and grab handles. */
    var selectedStepId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** When true the next tap reports a position instead of selecting anything. */
    var pickingCoordinate: Boolean = false
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
    private val editSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

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
        if (mode == CanvasMode.EDIT) return handleEditTouch(event)
        if (mode != CanvasMode.RECORDING) return false

        // The window is already non-touchable by this point, so reaching here means the flag had not
        // landed and the injected gesture is about to be swallowed. Report it instead of hiding it.
        if (replaying) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onReplayEcho?.invoke()
            return true
        }

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

    // --- Editing --------------------------------------------------------------

    private class Grab(val stepId: String, val handle: Handle, val offsetX: Float, val offsetY: Float)

    private var grab: Grab? = null
    private var editDownX = 0f
    private var editDownY = 0f

    private fun handleEditTouch(event: MotionEvent): Boolean {
        getLocationOnScreen(origin)
        val x = event.x + origin[0]
        val y = event.y + origin[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editDownX = x
                editDownY = y
                grab = if (pickingCoordinate) null else hitTest(x, y)?.let { (marker, handle) ->
                    val anchorX = if (handle == Handle.END) marker.endX else marker.anchorX
                    val anchorY = if (handle == Handle.END) marker.endY else marker.anchorY
                    Grab(marker.stepId, handle, x - anchorX, y - anchorY)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val target = grab ?: return true
                if (!movedPastSlop(x, y)) return true
                onDragStep?.invoke(target.stepId, target.handle, x - target.offsetX, y - target.offsetY)
            }

            MotionEvent.ACTION_UP -> {
                val moved = movedPastSlop(x, y)
                when {
                    pickingCoordinate -> onPickCoordinate?.invoke(x, y)
                    // Same rule as the toolbar: decided from net displacement at release, so a tap
                    // that wobbles is still a tap.
                    !moved -> onSelect?.invoke(hitTest(x, y)?.first?.stepId)
                    grab != null -> onDragEnd?.invoke()
                }
                grab = null
            }

            MotionEvent.ACTION_CANCEL -> {
                if (grab != null) onDragEnd?.invoke()
                grab = null
            }
        }
        return true
    }

    private fun movedPastSlop(x: Float, y: Float) =
        hypot(x - editDownX, y - editDownY) > editSlop

    /**
     * Finds the marker under a point.
     *
     * Searched newest first because later markers are painted on top, so the one the user can see is
     * the one that gets picked. End handles win over bodies: a short swipe can have both within the
     * same radius, and the tip is the more specific intent.
     */
    private fun hitTest(x: Float, y: Float): Pair<Marker, Handle>? {
        val radius = dp(26f)
        for (marker in markers.forDensity(density).asReversed()) {
            if (marker.hasEndHandle && hypot(x - marker.endX, y - marker.endY) <= radius) {
                return marker to Handle.END
            }
            if (hypot(x - marker.anchorX, y - marker.anchorY) <= radius) {
                return marker to Handle.BODY
            }
        }
        return null
    }

    // --- Drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (dimAlpha > 0f && mode == CanvasMode.READ_ONLY) {
            dimPaint.alpha = (dimAlpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }

        if (mode == CanvasMode.RECORDING || mode == CanvasMode.EDIT) {
            if (mode == CanvasMode.RECORDING) canvas.drawColor(scrimColor)
            blockedAreas.forEach { painter.drawBlockedArea(canvas, it, hatchColor) }
            drawBlockedLabel(canvas)
        }

        // HIDDEN still shows the newest marker, it just stops showing the trail of older ones —
        // forDensity handles that, so there is nothing to branch on here.
        painter.draw(canvas, markers.forDensity(density), highlightNumber, selectedStepId, scale = 1f)

        drawInProgressStrokes(canvas)

        // The step list would sit under the parameter card while editing, and the markers are the
        // thing being looked at anyway.
        val showList = mode != CanvasMode.EDIT && density != MarkerDensity.HIDDEN
        if (showList && stepLines.isNotEmpty()) drawStepList(canvas)
    }

    /**
     * Names the hatching once, beside the largest blocked area.
     *
     * The stripes say "not here" but not why, and the reason — a floating window is on top, and
     * Android gives a touch only to the topmost window — is not guessable.
     */
    private fun drawBlockedLabel(canvas: Canvas) {
        val widest = blockedAreas.maxByOrNull { it.width() * it.height() } ?: return
        val text = context.getString(R.string.warn_toolbar_area)
        val textWidth = lineDimPaint.measureText(text)
        val x = (widest.centerX() - textWidth / 2f)
            .coerceIn(dp(8f), (width - dp(8f) - textWidth).coerceAtLeast(dp(8f)))
        // Below the area normally, above it when the area runs to the bottom of the screen — the
        // toolbar can be dragged anywhere, so neither side can be assumed to have room.
        val below = widest.bottom + dp(16f)
        val y = if (below <= height - dp(4f)) below else widest.top - dp(8f)
        canvas.drawText(text, x, y.coerceAtLeast(dp(12f)), lineDimPaint)
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
