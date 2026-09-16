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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.domain.usecase.scenario

import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

import javax.inject.Inject

/** Switches the smart scenario while keeping the current screen capture session alive. */
class SwitchScenarioUseCase @Inject constructor(
    scenarioRepository: IRepository,
    private val processingRepository: SmartProcessingRepository,
) {

    /** Scenarios that can be started from the runtime scenario menu. */
    val availableScenarios: Flow<List<Scenario>> = scenarioRepository.scenarios
        .map { scenarios ->
            scenarios
                .filter { scenario -> scenario.eventCount > 0 }
                .sortedBy { scenario -> scenario.name.lowercase() }
        }

    /**
     * Loads [scenario] without stopping MediaProjection.
     *
     * If detection is running, it is first stopped and the caller is told to restart it after the switch.
     */
    suspend fun switchTo(scenario: Scenario): Result {
        if (processingRepository.getScenarioId() == scenario.id) return Result.AlreadySelected

        val stateBeforeSwitch = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
            processingRepository.detectionState.first { state ->
                state == DetectionState.RECORDING || state == DetectionState.DETECTING || state.isError()
            }
        }
        if (stateBeforeSwitch == null || stateBeforeSwitch.isError()) return Result.Failed

        val restartDetection = stateBeforeSwitch == DetectionState.DETECTING
        if (restartDetection) {
            processingRepository.stopDetection()

            val readyToSwitch = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
                processingRepository.detectionState.first { state ->
                    state == DetectionState.RECORDING || state.isError()
                }
            }
            if (readyToSwitch != DetectionState.RECORDING) return Result.Failed
        }

        processingRepository.setScenarioId(scenario.id, markAsUsed = true)
        return Result.Switched(restartDetection = restartDetection)
    }

    sealed class Result {
        data class Switched(val restartDetection: Boolean) : Result()
        data object AlreadySelected : Result()
        data object Failed : Result()
    }
}

private fun DetectionState.isError(): Boolean = when (this) {
    DetectionState.ERROR_NO_NATIVE_LIB,
    DetectionState.ERROR_OCR_MODEL_NOT_FOUND,
    DetectionState.ERROR_SCREEN_IMAGE_CAPTURE_FAILED -> true
    else -> false
}

private const val SWITCH_TIMEOUT_MS = 10_000L
