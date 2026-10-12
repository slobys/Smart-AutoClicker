/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.tests

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import com.buzbuz.smartautoclicker.core.processing.domain.model.copyColorSamplePreview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorSamplePreviewTests {
    @Test fun copiesOnlyBoundedPatchAndOwnsItsPixels() {
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        frame.setPixel(45, 46, Color.RED)
        val preview = copyColorSamplePreview(frame, Rect(20, 20, 70, 70), Point(45, 46))!!
        assertEquals(25, preview.width)
        assertEquals(25, preview.height)
        assertEquals(Point(45, 46), preview.point)
        assertEquals(Color.RED, preview.color)
        frame.eraseColor(Color.GREEN)
        frame.recycle()
        assertEquals(Color.RED, preview.color)
        assertEquals(Color.BLUE, preview.pixels.first())
    }

    @Test fun missSamplesRangeCentreNotFabricatedHit() {
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val area = Rect(40, 50, 61, 71)
        val original = Rect(area)
        val preview = copyColorSamplePreview(frame, area, null)!!
        assertEquals(Point(50, 60), preview.point)
        assertEquals(original, area)
    }

    @Test fun cornersIncludingOriginAreValidAndNeverReadOutsideFrame() {
        val frame = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        listOf(Point(0, 0), Point(39, 0), Point(0, 59), Point(39, 59)).forEach { point ->
            frame.setPixel(point.x, point.y, Color.MAGENTA)
            val preview = copyColorSamplePreview(frame, Rect(0, 0, 40, 60), point)!!
            assertEquals(point, preview.point)
            assertEquals(Color.MAGENTA, preview.color)
            assertEquals(13, preview.width)
            assertEquals(13, preview.height)
            assertEquals(preview.width * preview.height, preview.pixels.size)
        }
    }

    @Test fun onePixelRegionAndPartiallyOffscreenRegionAreSupported() {
        val frame = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        assertEquals(Point(4, 5), copyColorSamplePreview(frame, Rect(4, 5, 5, 6), null)!!.point)
        assertEquals(Point(2, 2), copyColorSamplePreview(frame, Rect(-30, -30, 5, 5), null)!!.point)
    }

    @Test fun invalidOrReleasedFramesDoNotRetainOldPreview() {
        val frame = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        assertNull(copyColorSamplePreview(null, Rect(0, 0, 10, 10), null))
        assertNull(copyColorSamplePreview(frame, Rect(), null))
        assertNull(copyColorSamplePreview(frame, Rect(30, 30, 40, 40), null))
        frame.recycle()
        assertNull(copyColorSamplePreview(frame, Rect(0, 0, 10, 10), null))
    }

    @Test fun hitOutsideConfiguredRegionFallsBackToItsCentre() {
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertEquals(Point(15, 15), copyColorSamplePreview(frame, Rect(10, 10, 20, 20), Point(80, 80))!!.point)
    }
}
