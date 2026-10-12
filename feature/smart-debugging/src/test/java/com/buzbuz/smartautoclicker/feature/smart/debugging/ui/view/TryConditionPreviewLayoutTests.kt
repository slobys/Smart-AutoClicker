/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.buzbuz.smartautoclicker.core.processing.domain.model.ColorSamplePreview
import com.buzbuz.smartautoclicker.feature.smart.debugging.R
import com.buzbuz.smartautoclicker.feature.smart.debugging.databinding.OverlayTryImageConditionMenuBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.buzbuz.smartautoclicker.core.ui.R as UiR

/** Actual production XML with populated values, not a drawing of a proposed layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TryConditionPreviewLayoutTests {
    @Test fun populatedColorPanelFitsPortraitAndDoesNotClipScoreOrPreview() {
        checkLayout(color = true)
    }
    @Test fun nonColorPanelDoesNotReserveSpaceForColorPreview() {
        checkLayout(color = false)
    }

    private fun checkLayout(color: Boolean) {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), UiR.style.AppTheme)
        val binding = OverlayTryImageConditionMenuBinding.inflate(LayoutInflater.from(context))
        binding.colorPreviewPanel.visibility = if (color) View.VISIBLE else View.GONE
        binding.valueResult.text = "98.01 %"
        binding.valueThreshold.text = "80.00 %"
        binding.detectionStatus.setText(R.string.overlay_target_found)
        binding.conditionStatus.setText(R.string.overlay_condition_fulfilled)
        binding.sliderThreshold.valueTo = 20f
        binding.sliderThreshold.value = 20f
        binding.targetColor.setBackgroundColor(Color.YELLOW)
        binding.currentColor.setBackgroundColor(Color.YELLOW)
        binding.colorValues.text = context.getString(R.string.overlay_color_values, "#FFFF00", "#FFFF00")
        binding.samplePosition.text = context.getString(R.string.overlay_sample_hit, 123, 456)
        val pixels = IntArray(625) { if (it % 25 in 10..14 && it / 25 in 10..14) Color.YELLOW else Color.BLUE }
        binding.colorPreview.setPreview(ColorSamplePreview(Point(123, 456), 25, 25, pixels, 12, 12))
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        binding.root.measure(spec, spec)
        binding.root.layout(0, 0, binding.root.measuredWidth, binding.root.measuredHeight)
        assertTrue(binding.root.width <= 900)
        assertTrue(binding.root.height <= 600)
        listOf(binding.valueResult, binding.valueThreshold).forEach { value ->
            assertTrue("Score text clipped", value.layout.getLineWidth(0) <= value.width - value.paddingLeft - value.paddingRight)
        }
        if (color) {
            assertTrue(binding.colorPreview.width > 0 && binding.colorPreview.height > 0)
            assertTrue(binding.colorPreviewPanel.bottom <= binding.layoutResult.height)
        }
        System.getenv("KLICKR_PREVIEW_OUTPUT_DIR")?.let { output ->
            val bitmap = Bitmap.createBitmap(binding.root.width, binding.root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(35, 60, 80))
            binding.root.draw(canvas)
            File(output).mkdirs()
            File(output, if (color) "color-test-panel.png" else "image-test-panel.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
