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
import com.buzbuz.smartautoclicker.core.detection.data.NumberTestCase
import com.buzbuz.smartautoclicker.core.detection.data.TestImage
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import com.buzbuz.smartautoclicker.core.detection.utils.loadTestBitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class NumberMatcherTests {

    private lateinit var context: Context
    private lateinit var testedDetector: ImageDetector
    private lateinit var screenBitmap: Bitmap

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testedDetector = NativeDetector.newInstance()
            ?: throw IllegalStateException("Can't instantiate detector for tests")

        testedDetector.init()

        val (detectModelPath, recognitionModels) = context.extractTestOcrModels()
        val modelsLoaded = testedDetector.loadTextDetectionModels(detectModelPath, recognitionModels)
        assertTrue("OCR models failed to load", modelsLoaded)

        screenBitmap = context.loadTestBitmap(TestImage.NumberConditionsScreen)
        testedDetector.setScreenBitmap(screenBitmap, "")
    }

    @After
    fun tearDown() {
        testedDetector.close()
    }

    @Test
    fun detection_Number_42_Auto() {
        assertNumberDetected(TestImage.NumberConditionsScreen.numberTestCases[0])
    }

    @Test
    fun detection_Number_42dot5_DotDecimal() {
        assertNumberDetected(TestImage.NumberConditionsScreen.numberTestCases[1])
    }

    @Test
    fun detection_Number_42comma5_CommaDecimal() {
        assertNumberDetected(TestImage.NumberConditionsScreen.numberTestCases[2])
    }

    @Test
    fun detection_Number_42dot588_DotDecimal() {
        assertNumberDetected(TestImage.NumberConditionsScreen.numberTestCases[3])
    }

    @Test
    fun detection_Number_42comma588_CommaDecimal() {
        assertNumberDetected(TestImage.NumberConditionsScreen.numberTestCases[4])
    }

    @Test
    fun detection_Number_1dot234dot567comma890_CommaDecimal() {
        assertNumberDetected(TestImage.NumberConditionsScreen.numberTestCases[5])
    }

    @Test
    fun detection_Number_1comma234comma567dot890_DotDecimal() {
        assertNumberDetected(TestImage.NumberConditionsScreen.numberTestCases[6])
    }

    @Test
    fun detection_Number_SmallOutlined2_OnComplexBackground() {
        assertNumberDetectedInBitmap(createGameCounterBitmap("2"), expectedValue = 2.0)
    }

    @Test
    fun detection_Number_SmallOutlined5_OnComplexBackground() {
        assertNumberDetectedInBitmap(createGameCounterBitmap("5"), expectedValue = 5.0)
    }

    @Test
    fun detection_NoNumber_OnComplexGameIcon() {
        val bitmap = createGameCounterBitmap(value = null)
        testedDetector.setScreenBitmap(bitmap, "")

        val result = testedDetector.detectNumber(
            detectionArea = Rect(0, 0, bitmap.width, bitmap.height),
            threshold = 0,
            numberFormatType = NumberFormatType.AUTO,
        )

        assertFalse("A game icon without a counter should not produce a number", result.isDetected)
    }

    private fun assertNumberDetected(testCase: NumberTestCase) {
        val result = testedDetector.detectNumber(
            detectionArea = testCase.detectionArea,
            threshold = 0,
            numberFormatType = testCase.numberFormatType,
        )

        assertTrue("Number not detected in area ${testCase.detectionArea}", result.isDetected)
        assertNotNull("numberDetected is null for area ${testCase.detectionArea}", result.numberDetected)
        assertEquals(
            "Wrong value detected for area ${testCase.detectionArea}",
            testCase.expectedValue,
            result.numberDetected!!,
            DETECTION_NUMBER_DELTA,
        )
    }

    private fun assertNumberDetectedInBitmap(bitmap: Bitmap, expectedValue: Double) {
        testedDetector.setScreenBitmap(bitmap, "")

        val result = testedDetector.detectNumber(
            detectionArea = Rect(0, 0, bitmap.width, bitmap.height),
            threshold = 0,
            numberFormatType = NumberFormatType.AUTO,
        )

        assertTrue("Number not detected in synthetic game counter sample", result.isDetected)
        assertNotNull("numberDetected is null for synthetic game counter sample", result.numberDetected)
        assertEquals(
            "Wrong value detected for synthetic game counter sample",
            expectedValue,
            result.numberDetected!!,
            DETECTION_NUMBER_DELTA,
        )
    }

    private fun createGameCounterBitmap(value: String?): Bitmap =
        Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(92, 73, 58))

            val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(55, 52, 48)
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            canvas.drawRoundRect(7f, 7f, 133f, 133f, 8f, 8f, framePaint)

            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 96, 130)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(68f, 66f, 45f, iconPaint)
            iconPaint.apply {
                color = Color.rgb(120, 45, 95)
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
            canvas.drawLine(35f, 91f, 97f, 42f, iconPaint)
            canvas.drawLine(45f, 105f, 109f, 58f, iconPaint)

            if (value != null) {
                val counterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 27f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                counterPaint.apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }
                canvas.drawText(value, 116f, 119f, counterPaint)
                counterPaint.apply {
                    color = Color.rgb(185, 181, 176)
                    style = Paint.Style.FILL
                }
                canvas.drawText(value, 116f, 119f, counterPaint)
            }
        }

    private companion object {
        const val DETECTION_NUMBER_DELTA = 0.001
    }
}
