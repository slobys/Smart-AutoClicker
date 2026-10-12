/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.uistate.ScreenConditionResultUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render actual pixels, not mocked draw calls: debug borders must not paint over detected content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebugOverlayViewTests {

    @Test fun positiveResultHasTwoPixelOutsideBorderWithoutTwentyPixelMargin() {
        val area = Rect(50, 60, 90, 80)
        val bitmap = render(listOf(result(area)))

        assertBorder(bitmap, area, Color.GREEN)
        assertEquals(Rect(48, 58, 92, 82), paintedBounds(bitmap))
    }

    @Test fun negativeResultUsesSameThinGeometryInRed() {
        val area = Rect(30, 40, 70, 65)
        assertBorder(render(listOf(result(area, positive = false))), area, Color.RED)
    }

    @Test fun tinyAndOddSizedTargetsHaveCompletelyTransparentInteriors() {
        listOf(Rect(20, 20, 21, 21), Rect(30, 40, 33, 47), Rect(50, 50, 132, 128)).forEach { area ->
            listOf(true, false).forEach { positive ->
                assertBorder(render(listOf(result(area, positive))), area, if (positive) Color.GREEN else Color.RED)
            }
        }
    }

    @Test fun callerCoordinatesAreNotChangedByDrawingOrResizing() {
        val area = Rect(30, 50, 81, 79)
        val original = Rect(area)
        val view = view().apply { setResults(listOf(result(area))) }

        draw(view)
        view.layout(0, 0, 400, 180)
        draw(view, 400, 180)

        assertEquals(original, area)
    }

    @Test fun emptyAndInvertedCoordinatesNeverDrawPhantomResults() {
        val areas = listOf(Rect(), Rect(20, 20, 20, 30), Rect(20, 20, 30, 20), Rect(30, 20, 20, 30))
        val results = areas.flatMap { listOf(result(it), result(it, positive = false)) }

        assertTrue(paintedBounds(render(results)).isEmpty)
    }

    @Test fun clearAndReplacementRemovePreviousBorders() {
        val oldArea = Rect(20, 20, 40, 40)
        val newArea = Rect(90, 90, 110, 110)
        val view = view().apply { setResults(listOf(result(oldArea))) }
        assertFalse(paintedBounds(draw(view)).isEmpty)

        view.setResults(listOf(result(newArea, positive = false)))
        assertBorder(draw(view), newArea, Color.RED)

        view.clear()
        assertTrue(paintedBounds(draw(view)).isEmpty)
    }

    @Test fun portraitAndLandscapeDoNotRescaleResultCoordinates() {
        val area = Rect(30, 50, 80, 90)
        listOf(180 to 320, 320 to 180).forEach { (width, height) ->
            assertBorder(render(listOf(result(area)), width, height), area, Color.GREEN)
        }
    }

    @Test fun bordersAtScreenEdgesAreClippedWithoutShiftingOrPaintingInside() {
        listOf(Rect(0, 0, 20, 20), Rect(160, 160, 180, 180), Rect(0, 0, 180, 180)).forEach { area ->
            assertBorder(render(listOf(result(area))), area, Color.GREEN)
        }
    }

    @Test
    @Config(qualifiers = "xxxhdpi")
    fun higherDisplayDensityDoesNotMakeTheBorderThicker() {
        val area = Rect(50, 60, 90, 80)
        assertBorder(render(listOf(result(area))), area, Color.GREEN)
    }

    @Test fun eventResultsDoNotPaintInsideOtherOverlappingTargets() {
        val first = Rect(40, 40, 70, 70)
        val second = Rect(60, 60, 90, 90)
        val bitmap = render(listOf(result(first), result(second, positive = false)))

        listOf(first, second).forEach { area ->
            for (y in area.top until area.bottom) for (x in area.left until area.right) {
                assertEquals("Target content at $x,$y", Color.TRANSPARENT, bitmap.getPixel(x, y))
            }
        }
        assertEquals(Color.GREEN, bitmap.getPixel(38, 45))
        assertEquals(Color.RED, bitmap.getPixel(90, 75))
    }

    @Test fun clippingDoesNotLeakIntoSubsequentCanvasDrawing() {
        val bitmap = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view().apply { setResults(listOf(result(Rect(40, 40, 70, 70)))) }.draw(canvas)

        canvas.drawColor(Color.BLUE)

        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            assertEquals(Color.BLUE, bitmap.getPixel(x, y))
        }
    }

    private fun result(area: Rect, positive: Boolean = true) = ScreenConditionResultUiState(
        positive = positive,
        coordinates = area,
        confidenceRate = if (positive) 0.98 else 0.1,
    )

    private fun view(width: Int = 180, height: Int = 180) =
        DebugOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, width, height) }

    private fun render(results: List<ScreenConditionResultUiState>, width: Int = 180, height: Int = 180): Bitmap =
        draw(view(width, height).apply { setResults(results) }, width, height)

    private fun draw(view: DebugOverlayView, width: Int = 180, height: Int = 180): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { view.draw(Canvas(this)) }

    private fun assertBorder(bitmap: Bitmap, area: Rect, color: Int) {
        val outside = Rect(area).apply { inset(-2, -2) }
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val expected = if (outside.contains(x, y) && !area.contains(x, y)) color else Color.TRANSPARENT
            assertEquals("Unexpected pixel at $x,$y around $area", expected, bitmap.getPixel(x, y))
        }
    }

    private fun paintedBounds(bitmap: Bitmap): Rect {
        val bounds = Rect()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) != 0) bounds.union(x, y, x + 1, y + 1)
        }
        return bounds
    }
}
