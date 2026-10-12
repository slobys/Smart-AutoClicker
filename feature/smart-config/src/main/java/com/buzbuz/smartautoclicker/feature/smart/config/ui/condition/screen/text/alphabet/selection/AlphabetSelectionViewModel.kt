/*
 * Copyright (C) 2026 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.selection

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.OCRModelsRepository
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModelState
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.AlphabetDownloadUiState
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.AlphabetSelectionItem
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.toUiState

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlphabetSelectionViewModel @Inject constructor (
    private val editionRepository: EditionRepository,
    private val ocrModelsRepository: OCRModelsRepository,
) : ViewModel() {

    private val pendingSelection = MutableStateFlow<AlphabetChoice?>(null)
    private val confirming = MutableStateFlow(false)
    private val confirmationError = MutableStateFlow<Int?>(null)
    private val models = ocrModelsRepository.recognitionModels
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Radio changes are a draft. Only confirmation updates the edited condition. */
    private val selectedChoice = editionRepository.editionState.editedScreenConditionState
        .combine(pendingSelection) { state, pending ->
            (state.value as? ScreenCondition.Text)?.let { condition ->
                pending?.takeIf { it.conditionId == condition.id }
                    ?: AlphabetChoice(condition.id, condition.alphabet)
            }
        }

    val confirmationState: Flow<AlphabetConfirmationUiState> = combine(
        selectedChoice, models, confirming, confirmationError,
    ) { choice, available, busy, error ->
        AlphabetConfirmationUiState(
            alphabet = choice?.alphabet,
            canConfirm = !busy && available.any { it.alphabet == choice?.alphabet && it.state is OCRModelState.Installed },
            isConfirming = busy,
            errorMessage = error,
        )
    }

    private val alphabetItems: Flow<List<AlphabetSelectionItem.Alphabet>> = models
        .combine(selectedChoice) { available, choice ->
            available.map { model -> model.toUiState(choice?.alphabet == model.alphabet) }
        }

    /** Tells if the user is currently editing a condition. If that's not the case, dialog should be closed. */
    @OptIn(FlowPreview::class)
    val isEditingCondition: Flow<Boolean> = editionRepository.isEditingCondition
        .distinctUntilChanged()
        .debounce(1000)

    val items: Flow<List<AlphabetSelectionItem>> = alphabetItems
        .map { models ->
            buildList {
                add(AlphabetSelectionItem.Header(R.string.item_alphabet_selection_confirm_header))
                addAll(models.sortedByState())
            }
        }

    fun selectModel(alphabet: OCRAlphabet) {
        if (confirming.value || models.value.none { it.alphabet == alphabet && it.state is OCRModelState.Installed }) return
        getEditedTextCondition()?.let { condition ->
            confirmationError.value = null
            pendingSelection.value = AlphabetChoice(condition.id, alphabet)
        }
    }

    suspend fun confirmSelection(): Boolean {
        if (confirming.value) return false
        val original = getEditedTextCondition() ?: return confirmationFailed(R.string.alphabet_selection_condition_changed)
        val choice = pendingSelection.value?.takeIf { it.conditionId == original.id }
            ?: AlphabetChoice(original.id, original.alphabet)
        confirmationError.value = null
        confirming.value = true
        try {
            // Recheck readiness rather than trusting a stale radio/download row.
            if (ocrModelsRepository.getRecognitionModel(choice.alphabet)?.state !is OCRModelState.Installed)
                return confirmationFailed(R.string.alphabet_selection_model_unavailable)
            val current = getEditedTextCondition()
                ?: return confirmationFailed(R.string.alphabet_selection_condition_changed)
            if (current.id != choice.conditionId) return confirmationFailed(R.string.alphabet_selection_condition_changed)
            editionRepository.updateEditedCondition(current.copy(alphabet = choice.alphabet))
            // Do not report success if the editor stopped accepting changes in the meantime.
            val applied = getEditedTextCondition()
            if (applied?.id != choice.conditionId || applied.alphabet != choice.alphabet)
                return confirmationFailed(R.string.alphabet_selection_apply_failed)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("AlphabetSelection", "Could not apply recognition language", error)
            return confirmationFailed(R.string.alphabet_selection_apply_failed)
        } finally {
            confirming.value = false
        }
    }

    private fun confirmationFailed(@StringRes message: Int): Boolean {
        confirmationError.value = message
        return false
    }

    fun downloadModel(alphabet: OCRAlphabet) {
        viewModelScope.launch {
            ocrModelsRepository.downloadRecognitionModel(alphabet)
        }
    }

    private fun getEditedTextCondition(): ScreenCondition.Text? =
        editionRepository.editionState.getEditedCondition<ScreenCondition>() as? ScreenCondition.Text
}

private data class AlphabetChoice(val conditionId: Identifier, val alphabet: OCRAlphabet)

data class AlphabetConfirmationUiState(
    val alphabet: OCRAlphabet?,
    val canConfirm: Boolean,
    val isConfirming: Boolean = false,
    @get:StringRes val errorMessage: Int? = null,
)

private fun List<AlphabetSelectionItem.Alphabet>.sortedByState() =
    sortedWith(
        compareBy<AlphabetSelectionItem.Alphabet> {
            it.downloadState != AlphabetDownloadUiState.Downloaded
        }.thenBy { it.alphabet.name }
    )
