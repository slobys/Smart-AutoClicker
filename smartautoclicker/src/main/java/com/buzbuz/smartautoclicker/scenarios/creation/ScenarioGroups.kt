/* Copyright (C) 2026 Kevin Buzeau
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.buzbuz.smartautoclicker.scenarios.creation

import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.databinding.IncludeScenarioGroupInputBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.util.Locale

internal const val SCENARIO_GROUP_NAME_MAX_LENGTH = 40

internal fun normalizeScenarioGroup(name: String): String = name.trim().take(SCENARIO_GROUP_NAME_MAX_LENGTH)

internal fun distinctScenarioGroups(names: List<String>): List<String> = names
    .map(::normalizeScenarioGroup).filter(String::isNotEmpty)
    .distinctBy { it.lowercase(Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)

internal fun scenarioGroupNames(smart: IRepository, dumb: IDumbRepository) =
    combine(smart.scenarios, dumb.dumbScenarios) { smartScenarios, dumbScenarios ->
        distinctScenarioGroups(smartScenarios.map { it.groupName } + dumbScenarios.map { it.groupName })
    }.catch { error ->
        if (error is CancellationException) throw error
        // Suggestions are optional. A failed list read must not crash the running overlay.
        Log.e("ScenarioGroups", "Unable to load group suggestions", error)
        emit(emptyList())
    }

internal fun resolveScenarioGroup(name: String, existing: List<String>): String {
    val normalized = normalizeScenarioGroup(name)
    return existing.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
}

internal fun IncludeScenarioGroupInputBinding.setGroups(names: List<String>) {
    // Updating suggestions must never replace the user's in-progress group name.
    groupName.setSimpleItems(distinctScenarioGroups(names).toTypedArray())
}
