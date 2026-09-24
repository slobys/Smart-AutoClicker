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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.get

import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.domain.model.event.TriggerEvent
import com.buzbuz.smartautoclicker.core.domain.model.action.Pause
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.domain.EventType
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingListener
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.math.abs

/**
 * Process a screen image and tries to detect the list of [ScreenEvent] on it.
 *
 * @param imageDetector the detector for images.
 * @param randomize true to randomize the actions values a bit to avoid being taken for a bot.
 * @param screenEvents the list of scenario events to be detected.
 * @param bitmapSupplier provides the conditions bitmaps.
 * @param androidExecutor execute the actions requiring an interaction with Android.
 * @param onStopRequested called when an end condition of the scenario have been reached or all events are disabled.
 * @param progressListener the object to notify for detection progress. Can be null if not required.
 */
internal class ScenarioProcessor(
    private val processingTag: String,
    private val imageDetector: ImageDetector,
    scalingManager: ScalingManager,
    randomize: Boolean,
    screenEvents: List<ScreenEvent>,
    triggerEvents: List<TriggerEvent>,
    counters: List<Counter>,
    private val bitmapSupplier: suspend (String, Int, Int) -> Bitmap?,
    private val screenFrameSupplier: suspend () -> Bitmap? = { null },
    androidExecutor: AndroidActionExecutor,
    unblockWorkaroundEnabled: Boolean = false,
    private val onStopRequested: () -> Unit,
    private val progressListener: SmartProcessingListener?,
    screenEventConfirmationHits: Int = 1,
    screenEventConfirmationWindow: Int = 3,
    private val singleFrameConfidenceMargin: Double = 0.005,
    private val beforeEventActions: suspend (
        eventId: Long,
        eventName: String,
        conditionDurationMs: Long,
        isBreakpoint: Boolean,
    ) -> Unit = { _, _, _, _ -> },
    private val onActionResult: suspend (Event, Action, ActionExecutionResult) -> Unit = { _, _, _ -> },
) {

    private companion object {
        private const val TAG = "ScenarioProcessor"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val SLOW_EVENT_LOG_THRESHOLD_MS = 100L
        private const val SLOW_FRAME_LOG_THRESHOLD_MS = 250L
        private const val SMART_WAIT_POLL_INTERVAL_MS = 200L
        private const val FINGERPRINT_SIZE = 16
    }

    /** Handle the processing state of the scenario. */
    @VisibleForTesting internal val processingState: ProcessingState = ProcessingState(
        screenEvents = screenEvents,
        triggerEvents = triggerEvents,
        counters = counters,
        progressListener = progressListener,
    )
    /** Check conditions and tell if they are fulfilled. */
    private val conditionsVerifier = ConditionsVerifier(
        state = processingState,
        imageDetector = imageDetector,
        scalingManager = scalingManager,
        bitmapSupplier = bitmapSupplier,
        progressListener = progressListener,
    )
    /** Execute the detected event actions. */
    private val actionExecutor = ActionExecutor(
        androidExecutor = androidExecutor,
        processingState = processingState,
        randomize = randomize,
        unblockWorkaroundEnabled = unblockWorkaroundEnabled,
        subflowResultsProvider = ::resolveSubflowResults,
        beforeSubflowActions = { event ->
            beforeEventActions(
                event.id.databaseId,
                event.name,
                0L,
                event.isBreakpoint,
            )
        },
        smartWaitExecutor = ::executeSmartWait,
        onActionResult = onActionResult,
        onStopRequested = onStopRequested,
    )
    /** Filters one-frame visual glitches before actions are executed. */
    private val screenEventStabilityTracker = ScreenEventStabilityTracker(
        requiredHits = screenEventConfirmationHits,
        windowSize = screenEventConfirmationWindow,
    )
    /** True only while the detector owns a captured frame that subflows can reuse. */
    private var isScreenFrameActive: Boolean = false
    /** The frame currently owned by the detector, if any. */
    private var activeScreenFrame: Bitmap? = null

    fun onScenarioStart(context: Context) {
        screenEventStabilityTracker.resetAll()
        processingState.onProcessingStarted(context)
    }

    fun onScenarioEnd() {
        screenEventStabilityTracker.resetAll()
        processingState.onProcessingStopped()
    }

    /**
     * Find an event with the conditions fulfilled on the current image.
     *
     * @param screenFrame the bitmap containing the current screen display.
     *
     * @return the first Event with all conditions fulfilled, or null if none has been found.
     */
    suspend fun process(screenFrame: Bitmap) {
        // No more events enabled, there is nothing more to do. Stop the detection.
        if (processingState.areAllEventsDisabled()) {
            onStopRequested()
            return
        }

        // Handle all trigger events enabled during previous processing
        if (!processingState.areAllTriggerEventsDisabled()) {
            progressListener?.onEventsListProcessingStarted(EventType.Trigger)
            processTriggerEvents()
            progressListener?.onEventsProcessingCompleted(EventType.Trigger)
        }

        // Reset any values that needs to be reset for each iteration
        // After the triggers to let them handle changes, before the image processing to start capturing values before
        processingState.clearIterationState()

        // Handle the image detection
        if (!processingState.areAllScreenEventsDisabled()) {
            progressListener?.onEventsListProcessingStarted(EventType.Screen)
            processScreenEvents(screenFrame)
            progressListener?.onEventsProcessingCompleted(EventType.Screen)
        }

        // Loop is completed
        actionExecutor.onScenarioLoopFinished()

        return
    }

    private suspend fun processTriggerEvents() {
        for (triggerEvent in processingState.getTriggerEvents()) {
            // Enabled state of the event might have changed during the loop
            if (!processingState.isEventEnabled(triggerEvent.id.databaseId)) continue

            // No conditions ? This should not happen, skip this event
            if (triggerEvent.conditions.isEmpty()) continue

            progressListener?.onEventProcessingStarted(triggerEvent)
            val results = conditionsVerifier.verifyConditions(
                operator = triggerEvent.conditionOperator,
                conditions = triggerEvent.conditions,
            )

            progressListener?.onEventProcessingCompleted(triggerEvent, results.fulfilled == true, results.getAllTriggerConditionsResults())
            if (results.fulfilled  == true) {
                beforeEventActions(
                    triggerEvent.id.databaseId,
                    triggerEvent.name,
                    0L,
                    triggerEvent.isBreakpoint,
                )
                actionExecutor.executeActions(triggerEvent, results)
                progressListener?.onEventActionsExecuted(triggerEvent, results.getAllTriggerConditionsResults())
            }
        }
    }

    /**
     * Resolve condition-dependent action inputs for a synchronously called event.
     *
     * Subflows still execute their action list unconditionally. Verifying their conditions here only
     * supplies positions/OCR values to actions such as "click detected condition" and "use detected
     * number". Screen conditions can only be resolved while a captured frame is active.
     */
    private suspend fun resolveSubflowResults(event: Event): ConditionsResults? =
        when (event) {
            is ScreenEvent -> {
                if (event.conditions.isEmpty()) null
                else if (isScreenFrameActive) conditionsVerifier.verifyConditions(event.conditionOperator, event.conditions)
                else withLatestScreenFrame {
                    conditionsVerifier.verifyConditions(event.conditionOperator, event.conditions)
                }
            }
            is TriggerEvent -> {
                if (event.conditions.isEmpty()) null
                else conditionsVerifier.verifyConditions(event.conditionOperator, event.conditions)
            }
        }

    private suspend fun processScreenEvents(screenFrame: Bitmap) {
        val frameStartedAt = System.nanoTime()
        // Set the current screen image
        imageDetector.setScreenBitmap(screenFrame, processingTag)
        isScreenFrameActive = true
        activeScreenFrame = screenFrame
        conditionsVerifier.onScreenFrameStarted()

        try {
            // Check all events
            for (screenEvent in processingState.getScreenEvents()) {
                val eventId = screenEvent.id.databaseId

                // Enabled state of the event might have changed during the loop
                if (!processingState.isEventEnabled(eventId)) {
                    screenEventStabilityTracker.reset(eventId)
                    continue
                }

                // No conditions ? This should not happen, skip this event
                if (screenEvent.conditions.isEmpty()) {
                    screenEventStabilityTracker.reset(eventId)
                    continue
                }

                // Event is under cooldown, skip it
                if (processingState.isCooldownRunning(screenEvent)) {
                    screenEventStabilityTracker.reset(eventId)
                    continue
                }

                progressListener?.onEventProcessingStarted(screenEvent)
                val eventStartedAt = System.nanoTime()
                val results = conditionsVerifier.verifyConditions(
                    operator = screenEvent.conditionOperator,
                    conditions = screenEvent.conditions,
                )
                logSlowOperation(
                    operation = "condition check",
                    event = screenEvent,
                    startedAt = eventStartedAt,
                    thresholdMs = SLOW_EVENT_LOG_THRESHOLD_MS,
                )

                val isFulfilled = results.fulfilled == true
                val isConfirmed =
                    if (screenEvent.conditions.any { it is ScreenCondition.Image }) {
                        screenEventStabilityTracker.isConfirmed(
                            eventId = eventId,
                            isFulfilled = isFulfilled,
                            isStrongMatch = isFulfilled && results.isStrongPositiveImageMatch(),
                        )
                    } else {
                        screenEventStabilityTracker.reset(eventId)
                        isFulfilled
                    }

                progressListener?.onEventProcessingCompleted(screenEvent, isConfirmed, results.getAllScreenConditionsResults())
                if (isConfirmed) {
                    beforeEventActions(
                        screenEvent.id.databaseId,
                        screenEvent.name,
                        elapsedMillisecondsSince(eventStartedAt),
                        screenEvent.isBreakpoint,
                    )
                    val actionsStartedAt = System.nanoTime()
                    actionExecutor.executeActions(screenEvent, results)
                    logSlowOperation(
                        operation = "actions",
                        event = screenEvent,
                        startedAt = actionsStartedAt,
                        thresholdMs = SLOW_EVENT_LOG_THRESHOLD_MS,
                    )
                    progressListener?.onEventActionsExecuted(screenEvent, results.getAllScreenConditionsResults())

                    screenEventStabilityTracker.resetAll()
                    // keepDetecting can continue on the same captured bitmap after an action. Do not
                    // reuse a result obtained before that action, even though identical conditions
                    // elsewhere in an untouched frame may share their expensive detector result.
                    conditionsVerifier.invalidateScreenFrameCache()
                    processingState.startCooldownIfNeeded(screenEvent)
                    // A smart wait releases the triggering frame so it can observe fresh captures. In that
                    // case the remaining events must wait for the next detector frame. Normal actions keep
                    // the historical keepDetecting behaviour and may continue on this same frame.
                    if (!screenEvent.keepDetecting || !isScreenFrameActive) break
                }

                // Stop processing if requested
                yield()
            }
        } finally {
            // We are done processing this frame, release it
            releaseActiveScreenFrame()
            val frameDurationMs = elapsedMillisecondsSince(frameStartedAt)
            if (frameDurationMs >= SLOW_FRAME_LOG_THRESHOLD_MS) {
                Log.i(TAG, "Slow scenario frame: ${frameDurationMs}ms")
            }
        }
    }

    private suspend fun executeSmartWait(event: Event, pause: Pause): ActionExecutionResult {
        val timeoutMs = pause.pauseDuration ?: return ActionExecutionResult.Failed("Wait timeout is missing")
        // Never compare against the bitmap that triggered this event. A wait must observe captures
        // produced after the preceding action, otherwise animated/loading screens can be misread.
        releaseActiveScreenFrame()
        val startedAt = System.nanoTime()
        var confirmedFrames = 0
        var baseline: IntArray? = null

        while (elapsedMillisecondsSince(startedAt) < timeoutMs) {
            val matched = when (pause.waitMode) {
                Pause.WaitMode.FIXED_DELAY -> true
                Pause.WaitMode.TARGET_APPEARS,
                Pause.WaitMode.TARGET_DISAPPEARS -> {
                    val targetEventId = pause.waitTargetEventId?.databaseId
                        ?: return ActionExecutionResult.Failed("Wait target event is missing")
                    val screenEvent = processingState.getEvent(targetEventId) as? ScreenEvent
                        ?: return ActionExecutionResult.Failed("Wait target event is unavailable")
                    if (screenEvent.conditions.isEmpty()) {
                        return ActionExecutionResult.Failed("Wait target has no screen conditions")
                    }
                    val fulfilled = withLatestScreenFrame {
                        conditionsVerifier.verifyConditions(
                            screenEvent.conditionOperator,
                            screenEvent.conditions,
                        ).fulfilled == true
                    } ?: false
                    if (pause.waitMode == Pause.WaitMode.TARGET_APPEARS) fulfilled else !fulfilled
                }
                Pause.WaitMode.SCREEN_STABLE,
                Pause.WaitMode.SCREEN_CHANGED -> {
                    val fingerprint = captureScreenFingerprint() ?: run {
                        delay(SMART_WAIT_POLL_INTERVAL_MS)
                        continue
                    }
                    val reference = baseline
                    if (reference == null) {
                        baseline = fingerprint
                        false
                    } else {
                        val difference = frameDifferencePercent(reference, fingerprint)
                        if (pause.waitMode == Pause.WaitMode.SCREEN_STABLE) {
                            baseline = fingerprint
                            difference <= pause.changeThresholdPercent
                        } else {
                            difference >= pause.changeThresholdPercent
                        }
                    }
                }
            }

            confirmedFrames = if (matched) confirmedFrames + 1 else 0
            if (confirmedFrames >= pause.confirmationFrames) return ActionExecutionResult.Success
            delay(SMART_WAIT_POLL_INTERVAL_MS)
        }
        return ActionExecutionResult.TimedOut(timeoutMs)
    }

    private suspend fun <T> withLatestScreenFrame(block: suspend (Bitmap) -> T): T? {
        val frame = screenFrameSupplier() ?: return null
        imageDetector.setScreenBitmap(frame, processingTag)
        isScreenFrameActive = true
        activeScreenFrame = frame
        conditionsVerifier.onScreenFrameStarted()
        return try {
            block(frame)
        } finally {
            conditionsVerifier.invalidateScreenFrameCache()
            releaseActiveScreenFrame()
        }
    }

    private fun releaseActiveScreenFrame() {
        val frame = activeScreenFrame ?: return
        activeScreenFrame = null
        isScreenFrameActive = false
        imageDetector.releaseScreenBitmap(frame)
    }

    private suspend fun captureScreenFingerprint(): IntArray? = withLatestScreenFrame(::createFrameFingerprint)

    private fun createFrameFingerprint(bitmap: Bitmap): IntArray {
        val result = IntArray(FINGERPRINT_SIZE * FINGERPRINT_SIZE)
        val maxX = (bitmap.width - 1).coerceAtLeast(0)
        val maxY = (bitmap.height - 1).coerceAtLeast(0)
        for (sampleY in 0 until FINGERPRINT_SIZE) {
            val y = sampleY * maxY / (FINGERPRINT_SIZE - 1)
            for (sampleX in 0 until FINGERPRINT_SIZE) {
                val x = sampleX * maxX / (FINGERPRINT_SIZE - 1)
                result[sampleY * FINGERPRINT_SIZE + sampleX] = bitmap[x, y]
            }
        }
        return result
    }

    private fun frameDifferencePercent(first: IntArray, second: IntArray): Int {
        if (first.size != second.size || first.isEmpty()) return 100
        var difference = 0L
        first.indices.forEach { index ->
            val a = first[index]
            val b = second[index]
            difference += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            difference += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            difference += abs((a and 0xFF) - (b and 0xFF))
        }
        return ((difference * 100L) / (first.size * 3L * 255L)).toInt()
    }

    /**
     * A positive image match that is clearly above its own configured acceptance threshold can be
     * trusted immediately. Borderline and negative-image matches still use the multi-frame filter.
     * This avoids forcing a second full scenario scan for normal matches while keeping protection
     * against unstable results close to the configured threshold.
     */
    private fun ConditionsResults.isStrongPositiveImageMatch(): Boolean {
        val imageResults = getAllScreenConditionsResults()
            .filter { it.condition is ScreenCondition.Image }

        return imageResults.isNotEmpty() && imageResults.all { result ->
            val condition = result.condition as ScreenCondition.Image
            // Native image matching reports a normalized confidence in [0, 1], while the
            // configured threshold is an allowed difference percentage in [0, 100].
            val minimumConfidence = 1.0 - condition.threshold / 100.0
            val immediateConfidence =
                (minimumConfidence + singleFrameConfidenceMargin).coerceAtMost(1.0)

            condition.shouldBeDetected &&
                    result.isFulfilled &&
                    result.haveBeenDetected &&
                    result.confidenceRate >= immediateConfidence
        }
    }

    private fun logSlowOperation(
        operation: String,
        event: ScreenEvent,
        startedAt: Long,
        thresholdMs: Long,
    ) {
        val durationMs = elapsedMillisecondsSince(startedAt)
        if (durationMs >= thresholdMs) {
            Log.i(TAG, "Slow $operation: event=${event.id.databaseId} (${event.name}), ${durationMs}ms")
        }
    }

    private fun elapsedMillisecondsSince(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
}
