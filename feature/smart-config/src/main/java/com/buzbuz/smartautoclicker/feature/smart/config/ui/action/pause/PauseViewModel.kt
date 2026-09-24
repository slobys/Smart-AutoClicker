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
package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.pause

import android.content.Context
import android.content.SharedPreferences

import androidx.core.content.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.buzbuz.smartautoclicker.core.domain.model.action.Pause
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.ui.bindings.dropdown.TimeUnitDropDownItem
import com.buzbuz.smartautoclicker.core.ui.bindings.dropdown.findAppropriateTimeUnit
import com.buzbuz.smartautoclicker.core.ui.bindings.dropdown.formatDuration
import com.buzbuz.smartautoclicker.core.ui.bindings.dropdown.toDurationMs
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.utils.getEventConfigPreferences
import com.buzbuz.smartautoclicker.feature.smart.config.utils.putPauseDurationConfig
import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PauseViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val editionRepository: EditionRepository,
) : ViewModel() {

    /** The action being configured by the user. */
    private val configuredPause = editionRepository.editionState.editedActionState
        .mapNotNull { action -> action.value }
        .filterIsInstance<Pause>()

    private val editedActionHasChanged: StateFlow<Boolean> =
        editionRepository.editionState.editedActionState
            .map { it.hasChanged }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Event configuration shared preferences. */
    private val sharedPreferences: SharedPreferences = context.getEventConfigPreferences()

    /** Tells if the user is currently editing an action. If that's not the case, dialog should be closed. */
    val isEditingAction: Flow<Boolean> = editionRepository.isEditingAction
        .distinctUntilChanged()
        .debounce(1000)

    /** The name of the pause. */
    val name: Flow<String?> = configuredPause
        .map { it.name }
        .take(1)
    /** Tells if the action name is valid or not. */
    val nameError: Flow<Boolean> = configuredPause.map { it.name?.isEmpty() ?: true }

    private val _selectedUnitItem: MutableStateFlow<TimeUnitDropDownItem> = MutableStateFlow(
        editionRepository.editionState.getEditedAction<Pause>()?.let { action ->
            action.pauseDuration.findAppropriateTimeUnit()
        } ?: TimeUnitDropDownItem.Milliseconds
    )
    val selectedUnitItem: Flow<TimeUnitDropDownItem> = _selectedUnitItem

    /** The duration of the pause in milliseconds. */
    val pauseDuration: Flow<String?> = _selectedUnitItem
        .flatMapLatest { unitItem ->
            configuredPause
                .map { unitItem.formatDuration(it.pauseDuration ?: 0) }
                .take(1)
        }
    /** Tells if the pause duration value is valid or not. */
    val pauseDurationError: Flow<Boolean> = configuredPause.map { (it.pauseDuration ?: -1) <= 0 }

    val waitModeItem: Flow<PauseWaitModeItem> = configuredPause.map { it.waitMode.toDropdownItem() }
    val timeoutItem: Flow<PauseTimeoutItem> = configuredPause.map { it.timeoutBehavior.toDropdownItem() }
    val isSmartWait: Flow<Boolean> = configuredPause.map { it.waitMode != Pause.WaitMode.FIXED_DELAY }
    val showRetries: Flow<Boolean> = configuredPause.map {
        it.waitMode != Pause.WaitMode.FIXED_DELAY && it.timeoutBehavior == Pause.TimeoutBehavior.RETRY
    }
    val showFallback: Flow<Boolean> = configuredPause.map {
        it.waitMode != Pause.WaitMode.FIXED_DELAY &&
            it.timeoutBehavior == Pause.TimeoutBehavior.EXECUTE_FALLBACK
    }
    val showScreenComparison: Flow<Boolean> = configuredPause.map {
        it.waitMode == Pause.WaitMode.SCREEN_STABLE || it.waitMode == Pause.WaitMode.SCREEN_CHANGED
    }
    val showWaitTarget: Flow<Boolean> = configuredPause.map {
        it.waitMode == Pause.WaitMode.TARGET_APPEARS || it.waitMode == Pause.WaitMode.TARGET_DISAPPEARS
    }
    val maxRetries: Flow<String> = configuredPause.map { it.maxRetries.toString() }
    val confirmationFrames: Flow<String> = configuredPause.map { it.confirmationFrames.toString() }
    val changeThreshold: Flow<String> = configuredPause.map { it.changeThresholdPercent.toString() }
    val fallbackEventName: Flow<String?> = configuredPause.map { pause ->
        pause.fallbackEventId?.let { id ->
            editionRepository.editionState.getAllEditedEvents().firstOrNull { it.id == id }?.name
        }
    }
    val waitTargetEventName: Flow<String?> = configuredPause.map { pause ->
        pause.waitTargetEventId?.let { id ->
            editionRepository.editionState.getAllEditedEvents().firstOrNull { it.id == id }?.name
        }
    }

    fun getWaitTargetEvents(): List<ScreenEvent> =
        editionRepository.editionState.getAllEditedEvents()
            .filterIsInstance<ScreenEvent>()
            .filter { it.conditions.isNotEmpty() }

    fun getFallbackEvents(): List<Event> {
        val currentEventId = editionRepository.editionState.getEditedEvent()?.id
        return editionRepository.editionState.getAllEditedEvents().filter { it.id != currentEventId }
    }

    /** Tells if the configured pause is valid and can be saved. */
    val isValidAction: Flow<Boolean> = editionRepository.editionState.editedActionState
        .map { it.canBeSaved }


    fun hasUnsavedModifications(): Boolean =
        editedActionHasChanged.value

    /**
     * Set the name of the pause.
     * @param name the new name.
     */
    fun setName(name: String) {
        editionRepository.editionState.getEditedAction<Pause>()?.let { pause ->
            editionRepository.updateEditedAction(pause.copy(name = "" + name))
        }
    }

    /**
     * Set the duration of the pause.
     * @param duration the new duration.
     */
    fun setPauseDuration(duration: Long?) {
        editionRepository.editionState.getEditedAction<Pause>()?.let { oldPause ->
            val newDurationMs = duration.toDurationMs(_selectedUnitItem.value)

            if (oldPause.pauseDuration != newDurationMs) {
                editionRepository.updateEditedAction(oldPause.copy(pauseDuration = newDurationMs))
            }
        }
    }

    fun setTimeUnit(unit: TimeUnitDropDownItem) {
        _selectedUnitItem.value = unit
    }

    fun setWaitMode(item: PauseWaitModeItem) = updatePause { copy(waitMode = item.mode) }

    fun setWaitTargetEvent(event: ScreenEvent) = updatePause { copy(waitTargetEventId = event.id) }

    fun setTimeoutBehavior(item: PauseTimeoutItem) = updatePause { copy(timeoutBehavior = item.behavior) }

    fun setMaxRetries(value: Int?) = updatePause { copy(maxRetries = value ?: -1) }

    fun setConfirmationFrames(value: Int?) = updatePause { copy(confirmationFrames = value ?: 0) }

    fun setChangeThreshold(value: Int?) = updatePause { copy(changeThresholdPercent = value ?: 0) }

    fun setFallbackEvent(event: Event) = updatePause { copy(fallbackEventId = event.id) }

    private fun updatePause(transform: Pause.() -> Pause) {
        editionRepository.editionState.getEditedAction<Pause>()?.let { pause ->
            editionRepository.updateEditedAction(pause.transform())
        }
    }

    fun saveLastConfig() {
        editionRepository.editionState.getEditedAction<Pause>()?.let { pause ->
            sharedPreferences.edit { putPauseDurationConfig(pause.pauseDuration ?: 0) }
        }
    }
}
