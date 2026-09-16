/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.domain.usecase.scenario

import android.content.Context
import android.content.Intent

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState

import io.mockk.every
import io.mockk.mockk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.PrintWriter

import kotlin.time.Duration

class SwitchScenarioUseCaseTest {

    private val currentScenario = scenario(id = 1L, name = "Current", eventCount = 2)
    private val targetScenario = scenario(id = 2L, name = "Target", eventCount = 3)

    @Test
    fun availableScenarios_filtersEmptyScenariosAndSortsByName() = runTest {
        val repository = mockk<IRepository>()
        every { repository.scenarios } returns flowOf(listOf(
                scenario(id = 3L, name = "Zulu", eventCount = 1),
                scenario(id = 4L, name = "Empty", eventCount = 0),
                scenario(id = 5L, name = "alpha", eventCount = 1),
            ))
        val useCase = SwitchScenarioUseCase(repository, fakeProcessingRepository())

        assertEquals(listOf("alpha", "Zulu"), useCase.availableScenarios.first().map { it.name })
    }

    @Test
    fun switchTo_whenPaused_changesScenarioWithoutRequestingRestart() = runTest {
        val processingRepository = fakeProcessingRepository()
        val useCase = SwitchScenarioUseCase(mockScenarioRepository(), processingRepository)

        val result = useCase.switchTo(targetScenario)

        assertEquals(SwitchScenarioUseCase.Result.Switched(restartDetection = false), result)
        assertEquals(0, processingRepository.stopDetectionCalls)
        assertEquals(targetScenario.id, processingRepository.lastSetScenarioId)
        assertTrue(processingRepository.lastMarkAsUsed)
    }

    @Test
    fun switchTo_whenRunning_stopsDetectionAndRequestsRestart() = runTest {
        val detectionState = MutableStateFlow(DetectionState.DETECTING)
        val processingRepository = fakeProcessingRepository(states = detectionState) {
            detectionState.value = DetectionState.RECORDING
        }
        val useCase = SwitchScenarioUseCase(mockScenarioRepository(), processingRepository)

        val result = useCase.switchTo(targetScenario)

        assertEquals(SwitchScenarioUseCase.Result.Switched(restartDetection = true), result)
        assertEquals(1, processingRepository.stopDetectionCalls)
        assertEquals(targetScenario.id, processingRepository.lastSetScenarioId)
    }

    @Test
    fun switchTo_currentScenario_doesNothing() = runTest {
        val processingRepository = fakeProcessingRepository()
        val useCase = SwitchScenarioUseCase(mockScenarioRepository(), processingRepository)

        val result = useCase.switchTo(currentScenario)

        assertTrue(result is SwitchScenarioUseCase.Result.AlreadySelected)
        assertEquals(0, processingRepository.stopDetectionCalls)
        assertEquals(null, processingRepository.lastSetScenarioId)
    }

    @Test
    fun switchTo_whenDetectorStopsWithError_keepsCurrentScenario() = runTest {
        val detectionState = MutableStateFlow(DetectionState.DETECTING)
        val processingRepository = fakeProcessingRepository(states = detectionState) {
            detectionState.value = DetectionState.ERROR_SCREEN_IMAGE_CAPTURE_FAILED
        }
        val useCase = SwitchScenarioUseCase(mockScenarioRepository(), processingRepository)

        val result = useCase.switchTo(targetScenario)

        assertTrue(result is SwitchScenarioUseCase.Result.Failed)
        assertEquals(null, processingRepository.lastSetScenarioId)
    }

    private fun mockScenarioRepository(): IRepository = mockk<IRepository>().also { repository ->
        every { repository.scenarios } returns flowOf(listOf(currentScenario, targetScenario))
    }

    private fun fakeProcessingRepository(
        states: MutableStateFlow<DetectionState> = MutableStateFlow(DetectionState.RECORDING),
        onStopDetection: () -> Unit = {},
    ): FakeSmartProcessingRepository = FakeSmartProcessingRepository(
        initialScenarioId = currentScenario.id,
        states = states,
        onStopDetection = onStopDetection,
    )

    private fun scenario(id: Long, name: String, eventCount: Int): Scenario = Scenario(
        id = Identifier(databaseId = id),
        name = name,
        detectionQuality = 600,
        eventCount = eventCount,
    )
}

private class FakeSmartProcessingRepository(
    initialScenarioId: Identifier,
    private val states: MutableStateFlow<DetectionState>,
    private val onStopDetection: () -> Unit,
) : SmartProcessingRepository {

    private val currentScenarioId = MutableStateFlow<Identifier?>(initialScenarioId)

    override val scenarioId: StateFlow<Identifier?> = currentScenarioId
    override val canStartDetection: Flow<Boolean> = flowOf(true)
    override val detectionState: Flow<DetectionState> = states

    var stopDetectionCalls: Int = 0
        private set
    var lastSetScenarioId: Identifier? = null
        private set
    var lastMarkAsUsed: Boolean = false
        private set

    override fun getScenarioId(): Identifier? = currentScenarioId.value

    override fun isRunning(): Boolean = states.value == DetectionState.DETECTING

    override fun setScenarioId(identifier: Identifier, markAsUsed: Boolean) {
        currentScenarioId.value = identifier
        lastSetScenarioId = identifier
        lastMarkAsUsed = markAsUsed
    }

    override fun stopDetection() {
        stopDetectionCalls++
        onStopDetection()
    }

    override fun setProjectionErrorHandler(handler: () -> Unit) = Unit
    override fun startScreenRecord(resultCode: Int, data: Intent) = Unit
    override suspend fun startDetection(
        context: Context,
        liveDebugging: Boolean,
        generateReport: Boolean,
        autoStopDuration: Duration?,
    ) = Unit
    override fun stopScreenRecord() = Unit
    override suspend fun tryEvent(context: Context, scenario: Scenario, event: ScreenEvent) = Unit
    override suspend fun tryScreenCondition(
        context: Context,
        scenario: Scenario,
        condition: ScreenCondition,
    ) = Unit
    override suspend fun tryAction(context: Context, scenario: Scenario, action: Action) = Unit
    override fun dump(writer: PrintWriter, prefix: CharSequence) = Unit
}
