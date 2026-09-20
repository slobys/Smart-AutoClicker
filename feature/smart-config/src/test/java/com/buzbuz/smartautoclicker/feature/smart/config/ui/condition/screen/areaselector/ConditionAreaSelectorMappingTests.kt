/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.areaselector

import android.graphics.Point
import android.graphics.Rect
import android.os.Build

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ConditionAreaSelectorMappingTests {

    @Test
    fun numberCondition_usesAdaptiveMinimumArea() {
        val detectionArea = Rect(100, 200, 180, 250)

        val uiState = numberCondition(detectionArea).toSelectorUiState(SCREEN_SIZE)

        assertEquals(detectionArea, uiState?.initialArea)
        assertNull(uiState?.minimalArea)
    }

    @Test
    fun numberConditionWithoutArea_isCenteredAndUsesAdaptiveMinimumArea() {
        val uiState = numberCondition(Rect()).toSelectorUiState(SCREEN_SIZE)

        assertEquals(Rect(896, 476, 1024, 604), uiState?.initialArea)
        assertNull(uiState?.minimalArea)
    }

    private fun numberCondition(detectionArea: Rect): ScreenCondition.Number = ScreenCondition.Number(
        id = Identifier(databaseId = 1L),
        eventId = Identifier(databaseId = 2L),
        name = "Number",
        threshold = 0,
        priority = 0,
        detectionArea = detectionArea,
        comparisonOperation = ComparisonOperation.EQUALS,
        counterValue = CounterOperationValue.Number(5.0),
    )
}

private val SCREEN_SIZE = Point(1920, 1080)
