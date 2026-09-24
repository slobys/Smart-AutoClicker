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
package com.buzbuz.smartautoclicker.scenarios.list

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import com.buzbuz.smartautoclicker.scenarios.list.model.ScenarioBackupSelection
import com.buzbuz.smartautoclicker.scenarios.list.model.GroupableScenario
import com.buzbuz.smartautoclicker.scenarios.list.model.ScenarioListUiState
import com.buzbuz.smartautoclicker.scenarios.list.model.isEmpty
import com.buzbuz.smartautoclicker.scenarios.list.model.toggleAllScenarioSelectionForBackup
import com.buzbuz.smartautoclicker.scenarios.list.model.toggleScenarioSelectionForBackup
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.settings.domain.SettingsRepository
import com.buzbuz.smartautoclicker.core.settings.domain.model.ScenarioSortType
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getImageConditionBitmap

import dagger.hilt.android.lifecycle.HiltViewModel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import javax.inject.Inject
import java.util.Locale

@HiltViewModel
class ScenarioListViewModel @Inject constructor(
    filteredScenarioListUseCase: FilteredScenarioListUseCase,
    private val settingsRepository: SettingsRepository,
    private val bitmapRepository: BitmapRepository,
    private val smartRepository: IRepository,
    private val dumbRepository: IDumbRepository,
) : ViewModel() {

    /** Current state type of the ui. */
    private val uiStateType = MutableStateFlow(ScenarioListUiState.Type.SELECTION)

    /** Set of scenario with their items expanded. */
    private val expandedItems = MutableStateFlow(ScenarioExpandedSelection())
    /** Set of scenario identifier selected for a backup. */
    private val selectedForBackup = MutableStateFlow(ScenarioBackupSelection())
    /** Group headers collapsed for the current screen session. */
    private val collapsedGroups = MutableStateFlow<Set<String>>(emptySet())

    /** The currently searched action name. Null if no is. */
    private val searchQuery = MutableStateFlow<String?>(null)

    /** All scenarios, independently from current search/filter/collapse state, for bulk group editing. */
    val groupableScenarios: StateFlow<List<GroupableScenario>> = combine(
        dumbRepository.dumbScenarios,
        smartRepository.scenarios,
    ) { dumbScenarios, smartScenarios ->
        buildList {
            addAll(dumbScenarios.map { scenario ->
                GroupableScenario(
                    reference = GroupableScenario.Reference(
                        databaseId = scenario.id.databaseId,
                        isSmart = false,
                    ),
                    name = scenario.name,
                    groupName = scenario.groupName,
                    isFavorite = scenario.isFavorite,
                )
            })
            addAll(smartScenarios.map { scenario ->
                GroupableScenario(
                    reference = GroupableScenario.Reference(
                        databaseId = scenario.id.databaseId,
                        isSmart = true,
                    ),
                    name = scenario.name,
                    groupName = scenario.groupName,
                    isFavorite = scenario.isFavorite,
                )
            })
        }.sortedWith(
            compareBy<GroupableScenario> { !it.reference.isSmart }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )

    val uiState: StateFlow<ScenarioListUiState?> = combine(
        uiStateType,
        filteredScenarioListUseCase(searchQuery),
        selectedForBackup,
        expandedItems,
        collapsedGroups,
    ) { stateType, items, backupSelection, expanded, collapsed ->
        ScenarioListUiState(
            type = stateType,
            menuUiState = stateType.toMenuUiState(items, backupSelection),
            listContent = when (stateType) {
                ScenarioListUiState.Type.SELECTION ->
                    items.updateExpanded(expanded).applyGroupCollapse(collapsed)
                ScenarioListUiState.Type.SEARCH -> items.updateExpanded(expanded)
                ScenarioListUiState.Type.EXPORT -> items.filterForBackupSelection(backupSelection)
            },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    val needsConditionMigration: Flow<Boolean> =
        smartRepository.legacyConditionsCount.map { it != 0 }

    /**
     * Change the ui state type.
     * @param state the new state.
     */
    fun setUiState(state: ScenarioListUiState.Type) {
        uiStateType.value = state
        selectedForBackup.value = selectedForBackup.value.copy(
            dumbSelection = emptySet(),
            smartSelection = emptySet(),
        )
    }

    /**
     * Update the action search query.
     * @param query the new query.
     */
    fun updateSearchQuery(query: String?) {
        searchQuery.update { query }
    }

    fun updateSortType(type: ScenarioSortType) {
        settingsRepository.setScenarioSortType(type)
    }

    fun updateSortOrder(isChecked: Boolean) {
        settingsRepository.setScenarioSortOrder(isChecked)
    }

    fun updateDumbVisible(show: Boolean) {
        settingsRepository.setScenarioSortShowDumb(show)
    }

    fun updateSmartVisible(show: Boolean) {
        settingsRepository.setScenarioSortShowSmart(show)
    }

    fun getScenarioValidForBackupCount(): Int =
        uiState.value?.listContent?.fold(initial = 0) { acc, item ->
            if (item is ScenarioListUiState.Item.ScenarioItem.Valid) acc + 1
            else acc
        } ?: 0

    /** @return the list of selected dumb scenario identifiers. */
    fun getDumbScenariosSelectedForBackup(): Collection<Long> =
        selectedForBackup.value.dumbSelection.toList()

    /** @return the list of selected smart scenario identifiers. */
    fun getSmartScenariosSelectedForBackup(): Collection<Long> =
        selectedForBackup.value.smartSelection.toList()

    /**
     * Toggle the selected for backup state of a scenario.
     * @param scenario the scenario to be toggled.
     */
    fun toggleScenarioSelectionForBackup(scenario: ScenarioListUiState.Item) {
        selectedForBackup.value.toggleScenarioSelectionForBackup(scenario)?.let {
            selectedForBackup.value = it
        }
    }

    /** Toggle the selected for backup state value for all scenario. */
    fun toggleAllScenarioSelectionForBackup() {
        selectedForBackup.value = selectedForBackup.value.toggleAllScenarioSelectionForBackup(
            uiState.value?.listContent ?: emptyList()
        )
    }

    fun expandCollapseItem(item: ScenarioListUiState.Item) {
        if (item !is ScenarioListUiState.Item.ScenarioItem.Valid) return

        expandedItems.value = expandedItems.value.let { oldSelection ->
            when (item) {
                is ScenarioListUiState.Item.ScenarioItem.Valid.Smart -> {
                    oldSelection.copy(
                        smartSelection = oldSelection.smartSelection
                            .toMutableSet()
                            .toggleExpandedSelection(item.getScenarioId())
                    )
                }

                is ScenarioListUiState.Item.ScenarioItem.Valid.Dumb -> {
                    oldSelection.copy(
                        dumbSelection = oldSelection.dumbSelection
                            .toMutableSet()
                            .toggleExpandedSelection(item.getScenarioId())
                    )
                }
            }
        }
    }

    /**
     * Delete a click scenario.
     *
     * This will also delete all child entities associated with the scenario.
     *
     * @param item the scenario to be deleted.
     */
    fun deleteScenario(item: ScenarioListUiState.Item.ScenarioItem) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val scenario = item.scenario) {
                is DumbScenario -> dumbRepository.deleteDumbScenario(scenario)
                is Scenario -> smartRepository.deleteScenario(scenario.id)
            }
        }
    }

    fun toggleFavorite(item: ScenarioListUiState.Item.ScenarioItem) {
        updateOrganization(item, isFavorite = !item.isFavorite, groupName = item.groupName)
    }

    fun updateGroup(item: ScenarioListUiState.Item.ScenarioItem, groupName: String) {
        updateOrganization(
            item = item,
            isFavorite = item.isFavorite,
            groupName = groupName.trim().take(MAX_GROUP_NAME_LENGTH),
        )
    }

    fun toggleGroupCollapsed(header: ScenarioListUiState.Item.GroupHeader) {
        val key = header.groupName.normalizedGroupName()
        collapsedGroups.update { current ->
            if (key in current) current - key else current + key
        }
    }

    /**
     * Create or edit a group in one operation. Selected scenarios are moved to [groupName].
     * When editing, scenarios removed from the selection become ungrouped.
     */
    fun saveGroup(
        originalGroupName: String?,
        groupName: String,
        selectedScenarios: Set<GroupableScenario.Reference>,
    ) {
        val targetGroupName = groupName.trim().take(MAX_GROUP_NAME_LENGTH)
        if (targetGroupName.isEmpty() || selectedScenarios.isEmpty()) return

        val originalKey = originalGroupName?.normalizedGroupName()
        val scenarios = groupableScenarios.value
        viewModelScope.launch(Dispatchers.IO) {
            scenarios.forEach { scenario ->
                val currentlyInEditedGroup = originalKey != null &&
                    scenario.groupName.normalizedGroupName() == originalKey
                val newGroupName = when {
                    scenario.reference in selectedScenarios -> targetGroupName
                    currentlyInEditedGroup -> ""
                    else -> scenario.groupName
                }

                if (newGroupName != scenario.groupName) {
                    updateScenarioGroup(scenario, newGroupName)
                }
            }
        }
    }

    fun deleteGroup(groupName: String) {
        val groupKey = groupName.normalizedGroupName()
        val scenarios = groupableScenarios.value
        viewModelScope.launch(Dispatchers.IO) {
            scenarios
                .filter { it.groupName.normalizedGroupName() == groupKey }
                .forEach { updateScenarioGroup(it, "") }
            collapsedGroups.update { it - groupKey }
        }
    }

    private suspend fun updateScenarioGroup(scenario: GroupableScenario, groupName: String) {
        if (scenario.reference.isSmart) {
            smartRepository.updateScenarioOrganization(
                scenarioId = scenario.reference.databaseId,
                isFavorite = scenario.isFavorite,
                groupName = groupName,
            )
        } else {
            dumbRepository.updateScenarioOrganization(
                scenarioId = scenario.reference.databaseId,
                isFavorite = scenario.isFavorite,
                groupName = groupName,
            )
        }
    }

    private fun updateOrganization(
        item: ScenarioListUiState.Item.ScenarioItem,
        isFavorite: Boolean,
        groupName: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val scenario = item.scenario) {
                is DumbScenario -> dumbRepository.updateScenarioOrganization(
                    scenarioId = scenario.id.databaseId,
                    isFavorite = isFavorite,
                    groupName = groupName,
                )
                is Scenario -> smartRepository.updateScenarioOrganization(
                    scenarioId = scenario.id.databaseId,
                    isFavorite = isFavorite,
                    groupName = groupName,
                )
            }
        }
    }

    /**
     * Get the bitmap corresponding to a condition.
     * Loading is async and the result notified via the onBitmapLoaded argument.
     *
     * @param condition the condition to load the bitmap of.
     * @param onBitmapLoaded the callback notified upon completion.
     */
    fun getConditionBitmap(condition: ScreenCondition.Image, onBitmapLoaded: (Bitmap?) -> Unit): Job =
        getImageConditionBitmap(bitmapRepository, condition, onBitmapLoaded)

    private fun ScenarioListUiState.Type.toMenuUiState(
        scenarioItems: List<ScenarioListUiState.Item>,
        backupSelection: ScenarioBackupSelection,
    ): ScenarioListUiState.Menu = when (this) {
        ScenarioListUiState.Type.SEARCH -> ScenarioListUiState.Menu.Search
        ScenarioListUiState.Type.EXPORT -> ScenarioListUiState.Menu.Export(
            canExport = !backupSelection.isEmpty(),
        )
        ScenarioListUiState.Type.SELECTION -> ScenarioListUiState.Menu.Selection(
            searchEnabled = scenarioItems.isNotEmpty(),
            groupsEnabled = scenarioItems.any { it is ScenarioListUiState.Item.ScenarioItem },
        )
    }

    private fun List<ScenarioListUiState.Item>.filterForBackupSelection(
        backupSelection: ScenarioBackupSelection,
    ) : List<ScenarioListUiState.Item> = mapNotNull { item ->
        when (item) {
            is ScenarioListUiState.Item.SortItem -> item
            is ScenarioListUiState.Item.ScenarioItem.Valid.Dumb -> item.copy(
                showExportCheckbox = true,
                checkedForExport = backupSelection.dumbSelection.contains(item.scenario.id.databaseId)
            )
            is ScenarioListUiState.Item.ScenarioItem.Valid.Smart -> item.copy(
                showExportCheckbox = true,
                checkedForExport = backupSelection.smartSelection.contains(item.scenario.id.databaseId)
            )
            else -> null
        }
    }

    private fun List<ScenarioListUiState.Item>.updateExpanded(
        expanded: ScenarioExpandedSelection,
    ) : List<ScenarioListUiState.Item> = map { item ->
        when (item) {
            is ScenarioListUiState.Item.ScenarioItem.Valid.Dumb ->
                item.copy(expanded = expanded.dumbSelection.contains(item.getScenarioId()))
            is ScenarioListUiState.Item.ScenarioItem.Valid.Smart ->
                item.copy(expanded = expanded.smartSelection.contains(item.getScenarioId()))
            else -> item
        }
    }

    private fun List<ScenarioListUiState.Item>.applyGroupCollapse(
        collapsed: Set<String>,
    ): List<ScenarioListUiState.Item> = buildList {
        var hideScenarios = false
        this@applyGroupCollapse.forEach { item ->
            when (item) {
                is ScenarioListUiState.Item.GroupHeader -> {
                    val isCollapsed = item.groupName.normalizedGroupName() in collapsed
                    hideScenarios = isCollapsed
                    add(item.copy(isCollapsed = isCollapsed))
                }
                is ScenarioListUiState.Item.SortItem -> {
                    hideScenarios = false
                    add(item)
                }
                is ScenarioListUiState.Item.ScenarioItem -> if (!hideScenarios) add(item)
            }
        }
    }

    private fun MutableSet<Long>.toggleExpandedSelection(id: Long): MutableSet<Long> {
        if (!contains(id)) add(id)
        else remove(id)

        return this
    }
}

data class ScenarioExpandedSelection(
    val dumbSelection: Set<Long> = mutableSetOf(),
    val smartSelection: Set<Long> = mutableSetOf(),
)

private const val MAX_GROUP_NAME_LENGTH = 40

private fun String.normalizedGroupName(): String = trim().lowercase(Locale.ROOT)
