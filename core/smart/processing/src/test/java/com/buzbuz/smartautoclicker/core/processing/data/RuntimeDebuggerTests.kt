/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.processing.data

import com.buzbuz.smartautoclicker.core.processing.domain.model.DebugExecutionState

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeDebuggerTests {

    @Test
    fun breakpoint_pausesMatchingEvent_withoutManualPauseRequest() = runTest {
        val debugger = RuntimeDebugger()
        var actionsStarted = false

        val processingJob = launch {
            debugger.awaitBeforeActions(99L, "断点事件", 23L, isBreakpoint = true)
            actionsStarted = true
        }
        runCurrent()

        assertFalse(actionsStarted)
        assertEquals(
            DebugExecutionState.Paused(99L, "断点事件", 23L),
            debugger.state.value,
        )

        debugger.resume()
        advanceUntilIdle()

        assertTrue(actionsStarted)
        assertTrue(processingJob.isCompleted)
        assertEquals(DebugExecutionState.Running, debugger.state.value)
    }

    @Test
    fun pauseRequest_suspendsBeforeActions_untilResume() = runTest {
        val debugger = RuntimeDebugger()
        var actionsStarted = false

        debugger.requestPauseAtNextEvent()
        val processingJob = launch {
            debugger.awaitBeforeActions(42L, "测试事件", 17L)
            actionsStarted = true
        }
        runCurrent()

        assertFalse(actionsStarted)
        assertEquals(
            DebugExecutionState.Paused(42L, "测试事件", 17L),
            debugger.state.value,
        )

        debugger.resume()
        advanceUntilIdle()

        assertTrue(processingJob.isCompleted)
        assertTrue(actionsStarted)
        assertEquals(DebugExecutionState.Running, debugger.state.value)
    }

    @Test
    fun step_resumesCurrentEvent_andPausesAtNextFulfilledEvent() = runTest {
        val debugger = RuntimeDebugger()
        var firstActionsStarted = false
        var secondActionsStarted = false

        debugger.requestPauseAtNextEvent()
        val firstJob = launch {
            debugger.awaitBeforeActions(1L, "事件一", 5L)
            firstActionsStarted = true
        }
        runCurrent()

        debugger.step()
        advanceUntilIdle()
        assertTrue(firstJob.isCompleted)
        assertTrue(firstActionsStarted)
        assertEquals(DebugExecutionState.WaitingForEvent, debugger.state.value)

        val secondJob = launch {
            debugger.awaitBeforeActions(2L, "事件二", 8L)
            secondActionsStarted = true
        }
        runCurrent()

        assertFalse(secondActionsStarted)
        assertEquals(DebugExecutionState.Paused(2L, "事件二", 8L), debugger.state.value)

        debugger.resume()
        advanceUntilIdle()
        assertTrue(secondJob.isCompleted)
        assertTrue(secondActionsStarted)
    }

    @Test
    fun reset_releasesPausedEvent_andClearsPendingStep() = runTest {
        val debugger = RuntimeDebugger()

        debugger.requestPauseAtNextEvent()
        val processingJob = launch { debugger.awaitBeforeActions(7L, "事件", 1L) }
        runCurrent()

        debugger.reset()
        advanceUntilIdle()

        assertTrue(processingJob.isCompleted)
        assertEquals(DebugExecutionState.Running, debugger.state.value)
    }
}
