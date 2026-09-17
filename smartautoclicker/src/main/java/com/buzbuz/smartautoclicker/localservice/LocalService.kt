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
package com.buzbuz.smartautoclicker.localservice

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

import com.buzbuz.smartautoclicker.core.base.data.AppComponentsProvider
import com.buzbuz.smartautoclicker.core.common.accessibility.domain.LocalAccessibilityService
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.TutorialRepository
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.domain.IRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.IDumbRepository
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.dumb.engine.DumbEngine
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.processing.domain.model.DetectionState
import com.buzbuz.smartautoclicker.core.settings.domain.SettingsRepository
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.DebuggingRepository
import com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu.MainMenu
import com.buzbuz.smartautoclicker.feature.dumb.config.ui.DumbMainMenu
import com.buzbuz.smartautoclicker.feature.notifications.ServiceNotificationController
import com.buzbuz.smartautoclicker.feature.notifications.ServiceNotificationListener
import com.buzbuz.smartautoclicker.feature.revenue.IRevenueRepository
import com.buzbuz.smartautoclicker.feature.revenue.UserBillingState

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalService(
    private val context: Context,
    private val overlayManager: OverlayManager,
    private val appComponentsProvider: AppComponentsProvider,
    private val settingsRepository: SettingsRepository,
    private val smartProcessingRepository: SmartProcessingRepository,
    private val dumbEngine: DumbEngine,
    smartScenarioRepository: IRepository,
    dumbScenarioRepository: IDumbRepository,
    private val tutorialRepository: TutorialRepository,
    private val revenueRepository: IRevenueRepository,
    private val debuggingRepository: DebuggingRepository,
    private val onStart: (scenarioId: Long, isSmart: Boolean, foregroundNotification: Notification?) -> Unit,
    private val onScenarioChanged: (scenarioId: Long, isSmart: Boolean) -> Unit,
    private val onStop: (isScenarioHandoff: Boolean) -> Unit,
) : LocalAccessibilityService {

    /** Scope for this LocalService. */
    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** Coroutine job for the delayed start of engine & ui. */
    private var startJob: Job? = null
    /** Coroutine job for the paywall result upon start from notification. */
    private var paywallResultJob: Job? = null
    /** Coroutine job serialising runtime script switches. */
    private var scenarioSwitchJob: Job? = null
    /** Guards engine, state and overlay transitions against rapid repeated input. */
    private val transitionMutex = Mutex()

    private val sessionState = RuntimeSessionStateMachine()
    private val scenarioCatalog = RuntimeScenarioCatalog(smartScenarioRepository, dumbScenarioRepository, serviceScope)
    private val overlayCoordinator = RuntimeOverlayCoordinator(context, overlayManager)
    private val permissionHandoff = RuntimePermissionHandoff(context)
    private val dumbRuntime = DumbScenarioRuntime(dumbEngine)
    private val smartRuntime = SmartScenarioRuntime(context, smartProcessingRepository, revenueRepository, debuggingRepository)

    /** Controls the notifications for the foreground service. */
    private val notificationController: ServiceNotificationController by lazy {
        ServiceNotificationController(
            context = context,
            appComponentsProvider = appComponentsProvider,
            settingsRepository = settingsRepository,
            listener = object : ServiceNotificationListener {
                override fun onPlay() = play()
                override fun onPause()= pause()
                override fun onShow() = showMenu()
                override fun onHide() = hideMenu()
                override fun onStop() = stopScenario()
            }
        )
    }

    private val canSwitchScenario: StateFlow<Boolean> = combine(
        scenarioCatalog.targets,
        sessionState.state,
    ) { scenarios, runtimeState -> scenarios.size > 1 && runtimeState is RuntimeSessionState.Active }
        .stateIn(serviceScope, SharingStarted.Eagerly, false)
    /** True if the overlay is started, false if not. */
    internal val isStarted: Boolean
        get() = sessionState.isStarted

    init {
        combine(dumbEngine.isRunning, smartProcessingRepository.detectionState) { dumbIsRunning, smartState ->
            dumbIsRunning || smartState == DetectionState.DETECTING
        }.onEach { isRunning ->
            sessionState.updatePlayback(
                if (isRunning) RuntimePlaybackState.RUNNING else RuntimePlaybackState.PAUSED
            )
            notificationController.updateNotification(context, isRunning, !overlayManager.isOverlayStackHidden())
        }.launchIn(serviceScope)

        overlayManager.isStackHidden
            .onEach { isStackHidden ->
                notificationController.updateNotification(
                    context,
                    dumbEngine.isRunning.value || smartProcessingRepository.isRunning(),
                    !isStackHidden
                )
            }
            .launchIn(serviceScope)
    }

    override fun startDumbScenario(dumbScenario: DumbScenario) {
        if (!sessionState.beginStart(RuntimeScenarioTarget.Dumb(dumbScenario).toKey(), hasMediaProjection = false)) return
        onStart(dumbScenario.id.databaseId, false, null)

        startJob = serviceScope.launch {
            delay(500)
            transitionMutex.withLock {
                dumbRuntime.load(dumbScenario)
                overlayManager.navigateTo(context, createDumbMainMenu(dumbScenario))
                sessionState.completeStart()
            }
        }
    }

    /**
     * Start the overlay UI and instantiates the detection objects.
     *
     * This requires the media projection permission code and its data intent, they both can be retrieved using the
     * results of the activity intent provided by [MediaProjectionManager.createScreenCaptureIntent] (this Intent
     * shows the dialog warning about screen recording privacy). Any attempt to call this method without the
     * correct screen capture intent result will lead to a crash.
     *
     * @param resultCode the result code provided by the screen capture intent activity result callback
     * [android.app.Activity.onActivityResult]
     * @param data the data intent provided by the screen capture intent activity result callback
     * [android.app.Activity.onActivityResult]
     * @param scenario the identifier of the scenario of clicks to be used for detection.
     */
    override fun startSmartScenario(resultCode: Int, data: Intent, scenario: Scenario) {
        if (!sessionState.beginStart(RuntimeScenarioTarget.Smart(scenario).toKey(), hasMediaProjection = true)) return

        onStart(
            scenario.id.databaseId,
            true,
            notificationController.createNotification(
                context = context,
                scenarioName = scenario.name,
                isRunning = false,
                isMenuVisible = true
            )
        )

        startJob = serviceScope.launch {
            transitionMutex.withLock {
                val mainMenu = createSmartMainMenu()
                smartRuntime.load(scenario)
                overlayManager.navigateTo(context, mainMenu)
                smartProcessingRepository.startScreenRecord(resultCode = resultCode, data = data)
                sessionState.completeStart()
            }
        }
    }

    override fun stopScenario() {
        if (!sessionState.beginStop(isPermissionHandoff = false)) return
        scenarioSwitchJob?.cancel()
        scenarioSwitchJob = null

        serviceScope.launch {
            startJob?.join()
            startJob = null
            transitionMutex.withLock {
                stopScenarioInternal(isScenarioHandoff = false)
                sessionState.completeStop()
            }
        }
    }

    override fun release() {
        serviceScope.cancel()
    }

    internal fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        return overlayManager.propagateKeyEvent(event)
    }

    private fun play() {
        serviceScope.launch {
            val active = sessionState.active ?: return@launch
            if (active.target.kind == RuntimeScenarioKind.SMART && !smartRuntime.isRunning) {
                if (shouldStartPaywall()) startPaywall()
                else startSmartDetection()
            } else if (active.target.kind == RuntimeScenarioKind.DUMB && !dumbRuntime.isRunning) {
                dumbRuntime.start()
            }
        }
    }

    private fun pause() {
        serviceScope.launch {
            when {
                dumbRuntime.isRunning -> dumbRuntime.stop()
                smartRuntime.isRunning -> smartRuntime.stop()
            }
        }
    }

    private fun shouldStartPaywall(): Boolean =
        revenueRepository.userBillingState.value == UserBillingState.AD_REQUESTED &&
                !tutorialRepository.isTutorialStarted()

    private fun startPaywall() {
        revenueRepository.startPaywallUiFlow(context)

        paywallResultJob = combine(revenueRepository.isBillingFlowInProgress, revenueRepository.userBillingState) { inProgress, state ->
            if (inProgress) return@combine

            if (state != UserBillingState.AD_REQUESTED) startSmartDetection()
            paywallResultJob?.cancel()
            paywallResultJob = null
        }.launchIn(serviceScope)
    }

    private fun startSmartDetection() {
        serviceScope.launch {
            smartRuntime.start()
        }
    }

    private fun hideMenu() {
        overlayManager.hideAll()
    }

    private fun showMenu() {
        overlayManager.restoreVisibility()
    }

    private fun createSmartMainMenu(): MainMenu = MainMenu(
        onStopClicked = ::stopScenario,
        onScenarioSwitchClicked = ::showScenarioSwitcher,
        canSwitchScenario = canSwitchScenario,
        autoCollapseDelayMs = settingsRepository.getOverlayMenuAutoCollapseDelayMs(),
    ).also { mainMenu ->
        smartProcessingRepository.setProjectionErrorHandler { mainMenu.onMediaProjectionLost() }
    }

    private fun createDumbMainMenu(scenario: DumbScenario): DumbMainMenu = DumbMainMenu(
        dumbScenarioId = scenario.id,
        onStopClicked = ::stopScenario,
        onScenarioSwitchClicked = ::showScenarioSwitcher,
        canSwitchScenario = canSwitchScenario,
        autoCollapseDelayMs = settingsRepository.getOverlayMenuAutoCollapseDelayMs(),
    )

    private fun showScenarioSwitcher() {
        val currentTarget = sessionState.active?.target
        val items = scenarioCatalog.targets.value
            .map { target ->
                RuntimeScenarioListItem(
                    target = target,
                    isCurrent = target.toKey() == currentTarget,
                )
            }
            .sortedWith(compareByDescending<RuntimeScenarioListItem> { it.isCurrent }
                .thenBy { !it.target.isSmart }
                .thenBy { it.target.name.lowercase() })

        showRuntimeScenarioSwitcher(context, items, ::switchScenario)
    }

    private fun switchScenario(target: RuntimeScenarioTarget) {
        if (scenarioSwitchJob?.isActive == true) return
        if (target.toKey() == sessionState.active?.target) return

        scenarioSwitchJob = serviceScope.launch {
            transitionMutex.withLock {
                val previous = sessionState.beginSwitch(target.toKey()) ?: return@withLock
                try {
                    val switched = when (target) {
                        is RuntimeScenarioTarget.Smart -> switchToSmartScenario(target.scenario, previous)
                        is RuntimeScenarioTarget.Dumb -> switchToDumbScenario(target.scenario, previous)
                    }

                    if (switched && sessionState.state.value is RuntimeSessionState.Switching) {
                        sessionState.completeSwitch(
                            if (dumbRuntime.isRunning || smartRuntime.isRunning) RuntimePlaybackState.RUNNING
                            else RuntimePlaybackState.PAUSED
                        )
                    } else if (!switched) {
                        restorePreviousPlayback(previous)
                        sessionState.rollbackSwitch()
                        showSwitchError()
                    }
                } catch (exception: CancellationException) {
                    sessionState.rollbackSwitch()
                    throw exception
                } catch (exception: Exception) {
                    Log.e(TAG, "Failed to switch runtime scenario to ${target.databaseId}", exception)
                    if (recoverPreviousScenario(previous)) {
                        sessionState.rollbackSwitch()
                    } else {
                        sessionState.beginStop(isPermissionHandoff = false)
                        stopScenarioInternal(isScenarioHandoff = false)
                        sessionState.completeStop()
                    }
                    showSwitchError()
                }
            }
            scenarioSwitchJob = null
        }
    }

    private suspend fun switchToDumbScenario(
        scenario: DumbScenario,
        previous: RuntimeSessionState.Active,
    ): Boolean {
        val wasRunning = if (previous.target.kind == RuntimeScenarioKind.SMART) {
            smartRuntime.prepareForSwitch() ?: return false
        } else {
            dumbRuntime.prepareForSwitch()
        }

        dumbRuntime.load(scenario)
        if (previous.hasMediaProjection) {
            smartProcessingRepository.setProjectionErrorHandler(::stopScenario)
        }
        overlayCoordinator.replaceRoot(createDumbMainMenu(scenario))
        updateScenarioMetadata(scenario.id.databaseId, isSmart = false, scenario.name)

        if (wasRunning) dumbRuntime.start()
        return true
    }

    private suspend fun switchToSmartScenario(
        scenario: Scenario,
        previous: RuntimeSessionState.Active,
    ): Boolean {
        if (!previous.hasMediaProjection) {
            sessionState.beginStop(isPermissionHandoff = true)
            stopScenarioInternal(isScenarioHandoff = true)
            sessionState.completeStop()
            permissionHandoff.requestSmartScenario(scenario.id.databaseId)
            return true
        }

        val wasSmartLoaded = previous.target.kind == RuntimeScenarioKind.SMART
        val wasRunning = if (wasSmartLoaded) {
            smartRuntime.prepareForSwitch() ?: return false
        } else {
            dumbRuntime.prepareForSwitch()
        }

        dumbRuntime.release()
        smartRuntime.load(scenario)

        if (!wasSmartLoaded || overlayManager.getBackStackTop() !is MainMenu) {
            overlayCoordinator.replaceRoot(createSmartMainMenu())
        }
        updateScenarioMetadata(scenario.id.databaseId, isSmart = true, scenario.name)
        if (wasRunning) smartRuntime.start()
        return true
    }

    private fun showSwitchError() {
        if (!sessionState.isStarted) return
        Toast.makeText(context, com.buzbuz.smartautoclicker.R.string.runtime_switcher_error, Toast.LENGTH_SHORT).show()
    }

    private suspend fun restorePreviousPlayback(previous: RuntimeSessionState.Active) {
        if (previous.playback != RuntimePlaybackState.RUNNING) return
        when (previous.target.kind) {
            RuntimeScenarioKind.SMART -> if (!smartRuntime.isRunning) smartRuntime.start()
            RuntimeScenarioKind.DUMB -> if (!dumbRuntime.isRunning) dumbRuntime.start()
        }
    }

    /** Restores engine, root menu and metadata if a switch fails after partially applying its target. */
    private suspend fun recoverPreviousScenario(previous: RuntimeSessionState.Active): Boolean {
        val previousTarget = scenarioCatalog.targets.value.firstOrNull { target -> target.toKey() == previous.target }
            ?: return false
        return try {
            when (previousTarget) {
                is RuntimeScenarioTarget.Smart -> {
                    dumbRuntime.release()
                    smartRuntime.load(previousTarget.scenario)
                    overlayCoordinator.replaceRoot(createSmartMainMenu())
                }
                is RuntimeScenarioTarget.Dumb -> {
                    dumbRuntime.load(previousTarget.scenario)
                    overlayCoordinator.replaceRoot(createDumbMainMenu(previousTarget.scenario))
                }
            }
            updateScenarioMetadata(previousTarget.databaseId, previousTarget.isSmart, previousTarget.name)
            restorePreviousPlayback(previous)
            true
        } catch (recoveryException: Exception) {
            Log.e(TAG, "Failed to restore runtime scenario ${previous.target.databaseId}", recoveryException)
            false
        }
    }

    private fun updateScenarioMetadata(scenarioId: Long, isSmart: Boolean, scenarioName: String) {
        notificationController.updateScenarioName(context, scenarioName)
        onScenarioChanged(scenarioId, isSmart)
    }

    private suspend fun stopScenarioInternal(isScenarioHandoff: Boolean) {
        dumbRuntime.release()
        overlayCoordinator.closeAll()
        smartRuntime.stopScreenRecord()

        onStop(isScenarioHandoff)
        notificationController.destroyNotification()
    }
}
private const val TAG = "LocalService"
