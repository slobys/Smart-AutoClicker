package com.buzbuz.smartautoclicker.feature.dumb.config.ui

import androidx.lifecycle.ViewModelStore
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.TutorialRepository
import com.buzbuz.smartautoclicker.core.dumb.engine.DumbEngine
import com.buzbuz.smartautoclicker.feature.dumb.config.domain.DumbEditionRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DumbMainMenuStartupTests {
    private val engine = mockk<DumbEngine>(relaxed = true)
    private val starting = MutableStateFlow(false)
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); Dispatchers.resetMain(); clearAllMocks() }

    private fun TestScope.model(): DumbMainMenuModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        every { engine.isRunning } returns MutableStateFlow(false)
        every { engine.isStarting } returns starting
        every { engine.dumbScenario } returns flowOf(null)
        val edition = mockk<DumbEditionRepository>(relaxed = true)
        every { edition.isEditionSynchronized } returns flowOf(true)
        val tutorial = mockk<TutorialRepository>(relaxed = true)
        every { tutorial.shouldShowTip(any()) } returns flowOf(true)
        return DumbMainMenuModel(edition, engine, tutorial).also { store.put("menu", it) }
    }

    @Test fun loadingShowsPauseAndSecondTapStops() = runTest {
        val menu = model()
        starting.value = true
        runCurrent()
        assertTrue(menu.isPlaying.value)
        assertFalse(menu.shouldShowStopVolumeDownTutorialDialog())
        menu.toggleScenarioPlay()
        verify(exactly = 1) { engine.stopDumbScenario() }
        verify(exactly = 0) { engine.startDumbScenario() }
    }

    @Test fun volumePauseDuringLoadIsHandledImmediately() = runTest {
        val menu = model()
        starting.value = true
        assertTrue(menu.stopScenarioPlay())
        verify(exactly = 1) { engine.stopDumbScenario() }
    }

    @Test fun idlePauseStillInvalidatesPendingWorkWithoutSwallowingVolumeKey() = runTest {
        val menu = model()
        assertFalse(menu.stopScenarioPlay())
        verify(exactly = 1) { engine.stopDumbScenario() }
    }
}
