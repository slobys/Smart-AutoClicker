package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.Point
import android.graphics.Path
import android.view.View
import androidx.core.graphics.withSave

import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.uistate.ScreenConditionResultUiState
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.IN_AREA
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition

/**
 * Displays thin borders immediately outside the actual detection results, without painting over target pixels.
 * @param context the Android context.
 */
internal class DebugOverlayView(context: Context) : View(context) {

    private val displayDensity = resources.displayMetrics.density
    private val rangePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val contrastPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE }
    private val ranges = mutableListOf<DetectionRange>()
    private val wholeScreenBounds = Rect()
    private val cornerPath = Path()

    /** Display metadata only: never changes a condition or expands its detection area. */
    fun setConditions(conditions: List<ScreenCondition>) {
        ranges.clear()
        ranges.addAll(conditions.map { it.toDetectionRange() })
        postInvalidate()
    }

    private val positiveResultPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = RESULT_BORDER_WIDTH_PX
    }
    private val negativeResultPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = RESULT_BORDER_WIDTH_PX
    }

    private val results: MutableList<ScreenConditionResultUiState> = mutableListOf()
    private val displayedResults: MutableList<Pair<Paint, Rect>> = mutableListOf()

    fun setResults(newResults: List<ScreenConditionResultUiState>) {
        updateResults(newResults)
        postInvalidate()
    }

    fun clear() {
        updateResults(emptyList())
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        wholeScreenBounds.set(0, 0, w, h)
        updateResults(results)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    @Suppress("DEPRECATION") // DIFFERENCE clipping is also supported on our minimum Android version (API 24).
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.withSave {
            // Protect every target, including overlapping results in an event test. This only clips overlay drawing;
            // it must never mask, crop or otherwise change the screenshot that recognition processes.
            displayedResults.forEach { (_, coordinates) ->
                clipRect(coordinates, Region.Op.DIFFERENCE)
            }
            ranges.forEach { range ->
                withSave {
                    // Event tests can have overlapping search regions; do not paint one over another.
                    ranges.forEach { other ->
                        if (other !== range) other.area?.let { clipRect(it, Region.Op.DIFFERENCE) }
                    }
                    drawRange(this, range)
                }
            }
            ranges.forEach { range ->
                if (range.protectInterior) range.area?.let { clipRect(it, Region.Op.DIFFERENCE) }
            }
            displayedResults.forEach { (paint, coordinates) ->
                val halfStroke = paint.strokeWidth / 2f
                drawRect(
                    coordinates.left - halfStroke,
                    coordinates.top - halfStroke,
                    coordinates.right + halfStroke,
                    coordinates.bottom + halfStroke,
                    paint,
                )
            }
        }
        results.forEach { result ->
            (result.samplePoint ?: result.colorPreview?.point)?.let { point ->
                canvas.withSave {
                    // A visible ring must not feed its own black/white pixels into a color area test.
                    ranges.forEach { range -> range.area?.let { clipRect(it, Region.Op.DIFFERENCE) } }
                    displayedResults.forEach { (_, area) -> clipRect(area, Region.Op.DIFFERENCE) }
                    drawSamplePoint(this, point, if (result.isDetected) Color.GREEN else Color.WHITE)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun drawRange(canvas: Canvas, range: DetectionRange) {
        range.point?.let { point ->
            canvas.withSave {
                range.area?.let { clipRect(it, Region.Op.DIFFERENCE) }
                drawSamplePoint(this, point, Color.WHITE)
            }
            return // A one-pixel sample uses only the hollow ring, never a tiny box over its centre.
        }
        val area = range.area ?: wholeScreenBounds
        if (area.isEmpty) return
        // Screen-edge boundaries are drawn in the outermost screen pixels, not shifted into the target.
        val left = area.left.coerceAtLeast(2)
        val top = area.top.coerceAtLeast(2)
        val right = area.right.coerceAtMost(width - 2)
        val bottom = area.bottom.coerceAtMost(height - 2)
        if (left >= right || top >= bottom) return
        val edge = 1f.coerceAtLeast(displayDensity * 0.75f)
        val offset = edge / 2
        val l = left - offset; val t = top - offset
        val r = right + offset; val b = bottom + offset
        val corner = (12 * displayDensity).coerceAtMost(minOf(right - left, bottom - top) / 3f)
        canvas.withSave {
            clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Region.Op.DIFFERENCE)
            contrastPaint.strokeWidth = edge + 2 * displayDensity
            rangePaint.strokeWidth = edge
            drawRect(l, t, r, b, contrastPaint)
            drawRect(l, t, r, b, rangePaint)
            cornerPath.apply {
                reset()
                moveTo(l, t + corner); lineTo(l, t); lineTo(l + corner, t)
                moveTo(r - corner, t); lineTo(r, t); lineTo(r, t + corner)
                moveTo(l, b - corner); lineTo(l, b); lineTo(l + corner, b)
                moveTo(r - corner, b); lineTo(r, b); lineTo(r, b - corner)
            }
            contrastPaint.strokeWidth = 4 * displayDensity
            rangePaint.strokeWidth = 2 * displayDensity
            drawPath(cornerPath, contrastPaint)
            drawPath(cornerPath, rangePaint)
        }
    }

    private fun drawSamplePoint(canvas: Canvas, point: Point, color: Int) {
        val x = point.x + 0.5f
        val y = point.y + 0.5f
        val radius = 7 * displayDensity
        contrastPaint.strokeWidth = 4 * displayDensity
        rangePaint.strokeWidth = 2 * displayDensity
        rangePaint.color = color
        canvas.drawCircle(x, y, radius, contrastPaint)
        canvas.drawCircle(x, y, radius, rangePaint)
        rangePaint.color = Color.WHITE
    }

    private fun updateResults(newResults: List<ScreenConditionResultUiState>) {
        if (results != newResults) {
            results.clear()
            results.addAll(newResults)
        }
        displayedResults.clear()

        // No condition matched ? Nothing to display
        if (results.isEmpty()) {
            return
        }

        displayedResults.addAll(results.toDisplayResults())
    }

    private fun List<ScreenConditionResultUiState>.toDisplayResults(): List<Pair<Paint, Rect>> =
        mapNotNull { uiState ->
            if (uiState.coordinates.isEmpty) return@mapNotNull null

            Pair(
                if (uiState.isDetected) positiveResultPaint else negativeResultPaint,
                Rect(uiState.coordinates),
            )
        }

}

internal data class DetectionRange(val area: Rect?, val point: Point? = null, val protectInterior: Boolean = false)

internal fun ScreenCondition.toDetectionRange(): DetectionRange = when (this) {
    is ScreenCondition.Image -> DetectionRange(when (detectionType) {
        EXACT -> Rect(area)
        IN_AREA -> detectionArea?.let(::Rect)
        else -> null // Whole display, resolved on layout/rotation.
    })
    is ScreenCondition.Color -> DetectionRange(Rect(detectionArea),
        if (detectionArea.width() <= 1 && detectionArea.height() <= 1)
            Point(detectionArea.centerX(), detectionArea.centerY()) else null, protectInterior = true)
    is ScreenCondition.Text -> DetectionRange(Rect(detectionArea))
    is ScreenCondition.Number -> DetectionRange(Rect(detectionArea))
}

/** Physical pixels, deliberately independent of density so small labels keep the same fine border. */
private const val RESULT_BORDER_WIDTH_PX = 2f
