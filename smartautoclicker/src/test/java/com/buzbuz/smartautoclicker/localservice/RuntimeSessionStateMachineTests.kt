/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.localservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionStateMachineTests {

    private val smart = RuntimeScenarioKey(1L, RuntimeScenarioKind.SMART)
    private val dumb = RuntimeScenarioKey(2L, RuntimeScenarioKind.DUMB)

    @Test
    fun start_complete_createsPausedActiveSession() {
        val machine = RuntimeSessionStateMachine()

        assertTrue(machine.beginStart(smart, hasMediaProjection = true))
        assertTrue(machine.isStarted)
        assertTrue(machine.completeStart())

        assertEquals(
            RuntimeSessionState.Active(smart, hasMediaProjection = true, RuntimePlaybackState.PAUSED),
            machine.state.value,
        )
    }

    @Test
    fun secondStart_isRejected() {
        val machine = activeMachine()

        assertFalse(machine.beginStart(dumb, hasMediaProjection = false))
        assertEquals(smart, machine.active?.target)
    }

    @Test
    fun switch_complete_preservesProjectionAndUpdatesTarget() {
        val machine = activeMachine()

        assertEquals(smart, machine.beginSwitch(dumb)?.target)
        assertNull(machine.beginSwitch(RuntimeScenarioKey(3L, RuntimeScenarioKind.DUMB)))
        assertTrue(machine.completeSwitch(RuntimePlaybackState.RUNNING))

        assertEquals(
            RuntimeSessionState.Active(dumb, hasMediaProjection = true, RuntimePlaybackState.RUNNING),
            machine.state.value,
        )
    }

    @Test
    fun switchFailure_rollsBackExactlyToPreviousSession() {
        val machine = activeMachine(playback = RuntimePlaybackState.RUNNING)
        val previous = machine.state.value

        assertEquals(smart, machine.beginSwitch(dumb)?.target)
        assertTrue(machine.rollbackSwitch())

        assertEquals(previous, machine.state.value)
    }

    @Test
    fun switchingToCurrentTarget_isRejected() {
        val machine = activeMachine()

        assertNull(machine.beginSwitch(smart))
        assertTrue(machine.state.value is RuntimeSessionState.Active)
    }

    @Test
    fun stop_preventsNewTransitionsUntilCompleted() {
        val machine = activeMachine()

        assertTrue(machine.beginStop(isPermissionHandoff = true))
        assertFalse(machine.isStarted)
        assertNull(machine.active)
        assertFalse(machine.beginStart(dumb, hasMediaProjection = false))
        assertNull(machine.beginSwitch(dumb))

        machine.completeStop()
        assertEquals(RuntimeSessionState.Idle, machine.state.value)
        assertTrue(machine.beginStart(dumb, hasMediaProjection = false))
    }

    private fun activeMachine(
        playback: RuntimePlaybackState = RuntimePlaybackState.PAUSED,
    ): RuntimeSessionStateMachine = RuntimeSessionStateMachine().also { machine ->
        machine.beginStart(smart, hasMediaProjection = true)
        machine.completeStart(playback)
    }
}
