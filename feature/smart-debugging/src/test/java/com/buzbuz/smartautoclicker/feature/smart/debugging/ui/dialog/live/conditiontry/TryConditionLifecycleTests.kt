/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.conditiontry

import android.content.Context
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.usecase.GetDebugLiveDetectionResultUseCase
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TryConditionLifecycleTests {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<SmartProcessingRepository>(relaxed = true)
    private val useCase = mockk<GetDebugLiveDetectionResultUseCase>()
    private val context = mockk<Context>()
    private val scenario = mockk<Scenario>()
    private val condition = mockk<ScreenCondition.Image>().also { every { it.threshold } returns 7 }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { repository.detectionState } returns MutableStateFlow(DetectionState.RECORDING)
        every { useCase.invoke(any(), any()) } returns MutableStateFlow(null)
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun leavingBeforeStartupDelayCancelsPendingTest() = runTest(dispatcher) {
        val vm = TryImageConditionViewModel(useCase, repository)
        vm.startTry(context, scenario, condition)
        assertEquals(7, vm.getSelectedThreshold())
        vm.stopTry()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.tryScreenCondition(any(), any(), any()) }
        coVerify(exactly = 1) { repository.stopDetection() }
    }

    @Test fun replacingPendingTestStartsOnlyTheLatestCondition() = runTest(dispatcher) {
        val vm = TryImageConditionViewModel(useCase, repository)
        val latest = mockk<ScreenCondition.Image>().also { every { it.threshold } returns 12 }
        vm.startTry(context, scenario, condition)
        vm.startTry(context, scenario, latest)
        advanceUntilIdle()
        assertEquals(12, vm.getSelectedThreshold())
        coVerify(exactly = 0) { repository.tryScreenCondition(context, scenario, condition) }
        coVerify(exactly = 1) { repository.tryScreenCondition(context, scenario, latest) }
    }
}
