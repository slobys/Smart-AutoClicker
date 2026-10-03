package com.buzbuz.smartautoclicker.core.processing.tests

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.data.DetectorEngine
import com.buzbuz.smartautoclicker.core.processing.data.DetectorState
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepositoryImpl
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class SmartProcessingStartupTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val data = mockk<IRepository>(relaxed = true)
    private val engine = mockk<DetectorEngine>(relaxed = true)
    private val state = MutableStateFlow(DetectorState.RECORDING)
    private val scenario = Scenario(Identifier(databaseId = 1), "Startup race", 100)

    @After fun tearDown() = clearAllMocks()

    private fun TestScope.repository(): SmartProcessingRepositoryImpl {
        every { engine.state } returns state
        every { data.getEventsFlow(any()) } returns flowOf(emptyList())
        coEvery { data.getScenario(any()) } returns scenario
        coEvery { data.getScreenEvents(any()) } returns emptyList()
        coEvery { data.getTriggerEvents(any()) } returns emptyList()
        coEvery { data.getCounters(any()) } returns emptyList()
        return SmartProcessingRepositoryImpl(context, StandardTestDispatcher(testScheduler),
            StandardTestDispatcher(testScheduler), data, engine).apply { setScenarioId(scenario.id, false) }
    }

    private fun verifyNoStart() = verify(exactly = 0) {
        engine.startDetection(any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test fun pauseDuringLoadPreventsStartAndAutoStopTimer() = runTest {
        val repo = repository()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getScreenEvents(any()) } coAnswers { gate.await(); emptyList() }
        val load = launch { repo.startDetection(context, false, false, 1.seconds) }
        runCurrent()
        repo.stopDetection()
        gate.complete(Unit)
        load.join()
        advanceTimeBy(2_000)
        runCurrent()
        verifyNoStart()
        verify(exactly = 1) { engine.stopDetection(any()) }
    }

    @Test fun closeDuringLoadPreventsStart() = runTest {
        val repo = repository()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getCounters(any()) } coAnswers { gate.await(); emptyList() }
        val load = launch { repo.startDetection(context, false, false) }
        runCurrent()
        repo.stopScreenRecord()
        gate.complete(Unit)
        load.join()
        verifyNoStart()
    }

    @Test fun switchScenarioDuringLoadPreventsOldStart() = runTest {
        val repo = repository()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getTriggerEvents(any()) } coAnswers { gate.await(); emptyList() }
        val load = launch { repo.startDetection(context, false, false) }
        runCurrent()
        repo.setScenarioId(Identifier(databaseId = 2), false)
        gate.complete(Unit)
        load.join()
        verifyNoStart()
    }

    @Test fun pauseThenRestartIgnoresOlderLoad() = runTest {
        val repo = repository()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getScreenEvents(any()) } coAnswers { gate.await(); emptyList() }
        val old = launch { repo.startDetection(context, false, false) }
        runCurrent()
        repo.stopDetection()
        coEvery { data.getScreenEvents(any()) } returns emptyList()
        repo.startDetection(context, false, false)
        gate.complete(Unit)
        old.join()
        verify(exactly = 1) { engine.startDetection(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun cancelledCallerCannotStartAfterNonCancellableLoad() = runTest {
        val repo = repository()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getCounters(any()) } coAnswers { withContext(NonCancellable) { gate.await() }; emptyList() }
        val load = launch { repo.startDetection(context, false, false) }
        runCurrent()
        load.cancel()
        gate.complete(Unit)
        load.join()
        verifyNoStart()
    }

    @Test fun normalStartStillStartsAndAutoStops() = runTest {
        val repo = repository()
        repo.startDetection(context, false, false, 1.seconds)
        verify(exactly = 1) { engine.startDetection(any(), any(), any(), any(), any(), any(), any(), any()) }
        advanceTimeBy(1_001)
        runCurrent()
        verify(exactly = 1) { engine.stopDetection(com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeStopReason.AUTO_STOP) }
    }

    @Test fun pauseDuringActionTestLoadPreventsTestFromStarting() = runTest {
        val repo = repository()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getCounters(any()) } coAnswers { gate.await(); emptyList() }
        val action = com.buzbuz.smartautoclicker.core.domain.model.action.Pause(
            id = Identifier(databaseId = 2), eventId = Identifier(databaseId = 3),
            name = "Pause", priority = 0, pauseDuration = 100)
        val load = launch { repo.tryAction(context, scenario, action) }
        runCurrent()
        repo.stopDetection()
        gate.complete(Unit)
        load.join()
        verifyNoStart()
    }

    @Test fun olderAutoStopCannotStopRestartedRun() = runTest {
        val repo = repository()
        repo.startDetection(context, false, false, 1.seconds)
        repo.stopDetection()
        repo.startDetection(context, false, false, 10.seconds)
        advanceTimeBy(2_000)
        runCurrent()
        verify(exactly = 1) { engine.stopDetection(any()) }
    }

    @Test fun startRemainsPendingDuringNativeInitializationUntilPaused() = runTest {
        val repo = repository()
        every { engine.startDetection(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            state.value = DetectorState.TRANSITIONING
        }
        every { engine.stopDetection(any()) } answers { state.value = DetectorState.RECORDING }
        val load = launch { repo.startDetection(context, false, false) }
        runCurrent()
        assertFalse(load.isCompleted)
        repo.stopDetection()
        load.join()
        verify(exactly = 1) { engine.startDetection(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { engine.stopDetection(any()) }
    }
}
