/*
 * Copyright (C) 2026 Smart-AutoClicker contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.buzbuz.smartautoclicker.core.display.recorder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetectionFrameScalerTests {
    @Test fun portraitPreservesTargetBeyondReducedCaptureBottom() = checkWholeFrame(900, 1600, 527, 936)
    @Test fun landscapePreservesTargetBeyondReducedCaptureRight() = checkWholeFrame(1600, 900, 936, 527)

    private fun checkWholeFrame(width: Int, height: Int, scaledWidth: Int, scaledHeight: Int) {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            Canvas(this).drawRect(width * .8f, height * .8f, width * .95f, height * .95f, Paint().apply { color = Color.YELLOW })
        }
        val scaler = DetectionFrameScaler().apply { resize(Point(scaledWidth, scaledHeight)) }
        val result = scaler.scale(source)
        assertEquals(scaledWidth, result.width)
        assertEquals(scaledHeight, result.height)
        assertEquals(Color.YELLOW, result.getPixel((scaledWidth * .875).toInt(), (scaledHeight * .875).toInt()))
        assertEquals(Color.BLUE, result.getPixel(5, 5))
        assertEquals(Color.YELLOW, source.getPixel((width * .875).toInt(), (height * .875).toInt()))
    }

    @Test fun eachNewFrameReplacesPixelsWithoutKeepingOldMatch() {
        val source = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888)
        val scaler = DetectionFrameScaler().apply { resize(Point(40, 80)) }
        source.eraseColor(Color.GREEN)
        val first = scaler.scale(source)
        source.eraseColor(Color.TRANSPARENT)
        val second = scaler.scale(source)
        assertSame(first, second)
        assertEquals(Color.TRANSPARENT, second.getPixel(20, 40))
    }

    @Test fun nativeResolutionReturnsOriginalWithoutAllocationOrColorChanges() {
        val source = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val scaler = DetectionFrameScaler().apply { resize(Point(80, 160)) }
        assertSame(source, scaler.scale(source))
        assertEquals(Color.MAGENTA, source.getPixel(20, 40))
    }

    @Test fun resizeAndClearDropOnlyOwnedBuffersAndDoNotRecycleBorrowedImages() {
        val source = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888)
        val scaler = DetectionFrameScaler().apply { resize(Point(40, 80)) }
        val first = scaler.scale(source)
        scaler.resize(Point(40, 80))
        assertSame(first, scaler.scale(source))
        scaler.resize(Point(20, 40))
        val second = scaler.scale(source)
        assertNotSame(first, second)
        assertEquals(20, second.width)
        scaler.clear()
        assertFalse(first.isRecycled)
        assertFalse(second.isRecycled)
        assertFalse(source.isRecycled)
        scaler.resize(Point(40, 80))
        assertNotSame(first, scaler.scale(source))
    }

    @Test fun invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DetectionFrameScaler().resize(Point(0, 100)) }
    }
}
