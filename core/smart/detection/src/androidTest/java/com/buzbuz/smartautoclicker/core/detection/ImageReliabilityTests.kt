// SPDX-License-Identifier: GPL-3.0-or-later
package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** Real JNI regressions, independent of a game, keyword, or saved script. */
@RunWith(AndroidJUnit4::class)
class ImageReliabilityTests {
    private lateinit var detector: ImageDetector

    @Before fun setUp() {
        detector = NativeDetector.newInstance()!!
        detector.init()
    }

    @After fun tearDown() = detector.close()

    @Test fun colorStructureWithConstantLuminance_isDetectedAndDisappearanceClearsHit() {
        withTemplate(pattern(equalLuminance = true)) { template ->
            withScreen(screen(160, 100).apply { Canvas(this).drawBitmap(template, 70f, 40f, null) }) {
                val result = detect(template, it)
                assertTrue("valid colour-only structure rejected: $result", result.isDetected)
                assertEquals(Point(86, 56), result.position)
            }
            withScreen(screen(160, 100)) {
                assertFalse("flat area or previous frame accepted", detect(template, it).isDetected)
            }
        }
    }

    @Test fun manyGrayscaleIdenticalWrongColourIcons_doNotHideRealTarget() {
        withTemplate(pattern(equalLuminance = false)) { template ->
            withTemplate(pattern(equalLuminance = false, swapped = true)) { decoy ->
                withScreen(screen(320, 80).apply {
                    val canvas = Canvas(this)
                    repeat(7) { canvas.drawBitmap(decoy, 8f + it * 38, 8f, null) }
                    canvas.drawBitmap(template, 280f, 8f, null)
                }) {
                    val result = detect(template, it)
                    assertTrue("colour decoys exhausted candidate budget: $result", result.isDetected)
                    assertEquals(Point(296, 24), result.position)
                }
                withScreen(screen(320, 80).apply {
                    val canvas = Canvas(this)
                    repeat(8) { canvas.drawBitmap(decoy, 8f + it * 38, 8f, null) }
                }) { assertFalse("wrong spatial colour layout accepted", detect(template, it).isDetected) }
            }
        }
    }

