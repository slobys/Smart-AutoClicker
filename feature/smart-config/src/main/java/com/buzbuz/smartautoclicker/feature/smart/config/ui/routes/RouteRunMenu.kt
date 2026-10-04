/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.buzbuz.smartautoclicker.core.common.overlays.menu.OverlayMenu
import com.buzbuz.smartautoclicker.core.processing.routes.*
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.OverlayRouteMenuBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Separate from the user's main floating menu: hiding this overlay always cancels its work. */
class RouteRunMenu(
    private val pausable: Boolean,
    private val showTrace: Boolean = false,
    private val operation: suspend (RouteRunControl, (RouteProgress) -> Unit) -> Unit,
) : OverlayMenu(theme = R.style.ScenarioConfigTheme) {
    private lateinit var binding: OverlayRouteMenuBinding
    private val control = RouteRunControl()
    private val progress = MutableStateFlow(RouteProgress(RouteMessage.PREPARING))
    private var work: Job? = null
    private var heartbeat: Job? = null
    private var finished = false

    override fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup {
        binding = OverlayRouteMenuBinding.inflate(layoutInflater)
        binding.routePause.visibility = if (pausable) View.VISIBLE else View.GONE
        // Reserve the trace before the floating window is measured. Changing its height later
        // would clip the safety buttons in the shared fixed-size WindowManager layout.
        binding.routeTrace.visibility = if (showTrace) View.VISIBLE else View.GONE
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // The shared menu position may come from the much narrower main launcher.
        // Wait until our own window is measured before keeping every safety control on screen.
        dockMenuToHorizontalEdge(isMenuOnLeftHalf())
    }

    override fun onOrientationChanged() {
        super.onOrientationChanged()
        dockMenuToHorizontalEdge(isMenuOnLeftHalf())
    }

    override fun onStart() {
        super.onStart()
        // Update the blocked rectangle even when the user drags the toolbar.
        binding.root.viewTreeObserver.addOnPreDrawListener {
            val location = IntArray(2)
            binding.root.getLocationOnScreen(location)
            control.blockedArea = RouteArea(location[0], location[1], location[0] + binding.root.width, location[1] + binding.root.height)
            true
        }
        lifecycleScope.launch { progress.collect(::showProgress) }
        work = lifecycleScope.launch {
            try {
                operation(control) { progress.value = it }
                if (progress.value.message !in setOf(RouteMessage.COMPLETE, RouteMessage.SAVED_DRAFT, RouteMessage.DONE,
                        RouteMessage.LOCALIZATION_PASSED, RouteMessage.LOCALIZATION_WEAK))
                    progress.value = progress.value.copy(message = RouteMessage.DONE)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RouteFailure) { progress.value = progress.value.copy(message = failure.reason,
                position = null, diagnostics = failure.diagnostics) }
            catch (failure: Exception) {
                android.util.Log.e("RouteRunMenu", "Route operation failed", failure)
                progress.value = progress.value.copy(message = RouteMessage.FAILED, position = null)
            } finally {
                finished = true
                binding.routePause.isEnabled = false
                binding.routeFinish.setText(R.string.route_close)
                binding.routeFinish.isEnabled = true
            }
        }
        heartbeat = lifecycleScope.launch {
            // MediaProjection may emit nothing for a completely static game. A tiny visible
            // heartbeat requests a fresh composited frame; never count a cached screenshot twice.
            var bright = false
            while (!finished) {
                if (!control.paused) {
                    bright = !bright
                    binding.routeHeartbeat.alpha = if (bright) 1f else .25f
                }
                delay(400)
            }
            binding.routeHeartbeat.alpha = .25f
        }
        // Safety controls must not wait for the generic floating-menu click debounce.
        binding.routePause.setOnClickListener {
            if (!finished) {
                control.paused = !control.paused
                progress.value = if (control.paused) progress.value.copy(message = RouteMessage.PAUSED)
                    else progress.value.copy(message = RouteMessage.READING, position = null,
                        expectedPosition = null, allowedDistance = null, diagnostics = null)
                binding.routePause.setText(if (control.paused) R.string.route_resume else R.string.route_pause)
            }
        }
        binding.routeFinish.setOnClickListener {
            if (finished) back() else {
                control.stopped = true
                binding.routeFinish.setText(R.string.route_finishing)
                binding.routeFinish.isEnabled = false
            }
        }
    }

    private fun showProgress(value: RouteProgress) {
        binding.routeStatus.text = value.statusText(context)
        binding.routePause.setText(if (control.paused) R.string.route_resume else R.string.route_pause)
        binding.routeTrace.points = value.trace
        if (finished) binding.routeFinish.isEnabled = true
    }

    override fun back() { control.stopped = true; work?.cancel(); heartbeat?.cancel(); super.back() }
    override fun onStop() { control.stopped = true; work?.cancel(); heartbeat?.cancel(); super.onStop() }
}

