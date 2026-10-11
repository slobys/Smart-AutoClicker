// SPDX-License-Identifier: GPL-3.0-or-later
package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All four detectors share the same fresh frame, including width/height changes on one instance. */
@RunWith(AndroidJUnit4::class)
class PortraitRecognitionTests {
    private lateinit var detector: ImageDetector
    private val sizes = listOf(Point(361, 641), Point(641, 361), Point(1080, 2400), Point(2400, 1080))

    @Before fun setUp() {
        detector = NativeDetector.newInstance()!!
        detector.init()
        val (path, models) = InstrumentationRegistry.getInstrumentation().targetContext.extractTestOcrModels()
        assertTrue(detector.loadTextDetectionModels(path, models))
    }

    @After fun tearDown() = detector.close()

    @Test fun image_keepsBottomRightCoordinatesAcrossPortraitLandscapeAndTallPhone() {
        val template = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).apply {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 170, 75) }
                drawCircle(24f, 24f, 18f, paint)
                paint.color = Color.rgb(30, 60, 210)
                drawRect(12f, 16f, 20f, 34f, paint)
                drawRect(20f, 27f, 35f, 34f, paint)
            }
        }
        try {
            sizes.forEachIndexed { frame, size ->
                val left = size.x - 73
                val top = size.y - 100
                withFrame(size, frame, { Canvas(it).drawBitmap(template, left.toFloat(), top.toFloat(), null) }) {
                    val result = detector.detectImage(template, 48, 48, Rect(left - 9, top - 7, size.x, size.y), 8)
                    assertTrue("image missed at $size", result.isDetected)
                    assertEquals("transposed image position at $size", Point(left + 24, top + 24), result.position)
                }
                withFrame(size, frame + 1) {
                    assertFalse("stale image after rotation $size",
                        detector.detectImage(template, 48, 48, Rect(left - 9, top - 7, size.x, size.y), 8).isDetected)
                }
            }
        } finally { template.recycle() }
    }

    @Test fun color_keepsCoverageAndAreaSearchCoordinatesAcrossRotation() {
        sizes.forEachIndexed { frame, size ->
            val patch = Rect(size.x - 92, size.y - 100, size.x - 60, size.y - 76)
            val search = Rect(size.x - 150, size.y - 150, size.x - 20, size.y - 20)
            withFrame(size, frame, { Canvas(it).drawRect(patch, Paint().apply { color = Color.GREEN }) }) {
                assertTrue("coverage missed $size", detector.detectColor(Color.GREEN, patch, 0).isDetected)
                val result = detector.detectColor(Color.GREEN, search, 0, true)
                assertTrue("area colour missed $size", result.isDetected)
                assertTrue("colour position not in patch at $size", patch.contains(result.position.x, result.position.y))
                assertEquals(Color.GREEN, it.getPixel(result.position.x, result.position.y))
                assertFalse("wrong colour at $size", detector.detectColor(Color.RED, patch, 4).isDetected)
            }
            withFrame(size, frame + 1) {
                assertFalse("stale colour after rotation $size", detector.detectColor(Color.GREEN, search, 0, true).isDetected)
            }
        }
    }

    @Test fun text_keepsSelectedLineAndCoordinatesAcrossPortraitAndLandscape() {
        sizes.forEachIndexed { frame, size ->
            val roi = Rect(size.x - 280, size.y - 140, size.x - 20, size.y - 60)
            withFrame(size, frame, { drawLabel(it, roi, "QUEST") }) {
                val result = detector.detectText("QUEST", "latin", roi, 4)
                assertTrue("text missed $size: $result", result.isDetected)
                assertTrue("text position outside ROI at $size", roi.contains(result.position.x, result.position.y))
                assertFalse("wrong text at $size", detector.detectText("RESET", "latin", roi, 4).isDetected)
            }
            withFrame(size, frame + 1) {
                assertFalse("stale text after rotation $size", detector.detectText("QUEST", "latin", roi, 4).isDetected)
            }
        }
    }

    @Test fun number_keepsDecimalAndValueAcrossPortraitAndLandscape() {
        sizes.forEachIndexed { frame, size ->
            val value = listOf("23.5", "124", "16.5", "42")[frame]
            val roi = Rect(size.x - 280, size.y - 140, size.x - 20, size.y - 60)
            withFrame(size, frame, { drawLabel(it, roi, value) }) {
                val result = detector.detectNumber(roi, 10, NumberFormatType.DOT_DECIMAL)
                assertTrue("number missed $size: $result", result.isDetected)
                assertEquals(value.toDouble(), result.numberDetected!!, 0.0001)
                assertTrue("number position outside ROI at $size", roi.contains(result.position.x, result.position.y))
            }
            withFrame(size, frame + 1) {
                assertFalse("stale number after rotation $size",
                    detector.detectNumber(roi, 10, NumberFormatType.DOT_DECIMAL).isDetected)
            }
        }
    }

    private fun drawLabel(bitmap: Bitmap, roi: Rect, value: String) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 35, 45); style = Paint.Style.STROKE; strokeWidth = 3f
            textSize = 38f; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(value, roi.left + 15f, roi.top + 55f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(245, 240, 60)
        canvas.drawText(value, roi.left + 15f, roi.top + 55f, paint)
    }

    private inline fun withFrame(size: Point, frame: Int, draw: (Bitmap) -> Unit = {}, test: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(60 + frame * 9, 80 + frame * 7, 105 + frame * 11))
            draw(bitmap)
            detector.setScreenBitmap(bitmap, "portrait-recognition-test")
            test(bitmap)
        } finally {
            detector.releaseScreenBitmap(bitmap)
            bitmap.recycle()
        }
    }
}
