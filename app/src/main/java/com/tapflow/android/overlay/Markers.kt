package com.tapflow.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.tapflow.android.R
import com.tapflow.android.data.GestureKind
import com.tapflow.android.data.GestureStep
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.data.Step
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One on-screen marker.
 *
 * [number] is the 1-based position in the whole step list, including pause points, so the numbers
 * painted on screen line up with the step list even though pause points draw nothing. [stepId] is
 * what hit-testing reports back, so editing never has to reason about positions in a list.
 */
data class Marker(
    val stepId: String,
    val number: Int,
    val kind: GestureKind,
    val anchorX: Float,
    val anchorY: Float,
    val paths: List<List<Pair<Float, Float>>>,
) {
    /** End of the first stroke — the arrow tip, and the grab point for changing a swipe. */
    val endX: Float get() = paths.firstOrNull()?.lastOrNull()?.first ?: anchorX
    val endY: Float get() = paths.firstOrNull()?.lastOrNull()?.second ?: anchorY

    /** Only a single-stroke swipe has a meaningful end to drag. */
    val hasEndHandle: Boolean get() = kind == GestureKind.SWIPE && paths.size == 1
}

/** Builds the markers for a step list. Pause points and waits have no coordinates, so they are skipped. */
fun buildMarkers(steps: List<Step>): List<Marker> = steps.mapIndexedNotNull { index, step ->
    if (step !is GestureStep) return@mapIndexedNotNull null
    Marker(
        stepId = step.id,
        number = index + 1,
        kind = step.kind,
        anchorX = step.anchor.x,
        anchorY = step.anchor.y,
        paths = step.strokes.map { stroke -> stroke.points.map { it.x to it.y } },
    )
}

/**
 * Keeps only what the current density allows.
 *
 * Beyond [HARD_LIMIT] markers onDraw starts to cost real time on every frame, so the tail is
 * capped even in "show everything" mode. The step list is not capped.
 */
fun List<Marker>.forDensity(density: MarkerDensity): List<Marker> =
    takeLast(minOf(density.keepCount, HARD_LIMIT))

private const val HARD_LIMIT = 200

