package com.buzbuz.smartautoclicker.core.processing.domain.model

/** Stable diagnostic codes; never infer a crash from a normal stop request. */
enum class RuntimeStopReason {
    USER_PAUSE, SCENARIO_REQUEST, SESSION_CLOSED, SERVICE_DISCONNECTED, PROJECTION_LOST,
    EXECUTION_ERROR, STARTUP_ERROR, CLEANUP_ERROR, AUTO_STOP,
}

data class RuntimeFailure(
    val stage: String,
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun from(stage: String, error: Exception) = RuntimeFailure(
            stage = stage,
            exceptionType = error.javaClass.name,
            message = error.message.orEmpty().take(1024),
            stackTrace = error.stackTraceToString().take(16_384),
        )
    }
}