internal fun RouteProgress.statusText(context: android.content.Context): String = buildString {
    val currentPosition = position
    append(context.getString(
        if (observations == null) R.string.route_progress else R.string.route_progress_samples,
        context.getString(when {
            returning && message == RouteMessage.APPROACHING_START -> R.string.route_message_approaching_end
            returning && message == RouteMessage.REPLAYING -> R.string.route_message_returning
            returning && message == RouteMessage.COMPLETE -> R.string.route_message_return_complete
            returning && message == RouteMessage.WRONG_START -> R.string.route_message_wrong_end
            else -> message.stringId()
        }),
        currentPosition?.coordinateText() ?: context.getString(if (diagnostics == null)
            R.string.route_no_coordinate else R.string.route_unconfirmed_coordinate), observations ?: count))
    expectedPosition?.takeIf { message !in setOf(RouteMessage.COMPLETE, RouteMessage.DONE) }?.let { expected ->
        val moving = message in setOf(RouteMessage.APPROACHING_START, RouteMessage.REPLAYING,
            RouteMessage.WAITING_MOVEMENT, RouteMessage.ADJUSTING_STEP, RouteMessage.STUCK, RouteMessage.GESTURE_FAILED,
            RouteMessage.PAUSED, RouteMessage.TIMEOUT)
        append("\n").append(context.getString(when {
            message == RouteMessage.APPROACHING_START -> R.string.route_approach_position
            moving -> R.string.route_current_target
            else -> R.string.route_return_position
        }, expected.coordinateText()))
        if (moving && currentPosition != null)
            append("\n").append(context.getString(R.string.route_target_distance, currentPosition.distance(expected)))
        if (currentPosition != null && allowedDistance != null)
            append("\n").append(context.getString(R.string.route_return_distance, currentPosition.distance(expected), allowedDistance))
        append("\n").append(context.getString(when (message) {
            RouteMessage.APPROACHING_START -> R.string.route_approach_help
            RouteMessage.WRONG_START -> if (returning) R.string.route_wrong_end_help else R.string.route_wrong_start_help
            RouteMessage.STUCK -> R.string.route_stuck_help
            RouteMessage.GESTURE_FAILED -> R.string.route_gesture_help
            RouteMessage.PAUSED -> R.string.route_paused_help
            RouteMessage.TIMEOUT -> R.string.route_stuck_help
            RouteMessage.REPLAYING, RouteMessage.WAITING_MOVEMENT, RouteMessage.ADJUSTING_STEP -> R.string.route_movement_help
            else -> R.string.route_return_help
        }))
    }
    screenTarget?.let { append("\n").append(context.getString(R.string.route_screen_target, it.coordinateText())) }
    stepAttempt?.let { append("\n").append(context.getString(R.string.route_step_attempt, it)) }
    confidence?.let { append("\n").append(context.getString(R.string.route_localization_confidence, (it * 100).toInt())) }
    if (message == RouteMessage.READING || message == RouteMessage.LOST_POSITION)
        diagnostics?.let { append("\n").append(it.statusText(context)) }
}

