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
import com.tapflow.android.data.GlobalStep
import com.tapflow.android.data.MarkerDensity
import com.tapflow.android.data.PauseStep
import com.tapflow.android.data.Step
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What a marker stands for.
 *
 * An overlay-layer type rather than [GestureKind] because two of these are not gestures. Keeping it
 * here also keeps the drawing code from having to know about the data layer's sealed step types.
 */
enum class MarkerKind {
    TAP,
    LONG_PRESS,
    SWIPE,
    MULTI_TOUCH,
    PAUSE,
    WAIT;

    /**
     * Whether the step behind this marker carries no coordinates of its own.
     *
     * A pause point stops the run; it does not happen anywhere. Its position on screen is inferred
     * from the steps around it purely so there is something to point at and delete.
     */
    val isDerived: Boolean get() = this == PAUSE || this == WAIT
}

private fun GestureKind.toMarkerKind(): MarkerKind = when (this) {
    GestureKind.TAP -> MarkerKind.TAP
    GestureKind.LONG_PRESS -> MarkerKind.LONG_PRESS
    GestureKind.SWIPE -> MarkerKind.SWIPE
    GestureKind.MULTI_TOUCH -> MarkerKind.MULTI_TOUCH
}

/**
 * One on-screen marker.
 *
 * [number] is the 1-based position in the whole step list, so the numbers painted on screen line up
 * with the step list. [stepId] is what hit-testing reports back, so editing never has to reason
 * about positions in a list.
 */
data class Marker(
    val stepId: String,
    val number: Int,
    val kind: MarkerKind,
    val anchorX: Float,
    val anchorY: Float,
    val paths: List<List<Pair<Float, Float>>> = emptyList(),
) {
    /** End of the first stroke — the arrow tip, and the grab point for changing a swipe. */
    val endX: Float get() = paths.firstOrNull()?.lastOrNull()?.first ?: anchorX
    val endY: Float get() = paths.firstOrNull()?.lastOrNull()?.second ?: anchorY

    /** Only a single-stroke swipe has a meaningful end to drag. */
    val hasEndHandle: Boolean get() = kind == MarkerKind.SWIPE && paths.size == 1

    /**
     * Whether dragging this marker would mean anything.
     *
     * A derived marker's anchor is computed from its neighbours, so moving it would change no stored
     * value and the next rebuild would snap it straight back. It still hit-tests, because selecting
     * it is the only way to delete it.
     */
    val isDraggable: Boolean get() = !kind.isDerived
}

/**
 * Builds the markers for a step list.
 *
 * Pause points and waits get a marker even though they have no coordinates. Without one they cannot
 * be hit-tested, which means they cannot be selected, which means they cannot be deleted — undo was
 * the only way to remove one, and inserting a pause point stops the recording, which is exactly
 * where undo is about to stop being offered.
 *
 * [screenWidth] and [screenHeight] are only used to place derived markers when there is nothing to
 * derive from, and to keep them on screen.
 */
fun buildMarkers(
    steps: List<Step>,
    screenWidth: Float,
    screenHeight: Float,
    displayDensity: Float,
): List<Marker> {
    val gestures = steps.map { it as? GestureStep }
    val spacing = DERIVED_SPACING_DP * displayDensity

    return steps.mapIndexedNotNull { index, step ->
        when (step) {
            is GestureStep -> Marker(
                stepId = step.id,
                number = index + 1,
                kind = step.kind.toMarkerKind(),
                anchorX = step.anchor.x,
                anchorY = step.anchor.y,
                paths = step.strokes.map { stroke -> stroke.points.map { it.x to it.y } },
            )

            is PauseStep -> {
                val (x, y) = derivedAnchor(gestures, index, spacing, screenWidth, screenHeight)
                Marker(
                    stepId = step.id,
                    number = index + 1,
                    kind = if (step.isTimed) MarkerKind.WAIT else MarkerKind.PAUSE,
                    anchorX = clamp(x, screenWidth, spacing),
                    anchorY = clamp(y, screenHeight, spacing),
                )
            }

            // No UI constructs one of these yet. When something does, it needs the same treatment as
            // the two above or it will be unselectable in exactly the same way.
            is GlobalStep -> null
        }
    }
}

