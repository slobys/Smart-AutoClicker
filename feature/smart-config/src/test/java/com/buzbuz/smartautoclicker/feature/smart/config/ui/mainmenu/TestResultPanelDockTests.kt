/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Point
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.buzbuz.smartautoclicker.core.common.overlays.di.OverlaysEntryPoint
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common.OverlayMenuPositionDataSource
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.di.DisplayEntryPoint
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.SidePanelOverlayMenu
import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.conditiontry.TryImageConditionOverlayMenu
import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.eventtry.TryEventOverlayMenu
import com.google.android.material.slider.Slider
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
import com.buzbuz.smartautoclicker.feature.smart.debugging.R as DebugR

/** Real test-result XML and overlay lifecycle, without starting recognition or injecting game input. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "land-xhdpi")
class TestResultPanelDockTests {
    private val display = mockk<DisplayConfigManager>()
    private val positions = mockk<OverlayMenuPositionDataSource>(relaxed = true)
    private val window = mockk<WindowManager>(relaxed = true)
    private val saved = mutableMapOf<Int, Point>()
    private val opened = mutableListOf<TestMenu>()
    private var config = DisplayConfig(Point(1920, 1080), Configuration.ORIENTATION_LANDSCAPE, 0, emptyMap())
    private lateinit var context: Context

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
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
        every { display.displayConfig } answers { config }
        every { positions.loadMenuPosition(any()) } answers { saved[firstArg()]?.let(::Point) }
        every { positions.saveMenuPosition(any(), any()) } answers { saved[secondArg()] = Point(firstArg<Point>()) }
        every { window.addView(any(), any()) } answers { layOut(firstArg(), secondArg()) }
        every { window.updateViewLayout(any(), any()) } answers { layOut(firstArg(), secondArg()) }
    }

    @After fun teardown() {
        opened.forEach { it.finish() }
        idle()
        unmockkStatic(EntryPoints::class)
    }

    @Test fun conditionResultsOpenInwardAtRightEdge() = checkEdge(event = false, left = false)
    @Test fun eventResultsOpenInwardAtRightEdge() = checkEdge(event = true, left = false)
    @Test fun conditionResultsPreserveLeftAndTopZeroCoordinates() = checkEdge(event = false, left = true)
    @Test fun eventResultsPreserveLeftAndTopZeroCoordinates() = checkEdge(event = true, left = true)

    private fun checkEdge(event: Boolean, left: Boolean) {
        val menu = open(event, Point(if (left) 0 else 1919, 0))
        assertDock(menu, left)
        assertOnScreen(menu)
        assertEquals(0, menu.params.y)
        assertEquals(if (left) 0 else config.sizePx.x - menu.params.width, menu.params.x)
    }

    @Test fun openingNearBottomReclampsNewWindowHeight() {
        val menu = open(event = true, Point(1900, 1079))
        assertDock(menu, left = false)
        assertOnScreen(menu)
        assertEquals(config.sizePx.y - menu.params.height, menu.params.y)
    }

    @Test fun draggingBetweenEdgesFlipsBothTestPanelsWithoutMirroringTheirContent() {
        listOf(false, true).forEach { event ->
            val menu = open(event, Point(1919, 100))
            drag(menu, -1900f)
            assertDock(menu, left = true)
            assertOnScreen(menu)
            drag(menu, 1900f)
            assertDock(menu, left = false)
            assertOnScreen(menu)
            assertEquals(1f, menu.resultPanel.scaleX, 0f)
        }
    }

    @Test fun returningToTestRestoresDirectionAndKeepsThresholdAndResultText() {
        val menu = open(event = false, Point(1919, 100))
        val slider = menu.root.findViewById<Slider>(DebugR.id.slider_threshold)
        val result = menu.root.findViewById<TextView>(DebugR.id.value_result)
        slider.value = 10f
        result.text = "176,163"
        menu.hide()
        idle()
        menu.show()
        idle()
        assertDock(menu, left = false)
        assertOnScreen(menu)
        assertEquals(10f, slider.value, 0f)
        assertEquals("176,163", result.text.toString())
        assertEquals(1f, result.scaleX, 0f)
    }

    @Test fun rotationUsesEachOrientationsPositionAndKeepsPanelInsideScreen() {
        val menu = open(event = false, Point(1919, 800))
        saved[Configuration.ORIENTATION_PORTRAIT] = Point(899, 1550)
        config = config.copy(sizePx = Point(900, 1600), orientation = Configuration.ORIENTATION_PORTRAIT)
        menu.rotate()
        idle()
        assertDock(menu, left = false)
        assertOnScreen(menu)
        config = config.copy(sizePx = Point(1920, 1080), orientation = Configuration.ORIENTATION_LANDSCAPE)
        menu.rotate()
        idle()
        assertDock(menu, left = false)
        assertOnScreen(menu)
    }

    @Test fun bothProductionTestMenusUseAdaptivePanelLifecycle() {
        assertEquals(SidePanelOverlayMenu::class.java, TryImageConditionOverlayMenu::class.java.superclass)
        assertEquals(SidePanelOverlayMenu::class.java, TryEventOverlayMenu::class.java.superclass)
    }

    private fun open(event: Boolean, position: Point): TestMenu {
        saved[config.orientation] = position
        return TestMenu(event).also { menu ->
            opened += menu
            // Creation is module-internal; exercise it with real feature XML from this consumer's test suite.
            menu.javaClass.methods.single { it.name.startsWith("create") && it.parameterCount == 2 }
                .invoke(menu, context, null)
            menu.show()
            idle()
        }
    }

    private fun assertDock(menu: TestMenu, left: Boolean) {
        val toolbar = menu.root.findViewById<View>(DebugR.id.menu_items)
        if (left) assertTrue("Results must open to the right", toolbar.right <= menu.resultPanel.left)
        else assertTrue("Results must open to the left", menu.resultPanel.right <= toolbar.left)
    }

    private fun assertOnScreen(menu: TestMenu) {
        assertTrue(menu.params.x >= 0 && menu.params.y >= 0)
        assertTrue("Window extends past right edge", menu.params.x + menu.params.width <= config.sizePx.x)
        assertTrue("Window extends past bottom edge", menu.params.y + menu.params.height <= config.sizePx.y)
        assertTrue(menu.resultPanel.width > 0 && menu.resultPanel.height > 0)
        assertTrue(menu.resultPanel.left >= 0 && menu.resultPanel.right <= menu.root.width)
    }

    private fun layOut(view: View, params: ViewGroup.LayoutParams) {
        view.layoutParams = params
        fun spec(size: Int) = if (size > 0) View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(spec(params.width), spec(params.height))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun drag(menu: TestMenu, dx: Float) {
        val move = menu.root.findViewById<View>(DebugR.id.btn_move)
        val start = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to 0f, MotionEvent.ACTION_MOVE to dx, MotionEvent.ACTION_UP to dx)
            .forEachIndexed { index, (action, x) ->
                val event = MotionEvent.obtain(start, start + 100 * index, action, x, 0f, 0)
                try { assertTrue(move.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private class TestMenu(private val event: Boolean) : SidePanelOverlayMenu() {
        lateinit var root: ViewGroup
        public override val resultPanel: View get() = root.findViewById(if (event) DebugR.id.layout_debug else DebugR.id.layout_result)
        val params get() = root.layoutParams as WindowManager.LayoutParams
        fun rotate() = onOrientationChanged()
        override fun animateOverlayView() = false
        override fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup =
            LayoutInflater.from(ContextThemeWrapper(context, R.style.ScenarioConfigTheme)).inflate(
                if (event) DebugR.layout.overlay_try_event_menu else DebugR.layout.overlay_try_image_condition_menu,
                null,
            ).let { it as ViewGroup }.also { root = it }
    }
}
