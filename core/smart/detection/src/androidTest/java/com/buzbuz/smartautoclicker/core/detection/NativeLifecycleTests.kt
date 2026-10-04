package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs through the actual JNI boundary, not a mocked image detector. */
@RunWith(AndroidJUnit4::class)
class NativeLifecycleTests {
    /** Opt-in soak: -e routeSoak true; not a ten-minute penalty on the ordinary native suite. */
    @Test fun routeMetadataContinuesBeyondTenMinutes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("routeSoak") == "true")
        val detector = checkNotNull(NativeDetector.newInstance())
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val area = Rect(0, 0, 32, 32)
        detector.init()
        try {
            val start = SystemClock.elapsedRealtime()
            var sample = 0
            do {
                val color = if (sample++ % 2 == 0) Color.GREEN else Color.RED
                bitmap.eraseColor(color)
                detector.setScreenBitmap(bitmap, "route:ten-minute-soak")
                try { assertTrue("Fresh frame rejected at ${SystemClock.elapsedRealtime() - start} ms",
                    detector.detectColor(color, area, 0).isDetected) }
                finally { detector.releaseScreenBitmap(bitmap) }
                Thread.sleep(1_000)
            } while (SystemClock.elapsedRealtime() - start < 620_000)
            // Also check a new session, where suppressed frames previously left a zero-sized ROI.
            val next = checkNotNull(NativeDetector.newInstance())
            next.init()
            try {
                bitmap.eraseColor(Color.BLUE)
                next.setScreenBitmap(bitmap, "route:new-session-after-soak")
                try { assertTrue(next.detectColor(Color.BLUE, area, 0).isDetected) }
                finally { next.releaseScreenBitmap(bitmap) }
            } finally { next.close() }
        } finally { detector.close(); bitmap.recycle() }
    }

    @Test fun routeMetadataNeverChangesFreshFrameAcceptance() {
        val detector = checkNotNull(NativeDetector.newInstance())
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val area = Rect(0, 0, 32, 32)
        detector.init()
        try {
            // Diagnostics may identify routes rather than packages. Every frame must replace
            // the preceding one; a successful result cannot come from stale pixels.
            repeat(20) { i ->
                val color = if (i % 2 == 0) Color.GREEN else Color.RED
                bitmap.eraseColor(color)
                detector.setScreenBitmap(bitmap, "route:metadata-regression-$i")
                try {
                    assertTrue(detector.detectColor(color, area, 0).isDetected)
                    val oldColor = if (color == Color.GREEN) Color.RED else Color.GREEN
                    assertTrue(!detector.detectColor(oldColor, area, 0).isDetected)
                } finally { detector.releaseScreenBitmap(bitmap) }
            }
        } finally { detector.close(); bitmap.recycle() }
    }

    @Test fun closeBeforeInitAndRepeatedCloseAreSafe() {
        val detector = checkNotNull(NativeDetector.newInstance())
        detector.close()
        detector.close()
    }

    @Test fun oneThousandFramesCanBeAcquiredDetectedAndReleased() {
        val detector = checkNotNull(NativeDetector.newInstance())
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        detector.init()
        try {
            repeat(1_000) {
                detector.setScreenBitmap(bitmap, "native_lifecycle_test")
                try {
                    assertNotNull(detector.detectColor(Color.GREEN, Rect(0, 0, 64, 64), 20))
                    assertTrue(detector.detectColor(Color.GREEN, Rect(0, 0, 64, 64), 20, true).isDetected)
                }
                finally { detector.releaseScreenBitmap(bitmap) }
            }
        } finally { detector.close(); bitmap.recycle() }
    }

    @Test fun invalidBitmapDoesNotAbortAndNextFrameStillWorks() {
        val detector = checkNotNull(NativeDetector.newInstance())
        val invalid = Bitmap.createBitmap(16, 16, Bitmap.Config.RGB_565)
        val valid = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        detector.init()
        try {
            try { detector.setScreenBitmap(invalid, "invalid_format"); fail("Expected format error") }
            catch (_: RuntimeException) { }
            detector.setScreenBitmap(valid, "valid_format")
            detector.releaseScreenBitmap(valid)
        } finally { detector.close(); invalid.recycle(); valid.recycle() }
    }

    @Test fun invalidTemplateSizeCanFailRepeatedlyWithoutDoubleUnlock() {
        val detector = checkNotNull(NativeDetector.newInstance())
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val condition = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        detector.init()
        try {
            detector.setScreenBitmap(bitmap, "invalid_template")
            try {
                repeat(20) {
                    try {
                        detector.detectImage(condition, -1, -1, Rect(0, 0, 32, 32), 20)
                        fail("Expected invalid dimensions")
                    } catch (_: RuntimeException) { }
                }
            } finally { detector.releaseScreenBitmap(bitmap) }
        } finally { detector.close(); bitmap.recycle(); condition.recycle() }
    }
}