/** Roughly a fingertip apart, so consecutive derived markers stay separately tappable. */
private const val DERIVED_SPACING_DP = 46f

/**
 * Where to draw a step that has no coordinates of its own.
 *
 * Consecutive ones are handled as a run rather than one at a time: placing each at, say, the
 * midpoint of its neighbours would stack every member of the run on the same pixel.
 */
private fun derivedAnchor(
    gestures: List<GestureStep?>,
    index: Int,
    spacing: Float,
    screenWidth: Float,
    screenHeight: Float,
): Pair<Float, Float> {
    var runStart = index
    while (runStart > 0 && gestures[runStart - 1] == null) runStart--
    var runEnd = index
    while (runEnd < gestures.lastIndex && gestures[runEnd + 1] == null) runEnd++

    val position = index - runStart + 1
    val runLength = runEnd - runStart + 1
    val before = gestures.getOrNull(runStart - 1)
    val after = gestures.getOrNull(runEnd + 1)

    return when {
        // Between two gestures: spread along the dashed link the user already sees, so the node
        // lands on the line rather than beside it.
        before != null && after != null -> {
            val t = position.toFloat() / (runLength + 1)
            before.anchor.x + (after.anchor.x - before.anchor.x) * t to
                before.anchor.y + (after.anchor.y - before.anchor.y) * t
        }

        // Trailing the last gesture: carry on in the direction the run arrived from.
        before != null -> {
            val (dx, dy) = approachDirection(gestures, runStart - 1)
            before.anchor.x + dx * spacing * position to before.anchor.y + dy * spacing * position
        }

        // Leading the first gesture: back away from it, against the direction it leaves in.
        after != null -> {
            val (dx, dy) = departDirection(gestures, runEnd + 1)
            val steps = runLength - position + 1
            after.anchor.x - dx * spacing * steps to after.anchor.y - dy * spacing * steps
        }

        // Nothing anywhere in the workspace has coordinates, so there is no neighbour to derive
        // from. A column down the middle at least gives every one of them a distinct, reachable spot.
        else -> screenWidth / 2f to screenHeight / 2f + (position - (runLength + 1) / 2f) * spacing
    }
}

/** Unit vector of the move into [index], so trailing markers keep going the same way. */
private fun approachDirection(gestures: List<GestureStep?>, index: Int): Pair<Float, Float> {
    val here = gestures.getOrNull(index) ?: return DEFAULT_DIRECTION
    val previous = (index - 1 downTo 0).firstNotNullOfOrNull { gestures[it] } ?: return DEFAULT_DIRECTION
    return normalise(here.anchor.x - previous.anchor.x, here.anchor.y - previous.anchor.y)
}

/** Unit vector of the move out of [index], so leading markers back away along the same line. */
private fun departDirection(gestures: List<GestureStep?>, index: Int): Pair<Float, Float> {
    val here = gestures.getOrNull(index) ?: return DEFAULT_DIRECTION
    val next = (index + 1..gestures.lastIndex).firstNotNullOfOrNull { gestures[it] } ?: return DEFAULT_DIRECTION
    return normalise(next.anchor.x - here.anchor.x, next.anchor.y - here.anchor.y)
}

/** Straight down, used whenever there is no second gesture to take a direction from. */
private val DEFAULT_DIRECTION = 0f to 1f

private fun normalise(dx: Float, dy: Float): Pair<Float, Float> {
    val length = hypot(dx, dy)
    // Two gestures on almost the same pixel give a direction that is mostly rounding error.
    return if (length < 1f) DEFAULT_DIRECTION else dx / length to dy / length
}

