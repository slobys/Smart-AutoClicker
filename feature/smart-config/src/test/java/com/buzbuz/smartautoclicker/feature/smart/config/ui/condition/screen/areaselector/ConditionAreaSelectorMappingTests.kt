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

import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ConditionAreaSelectorMappingTests {

    @Test fun selectionDoesNotMutateStoredNumberOrTextArea() {
        val area = Rect(100, 200, 124, 216)
        listOf(numberCondition(area), textCondition(area)).forEach { condition ->
            val selection = condition.toSelectorUiState(SCREEN_SIZE)!!
            assertNotSame(area, selection.initialArea)
            selection.initialArea.offset(20, 30)
            assertEquals(Rect(100, 200, 124, 216), area)
        }
    }

    @Test fun colorPreservesExistingSinglePixelAndSupportsRegionResizing() {
        val area = Rect(0, 0, 1, 1)
        val condition = ScreenCondition.Color(
            id = Identifier(databaseId = 1L), eventId = Identifier(databaseId = 2L),
            name = "Color", threshold = 0, priority = 0, shouldBeDetected = true,
            color = android.graphics.Color.RED, detectionArea = area,
        )
        val selection = condition.toSelectorUiState(SCREEN_SIZE)!!
        assertEquals(area, selection.initialArea)
        assertEquals(Rect(0, 0, 1, 1), selection.minimalArea)
        assertNotSame(area, selection.initialArea)
    }

    @Test
    fun numberCondition_usesSmallMinimumArea() {
        val detectionArea = Rect(100, 200, 180, 250)

        val uiState = numberCondition(detectionArea).toSelectorUiState(SCREEN_SIZE)

        assertEquals(detectionArea, uiState?.initialArea)
        assertEquals(Rect(0, 0, 8, 8), uiState?.minimalArea)
    }

    @Test
    fun numberConditionWithoutArea_isCenteredAndUsesSmallMinimumArea() {
        val uiState = numberCondition(Rect()).toSelectorUiState(SCREEN_SIZE)

        assertEquals(Rect(896, 476, 1024, 604), uiState?.initialArea)
        assertEquals(Rect(0, 0, 8, 8), uiState?.minimalArea)
    }

    @Test
    fun textCondition_usesSmallMinimumArea() {
        val detectionArea = Rect(100, 200, 180, 250)

        val uiState = textCondition(detectionArea).toSelectorUiState(SCREEN_SIZE)

        assertEquals(detectionArea, uiState?.initialArea)
        assertEquals(Rect(0, 0, 8, 8), uiState?.minimalArea)
    }

    @Test
    fun textConditionWithoutArea_isCenteredAndUsesSmallMinimumArea() {
        val uiState = textCondition(Rect()).toSelectorUiState(SCREEN_SIZE)

        assertEquals(Rect(896, 476, 1024, 604), uiState?.initialArea)
        assertEquals(Rect(0, 0, 8, 8), uiState?.minimalArea)
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

    private fun textCondition(detectionArea: Rect): ScreenCondition.Text = ScreenCondition.Text(
        id = Identifier(databaseId = 3L),
        eventId = Identifier(databaseId = 4L),
        name = "Text",
        threshold = 0,
        shouldBeDetected = true,
        priority = 0,
        text = "target",
        detectionArea = detectionArea,
        alphabet = OCRAlphabet.LATIN,
    )
}

private val SCREEN_SIZE = Point(1920, 1080)
