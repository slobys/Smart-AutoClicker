package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.view.View
import androidx.core.graphics.withSave

import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.uistate.ScreenConditionResultUiState

/**
 * Displays thin borders immediately outside the actual detection results, without painting over target pixels.
 * @param context the Android context.
 */
internal class DebugOverlayView(context: Context) : View(context) {

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
                if (uiState.positive) positiveResultPaint else negativeResultPaint,
                Rect(uiState.coordinates),
            )
        }

}

/** Physical pixels, deliberately independent of density so small labels keep the same fine border. */
private const val RESULT_BORDER_WIDTH_PX = 2f
