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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
class ImageSelectorValidityTests {
    private lateinit var bitmap: Bitmap
    private lateinit var view: ImageSelectorView
    private val changes = mutableListOf<Boolean>()

    @Before fun setup() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.ScenarioConfigTheme)
        val display = mock<DisplayConfigManager>().apply {
            whenever(displayConfig).thenReturn(DisplayConfig(Point(900, 1600), Configuration.ORIENTATION_PORTRAIT, 0, emptyMap()))
        }
        bitmap = Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888)
        view = ImageSelectorView(context, display) { changes += it }.apply { layout(0, 0, 900, 1600) }
    }

    @After fun tearDown() { bitmap.recycle() }

    @Test fun noCaptureIsInvalidAndLoadingCaptureImmediatelyNotifiesValidity() {
        assertFalse(view.isSelectionValid)
        view.showCapture(bitmap)
        assertTrue(view.isSelectionValid)
        assertEquals(listOf(true), changes)
    }

    @Test fun resetInvalidatesOldCaptureAndRetakeRestoresValidity() {
        show()
        view.hide = true
        assertFalse(view.isSelectionValid)
        view.showCapture(bitmap)
        assertTrue(view.isSelectionValid)
        assertEquals(listOf(true, false, true), changes)
    }

    @Test fun tooSmallIntersectionCannotConfirmAndPanBackRecovers() {
        show()
        val original = selection()
        val distance = original.right - 20f
        pan(distance)
        assertFalse("Only 20 px of the box is over the capture", view.isSelectionValid)
        assertThrows(IllegalStateException::class.java) { view.getSelection() }
        pan(-distance)
        assertTrue(view.isSelectionValid)
        assertEquals(original, selection())
    }

    @Test fun disjointBoxCannotConfirmAndPanBackRecovers() {
        show()
        val original = selection()
        pan(700f)
        assertFalse(view.isSelectionValid)
        assertThrows(IllegalStateException::class.java) { view.getSelection() }
        pan(-700f)
        assertTrue(view.isSelectionValid)
        assertEquals(original, selection())
    }

    private fun show() {
        view.showCapture(bitmap); view.hide = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    }
    private fun selection(): Rect = view.getSelection().let { (area, crop) -> crop.recycle(); area }
    private fun pan(distance: Float) {
        val start = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to 20f, MotionEvent.ACTION_MOVE to 20f + distance,
            MotionEvent.ACTION_UP to 20f + distance).forEachIndexed { index, (action, x) ->
            val event = MotionEvent.obtain(start, start + index * 100, action, x, 200f, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
    }
}
