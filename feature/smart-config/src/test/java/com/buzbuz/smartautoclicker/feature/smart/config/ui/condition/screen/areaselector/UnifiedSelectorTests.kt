/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.areaselector

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.ui.views.areaselector.AreaSelectorView
import com.buzbuz.smartautoclicker.core.ui.views.imageselector.ImageSelectorView
import com.buzbuz.smartautoclicker.feature.smart.config.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnifiedSelectorTests {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.ScenarioConfigTheme)

    private fun display(width: Int = 1920, height: Int = 1080): DisplayConfigManager = mock<DisplayConfigManager>().apply {
        whenever(displayConfig).thenReturn(DisplayConfig(Point(width, height),
            if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT,
            0, emptyMap()))
    }

    @Test fun imagePreviewStaysFullSizeAndCropUsesOriginalCoordinates() = checkFullSizePreview(1920, 1080)
    @Test fun portraitImagePreviewStaysFullSize() = checkFullSizePreview(1080, 1920)
    @Test @Config(qualifiers = "xhdpi") fun denseDisplayStillUsesScreenPixels() = checkFullSizePreview(1920, 1080)

    private fun checkFullSizePreview(width: Int, height: Int) {
        val original = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
            setPixel(width / 2, height / 2, Color.MAGENTA)
        }
        val view = ImageSelectorView(context, display(width, height)) {}.apply {
            layout(0, 0, width, height)
            showCapture(original)
            hide = false
        }
        idle()
        val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val (area, crop) = view.getSelection()
        try {
            view.draw(Canvas(rendered))
            assertTrue("No automatic black margins", Color.green(rendered.getPixel(5, 5)) > 0)
            val inset = context.resources.displayMetrics.density.toInt()
            assertEquals(context.resources.getDimensionPixelSize(R.dimen.overlay_condition_selector_width) - 2 * inset, area.width())
            assertEquals(Color.MAGENTA, rendered.getPixel(width / 2, height / 2))
            assertEquals(original.getPixel(area.left, area.top), crop.getPixel(0, 0))
            assertEquals(Color.MAGENTA, crop.getPixel(width / 2 - area.left, height / 2 - area.top))
            assertEquals("External resize handle", Color.WHITE,
                rendered.getPixel(area.right + 6 * inset, area.centerY()))
            // Every selected pixel must be free of borders and handles.
            for (y in area.top until area.bottom) for (x in area.left until area.right)
                assertEquals("Obscured at $x,$y", original.getPixel(x, y), rendered.getPixel(x, y))
        } finally { crop.recycle(); rendered.recycle(); original.recycle() }
    }

    @Test fun tinyColorAtAllFourCornersKeepsExactPixelCoordinates() {
        val view = areaSelector()
        listOf(Rect(0, 0, 1, 1), Rect(1919, 0, 1920, 1), Rect(0, 1079, 1, 1080),
            Rect(1919, 1079, 1920, 1080)).forEach { area ->
            view.setSelection(area, Rect(0, 0, 1, 1))
            assertEquals(area, view.getSelection())
        }
    }

    @Test fun fullScreenAreaDoesNotLoseBorderPixels() {
        val view = areaSelector()
        view.setSelection(Rect(0, 0, 1920, 1080), Rect(0, 0, 8, 8))
        assertEquals(Rect(0, 0, 1920, 1080), view.getSelection())
    }

    @Test fun singlePixelColorCanBeExpandedWithoutHittingExactPixelRow() {
        val view = areaSelector()
        view.setSelection(Rect(960, 600, 961, 601), Rect(0, 0, 1, 1))
        // Touch the right handle a few pixels below its center, outside the 1 px edge.
        drag(view, 967f, 603f, 1027f, 603f)
        assertEquals(Rect(960, 600, 1021, 601), view.getSelection())
        drag(view, 990f, 607f, 990f, 647f)
        assertEquals(Rect(960, 600, 1021, 641), view.getSelection())
    }

    @Test fun manualPinchZoomStillChangesCropScale() {
        val original = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val view = ImageSelectorView(context, display()) {}.apply {
            layout(0, 0, 1920, 1080); showCapture(original); hide = false
        }
        idle()
        val (before, firstCrop) = view.getSelection()
        firstCrop.recycle()
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, time: Long, left: Float, right: Float, count: Int = 2) {
            val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                x = if (i == 0) left else right; y = 200f; pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(start, start + time, action, count, props, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0, 200f, 400f, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20, 200f, 400f)
        for (i in 1..8) send(MotionEvent.ACTION_MOVE, 20L + 40 * i, 200f - 10 * i, 400f + 10 * i)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 400, 120f, 480f)
        send(MotionEvent.ACTION_UP, 420, 120f, 480f, 1)
        val (after, crop) = view.getSelection()
        try { assertTrue("Manual zoom remains available", after.width() < before.width()) }
        finally { crop.recycle(); original.recycle() }
    }

    @Test fun resizeToDisplayEdgePreservesSelectedLastColumn() {
        val view = areaSelector()
        view.setSelection(Rect(1800, 300, 1880, 340), Rect(0, 0, 8, 8))
        drag(view, 1886f, 320f, 1950f, 320f)
        assertEquals(Rect(1800, 300, 1920, 340), view.getSelection())
    }

    @Test fun numberAndTextCanShrinkToEightPixelsWithHandlesOutside() {
        val view = areaSelector()
        view.setSelection(Rect(400, 300, 600, 340), Rect(0, 0, 8, 8))
        drag(view, 606f, 320f, 401f, 320f)
        assertEquals(Rect(400, 300, 408, 340), view.getSelection())
        val rendered = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(rendered))
            for (y in 300 until 340) for (x in 400 until 408)
                assertEquals(0, Color.alpha(rendered.getPixel(x, y)))
        } finally { rendered.recycle() }
    }

    @Test fun imageManualPanStillMapsCropBackToOriginalPixels() {
        val original = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val view = ImageSelectorView(context, display()) {}.apply {
            layout(0, 0, 1920, 1080); showCapture(original); hide = false
        }
        idle()
        val (before, firstCrop) = view.getSelection()
        firstCrop.recycle()
        drag(view, 200f, 200f, 260f, 240f, requireHandled = false)
        val (after, secondCrop) = view.getSelection()
        try { assertEquals(Rect(before).apply { offset(-60, -40) }, after) }
        finally { secondCrop.recycle(); original.recycle() }
    }

    private fun areaSelector() = AreaSelectorView(context, display()).apply {
        layout(0, 0, 1920, 1080)
        setSelection(Rect(400, 300, 600, 340), Rect(0, 0, 8, 8))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

    private fun drag(view: View, x: Float, y: Float, toX: Float, toY: Float, requireHandled: Boolean = true) {
        val start = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, x, y), Triple(MotionEvent.ACTION_MOVE, toX, toY),
            Triple(MotionEvent.ACTION_UP, toX, toY)).forEachIndexed { index, (action, px, py) ->
            val event = MotionEvent.obtain(start, start + index * 100, action, px, py, 0)
            try {
                val consumed = view.onTouchEvent(event)
                if (requireHandled) assertTrue(consumed)
            } finally { event.recycle() }
        }
    }
}
