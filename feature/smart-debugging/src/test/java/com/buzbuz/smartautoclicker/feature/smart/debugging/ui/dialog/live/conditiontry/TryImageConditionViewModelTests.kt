/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.conditiontry

import android.graphics.Rect
import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.uistate.ScreenConditionResultUiState

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TryImageConditionViewModelTests {

    @Test fun targetAbsenceIsDistinctFromConditionBeingFulfilled() {
        val state = ScreenConditionResultUiState(true, Rect(), 0.1, shouldBeDetected = false).withTryThreshold(20)
        assertFalse(state.isDetected)
        assertTrue(state.positive)
    }

    @Test fun numberReadCanBeSuccessfulWhileComparisonIsFalse() {
        val state = ScreenConditionResultUiState(false, Rect(), 0.98,
            conditionFulfilledWithoutThreshold = false).withTryThreshold(20)
        assertTrue(state.isDetected)
        assertFalse(state.positive)
    }

    @Test fun invalidDataCannotFulfillAnAbsenceCondition() {
        val state = ScreenConditionResultUiState(false, Rect(), 0.0,
            shouldBeDetected = false, isValid = false).withTryThreshold(20)
        assertFalse(state.isDetected)
        assertFalse(state.positive)
    }

    @Test
    fun thresholdBoundary_IsAccepted() {
        assertTrue(isTryConditionPositive(4, 0.96, null))
    }

    @Test
    fun numberCondition_RequiresThresholdAndComparison() {
        assertTrue(isTryConditionPositive(4, 0.98, true))
        assertFalse(isTryConditionPositive(4, 0.95, true))
        assertFalse(isTryConditionPositive(4, 0.98, false))
    }
}