/** Paints markers onto a canvas. Holds its Paints so onDraw allocates nothing. */
class MarkerPainter(context: Context) {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float) = value * density

    private val colorTap = color(context, R.color.marker_tap)
    private val colorLongPress = color(context, R.color.marker_long_press)
    private val colorSwipe = color(context, R.color.marker_swipe)
    private val colorMulti = color(context, R.color.marker_multi)
    private val colorHighlight = color(context, R.color.marker_highlight)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = color(context, R.color.marker_path)
    }
    private val link = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = color(context, R.color.marker_path)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(6f)), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(context, R.color.marker_label)
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        isFakeBoldText = true
    }

    private val reusablePath = Path()

    /**
     * @param highlightNumber the step currently being replayed, drawn larger and in the accent
     *   colour so it is obvious where the automation has got to.
     * @param selectedStepId the step being edited, given a corner frame and grab handles.
     */
    fun draw(
        canvas: Canvas,
        markers: List<Marker>,
        highlightNumber: Int?,
        selectedStepId: String?,
        scale: Float,
    ) {
        drawLinks(canvas, markers)
        markers.forEach { marker ->
            val highlighted = marker.number == highlightNumber
            drawMarker(canvas, marker, highlighted, scale)
            if (marker.stepId == selectedStepId) drawSelection(canvas, marker, scale)
        }
    }

    /** Corner frame plus a ring on each grab point, so what can be dragged is visible. */
    private fun drawSelection(canvas: Canvas, marker: Marker, scale: Float) {
        val reach = dp(30f) * scale
        stroke.color = colorHighlight
        stroke.strokeWidth = dp(2f) * scale

        val arm = dp(9f) * scale
        val left = marker.anchorX - reach
        val right = marker.anchorX + reach
        val top = marker.anchorY - reach
        val bottom = marker.anchorY + reach

        canvas.drawLine(left, top, left + arm, top, stroke)
        canvas.drawLine(left, top, left, top + arm, stroke)
        canvas.drawLine(right, top, right - arm, top, stroke)
        canvas.drawLine(right, top, right, top + arm, stroke)
        canvas.drawLine(left, bottom, left + arm, bottom, stroke)
        canvas.drawLine(left, bottom, left, bottom - arm, stroke)
        canvas.drawLine(right, bottom, right - arm, bottom, stroke)
        canvas.drawLine(right, bottom, right, bottom - arm, stroke)

        if (marker.hasEndHandle) {
            canvas.drawCircle(marker.endX, marker.endY, dp(13f) * scale, stroke)
        }
    }

    private fun drawLinks(canvas: Canvas, markers: List<Marker>) {
        if (markers.size < 2) return
        reusablePath.reset()
        reusablePath.moveTo(markers[0].anchorX, markers[0].anchorY)
        for (index in 1 until markers.size) {
            reusablePath.lineTo(markers[index].anchorX, markers[index].anchorY)
        }
        canvas.drawPath(reusablePath, link)
    }

    private fun drawMarker(canvas: Canvas, marker: Marker, highlighted: Boolean, scale: Float) {
        val tint = if (highlighted) colorHighlight else when (marker.kind) {
            GestureKind.TAP -> colorTap
            GestureKind.LONG_PRESS -> colorLongPress
            GestureKind.SWIPE -> colorSwipe
            GestureKind.MULTI_TOUCH -> colorMulti
        }
        val radius = dp(if (highlighted) 13f else 10f) * scale

        if (marker.kind == GestureKind.SWIPE || marker.kind == GestureKind.MULTI_TOUCH) {
            marker.paths.forEach { drawTrail(canvas, it) }
        }

        // Crosshair, so the exact pixel is visible even under the numbered disc.
        stroke.color = tint
        stroke.strokeWidth = dp(1.5f) * scale
        val reach = radius * 2f
        canvas.drawLine(marker.anchorX - reach, marker.anchorY, marker.anchorX - radius, marker.anchorY, stroke)
        canvas.drawLine(marker.anchorX + radius, marker.anchorY, marker.anchorX + reach, marker.anchorY, stroke)
        canvas.drawLine(marker.anchorX, marker.anchorY - reach, marker.anchorX, marker.anchorY - radius, stroke)
        canvas.drawLine(marker.anchorX, marker.anchorY + radius, marker.anchorX, marker.anchorY + reach, stroke)

        if (marker.kind == GestureKind.LONG_PRESS) {
            stroke.strokeWidth = dp(2.5f) * scale
            canvas.drawCircle(marker.anchorX, marker.anchorY, radius + dp(5f) * scale, stroke)
        }

        fill.color = tint
        canvas.drawCircle(marker.anchorX, marker.anchorY, radius, fill)

        label.textSize = dp(if (highlighted) 13f else 11f) * scale
        val baseline = marker.anchorY - (label.descent() + label.ascent()) / 2f
        canvas.drawText(marker.number.toString(), marker.anchorX, baseline, label)
    }

    private fun drawTrail(canvas: Canvas, points: List<Pair<Float, Float>>) {
        if (points.size < 2) return
        reusablePath.reset()
        reusablePath.moveTo(points[0].first, points[0].second)
        for (index in 1 until points.size) {
            reusablePath.lineTo(points[index].first, points[index].second)
        }
        canvas.drawPath(reusablePath, trail)
        drawArrowHead(canvas, points)
    }

    private fun drawArrowHead(canvas: Canvas, points: List<Pair<Float, Float>>) {
        val end = points.last()
        // Walk back until the points are far enough apart to give a stable direction; the last few
        // samples of a swipe are often almost identical.
        var reference = points.first()
        for (index in points.indices.reversed()) {
            val candidate = points[index]
            if (hypot(end.first - candidate.first, end.second - candidate.second) > dp(8f)) {
                reference = candidate
                break
            }
        }
        val angle = atan2(end.second - reference.second, end.first - reference.first)
        val size = dp(11f)
        val spread = 0.5f

        reusablePath.reset()
        reusablePath.moveTo(end.first, end.second)
        reusablePath.lineTo(
            end.first - size * cos(angle - spread),
            end.second - size * sin(angle - spread),
        )
        reusablePath.lineTo(
            end.first - size * cos(angle + spread),
            end.second - size * sin(angle + spread),
        )
        reusablePath.close()
        fill.color = colorSwipe
        canvas.drawPath(reusablePath, fill)
    }

    /** Diagonal hatching over an area the toolbar covers, which cannot be recorded or replayed. */
    fun drawBlockedArea(canvas: Canvas, rect: RectF, hatchColor: Int) {
        stroke.color = hatchColor
        stroke.strokeWidth = dp(2f)
        val step = dp(12f)
        canvas.save()
        canvas.clipRect(rect)
        var x = rect.left - rect.height()
        while (x < rect.right) {
            canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, stroke)
            x += step
        }
        canvas.restore()
    }

    private fun color(context: Context, id: Int): Int =
        runCatching { ContextCompat.getColor(context, id) }.getOrDefault(Color.WHITE)
}
