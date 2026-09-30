package com.buzbuz.smartautoclicker.scenarios.creation

import org.junit.Assert.*
import org.junit.Test

class ScenarioGroupsTests {
    @Test fun suggestionsDropBlankGroupsAndMergeCaseInsensitiveDuplicates() {
        assertEquals(listOf("Daily", "任务"), distinctScenarioGroups(listOf(" ", "Daily", "daily", "任务", " 任务 ")))
    }
    @Test fun existingGroupKeepsItsNameWhileNewGroupIsTrimmed() {
        assertEquals("Daily", resolveScenarioGroup(" daily ", listOf("Daily")))
        assertEquals("新分组", resolveScenarioGroup(" 新分组 ", listOf("任务")))
        assertEquals("", resolveScenarioGroup("  ", listOf("任务")))
    }
    @Test fun groupLengthMatchesExistingGroupEditor() {
        assertEquals(40, normalizeScenarioGroup("长".repeat(60)).length)
    }
}
