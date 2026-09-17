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
import com.buzbuz.smartautoclicker.feature.qstile.ui.QSTileLauncherActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    /** State of this LocalService. */
    private val serviceState: MutableStateFlow<LocalServiceState> = MutableStateFlow(LocalServiceState())
    private var state: LocalServiceState
        get() = serviceState.value
        set(value) { serviceState.value = value }

    private val isScenarioSwitching: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val runtimeScenarioTargets: StateFlow<List<RuntimeScenarioTarget>> = combine(
        smartScenarioRepository.scenarios,
        dumbScenarioRepository.dumbScenarios,
    ) { smartScenarios, dumbScenarios ->
        buildList {
            addAll(smartScenarios.filter { it.eventCount > 0 }.map(RuntimeScenarioTarget::Smart))
            addAll(dumbScenarios.filter { it.isValid() }.map(RuntimeScenarioTarget::Dumb))
        }.sortedWith(compareBy<RuntimeScenarioTarget> { !it.isSmart }.thenBy { it.name.lowercase() })
    }.stateIn(serviceScope, SharingStarted.Eagerly, emptyList())

    private val canSwitchScenario: StateFlow<Boolean> = combine(
        runtimeScenarioTargets,
        isScenarioSwitching,
    ) { scenarios, switching -> scenarios.size > 1 && !switching }
        .stateIn(serviceScope, SharingStarted.Eagerly, false)
    /** True if the overlay is started, false if not. */
    internal val isStarted: Boolean
        get() = state.isStarted

    init {
        combine(dumbEngine.isRunning, smartProcessingRepository.detectionState) { dumbIsRunning, smartState ->
            dumbIsRunning || smartState == DetectionState.DETECTING
        }.onEach { isRunning ->
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
        if (state.isStarted) return
        state = LocalServiceState(
            isStarted = true,
            isSmartLoaded = false,
            scenarioId = dumbScenario.id.databaseId,
        )
        onStart(dumbScenario.id.databaseId, false, null)

        startJob = serviceScope.launch {
            delay(500)

            dumbEngine.init(dumbScenario)

            overlayManager.navigateTo(
                context = context,
                newOverlay = createDumbMainMenu(dumbScenario),
            )
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
        if (isStarted) return
        state = LocalServiceState(
            isStarted = true,
            isSmartLoaded = true,
            scenarioId = scenario.id.databaseId,
            hasMediaProjection = true,
        )

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
            val mainMenu = createSmartMainMenu()

            smartProcessingRepository.apply {
                setScenarioId(scenario.id, markAsUsed = true)
            }

            overlayManager.navigateTo(
                context = context,
                newOverlay = mainMenu,
            )

            smartProcessingRepository.startScreenRecord(
                resultCode = resultCode,
                data = data,
            )
        }
    }

    override fun stopScenario() {
        if (!isStarted) return
        scenarioSwitchJob?.cancel()
        scenarioSwitchJob = null
        isScenarioSwitching.value = false
        state = LocalServiceState()

        serviceScope.launch {
            stopScenarioInternal(isScenarioHandoff = false)
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
            if (state.isSmartLoaded && !smartProcessingRepository.isRunning()) {
                if (shouldStartPaywall()) startPaywall()
                else startSmartScenario()
            } else if (!state.isSmartLoaded && !dumbEngine.isRunning.value) {
                dumbEngine.startDumbScenario()
            }
        }
    }

    private fun pause() {
        serviceScope.launch {
            when {
                dumbEngine.isRunning.value -> dumbEngine.stopDumbScenario()
                smartProcessingRepository.isRunning() -> smartProcessingRepository.stopDetection()
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

            if (state != UserBillingState.AD_REQUESTED) startSmartScenario()
            paywallResultJob?.cancel()
            paywallResultJob = null
        }.launchIn(serviceScope)
    }

    private fun startSmartScenario() {
        serviceScope.launch {
            smartProcessingRepository.startDetection(
                context = context,
                autoStopDuration = revenueRepository.consumeTrial(),
                liveDebugging = debuggingRepository.isDebugViewEnabled(),
                generateReport = debuggingRepository.isDebugReportEnabled(),
            )
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
    ).also { mainMenu ->
        smartProcessingRepository.setProjectionErrorHandler { mainMenu.onMediaProjectionLost() }
    }

    private fun createDumbMainMenu(scenario: DumbScenario): DumbMainMenu = DumbMainMenu(
        dumbScenarioId = scenario.id,
        onStopClicked = ::stopScenario,
        onScenarioSwitchClicked = ::showScenarioSwitcher,
        canSwitchScenario = canSwitchScenario,
    )

    private fun showScenarioSwitcher() {
        val currentState = state
        val items = runtimeScenarioTargets.value
            .map { target ->
                RuntimeScenarioListItem(
                    target = target,
                    isCurrent = target.databaseId == currentState.scenarioId &&
                            target.isSmart == currentState.isSmartLoaded,
                )
            }
            .sortedWith(compareByDescending<RuntimeScenarioListItem> { it.isCurrent }
                .thenBy { !it.target.isSmart }
                .thenBy { it.target.name.lowercase() })

        showRuntimeScenarioSwitcher(context, items, ::switchScenario)
    }

    private fun switchScenario(target: RuntimeScenarioTarget) {
        if (scenarioSwitchJob?.isActive == true) return
        if (target.databaseId == state.scenarioId && target.isSmart == state.isSmartLoaded) return

        scenarioSwitchJob = serviceScope.launch {
            isScenarioSwitching.value = true
            try {
                val switched = when (target) {
                    is RuntimeScenarioTarget.Smart -> switchToSmartScenario(target.scenario)
                    is RuntimeScenarioTarget.Dumb -> switchToDumbScenario(target.scenario)
                }

                if (!switched && state.isStarted) {
                    Toast.makeText(
                        context,
                        com.buzbuz.smartautoclicker.R.string.runtime_switcher_error,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to switch runtime scenario to ${target.databaseId}", exception)
                if (state.isStarted) {
                    Toast.makeText(
                        context,
                        com.buzbuz.smartautoclicker.R.string.runtime_switcher_error,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                isScenarioSwitching.value = false
                scenarioSwitchJob = null
            }
        }
    }

    private suspend fun switchToDumbScenario(scenario: DumbScenario): Boolean {
        val wasRunning = if (state.isSmartLoaded) {
            stopSmartDetectionForSwitch() ?: return false
        } else {
            dumbEngine.isRunning.value.also { running -> if (running) dumbEngine.stopDumbScenario() }
        }

        dumbEngine.release()
        dumbEngine.init(scenario)
        if (state.hasMediaProjection) {
            smartProcessingRepository.setProjectionErrorHandler(::stopScenario)
        }
        state = state.copy(
            isSmartLoaded = false,
            scenarioId = scenario.id.databaseId,
        )
        replaceRootMenu(createDumbMainMenu(scenario))
        updateScenarioMetadata(scenario.id.databaseId, isSmart = false, scenario.name)

        if (wasRunning) dumbEngine.startDumbScenario()
        return true
    }

    private suspend fun switchToSmartScenario(scenario: Scenario): Boolean {
        if (!state.hasMediaProjection) {
            Toast.makeText(
                context,
                com.buzbuz.smartautoclicker.R.string.runtime_switcher_projection_required,
                Toast.LENGTH_LONG,
            ).show()
            state = LocalServiceState()
            stopScenarioInternal(isScenarioHandoff = true)
            context.startActivity(
                QSTileLauncherActivity.getStartIntent(context, scenario.id.databaseId, isSmartScenario = true)
            )
            return true
        }

        val wasSmartLoaded = state.isSmartLoaded
        val wasRunning = if (wasSmartLoaded) {
            stopSmartDetectionForSwitch() ?: return false
        } else {
            dumbEngine.isRunning.value.also { running -> if (running) dumbEngine.stopDumbScenario() }
        }

        dumbEngine.release()
        smartProcessingRepository.setScenarioId(scenario.id, markAsUsed = true)
        state = state.copy(
            isSmartLoaded = true,
            scenarioId = scenario.id.databaseId,
        )

        if (!wasSmartLoaded || overlayManager.getBackStackTop() !is MainMenu) {
            replaceRootMenu(createSmartMainMenu())
        }
        updateScenarioMetadata(scenario.id.databaseId, isSmart = true, scenario.name)
        if (wasRunning) startSmartScenario()
        return true
    }

    private suspend fun stopSmartDetectionForSwitch(): Boolean? {
        val stateBeforeSwitch = withTimeoutOrNull(SCENARIO_SWITCH_TIMEOUT_MS) {
            smartProcessingRepository.detectionState.first { detectionState ->
                detectionState == DetectionState.RECORDING || detectionState == DetectionState.DETECTING ||
                        detectionState.isError()
            }
        } ?: return null
        if (stateBeforeSwitch.isError()) return null

        val wasRunning = stateBeforeSwitch == DetectionState.DETECTING
        if (!wasRunning) return false

        smartProcessingRepository.stopDetection()
        val readyState = withTimeoutOrNull(SCENARIO_SWITCH_TIMEOUT_MS) {
            smartProcessingRepository.detectionState.first { detectionState ->
                detectionState == DetectionState.RECORDING || detectionState.isError()
            }
        }
        return if (readyState == DetectionState.RECORDING) true else null
    }

    private suspend fun replaceRootMenu(newMenu: com.buzbuz.smartautoclicker.core.common.overlays.base.Overlay) {
        overlayManager.closeAll(context)
        val rootClosed = withTimeoutOrNull(SCENARIO_SWITCH_TIMEOUT_MS) {
            overlayManager.backStackTopFlow.first { overlay -> overlay == null }
            true
        } == true
        check(rootClosed) { "Timed out while replacing the runtime scenario menu" }
        overlayManager.navigateTo(context, newMenu)
    }

    private fun updateScenarioMetadata(scenarioId: Long, isSmart: Boolean, scenarioName: String) {
        notificationController.updateScenarioName(context, scenarioName)
        onScenarioChanged(scenarioId, isSmart)
    }

    private suspend fun stopScenarioInternal(isScenarioHandoff: Boolean) {
        startJob?.join()
        startJob = null

        dumbEngine.release()
        overlayManager.closeAll(context)
        withTimeoutOrNull(SCENARIO_SWITCH_TIMEOUT_MS) {
            overlayManager.backStackTopFlow.first { overlay -> overlay == null }
        }
        smartProcessingRepository.stopScreenRecord()

        onStop(isScenarioHandoff)
        notificationController.destroyNotification()
    }
}

private data class LocalServiceState(
    val isStarted: Boolean = false,
    val isSmartLoaded: Boolean = false,
    val scenarioId: Long? = null,
    val hasMediaProjection: Boolean = false,
)

private fun DetectionState.isError(): Boolean = when (this) {
    DetectionState.ERROR_NO_NATIVE_LIB,
    DetectionState.ERROR_OCR_MODEL_NOT_FOUND,
    DetectionState.ERROR_SCREEN_IMAGE_CAPTURE_FAILED -> true
    else -> false
}

private const val SCENARIO_SWITCH_TIMEOUT_MS = 10_000L
private const val TAG = "LocalService"
