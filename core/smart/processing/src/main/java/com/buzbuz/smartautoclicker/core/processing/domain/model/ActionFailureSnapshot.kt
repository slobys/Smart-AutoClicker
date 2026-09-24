/* Copyright (C) 2026 Kevin Buzeau */
package com.buzbuz.smartautoclicker.core.processing.domain.model

data class ActionFailureSnapshot(
    val eventId: Long,
    val eventName: String,
    val actionId: Long,
    val actionName: String,
    val result: ActionExecutionResult,
    val timestampMs: Long,
    val screenshotPath: String?,
)
