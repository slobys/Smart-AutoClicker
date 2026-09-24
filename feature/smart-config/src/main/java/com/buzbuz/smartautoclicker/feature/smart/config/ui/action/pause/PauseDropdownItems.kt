/* Copyright (C) 2026 Kevin Buzeau */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.pause

import androidx.annotation.StringRes
import com.buzbuz.smartautoclicker.core.domain.model.action.Pause
import com.buzbuz.smartautoclicker.core.ui.bindings.dropdown.DropdownItem
import com.buzbuz.smartautoclicker.feature.smart.config.R

sealed class PauseWaitModeItem(@StringRes title: Int, val mode: Pause.WaitMode) : DropdownItem(title) {
    data object Fixed : PauseWaitModeItem(R.string.pause_wait_mode_fixed, Pause.WaitMode.FIXED_DELAY)
    data object Appears : PauseWaitModeItem(R.string.pause_wait_mode_target_appears, Pause.WaitMode.TARGET_APPEARS)
    data object Disappears : PauseWaitModeItem(R.string.pause_wait_mode_target_disappears, Pause.WaitMode.TARGET_DISAPPEARS)
    data object Stable : PauseWaitModeItem(R.string.pause_wait_mode_screen_stable, Pause.WaitMode.SCREEN_STABLE)
    data object Changed : PauseWaitModeItem(R.string.pause_wait_mode_screen_changed, Pause.WaitMode.SCREEN_CHANGED)
}

val pauseWaitModeItems = listOf(
    PauseWaitModeItem.Fixed, PauseWaitModeItem.Appears, PauseWaitModeItem.Disappears,
    PauseWaitModeItem.Stable, PauseWaitModeItem.Changed,
)

fun Pause.WaitMode.toDropdownItem(): PauseWaitModeItem = pauseWaitModeItems.first { it.mode == this }

sealed class PauseTimeoutItem(@StringRes title: Int, val behavior: Pause.TimeoutBehavior) : DropdownItem(title) {
    data object Retry : PauseTimeoutItem(R.string.pause_timeout_retry, Pause.TimeoutBehavior.RETRY)
    data object Skip : PauseTimeoutItem(R.string.pause_timeout_skip, Pause.TimeoutBehavior.SKIP)
    data object Stop : PauseTimeoutItem(R.string.pause_timeout_stop, Pause.TimeoutBehavior.STOP)
    data object Fallback : PauseTimeoutItem(R.string.pause_timeout_fallback, Pause.TimeoutBehavior.EXECUTE_FALLBACK)
}

val pauseTimeoutItems = listOf(
    PauseTimeoutItem.Retry, PauseTimeoutItem.Skip, PauseTimeoutItem.Stop, PauseTimeoutItem.Fallback,
)

fun Pause.TimeoutBehavior.toDropdownItem(): PauseTimeoutItem = pauseTimeoutItems.first { it.behavior == this }
