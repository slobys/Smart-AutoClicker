/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.areaselector

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
class ImageSelectorMinimumTests {
    @Test fun portraitMinimumRemainsValidAfterFractionalMoves() = checkMinimum(900, 1600)
    @Test fun landscapeMinimumRemainsValidAfterFractionalMoves() = checkMinimum(1600, 900)
    @Test @Config(qualifiers = "xhdpi") fun denseMinimumRemainsValidAfterFractionalMoves() = checkMinimum(900, 1600)

    private fun checkMinimum(width: Int, height: Int) {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.ScenarioConfigTheme)
        val display = mock<DisplayConfigManager>().apply {
            whenever(displayConfig).thenReturn(DisplayConfig(Point(width, height),
                if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT,
                0, emptyMap()))
        }
        var valid = false
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val view = ImageSelectorView(context, display) { valid = it }.apply {
            layout(0, 0, width, height); showCapture(bitmap); hide = false
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        fun selection(): Rect = view.getSelection().let { (area, crop) -> crop.recycle(); area }
        val original = selection()
        val outward = 6f * context.resources.displayMetrics.density
        drag(view, original.right + outward, original.exactCenterY(), original.left - 100f, original.exactCenterY())
        val narrow = selection()
        drag(view, narrow.exactCenterX(), narrow.bottom + outward, narrow.exactCenterX(), narrow.top - 100f)
        assertTrue(valid)
        // Moving an already minimum-sized box must not turn the confirm button grey.
        // Fractional touch coordinates also cross Float exponent boundaries (256, 512).
        listOf(250.3333f to 490.1234f, 260.1f to 520.1f, 339.2f to 737.6f,
            511.3333f to 255.3333f, 130.5f to 127.99998f).forEach { (x, y) ->
            val area = selection()
            drag(view, area.exactCenterX(), area.exactCenterY(), x, y)
            assertTrue("Minimum selection invalid at $x,$y", valid)
            val cropArea = selection()
            assertEquals(50, cropArea.width())
            assertEquals(50, cropArea.height())
        }
        bitmap.recycle()
    }

    private fun drag(view: ImageSelectorView, x: Float, y: Float, toX: Float, toY: Float) {
        val start = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, x, y), Triple(MotionEvent.ACTION_MOVE, toX, toY),
            Triple(MotionEvent.ACTION_UP, toX, toY)).forEachIndexed { index, (action, px, py) ->
            val event = MotionEvent.obtain(start, start + index * 100, action, px, py, 0)
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
    }
}
