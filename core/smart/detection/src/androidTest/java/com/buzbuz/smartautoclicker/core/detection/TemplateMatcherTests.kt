/*
 * Copyright (C) 2025 Kevin Buzeau
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
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.data.TestResults
import com.buzbuz.smartautoclicker.core.detection.data.TestImage
import com.buzbuz.smartautoclicker.core.detection.data.getDetectionExactArea
import com.buzbuz.smartautoclicker.core.detection.data.getBiggerThanScreenDetectionArea
import com.buzbuz.smartautoclicker.core.detection.data.getInsideButTallerThanDetectionArea
import com.buzbuz.smartautoclicker.core.detection.data.getInsideButWiderThanDetectionArea
import com.buzbuz.smartautoclicker.core.detection.data.getValidCustomDetectionArea
import com.buzbuz.smartautoclicker.core.detection.data.isValid
import com.buzbuz.smartautoclicker.core.detection.utils.TEST_DETECTION_THRESHOLD_ALL
import com.buzbuz.smartautoclicker.core.detection.utils.TEST_DETECTION_THRESHOLD_STANDARD
import com.buzbuz.smartautoclicker.core.detection.utils.loadTestBitmap
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@LargeTest
@RunWith(AndroidJUnit4::class)
class TemplateMatcherTests {

    private lateinit var context: Context
    private lateinit var testedDetector: ImageDetector

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testedDetector = NativeDetector.newInstance() ?:
            throw IllegalStateException("Can't instantiate detector for tests")

        testedDetector.init()
    }

    @After
    fun tearDown() {
        testedDetector.close()
    }

    @Test
    fun detection_Screen1_Condition1_WholeScreen() {
        // Given
        val screenImage = TestImage.Screen.TutorialWithTarget
        val conditionImage = TestImage.Condition.TutorialTargetBlue

        // When
        val result = testedDetector.executeImageDetectionTest(
            context = context,
            screenImage = screenImage,
            conditionImage = conditionImage,
            area = Rect(0, 0, screenImage.size.x, screenImage.size.y),
        )

        // Then
        assertTrue("Image detection failed", result?.isValid() == true)
    }

    @Test
    fun detection_Screen1_Condition1_InArea() {
        // Given
        val screenImage = TestImage.Screen.TutorialWithTarget
        val conditionImage = TestImage.Condition.TutorialTargetBlue
        val validArea = getValidCustomDetectionArea(screenImage, conditionImage)

        // When
        val result = testedDetector.executeImageDetectionTest(
            context = context,
            screenImage = screenImage,
            conditionImage = conditionImage,
            area = validArea,
        )

        // Then
        assertTrue("Image detection failed", result?.isValid() == true)
    }

    @Test
    fun detection_Screen1_Condition1_Exact() {
        // Given
        val screenImage = TestImage.Screen.TutorialWithTarget
        val conditionImage = TestImage.Condition.TutorialTargetBlue
        val exactArea = getDetectionExactArea(screenImage, conditionImage)

        // When
        val result = testedDetector.executeImageDetectionTest(
            context = context,
            screenImage = screenImage,
            conditionImage = conditionImage,
            area = exactArea,
        )

        // Then
        assertTrue("Image detection failed", result?.isValid() == true)
    }

    @Test
    fun detection_Detection_Area_Bigger_Than_Screen() {
        // Given
        val screenImage = TestImage.Screen.TutorialWithTarget
        val conditionImage = TestImage.Condition.TutorialTargetBlue
        val outOfScreenArea = getBiggerThanScreenDetectionArea(screenImage)

        // When
        val result = testedDetector.executeImageDetectionTest(
            context = context,
            screenImage = screenImage,
            conditionImage = conditionImage,
            area = outOfScreenArea,
        )

        // Then
        assertTrue("Image detection failed", result?.isValid() == false)
    }

    @Test
    fun detection_Detection_Area_Inside_But_Wider_Than_Screen() {
        // Given
        val screenImage = TestImage.Screen.TutorialWithTarget
        val conditionImage = TestImage.Condition.TutorialTargetBlue
        val outOfScreenArea = getInsideButWiderThanDetectionArea(screenImage)

        // When
        val result = testedDetector.executeImageDetectionTest(
            context = context,
            screenImage = screenImage,
            conditionImage = conditionImage,
            area = outOfScreenArea,
        )

        // Then
        assertTrue("Image detection failed", result?.isValid() == false)
    }

    @Test
    fun detection_Detection_Area_Inside_But_Taller_Than_Screen() {
        // Given
        val screenImage = TestImage.Screen.TutorialWithTarget
        val conditionImage = TestImage.Condition.TutorialTargetBlue
        val outOfScreenArea = getInsideButTallerThanDetectionArea(screenImage)

        // When
        val result = testedDetector.executeImageDetectionTest(
            context = context,
            screenImage = screenImage,
            conditionImage = conditionImage,
            area = outOfScreenArea,
        )

        // Then
        assertTrue("Image detection failed", result?.isValid() == false)
    }

    @Test
    fun detection_ColorCondition_ColorScreen_IsDetected() {
        // Given
        val screenImage = TestImage.Screen.TutorialWithTarget
        val conditionImage = TestImage.Condition.TutorialTargetBlue
        val screenBitmap = context.loadTestBitmap(screenImage)
        val conditionBitmap = context.loadTestBitmap(conditionImage)

        // When
        testedDetector.setScreenBitmap(screenBitmap, "")
        val result = testedDetector.detectImage(
            conditionBitmap = conditionBitmap,
            conditionWidth = conditionBitmap.width,
            conditionHeight = conditionBitmap.height,
            detectionArea = Rect(0, 0, screenImage.size.x, screenImage.size.y),
            threshold = TEST_DETECTION_THRESHOLD_STANDARD,
        )

        // Then
        assertTrue("Color condition should match color screen", result.isDetected)
    }

    @Test
    fun detection_GrayscaleCondition_ColorScreen_IsNotDetected() {
        // Given
        val screenImage = TestImage.Screen.TutorialWithTarget
        val conditionImage = TestImage.Condition.TutorialTargetGrayscale
        val screenBitmap = context.loadTestBitmap(screenImage)
        val conditionBitmap = context.loadTestBitmap(conditionImage)

        // When
        testedDetector.setScreenBitmap(screenBitmap, "")
        val result = testedDetector.detectImage(
            conditionBitmap = conditionBitmap,
            conditionWidth = conditionBitmap.width,
            conditionHeight = conditionBitmap.height,
            detectionArea = Rect(0, 0, screenImage.size.x, screenImage.size.y),
            threshold = TEST_DETECTION_THRESHOLD_STANDARD,
        )

        // Then
        assertFalse("Grayscale condition should not match color screen", result.isDetected)
    }

    @Test
    fun detection_SameGrayscaleAndAverageColor_UsesPixelColorLayout() {
        // Given: both icons have exactly the same grayscale structure and average colors, but the
        // first one has its red/green pixels swapped. Mean-color validation can't distinguish them.
        val conditionBitmap = createPatternBitmap(swappedColors = false)
        val decoyBitmap = createPatternBitmap(swappedColors = true)
        val screenBitmap = Bitmap.createBitmap(80, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(24, 24, 24))
            copyBitmap(decoyBitmap, left = 4, top = 4)
            copyBitmap(conditionBitmap, left = 48, top = 4)
        }

        // When
        testedDetector.setScreenBitmap(screenBitmap, "")
        val result = testedDetector.detectImage(
            conditionBitmap = conditionBitmap,
            conditionWidth = conditionBitmap.width,
            conditionHeight = conditionBitmap.height,
            detectionArea = Rect(0, 0, screenBitmap.width, screenBitmap.height),
            threshold = TEST_DETECTION_THRESHOLD_STANDARD,
        )

        // Then: the spatial color check rejects the decoy and returns the real icon.
        assertTrue("Image detection should find the real color layout", result.isDetected)
        assertTrue(
            "Image detection selected the grayscale-identical decoy at ${result.position}",
            result.position == Point(60, 16),
        )
    }

    @Test
    fun detection_MultipleValidCandidates_SelectsBestCompositeScore() {
        // Given: the first candidate differs only by a small uniform brightness offset. Both pass
        // the shape and color gates, but the exact candidate has the better composite score.
        val conditionBitmap = createScoringPatternBitmap(brightnessOffset = 0)
        val brightnessShiftedDecoy = createScoringPatternBitmap(brightnessOffset = 8)
        val screenBitmap = Bitmap.createBitmap(80, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 20, 20))
            copyBitmap(brightnessShiftedDecoy, left = 4, top = 4)
            copyBitmap(conditionBitmap, left = 48, top = 4)
        }

        // When
        testedDetector.setScreenBitmap(screenBitmap, "")
        val result = testedDetector.detectImage(
            conditionBitmap = conditionBitmap,
            conditionWidth = conditionBitmap.width,
            conditionHeight = conditionBitmap.height,
            detectionArea = Rect(0, 0, screenBitmap.width, screenBitmap.height),
            threshold = TEST_DETECTION_THRESHOLD_STANDARD,
        )

        // Then
        assertTrue("Image detection should find a candidate", result.isDetected)
        assertTrue(
            "Image detection selected the merely acceptable candidate at ${result.position}",
            result.position == Point(60, 16),
        )
    }

    @Test
    fun detection_UniformTemplate_IsRejectedAsLowInformation() {
        // Given: normalized correlation is undefined for a constant template and can otherwise
        // produce a false perfect match.
        val conditionBitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 120, 160))
        }
        val screenBitmap = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 120, 160))
        }

        // When
        testedDetector.setScreenBitmap(screenBitmap, "")
        val result = testedDetector.detectImage(
            conditionBitmap = conditionBitmap,
            conditionWidth = conditionBitmap.width,
            conditionHeight = conditionBitmap.height,
            detectionArea = Rect(0, 0, screenBitmap.width, screenBitmap.height),
            threshold = TEST_DETECTION_THRESHOLD_STANDARD,
        )

        // Then
        assertFalse("Uniform templates should not be accepted as image conditions", result.isDetected)
    }

    @Test
    fun detection_HorizontalMotionBlur_UsesTolerantFallback() {
        assertMotionBlurredConditionDetected(horizontal = true)
    }

    @Test
    fun detection_VerticalMotionBlur_UsesTolerantFallback() {
        assertMotionBlurredConditionDetected(horizontal = false)
    }

    @Test
    fun detection_MotionBlurredColorDecoy_IsRejected() {
        val conditionBitmap = createPatternBitmap(swappedColors = false)
        val blurredDecoy = createMotionBlurredBitmap(
            source = createPatternBitmap(swappedColors = true),
            horizontal = true,
        )
        val screenBitmap = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(24, 24, 24))
            copyBitmap(blurredDecoy, left = 20, top = 8)
        }

        testedDetector.setScreenBitmap(screenBitmap, "")
        val result = testedDetector.detectImage(
            conditionBitmap = conditionBitmap,
            conditionWidth = conditionBitmap.width,
            conditionHeight = conditionBitmap.height,
            detectionArea = Rect(0, 0, screenBitmap.width, screenBitmap.height),
            threshold = 4,
        )

        assertFalse("Motion fallback should reject a different color layout", result.isDetected)
    }

    private fun assertMotionBlurredConditionDetected(horizontal: Boolean) {
        val conditionBitmap = createScoringPatternBitmap(brightnessOffset = 0)
        val blurredBitmap = createMotionBlurredBitmap(conditionBitmap, horizontal)
        val screenBitmap = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 20, 20))
            copyBitmap(blurredBitmap, left = 28, top = 12)
        }

        testedDetector.setScreenBitmap(screenBitmap, "")
        val result = testedDetector.detectImage(
            conditionBitmap = conditionBitmap,
            conditionWidth = conditionBitmap.width,
            conditionHeight = conditionBitmap.height,
            detectionArea = Rect(0, 0, screenBitmap.width, screenBitmap.height),
            threshold = 4,
        )

        assertTrue(
            "Image detection should tolerate ${if (horizontal) "horizontal" else "vertical"} motion blur",
            result.isDetected,
        )
        assertTrue("Motion-blurred target position is wrong: ${result.position}", result.position == Point(40, 24))
    }

    private fun ImageDetector.executeImageDetectionTest(
        context: Context,
        screenImage: TestImage.Screen,
        conditionImage: TestImage.Condition,
        area: Rect,
    ): TestResults? = conditionImage.expectedResults[screenImage]?.let { expectedResults ->
         val screenBitmap = context.loadTestBitmap(screenImage)
            val conditionBitmap = context.loadTestBitmap(conditionImage)

            setScreenBitmap(screenBitmap, "")

            val results = detectImage(
                conditionBitmap = conditionBitmap,
                conditionWidth = conditionBitmap.width,
                conditionHeight = conditionBitmap.height,
                detectionArea = area,
                threshold = TEST_DETECTION_THRESHOLD_ALL,
            )

            TestResults(
                expectedCenterPosition = expectedResults.centerPosition,
                actualCenterPosition = Point(results.position),
                expectedConfidence = expectedResults.quality,
                actualConfidence = results.confidenceRate,
            )
        }

    private fun createPatternBitmap(swappedColors: Boolean): Bitmap {
        val size = 24
        val pixels = IntArray(size * size)
        val red = Color.rgb(255, 0, 0)
        // OpenCV RGBA-to-gray maps this green and pure red to the same 8-bit gray value.
        val equalGrayGreen = Color.rgb(0, 130, 0)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val isBorder = x < 2 || y < 2 || x >= size - 2 || y >= size - 2
                pixels[y * size + x] = if (isBorder) {
                    if ((x + y) % 2 == 0) Color.WHITE else Color.BLACK
                } else {
                    val useRed = ((x / 4 + y / 4) % 2 == 0) xor swappedColors
                    if (useRed) red else equalGrayGreen
                }
            }
        }

        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun createScoringPatternBitmap(brightnessOffset: Int): Bitmap {
        val size = 24
        val pixels = IntArray(size * size)

        fun shiftedColor(red: Int, green: Int, blue: Int): Int = Color.rgb(
            red + brightnessOffset,
            green + brightnessOffset,
            blue + brightnessOffset,
        )

        for (y in 0 until size) {
            for (x in 0 until size) {
                val color = when {
                    x < 3 || y < 3 || x >= size - 3 || y >= size - 3 ->
                        shiftedColor(70, 90, 130)
                    (x / 4 + y / 4) % 2 == 0 ->
                        shiftedColor(180, 45, 55)
                    else ->
                        shiftedColor(45, 135, 70)
                }
                pixels[y * size + x] = color
            }
        }

        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun createMotionBlurredBitmap(source: Bitmap, horizontal: Boolean): Bitmap {
        val sourcePixels = IntArray(source.width * source.height)
        val blurredPixels = IntArray(sourcePixels.size)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                var red = 0
                var green = 0
                var blue = 0
                var sampleCount = 0

                for (offset in -2..2) {
                    val sampleX = if (horizontal) x + offset else x
                    val sampleY = if (horizontal) y else y + offset
                    if (sampleX !in 0 until source.width || sampleY !in 0 until source.height) continue

                    val color = sourcePixels[sampleY * source.width + sampleX]
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    sampleCount++
                }

                blurredPixels[y * source.width + x] = Color.rgb(
                    red / sampleCount,
                    green / sampleCount,
                    blue / sampleCount,
                )
            }
        }

        return Bitmap.createBitmap(
            blurredPixels,
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
        )
    }

    private fun Bitmap.copyBitmap(source: Bitmap, left: Int, top: Int) {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        setPixels(pixels, 0, source.width, left, top, source.width, source.height)
    }
}


