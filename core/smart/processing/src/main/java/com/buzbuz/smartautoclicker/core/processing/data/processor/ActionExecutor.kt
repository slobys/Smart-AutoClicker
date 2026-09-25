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
package com.buzbuz.smartautoclicker.core.processing.data.processor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent as AndroidIntent
import android.graphics.Path
import android.graphics.Point
import android.util.Log

import com.buzbuz.smartautoclicker.core.base.workarounds.UnblockGestureScheduler
import com.buzbuz.smartautoclicker.core.base.workarounds.buildUnblockGesture
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.common.actions.AndroidGestureResult
import com.buzbuz.smartautoclicker.core.common.actions.gesture.buildSingleStroke
import com.buzbuz.smartautoclicker.core.common.actions.gesture.line
import com.buzbuz.smartautoclicker.core.common.actions.gesture.moveTo
import com.buzbuz.smartautoclicker.core.common.actions.model.ActionNotificationRequest
import com.buzbuz.smartautoclicker.core.common.actions.text.findCounterReferences
import com.buzbuz.smartautoclicker.core.common.actions.text.replaceCounterReferences
import com.buzbuz.smartautoclicker.core.common.actions.utils.getPauseDurationMs
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.domain.model.OR
import com.buzbuz.smartautoclicker.core.domain.model.action.Intent
import com.buzbuz.smartautoclicker.core.domain.model.action.Click
import com.buzbuz.smartautoclicker.core.domain.model.action.Pause
import com.buzbuz.smartautoclicker.core.domain.model.action.Swipe
import com.buzbuz.smartautoclicker.core.domain.model.action.ToggleEvent
import com.buzbuz.smartautoclicker.core.domain.model.action.ChangeCounter
import com.buzbuz.smartautoclicker.core.domain.model.action.Notification
import com.buzbuz.smartautoclicker.core.domain.model.action.SetText
import com.buzbuz.smartautoclicker.core.domain.model.action.SystemAction
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.action.intent.putDomainExtra
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.domain.model.ProcessedConditionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.isFailure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.random.Random

/**
 * Execute the actions of an event.
 *
 * @param androidExecutor the executor for the actions requiring an interaction with Android.
 * @param processingState the state of the current processing (counters, enabled events...).
 * @param randomize true to randomize the actions values a bit (positions, timers...), false to be precise.
 * @param subflowResultsProvider resolves a called event conditions against the current processing context.
 */
