/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.processing.domain.model

/** Normalized result returned by every scenario action. */
sealed interface ActionExecutionResult {
    data object Success : ActionExecutionResult
    data class Skipped(val reason: String) : ActionExecutionResult
    data class Failed(val reason: String) : ActionExecutionResult
    data class Cancelled(val reason: String) : ActionExecutionResult
    data class TimedOut(val timeoutMs: Long) : ActionExecutionResult
}

val ActionExecutionResult.isFailure: Boolean
    get() = this is ActionExecutionResult.Failed ||
        this is ActionExecutionResult.Cancelled ||
        this is ActionExecutionResult.TimedOut
