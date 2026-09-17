/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.localservice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class RuntimeScenarioKind { SMART, DUMB }

internal enum class RuntimePlaybackState { PAUSED, RUNNING }

internal data class RuntimeScenarioKey(
    val databaseId: Long,
    val kind: RuntimeScenarioKind,
)

internal sealed interface RuntimeSessionState {
    data object Idle : RuntimeSessionState

    data class Starting(
        val target: RuntimeScenarioKey,
        val hasMediaProjection: Boolean,
    ) : RuntimeSessionState

    data class Active(
        val target: RuntimeScenarioKey,
        val hasMediaProjection: Boolean,
        val playback: RuntimePlaybackState,
    ) : RuntimeSessionState

    data class Switching(
        val from: Active,
        val target: RuntimeScenarioKey,
    ) : RuntimeSessionState

    data class Stopping(
        val from: Active?,
        val isPermissionHandoff: Boolean,
    ) : RuntimeSessionState
}

/**
 * Owns the runtime lifecycle and rejects transitions that would make the engine and overlay disagree.
 */
internal class RuntimeSessionStateMachine {

    private val mutableState = MutableStateFlow<RuntimeSessionState>(RuntimeSessionState.Idle)
    val state: StateFlow<RuntimeSessionState> = mutableState.asStateFlow()

    val isStarted: Boolean
        get() = mutableState.value is RuntimeSessionState.Starting ||
                mutableState.value is RuntimeSessionState.Active ||
                mutableState.value is RuntimeSessionState.Switching

    val active: RuntimeSessionState.Active?
        get() = when (val current = mutableState.value) {
            is RuntimeSessionState.Active -> current
            is RuntimeSessionState.Switching -> current.from
            else -> null
        }

    fun beginStart(target: RuntimeScenarioKey, hasMediaProjection: Boolean): Boolean {
        if (mutableState.value !is RuntimeSessionState.Idle) return false
        mutableState.value = RuntimeSessionState.Starting(target, hasMediaProjection)
        return true
    }

    fun completeStart(playback: RuntimePlaybackState = RuntimePlaybackState.PAUSED): Boolean {
        val starting = mutableState.value as? RuntimeSessionState.Starting ?: return false
        mutableState.value = RuntimeSessionState.Active(
            target = starting.target,
            hasMediaProjection = starting.hasMediaProjection,
            playback = playback,
        )
        return true
    }

    fun beginSwitch(target: RuntimeScenarioKey): RuntimeSessionState.Active? {
        val current = mutableState.value as? RuntimeSessionState.Active ?: return null
        if (current.target == target) return null
        mutableState.value = RuntimeSessionState.Switching(current, target)
        return current
    }

    fun completeSwitch(playback: RuntimePlaybackState): Boolean {
        val switching = mutableState.value as? RuntimeSessionState.Switching ?: return false
        mutableState.value = RuntimeSessionState.Active(
            target = switching.target,
            hasMediaProjection = switching.from.hasMediaProjection,
            playback = playback,
        )
        return true
    }

    fun rollbackSwitch(): Boolean {
        val switching = mutableState.value as? RuntimeSessionState.Switching ?: return false
        mutableState.value = switching.from
        return true
    }

    fun updatePlayback(playback: RuntimePlaybackState): Boolean {
        val current = mutableState.value as? RuntimeSessionState.Active ?: return false
        mutableState.value = current.copy(playback = playback)
        return true
    }

    fun beginStop(isPermissionHandoff: Boolean): Boolean {
        val current = mutableState.value
        val activeState = when (current) {
            is RuntimeSessionState.Active -> current
            is RuntimeSessionState.Switching -> current.from
            else -> null
        }
        if (current is RuntimeSessionState.Idle || current is RuntimeSessionState.Stopping) return false
        mutableState.value = RuntimeSessionState.Stopping(activeState, isPermissionHandoff)
        return true
    }

    fun completeStop() {
        mutableState.value = RuntimeSessionState.Idle
    }
}

internal fun RuntimeScenarioTarget.toKey(): RuntimeScenarioKey = RuntimeScenarioKey(
    databaseId = databaseId,
    kind = if (isSmart) RuntimeScenarioKind.SMART else RuntimeScenarioKind.DUMB,
)