internal class ActionExecutor(
    private val androidExecutor: AndroidActionExecutor,
    private val processingState: ProcessingState,
    randomize: Boolean,
    unblockWorkaroundEnabled: Boolean = false,
    private val subflowResultsProvider: suspend (Event) -> ConditionsResults? = { null },
    private val beforeSubflowActions: suspend (Event) -> Unit = {},
    private val smartWaitExecutor: suspend (Event, Pause) -> ActionExecutionResult = { _, _ ->
        ActionExecutionResult.Failed("Smart wait is unavailable")
    },
    private val onActionResult: suspend (Event, Action, ActionExecutionResult) -> Unit = { _, _, _ -> },
    private val onStopRequested: () -> Unit = {},
    private val onActionCompleted: suspend (Event, Action, ActionExecutionResult, Long) -> Unit = { _, _, _, _ -> },
) {

    init { androidExecutor.resetState() }

    private val random: Random? =
        if (randomize) Random(System.currentTimeMillis()) else null

    private val unblockGestureScheduler: UnblockGestureScheduler? =
        if (unblockWorkaroundEnabled) UnblockGestureScheduler()
        else null
    // A successful preamble (for example a delay) must not hide an event that fails every run.
    private val eventFailures = mutableMapOf<Long, Int>()
    private var stopAfterAction = false


    suspend fun onScenarioLoopFinished() {
        if (unblockGestureScheduler?.shouldTrigger() == true) {
            withContext(Dispatchers.Main) {
                Log.i(TAG, "Injecting unblock gesture")
                androidExecutor.dispatchGesture(
                    GestureDescription.Builder().buildUnblockGesture()
                )
            }
        }
    }

    suspend fun executeActions(
        event: Event,
        results: ConditionsResults? = null,
    ): ActionExecutionResult {
        val result = executeActionsInternal(
            event = event,
            results = results,
            callStack = listOf(event.getValidId()),
        )
        val eventId = event.getValidId()
        if (result.isFailure) {
            val failures = (eventFailures[eventId] ?: 0) + 1
            eventFailures[eventId] = failures
            Log.w(TAG, "Event ${event.name} failed ($failures/$MAX_CONSECUTIVE_FAILURES): $result")
            if (failures == MAX_CONSECUTIVE_FAILURES) stopAfterAction = true
        } else if (result == ActionExecutionResult.Success) {
            eventFailures.remove(eventId)
        }
        if (stopAfterAction) { stopAfterAction = false; onStopRequested() }
        return result
    }

    private suspend fun executeActionsInternal(
        event: Event,
        results: ConditionsResults?,
        callStack: List<Long>,
    ): ActionExecutionResult {
        for (action in event.actions) {
            val startedAt = System.nanoTime()
            val timeoutMs = action.watchdogTimeoutMs()
            // Orchestration is bounded by its leaf actions and cycle/depth guards. A parent
            // deadline would also count debugger pauses and truncate valid long child waits.
            val result = if (timeoutMs == null) {
                executeAction(event, action, results, callStack)
            } else withTimeoutOrNull(timeoutMs) {
                executeAction(event, action, results, callStack)
            } ?: ActionExecutionResult.TimedOut(timeoutMs)

            val durationMs = (System.nanoTime() - startedAt) / 1_000_000L
            onActionResult(event, action, result)
            onActionCompleted(event, action, result, durationMs)
            if (result.isFailure) return result
        }

        return ActionExecutionResult.Success
    }

    private suspend fun executeAction(
        event: Event,
        action: Action,
        results: ConditionsResults?,
        callStack: List<Long>,
    ): ActionExecutionResult = when (action) {
        is Click -> executeClick(event, action, results)
        is Swipe -> executeSwipeSearch(event, action)
        is Pause -> executePause(event, action, callStack)
        is Intent -> executeIntent(action)
        is ToggleEvent -> executeToggleEvent(action, callStack)
        is ChangeCounter -> executeChangeCounter(action, results)
        is Notification -> executeNotification(event, action)
        is SystemAction -> executeSystemAction(action)
        is SetText -> executeSetText(action)
    }

    private suspend fun executeClick(event: Event, click: Click, results: ConditionsResults?): ActionExecutionResult {
        if ((click.pressDuration ?: 0L) !in 1..59_999) return ActionExecutionResult.Failed("Invalid click duration")
        click.verificationEventId?.let { id ->
            val target = processingState.getEvent(id.databaseId) as? ScreenEvent
            if (target == null || target.conditions.isEmpty() || click.verificationTimeoutMs !in 400..300_000) {
                return ActionExecutionResult.Failed("Click confirmation target or timeout is invalid")
            }
        }
        val clickPath = when (click.positionType) {
            Click.PositionType.USER_SELECTED -> {
                click.position?.let { position ->
                    Path().apply { moveTo(position, random) }
                }
            }

            Click.PositionType.ON_DETECTED_CONDITION ->
                getOnConditionClickPath(event, click, results)
        } ?: return ActionExecutionResult.Failed("Click target is unavailable")

        val clickGesture = GestureDescription.Builder().buildSingleStroke(
            path = clickPath,
            durationMs = click.pressDuration!!,
            random = random,
        )

        val result = dispatchGesture(clickGesture, click.pressDuration!!)
        if (result.isFailure || click.verificationEventId == null) return result
        val verification = smartWaitExecutor(event, Pause(
            id = click.id, eventId = click.eventId, name = click.name, priority = click.priority,
            pauseDuration = click.verificationTimeoutMs, waitMode = Pause.WaitMode.TARGET_APPEARS,
            waitTargetEventId = click.verificationEventId, confirmationFrames = 2,
        ))
        // Never repeat a potentially consuming click after an ambiguous result.
        if (verification.isFailure) stopAfterAction = true
        return verification
    }

    private fun getOnConditionClickPath(event: Event, click: Click, results: ConditionsResults?): Path? {
        if (event !is ScreenEvent) return null

        val result = when {
            event.conditionOperator == OR -> results?.getFirstScreenConditionDetectedResult()
            click.clickOnConditionId != null -> results?.getScreenConditionResult(click.clickOnConditionId!!.databaseId)
            else -> null
        }

        val detectedPosition = result?.getConditionClickPosition()
        if (detectedPosition == null) {
            Log.w(TAG, "Click is invalid, detected condition has no clickable position")
            return null
        }

        return Path().apply {
            moveTo(
                position = Point(
                    detectedPosition.x + (click.clickOffset?.x ?: 0),
                    detectedPosition.y + (click.clickOffset?.y ?: 0),
                ),
                random = random,
            )
        }
    }

    /**
     * Execute the provided swipe.
     * @param swipe the swipe to be executed.
     */
    private suspend fun executeSwipe(swipe: Swipe): ActionExecutionResult {
        if ((swipe.swipeDuration ?: 0L) !in 1..59_999) return ActionExecutionResult.Failed("Invalid swipe duration")
        val swipeGesture = GestureDescription.Builder().buildSingleStroke(
            path =
                if (swipe.from == null || swipe.to == null) return ActionExecutionResult.Failed("Swipe coordinates are invalid")
                else Path().apply { line(swipe.from, swipe.to, random) },
            durationMs = swipe.swipeDuration!!,
            random = random,
        )

        return dispatchGesture(swipeGesture, swipe.swipeDuration!!)
    }

    private suspend fun dispatchGesture(gesture: GestureDescription, durationMs: Long): ActionExecutionResult =
        withTimeoutOrNull(durationMs.coerceIn(0, 59_999) + ACTION_TIMEOUT_GRACE_MS) {
            withContext(Dispatchers.Main) { androidExecutor.dispatchGesture(gesture) }.toExecutionResult()
        } ?: ActionExecutionResult.TimedOut(durationMs + ACTION_TIMEOUT_GRACE_MS)

    private suspend fun executeSwipeSearch(event: Event, swipe: Swipe): ActionExecutionResult {
        val targetId = swipe.verificationEventId ?: return executeSwipe(swipe)
        if (swipe.searchMaxSwipes !in 1..50 || swipe.verificationTimeoutMs !in 200..30_000) {
            return ActionExecutionResult.Failed("Invalid swipe search limits")
        }
        val target = processingState.getEvent(targetId.databaseId) as? ScreenEvent
            ?: return ActionExecutionResult.Failed("Swipe search target is unavailable")
        if (target.conditions.isEmpty()) return ActionExecutionResult.Failed("Swipe search target has no conditions")
        // Check first, then after every swipe, including the final allowed swipe.
        repeat(swipe.searchMaxSwipes + 1) { attempt ->
            val observation = subflowResultsProvider(target)
                ?: return ActionExecutionResult.Failed("Swipe search cannot capture a fresh screen")
            observation.errorReason?.let { return ActionExecutionResult.Failed(it) }
            if (observation.fulfilled == true) {
                val confirmed = smartWaitExecutor(event, Pause(
                    swipe.id, swipe.eventId, swipe.name, swipe.priority, swipe.verificationTimeoutMs.coerceAtLeast(400L),
                    waitMode = Pause.WaitMode.TARGET_APPEARS, waitTargetEventId = targetId, confirmationFrames = 2,
                ))
                // A transient match is not a reason to blindly swipe past the target.
                return confirmed
            }
            if (attempt == swipe.searchMaxSwipes) return ActionExecutionResult.Failed("Swipe search reached its limit")
            val result = executeSwipe(swipe)
            if (result.isFailure) return result
            delay(swipe.verificationTimeoutMs)
        }
        return ActionExecutionResult.Failed("Swipe search did not find the target")
    }

    /**
     * Execute the provided pause.
     * @param pause the pause to be executed.
     */
    private suspend fun executePause(event: Event, pause: Pause, callStack: List<Long>): ActionExecutionResult {
        if ((pause.pauseDuration ?: 0L) <= 0 || pause.maxRetries !in 0..20) {
            return ActionExecutionResult.Failed("Invalid wait duration or retry count")
        }
        if (pause.waitMode == Pause.WaitMode.FIXED_DELAY) {
            delay(pause.pauseDuration!!.getPauseDurationMs(random))
            return ActionExecutionResult.Success
        }

        val attempts = if (pause.timeoutBehavior == Pause.TimeoutBehavior.RETRY) pause.maxRetries + 1 else 1
        repeat(attempts) { attempt ->
            val result = smartWaitExecutor(event, pause)
            if (result !is ActionExecutionResult.TimedOut) return result
            if (attempt < attempts - 1) Log.i(TAG, "Smart wait timed out, retrying (${attempt + 1}/$attempts)")
        }

        return when (pause.timeoutBehavior) {
            Pause.TimeoutBehavior.SKIP -> {
                onActionResult(event, pause, ActionExecutionResult.TimedOut(pause.pauseDuration!!))
                ActionExecutionResult.Skipped("Smart wait timed out")
            }
            Pause.TimeoutBehavior.STOP -> {
                stopAfterAction = true
                ActionExecutionResult.TimedOut(pause.pauseDuration!!)
            }
            Pause.TimeoutBehavior.EXECUTE_FALLBACK -> {
                onActionResult(event, pause, ActionExecutionResult.TimedOut(pause.pauseDuration!!))
                val targetId = pause.fallbackEventId?.databaseId
                    ?: return ActionExecutionResult.Failed("Fallback event is missing")
                val fallbackResult = executeEventOnce(targetId, callStack)
                if (!fallbackResult.isFailure) {
                    ActionExecutionResult.Skipped("Fallback event executed after timeout")
                } else {
                    fallbackResult
                }
            }
            Pause.TimeoutBehavior.RETRY -> ActionExecutionResult.TimedOut(pause.pauseDuration!!)
        }
    }

    /**
     * Execute the provided intent.
     * @param intent the intent to be executed.
     */
    private suspend fun executeIntent(intent: Intent): ActionExecutionResult {
        val androidIntent = AndroidIntent().apply {
            action = intent.intentAction!!
            flags = intent.flags!!

            intent.componentName?.let {
                component = intent.componentName
            }

            intent.extras?.forEach { putDomainExtra(it) }
        }

        val accepted = if (intent.isBroadcast) {
            withContext(Dispatchers.Main) {
                androidExecutor.sendBroadcast(androidIntent)
            }
        } else {
            withContext(Dispatchers.Main) {
                androidExecutor.startActivity(androidIntent)
            }
        }
        if (!accepted) return ActionExecutionResult.Failed("Android rejected the intent or the target app is unavailable")
        delay(if (intent.isBroadcast) INTENT_BROADCAST_DELAY else INTENT_START_ACTIVITY_DELAY)
        return ActionExecutionResult.Success
    }

    /**
     * Execute the provided toggle event.
     * @param toggleEvent the toggleEvent to be executed.
     */
    private suspend fun executeToggleEvent(
        toggleEvent: ToggleEvent,
        callStack: List<Long>,
    ): ActionExecutionResult {
        if (toggleEvent.toggleAll) {
            return when (toggleEvent.toggleAllType) {
                ToggleEvent.ToggleType.ENABLE -> {
                    processingState.enableAll()
                    ActionExecutionResult.Success
                }
                ToggleEvent.ToggleType.DISABLE -> {
                    processingState.disableAll()
                    ActionExecutionResult.Success
                }
                ToggleEvent.ToggleType.TOGGLE -> {
                    processingState.toggleAll()
                    ActionExecutionResult.Success
                }
                ToggleEvent.ToggleType.EXECUTE_ONCE -> {
                    Log.w(TAG, "Execute-once is not supported with toggle-all")
                    ActionExecutionResult.Failed("Execute-once is not supported with toggle-all")
                }
                null -> ActionExecutionResult.Failed("Toggle-all operation is missing")
            }
        }

        toggleEvent.eventToggles.forEach { eventToggle ->
            when (eventToggle.toggleType) {
                ToggleEvent.ToggleType.ENABLE -> processingState.enableEvent(eventToggle.targetEventId!!.databaseId)
                ToggleEvent.ToggleType.DISABLE -> processingState.disableEvent(eventToggle.targetEventId!!.databaseId)
                ToggleEvent.ToggleType.TOGGLE -> processingState.toggleEvent(eventToggle.targetEventId!!.databaseId)
                ToggleEvent.ToggleType.EXECUTE_ONCE -> {
                    val result = executeEventOnce(
                        targetEventId = eventToggle.targetEventId!!.databaseId,
                        callStack = callStack,
                    )
                    if (result.isFailure) return result
                }
            }
        }

        return ActionExecutionResult.Success
    }

    private suspend fun executeEventOnce(
        targetEventId: Long,
        callStack: List<Long>,
    ): ActionExecutionResult {
        if (targetEventId in callStack) {
            Log.w(TAG, "Subflow call skipped: recursive event reference $targetEventId")
            return ActionExecutionResult.Failed("Recursive subflow call blocked: $targetEventId")
        }
        if (callStack.size >= MAX_SUBFLOW_CALL_DEPTH) {
            Log.w(TAG, "Subflow call skipped: maximum depth $MAX_SUBFLOW_CALL_DEPTH reached")
            return ActionExecutionResult.Failed("Maximum subflow depth reached: $MAX_SUBFLOW_CALL_DEPTH")
        }

        val targetEvent = processingState.getEvent(targetEventId)
        if (targetEvent == null) {
            Log.w(TAG, "Subflow call skipped: target event $targetEventId not found")
            return ActionExecutionResult.Failed("Subflow event not found: $targetEventId")
        }

        Log.d(TAG, "Executing subflow event $targetEventId at depth ${callStack.size}")
        beforeSubflowActions(targetEvent)
        // Resolve after the breakpoint: the screen may have changed while execution was paused.
        val targetResults = subflowResultsProvider(targetEvent)
        return executeActionsInternal(
            event = targetEvent,
            results = targetResults,
            callStack = callStack + targetEventId,
        )
    }

    /**
     * Execute the provided change counter.
     * @param changeCounter the changeCounter action to be executed.
     */
    private fun executeChangeCounter(changeCounter: ChangeCounter, results: ConditionsResults?): ActionExecutionResult {
        val oldValue = processingState.getCounterValue(changeCounter.counterName)
            ?: return ActionExecutionResult.Failed("Counter not found: ${changeCounter.counterName}")

        val detectedNumberConditionId = changeCounter.detectedNumberConditionId
        val operandValue = if (detectedNumberConditionId != null) {
            results
                ?.getScreenConditionResult(detectedNumberConditionId.databaseId)
                ?.numberDetected
        } else when (val operationValue = changeCounter.operationValue) {
                is CounterOperationValue.Counter -> processingState.getCounterValue(operationValue.value)
                    ?: return ActionExecutionResult.Failed(
                        "Referenced counter not found: ${operationValue.value}",
                    )
                is CounterOperationValue.Number -> operationValue.value
            }

        if (operandValue == null) {
            Log.w(TAG, "Change counter skipped: referenced number condition has no OCR result")
            return ActionExecutionResult.Failed("Referenced OCR number is unavailable")
        }

        processingState.setCounterValue(
            counterName = changeCounter.counterName,
            value = when (changeCounter.operation) {
                ChangeCounter.OperationType.ADD -> oldValue + operandValue
                ChangeCounter.OperationType.MINUS -> oldValue - operandValue
                ChangeCounter.OperationType.SET -> operandValue
                ChangeCounter.OperationType.ABS_DIFF -> abs(oldValue - operandValue)
            }
        )
        return ActionExecutionResult.Success
    }

    private fun executeNotification(event: Event, notification: Notification): ActionExecutionResult {
        val counters = buildMap {
            notification.messageText.findCounterReferences().forEach { counterName ->
                processingState.getCounterValue(counterName)?.let { counterValue ->
                    put(counterName, counterValue)
                }
            }
        }

        val accepted = androidExecutor.postNotification(
            ActionNotificationRequest(
                actionId = notification.id.databaseId,
                title = notification.name ?: "Klick'r",
                message = notification.messageText.replaceCounterReferences(counters),
                eventId = event.id.databaseId,
                groupName = event.name,
                importance = notification.channelImportance,
            )
        )
        return accepted.toExecutionResult("Notification could not be queued")
    }

    private suspend fun executeSystemAction(action: SystemAction): ActionExecutionResult {
        val globalAction = when (action.type) {
            SystemAction.Type.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            SystemAction.Type.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            SystemAction.Type.RECENT_APPS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        }

        return withContext(Dispatchers.Main) {
            androidExecutor.performGlobalAction(globalAction)
        }.toExecutionResult("Android rejected the system action")
    }

    private suspend fun executeSetText(action: SetText): ActionExecutionResult {
        val counters = buildMap {
            action.text.findCounterReferences().forEach { counterName ->
                processingState.getCounterValue(counterName)?.let { counterValue ->
                    put(counterName, counterValue)
                }
            }
        }

        return withContext(Dispatchers.Main) {
            androidExecutor.writeTextOnFocusedItem(
                text = action.text.replaceCounterReferences(counters),
                validate = action.validateInput,
            )
        }.toExecutionResult("Text input failed: no writable field, or validation was rejected")
    }
}

