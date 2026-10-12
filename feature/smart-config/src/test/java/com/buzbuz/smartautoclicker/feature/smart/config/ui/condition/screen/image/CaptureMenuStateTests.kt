/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.buzbuz.smartautoclicker.core.common.overlays.di.OverlaysEntryPoint
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common.OverlayMenuPositionDataSource
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.di.DisplayEntryPoint
import com.buzbuz.smartautoclicker.core.ui.views.imageselector.ImageSelectorView
import com.buzbuz.smartautoclicker.feature.smart.config.R
import dagger.hilt.EntryPoints
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
class CaptureMenuStateTests {
    private val display = mockk<DisplayConfigManager>()
    private val window = mockk<WindowManager>(relaxed = true)
    private val positions = mockk<OverlayMenuPositionDataSource>(relaxed = true)
    private val added = mutableListOf<View>()
    private lateinit var menu: CaptureMenu
    private val root get() = added.first { it.findViewById<View>(R.id.btn_confirm) != null }
    private val confirm get() = root.findViewById<View>(R.id.btn_confirm)
    private val selector get() = added.filterIsInstance<ImageSelectorView>().single()

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == WINDOW_SERVICE) window else super.getSystemService(name)
        }
        mockkStatic(EntryPoints::class)
        val displayEntry = mockk<DisplayEntryPoint>()
        val overlayEntry = mockk<OverlaysEntryPoint>()
        every { EntryPoints.get(app, DisplayEntryPoint::class.java) } returns displayEntry
        every { EntryPoints.get(app, OverlaysEntryPoint::class.java) } returns overlayEntry
        every { displayEntry.displayMetrics() } returns display
        every { overlayEntry.overlayMenuPositionDataSource() } returns positions
        every { display.displayConfig } returns DisplayConfig(Point(900, 1600), Configuration.ORIENTATION_PORTRAIT, 0, emptyMap())
        every { positions.loadMenuPosition(any()) } returns null
        every { window.addView(any(), any()) } answers {
            added += firstArg<View>()
            layout(firstArg(), secondArg())
        }
        every { window.updateViewLayout(any(), any()) } answers { layout(firstArg(), secondArg()) }
        menu = CaptureMenu {}
        menu.javaClass.methods.single { it.name.startsWith("create") && it.parameterCount == 2 }.invoke(menu, context, null)
        menu.show()
        idle()
    }

    @After fun teardown() { menu.finish(); idle(); unmockkStatic(EntryPoints::class) }

    @Test fun returningFromInvalidSelectionAlwaysEnablesScreenshotButton() {
        state(3) // Adjust without a screenshot: confirmation must be disabled.
        assertFalse(confirm.isEnabled)
        state(1) // Take a new screenshot, independent of the previous crop validity.
        assertTrue(confirm.isEnabled)
        assertEquals(1f, confirm.alpha, 0f)
    }

    @Test fun loadedScreenshotImmediatelyEnablesAdjustConfirmation() {
        val bitmap = Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888)
        state(2)
        selector.showCapture(bitmap)
        state(3)
        assertTrue(selector.isSelectionValid)
        assertTrue(confirm.isEnabled)
        assertEquals(1f, confirm.alpha, 0f)
        bitmap.recycle()
    }

    @Test fun selectorCallbacksCannotReenableConfirmWhileSaving() {
        val bitmap = Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888)
        state(2); selector.showCapture(bitmap); state(3); state(4)
        assertFalse(confirm.isEnabled)
        selector.hide = true
        selector.showCapture(bitmap)
        assertTrue(selector.isSelectionValid)
        assertFalse(confirm.isEnabled)
        bitmap.recycle()
    }

    private fun state(value: Int) {
        menu.javaClass.getDeclaredMethod("setState", Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(menu, value)
    }

    private fun layout(view: View, params: ViewGroup.LayoutParams) {
        view.layoutParams = params
        fun spec(size: Int) = if (size > 0) View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(spec(params.width), spec(params.height))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
}
