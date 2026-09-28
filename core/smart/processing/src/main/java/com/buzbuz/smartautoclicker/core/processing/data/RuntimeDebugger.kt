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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Suspends execution at event boundaries without stopping the detection session. */
internal class RuntimeDebugger {

    private val lock = Any()
    private val _state = MutableStateFlow<DebugExecutionState>(DebugExecutionState.Running)
    val state: StateFlow<DebugExecutionState> = _state.asStateFlow()

    private var pauseAtNextEvent: Boolean = false
    private var pauseWithinAction: Boolean = false
    private var pausedGate: CompletableDeferred<Unit>? = null

    fun requestPauseAtNextEvent(): Unit = synchronized(lock) {
        if (pausedGate != null) return@synchronized

        pauseAtNextEvent = true
        pauseWithinAction = true
        _state.value = DebugExecutionState.WaitingForEvent
    }

    fun cancelPauseRequest(): Unit = synchronized(lock) {
        if (pausedGate != null) return@synchronized

        pauseAtNextEvent = false
        pauseWithinAction = false
        _state.value = DebugExecutionState.Running
    }

    suspend fun awaitBeforeActions(
        eventId: Long,
        eventName: String,
        conditionDurationMs: Long,
        isBreakpoint: Boolean = false,
    ) = awaitCheckpoint(eventId, eventName, conditionDurationMs, isBreakpoint, withinActionOnly = false)

    /** Long route actions honour explicit pause requests without consuming an event-step token. */
    suspend fun awaitWithinAction(eventId: Long, eventName: String) =
        awaitCheckpoint(eventId, eventName, 0L, false, withinActionOnly = true)

    private suspend fun awaitCheckpoint(
        eventId: Long, eventName: String, conditionDurationMs: Long,
        isBreakpoint: Boolean, withinActionOnly: Boolean,
    ) {
        val gate = synchronized(lock) {
            if (withinActionOnly && !pauseWithinAction) return
            if (!pauseAtNextEvent && !isBreakpoint) return

            pauseAtNextEvent = false
            pauseWithinAction = false
            CompletableDeferred<Unit>().also { newGate ->
                pausedGate = newGate
                _state.value = DebugExecutionState.Paused(eventId, eventName, conditionDurationMs)
            }
        }

        try {
            gate.await()
        } finally {
            synchronized(lock) {
                if (pausedGate === gate) pausedGate = null
                _state.value =
                    if (pauseAtNextEvent) DebugExecutionState.WaitingForEvent
                    else DebugExecutionState.Running
            }
        }
    }

    fun resume(): Unit = synchronized(lock) {
        pauseAtNextEvent = false
        pauseWithinAction = false
        _state.value = DebugExecutionState.Running
        pausedGate?.complete(Unit)
        Unit
    }

    fun step(): Unit = synchronized(lock) {
        val gate = pausedGate ?: return@synchronized

        pauseAtNextEvent = true
        pauseWithinAction = false
        _state.value = DebugExecutionState.WaitingForEvent
        gate.complete(Unit)
    }

    /** Releases a suspended processor before cancellation and clears all debugger state. */
    fun reset(): Unit = synchronized(lock) {
        pauseAtNextEvent = false
        pauseWithinAction = false
        _state.value = DebugExecutionState.Running
        pausedGate?.complete(Unit)
        pausedGate = null
        Unit
    }
}
