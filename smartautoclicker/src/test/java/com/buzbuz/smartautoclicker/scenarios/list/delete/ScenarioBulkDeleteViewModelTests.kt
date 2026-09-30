package com.buzbuz.smartautoclicker.scenarios.list.delete

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.scenarios.list.model.GroupableScenario.Reference
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class ScenarioBulkDeleteViewModelTests {
    private val stores = mutableListOf<ViewModelStore>()
    @After fun tearDown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    @Test fun allTypesIncludingEmptyScriptsCanBeSelectedWithoutIdCollisions() = runTest {
        val f = fixture(); runCurrent()
        assertEquals(3, f.vm.state.value.scenarios.size)
        assertTrue(f.vm.state.value.selected.isEmpty())
        f.vm.toggleAll()
        assertEquals(setOf(Reference(1, true), Reference(2, true), Reference(1, false)), f.vm.state.value.selected)
        f.vm.toggleAll()
        assertTrue(f.vm.state.value.selected.isEmpty())
        f.vm.select(setOf(Reference(999, true), Reference(1, true)))
        assertEquals(setOf(Reference(1, true)), f.vm.state.value.selected)
    }

    @Test fun deletionRequiresConfirmationAndCancelDoesNotDelete() = runTest {
        val f = fixture(); runCurrent()
        f.vm.toggleAll()
        f.vm.confirmDeletion(); runCurrent()
        assertNull(f.vm.state.value.result)
        f.vm.requestDeletion()
        assertEquals(3, f.vm.state.value.confirmation!!.size)
        f.vm.cancelConfirmation()
        f.vm.confirmDeletion(); runCurrent()
        coVerify(exactly = 0) { f.smart.deleteScenario(any()) }
        coVerify(exactly = 0) { f.dumb.deleteDumbScenario(any()) }
        assertEquals(3, f.vm.state.value.selected.size)
    }

    @Test fun onlyConfirmedSnapshotIsDeletedEvenIfNewScriptAppears() = runTest {
        val f = fixture(); runCurrent()
        f.vm.select(setOf(Reference(1, true), Reference(1, false)))
        f.vm.requestDeletion()
        f.smartRows.value += scenario(3)
        runCurrent()
        f.vm.toggleAll() // Cannot alter the pending confirmation.
        f.vm.confirmDeletion(); advanceUntilIdle()
        coVerify(exactly = 1) { f.smart.deleteScenario(Identifier(databaseId = 1)) }
        coVerify(exactly = 0) { f.smart.deleteScenario(Identifier(databaseId = 2)) }
        coVerify(exactly = 0) { f.smart.deleteScenario(Identifier(databaseId = 3)) }
        coVerify(exactly = 1) { f.dumb.deleteDumbScenario(any()) }
        assertEquals(BulkDeleteResult(2, 0), f.vm.state.value.result)
    }

    @Test fun repeatedConfirmDoesNotSubmitAnotherDeletion() = runTest {
        val f = fixture(); runCurrent()
        val finish = CompletableDeferred<Unit>()
        coEvery { f.smart.deleteScenario(any()) } coAnswers { finish.await() }
        f.vm.select(setOf(Reference(1, true))); f.vm.requestDeletion()
        f.vm.confirmDeletion(); runCurrent()
        assertTrue(f.vm.state.value.deleting)
        f.vm.confirmDeletion(); f.vm.requestDeletion(); f.vm.toggleAll(); runCurrent()
        coVerify(exactly = 1) { f.smart.deleteScenario(any()) }
        finish.complete(Unit); advanceUntilIdle()
        assertEquals(BulkDeleteResult(1, 0), f.vm.state.value.result)
    }

    @Test fun partialFailureRetainsOnlyFailedItemsAndRetryDoesNotRepeatSuccess() = runTest {
        val f = fixture(); runCurrent()
        coEvery { f.dumb.deleteDumbScenario(any()) } throws IllegalStateException("Disk error")
        f.vm.toggleAll(); f.vm.requestDeletion(); f.vm.confirmDeletion(); advanceUntilIdle()
        assertEquals(BulkDeleteResult(2, 1), f.vm.state.value.result)
        assertEquals(setOf(Reference(1, false)), f.vm.state.value.selected)
        assertEquals(1, f.vm.state.value.scenarios.size)
        coEvery { f.dumb.deleteDumbScenario(any()) } just Runs
        f.vm.requestDeletion(); f.vm.confirmDeletion(); advanceUntilIdle()
        assertEquals(BulkDeleteResult(1, 0), f.vm.state.value.result)
        coVerify(exactly = 2) { f.smart.deleteScenario(any()) }
    }

    @Test fun externallyDeletedScriptCannotLeaveAHiddenSelection() = runTest {
        val f = fixture(); runCurrent()
        f.vm.toggleAll()
        f.smartRows.value = emptyList(); runCurrent()
        assertEquals(setOf(Reference(1, false)), f.vm.state.value.selected)
    }

    @Test fun clearingViewModelCancelsBatchBeforeNextItem() = runTest {
        val f = fixture(); runCurrent()
        val finish = CompletableDeferred<Unit>()
        coEvery { f.smart.deleteScenario(any()) } coAnswers { finish.await() }
        f.vm.toggleAll(); f.vm.requestDeletion(); f.vm.confirmDeletion(); runCurrent()
        stores.forEach { it.clear() }; runCurrent()
        coVerify(exactly = 1) { f.smart.deleteScenario(any()) }
        coVerify(exactly = 0) { f.dumb.deleteDumbScenario(any()) }
        assertNull(f.vm.state.value.result)
    }

    private fun TestScope.fixture(): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val smart = mockk<IRepository>()
        val dumb = mockk<IDumbRepository>()
        val smartRows = MutableStateFlow(listOf(scenario(1), scenario(2)))
        val simple = DumbScenario(id = Identifier(databaseId = 1), name = "Same name", repeatCount = 1,
            isRepeatInfinite = false, maxDurationMin = 1, isDurationInfinite = true, randomize = false)
        every { smart.scenarios } returns smartRows
        every { dumb.dumbScenarios } returns MutableStateFlow(listOf(simple))
        coEvery { smart.deleteScenario(any()) } just Runs
        coEvery { dumb.getDumbScenario(1) } returns simple
        coEvery { dumb.deleteDumbScenario(any()) } just Runs
        val vm = ScenarioBulkDeleteViewModel(smart, dumb, dispatcher)
        stores += ViewModelStore().apply { put("delete", vm) }
        return Fixture(vm, smart, dumb, smartRows)
    }

    private fun scenario(id: Long) = Scenario(id = Identifier(databaseId = id), name = "Same name",
        detectionQuality = 800, randomize = false)
    private data class Fixture(val vm: ScenarioBulkDeleteViewModel, val smart: IRepository, val dumb: IDumbRepository,
                               val smartRows: MutableStateFlow<List<Scenario>>)
}
