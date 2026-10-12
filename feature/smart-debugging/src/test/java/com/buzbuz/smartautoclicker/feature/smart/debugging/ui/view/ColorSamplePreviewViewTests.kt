/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import com.buzbuz.smartautoclicker.core.processing.domain.model.ColorSamplePreview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorSamplePreviewViewTests {
    private fun view() = ColorSamplePreviewView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 90, 90) }
    private fun preview() = ColorSamplePreview(Point(20, 20), 3, 3,
        IntArray(9) { Color.BLUE }.also { it[4] = Color.RED }, 1, 1)
    private fun draw(view: ColorSamplePreviewView) =
        Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888).apply { view.draw(Canvas(this)) }

    @Test fun magnifiedSamplingPixelKeepsOriginalColorAndSharpEdges() {
        val view = view().apply { setPreview(preview()) }
        val bitmap = draw(view)
        for (y in 30 until 60) for (x in 30 until 60) assertEquals(Color.RED, bitmap.getPixel(x, y))
        assertEquals(Color.BLUE, bitmap.getPixel(10, 10))
    }

    @Test fun previewOwnsItsBitmapAndClearingRemovesOldPixels() {
        val source = preview()
        val view = view().apply { setPreview(source) }
        source.pixels.fill(Color.GREEN)
        assertEquals(Color.RED, draw(view).getPixel(45, 45))
        view.setPreview(null)
        assertEquals(Color.TRANSPARENT, draw(view).getPixel(45, 45))
    }

    @Test fun screenEdgeSampleCanBeMagnifiedWithoutMovingMarkerToCentre() {
        val view = view().apply { setPreview(preview().copy(sampleX = 0, sampleY = 0)) }
        assertEquals(Color.BLUE, draw(view).getPixel(15, 15))
        assertEquals(Color.RED, draw(view).getPixel(45, 45))
    }
}
