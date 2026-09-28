/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.domain.model.action.*
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.processor.ActionExecutor
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RouteActionTests {
    private val action = ExecuteRoute(Identifier(databaseId = 1), Identifier(databaseId = 2), "Walk", 0,
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private fun event(a: ExecuteRoute = action) = ScreenEvent(Identifier(databaseId = 2), Identifier(databaseId = 3),
        "Route event", 1, listOf(a, Pause(Identifier(databaseId = 4), Identifier(databaseId = 2), "After route", 1, 20)),
        emptyList(), true, 0, cooldownMs = 0, keepDetecting = false)

    @Test fun successContinuesNextActionInOrder() = runTest {
        val event = event(); val visited = mutableListOf<String>()
        val executor = ActionExecutor(mock<AndroidActionExecutor>(), ProcessingState(listOf(event), emptyList(), emptyList(), null), false,
            routeExecutor = { _, _ -> visited.add("route"); ActionExecutionResult.Success },
            onActionCompleted = { _, a, _, _ -> visited.add(a.name!!) })
        assertEquals(ActionExecutionResult.Success, executor.executeActions(event))
        assertEquals(listOf("route", "Walk", "After route"), visited)
    }
    @Test fun failureStopsScenarioAndDoesNotExecuteFollowingAction() = runTest {
        val event = event(); var stopped = false; var completed = 0
        val failure = ActionExecutionResult.Failed("Missing route")
        val executor = ActionExecutor(mock(), ProcessingState(listOf(event), emptyList(), emptyList(), null), false,
            routeExecutor = { _, _ -> failure }, onStopRequested = { stopped = true },
            onActionCompleted = { _, _, _, _ -> completed++ })
        assertEquals(failure, executor.executeActions(event)); assertTrue(stopped); assertEquals(1, completed)
    }
    @Test fun invalidReferenceNeverCallsRunner() = runTest {
        val event = event(action.copy(routeId = "../route"))
        val executor = ActionExecutor(mock(), ProcessingState(listOf(event), emptyList(), emptyList(), null), false,
            routeExecutor = { _, _ -> error("Must not load unvalidated path") })
        assertTrue(executor.executeActions(event) is ActionExecutionResult.Failed)
    }
    @Test fun cancellationIsNotReportedAsSuccess() = runTest {
        val event = event(); var completed = 0
        val executor = ActionExecutor(mock(), ProcessingState(listOf(event), emptyList(), emptyList(), null), false,
            routeExecutor = { _, _ -> throw CancellationException("stop") },
            onActionCompleted = { _, _, _, _ -> completed++ })
        try { executor.executeActions(event); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(0, completed)
    }
}
