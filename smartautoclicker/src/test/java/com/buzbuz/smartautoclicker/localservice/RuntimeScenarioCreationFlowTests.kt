package com.buzbuz.smartautoclicker.localservice

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.dumb.engine.DumbEngine
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import com.buzbuz.smartautoclicker.feature.dumb.config.ui.DumbMainMenu
import com.google.android.material.button.MaterialButton
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class RuntimeScenarioCreationFlowTests {
    @After fun tearDown() { Dispatchers.resetMain(); clearAllMocks() }

    @Test fun openingCreatorPausesExecutionAndCancelDoesNotInsertOrResume() = runTest {
        val fixture = fixture()
        fixture.running.value = true
        runCurrent()
        fixture.menu.clickCreate()
        runCurrent()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        verify(exactly = 1) { fixture.engine.stopDumbScenario() }
        dialog.findViewById<MaterialButton>(R.id.button_cancel)!!.performClick()
        ShadowLooper.idleMainLooper(); runCurrent()
        assertFalse(dialog.isShowing)
        assertTrue(fixture.service.isStarted)
        coVerify(exactly = 0) { fixture.dumb.addDumbScenario(any()) }
        coVerify(exactly = 0) { fixture.smart.addScenario(any()) }
        verify(exactly = 0) { fixture.engine.startDumbScenario() }
        fixture.service.release(); advanceUntilIdle()
    }

    @Test fun serviceStopDismissesOwnedCreationDialog() = runTest {
        val fixture = fixture()
        fixture.menu.clickCreate()
        runCurrent()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        fixture.service.stopScenario()
        ShadowLooper.idleMainLooper(); advanceUntilIdle()
        assertFalse(dialog.isShowing)
        assertFalse(fixture.service.isStarted)
        fixture.service.release(); advanceUntilIdle()
    }

    @Test fun repeatedToolbarTapsKeepOneDialogAndCanReopenAfterCancel() = runTest {
        val fixture = fixture()
        fixture.menu.clickCreate()
        fixture.menu.clickCreate()
        runCurrent()
        val first = ShadowDialog.getLatestDialog()
        fixture.menu.clickCreate()
        runCurrent()
        assertSame(first, ShadowDialog.getLatestDialog())
        first.dismiss(); ShadowLooper.idleMainLooper(); runCurrent()
        fixture.menu.clickCreate()
        runCurrent()
        assertNotSame(first, ShadowDialog.getLatestDialog())
        fixture.service.release(); ShadowLooper.idleMainLooper(); advanceUntilIdle()
    }

    @Test fun pauseFailureDoesNotCrashOrCreateAnything() = runTest {
        val fixture = fixture()
        fixture.running.value = true
        every { fixture.engine.stopDumbScenario() } throws IllegalStateException("Engine not ready")
        val dialogBefore = ShadowDialog.getLatestDialog()
        fixture.menu.clickCreate()
        runCurrent()
        assertSame(dialogBefore, ShadowDialog.getLatestDialog())
        assertTrue(fixture.service.isStarted)
        coVerify(exactly = 0) { fixture.dumb.addDumbScenario(any()) }
        fixture.service.release(); advanceUntilIdle()
    }

    private fun DumbMainMenu.clickCreate() {
        // Exercise the protected toolbar dispatcher without attaching the Hilt-backed editor.
        javaClass.getDeclaredMethod("onMenuItemClicked", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(this, R.id.btn_create_scenario)
    }

    private suspend fun TestScope.fixture(): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val running = MutableStateFlow(false)
        val engine = mockk<DumbEngine>(relaxed = true)
        every { engine.isRunning } returns running
        every { engine.stopDumbScenario() } answers { running.value = false }
        val smartRuntime = mockk<SmartProcessingRepository>(relaxed = true)
        every { smartRuntime.detectionState } returns flowOf(DetectionState.INACTIVE)
        val overlays = mockk<OverlayManager>(relaxed = true)
        every { overlays.isStackHidden } returns MutableStateFlow(false)
        every { overlays.backStackTopFlow } returns MutableStateFlow(null)
        lateinit var menu: DumbMainMenu
        every { overlays.navigateTo(any(), any(), any()) } answers { menu = secondArg() }
        val smart = mockk<IRepository>(relaxed = true)
        every { smart.scenarios } returns flowOf(emptyList())
        val dumb = mockk<IDumbRepository>(relaxed = true)
        every { dumb.dumbScenarios } returns flowOf(emptyList())
        val service = LocalService(context, overlays, mockk(relaxed = true), mockk(relaxed = true),
            smartRuntime, engine, smart, dumb, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            { _, _, _ -> }, { _, _ -> }, {})
        service.startDumbScenario(DumbScenario(id = Identifier(databaseId = 1), name = "Existing",
            repeatCount = 1, isRepeatInfinite = false, maxDurationMin = 1, isDurationInfinite = true, randomize = false))
        advanceTimeBy(500); runCurrent()
        return Fixture(service, menu, engine, running, smart, dumb)
    }

    private data class Fixture(val service: LocalService, val menu: DumbMainMenu, val engine: DumbEngine,
        val running: MutableStateFlow<Boolean>, val smart: IRepository, val dumb: IDumbRepository)
}
