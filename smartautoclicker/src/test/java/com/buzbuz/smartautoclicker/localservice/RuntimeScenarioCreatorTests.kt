package com.buzbuz.smartautoclicker.localservice

import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RuntimeScenarioCreatorTests {
    private val smart = mockk<IRepository>()
    private val dumb = mockk<IDumbRepository>()
    private val creator = RuntimeScenarioCreator(smart, dumb)

    @Test fun smartCreationReturnsInsertedIdWithEmptySafeDefaults() = runTest {
        val inserted = slot<Scenario>()
        coEvery { smart.addScenario(capture(inserted)) } returns 77
        val target = creator.create("  New route  ", RuntimeScenarioKind.SMART, 1920)
        assertEquals(77L, target.databaseId)
        assertEquals("New route", target.name)
        assertEquals(0, target.itemCount)
        assertEquals(936, inserted.captured.detectionQuality)
        assertFalse(inserted.captured.randomize)
        coVerify(exactly = 1) { smart.addScenario(any()) }
        confirmVerified(dumb)
    }

    @Test fun simpleCreationReturnsExactIdEvenWhenNamesAreDuplicated() = runTest {
        val inserted = slot<DumbScenario>()
        coEvery { dumb.addDumbScenario(capture(inserted)) } returnsMany listOf(81L, 82L)
        assertEquals(81L, creator.create("Same name", RuntimeScenarioKind.DUMB, 1080).databaseId)
        assertEquals(82L, creator.create("Same name", RuntimeScenarioKind.DUMB, 1080).databaseId)
        assertTrue(inserted.captured.dumbActions.isEmpty())
        assertFalse(inserted.captured.isRepeatInfinite)
        assertEquals(1, inserted.captured.repeatCount)
        coVerify(exactly = 0) { dumb.getDumbScenario(any()) }
    }

    @Test fun blankNameDoesNotTouchEitherDatabase() = runTest {
        try { creator.create("   ", RuntimeScenarioKind.SMART, 1080); fail("Blank name accepted") }
        catch (_: IllegalArgumentException) { }
        confirmVerified(smart, dumb)
    }

    @Test fun groupIsInsertedWithSmartAndSimpleScriptInTheSameWrite() = runTest {
        val smartInsert = slot<Scenario>()
        val simpleInsert = slot<DumbScenario>()
        coEvery { smart.addScenario(capture(smartInsert)) } returns 7
        coEvery { dumb.addDumbScenario(capture(simpleInsert)) } returns 7
        creator.create("Smart", RuntimeScenarioKind.SMART, 1080, "  新分组  ")
        creator.create("Simple", RuntimeScenarioKind.DUMB, 1080, " 任务 ")
        assertEquals("新分组", smartInsert.captured.groupName)
        assertEquals("任务", simpleInsert.captured.groupName)
        coVerify(exactly = 0) { smart.updateScenarioOrganization(any(), any(), any()) }
        coVerify(exactly = 0) { dumb.updateScenarioOrganization(any(), any(), any()) }
    }

    @Test fun failedInsertIsNotReportedAsSaved() = runTest {
        coEvery { smart.addScenario(any()) } returns -1
        try { creator.create("New", RuntimeScenarioKind.SMART, 1080); fail("Invalid ID accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun repositoryExceptionPropagatesWithoutFallbackInsertion() = runTest {
        coEvery { dumb.addDumbScenario(any()) } throws IllegalStateException("Disk full")
        try { creator.create("New", RuntimeScenarioKind.DUMB, 1080); fail("Failure hidden") }
        catch (_: IllegalStateException) { }
        coVerify(exactly = 1) { dumb.addDumbScenario(any()) }
        confirmVerified(smart)
    }
}
