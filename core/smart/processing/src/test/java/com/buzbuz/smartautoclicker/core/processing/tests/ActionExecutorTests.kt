/*
 * Copyright (C) 2024 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.core.processing.tests

import android.accessibilityservice.GestureDescription
import android.graphics.Point
import android.graphics.Rect
import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.common.actions.AndroidGestureResult
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.OR
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.domain.model.action.Click
import com.buzbuz.smartautoclicker.core.domain.model.action.ChangeCounter
import com.buzbuz.smartautoclicker.core.domain.model.action.Pause
import com.buzbuz.smartautoclicker.core.domain.model.action.Swipe
import com.buzbuz.smartautoclicker.core.domain.model.action.ToggleEvent
import com.buzbuz.smartautoclicker.core.domain.model.action.toggleevent.EventToggle
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.processor.ActionExecutor
import com.buzbuz.smartautoclicker.core.processing.data.processor.ConditionsResults
import com.buzbuz.smartautoclicker.core.processing.data.processor.getConditionClickPosition
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.utils.anyNotNull
import com.buzbuz.smartautoclicker.core.processing.domain.model.ProcessedConditionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.isFailure

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as mockWhen
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

import org.robolectric.annotation.Config

/** Test the [ActionExecutor] class. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ActionExecutorTests {

    private companion object {
        private val TEST_EVENT_ID = Identifier(databaseId = 42L)
        private const val TEST_NAME = "Action name"
        private const val TEST_DURATION = 25L
        private const val TEST_X1 = 12
        private const val TEST_X2 = 24
        private const val TEST_Y1 = 88
        private const val TEST_Y2 = 76

        fun getNewDefaultEvent(operator: Int = OR, conditions: List<ScreenCondition> = emptyList(), actions: List<Action> = emptyList()) =
            ScreenEvent(TEST_EVENT_ID, Identifier(databaseId = 12L), "Name", operator, actions, conditions, true, 0, cooldownMs = 0, keepDetecting = false)

        fun getNewDefaultClickUserPos(id: Long, duration: Long = TEST_DURATION) =
            Click(Identifier(databaseId = id), TEST_EVENT_ID, TEST_NAME, 0, duration, Click.PositionType.USER_SELECTED, Point(
                TEST_X1, TEST_Y1
            ), null)
        fun getNewDefaultClickCondition(id: Long, conditionId: Long? = null) =
            Click(Identifier(databaseId = id), TEST_EVENT_ID, TEST_NAME, 1, TEST_DURATION, Click.PositionType.ON_DETECTED_CONDITION, null, conditionId?.let { Identifier(databaseId = conditionId) })
        fun getNewDefaultSwipe(id: Long) =
            Swipe(Identifier(databaseId = id), TEST_EVENT_ID, TEST_NAME, 2, TEST_DURATION, Point(TEST_X1, TEST_Y1), Point(
                TEST_X2, TEST_Y2
            ))
        fun getNewDefaultPause(id: Long) =
            Pause(Identifier(databaseId = id), TEST_EVENT_ID, TEST_NAME, 3, TEST_DURATION)

        fun getExecuteOnceAction(id: Long, targetEventId: Long) =
            ToggleEvent(
                id = Identifier(databaseId = id),
                eventId = TEST_EVENT_ID,
                name = TEST_NAME,
                priority = 0,
                eventToggles = listOf(
                    EventToggle(
                        id = Identifier(databaseId = id + 1_000),
                        actionId = Identifier(databaseId = id),
                        targetEventId = Identifier(databaseId = targetEventId),
                        toggleType = ToggleEvent.ToggleType.EXECUTE_ONCE,
                    )
                ),
            )

        fun getNewDefaultCondition(id: Long) =
            ScreenCondition.Image(Identifier(databaseId = id), TEST_EVENT_ID, TEST_NAME, 0, true, 10, "path", Rect(), EXACT, null)

        fun getClickableConditions(): List<ScreenCondition> = listOf(
            ScreenCondition.Color(
                Identifier(databaseId = 1L), TEST_EVENT_ID, TEST_NAME, 10, true, 0,
                0xFF0000, Rect(100, 200, 140, 260),
            ),
            ScreenCondition.Image(
                Identifier(databaseId = 2L), TEST_EVENT_ID, TEST_NAME, 10, true, 0,
                "path", Rect(200, 300, 260, 380), EXACT, null,
            ),
            ScreenCondition.Number(
                Identifier(databaseId = 3L), TEST_EVENT_ID, TEST_NAME, 10, true, 0,
                Rect(300, 400, 380, 500), ComparisonOperation.EQUALS, CounterOperationValue.Number(42.0),
            ),
            ScreenCondition.Text(
                Identifier(databaseId = 4L), TEST_EVENT_ID, TEST_NAME, 10, true, 0,
                "target", Rect(400, 500, 500, 620), OCRAlphabet.LATIN,
            ),
        )
    }

    @Mock private lateinit var mockAndroidExecutor: AndroidActionExecutor
    @Mock private lateinit var mockProcessingState: ProcessingState

    private lateinit var actionExecutor: ActionExecutor

    private fun assertActionGesture(gesture: GestureDescription) {
        assertEquals("Gesture should contains only one stroke", 1, gesture.strokeCount)
        gesture.getStroke(0).let { stroke ->
            assertEquals("Gesture duration is invalid", TEST_DURATION, stroke.duration)
            assertEquals("Gesture start time is invalid", 0, stroke.startTime)
        }
    }

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(StandardTestDispatcher())
        runBlocking { whenever(mockAndroidExecutor.dispatchGesture(any())).thenReturn(AndroidGestureResult.COMPLETED) }

        actionExecutor = ActionExecutor(mockAndroidExecutor, mockProcessingState, randomize = false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun noActions() = runTest {
        val event = getNewDefaultEvent()
        actionExecutor.executeActions(event, ConditionsResults())
        verify(mockAndroidExecutor, never()).dispatchGesture(anyNotNull())
    }

    @Test
    fun execute_oneClick_notOnCondition() = runTest {
        val clickAction = getNewDefaultClickUserPos(1)
        val event = getNewDefaultEvent(actions = listOf(clickAction))

        actionExecutor.executeActions(event, ConditionsResults())

        val gestureCaptor = argumentCaptor<GestureDescription>()
        verify(mockAndroidExecutor).dispatchGesture(gestureCaptor.capture())
        assertActionGesture(gestureCaptor.lastValue)
    }

    @Test
    fun execute_oneClick_onCondition_or() = runTest {
        val clickAction = getNewDefaultClickUserPos(1)

        val condition = getNewDefaultCondition(42L)
        val event = getNewDefaultEvent(
            OR,
            conditions = listOf(condition),
            actions = listOf(clickAction),
        )

        val results = ConditionsResults()
        results.addResult(
            conditionId = condition.getDatabaseId(),
            result = ProcessedConditionResult.Screen(
                isFulfilled = true,
                haveBeenDetected = true,
                condition = condition,
                position = Point(15, 15),
                size = Point(10, 10),
                confidenceRate = 100.0
            )
        )

        actionExecutor.executeActions(event, results)

        val gestureCaptor = argumentCaptor<GestureDescription>()
        verify(mockAndroidExecutor).dispatchGesture(gestureCaptor.capture())
        assertActionGesture(gestureCaptor.lastValue)
    }

    @Test
    fun execute_oneClick_onCondition_and() = runTest {
        val conditionValid = getNewDefaultCondition(42L)
        val conditionOther = getNewDefaultCondition(75L)
        val clickAction = getNewDefaultClickCondition(1, conditionValid.id.databaseId)

        val event = getNewDefaultEvent(
            AND,
            conditions = listOf(conditionValid, conditionOther),
            actions = listOf(clickAction),
        )
        val results = ConditionsResults()
        results.addResult(
            conditionId = conditionValid.getDatabaseId(),
            result = ProcessedConditionResult.Screen(
                isFulfilled = true,
                haveBeenDetected = true,
                condition = conditionValid,
                position = Point(15, 15),
                size = Point(10, 10),
                confidenceRate = 100.0
            )
        )
        results.addResult(
            conditionId = conditionOther.getDatabaseId(),
            result = ProcessedConditionResult.Screen(
                isFulfilled = true,
                haveBeenDetected = false,
                condition = conditionOther,
                position = Point(45, 45),
                size = Point(10, 10),
                confidenceRate = 98.0
            )
        )

        actionExecutor.executeActions(event, results)

        val gestureCaptor = argumentCaptor<GestureDescription>()
        verify(mockAndroidExecutor).dispatchGesture(gestureCaptor.capture())
        assertActionGesture(gestureCaptor.lastValue)
    }

    @Test
    fun conditionClickPosition_usesDetectedPositionForAllScreenConditionTypes() {
        val expectedPosition = Point(321, 654)

        getClickableConditions().forEach { condition ->
            val result = ProcessedConditionResult.Screen(
                isFulfilled = true,
                haveBeenDetected = true,
                condition = condition,
                position = expectedPosition,
                size = Point(20, 10),
                confidenceRate = 100.0,
            )

            assertEquals(condition::class.simpleName, expectedPosition, result.getConditionClickPosition())
        }
    }

    @Test
    fun conditionClickPosition_fallsBackToDetectionAreaForAllScreenConditionTypes() {
        getClickableConditions().forEach { condition ->
            val area = when (condition) {
                is ScreenCondition.Color -> condition.detectionArea
                is ScreenCondition.Image -> condition.detectionArea ?: condition.area
                is ScreenCondition.Number -> condition.detectionArea
                is ScreenCondition.Text -> condition.detectionArea
            }
            val result = ProcessedConditionResult.Screen(
                isFulfilled = true,
                haveBeenDetected = true,
                condition = condition,
                position = null,
                size = null,
                confidenceRate = 100.0,
            )

            assertEquals(
                condition::class.simpleName,
                Point(area.centerX(), area.centerY()),
                result.getConditionClickPosition(),
            )
        }
    }

    @Test
    fun conditionClickPosition_rejectsNegativeOrUndetectedConditions() {
        val condition = getClickableConditions().first()
        val negativeCondition = (condition as ScreenCondition.Color).copy(shouldBeDetected = false)
        val conditionWithoutClickableArea = getNewDefaultCondition(5L)

        assertNull(
            ProcessedConditionResult.Screen(
                isFulfilled = true,
                haveBeenDetected = true,
                condition = negativeCondition,
                position = Point(10, 20),
                size = Point(5, 5),
                confidenceRate = 100.0,
            ).getConditionClickPosition()
        )
        assertNull(
            ProcessedConditionResult.Screen(
                isFulfilled = false,
                haveBeenDetected = false,
                condition = condition,
                position = null,
                size = null,
                confidenceRate = 0.0,
            ).getConditionClickPosition()
        )
        assertNull(
            ProcessedConditionResult.Screen(
                isFulfilled = true,
                haveBeenDetected = true,
                condition = conditionWithoutClickableArea,
                position = null,
                size = null,
                confidenceRate = 100.0,
            ).getConditionClickPosition()
        )
    }

    @Test
    fun execute_oneSwipe() = runTest {
        val swipeAction = getNewDefaultSwipe(1)

        actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(swipeAction)),
            results = ConditionsResults(),
        )

        val gestureCaptor = argumentCaptor<GestureDescription>()
        verify(mockAndroidExecutor).dispatchGesture(gestureCaptor.capture())
        assertActionGesture(gestureCaptor.lastValue)
    }

    @Test
    fun execute_onePause() = runTest {
        val pause = getNewDefaultPause(1)

        // Execute the pause. As the handler is waiting to the finish the pause, we should stays in EXECUTING
        actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(pause)),
            results = ConditionsResults(),
        )

        // Only a pause, there should be no gestures
        verify(mockAndroidExecutor, never()).dispatchGesture(anyNotNull())
    }

    @Test
    fun execute_changeCounter_usesDetectedNumberResult() = runTest {
        val numberCondition = getClickableConditions().filterIsInstance<ScreenCondition.Number>().single()
        val changeCounter = ChangeCounter(
            id = Identifier(databaseId = 90L),
            eventId = TEST_EVENT_ID,
            name = TEST_NAME,
            priority = 0,
            counterName = "growth",
            operation = ChangeCounter.OperationType.SET,
            operationValue = CounterOperationValue.Number(0.0),
            detectedNumberConditionId = numberCondition.id,
        )
        val results = ConditionsResults().apply {
            addResult(
                numberCondition.getDatabaseId(),
                ProcessedConditionResult.Screen(
                    isFulfilled = true,
                    haveBeenDetected = true,
                    condition = numberCondition,
                    confidenceRate = 99.0,
                    position = Point(10, 10),
                    size = Point(20, 10),
                    numberDetected = 2.34,
                    numberComparisonFulfilled = true,
                )
            )
        }
        mockWhen(mockProcessingState.getCounterValue("growth")).thenReturn(0.0)

        actionExecutor.executeActions(
            event = getNewDefaultEvent(conditions = listOf(numberCondition), actions = listOf(changeCounter)),
            results = results,
        )

        verify(mockProcessingState).setCounterValue("growth", 2.34)
    }

    @Test
    fun execute_changeCounter_skipsWhenDetectedNumberIsUnavailable() = runTest {
        val numberCondition = getClickableConditions().filterIsInstance<ScreenCondition.Number>().single()
        val changeCounter = ChangeCounter(
            id = Identifier(databaseId = 91L),
            eventId = TEST_EVENT_ID,
            name = TEST_NAME,
            priority = 0,
            counterName = "growth",
            operation = ChangeCounter.OperationType.SET,
            operationValue = CounterOperationValue.Number(0.0),
            detectedNumberConditionId = numberCondition.id,
        )
        mockWhen(mockProcessingState.getCounterValue("growth")).thenReturn(7.0)

        actionExecutor.executeActions(
            event = getNewDefaultEvent(conditions = listOf(numberCondition), actions = listOf(changeCounter)),
            results = ConditionsResults(),
        )

        verify(mockProcessingState, never()).setCounterValue(any(), any())
    }

    @Test
    fun execute_changeCounter_calculatesAbsoluteDifference() = runTest {
        val changeCounter = ChangeCounter(
            id = Identifier(databaseId = 92L),
            eventId = TEST_EVENT_ID,
            name = TEST_NAME,
            priority = 0,
            counterName = "growthDifference",
            operation = ChangeCounter.OperationType.ABS_DIFF,
            operationValue = CounterOperationValue.Number(2.34),
        )
        mockWhen(mockProcessingState.getCounterValue("growthDifference")).thenReturn(2.3)

        actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(changeCounter)),
            results = ConditionsResults(),
        )

        val valueCaptor = argumentCaptor<Double>()
        verify(mockProcessingState).setCounterValue(eq("growthDifference"), valueCaptor.capture())
        assertEquals(0.04, valueCaptor.firstValue, 0.000001)
    }

    @Test
    fun execute_changeCounter_failsWhenReferencedCounterIsMissing() = runTest {
        val changeCounter = ChangeCounter(
            id = Identifier(databaseId = 93L),
            eventId = TEST_EVENT_ID,
            name = TEST_NAME,
            priority = 0,
            counterName = "target",
            operation = ChangeCounter.OperationType.SET,
            operationValue = CounterOperationValue.Counter("missing"),
        )
        mockWhen(mockProcessingState.getCounterValue("target")).thenReturn(12.0)
        mockWhen(mockProcessingState.getCounterValue("missing")).thenReturn(null)

        val result = actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(changeCounter)),
            results = ConditionsResults(),
        )

        assertEquals(ActionExecutionResult.Failed("Referenced counter not found: missing"), result)
        verify(mockProcessingState, never()).setCounterValue(any(), any())
    }

    @Test
    fun execute_subflow_executesTargetActionsAndReturns() = runTest {
        val targetEventId = 77L
        val targetEvent = getNewDefaultEvent(
            actions = listOf(getNewDefaultClickUserPos(101L)),
        ).copy(id = Identifier(databaseId = targetEventId))
        mockWhen(mockProcessingState.getEvent(targetEventId)).thenReturn(targetEvent)

        actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(getExecuteOnceAction(100L, targetEventId))),
            results = ConditionsResults(),
        )

        verify(mockProcessingState).getEvent(targetEventId)
        verify(mockAndroidExecutor).dispatchGesture(anyNotNull())
    }

    @Test
    fun execute_subflow_suppliesTargetConditionResultsToActions() = runTest {
        val targetEventId = 177L
        val targetCondition = getNewDefaultCondition(178L)
        val targetEvent = getNewDefaultEvent(
            operator = AND,
            conditions = listOf(targetCondition),
            actions = listOf(getNewDefaultClickCondition(179L, targetCondition.getDatabaseId())),
        ).copy(id = Identifier(databaseId = targetEventId))
        val targetResults = ConditionsResults().apply {
            addResult(
                targetCondition.getDatabaseId(),
                ProcessedConditionResult.Screen(
                    isFulfilled = true,
                    haveBeenDetected = true,
                    condition = targetCondition,
                    position = Point(150, 250),
                    size = Point(20, 20),
                    confidenceRate = 100.0,
                )
            )
            setFulfilledState(true)
        }
        mockWhen(mockProcessingState.getEvent(targetEventId)).thenReturn(targetEvent)
        actionExecutor = ActionExecutor(
            androidExecutor = mockAndroidExecutor,
            processingState = mockProcessingState,
            randomize = false,
            subflowResultsProvider = { event ->
                assertEquals(targetEventId, event.getValidId())
                targetResults
            },
        )

        actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(getExecuteOnceAction(180L, targetEventId))),
            results = ConditionsResults(),
        )

        verify(mockAndroidExecutor).dispatchGesture(anyNotNull())
    }

    @Test
    fun execute_subflow_sharesCountersAsInputAndOutputParameters() = runTest {
        val targetEventId = 78L
        val setInput = ChangeCounter(
            id = Identifier(databaseId = 110L),
            eventId = TEST_EVENT_ID,
            name = TEST_NAME,
            priority = 0,
            counterName = "parameter",
            operation = ChangeCounter.OperationType.SET,
            operationValue = CounterOperationValue.Number(7.0),
        )
        val updateOutput = setInput.copy(
            id = Identifier(databaseId = 111L),
            operation = ChangeCounter.OperationType.ADD,
            operationValue = CounterOperationValue.Number(1.0),
        )
        val targetEvent = getNewDefaultEvent(actions = listOf(updateOutput))
            .copy(id = Identifier(databaseId = targetEventId))
        mockWhen(mockProcessingState.getCounterValue("parameter")).thenReturn(0.0, 7.0)
        mockWhen(mockProcessingState.getEvent(targetEventId)).thenReturn(targetEvent)

        actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(setInput, getExecuteOnceAction(112L, targetEventId))),
            results = ConditionsResults(),
        )

        inOrder(mockProcessingState).apply {
            verify(mockProcessingState).setCounterValue("parameter", 7.0)
            verify(mockProcessingState).setCounterValue("parameter", 8.0)
        }
    }

    @Test
    fun execute_subflow_stopsRecursiveCalls() = runTest {
        val targetEventId = 79L
        val targetEvent = getNewDefaultEvent(
            actions = listOf(getExecuteOnceAction(120L, TEST_EVENT_ID.databaseId)),
        ).copy(id = Identifier(databaseId = targetEventId))
        mockWhen(mockProcessingState.getEvent(targetEventId)).thenReturn(targetEvent)

        val result = actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(getExecuteOnceAction(121L, targetEventId))),
            results = ConditionsResults(),
        )

        assertEquals(
            ActionExecutionResult.Failed("Recursive subflow call blocked: ${TEST_EVENT_ID.databaseId}"),
            result,
        )
        verify(mockProcessingState).getEvent(targetEventId)
        verify(mockProcessingState, never()).getEvent(TEST_EVENT_ID.databaseId)
        verify(mockAndroidExecutor, never()).dispatchGesture(anyNotNull())
    }

    @Test
    fun execute_subflow_reportsMissingTarget() = runTest {
        val targetEventId = 80L
        mockWhen(mockProcessingState.getEvent(targetEventId)).thenReturn(null)

        val result = actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(getExecuteOnceAction(130L, targetEventId))),
            results = ConditionsResults(),
        )

        assertEquals(ActionExecutionResult.Failed("Subflow event not found: $targetEventId"), result)
        verify(mockProcessingState).getEvent(targetEventId)
        verify(mockAndroidExecutor, never()).dispatchGesture(anyNotNull())
    }

    @Test
    fun execute_subflow_propagatesChildFailureAndStopsParentActions() = runTest {
        val targetEventId = 81L
        val invalidSwipe = getNewDefaultSwipe(140L).copy(from = null)
        val targetEvent = getNewDefaultEvent(actions = listOf(invalidSwipe))
            .copy(id = Identifier(databaseId = targetEventId))
        mockWhen(mockProcessingState.getEvent(targetEventId)).thenReturn(targetEvent)

        val result = actionExecutor.executeActions(
            event = getNewDefaultEvent(
                actions = listOf(
                    getExecuteOnceAction(141L, targetEventId),
                    getNewDefaultClickUserPos(142L),
                ),
            ),
            results = ConditionsResults(),
        )

        assertEquals(ActionExecutionResult.Failed("Swipe coordinates are invalid"), result)
        verify(mockAndroidExecutor, never()).dispatchGesture(anyNotNull())
    }

    @Test
    fun execute_mixed() = runTest {
        val click = getNewDefaultClickUserPos(1)
        val pause = getNewDefaultPause(2)
        val swipe = getNewDefaultSwipe(3)
        val gestureCaptor = argumentCaptor<GestureDescription>()

        // Execute the actions.
        actionExecutor.executeActions(
            event = getNewDefaultEvent(actions = listOf(click, pause, swipe)),
            results = ConditionsResults(),
        )

        // Verify the gestures executions
        verify(mockAndroidExecutor, times(2)).dispatchGesture(gestureCaptor.capture())
        assertActionGesture(gestureCaptor.firstValue)
        assertActionGesture(gestureCaptor.lastValue)
    }

    @Test
    fun execute_click_delay() = runTest {
        val executionDurationMs = 10L
        var isCompleted = false
        mockWhen(mockAndroidExecutor.dispatchGesture(anyNotNull())).doAnswer {
            runBlocking {
                // The execution is set to 10ms, but we simulate an input lag for a worst case scenario
                delay(executionDurationMs * 10)
                isCompleted = true
            }
            AndroidGestureResult.COMPLETED
        }

        launch(Dispatchers.IO) {
            actionExecutor.executeActions(
                event = getNewDefaultEvent(actions = listOf(getNewDefaultClickUserPos(1, executionDurationMs))),
                results = ConditionsResults(),
            )

            assertTrue("Action execution have not completed yet", isCompleted)
        }.join()
    }

    @Test
    fun execute_gestureRejected_reportsNormalizedFailure() = runTest {
        whenever(mockAndroidExecutor.dispatchGesture(any())).thenReturn(AndroidGestureResult.REJECTED)
        val results = mutableListOf<ActionExecutionResult>()
        val executor = ActionExecutor(
            mockAndroidExecutor,
            mockProcessingState,
            randomize = false,
            onActionResult = { _, _, result -> results += result },
        )

        executor.executeActions(getNewDefaultEvent(actions = listOf(getNewDefaultClickUserPos(1))))

        assertTrue(results.single() is ActionExecutionResult.Failed)
    }

    @Test
    fun execute_threeConsecutiveFailures_stopsScenarioWatchdog() = runTest {
        var stopRequests = 0
        val invalidSwipe = getNewDefaultSwipe(1).copy(from = null)
        val executor = ActionExecutor(
            mockAndroidExecutor,
            mockProcessingState,
            randomize = false,
            onStopRequested = { stopRequests++ },
        )

        repeat(3) {
            executor.executeActions(getNewDefaultEvent(actions = listOf(invalidSwipe)))
        }

        assertEquals(1, stopRequests)
    }

    @Test
    fun execute_smartWaitSkip_recordsTimeoutAndReturnsSkipped() = runTest {
        val results = mutableListOf<ActionExecutionResult>()
        val smartPause = getNewDefaultPause(1).copy(
            waitMode = Pause.WaitMode.SCREEN_CHANGED,
            timeoutBehavior = Pause.TimeoutBehavior.SKIP,
        )
        val executor = ActionExecutor(
            mockAndroidExecutor,
            mockProcessingState,
            randomize = false,
            smartWaitExecutor = { _, pause -> ActionExecutionResult.TimedOut(pause.pauseDuration!!) },
            onActionResult = { _, _, result -> results += result },
        )

        executor.executeActions(getNewDefaultEvent(actions = listOf(smartPause)))

        assertTrue(results.first() is ActionExecutionResult.TimedOut)
        assertTrue(results.last() is ActionExecutionResult.Skipped)
    }

    @Test
    fun repeatedEventFailures_areNotResetBySuccessfulPreambleOrOtherEvents() = runTest {
        var stops = 0
        val executor = ActionExecutor(mockAndroidExecutor, mockProcessingState, false, onStopRequested = { stops++ })
        val failing = getNewDefaultEvent(actions = listOf(getNewDefaultPause(1), getNewDefaultSwipe(2).copy(from = null)))
        val other = getNewDefaultEvent(actions = listOf(getNewDefaultPause(3))).copy(id = Identifier(databaseId = 99))
        repeat(3) {
            assertTrue(executor.executeActions(failing) is ActionExecutionResult.Failed)
            assertEquals(ActionExecutionResult.Success, executor.executeActions(other))
        }
        assertEquals(1, stops)
    }

    @Test
    fun successfulWholeEvent_resetsItsFailureCount() = runTest {
        var stops = 0
        val executor = ActionExecutor(mockAndroidExecutor, mockProcessingState, false, onStopRequested = { stops++ })
        val failed = getNewDefaultEvent(actions = listOf(getNewDefaultSwipe(1).copy(from = null)))
        repeat(2) { executor.executeActions(failed) }
        executor.executeActions(getNewDefaultEvent(actions = listOf(getNewDefaultPause(2))))
        repeat(2) { executor.executeActions(failed) }
        assertEquals(0, stops)
    }

    @Test
    fun longSubflowAndDebuggerPause_doNotConsumeAParentDeadline() = runTest {
        val target = getNewDefaultEvent(actions = listOf(getNewDefaultPause(2).copy(pauseDuration = 90_000L)))
            .copy(id = Identifier(databaseId = 99))
        whenever(mockProcessingState.getEvent(99)).thenReturn(target)
        var gateFinished = false
        val executor = ActionExecutor(
            mockAndroidExecutor, mockProcessingState, false,
            beforeSubflowActions = { delay(120_000); gateFinished = true },
            subflowResultsProvider = { assertTrue(gateFinished); null },
        )
        assertEquals(ActionExecutionResult.Success, executor.executeActions(
            getNewDefaultEvent(actions = listOf(getExecuteOnceAction(1, 99))),
        ))
        assertEquals(210_000L, currentTime)
    }

    @Test
    fun fifteenMinuteWait_isNotTruncatedAtTenMinutes() = runTest {
        assertEquals(ActionExecutionResult.Success, actionExecutor.executeActions(
            getNewDefaultEvent(actions = listOf(getNewDefaultPause(1).copy(pauseDuration = 900_000L))),
        ))
        assertEquals(900_000L, currentTime)
    }

    @Test
    fun rejectedSystemAction_stopsFollowingClick() = runTest {
        val system = com.buzbuz.smartautoclicker.core.domain.model.action.SystemAction(
            Identifier(databaseId = 1), TEST_EVENT_ID, "返回", 0,
            com.buzbuz.smartautoclicker.core.domain.model.action.SystemAction.Type.BACK,
        )
        whenever(mockAndroidExecutor.performGlobalAction(any())).thenReturn(false)
        assertTrue(actionExecutor.executeActions(getNewDefaultEvent(actions = listOf(system, getNewDefaultClickUserPos(2))))
            is ActionExecutionResult.Failed)
        verify(mockAndroidExecutor, never()).dispatchGesture(any())
    }

    @Test
    fun longWait_remainsCancellable() = runTest {
        val job = launch {
            actionExecutor.executeActions(getNewDefaultEvent(actions = listOf(getNewDefaultPause(1).copy(pauseDuration = 900_000L))))
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0L, currentTime)
    }

    @Test
    fun swipeSearch_checksBeforeSwipingAndNeverRunsTargetActions() = runTest {
        val target = getNewDefaultEvent(conditions = listOf(getNewDefaultCondition(3)), actions = listOf(getNewDefaultClickUserPos(4)))
            .copy(id = Identifier(databaseId = 99), enabledOnStart = false)
        whenever(mockProcessingState.getEvent(99)).thenReturn(target)
        var confirmations = 0
        val executor = ActionExecutor(mockAndroidExecutor, mockProcessingState, false,
            subflowResultsProvider = { ConditionsResults().apply { setFulfilledState(true) } },
            smartWaitExecutor = { _, wait ->
                assertEquals(2, wait.confirmationFrames)
                confirmations++
                ActionExecutionResult.Success
            })
        val search = getNewDefaultSwipe(1).copy(verificationEventId = target.id)
        assertEquals(ActionExecutionResult.Success, executor.executeActions(getNewDefaultEvent(actions = listOf(search))))
        assertEquals(1, confirmations)
        verify(mockAndroidExecutor, never()).dispatchGesture(any())
    }

    @Test
    fun swipeSearch_checksAfterFinalSwipeAndStopsAtLimit() = runTest {
        val target = getNewDefaultEvent(conditions = listOf(getNewDefaultCondition(3))).copy(id = Identifier(databaseId = 99))
        whenever(mockProcessingState.getEvent(99)).thenReturn(target)
        var observations = 0
        val executor = ActionExecutor(mockAndroidExecutor, mockProcessingState, false,
            subflowResultsProvider = { observations++; ConditionsResults().apply { setFulfilledState(false) } })
        val search = getNewDefaultSwipe(1).copy(verificationEventId = target.id, searchMaxSwipes = 2, verificationTimeoutMs = 200)
        assertTrue(executor.executeActions(getNewDefaultEvent(actions = listOf(search, getNewDefaultClickUserPos(2)))) is ActionExecutionResult.Failed)
        assertEquals(3, observations)
        verify(mockAndroidExecutor, times(2)).dispatchGesture(any())
    }

    @Test
    fun swipeSearch_findsTargetAfterLastAllowedSwipe() = runTest {
        val target = getNewDefaultEvent(conditions = listOf(getNewDefaultCondition(3))).copy(id = Identifier(databaseId = 99))
        whenever(mockProcessingState.getEvent(99)).thenReturn(target)
        var observations = 0
        val executor = ActionExecutor(mockAndroidExecutor, mockProcessingState, false,
            subflowResultsProvider = { ConditionsResults().apply { setFulfilledState(++observations == 3) } },
            smartWaitExecutor = { _, _ -> ActionExecutionResult.Success })
        val search = getNewDefaultSwipe(1).copy(verificationEventId = target.id, searchMaxSwipes = 2, verificationTimeoutMs = 200)
        assertEquals(ActionExecutionResult.Success, executor.executeActions(getNewDefaultEvent(actions = listOf(search))))
        assertEquals(3, observations)
        verify(mockAndroidExecutor, times(2)).dispatchGesture(any())
    }

    @Test
    fun swipeSearch_missingCaptureDoesNotScrollBlindly() = runTest {
        val target = getNewDefaultEvent(conditions = listOf(getNewDefaultCondition(3))).copy(id = Identifier(databaseId = 99))
        whenever(mockProcessingState.getEvent(99)).thenReturn(target)
        val search = getNewDefaultSwipe(1).copy(verificationEventId = target.id)
        assertTrue(actionExecutor.executeActions(getNewDefaultEvent(actions = listOf(search))) is ActionExecutionResult.Failed)
        verify(mockAndroidExecutor, never()).dispatchGesture(any())
    }

    @Test
    fun clickConfirmation_failureRecordsBeforeStoppingAndNeverRetriesClick() = runTest {
        whenever(mockProcessingState.getEvent(99)).thenReturn(getNewDefaultEvent(conditions = listOf(getNewDefaultCondition(3))))
        val order = mutableListOf<String>()
        val executor = ActionExecutor(mockAndroidExecutor, mockProcessingState, false,
            smartWaitExecutor = { _, _ -> ActionExecutionResult.TimedOut(500) },
            onStopRequested = { order += "stop" },
            onActionCompleted = { _, _, result, _ -> assertTrue(result.isFailure); order += "record" })
        val click = getNewDefaultClickUserPos(1).copy(verificationEventId = Identifier(databaseId = 99), verificationTimeoutMs = 500)
        assertEquals(ActionExecutionResult.TimedOut(500), executor.executeActions(getNewDefaultEvent(actions = listOf(click, getNewDefaultClickUserPos(2)))))
        assertEquals(listOf("record", "stop"), order)
        verify(mockAndroidExecutor, times(1)).dispatchGesture(any())
    }

    @Test
    fun clickConfirmation_successContinuesFollowingActions() = runTest {
        whenever(mockProcessingState.getEvent(99)).thenReturn(getNewDefaultEvent(conditions = listOf(getNewDefaultCondition(3))))
        var confirmations = 0
        val executor = ActionExecutor(mockAndroidExecutor, mockProcessingState, false,
            smartWaitExecutor = { _, _ -> confirmations++; ActionExecutionResult.Success })
        val click = getNewDefaultClickUserPos(1).copy(verificationEventId = Identifier(databaseId = 99))
        assertEquals(ActionExecutionResult.Success, executor.executeActions(getNewDefaultEvent(actions = listOf(click, getNewDefaultClickUserPos(2)))))
        assertEquals(1, confirmations)
        verify(mockAndroidExecutor, times(2)).dispatchGesture(any())
    }

    @Test
    fun clickConfirmation_missingTargetIsRejectedBeforeClick() = runTest {
        val click = getNewDefaultClickUserPos(1).copy(verificationEventId = Identifier(databaseId = 99))
        assertTrue(actionExecutor.executeActions(getNewDefaultEvent(actions = listOf(click))) is ActionExecutionResult.Failed)
        verify(mockAndroidExecutor, never()).dispatchGesture(any())
    }
}
