package com.buzbuz.smartautoclicker.core.processing.tests

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.OR
import com.buzbuz.smartautoclicker.core.domain.model.action.Click
import com.buzbuz.smartautoclicker.core.domain.model.action.ToggleEvent
import com.buzbuz.smartautoclicker.core.domain.model.action.toggleevent.EventToggle
import com.buzbuz.smartautoclicker.core.common.actions.AndroidGestureResult
import android.graphics.Point
import com.buzbuz.smartautoclicker.core.domain.model.action.Pause
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.condition.TriggerCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.processor.ConditionsVerifier
import com.buzbuz.smartautoclicker.core.processing.data.processor.ScenarioProcessor
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScreenConditionScalingInfo
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ReliabilityRegressionTests {
    private val eventId = Identifier(databaseId = 1)
    private val condition = ScreenCondition.Color(
        Identifier(databaseId = 2), eventId, "目标", 10, true, 0, 0xffffff, Rect(0, 0, 20, 20),
    )
    private val event = ScreenEvent(
        eventId, Identifier(databaseId = 3), "测试", AND, emptyList(), listOf(condition), true, 0,
        cooldownMs = 0, keepDetecting = false,
    )
    private val wait = Pause(
        Identifier(databaseId = 4), eventId, "等待", 0, 1_000,
        waitMode = Pause.WaitMode.TARGET_DISAPPEARS, waitTargetEventId = eventId, confirmationFrames = 3,
    )

    @Test fun missingFrames_neverProveDisappearance() = runTest {
        val processor = processor({ null }, { currentTime })
        assertEquals(ActionExecutionResult.TimedOut(1_000), processor.executeSmartWait(event, wait))
    }

    @Test fun threeRealNegativeFrames_proveDisappearance() = runTest {
        val frame = mock<Bitmap>()
        val processor = processor({ frame }, { currentTime })
        assertEquals(ActionExecutionResult.Success, processor.executeSmartWait(event, wait))
        assertEquals(400L, currentTime)
    }

    @Test fun missingFrame_breaksConsecutiveConfirmation() = runTest {
        val frame = mock<Bitmap>()
        val frames = ArrayDeque(listOf(frame, frame, null, frame, frame))
        val processor = processor({ if (frames.isEmpty()) null else frames.removeFirst() }, { currentTime })
        assertEquals(ActionExecutionResult.TimedOut(1_000), processor.executeSmartWait(event, wait))
    }

    @Test fun unavailableCondition_neverProvesDisappearance() = runTest {
        val processor = processor({ mock<Bitmap>() }, { currentTime }, validScaling = false)
        assertEquals(ActionExecutionResult.TimedOut(1_000), processor.executeSmartWait(event, wait))
    }

    @Test fun missingCounterOperand_isInvalidNotZero() = runTest {
        val state = mock<ProcessingState>()
        whenever(state.getCounterValue("金币")).thenReturn(100.0)
        whenever(state.getCounterValue("预算")).thenReturn(null)
        val verifier = ConditionsVerifier(state, mock(), mock(), { _, _, _ -> null })
        val counter = TriggerCondition.OnCounterCountReached(
            Identifier(databaseId = 5), eventId, "比较", "金币", ComparisonOperation.GREATER_OR_EQUALS,
            CounterOperationValue.Counter("预算"),
        )
        val result = verifier.verifyConditions(AND, listOf(counter))
        assertEquals(false, result.fulfilled)
        assertEquals("计数器不存在：预算", result.errorReason)
    }

    @Test fun missingNumberComparisonCounter_isInvalidBeforeOcr() = runTest {
        val detector = mock<ImageDetector>()
        val state = mock<ProcessingState>()
        whenever(state.getCounterValue("预算")).thenReturn(null)
        val verifier = ConditionsVerifier(state, detector, mock(), { _, _, _ -> null })
        val number = ScreenCondition.Number(
            Identifier(databaseId = 5), eventId, "金币", 10, true, 0, Rect(0, 0, 20, 20),
            ComparisonOperation.GREATER_OR_EQUALS, CounterOperationValue.Counter("预算"),
        )
        val result = verifier.verifyConditions(AND, listOf(number))
        assertEquals(false, result.fulfilled)
        assertEquals("计数器不存在：预算", result.errorReason)
        verifyNoInteractions(detector)
    }

    @Test fun unreadableOrAlternative_doesNotHideValidMatch() = runTest {
        val invalid = condition.copy(id = Identifier(databaseId = 7))
        val scaling = mock<ScalingManager>()
        whenever(scaling.getScreenConditionScalingInfo(condition)).thenReturn(ScreenConditionScalingInfo.Color(condition, condition.detectionArea))
        val detector = mock<ImageDetector>()
        whenever(detector.detectColor(any(), any(), any())).thenReturn(DetectionResult(true))
        val verifier = ConditionsVerifier(mock(), detector, scaling, { _, _, _ -> null })
        val result = verifier.verifyConditions(OR, listOf(invalid, condition))
        assertEquals(true, result.fulfilled)
        assertNull(result.errorReason)
    }

    @Test fun subflowAfterParentClick_usesNewFrameNotTriggerFrame() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val parentFrame = mock<Bitmap>()
            val childFrame = mock<Bitmap>()
            val childId = Identifier(databaseId = 8)
            val childCondition = condition.copy(id = Identifier(databaseId = 9), eventId = childId)
            val fixedClick = Click(Identifier(databaseId = 10), eventId, "打开", 0, 20,
                Click.PositionType.USER_SELECTED, Point(100, 100))
            val detectedClick = Click(Identifier(databaseId = 11), childId, "点击目标", 0, 20,
                Click.PositionType.ON_DETECTED_CONDITION, clickOnConditionId = childCondition.id)
            val call = ToggleEvent(Identifier(databaseId = 12), eventId, "子流程", 1,
                eventToggles = listOf(EventToggle(Identifier(databaseId = 13), Identifier(databaseId = 12), childId, ToggleEvent.ToggleType.EXECUTE_ONCE)))
            val child = event.copy(id = childId, enabledOnStart = false, conditions = listOf(childCondition), actions = listOf(detectedClick))
            val parent = event.copy(actions = listOf(fixedClick, call))
            val detector = mock<ImageDetector>()
            val scaling = mock<ScalingManager>()
            listOf(condition, childCondition).forEach {
                whenever(scaling.getScreenConditionScalingInfo(it)).thenReturn(ScreenConditionScalingInfo.Color(it, it.detectionArea))
            }
            whenever(scaling.scaleUpDetectionResult(any())).thenAnswer { it.getArgument<Point>(0) }
            whenever(detector.detectColor(any(), any(), any())).thenReturn(DetectionResult(true, 100.0, Point(5, 5), Point(10, 10)))
            val android = mock<AndroidActionExecutor>()
            whenever(android.dispatchGesture(any())).thenReturn(AndroidGestureResult.COMPLETED)
            val processor = ScenarioProcessor("test", detector, scaling, false, listOf(parent, child), emptyList(), emptyList(),
                bitmapSupplier = { _, _, _ -> null }, screenFrameSupplier = { childFrame }, androidExecutor = android,
                onStopRequested = {}, progressListener = null, monotonicTimeMs = { currentTime })
            processor.process(parentFrame)
            inOrder(detector, android).apply {
                verify(detector).setScreenBitmap(parentFrame, "test")
                verify(android).dispatchGesture(any())
                verify(detector).releaseScreenBitmap(parentFrame)
                verify(detector).setScreenBitmap(childFrame, "test")
                verify(detector).releaseScreenBitmap(childFrame)
                verify(android).dispatchGesture(any())
            }
        } finally { Dispatchers.resetMain() }
    }

    private fun processor(
        frames: suspend () -> Bitmap?, clock: () -> Long, validScaling: Boolean = true,
    ): ScenarioProcessor {
        val detector = mock<ImageDetector>()
        val scaling = mock<ScalingManager>()
        if (validScaling) whenever(scaling.getScreenConditionScalingInfo(condition))
            .thenReturn(ScreenConditionScalingInfo.Color(condition, condition.detectionArea))
        whenever(detector.detectColor(any(), any(), any())).thenReturn(DetectionResult(false))
        return ScenarioProcessor(
            "test", detector, scaling, false, listOf(event), emptyList(), emptyList(),
            bitmapSupplier = { _, _, _ -> null }, screenFrameSupplier = frames,
            androidExecutor = mock<AndroidActionExecutor>(), onStopRequested = {}, progressListener = null,
            monotonicTimeMs = clock,
        )
    }
}
