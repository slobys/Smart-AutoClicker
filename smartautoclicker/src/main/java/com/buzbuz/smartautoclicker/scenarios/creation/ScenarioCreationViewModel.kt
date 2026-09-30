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
package com.buzbuz.smartautoclicker.scenarios.creation

import android.content.Context
import android.util.Log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.base.identifier.DATABASE_ID_INSERTION
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.processing.domain.DETECTION_QUALITY_MIN
import com.buzbuz.smartautoclicker.feature.revenue.IRevenueRepository
import com.buzbuz.smartautoclicker.feature.revenue.UserBillingState

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.floor
import kotlin.math.max

@HiltViewModel
class ScenarioCreationViewModel @Inject constructor(
    @ApplicationContext context: Context,
    revenueRepository: IRevenueRepository,
    private val smartRepository: IRepository,
    private val dumbRepository: IDumbRepository,
    private val displayConfigManager: DisplayConfigManager,
) : ViewModel() {

    private val _name: MutableStateFlow<String?> =
        MutableStateFlow(context.getString(R.string.default_scenario_name))
    val name: Flow<String> = _name
        .map { it ?: "" }
        .take(1)
    val nameError: Flow<Boolean> = _name
        .map { it.isNullOrBlank() }
    val showPaidLimitationWarning: Flow<Boolean> = revenueRepository.userBillingState
        .map { it != UserBillingState.PURCHASED }

    private val _selectedType: MutableStateFlow<ScenarioTypeSelection> =
        MutableStateFlow(ScenarioTypeSelection.SMART)
    val scenarioTypeSelectionState: Flow<ScenarioTypeSelectionState> =
        combine(_selectedType, revenueRepository.userBillingState) { selectedType, billingState ->
            ScenarioTypeSelectionState(
                dumbItem = ScenarioTypeItem.Dumb,
                smartItem = ScenarioTypeItem.Smart,
                selectedItem = selectedType,
            )
        }

    private val canBeCreated: Flow<Boolean> = _name.map { name -> !name.isNullOrBlank() }
    private val _creationState: MutableStateFlow<CreationState> =
        MutableStateFlow(CreationState.CONFIGURING)
    val creationState: Flow<CreationState> = _creationState.combine(canBeCreated) { state, valid ->
        if (state == CreationState.CONFIGURING && !valid) CreationState.CONFIGURING_INVALID
        else state
    }

    fun setName(newName: String?) {
        _name.value = newName
    }

    fun setSelectedType(type: ScenarioTypeSelection) {
        _selectedType.value = type
    }
    val creationError = MutableStateFlow(false)

    val groups = scenarioGroupNames(smartRepository, dumbRepository)
    var groupName: String = ""
        private set

    fun setGroupName(value: String) { groupName = normalizeScenarioGroup(value) }

    fun createScenario() {
        if (isInvalidForCreation() || _creationState.value != CreationState.CONFIGURING) return

        _creationState.value = CreationState.CREATING
        creationError.value = false
        val requestedName = _name.value!!.trim()
        val requestedType = _selectedType.value
        val requestedGroup = groupName
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val group = resolveScenarioGroup(requestedGroup, groups.first())
                when (requestedType) {
                    ScenarioTypeSelection.DUMB -> createDumbScenario(requestedName, group)
                    ScenarioTypeSelection.SMART -> createSmartScenario(requestedName, group)
                }
                _creationState.value = CreationState.SAVED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("ScenarioCreation", "Unable to create scenario", error)
                creationError.value = true
                _creationState.value = CreationState.CONFIGURING
            }
        }
    }

    private suspend fun createDumbScenario(name: String, group: String) {
        val id = dumbRepository.addDumbScenario(
            DumbScenario(
                id = Identifier(databaseId = DATABASE_ID_INSERTION, tempId = 0L),
                name = name,
                dumbActions = emptyList(),
                repeatCount = 1,
                isRepeatInfinite = false,
                maxDurationMin = 1,
                isDurationInfinite = true,
                randomize = false,
                groupName = group,
            )
        )
        check(id > 0) { "Scenario insertion failed" }
    }

    private suspend fun createSmartScenario(name: String, group: String) {
        val id = smartRepository.addScenario(
            Scenario(
                id = Identifier(databaseId = DATABASE_ID_INSERTION, tempId = 0L),
                name = name,
                detectionQuality = getDefaultDetectionQuality(),
                randomize = false,
                groupName = group,
            )
        )
        check(id > 0) { "Scenario insertion failed" }
    }

    private fun getDefaultDetectionQuality(): Int {
        val displaySize = displayConfigManager.displayConfig.sizePx
        val biggestScreenSideSize: Int = max(displaySize.x, displaySize.y)

        return max(
            DETECTION_QUALITY_MIN.toInt(),
            floor(biggestScreenSideSize / DEFAULT_DETECTION_QUALITY_RATIO).toInt(),
        )
    }

    private fun isInvalidForCreation(): Boolean = _name.value.isNullOrBlank()
}


data class ScenarioTypeSelectionState(
    val dumbItem: ScenarioTypeItem.Dumb,
    val smartItem: ScenarioTypeItem.Smart,
    val selectedItem: ScenarioTypeSelection,
)

sealed class ScenarioTypeItem(val titleRes: Int, val iconRes: Int, val descriptionText: Int) {

    data object Dumb: ScenarioTypeItem(
        titleRes = R.string.item_title_dumb_scenario,
        iconRes = R.drawable.ic_dumb,
        descriptionText = R.string.item_desc_dumb_scenario,
    )

    data object Smart: ScenarioTypeItem(
        titleRes = R.string.item_title_smart_scenario,
        iconRes = R.drawable.ic_smart,
        descriptionText = R.string.item_desc_smart_scenario,
    )
}
enum class ScenarioTypeSelection {
    DUMB,
    SMART,
}

enum class CreationState {
    CONFIGURING_INVALID,
    CONFIGURING,
    CREATING,
    SAVED,
}

private const val DEFAULT_DETECTION_QUALITY_RATIO = 2.05
