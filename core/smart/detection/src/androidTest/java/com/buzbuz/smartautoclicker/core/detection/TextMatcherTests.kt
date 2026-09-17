/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class TextMatcherTests {

    private lateinit var context: Context
    private lateinit var testedDetector: ImageDetector

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testedDetector = NativeDetector.newInstance()
            ?: throw IllegalStateException("Can't instantiate detector for tests")

        testedDetector.init()
        val (detectModelPath, recognitionModels) = context.extractTestOcrModels()
        assertTrue(
            "OCR models failed to load",
            testedDetector.loadTextDetectionModels(detectModelPath, recognitionModels),
        )
    }

    @After
    fun tearDown() {
        testedDetector.close()
    }

    @Test
    fun detection_Text_RemainsAccurateWhenLargeAreaIsDownscaled() {
        val bitmap = createLargeTextBitmap()
        testedDetector.setScreenBitmap(bitmap, "")

        val result = testedDetector.detectText(
            conditionText = TEST_TEXT,
            recognitionModelId = LATIN_MODEL_ID,
            detectionArea = Rect(0, 0, bitmap.width, bitmap.height),
            threshold = TEXT_MATCH_THRESHOLD,
        )

        assertTrue("Text was not recognized after large-area downscaling", result.isDetected)
    }

    @Test
    fun detection_Text_UsesConfiguredFuzzyThreshold() {
        val bitmap = createLargeTextBitmap()
        testedDetector.setScreenBitmap(bitmap, "")

        val result = testedDetector.detectText(
            conditionText = "PLAX",
            recognitionModelId = LATIN_MODEL_ID,
            detectionArea = Rect(0, 0, bitmap.width, bitmap.height),
            threshold = 70,
        )

        assertTrue("A 75% text match should satisfy a 70% threshold", result.isDetected)
    }

    @Test
    fun detection_Text_DoesNotBypassConfiguredFuzzyThreshold() {
        val bitmap = createLargeTextBitmap()
        testedDetector.setScreenBitmap(bitmap, "")

        val result = testedDetector.detectText(
            conditionText = "PLAX",
            recognitionModelId = LATIN_MODEL_ID,
            detectionArea = Rect(0, 0, bitmap.width, bitmap.height),
            threshold = 80,
        )

        assertFalse("A 75% text match must not satisfy an 80% threshold", result.isDetected)
    }

    @Test
    fun detection_Text_RecognizesOutlinedLowContrastGameText() {
        val bitmap = createOutlinedGameTextBitmap()
        testedDetector.setScreenBitmap(bitmap, "")

        val result = testedDetector.detectText(
            conditionText = OUTLINED_TEXT,
            recognitionModelId = LATIN_MODEL_ID,
            detectionArea = Rect(0, 0, bitmap.width, bitmap.height),
            threshold = TEXT_MATCH_THRESHOLD,
        )

        assertTrue("Outlined low-contrast game text was not recognized", result.isDetected)
    }

    private fun createLargeTextBitmap(): Bitmap =
        Bitmap.createBitmap(1440, 800, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(235, 239, 245))

            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(28, 39, 58)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(340f, 260f, 1100f, 540f, 32f, 32f, panelPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 96f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(TEST_TEXT, 720f, 435f, textPaint)
        }

    private fun createOutlinedGameTextBitmap(): Bitmap =
        Bitmap.createBitmap(520, 110, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(72, 84, 91))

            val decorationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(88, 103, 109)
                strokeWidth = 9f
            }
            for (x in -80..600 step 70) {
                canvas.drawLine(x.toFloat(), 0f, (x + 90).toFloat(), 110f, decorationPaint)
            }

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 62f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            textPaint.apply {
                color = Color.rgb(45, 52, 57)
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
            canvas.drawText(OUTLINED_TEXT, 260f, 78f, textPaint)
            textPaint.apply {
                color = Color.rgb(164, 175, 178)
                style = Paint.Style.FILL
            }
            canvas.drawText(OUTLINED_TEXT, 260f, 78f, textPaint)
        }

    private companion object {
        const val TEST_TEXT = "PLAY"
        const val OUTLINED_TEXT = "QUEST"
        const val LATIN_MODEL_ID = "latin"
        const val TEXT_MATCH_THRESHOLD = 70
    }
}
