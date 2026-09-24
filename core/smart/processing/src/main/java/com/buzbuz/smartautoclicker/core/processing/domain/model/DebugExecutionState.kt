/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.processing.domain.model

/** Runtime state of the event-level debugger. */
sealed interface DebugExecutionState {

    /** The scenario is running normally. */
    data object Running : DebugExecutionState

    /** The debugger will suspend before the next fulfilled event executes its actions. */
    data object WaitingForEvent : DebugExecutionState

    /** A fulfilled event is suspended before its first action. */
    data class Paused(
        val eventId: Long,
        val eventName: String,
        val conditionDurationMs: Long,
    ) : DebugExecutionState
}