    @Test fun lowContrastButStructuredTemplate_isNotTreatedAsAFlatColour() {
        withTemplate(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 120, 120))
            Canvas(this).apply {
                val paint = Paint().apply { color = Color.rgb(126, 126, 126) }
                drawRect(5f, 5f, 11f, 27f, paint)
                drawRect(11f, 21f, 26f, 27f, paint)
            }
        }) { template ->
            withScreen(screen(120, 80).apply { Canvas(this).drawBitmap(template, 65f, 35f, null) }) {
                assertTrue("exact low contrast image rejected", detect(template, it).isDetected)
            }
            withScreen(screen(120, 80, Color.rgb(123, 123, 123))) {
                assertFalse("constant screen accepted", detect(template, it).isDetected)
            }
        }
    }

    @Test fun fullHdDynamicBackground_usesBoundedForegroundSearchAtTileSeam() {
        withTemplate(dynamicIcon(80, 80, 0, 0, 0)) { template ->
            // Cross a search-tile boundary and keep a non-zero ROI origin for coordinate mapping.
            repeat(3) { frame ->
                val x = 970 + frame * 3
                val y = 490 + frame * 4
                val width = if (frame == 1) 1080 else 1920
                val height = if (frame == 1) 2400 else 1080
                withScreen(dynamicIcon(width, height, x, y, frame + 3)) {
                    val result = detect(template, it, Rect(30, 20, width - 20, height - 20), threshold = 8)
                    assertTrue("full HD moving background missed frame $frame: $result", result.isDetected)
                    assertTrue("tile coordinate wrong: ${result.position}",
                        kotlin.math.abs(result.position.x - (x + 40)) <= 2 &&
                            kotlin.math.abs(result.position.y - (y + 40)) <= 2)
                }
            }
            withScreen(dynamicIcon(1920, 1080, 975, 495, 4, visible = false)) {
                assertFalse("background/stale full HD hit", detect(template, it, threshold = 8).isDetected)
            }
            withScreen(dynamicIcon(1920, 1080, 975, 495, 4, foreground = Color.RED)) {
                assertFalse("wrong-colour full HD icon", detect(template, it, threshold = 8).isDetected)
            }
        }
    }

    @Test fun tallPortrait_dynamicScaledTargetIsNotSkippedBecauseOfSearchAreaSize() {
        withTemplate(dynamicIcon(80, 80, 0, 0, 0)) { template ->
            listOf(0.9f, 1.1f).forEachIndexed { frame, scale ->
                withScreen(dynamicIcon(1080, 2400, 960, 970, frame + 2, scale = scale)) {
                    val result = detect(template, it, threshold = 8)
                    assertTrue("portrait scale $scale skipped: $result", result.isDetected)
                    assertTrue("portrait scaled position: ${result.position}",
                        kotlin.math.abs(result.position.x - (960 + 40 * scale)) <= 3 &&
                            kotlin.math.abs(result.position.y - (970 + 40 * scale)) <= 3)
                }
                withScreen(dynamicIcon(1080, 2400, 960, 970, frame + 2,
                    foreground = Color.RED, scale = scale)) {
                    assertFalse("portrait scaled colour decoy", detect(template, it, threshold = 8).isDetected)
                }
            }
        }
    }

    @Test fun repeatedExactAreaCalls_doNotInvalidateTheNextPass() {
        withTemplate(pattern(equalLuminance = false)) { template ->
            withScreen(screen(160, 100).apply { Canvas(this).drawBitmap(template, 70f, 40f, null) }) {
                repeat(12) { call ->
                    val result = detect(template, it, Rect(70, 40, 102, 72), threshold = 0)
                    assertTrue("exact ROI failed on call $call", result.isDetected)
                    assertEquals(Point(86, 56), result.position)
                }
                assertFalse("target outside search area accepted",
                    detect(template, it, Rect(0, 0, 40, 40)).isDetected)
            }
        }
    }

    private fun detect(template: Bitmap, frame: Bitmap,
                       roi: Rect = Rect(0, 0, frame.width, frame.height), threshold: Int = 4): DetectionResult {
        val start = SystemClock.elapsedRealtime()
        return detector.detectImage(template, template.width, template.height, roi, threshold).also {
            Log.i("ImageReliabilityTests", "${frame.width}x${frame.height}, $roi: " +
                "${SystemClock.elapsedRealtime() - start}ms, hit=${it.isDetected}, score=${it.confidenceRate}")
        }
    }

    private fun pattern(equalLuminance: Boolean, swapped: Boolean = false): Bitmap =
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            val red = Color.rgb(255, 0, 0)
            val green = Color.rgb(0, 130, 0) // Same 8-bit RGBA-to-gray luminance as red.
            val pixels = IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                if (!equalLuminance && (x < 2 || y < 2 || x >= 30 || y >= 30)) {
                    if ((x + y) % 2 == 0) Color.WHITE else Color.BLACK
                } else if (((x / 5 + y / 7) % 2 == 0) xor swapped) red else green
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun dynamicIcon(width: Int, height: Int, x: Int, y: Int, frame: Int,
                            visible: Boolean = true, foreground: Int = Color.WHITE, scale: Float = 1f): Bitmap =
        screen(width, height).apply {
            val pixels = IntArray(width * height) { index ->
                val wave = (sin((index % width + frame * 21) / 35.0) * 32 +
                    sin((index / width - frame * 13) / 22.0) * 24).toInt()
                Color.rgb((80 + wave + frame * 11 % 70).coerceIn(0, 255),
                    (115 - wave + frame * 7 % 60).coerceIn(0, 255), (145 + wave).coerceIn(0, 255))
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
            if (visible) {
                val canvas = Canvas(this)
                canvas.translate(x.toFloat(), y.toFloat())
                canvas.scale(scale, scale)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = foreground; strokeWidth = 6f; style = Paint.Style.STROKE
                }
                canvas.drawCircle(40f, 40f, 23f, paint)
                canvas.drawLine(40f, 24f, 40f, 52f, paint)
                canvas.drawLine(40f, 52f, 54f, 46f, paint)
            }
        }

    private fun screen(width: Int, height: Int, color: Int = Color.rgb(30, 30, 30)) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private inline fun withTemplate(bitmap: Bitmap, block: (Bitmap) -> Unit) {
        try { block(bitmap) } finally { bitmap.recycle() }
    }

    private inline fun withScreen(bitmap: Bitmap, block: (Bitmap) -> Unit) {
        try {
            detector.setScreenBitmap(bitmap, "image-reliability-test")
            block(bitmap)
        } finally {
            detector.releaseScreenBitmap(bitmap)
            bitmap.recycle()
        }
    }
}