private fun AndroidGestureResult.toExecutionResult(): ActionExecutionResult = when (this) {
    AndroidGestureResult.COMPLETED -> ActionExecutionResult.Success
    AndroidGestureResult.CANCELLED -> ActionExecutionResult.Cancelled("Gesture was cancelled by Android")
    AndroidGestureResult.REJECTED -> ActionExecutionResult.Failed("Gesture was rejected by Android")
    AndroidGestureResult.SERVICE_UNAVAILABLE -> ActionExecutionResult.Failed("Accessibility service is unavailable")
}

private fun Boolean.toExecutionResult(reason: String): ActionExecutionResult =
    if (this) ActionExecutionResult.Success else ActionExecutionResult.Failed(reason)

private fun Action.watchdogTimeoutMs(): Long? = when (this) {
    is Pause, is ToggleEvent -> null
    is Click, is Swipe -> null // Individual gestures have their own deadline; checks/waits have theirs.
    else -> DEFAULT_ACTION_TIMEOUT_MS
}

/**
 * Returns the actual detection centre used by a click-on-condition action.
 *
 * OCR and color detectors normally provide this point. If a valid positive detection comes from
 * an older detector or a fallback pass without a bounding box, use the configured detection area
 * centre instead of silently clicking at (0, 0).
 */