internal fun RouteReadDiagnostics.statusText(context: android.content.Context): String = buildString {
    append(context.getString(when (issue) {
        RouteReadIssue.READY -> R.string.route_read_ready
        RouteReadIssue.WAITING_CONFIRMATION -> R.string.route_read_confirming
        RouteReadIssue.NO_FRAME -> R.string.route_read_no_frame
        RouteReadIssue.OVERLAY_BLOCKED -> R.string.route_read_overlay
        RouteReadIssue.MAP_MISMATCH -> R.string.route_read_map_mismatch
        RouteReadIssue.X_UNREADABLE -> R.string.route_read_x
        RouteReadIssue.Y_UNREADABLE -> R.string.route_read_y
        RouteReadIssue.XY_UNREADABLE -> R.string.route_read_xy
        RouteReadIssue.INVALID_COORDINATE -> R.string.route_read_invalid
        RouteReadIssue.POSITION_OUTLIER -> R.string.route_read_outlier
        RouteReadIssue.MINIMAP_UNCERTAIN -> R.string.route_read_minimap
    }))
    if (x != null || y != null) {
        fun RouteAxisReading?.text(): String {
            if (this == null) return context.getString(R.string.route_read_not_checked)
            val number = value
            if (number == null) return context.getString(R.string.route_no_coordinate)
            // Keep candidates bounded and clearly distinguish them from confirmed coordinates.
            val valueText = if (number in 0.0..100_000.0 && number == kotlin.math.floor(number)) number.toInt().toString()
                else context.getString(R.string.route_read_invalid_number)
            return context.getString(if (accepted) R.string.route_read_axis else R.string.route_read_candidate,
                valueText, (confidence.coerceIn(0.0, 1.0) * 100).toInt())
        }
        append("\n").append(context.getString(R.string.route_read_axes, x.text(), y.text()))
    }
    mapMatched?.let { append("\n").append(context.getString(if (it) R.string.route_read_map_ok else R.string.route_read_map_failed)) }
}

internal fun RoutePoint.coordinateText(): String = "${x.toInt()}, ${y.toInt()}"

internal fun RouteMessage.stringId(): Int = when (this) {
    RouteMessage.PREPARING -> R.string.route_message_preparing
    RouteMessage.READING -> R.string.route_message_reading
    RouteMessage.RECORDING -> R.string.route_message_recording
    RouteMessage.APPROACHING_START -> R.string.route_message_approaching_start
    RouteMessage.REPLAYING -> R.string.route_message_replaying
    RouteMessage.WAITING_MOVEMENT -> R.string.route_message_waiting_movement
    RouteMessage.ADJUSTING_STEP -> R.string.route_message_adjusting_step
    RouteMessage.PAUSED -> R.string.route_message_paused
    RouteMessage.COMPLETE -> R.string.route_message_complete
    RouteMessage.DONE -> R.string.route_message_done
    RouteMessage.SAVED_DRAFT -> R.string.route_message_saved_draft
    RouteMessage.WRONG_START -> R.string.route_message_wrong_start
    RouteMessage.POSITION_JUMP -> R.string.route_message_position_jump
    RouteMessage.STUCK -> R.string.route_message_stuck
    RouteMessage.TIMEOUT -> R.string.route_message_timeout
    RouteMessage.LOST_POSITION -> R.string.route_message_lost_position
    RouteMessage.BAD_CALIBRATION -> R.string.route_message_bad_calibration
    RouteMessage.RESOLUTION_CHANGED -> R.string.route_message_resolution_changed
    RouteMessage.SERVICE_STOPPED -> R.string.route_message_service_stopped
    RouteMessage.MODELS_MISSING -> R.string.route_message_models_missing
    RouteMessage.GESTURE_FAILED -> R.string.route_message_gesture_failed
    RouteMessage.LIMIT_REACHED -> R.string.route_message_limit_reached
    RouteMessage.FAILED -> R.string.route_message_failed
    RouteMessage.LOCALIZATION_PASSED -> R.string.route_message_localization_passed
    RouteMessage.LOCALIZATION_WEAK -> R.string.route_message_localization_weak
}
