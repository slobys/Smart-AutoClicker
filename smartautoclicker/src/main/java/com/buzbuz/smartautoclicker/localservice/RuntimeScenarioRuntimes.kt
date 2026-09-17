/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.localservice

import android.content.Context

import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.dumb.engine.DumbEngine
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.DebuggingRepository
import com.buzbuz.smartautoclicker.feature.revenue.IRevenueRepository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class DumbScenarioRuntime(private val engine: DumbEngine) {
    val isRunning: Boolean get() = engine.isRunning.value

    fun prepareForSwitch(): Boolean = isRunning.also { if (it) engine.stopDumbScenario() }
    fun load(scenario: DumbScenario) { engine.release(); engine.init(scenario) }
    fun start() = engine.startDumbScenario()
    fun stop() = engine.stopDumbScenario()
    fun release() = engine.release()
}

internal class SmartScenarioRuntime(
    private val context: Context,
    private val repository: SmartProcessingRepository,
    private val revenueRepository: IRevenueRepository,
    private val debuggingRepository: DebuggingRepository,
) {
    val isRunning: Boolean get() = repository.isRunning()

    fun load(scenario: Scenario) = repository.setScenarioId(scenario.id, markAsUsed = true)

    suspend fun start() {
        repository.startDetection(
            context = context,
            autoStopDuration = revenueRepository.consumeTrial(),
            liveDebugging = debuggingRepository.isDebugViewEnabled(),
            generateReport = debuggingRepository.isDebugReportEnabled(),
        )
    }

    fun stop() = repository.stopDetection()
    fun stopScreenRecord() = repository.stopScreenRecord()

    suspend fun prepareForSwitch(): Boolean? {
        val state = withTimeoutOrNull(RUNTIME_TRANSITION_TIMEOUT_MS) {
            repository.detectionState.first { value ->
                value == DetectionState.RECORDING || value == DetectionState.DETECTING || value.isRuntimeError()
            }
        } ?: return null
        if (state.isRuntimeError()) return null
        if (state != DetectionState.DETECTING) return false

        repository.stopDetection()
        val readyState = withTimeoutOrNull(RUNTIME_TRANSITION_TIMEOUT_MS) {
            repository.detectionState.first { value ->
                value == DetectionState.RECORDING || value.isRuntimeError()
            }
        }
        return if (readyState == DetectionState.RECORDING) true else null
    }
}

internal fun DetectionState.isRuntimeError(): Boolean = when (this) {
    DetectionState.ERROR_NO_NATIVE_LIB,
    DetectionState.ERROR_OCR_MODEL_NOT_FOUND,
    DetectionState.ERROR_SCREEN_IMAGE_CAPTURE_FAILED -> true
    else -> false
}
