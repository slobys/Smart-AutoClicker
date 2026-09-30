/* Copyright (C) 2026 Kevin Buzeau
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.buzbuz.smartautoclicker.scenarios.list.delete

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers.IO
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.scenarios.list.model.GroupableScenario
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BulkDeleteState(
    val scenarios: List<GroupableScenario> = emptyList(),
    val selected: Set<GroupableScenario.Reference> = emptySet(),
    val confirmation: List<GroupableScenario>? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val deleting: Boolean = false,
    val result: BulkDeleteResult? = null,
)

data class BulkDeleteResult(val deleted: Int, val failed: Int)

@HiltViewModel
class ScenarioBulkDeleteViewModel @Inject constructor(
    private val smartRepository: IRepository,
    private val dumbRepository: IDumbRepository,
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BulkDeleteState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                combine(smartRepository.scenarios, dumbRepository.dumbScenarios) { smart, dumb ->
                    smart.map { GroupableScenario(GroupableScenario.Reference(it.id.databaseId, true),
                        it.name, it.groupName, it.isFavorite) } +
                        dumb.map { GroupableScenario(GroupableScenario.Reference(it.id.databaseId, false),
                            it.name, it.groupName, it.isFavorite) }
                }.collect { scenarios ->
                    val references = scenarios.mapTo(mutableSetOf()) { it.reference }
                    mutableState.update { it.copy(
                        scenarios = scenarios.sortedWith(compareBy<GroupableScenario> { it.groupName }
                            .thenBy { !it.reference.isSmart }.thenBy { it.name }),
                        selected = it.selected.intersect(references), loading = false,
                    ) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("ScenarioBulkDelete", "Unable to load scenarios", error)
                mutableState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun select(references: Set<GroupableScenario.Reference>) {
        val current = state.value
        if (current.deleting || current.confirmation != null || current.loading) return
        mutableState.update { it.copy(selected = references.intersect(it.scenarios.map { item -> item.reference }.toSet())) }
    }

    fun toggleAll() {
        val current = state.value
        select(if (current.selected.size == current.scenarios.size) emptySet()
            else current.scenarios.mapTo(mutableSetOf()) { it.reference })
    }

    fun requestDeletion() {
        val current = state.value
        if (current.loading || current.deleting || current.loadFailed || current.confirmation != null) return
        val snapshot = current.scenarios.filter { it.reference in current.selected }
        if (snapshot.isNotEmpty()) mutableState.update { it.copy(confirmation = snapshot) }
    }

    fun cancelConfirmation() {
        if (!state.value.deleting) mutableState.update { it.copy(confirmation = null) }
    }

    fun confirmDeletion() {
        val current = state.value
        if (current.deleting) return
        // Only this immutable, user-confirmed snapshot can be deleted, never later-added scripts.
        val snapshot = current.confirmation ?: return
        mutableState.update { it.copy(deleting = true, confirmation = null, result = null) }
        viewModelScope.launch {
            try {
                val failures = mutableSetOf<GroupableScenario.Reference>()
                withContext(ioDispatcher) {
                    snapshot.forEach { item ->
                        currentCoroutineContext().ensureActive()
                        try {
                            if (item.reference.isSmart) {
                                smartRepository.deleteScenario(Identifier(databaseId = item.reference.databaseId))
                            } else {
                                dumbRepository.getDumbScenario(item.reference.databaseId)?.let {
                                    dumbRepository.deleteDumbScenario(it)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.e("ScenarioBulkDelete", "Unable to delete ${item.reference}", error)
                            failures += item.reference
                        }
                    }
                }
                val succeeded = snapshot.map { it.reference }.toSet() - failures
                mutableState.update { it.copy(
                    // Filter successful rows immediately; repository flows may publish a moment later.
                    scenarios = it.scenarios.filterNot { item -> item.reference in succeeded },
                    selected = failures, result = BulkDeleteResult(succeeded.size, failures.size),
                ) }
            } finally {
                mutableState.update { it.copy(deleting = false) }
            }
        }
    }
}
