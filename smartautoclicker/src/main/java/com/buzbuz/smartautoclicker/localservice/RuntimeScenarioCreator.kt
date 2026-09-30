/*
 * Copyright (C) 2026 Kevin Buzeau
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.buzbuz.smartautoclicker.localservice

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.base.identifier.DATABASE_ID_INSERTION
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.processing.domain.DETECTION_QUALITY_MIN
import com.buzbuz.smartautoclicker.scenarios.creation.normalizeScenarioGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Returns the inserted row's ID, never a name-based lookup (names need not be unique). */
internal class RuntimeScenarioCreator(
    private val smartRepository: IRepository,
    private val dumbRepository: IDumbRepository,
) {
    suspend fun create(name: String, kind: RuntimeScenarioKind, screenMaxSidePx: Int, groupName: String = ""): RuntimeScenarioTarget =
        withContext(Dispatchers.IO) {
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty())
            val group = normalizeScenarioGroup(groupName)
            val insertionId = Identifier(databaseId = DATABASE_ID_INSERTION, tempId = 0L)
            when (kind) {
                RuntimeScenarioKind.SMART -> {
                    val scenario = Scenario(id = insertionId, name = trimmedName,
                        detectionQuality = max(DETECTION_QUALITY_MIN.toInt(), (screenMaxSidePx / 2.05).toInt()),
                        randomize = false, groupName = group)
                    val id = smartRepository.addScenario(scenario)
                    check(id > 0) { "Scenario insertion failed" }
                    RuntimeScenarioTarget.Smart(scenario.copy(id = Identifier(databaseId = id)))
                }
                RuntimeScenarioKind.DUMB -> {
                    val scenario = DumbScenario(id = insertionId, name = trimmedName, repeatCount = 1,
                        isRepeatInfinite = false, maxDurationMin = 1, isDurationInfinite = true, randomize = false,
                        groupName = group)
                    val id = dumbRepository.addDumbScenario(scenario)
                    check(id > 0) { "Scenario insertion failed" }
                    RuntimeScenarioTarget.Dumb(scenario.copy(id = Identifier(databaseId = id)))
                }
            }
        }
}
