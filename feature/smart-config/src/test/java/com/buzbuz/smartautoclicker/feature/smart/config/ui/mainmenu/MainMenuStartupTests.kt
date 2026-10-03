package com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.TutorialRepository
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import com.buzbuz.smartautoclicker.feature.revenue.IRevenueRepository
import com.buzbuz.smartautoclicker.feature.revenue.UserBillingState
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.domain.usecase.alphabet.AreRequiredAlphabetModelsInstalledUseCase
import io.mockk.*
import kotlinx.coroutines.*
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
class MainMenuStartupTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    // scenarioId's getter and getScenarioId() share a JVM name with different return types.
    // A relaxed MockK proxy can confuse them depending on class-loading order. Keep the flow real
    // and delegate the operations to the mock so these tests exercise startup, not proxy casting.
    private val processing = object : SmartProcessingRepository by mockk(relaxed = true) {
        override val scenarioId = MutableStateFlow<Identifier?>(Identifier(databaseId = 1))
    }
    private val revenue = mockk<IRevenueRepository>(relaxed = true)
    private val billingState = MutableStateFlow(UserBillingState.EXEMPTED)
    private val billingInProgress = MutableStateFlow(false)
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); Dispatchers.resetMain(); clearAllMocks() }

    private fun TestScope.model(): MainMenuModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        every { processing.detectionState } returns flowOf(DetectionState.RECORDING)
        every { processing.canStartDetection } returns flowOf(true)
        every { processing.isRunning() } returns false
        every { revenue.userBillingState } returns billingState
        every { revenue.isBillingFlowInProgress } returns billingInProgress
        every { revenue.consumeTrial() } returns null
        val edition = mockk<EditionRepository>(relaxed = true)
        every { edition.isEditionSynchronized } returns flowOf(true)
        val tutorial = mockk<TutorialRepository>(relaxed = true)
        every { tutorial.shouldShowTip(any()) } returns flowOf(false)
        val models = mockk<AreRequiredAlphabetModelsInstalledUseCase>()
        every { models(any()) } returns flowOf(true)
        return MainMenuModel(processing, edition, tutorial, revenue,
            mockk(relaxed = true), mockk(relaxed = true), models).also { store.put("menu", it) }
    }

    @Test fun secondTapDuringLoadPausesInsteadOfStartingAgain() = runTest {
        val menu = model()
        coEvery { processing.startDetection(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        menu.toggleDetection(context)
        runCurrent()
        assertEquals(UiState.Detecting, menu.detectionState.value)
        menu.toggleDetection(context)
        runCurrent()
        verify(exactly = 1) { processing.stopDetection() }
        coVerify(exactly = 1) { processing.startDetection(any(), any(), any(), any()) }
        assertEquals(UiState.Idle, menu.detectionState.value)
    }

    @Test fun volumePauseDuringLoadIsHandled() = runTest {
        val menu = model()
        coEvery { processing.startDetection(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        menu.toggleDetection(context)
        runCurrent()
        assertTrue(menu.stopDetection())
        verify(exactly = 1) { processing.stopDetection() }
    }

    @Test fun pauseBeforeQueuedStartPreventsLoad() = runTest {
        val menu = model()
        menu.toggleDetection(context)
        assertTrue(menu.stopDetection())
        runCurrent()
        coVerify(exactly = 0) { processing.startDetection(any(), any(), any(), any()) }
    }

    @Test fun pausePreventsLatePaywallResultFromStarting() = runTest {
        val menu = model()
        billingState.value = UserBillingState.AD_REQUESTED
        billingInProgress.value = true
        menu.toggleDetection(context)
        runCurrent()
        assertTrue(menu.stopDetection())
        billingInProgress.value = false
        billingState.value = UserBillingState.AD_WATCHED
        runCurrent()
        coVerify(exactly = 0) { processing.startDetection(any(), any(), any(), any()) }
    }

    @Test fun lateCancelledLoadDoesNotClearNewLoadingState() = runTest {
        val menu = model()
        val gate = CompletableDeferred<Unit>()
        coEvery { processing.startDetection(any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { gate.await() }
        }
        menu.toggleDetection(context)
        runCurrent()
        menu.stopDetection()
        coEvery { processing.startDetection(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        menu.toggleDetection(context)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(UiState.Detecting, menu.detectionState.value)
        assertTrue(menu.stopDetection())
    }
}
