package com.buzbuz.smartautoclicker.localservice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.dumb.engine.DumbEngine
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeStopReason
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class LocalServiceCleanupTests {
    @After fun tearDown() { Dispatchers.resetMain(); clearAllMocks() }

    @Test fun releaseAfterStopWaitsForRuntimeBeforeDiscardingScopeAndBitmaps() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val finished = CompletableDeferred<Unit>()
        val smart = mockk<SmartProcessingRepository>(relaxed = true)
        every { smart.detectionState } returns flowOf(DetectionState.RECORDING)
        coEvery { smart.awaitStopped() } coAnswers { finished.await() }
        val overlays = mockk<OverlayManager>(relaxed = true)
        every { overlays.isStackHidden } returns MutableStateFlow(false)
        every { overlays.backStackTopFlow } returns MutableStateFlow(null)
        val dumb = mockk<DumbEngine>(relaxed = true)
        every { dumb.isRunning } returns MutableStateFlow(false)
        val scenarios = mockk<IRepository>(relaxed = true)
        every { scenarios.scenarios } returns flowOf(emptyList())
        val dumbScenarios = mockk<IDumbRepository>(relaxed = true)
        every { dumbScenarios.dumbScenarios } returns flowOf(emptyList())
        var onStopCount = 0
        val service = LocalService(
            context = ApplicationProvider.getApplicationContext<Context>(),
            overlayManager = overlays, appComponentsProvider = mockk(relaxed = true),
            settingsRepository = mockk(relaxed = true), smartProcessingRepository = smart,
            dumbEngine = dumb, smartScenarioRepository = scenarios, dumbScenarioRepository = dumbScenarios,
            tutorialRepository = mockk(relaxed = true), revenueRepository = mockk(relaxed = true),
            debuggingRepository = mockk(relaxed = true), onStart = { _, _, _ -> },
            onScenarioChanged = { _, _ -> }, onStop = { onStopCount++ },
        )
        val scenario = mockk<DumbScenario>(relaxed = true)
        every { scenario.id } returns Identifier(databaseId = 1)
        service.startDumbScenario(scenario)
        // Same call order as an accessibility disconnect, before the delayed start has finished.
        service.stopScenario()
        service.release()
        runCurrent()
        verify { smart.stopScreenRecord(RuntimeStopReason.SERVICE_DISCONNECTED) }
        coVerify { smart.awaitStopped() }
        assertEquals(0, onStopCount)
        verify(exactly = 0) { overlays.closeAll(any()) }
        finished.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, onStopCount)
        verify(exactly = 1) { overlays.closeAll(any()) }
        verify(exactly = 0) { overlays.navigateTo(any(), any(), any()) }
    }
}