/** Keeps a derived anchor on screen. A zero extent means the size is not known yet, so leave it be. */
private fun clamp(value: Float, extent: Float, margin: Float): Float =
    if (extent <= margin * 2f) value else value.coerceIn(margin, extent - margin)

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
    private val colorPause = color(context, R.color.marker_pause)
    private val colorWait = color(context, R.color.marker_wait)
    private val colorLabel = color(context, R.color.marker_label)
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
        if (marker.kind.isDerived) {
            drawDerivedMarker(canvas, marker, highlighted, scale)
            return
        }

        val tint = if (highlighted) colorHighlight else when (marker.kind) {
            MarkerKind.TAP -> colorTap
            MarkerKind.LONG_PRESS -> colorLongPress
            MarkerKind.SWIPE -> colorSwipe
            MarkerKind.MULTI_TOUCH -> colorMulti
            MarkerKind.PAUSE, MarkerKind.WAIT -> colorPause
        }
        val radius = dp(if (highlighted) 13f else 10f) * scale

        if (marker.kind == MarkerKind.SWIPE || marker.kind == MarkerKind.MULTI_TOUCH) {
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

        if (marker.kind == MarkerKind.LONG_PRESS) {
            stroke.strokeWidth = dp(2.5f) * scale
            canvas.drawCircle(marker.anchorX, marker.anchorY, radius + dp(5f) * scale, stroke)
        }

        fill.color = tint
        canvas.drawCircle(marker.anchorX, marker.anchorY, radius, fill)

        label.textSize = dp(if (highlighted) 13f else 11f) * scale
        val baseline = marker.anchorY - (label.descent() + label.ascent()) / 2f
        canvas.drawText(marker.number.toString(), marker.anchorX, baseline, label)
    }

    /**
     * A pause point or a wait, drawn as a node sitting on the dashed link.
     *
     * Deliberately no crosshair: the crosshair means "this exact pixel", and these steps have no
     * pixel. Their anchor only exists so there is something to tap. The glyphs are drawn rather than
     * typed because every user-visible string has to come from strings.xml, and a shape is not one.
     */
    private fun drawDerivedMarker(canvas: Canvas, marker: Marker, highlighted: Boolean, scale: Float) {
        val tint = when {
            highlighted -> colorHighlight
            marker.kind == MarkerKind.WAIT -> colorWait
            else -> colorPause
        }
        val radius = dp(if (highlighted) 12f else 10f) * scale

        fill.color = tint
        canvas.drawCircle(marker.anchorX, marker.anchorY, radius, fill)

        stroke.color = colorLabel
        stroke.strokeWidth = dp(2f) * scale
        if (marker.kind == MarkerKind.WAIT) {
            // Clock hands, reading noon and three.
            canvas.drawLine(
                marker.anchorX, marker.anchorY,
                marker.anchorX, marker.anchorY - radius * 0.55f, stroke,
            )
            canvas.drawLine(
                marker.anchorX, marker.anchorY,
                marker.anchorX + radius * 0.4f, marker.anchorY, stroke,
            )
        } else {
            val inset = radius * 0.3f
            val reach = radius * 0.42f
            canvas.drawLine(
                marker.anchorX - inset, marker.anchorY - reach,
                marker.anchorX - inset, marker.anchorY + reach, stroke,
            )
            canvas.drawLine(
                marker.anchorX + inset, marker.anchorY - reach,
                marker.anchorX + inset, marker.anchorY + reach, stroke,
            )
        }

        // Outside the disc, because the glyph already occupies the inside.
        label.textSize = dp(10f) * scale
        canvas.drawText(
            marker.number.toString(),
            marker.anchorX,
            marker.anchorY + radius + dp(11f) * scale,
            label,
        )
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


    private fun color(context: Context, id: Int): Int =
        runCatching { ContextCompat.getColor(context, id) }.getOrDefault(Color.WHITE)
}
