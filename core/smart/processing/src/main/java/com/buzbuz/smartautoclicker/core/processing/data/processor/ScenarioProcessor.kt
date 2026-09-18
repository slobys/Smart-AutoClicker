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

import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.domain.model.event.TriggerEvent
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.domain.EventType
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingListener

import kotlinx.coroutines.yield

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
    androidExecutor: AndroidActionExecutor,
    unblockWorkaroundEnabled: Boolean = false,
    private val onStopRequested: () -> Unit,
    private val progressListener: SmartProcessingListener?,
    screenEventConfirmationHits: Int = 1,
    screenEventConfirmationWindow: Int = 3,
    private val singleFrameConfidenceMargin: Double = 0.5,
) {

    private companion object {
        private const val TAG = "ScenarioProcessor"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val SLOW_EVENT_LOG_THRESHOLD_MS = 100L
        private const val SLOW_FRAME_LOG_THRESHOLD_MS = 250L
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
    )
    /** Filters one-frame visual glitches before actions are executed. */
    private val screenEventStabilityTracker = ScreenEventStabilityTracker(
        requiredHits = screenEventConfirmationHits,
        windowSize = screenEventConfirmationWindow,
    )

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
                actionExecutor.executeActions(triggerEvent, results)
                progressListener?.onEventActionsExecuted(triggerEvent, results.getAllTriggerConditionsResults())
            }
        }
    }

    private suspend fun processScreenEvents(screenFrame: Bitmap) {
        val frameStartedAt = System.nanoTime()
        // Set the current screen image
        imageDetector.setScreenBitmap(screenFrame, processingTag)

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
                    processingState.startCooldownIfNeeded(screenEvent)
                    if (!screenEvent.keepDetecting) break
                }

                // Stop processing if requested
                yield()
            }
        } finally {
            // We are done processing this frame, release it
            imageDetector.releaseScreenBitmap(screenFrame)
            val frameDurationMs = elapsedMillisecondsSince(frameStartedAt)
            if (frameDurationMs >= SLOW_FRAME_LOG_THRESHOLD_MS) {
                Log.i(TAG, "Slow scenario frame: ${frameDurationMs}ms")
            }
        }
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
            val minimumConfidence = 100.0 - condition.threshold
            val immediateConfidence =
                (minimumConfidence + singleFrameConfidenceMargin).coerceAtMost(100.0)

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
