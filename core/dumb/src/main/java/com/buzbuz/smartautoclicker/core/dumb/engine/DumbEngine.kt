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
package com.buzbuz.smartautoclicker.core.dumb.engine

import android.util.Log

import com.buzbuz.smartautoclicker.core.base.Dumpable
import com.buzbuz.smartautoclicker.core.base.addDumpTabulationLvl
import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers.IO
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbAction
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.settings.domain.SettingsRepository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DumbEngine @Inject constructor(
    private val dumbRepository: IDumbRepository,
    private val dumbActionExecutor: DumbActionExecutor,
    private val settingsRepository: SettingsRepository,
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Dumpable {

    /** Coroutine scope for the dumb scenario processing. */
    private var processingScope: CoroutineScope? = null
    /** Job for the scenario auto stop. */
    private var timeoutJob: Job? = null
    /** Job for the scenario execution. */
    private var executionJob: Job? = null
    private var startupJob: Job? = null
    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting
    /** Completion listener on dumb actions tries.*/
    private var onTryCompletedListener: (() -> Unit)? = null

    private val dumbScenarioDbId: MutableStateFlow<Long?> = MutableStateFlow(null)
    val dumbScenario: Flow<DumbScenario?> =
        dumbScenarioDbId.flatMapLatest { dbId ->
            if (dbId == null) flowOf(null)
            else dumbRepository.getDumbScenarioFlow(dbId)
        }

    private val _isRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    @Synchronized fun init(dumbScenario: DumbScenario) {
        release()
        dumbActionExecutor.setUnblockWorkaround(settingsRepository.isInputBlockWorkaroundEnabled())
        dumbScenarioDbId.value = dumbScenario.id.databaseId

        processingScope = CoroutineScope(ioDispatcher)
        processingScope?.launch {
            dumbRepository.markAsUsed(dumbScenario.id)
        }
    }

    @Synchronized fun startDumbScenario() {
        if (_isRunning.value || startupJob != null) return
        val scope = processingScope ?: return
        val dbId = dumbScenarioDbId.value ?: return
        _isStarting.value = true
        startupJob = scope.launch(start = CoroutineStart.LAZY) {
            val request = coroutineContext.job
            try {
                val scenario = dumbRepository.getDumbScenario(dbId) ?: return@launch
                coroutineContext.ensureActive()
                synchronized(this@DumbEngine) {
                    if (startupJob === request && dumbScenarioDbId.value == dbId) startEngine(scenario)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Unable to load simple scenario", error)
            } finally {
                synchronized(this@DumbEngine) {
                    if (startupJob === request) {
                        startupJob = null
                        _isStarting.value = false
                    }
                }
            }
        }
        startupJob?.start()
    }

    @Synchronized fun tryDumbAction(dumbAction: DumbAction, completionListener: () -> Unit) {
        stopDumbScenario()
        Log.i(TAG, "Trying dumb action: $dumbAction")
        onTryCompletedListener = completionListener
        startEngine(dumbAction.toDumbScenarioTry())
    }

    @Synchronized fun stopDumbScenario() {
        // Loading is cancellable too, even before the engine reports itself as running.
        startupJob?.cancel()
        startupJob = null
        _isStarting.value = false
        _isRunning.value = false

        Log.d(TAG, "stopDumbScenario")

        timeoutJob?.cancel()
        timeoutJob = null
        executionJob?.cancel()
        executionJob = null

        val completedListener = onTryCompletedListener
        onTryCompletedListener = null
        completedListener?.invoke()
    }

    @Synchronized fun release() {
        stopDumbScenario()

        dumbScenarioDbId.value = null
        processingScope?.cancel()
        processingScope = null
    }

    private fun startEngine(scenario: DumbScenario) {
        if (processingScope == null || _isRunning.value || scenario.dumbActions.isEmpty()) return
        _isRunning.value = true

        Log.d(TAG, "startDumbScenario ${scenario.id} with ${scenario.dumbActions.size} actions")

        if (!scenario.isDurationInfinite) timeoutJob = startTimeoutJob(scenario.maxDurationMin)
        executionJob = startScenarioExecutionJob(scenario)
        timeoutJob?.start()
        executionJob?.start()
    }

    private fun startTimeoutJob(timeoutDurationMinutes: Int): Job? =
        processingScope?.launch(start = CoroutineStart.LAZY) {
            Log.d(TAG, "startTimeoutJob: timeoutDurationMinutes=$timeoutDurationMinutes")
            delay(timeoutDurationMinutes.minutes.inWholeMilliseconds)

            synchronized(this@DumbEngine) {
                if (timeoutJob === coroutineContext.job) stopDumbScenario()
            }
        }

    private fun startScenarioExecutionJob(dumbScenario: DumbScenario): Job? =
        processingScope?.launch(start = CoroutineStart.LAZY) {
            try {
                dumbScenario.repeat {
                    dumbScenario.dumbActions.forEach { dumbAction ->
                        coroutineContext.ensureActive()
                        dumbActionExecutor.executeDumbAction(dumbAction, dumbScenario.randomize)
                    }
                    coroutineContext.ensureActive()
                    dumbActionExecutor.onScenarioLoopFinished()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UnconfirmedGestureException) {
                Log.w(TAG, "Stopping simple scenario to prevent duplicate input", error)
            } finally {
                synchronized(this@DumbEngine) {
                    if (executionJob === coroutineContext.job) stopDumbScenario()
                }
            }
        }

    override fun dump(writer: PrintWriter, prefix: CharSequence) {
        val contentPrefix = prefix.addDumpTabulationLvl()

        writer.apply {
            append(prefix).println("* DumbEngine:")

            append(contentPrefix)
                .append("- scenarioId=${dumbScenarioDbId.value}; ")
                .append("isRunning=${isRunning.value}; ")
                .println()
        }
    }
}

private const val TAG = "DumbEngine"
