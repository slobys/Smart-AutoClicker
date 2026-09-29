/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

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
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.ui.views.areaselector.AreaSelectorView
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
class RouteAreaSelectorTests {
    private fun selector(): AreaSelectorView {
        val display = mock<DisplayConfigManager>()
        whenever(display.displayConfig).thenReturn(DisplayConfig(Point(1920, 1080), Configuration.ORIENTATION_LANDSCAPE, 0, emptyMap()))
        return AreaSelectorView(ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.ScenarioConfigTheme), display).apply {
            layout(0, 0, 1920, 1080)
            setSelection(Rect(400, 300, 800, 600), Rect(0, 0, 8, 8))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        }
    }

    @Test fun whiteOutlineAndHandlesRemainAfterHintsFade() {
        val view = selector()
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            assertEquals(Color.WHITE, bitmap.getPixel(398, 400))
            assertEquals(Color.WHITE, bitmap.getPixel(398, 450))
            assertEquals(0, Color.alpha(bitmap.getPixel(600, 400)))
        } finally { bitmap.recycle() }
    }

    @Test fun dragCenterMovesWithoutResizing() {
        val view = selector()
        drag(view, 600f, 450f, 640f, 490f)
        assertEquals(Rect(440, 340, 840, 640), view.getSelection())
    }

    @Test fun dragRightEdgeResizesWithoutMovingOtherEdges() {
        val view = selector()
        drag(view, 800f, 450f, 840f, 450f)
        assertEquals(Rect(400, 300, 840, 600), view.getSelection())
    }

    @Test fun resizingCannotCollapseBelowMinimumContentSize() {
        val view = selector()
        drag(view, 800f, 450f, 401f, 450f)
        assertEquals(8, view.getSelection().width())
    }

    private fun drag(view: AreaSelectorView, x: Float, y: Float, toX: Float, toY: Float) {
        val start = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, x, y), Triple(MotionEvent.ACTION_MOVE, toX, toY),
            Triple(MotionEvent.ACTION_UP, toX, toY)).forEachIndexed { index, (action, px, py) ->
            val event = MotionEvent.obtain(start, start + index * 100, action, px, py, 0)
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
    }
}
