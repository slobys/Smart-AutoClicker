/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.buzbuz.smartautoclicker.core.processing.routes.*
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.OverlayRouteMenuBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteReadLayoutTests {
    @Test fun chineseDiagnosticsFitWithoutGrowingOrHidingControls() = checkLayouts()
    @Test @Config(qualifiers = "en-rUS-mdpi")
    fun englishDiagnosticsFitWithoutGrowingOrHidingControls() = checkLayouts()

    private fun checkLayouts() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.ScenarioConfigTheme)
        val binding = OverlayRouteMenuBinding.inflate(LayoutInflater.from(context))
        binding.routeTrace.visibility = View.GONE
        var originalHeight: Int? = null
        for (issue in RouteReadIssue.entries) {
            binding.routeStatus.text = RouteProgress(RouteMessage.LOST_POSITION, count = 1999,
                diagnostics = RouteReadDiagnostics(issue, issue != RouteReadIssue.MAP_MISMATCH,
                    RouteAxisReading(100000.0, .79, false), RouteAxisReading(100000.0, .79, false))).statusText(context)
            val width = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
            val height = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
            repeat(2) {
                binding.root.measure(width, height)
                binding.root.layout(0, 0, binding.root.measuredWidth, binding.root.measuredHeight)
            }
            val text = binding.routeStatus
            val layout = requireNotNull(text.layout)
            assertEquals("Truncated text: $issue", text.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue("Clipped text: $issue", layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
            assertEquals(originalHeight ?: binding.root.height, binding.root.height)
            assertEquals(View.VISIBLE, binding.routeFinish.visibility)
            assertTrue(binding.routeFinish.height > 0)
            originalHeight = binding.root.height
        }
    }
}