internal fun ProcessedConditionResult.Screen.getConditionClickPosition(): Point? {
    if (!isFulfilled || !haveBeenDetected || !condition.shouldBeDetected) return null

    val detectedPosition = position
    val detectedSize = size
    if (detectedPosition != null &&
        (detectedPosition.x != 0 || detectedPosition.y != 0 ||
                (detectedSize?.x ?: 0) > 0 || (detectedSize?.y ?: 0) > 0)) {
        return detectedPosition
    }

    val detectionArea = when (val screenCondition = condition) {
        is ScreenCondition.Color -> screenCondition.detectionArea
        is ScreenCondition.Image -> screenCondition.detectionArea ?: screenCondition.area
        is ScreenCondition.Number -> screenCondition.detectionArea
        is ScreenCondition.Text -> screenCondition.detectionArea
    }
    if (detectionArea.isEmpty) return null

    return Point(detectionArea.centerX(), detectionArea.centerY())
}

/** Tag for logs. */
private const val TAG = "ActionExecutor"
/** Waiting delay after a start activity to avoid overflowing the system. */
private const val INTENT_START_ACTIVITY_DELAY = 1000L
/** Waiting delay after a broadcast to avoid overflowing the system. */
private const val INTENT_BROADCAST_DELAY = 100L
/** Maximum number of events in a synchronous subflow call chain. */
private const val MAX_SUBFLOW_CALL_DEPTH = 8
private const val MAX_CONSECUTIVE_FAILURES = 3
private const val DEFAULT_ACTION_TIMEOUT_MS = 60_000L
private const val ACTION_TIMEOUT_GRACE_MS = 5_000L
