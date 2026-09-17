/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.localservice

import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal class RuntimeScenarioCatalog(
    smartRepository: IRepository,
    dumbRepository: IDumbRepository,
    scope: CoroutineScope,
) {
    val targets: StateFlow<List<RuntimeScenarioTarget>> = combine(
        smartRepository.scenarios,
        dumbRepository.dumbScenarios,
    ) { smartScenarios, dumbScenarios ->
        buildList {
            addAll(smartScenarios.filter { it.eventCount > 0 }.map(RuntimeScenarioTarget::Smart))
            addAll(dumbScenarios.filter { it.isValid() }.map(RuntimeScenarioTarget::Dumb))
        }.sortedWith(compareBy<RuntimeScenarioTarget> { !it.isSmart }.thenBy { it.name.lowercase() })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
}
