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
package com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.widget.Toast
import android.widget.ImageView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.buzbuz.smartautoclicker.core.base.extensions.setLeftCompoundDrawable
import com.buzbuz.smartautoclicker.core.base.isStopScenarioKey
import com.buzbuz.smartautoclicker.core.common.navigation.TutorialNavigator
import com.buzbuz.smartautoclicker.core.common.navigation.getTutorialNavigator
import com.buzbuz.smartautoclicker.core.common.overlays.base.viewModels
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.Companion.showAsOverlay
import com.buzbuz.smartautoclicker.core.common.overlays.menu.OverlayMenu
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.Tip
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredOverlayType
import com.buzbuz.smartautoclicker.core.processing.domain.model.DebugExecutionState
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionFailureSnapshot
import com.buzbuz.smartautoclicker.core.ui.utils.AnimatedStatesImageButtonController
import com.buzbuz.smartautoclicker.core.ui.utils.getDynamicColorsContext
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.OverlayMenuBinding
import com.buzbuz.smartautoclicker.feature.smart.config.di.ScenarioConfigViewModelsEntryPoint
import com.buzbuz.smartautoclicker.feature.smart.config.ui.common.starters.newRestartMediaProjectionStarterOverlay
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.AlphabetActivity
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.required.RequiredAlphabetFragment
import com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu.debugging.LiveDebuggingActionsAdapter
import com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu.debugging.LiveDebuggingUiState
import com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu.debugging.LiveDebuggingViewModel
import com.buzbuz.smartautoclicker.feature.smart.config.ui.scenario.ScenarioDialog

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * [OverlayMenu] implementation for displaying the main menu overlay.
 *
 * This is the menu displayed once the service is started via the [com.buzbuz.smartautoclicker.scenarios.ScenarioActivity]
 * once the user has selected a scenario to be used. It allows the user to start the detection on the currently loaded
 * scenario, as well as editing the attached list of events.
 *
 * There is no overlay views attached to this overlay menu, meaning that the user will always be able to clicks on the
 * Activities displayed below it.
 */
