/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.conditiontry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TryImageConditionViewModelTests {

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
