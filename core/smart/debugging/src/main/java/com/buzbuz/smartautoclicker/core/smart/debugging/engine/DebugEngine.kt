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
package com.buzbuz.smartautoclicker.core.smart.debugging.engine

import android.graphics.Rect
import android.util.Size

import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers.IO
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.domain.model.event.TriggerEvent
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.domain.EventType
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingListener
import com.buzbuz.smartautoclicker.core.processing.domain.model.ProcessedConditionResult
import com.buzbuz.smartautoclicker.core.smart.debugging.data.DebugReportLocalDataSource
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.model.live.DebugLiveEventConditionResult
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.model.live.DebugLiveEventOccurrence
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.model.report.DebugReportConditionResult
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.model.report.DebugReportEventOccurrence
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.model.report.DebugReportOverview
import com.buzbuz.smartautoclicker.core.smart.debugging.engine.recorder.CounterValuesRecorder
import com.buzbuz.smartautoclicker.core.smart.debugging.engine.recorder.DebugReportOverviewRecorder
import com.buzbuz.smartautoclicker.core.smart.debugging.engine.recorder.EventOccurrencesRecorder
import com.buzbuz.smartautoclicker.core.smart.debugging.engine.recorder.EventStateRecorder
import com.buzbuz.smartautoclicker.core.smart.debugging.data.mapping.toCountersInitProtobuf
import com.buzbuz.smartautoclicker.core.smart.debugging.data.mapping.toProtobuf
import com.buzbuz.smartautoclicker.core.smart.debugging.engine.recorder.ScreenConditionOccurrenceRecorder

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.toList
import kotlin.time.Duration.Companion.milliseconds


