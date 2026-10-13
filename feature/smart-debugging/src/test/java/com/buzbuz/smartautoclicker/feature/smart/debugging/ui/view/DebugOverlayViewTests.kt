/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Point
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.IN_AREA
import com.buzbuz.smartautoclicker.core.domain.model.WHOLE_SCREEN
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
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

    @Test fun fixedColorRangeRemainsVisibleBeforeFirstResultAndAfterMissAndClear() {
        val area = Rect(30, 40, 130, 120)
        val view = view().apply { setConditions(listOf(color(area))) }
        val initial = draw(view)
        assertTrue(initial.sameAs(draw(view)))
        assertTrue(hasColor(initial, Color.WHITE))
        assertTrue(hasColor(initial, Color.BLACK))
        assertInteriorTransparent(initial, area)
        view.setResults(listOf(result(Rect(), false)))
        assertTrue(initial.sameAs(draw(view)))
        view.clear()
        assertTrue(initial.sameAs(draw(view)))
        view.setConditions(emptyList())
        assertTrue(paintedBounds(draw(view)).isEmpty)
    }

    @Test fun movingHitDoesNotMoveTheConfiguredSearchRange() {
        val area = Rect(20, 20, 150, 150)
        val condition = image(Rect(40, 40, 50, 50)).copy(detectionType = IN_AREA, detectionArea = Rect(area))
        val view = view().apply { setConditions(listOf(condition)) }
        assertTrue(paintedBounds(draw(view)).isEmpty)
        listOf(Rect(40, 40, 50, 50), Rect(90, 95, 100, 105)).forEach { hit ->
            view.setResults(listOf(result(hit)))
            assertBorder(draw(view), hit, Color.GREEN)
            assertEquals(area, condition.detectionArea)
        }
    }

    @Test fun onePixelColorUsesVisibleRingWithTransparentSamplingPixel() {
        val point = Point(70, 80)
        val area = Rect(70, 80, 71, 81)
        val view = view().apply { setConditions(listOf(color(area))) }
        val initial = draw(view)
        assertTrue(paintedBounds(initial).width() >= 16)
        assertTrue(hasColor(initial, Color.WHITE))
        assertInteriorTransparent(initial, area)
        view.setResults(listOf(result(Rect()).copy(samplePoint = point)))
        val matched = draw(view)
        assertTrue(hasColor(matched, Color.GREEN))
        assertInteriorTransparent(matched, area)
        assertInteriorTransparent(initial, Rect(67, 77, 74, 84))
        assertInteriorTransparent(matched, Rect(67, 77, 74, 84))
    }

    @Test fun areaColorDoesNotDrawItsOwnRingIntoPixelsBeingRecognized() {
        val area = Rect(30, 30, 130, 130)
        val view = view().apply {
            setConditions(listOf(color(area).copy(findInArea = true)))
            setResults(listOf(result(Rect()).copy(samplePoint = Point(80, 80))))
        }
        assertInteriorTransparent(draw(view), area)
        assertTrue(hasColor(draw(view), Color.WHITE))
    }

    @Test fun overlappingConfiguredRangesNeverPaintInsideEachOther() {
        val first = Rect(30, 30, 100, 100)
        val second = Rect(60, 60, 140, 140)
        val view = view().apply { setConditions(listOf(color(first), color(second))) }
        listOf(first, second).forEach { assertInteriorTransparent(draw(view), it) }
    }

    @Test fun anotherConditionHitCannotPaintIntoColorArea() {
        val area = Rect(30, 30, 130, 130)
        val view = view().apply {
            setConditions(listOf(color(area)))
            setResults(listOf(result(Rect(70, 70, 80, 80))))
        }
        assertInteriorTransparent(draw(view), area)
    }

    @Test fun matchColorIsIndependentFromAnInvertedConditionBeingFulfilled() {
        val area = Rect(30, 30, 60, 60)
        assertBorder(render(listOf(result(area).copy(positive = false, isDetected = true))), area, Color.GREEN)
        assertBorder(render(listOf(result(area).copy(positive = true, isDetected = false))), area, Color.RED)
    }

    @Test fun imageSearchModesDoNotConfuseTemplatePositionAndSearchRange() {
        val template = Rect(20, 20, 40, 40)
        val search = Rect(50, 50, 150, 150)
        assertEquals(template, image(template).toDetectionRange().area)
        assertEquals(search, image(template).copy(detectionType = IN_AREA, detectionArea = search).toDetectionRange().area)
        assertEquals(null, image(template).copy(detectionType = WHOLE_SCREEN).toDetectionRange().area)
        val original = Rect(template)
        val range = image(template).toDetectionRange()
        range.area!!.inset(10, 10)
        assertEquals(original, template)
    }

    @Test fun wholeScreenSearchShowsOnlyResultBordersAcrossRotation() {
        val view = view().apply { setConditions(listOf(image(Rect(20, 20, 40, 40)).copy(detectionType = WHOLE_SCREEN))) }
        val hit = Rect(60, 60, 100, 100)
        listOf(180 to 320, 320 to 180).forEach { (w, h) ->
            view.layout(0, 0, w, h)
            assertTrue(paintedBounds(draw(view, w, h)).isEmpty)
            view.setResults(listOf(result(hit)))
            assertBorder(draw(view, w, h), hit, Color.GREEN)
            view.clear()
            assertTrue(paintedBounds(draw(view, w, h)).isEmpty)
        }
    }

    @Test fun imageTextAndNumberHaveNoInnerRangeWhileWaitingAfterMissOrAfterClear() {
        val area = Rect(60, 60, 120, 95)
        nonColorConditions(area).forEach { condition ->
            val view = view().apply { setConditions(listOf(condition)) }
            assertTrue(paintedBounds(draw(view)).isEmpty)
            view.setResults(listOf(result(Rect(), positive = false)))
            assertTrue(paintedBounds(draw(view)).isEmpty)
            view.setResults(listOf(result(Rect(area))))
            assertBorder(draw(view), area, Color.GREEN)
            view.clear()
            assertTrue(paintedBounds(draw(view)).isEmpty)
            assertEquals(area, condition.toDetectionRange().area)
        }
    }

    @Test fun allImageSearchModesShowOnlyTheExpandedResultBorder() {
        val template = Rect(60, 60, 120, 95)
        val search = Rect(30, 30, 150, 150)
        listOf(EXACT, IN_AREA, WHOLE_SCREEN).forEach { mode ->
            val condition = image(Rect(template)).copy(detectionType = mode, detectionArea = Rect(search))
            val view = view().apply { setConditions(listOf(condition)) }
            assertTrue(paintedBounds(draw(view)).isEmpty)
            listOf(true, false).forEach { detected ->
                view.setResults(listOf(result(Rect(template), positive = detected)))
                assertBorder(draw(view), template, if (detected) Color.GREEN else Color.RED)
            }
            assertEquals(template, condition.area)
            assertEquals(search, condition.detectionArea)
        }
    }

    @Test fun mixedEventKeepsColorOutlineWithoutNonColorInnerOutlines() {
        val colorArea = Rect(30, 30, 50, 50)
        val target = Rect(100, 100, 130, 130)
        val colorOnly = draw(view().apply { setConditions(listOf(color(colorArea))) })
        val view = view().apply { setConditions(listOf(color(colorArea)) + nonColorConditions(target)) }
        assertTrue(colorOnly.sameAs(draw(view)))
        view.setResults(listOf(result(target)))
        val rendered = draw(view)
        // Outside the expanded hit, the color-only overlay must remain exactly unchanged.
        for (y in 0 until 70) for (x in 0 until 70) {
            assertEquals(colorOnly.getPixel(x, y), rendered.getPixel(x, y))
        }
        assertInteriorTransparent(rendered, colorArea)
        assertInteriorTransparent(rendered, target)
        assertEquals(Color.TRANSPARENT, rendered.getPixel(target.left - 1, target.top))
        assertEquals(Color.GREEN, rendered.getPixel(target.left - 20, target.top))
    }

    private fun image(area: Rect) = ScreenCondition.Image(Identifier(databaseId = 1), Identifier(databaseId = 1),
        "Image", 20, true, 0, "template", area, EXACT)

    private fun nonColorConditions(area: Rect): List<ScreenCondition> {
        val id = Identifier(databaseId = 1)
        return listOf(
            image(Rect(area)),
            ScreenCondition.Text(id, id, "Text", 20, true, 0, "Telegram", Rect(area), OCRAlphabet.CHINESE_SIMPLIFIED),
            ScreenCondition.Number(id, id, "Number", 20, priority = 0, detectionArea = Rect(area),
                comparisonOperation = ComparisonOperation.EQUALS, counterValue = CounterOperationValue.Number(123.0)),
        )
    }

    private fun color(area: Rect) = ScreenCondition.Color(Identifier(databaseId = 1), Identifier(databaseId = 1),
        "Color", 20, true, 0, Color.BLUE, area)

    private fun hasColor(bitmap: Bitmap, color: Int): Boolean =
        (0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) == color } }

    private fun assertInteriorTransparent(bitmap: Bitmap, area: Rect) {
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            assertEquals("Range content at $x,$y", Color.TRANSPARENT, bitmap.getPixel(x, y))
        }
    }

    @Test fun positiveResultRestoresTenPixelBorderWithTwentyPixelMargin() {
        val area = Rect(50, 60, 90, 80)
        val bitmap = render(listOf(result(area)))

        assertBorder(bitmap, area, Color.GREEN)
        assertEquals(Rect(25, 35, 115, 105), paintedBounds(bitmap))
    }

    @Test fun imageTextAndNumberUseLegacyResultGeometryWithoutChangingTheirSearchAreas() {
        val area = Rect(60, 60, 120, 95)
        nonColorConditions(area).forEach { condition ->
            val view = view().apply { setConditions(listOf(condition)) }
            listOf(true, false).forEach { detected ->
                view.setResults(listOf(result(Rect(area), positive = detected)))
                assertBorder(draw(view), area, if (detected) Color.GREEN else Color.RED)
                assertEquals(area, condition.toDetectionRange().area)
            }
        }
    }

    @Test fun configuredRangeIsNotExpandedWhenResultsAreSetOrCleared() {
        val area = Rect(60, 60, 120, 95)
        val condition = image(Rect(area))
        val view = view().apply { setConditions(listOf(condition)) }
        val initial = draw(view)
        assertTrue(paintedBounds(initial).isEmpty)
        view.setResults(listOf(result(Rect(area))))
        assertEquals(Color.GREEN, draw(view).getPixel(40, 70))
        view.clear()
        assertTrue(initial.sameAs(draw(view)))
        assertEquals(area, condition.area)
    }

    @Test fun expandedEventBorderCannotPaintInsideANearbyNonOverlappingTarget() {
        val first = Rect(50, 50, 70, 70)
        val second = Rect(88, 50, 105, 70)
        val bitmap = render(listOf(result(first), result(second, positive = false)))
        assertInteriorTransparent(bitmap, first)
        assertInteriorTransparent(bitmap, second)
        assertTrue(hasColor(bitmap, Color.GREEN))
        assertTrue(hasColor(bitmap, Color.RED))
    }

    @Test fun expandedResultCannotPaintInsideANearbyConfiguredColorArea() {
        val colorArea = Rect(88, 50, 105, 70)
        val view = view().apply {
            setConditions(listOf(color(colorArea)))
            setResults(listOf(result(Rect(50, 50, 70, 70))))
        }
        assertInteriorTransparent(draw(view), colorArea)
        assertTrue(hasColor(draw(view), Color.GREEN))
    }

    @Test fun negativeResultUsesSameExpandedGeometryInRed() {
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
        nonColorConditions(area).forEach { condition ->
            val view = view().apply {
                setConditions(listOf(condition))
                setResults(listOf(result(Rect(area))))
            }
            assertBorder(draw(view), area, Color.GREEN)
        }
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
        assertEquals(Color.GREEN, bitmap.getPixel(15, 45))
        assertEquals(Color.RED, bitmap.getPixel(110, 75))
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
        // Legacy geometry: a 10 px stroke centred 20 px outside each target edge.
        val outside = Rect(area).apply { inset(-25, -25) }
        val inside = Rect(area).apply { inset(-15, -15) }
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val expected = if (outside.contains(x, y) && !inside.contains(x, y)) color else Color.TRANSPARENT
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
