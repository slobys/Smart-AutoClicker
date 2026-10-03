package com.buzbuz.smartautoclicker.core.dumb.engine

import android.graphics.Point
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbAction
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.settings.domain.SettingsRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DumbEngineStartupTests {
    private val data = mockk<IDumbRepository>(relaxed = true)
    private val actions = mockk<DumbActionExecutor>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val action = DumbAction.DumbPause(Identifier(databaseId = 2), Identifier(databaseId = 1), "Pause", 0, 100)
    private val scenario = DumbScenario(Identifier(databaseId = 1), "Simple", listOf(action), 1, false, 1, true, false)
    private lateinit var engine: DumbEngine

    @After fun tearDown() { if (::engine.isInitialized) engine.release(); clearAllMocks() }

    private fun TestScope.setup() {
        engine = DumbEngine(data, actions, settings, StandardTestDispatcher(testScheduler))
        coEvery { data.getDumbScenario(any()) } returns scenario
        coEvery { actions.executeDumbAction(any(), any()) } coAnswers { delay(1_000) }
        engine.init(scenario)
    }

    @Test fun pauseDuringNonCancellableLoadCannotStartLater() = runTest {
        setup()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getDumbScenario(any()) } coAnswers { withContext(NonCancellable) { gate.await() }; scenario }
        engine.startDumbScenario()
        runCurrent()
        assertTrue(engine.isStarting.value)
        engine.stopDumbScenario()
        assertFalse(engine.isStarting.value)
        gate.complete(Unit)
        runCurrent()
        assertFalse(engine.isRunning.value)
        coVerify(exactly = 0) { actions.executeDumbAction(any(), any()) }
    }

    @Test fun releaseDuringLoadCannotStartLater() = runTest {
        setup()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getDumbScenario(any()) } coAnswers { withContext(NonCancellable) { gate.await() }; scenario }
        engine.startDumbScenario()
        runCurrent()
        engine.release()
        gate.complete(Unit)
        runCurrent()
        assertFalse(engine.isRunning.value)
        coVerify(exactly = 0) { actions.executeDumbAction(any(), any()) }
    }

    @Test fun rapidStartsLoadAndExecuteOnlyOnce() = runTest {
        setup()
        engine.startDumbScenario()
        engine.startDumbScenario()
        runCurrent()
        assertTrue(engine.isRunning.value)
        coVerify(exactly = 1) { data.getDumbScenario(any()) }
        coVerify(exactly = 1) { actions.executeDumbAction(any(), any()) }
    }

    @Test fun pauseThenRestartDoesNotAcceptOldLoad() = runTest {
        setup()
        val gate = CompletableDeferred<Unit>()
        coEvery { data.getDumbScenario(any()) } coAnswers { withContext(NonCancellable) { gate.await() }; scenario }
        engine.startDumbScenario()
        runCurrent()
        engine.stopDumbScenario()
        coEvery { data.getDumbScenario(any()) } returns scenario
        engine.startDumbScenario()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(engine.isRunning.value)
        coVerify(exactly = 1) { actions.executeDumbAction(any(), any()) }
    }

    @Test fun unconfirmedGestureStopsRepetition() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val android = mockk<com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor>()
            coEvery { android.dispatchGesture(any()) } returns
                com.buzbuz.smartautoclicker.core.common.actions.AndroidGestureResult.TIMED_OUT
            val click = DumbAction.DumbClick(Identifier(databaseId = 2), scenario.id, "Click", 0,
                1_000, false, 0, Point(10, 10), 100)
            coEvery { data.getDumbScenario(any()) } returns scenario.copy(dumbActions = listOf(click), repeatCount = 1_000)
            engine = DumbEngine(data, DumbActionExecutor(android), settings, dispatcher)
            engine.init(scenario)
            engine.startDumbScenario()
            runCurrent()
            assertFalse(engine.isRunning.value)
            coVerify(exactly = 1) { android.dispatchGesture(any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun cancelledOldRunCannotStopNewRun() = runTest {
        setup()
        val oldAction = CompletableDeferred<Unit>()
        coEvery { actions.executeDumbAction(any(), any()) } coAnswers { withContext(NonCancellable) { oldAction.await() } }
        engine.startDumbScenario()
        runCurrent()
        engine.stopDumbScenario()
        coEvery { actions.executeDumbAction(any(), any()) } coAnswers { delay(1_000) }
        engine.startDumbScenario()
        runCurrent()
        oldAction.complete(Unit)
        runCurrent()
        assertTrue(engine.isRunning.value)
    }
}
