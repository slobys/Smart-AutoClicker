package com.buzbuz.smartautoclicker.localservice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.engine.DumbEngine
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class RuntimePauseDuringLoadTests {
    @After fun tearDown() { Dispatchers.resetMain(); clearAllMocks() }

    @Test fun switchPreparationStopsSmartLoadBeforeRunning() = runTest {
        val repository = mockk<SmartProcessingRepository>(relaxed = true)
        every { repository.detectionState } returns flowOf(DetectionState.RECORDING)
        val runtime = SmartScenarioRuntime(ApplicationProvider.getApplicationContext(), repository,
            mockk(relaxed = true), mockk(relaxed = true))
        assertFalse(runtime.prepareForSwitch()!!)
        verify(exactly = 1) { repository.stopDetection() }
    }

    @Test fun switchPreparationStopsSimpleLoadBeforeRunning() {
        val engine = mockk<DumbEngine>(relaxed = true)
        every { engine.isRunning } returns MutableStateFlow(false)
        assertFalse(DumbScenarioRuntime(engine).prepareForSwitch())
        verify(exactly = 1) { engine.stopDumbScenario() }
    }

    @Test fun notificationPauseReachesBothEnginesEvenWhenNotRunning() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val smart = mockk<SmartProcessingRepository>(relaxed = true)
        every { smart.detectionState } returns flowOf(DetectionState.RECORDING)
        every { smart.isRunning() } returns false
        val dumb = mockk<DumbEngine>(relaxed = true)
        every { dumb.isRunning } returns MutableStateFlow(false)
        val overlays = mockk<OverlayManager>(relaxed = true)
        every { overlays.isStackHidden } returns MutableStateFlow(false)
        every { overlays.backStackTopFlow } returns MutableStateFlow(null)
        val scenarios = mockk<IRepository>(relaxed = true)
        every { scenarios.scenarios } returns flowOf(emptyList())
        val dumbScenarios = mockk<IDumbRepository>(relaxed = true)
        every { dumbScenarios.dumbScenarios } returns flowOf(emptyList())
        val service = LocalService(
            context = ApplicationProvider.getApplicationContext<Context>(),
            overlayManager = overlays, appComponentsProvider = mockk(relaxed = true),
            settingsRepository = mockk(relaxed = true), smartProcessingRepository = smart,
            dumbEngine = dumb, smartScenarioRepository = scenarios, dumbScenarioRepository = dumbScenarios,
            tutorialRepository = mockk(relaxed = true), revenueRepository = mockk(relaxed = true),
            debuggingRepository = mockk(relaxed = true), onStart = { _, _, _ -> },
            onScenarioChanged = { _, _ -> }, onStop = {},
        )
        LocalService::class.java.getDeclaredMethod("pause").apply { isAccessible = true }.invoke(service)
        runCurrent()
        verify(exactly = 1) { smart.stopDetection() }
        verify(exactly = 1) { dumb.stopDumbScenario() }
        service.release()
        advanceUntilIdle()
    }
}
