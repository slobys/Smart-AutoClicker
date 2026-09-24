/*
 * Copyright (C) 2024 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.domain.model.scenario

import com.buzbuz.smartautoclicker.core.base.ScenarioStats
import com.buzbuz.smartautoclicker.core.database.entity.ScenarioEntity
import com.buzbuz.smartautoclicker.core.domain.utils.asIdentifier

internal object ScenarioTestsData {

    /* ------- Scenario Data ------- */

    const val SCENARIO_ID = 42L
    const val SCENARIO_NAME = "ClickScenario"
    const val SCENARIO_DETECTION_QUALITY = 500
    const val SCENARIO_RANDOMIZE = false
    const val SCENARIO_IS_FAVORITE = true
    const val SCENARIO_GROUP_NAME = "Daily"

    fun getNewScenarioEntity(
        id: Long = SCENARIO_ID,
        name: String = SCENARIO_NAME,
        detectionQuality: Int = SCENARIO_DETECTION_QUALITY,
        randomize: Boolean = SCENARIO_RANDOMIZE,
        isFavorite: Boolean = SCENARIO_IS_FAVORITE,
        groupName: String = SCENARIO_GROUP_NAME,
    ) = ScenarioEntity(
        id = id,
        name = name,
        detectionQuality = detectionQuality,
        computeRate = 0.0,
        randomize = randomize,
        isFavorite = isFavorite,
        groupName = groupName,
    )

    fun getNewScenario(
        id: Long = SCENARIO_ID,
        name: String = SCENARIO_NAME,
        detectionQuality: Int = SCENARIO_DETECTION_QUALITY,
        randomize: Boolean = SCENARIO_RANDOMIZE,
        keepScreenOn: Boolean = false,
        eventCount: Int = 0,
        stats: ScenarioStats? = null,
        isFavorite: Boolean = SCENARIO_IS_FAVORITE,
        groupName: String = SCENARIO_GROUP_NAME,
    ) = Scenario(
        id = id.asIdentifier(),
        name = name,
        detectionQuality = detectionQuality,
        randomize = randomize,
        keepScreenOn = keepScreenOn,
        computeRate = 0.0,
        eventCount = eventCount,
        stats = stats,
        isFavorite = isFavorite,
        groupName = groupName,
    )

    fun defaultStats(): ScenarioStats =
        ScenarioStats(lastStartTimestampMs=0, startCount=0)
}
