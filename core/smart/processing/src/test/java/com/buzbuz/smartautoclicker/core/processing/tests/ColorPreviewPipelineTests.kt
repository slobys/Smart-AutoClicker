/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.tests

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.processing.data.processor.ConditionsVerifier
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScreenConditionScalingInfo
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingListener
import com.buzbuz.smartautoclicker.core.processing.domain.model.ProcessedConditionResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorPreviewPipelineTests {
    private val area = Rect(10, 10, 40, 40)
    private val condition = ScreenCondition.Color(Identifier(databaseId = 1), Identifier(databaseId = 1),
        "Color", 20, true, 0, Color.RED, area, true)

    @Test fun listenerGetsOwnedPreviewFromTheSameVerificationFrame() = runTest {
        val frame = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        frame.setPixel(18, 19, Color.RED)
        val detector = mockk<ImageDetector>()
        val scaling = mockk<ScalingManager>()
        val listener = mockk<SmartProcessingListener>(relaxed = true)
        every { scaling.getScreenConditionScalingInfo(condition) } returns ScreenConditionScalingInfo.Color(condition, area)
        every { scaling.scaleUpDetectionResult(any()) } answers { Point(firstArg<Point>()) }
        every { detector.detectColor(Color.RED, area, 20, true) } returns
            DetectionResult(true, 1.0, Point(18, 19), Point(1, 1))
        var frameReads = 0
        val verifier = ConditionsVerifier(mockk(), detector, scaling, { _, _, _ -> null }, listener,
            colorPreviewFrame = { frameReads++; frame })
        verifier.onScreenFrameStarted()
        verifier.verifyConditions(AND, listOf(condition))
        val result = slot<ProcessedConditionResult.Screen>()
        verify(exactly = 1) { listener.onScreenConditionProcessingCompleted(capture(result)) }
        assertEquals(1, frameReads)
        assertEquals(Point(18, 19), result.captured.colorPreview!!.point)
        assertEquals(Color.RED, result.captured.colorPreview!!.color)
        assertTrue(result.captured.isFulfilled)
        frame.eraseColor(Color.GREEN)
        assertEquals(Color.RED, result.captured.colorPreview!!.color)
    }

    @Test fun ordinaryVerificationDoesNotCreatePreviewsOrChangeDetection() = runTest {
        val detector = mockk<ImageDetector>()
        val scaling = mockk<ScalingManager>()
        val listener = mockk<SmartProcessingListener>(relaxed = true)
        every { scaling.getScreenConditionScalingInfo(condition) } returns ScreenConditionScalingInfo.Color(condition, area)
        every { scaling.scaleUpDetectionResult(any()) } answers { Point(firstArg<Point>()) }
        every { detector.detectColor(any(), any(), any(), any()) } returns DetectionResult(false, 0.0)
        val verifier = ConditionsVerifier(mockk(), detector, scaling, { _, _, _ -> null }, listener)
        verifier.verifyConditions(AND, listOf(condition))
        val result = slot<ProcessedConditionResult.Screen>()
        verify { listener.onScreenConditionProcessingCompleted(capture(result)) }
        assertNull(result.captured.colorPreview)
        assertFalse(result.captured.haveBeenDetected)
        assertFalse(result.captured.isFulfilled)
    }
}
