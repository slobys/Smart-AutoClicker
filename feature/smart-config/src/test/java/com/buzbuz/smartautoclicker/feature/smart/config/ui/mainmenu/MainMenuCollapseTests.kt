/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu

import com.buzbuz.smartautoclicker.core.processing.domain.model.DebugExecutionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMenuCollapseTests {

    @Test
    fun debugPanelIsVisibleWhenDebuggingAndMenuIsExpanded() {
        assertTrue(shouldShowDebugPanel(isDebugging = true, isMenuCollapsed = false))
    }

    @Test
    fun debugPanelRemainsVisibleWhenMenuIsCollapsed() {
        assertTrue(shouldShowDebugPanel(isDebugging = true, isMenuCollapsed = true))
    }

    @Test
    fun debugPanelRemainsHiddenWhenDebuggingIsDisabled() {
        assertFalse(shouldShowDebugPanel(isDebugging = false, isMenuCollapsed = false))
    }

    @Test
    fun compactPauseMenuIsAvailableWhileRunningWithoutLiveDebugging() {
        assertTrue(shouldUseCompactPauseMenu(isDetectionRunning = true, isLiveDebuggingEnabled = false))
    }

    @Test
    fun compactPauseMenuIsHiddenWhenLiveDebuggingPanelIsEnabled() {
        assertFalse(shouldUseCompactPauseMenu(isDetectionRunning = true, isLiveDebuggingEnabled = true))
    }

    @Test
    fun compactPauseMenuIsHiddenWhenScenarioIsIdle() {
        assertFalse(shouldUseCompactPauseMenu(isDetectionRunning = false, isLiveDebuggingEnabled = false))
    }

    @Test
    fun pauseControlShowsPauseIconWhileScenarioIsRunning() {
        assertFalse(shouldShowResumeDebugIcon(DebugExecutionState.Running))
    }

    @Test
    fun pauseControlImmediatelyShowsResumeIconWhenPauseIsRequested() {
        assertTrue(shouldShowResumeDebugIcon(DebugExecutionState.WaitingForEvent))
    }

    @Test
    fun pauseControlShowsResumeIconWhileExecutionIsPaused() {
        assertTrue(
            shouldShowResumeDebugIcon(
                DebugExecutionState.Paused(eventId = 1L, eventName = "Event", conditionDurationMs = 0L),
            ),
        )
    }
}