class MainMenu(
    private val onStopClicked: () -> Unit,
    private val onScenarioSwitchClicked: () -> Unit,
    private val canSwitchScenario: StateFlow<Boolean>,
    autoCollapseDelayMs: Long?,
) : OverlayMenu(autoCollapseDelayMs = autoCollapseDelayMs) {

    override fun tutorialMonitoringTag(): String = MonitoredOverlayType.MAIN_MENU.name

    /** The view model for this menu. */
    private val viewModel: MainMenuModel by viewModels(
        entryPoint = ScenarioConfigViewModelsEntryPoint::class.java,
        creator = { mainMenuViewModel() },
    )

    /** The view model for the live debugging. */
    private val debuggingViewModel: LiveDebuggingViewModel by viewModels(
        entryPoint = ScenarioConfigViewModelsEntryPoint::class.java,
        creator = { liveDebuggingViewModel() },
    )

    private val tutorialNavigator: TutorialNavigator by lazy {
        context.getTutorialNavigator()
    }

    private var isHiddenForPaywall: Boolean = false

    /** View binding for the content of the overlay. */
    private lateinit var viewBinding: OverlayMenuBinding
    /** Controls the animations of the play/pause button. */
    private lateinit var playPauseButtonController: AnimatedStatesImageButtonController
    /** Adapter upon actions being executed while in live debugging. */
    private val debugLiveActionsAdapter: LiveDebuggingActionsAdapter = LiveDebuggingActionsAdapter()
    private var lastLiveDebugUiState: LiveDebuggingUiState? = null
    private var debugExecutionState: DebugExecutionState = DebugExecutionState.Running
    private var lastActionFailure: ActionFailureSnapshot? = null
    private var detectionIsRunning: Boolean = false
    private var liveDebuggingIsEnabled: Boolean = false

    private lateinit var menuBackground: CardView
    private var menuBackgroundColor: Int = Color.TRANSPARENT
    private var menuBackgroundElevation: Float = 0f
    private var quickControlsAreExpanded: Boolean = false
    private var quickControlsAreDockedLeft: Boolean = true
    private var quickControlsAutoHideJob: Job? = null
    private var lastQuickPauseClickAtMs: Long = 0L

    /** The coroutine job for the observable used in debug mode. Null when not in debug mode. */
    private var debugObservableJob: Job? = null

    /**
     * Tells if this service has handled onKeyEvent with ACTION_DOWN for a key in order to return
     * the correct value when ACTION_UP is received.
     */
    private var keyDownHandled: Boolean = false

    override fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup {
        playPauseButtonController = AnimatedStatesImageButtonController(
            context = context,
            state1StaticRes = R.drawable.ic_play_arrow,
            state2StaticRes = R.drawable.ic_pause,
            state1to2AnimationRes = R.drawable.anim_play_pause,
            state2to1AnimationRes = R.drawable.anim_pause_play,
        )
        viewBinding = OverlayMenuBinding.inflate(layoutInflater)
        playPauseButtonController.attachView(viewBinding.btnPlay)
        menuBackground = viewBinding.root.findViewById(R.id.menu_background)
        menuBackgroundColor = menuBackground.cardBackgroundColor.defaultColor
        menuBackgroundElevation = menuBackground.cardElevation

        return viewBinding.root
    }

    override fun onCreate() {
        super.onCreate()

        // Ensure the debug view state is correct
        viewBinding.layoutDebug.visibility = View.GONE
        viewBinding.actionList.adapter = debugLiveActionsAdapter
        viewBinding.actionList.itemAnimator = null
        viewBinding.btnDebugPauseResume.setOnClickListener { debuggingViewModel.togglePauseAtNextEvent() }
        viewBinding.btnDebugStep.setOnClickListener {
            if (debugExecutionState is DebugExecutionState.Paused) debuggingViewModel.stepToNextEvent()
            else showLastActionFailure()
        }
        viewBinding.btnQuickPauseResume.setOnClickListener { button ->
            val clickAtMs = SystemClock.elapsedRealtime()
            if (clickAtMs - lastQuickPauseClickAtMs < QUICK_PAUSE_CLICK_GUARD_MS) {
                return@setOnClickListener
            }
            lastQuickPauseClickAtMs = clickAtMs

            val stateBeforeClick = debugExecutionState
            button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            debuggingViewModel.togglePauseAtNextEvent()
            Toast.makeText(
                context,
                when (stateBeforeClick) {
                    DebugExecutionState.Running -> R.string.debug_pause_request_accepted
                    DebugExecutionState.WaitingForEvent -> R.string.debug_pause_request_cancelled
                    is DebugExecutionState.Paused -> R.string.debug_execution_resumed
                },
                Toast.LENGTH_SHORT,
            ).show()
            scheduleQuickControlsAutoHide()
        }
        viewBinding.btnQuickStep.setOnClickListener {
            if (debugExecutionState is DebugExecutionState.Paused) debuggingViewModel.stepToNextEvent()
            else showLastActionFailure()
            scheduleQuickControlsAutoHide()
        }
        viewBinding.btnQuickSwitchScenario.setOnClickListener { button ->
            button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setQuickControlsExpanded(false)
            onScenarioSwitchClicked()
        }
        viewBinding.btnQuickStop.setOnClickListener { button ->
            button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setQuickControlsExpanded(false)
            onStopClicked()
        }
        viewBinding.btnQuickOpenMenu.setOnClickListener { openFullMenuFromQuickControls() }

        setOverlayViewVisibility(false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch { viewModel.paywallIsVisible.collect(::updateVisibilityForPaywall) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.isStartButtonEnabled.collect(::updatePlayPauseButtonEnabledState) }
                launch { viewModel.isMediaProjectionStarted.collect(::updateProjectionErrorBadge) }
                launch { viewModel.detectionState.collect(::updateDetectionState) }
                launch { viewModel.nativeLibError.collect(::showNativeLibErrorDialogIfNeeded) }
                launch { viewModel.screenCaptureError.collect(::showScreenCaptureErrorDialogIfNeeded) }
                launch { canSwitchScenario.collect(::updateScenarioSwitchButtonEnabledState) }
                launch { debuggingViewModel.isDebugging.collect(::updateDebugOverlayViewVisibility) }
                launch { debuggingViewModel.debugExecutionState.collect(::updateDebugExecutionState) }
                launch { debuggingViewModel.lastActionFailure.collect(::updateLastActionFailure) }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        viewModel.monitorViews(
            playMenuButton = viewBinding.btnPlay,
            configMenuButton = viewBinding.btnClickList,
        )

        // Start loading advertisement if needed
        viewModel.loadAdIfNeeded(context)
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopViewMonitoring()
        viewBinding.btnPlay.tag = null
    }

    override fun onDestroy() {
        quickControlsAutoHideJob?.cancel()
        super.onDestroy()
        playPauseButtonController.detachView()
    }

    override fun onCollapsedLauncherClicked(): Boolean {
        if (!shouldUseCompactPauseMenu(detectionIsRunning, liveDebuggingIsEnabled)) return false

        setQuickControlsExpanded(!quickControlsAreExpanded)
        return true
    }

    override fun onCollapsedLauncherDockChanged(isOnLeftEdge: Boolean) {
        if (!quickControlsAreExpanded || quickControlsAreDockedLeft == isOnLeftEdge) return

        quickControlsAreDockedLeft = isOnLeftEdge
        updateQuickControlsDock()
    }

    override fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (!keyEvent.isStopScenarioKey()) return false

        when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> {
                if (viewModel.stopDetection()) {
                    keyDownHandled = true
                    return true
                }
            }

            KeyEvent.ACTION_UP -> {
                if (keyDownHandled) {
                    keyDownHandled = false
                    return true
                }
            }
        }

        return false
    }

    override fun onMenuItemClicked(viewId: Int) {
        when (viewId) {
            R.id.btn_play -> onPlayPauseClicked()
            R.id.btn_switch_scenario -> onScenarioSwitchClicked()
            R.id.btn_click_list -> onConfigureClicked()
            R.id.btn_stop -> onStopClicked()
        }
    }

    override fun getWindowMaximumSize(backgroundView: ViewGroup): Size {
        val bgSize = super.getWindowMaximumSize(backgroundView)
        val debugPanelHeight = context.resources.getDimensionPixelSize(R.dimen.overlay_debug_panel_height)
        val quickControlsHeight = context.resources.getDimensionPixelSize(R.dimen.overlay_quick_controls_height)
        return Size(
            bgSize.width + context.resources.getDimensionPixelSize(R.dimen.overlay_debug_panel_width),
            maxOf(bgSize.height, debugPanelHeight, quickControlsHeight),
        )
    }

    fun onMediaProjectionLost() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return

        overlayManager.navigateUpToRoot(context)
        viewModel.cancelScenarioChanges()
    }

    private fun onConfigureClicked() {
        if (viewModel.shouldRestartMediaProjection()) {
            showRestartMediaProjectionScreen()
            return
        }

        viewModel.startScenarioEdition {
            showScenarioConfigDialog()
        }
    }

    private fun onPlayPauseClicked() {
        if (viewModel.shouldDownloadModels()) {
            context.startActivity(AlphabetActivity.getStartIntent(context, RequiredAlphabetFragment.FRAGMENT_TAG))
            return
        }

        if (viewModel.shouldRestartMediaProjection()) {
            showRestartMediaProjectionScreen()
            return
        }

        if (viewModel.shouldShowStopVolumeDownTutorialDialog()) {
            showStopVolumeDownTutorialDialog()
            return
        }

        viewModel.toggleDetection(context)
    }

    /** Refresh the play menu item according to the scenario state. */
    private fun updatePlayPauseButtonEnabledState(canStartDetection: Boolean) =
        setMenuItemViewEnabled(viewBinding.btnPlay, canStartDetection)

    private fun updateScenarioSwitchButtonEnabledState(canSwitchScenario: Boolean) {
        setMenuItemViewEnabled(viewBinding.btnSwitchScenario, canSwitchScenario)
        setMenuItemViewEnabled(viewBinding.btnQuickSwitchScenario, canSwitchScenario)
    }

    /** Refresh the menu layout according to the detection state. */
    private fun updateDetectionState(newState: UiState) {
        detectionIsRunning = newState == UiState.Detecting
        updateCompactPauseMenuAvailability()

        val currentState = viewBinding.btnPlay.tag
        if (currentState == newState) return

        viewBinding.btnPlay.tag = newState
        when (newState) {
            UiState.Idle -> {
                if (currentState == null) {
                    viewBinding.btnStop.isVisible = true
                    viewBinding.btnClickList.isVisible = true
                    playPauseButtonController.toState1(false)
                } else {
                    animateLayoutChanges {
                        setMenuItemVisibility(viewBinding.btnStop, true)
                        setMenuItemVisibility(viewBinding.btnClickList, true)
                        playPauseButtonController.toState1(true)
                    }
                }
            }

            UiState.Detecting -> {
                if (currentState == null) {
                    viewBinding.btnStop.isVisible = false
                    viewBinding.btnClickList.isVisible = false
                    playPauseButtonController.toState2(false)
                } else {
                    animateLayoutChanges {
                        setMenuItemVisibility(viewBinding.btnStop, false)
                        setMenuItemVisibility(viewBinding.btnClickList, false)
                        playPauseButtonController.toState2(true)
                    }
                }
            }
        }
    }

    private fun updateVisibilityForPaywall(isHidden: Boolean) {
        if (isHidden) {
            isHiddenForPaywall = true
            hide()
        } else if (isHiddenForPaywall) {
            isHiddenForPaywall = false
            show()
        }
    }

    private fun updateProjectionErrorBadge(isProjectionStarted: Boolean) {
        viewBinding.errorBadge.visibility = if (isProjectionStarted) View.GONE else View.VISIBLE
    }

    /**
     * Change the debug state of this UI.
     * @param isVisible true when the debug view should be shown, false to hide it.
     */
    private fun updateDebugOverlayViewVisibility(isVisible: Boolean) {
        liveDebuggingIsEnabled = isVisible
        updateCompactPauseMenuAvailability()

        if (isVisible && debugObservableJob == null) {
            debugObservableJob = observeDebugValues()

        } else if (!isVisible && debugObservableJob != null) {
            debugObservableJob?.cancel()
            debugObservableJob = null

            updateLiveDebugUiState(null)
        }

        setMenuItemVisibility(viewBinding.layoutDebug, isVisible)
    }

    /**
     * Observe the values for the debug and update the debug views.
     * @return the coroutine job for the observable. Can be cancelled to stop the observation.
     */
    private fun observeDebugValues() = lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                debuggingViewModel.debugLastPositive.collect(::updateLiveDebugUiState)
            }
        }
    }

    private fun updateLiveDebugUiState(uiState: LiveDebuggingUiState?) {
        lastLiveDebugUiState = uiState
        renderDebugState()
    }

    private fun updateDebugExecutionState(state: DebugExecutionState) {
        debugExecutionState = state
        renderDebugState()
    }

    private fun updateLastActionFailure(failure: ActionFailureSnapshot?) {
        lastActionFailure = failure
        renderDebugState()
    }

    private fun renderDebugState() {
        val pausedState = debugExecutionState as? DebugExecutionState.Paused
        val uiState = if (debugExecutionState is DebugExecutionState.Running) lastLiveDebugUiState else null

        viewBinding.apply {
            debugEventName.apply {
                text = when (debugExecutionState) {
                    DebugExecutionState.Running -> uiState?.eventName
                    DebugExecutionState.WaitingForEvent -> context.getString(R.string.debug_waiting_for_event)
                    is DebugExecutionState.Paused -> pausedState?.eventName
                }
                setLeftCompoundDrawable(
                    when (debugExecutionState) {
                        DebugExecutionState.Running -> uiState?.eventIcon
                        DebugExecutionState.WaitingForEvent -> R.drawable.ic_debug_pause
                        is DebugExecutionState.Paused -> R.drawable.ic_debug_pause
                    }
                )
            }

            debugEventFulfilledCount.apply {
                text = when (debugExecutionState) {
                    DebugExecutionState.Running -> uiState?.eventFulfilledCount
                    DebugExecutionState.WaitingForEvent -> context.getString(R.string.debug_breakpoint_armed)
                    is DebugExecutionState.Paused -> context.getString(R.string.debug_paused)
                }
                setLeftCompoundDrawable(if (text.isNullOrEmpty()) null else R.drawable.ic_confirm)
            }

            debugEventConditionComputeTime.apply {
                text = when (debugExecutionState) {
                    DebugExecutionState.Running -> uiState?.eventDuration
                    DebugExecutionState.WaitingForEvent -> null
                    is DebugExecutionState.Paused -> pausedState?.conditionDurationMs?.let { "${it}ms" }
                }
                setLeftCompoundDrawable(if (text.isNullOrEmpty()) null else R.drawable.ic_duration)
            }

            debugLiveActionsAdapter.submitList(uiState?.actions)

            val isPaused = debugExecutionState is DebugExecutionState.Paused
            val pauseControlIsActive = shouldShowResumeDebugIcon(debugExecutionState)
            btnDebugPauseResume.apply {
                setImageResource(if (pauseControlIsActive) R.drawable.ic_debug_play else R.drawable.ic_debug_pause)
                imageTintList = ContextCompat.getColorStateList(
                    context,
                    if (pauseControlIsActive) R.color.overlayQuickActionPositive
                    else R.color.overlayQuickActionPause,
                )
                contentDescription = context.getString(
                    when (debugExecutionState) {
                        DebugExecutionState.Running -> R.string.content_desc_debug_pause_next_event
                        DebugExecutionState.WaitingForEvent -> R.string.content_desc_debug_cancel_pause
                        is DebugExecutionState.Paused -> R.string.content_desc_debug_resume
                    }
                )
                isActivated = pauseControlIsActive
            }
            btnDebugStep.apply {
                val canShowFailure = lastActionFailure != null
                isEnabled = isPaused || canShowFailure
                alpha = if (isEnabled) 1f else 0.35f
                setImageResource(if (!isPaused && canShowFailure) R.drawable.ic_failure_snapshot else R.drawable.ic_debug_step)
                imageTintList = ContextCompat.getColorStateList(
                    context,
                    if (!isPaused && canShowFailure) R.color.overlayQuickActionWarning
                    else R.color.overlayQuickActionPositive,
                )
                contentDescription = context.getString(
                    if (!isPaused && canShowFailure) R.string.content_desc_failure_snapshot else R.string.content_desc_debug_step
                )
            }
            btnQuickPauseResume.apply {
                setImageResource(if (pauseControlIsActive) R.drawable.ic_debug_play else R.drawable.ic_debug_pause)
                imageTintList = btnDebugPauseResume.imageTintList
                contentDescription = btnDebugPauseResume.contentDescription
                isActivated = pauseControlIsActive
            }
            btnQuickStep.apply {
                val canShowFailure = lastActionFailure != null
                isEnabled = isPaused || canShowFailure
                alpha = if (isEnabled) 1f else 0.35f
                setImageResource(if (!isPaused && canShowFailure) R.drawable.ic_failure_snapshot else R.drawable.ic_debug_step)
                imageTintList = btnDebugStep.imageTintList
                contentDescription = btnDebugStep.contentDescription
            }
        }
    }

    private fun showLastActionFailure() {
        val failure = lastActionFailure ?: return
        val screenshot = failure.screenshotPath?.let(BitmapFactory::decodeFile)
        val imageView = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(24, 8, 24, 8)
            setImageBitmap(screenshot)
        }
        MaterialAlertDialogBuilder(context.getDynamicColorsContext(R.style.AppTheme))
            .setTitle(R.string.failure_snapshot_title)
            .setMessage(
                context.getString(
                    R.string.failure_snapshot_message,
                    failure.eventName,
                    failure.actionName,
                    failure.result.toString(),
                )
            )
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .showAsOverlay()
    }

    private fun updateCompactPauseMenuAvailability() {
        if (!shouldUseCompactPauseMenu(detectionIsRunning, liveDebuggingIsEnabled)) {
            setQuickControlsExpanded(false)
        }
    }

    private fun setQuickControlsExpanded(expanded: Boolean) {
        val canExpand = shouldUseCompactPauseMenu(detectionIsRunning, liveDebuggingIsEnabled)
                && isMenuCurrentlyCollapsed()
        val targetExpanded = expanded && canExpand

        if (quickControlsAreExpanded == targetExpanded) {
            if (targetExpanded) scheduleQuickControlsAutoHide()
            return
        }

        quickControlsAutoHideJob?.cancel()
        quickControlsAutoHideJob = null
        quickControlsAreExpanded = targetExpanded

        if (targetExpanded) {
            quickControlsAreDockedLeft = isMenuOnLeftHalf()
            setCollapsedLauncherIconVisible(true)
            updateQuickControlsDock()
            menuBackground.setCardBackgroundColor(Color.TRANSPARENT)
            menuBackground.cardElevation = 0f
            viewBinding.layoutQuickControls.alpha = 0f
            setQuickControlsClickable(false)
            setMenuItemVisibility(viewBinding.layoutQuickControls, true)
            viewBinding.root.post {
                dockMenuToHorizontalEdge(quickControlsAreDockedLeft)
                viewBinding.layoutQuickControls.postOnAnimation {
                    if (!quickControlsAreExpanded) return@postOnAnimation

                    setQuickControlsClickable(true)
                    viewBinding.layoutQuickControls.animate()
                        .alpha(1f)
                        .setDuration(QUICK_CONTROLS_REVEAL_DURATION_MS)
                        .start()
                }
            }
            scheduleQuickControlsAutoHide()
        } else {
            viewBinding.layoutQuickControls.animate().cancel()
            setQuickControlsClickable(false)
            setMenuItemVisibility(viewBinding.layoutQuickControls, false)
            viewBinding.layoutQuickControls.alpha = 1f
            menuBackground.setCardBackgroundColor(menuBackgroundColor)
            menuBackground.cardElevation = menuBackgroundElevation
            if (isMenuCurrentlyCollapsed()) {
                setCollapsedLauncherIconVisible(false)
                viewBinding.root.post {
                    concealCollapsedLauncherAtHorizontalEdge(quickControlsAreDockedLeft)
                }
            }
        }
    }

    private fun updateQuickControlsDock() {
        ConstraintSet().apply {
            clone(viewBinding.menuContent)
            clear(viewBinding.layoutQuickControls.id, ConstraintSet.START)
            clear(viewBinding.layoutQuickControls.id, ConstraintSet.END)
            clear(viewBinding.menuItems.id, ConstraintSet.START)
            clear(viewBinding.menuItems.id, ConstraintSet.END)

            if (quickControlsAreDockedLeft) {
                connect(viewBinding.menuItems.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                connect(
                    viewBinding.layoutQuickControls.id,
                    ConstraintSet.START,
                    viewBinding.menuItems.id,
                    ConstraintSet.END,
                )
                connect(
                    viewBinding.layoutQuickControls.id,
                    ConstraintSet.END,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.END,
                )
            } else {
                connect(
                    viewBinding.layoutQuickControls.id,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                )
                connect(
                    viewBinding.layoutQuickControls.id,
                    ConstraintSet.END,
                    viewBinding.menuItems.id,
                    ConstraintSet.START,
                )
                connect(
                    viewBinding.menuItems.id,
                    ConstraintSet.START,
                    viewBinding.layoutQuickControls.id,
                    ConstraintSet.END,
                )
                connect(
                    viewBinding.menuItems.id,
                    ConstraintSet.END,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.END,
                )
            }
            applyTo(viewBinding.menuContent)
        }

        val mirrorScale = if (quickControlsAreDockedLeft) 1f else -1f
        viewBinding.layoutQuickControls.scaleX = mirrorScale
        viewBinding.btnQuickPauseResume.scaleX = mirrorScale
        viewBinding.btnQuickStep.scaleX = mirrorScale
        viewBinding.btnQuickSwitchScenario.scaleX = mirrorScale
        viewBinding.btnQuickStop.scaleX = mirrorScale
        viewBinding.btnQuickOpenMenu.scaleX = mirrorScale
    }

    private fun setQuickControlsClickable(clickable: Boolean) {
        viewBinding.btnQuickPauseResume.isClickable = clickable
        viewBinding.btnQuickStep.isClickable = clickable
        viewBinding.btnQuickSwitchScenario.isClickable = clickable
        viewBinding.btnQuickStop.isClickable = clickable
        viewBinding.btnQuickOpenMenu.isClickable = clickable
    }

    private fun scheduleQuickControlsAutoHide() {
        if (!quickControlsAreExpanded) return

        quickControlsAutoHideJob?.cancel()
        quickControlsAutoHideJob = lifecycleScope.launch {
            delay(QUICK_CONTROLS_AUTO_HIDE_DELAY_MS)
            setQuickControlsExpanded(false)
        }
    }

    private fun openFullMenuFromQuickControls() {
        val wasDockedLeft = quickControlsAreDockedLeft
        setQuickControlsExpanded(false)
        viewBinding.root.post {
            expandCollapsedMenu()
            viewBinding.root.postDelayed(
                { dockMenuToHorizontalEdge(wasDockedLeft) },
                MENU_EXPANSION_DOCK_DELAY_MS,
            )
        }
    }

    private fun showScenarioConfigDialog() =
        overlayManager.navigateTo(
            context = context,
            newOverlay = ScenarioDialog(
                onConfigDiscarded = viewModel::cancelScenarioChanges,
                onConfigSaved = { viewModel.saveScenarioChanges { success -> if (!success) showScenarioSaveErrorDialog() } },
            ),
            hideCurrent = true,
        )

    private fun showScenarioSaveErrorDialog() {
        MaterialAlertDialogBuilder(context.getDynamicColorsContext(R.style.AppTheme))
            .setTitle(R.string.dialog_overlay_title_warning)
            .setMessage(R.string.error_dialog_message_scenario_saving)
            .setPositiveButton(R.string.generic_modify) { _: DialogInterface, _: Int ->
                showScenarioConfigDialog()
            }
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface, _: Int ->
                viewModel.cancelScenarioChanges()
            }
            .create()
            .showAsOverlay()
    }

    private fun showStopVolumeDownTutorialDialog() {
        tutorialNavigator.showTipDialog(context, Tip.STOP_WITH_VOLUME_DOWN) {
            viewModel.toggleDetection(context)
        }
    }

    private fun showNativeLibErrorDialogIfNeeded(haveError: Boolean) {
        if (!haveError) return

        MaterialAlertDialogBuilder(context.getDynamicColorsContext(R.style.AppTheme))
            .setTitle(R.string.dialog_overlay_title_warning)
            .setMessage(R.string.error_dialog_message_error_native_lib)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onStopClicked()
            }
            .create()
            .showAsOverlay()
    }

    private fun showScreenCaptureErrorDialogIfNeeded(haveError: Boolean) {
        if (!haveError) return

        MaterialAlertDialogBuilder(context.getDynamicColorsContext(R.style.AppTheme))
            .setTitle(R.string.dialog_overlay_title_warning)
            .setMessage(R.string.error_dialog_message_screen_capture_unsupported)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onStopClicked()
            }
            .create()
            .showAsOverlay()
    }

    private fun showRestartMediaProjectionScreen() {
        overlayManager.navigateTo(
            context = context,
            newOverlay = newRestartMediaProjectionStarterOverlay(context),
            hideCurrent = true,
        )
    }

}

@Suppress("UNUSED_PARAMETER")
internal fun shouldShowDebugPanel(
    isDebugging: Boolean,
    isMenuCollapsed: Boolean,
): Boolean = isDebugging

internal fun shouldUseCompactPauseMenu(
    isDetectionRunning: Boolean,
    isLiveDebuggingEnabled: Boolean,
): Boolean = isDetectionRunning && !isLiveDebuggingEnabled

internal fun shouldShowResumeDebugIcon(state: DebugExecutionState): Boolean =
    state != DebugExecutionState.Running

private const val QUICK_CONTROLS_AUTO_HIDE_DELAY_MS = 10_000L
private const val QUICK_CONTROLS_REVEAL_DURATION_MS = 120L
private const val QUICK_PAUSE_CLICK_GUARD_MS = 450L
private const val MENU_EXPANSION_DOCK_DELAY_MS = 400L