/** Engine for the debugging of a scenario processing. */
@Singleton
internal class DebugEngine @Inject constructor(
    @Dispatcher(IO) ioDispatcher: CoroutineDispatcher,
    debugReportLocalDataSource: DebugReportLocalDataSource,
    private val overviewRecorder: DebugReportOverviewRecorder,
    private val eventOccurrencesRecorder: EventOccurrencesRecorder,
    private val screenConditionOccurrenceRecorder: ScreenConditionOccurrenceRecorder,
    private val counterValuesRecorder: CounterValuesRecorder,
    private val eventStateRecorder: EventStateRecorder,
) : SmartProcessingListener {

    private val reportWriter = BoundedReportWriter(ioDispatcher, debugReportLocalDataSource)

    private var isReportEnabled: Boolean = false
    private var shouldGenerateLiveEvents: Boolean = false

    private val shouldWriteReport: Boolean
        get() = isReportEnabled

    private val _isDebuggingSession: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isDebuggingSession: StateFlow<Boolean> = _isDebuggingSession

    private val _lastEventProcessed: MutableStateFlow<DebugLiveEventOccurrence?> = MutableStateFlow(null)
    val lastEventProcessed: StateFlow<DebugLiveEventOccurrence?> = _lastEventProcessed


    @Synchronized override fun onSessionStarted(
        scenario: Scenario,
        counters: List<Counter>,
        generateLiveEvents: Boolean,
        generateReport: Boolean,
    ) {
        resetRecorders()
        _lastEventProcessed.value = null
        isReportEnabled = generateReport
        shouldGenerateLiveEvents = generateLiveEvents
        _isDebuggingSession.value = true

        if (shouldWriteReport) {
            overviewRecorder.onSessionStart(scenario)
            counterValuesRecorder.onSessionStarted(counters)

            reportWriter.start(counters.toCountersInitProtobuf())
        }
    }

    // Processing started on current frame
    @Synchronized override fun onEventsListProcessingStarted(eventType: EventType) {
        if (!shouldWriteReport) return

        overviewRecorder.onFrameProcessingStarted()
    }

    // Processing started for current Event
    @Synchronized override fun onEventProcessingStarted(event: Event) {
        if (!_isDebuggingSession.value) return
        eventOccurrencesRecorder.onEventProcessingStarted()
        screenConditionOccurrenceRecorder.onEventProcessingStarted()

        if (!shouldWriteReport) return
        counterValuesRecorder.onEventProcessingStarted()
        eventStateRecorder.onEventProcessingStarted()
    }

    @Synchronized override fun onEventProcessingCompleted(event: Event, fulfilled: Boolean, results: List<ProcessedConditionResult>) {
        if (!_isDebuggingSession.value) return
        if (fulfilled) eventOccurrencesRecorder.onEventFulfilled(event)
        if (!shouldGenerateLiveEvents) return

        _lastEventProcessed.update {
            @Suppress("UNCHECKED_CAST")
            when (event) {
                is ScreenEvent -> getLiveScreenEventOccurrence(
                    event = event,
                    fulfilled = fulfilled,
                    results = results as List<ProcessedConditionResult.Screen>,
                )
                is TriggerEvent -> getLiveTriggerEventOccurrence(
                    event = event,
                    fulfilled = fulfilled,
                    results = results as List<ProcessedConditionResult.Trigger>,
                )
            }
        }
    }

    // Processing ended for current Event
    @Synchronized override fun onEventActionsExecuted(event: Event, results: List<ProcessedConditionResult>) {
        if (!shouldWriteReport) return

        overviewRecorder.onActionsExecuted(event)

        @Suppress("UNCHECKED_CAST")
        when (event) {
            is ScreenEvent -> {
                writeImageEventToReport(event)
                screenConditionOccurrenceRecorder.reset()
            }

            is TriggerEvent ->
                writeTriggerEventToReport(event, results as List<ProcessedConditionResult.Trigger>)
        }
    }

    // Processing ended on current frame
    @Synchronized override fun onEventsProcessingCompleted(eventType: EventType) {
        if (!shouldWriteReport) return

        overviewRecorder.onFrameProcessingStopped()
    }

    @Synchronized override fun onEventsProcessingCancelled() {
        if (!shouldWriteReport) return

        overviewRecorder.onFrameProcessingStopped()
        screenConditionOccurrenceRecorder.reset()
        eventOccurrencesRecorder.reset()
    }

    // Image Condition is processed
    @Synchronized override fun onScreenConditionProcessingStarted() {
        if (!shouldWriteReport) return

        screenConditionOccurrenceRecorder.onImageConditionProcessingStarted()
    }

    // Called anyway,even if not matched
    @Synchronized override fun onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen) {
        if (!shouldWriteReport) return
        if (screenConditionOccurrenceRecorder.screenConditionResults.size >= MAX_EVENT_DETAILS) {
            disableOverloadedReport()
            return
        }

        screenConditionOccurrenceRecorder.onImageConditionProcessingCompleted(result)
    }

    @Synchronized override fun onCounterValueChanged(counterName: String, previousValue: Double, newValue: Double) {
        if (!shouldWriteReport) return
        if (counterValuesRecorder.eventCounterChanges.size >= MAX_EVENT_DETAILS) {
            disableOverloadedReport()
            return
        }
        counterValuesRecorder.onCounterValueChanged(counterName, previousValue, newValue)
    }

    @Synchronized override fun onEventStateChanged(event: Event, newValue: Boolean) {
        if (!shouldWriteReport) return
        if (eventStateRecorder.changes.size >= MAX_EVENT_DETAILS) {
            disableOverloadedReport()
            return
        }
        eventStateRecorder.onEventStateChanged(event, newValue)
    }

    @Synchronized override fun onSessionEnded() {
        if (shouldWriteReport) {
            reportWriter.finish(
                overview = DebugReportOverview(
                    scenarioId = overviewRecorder.scenarioId,
                    duration = overviewRecorder.sessionDurationMs.milliseconds,
                    frameCount = overviewRecorder.frameCount,
                    averageFrameProcessingDuration = overviewRecorder.averageFrameProcessingDurationMs.milliseconds,
                    imageEventFulfilledCount = overviewRecorder.imageEventFulfilledCount,
                    triggerEventFulfilledCount = overviewRecorder.triggerEventFulfilledCount,
                    counterNames = counterValuesRecorder.counterNames.toSet(),
                )
            )
        }
        resetRecorders()
        _lastEventProcessed.value = null
        _isDebuggingSession.value = false
        isReportEnabled = false
        shouldGenerateLiveEvents = false
    }

    private fun resetRecorders() {
        overviewRecorder.reset()
        counterValuesRecorder.reset()
        eventStateRecorder.reset()
        eventOccurrencesRecorder.reset()
        screenConditionOccurrenceRecorder.reset()
    }

    private fun disableOverloadedReport() {
        isReportEnabled = false
        reportWriter.cancel()
        screenConditionOccurrenceRecorder.reset()
        counterValuesRecorder.reset()
        eventStateRecorder.reset()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getLiveScreenEventOccurrence(event: Event, fulfilled: Boolean, results: List<ProcessedConditionResult.Screen>): DebugLiveEventOccurrence.Screen =
        DebugLiveEventOccurrence.Screen(
            event = event as ScreenEvent,
            fulfilled = fulfilled,
            fulfilledCount = eventOccurrencesRecorder.getEventOccurrences(event.id.databaseId),
            processingDurationMs = eventOccurrencesRecorder.getLastEventDurationMs(),
            timestamp = System.currentTimeMillis(),
            conditionsResults = results.map { result ->
                DebugLiveEventConditionResult.Screen(
                    condition = result.condition,
                    isFulfilled = result.isFulfilled,
                    isDetected = result.haveBeenDetected,
                    confidenceRate = result.confidenceRate,
                    detectionArea = result.getDetectionArea(),
                    numberDetected = result.numberDetected,
                    numberComparisonFulfilled = result.numberComparisonFulfilled,
                )
            },
        )

    @Suppress("UNCHECKED_CAST")
    private fun getLiveTriggerEventOccurrence(event: Event, fulfilled: Boolean, results: List<ProcessedConditionResult.Trigger>): DebugLiveEventOccurrence.Trigger =
        DebugLiveEventOccurrence.Trigger(
            event = event as TriggerEvent,
            fulfilled = fulfilled,
            fulfilledCount = eventOccurrencesRecorder.getEventOccurrences(event.id.databaseId),
            processingDurationMs = eventOccurrencesRecorder.getLastEventDurationMs(),
            timestamp = System.currentTimeMillis(),
            conditionsResults = results.map { result ->
                DebugLiveEventConditionResult.Trigger(
                    condition = result.condition,
                    isFulfilled = result.isFulfilled,
                )
            },
        )

    private fun writeImageEventToReport(event: ScreenEvent) {
        isReportEnabled = reportWriter.write(
            DebugReportEventOccurrence.ScreenEvent(
                eventId = event.id.databaseId,
                frameNumber = overviewRecorder.frameCount,
                relativeTimestampMs = overviewRecorder.sessionDurationMs,
                conditionsResults = screenConditionOccurrenceRecorder.screenConditionResults.toList(),
                counterChanges = counterValuesRecorder.eventCounterChanges.toList(),
                eventStateChanges = eventStateRecorder.changes.toList(),
            ).toProtobuf()
        )
    }

    private fun writeTriggerEventToReport(event: TriggerEvent, results: List<ProcessedConditionResult.Trigger>) {
        isReportEnabled = reportWriter.write(
            DebugReportEventOccurrence.TriggerEvent(
                eventId = event.id.databaseId,
                relativeTimestampMs = overviewRecorder.sessionDurationMs,
                counterChanges = counterValuesRecorder.eventCounterChanges.toList(),
                eventStateChanges = eventStateRecorder.changes.toList(),
                conditionsResults = results.map { result ->
                    DebugReportConditionResult.TriggerCondition(
                        conditionId = result.condition.id.databaseId,
                        isFulFilled = result.isFulfilled,
                    )
                }
            ).toProtobuf()
        )
    }
}

private fun ProcessedConditionResult.Screen.getDetectionArea(): Rect? {
    val pos = position ?: return null
    val size = size ?: return null

    val halfSize = when (val cond = condition) {
        is ScreenCondition.Color ->
            Size(cond.detectionArea.width() / 2, cond.detectionArea.height() / 2)

        is ScreenCondition.Image,
        is ScreenCondition.Text,
        is ScreenCondition.Number ->
            Size(size.x / 2,  size.y / 2)
    }

    return if (pos.x == 0 && pos.y == 0) Rect()
    else Rect(
        pos.x - halfSize.width,
        pos.y - halfSize.height,
        pos.x + halfSize.width,
        pos.y + halfSize.height,
    )
}

private const val MAX_EVENT_DETAILS = 512
