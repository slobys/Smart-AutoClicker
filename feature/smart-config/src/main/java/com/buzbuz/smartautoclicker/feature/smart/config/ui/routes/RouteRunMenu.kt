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
                if (progress.value.message !in setOf(RouteMessage.COMPLETE, RouteMessage.SAVED_DRAFT, RouteMessage.DONE))
                    progress.value = progress.value.copy(message = RouteMessage.DONE)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RouteFailure) { progress.value = RouteProgress(failure.reason) }
            catch (failure: Exception) {
                android.util.Log.e("RouteRunMenu", "Route operation failed", failure)
                progress.value = RouteProgress(RouteMessage.FAILED)
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
                    else progress.value.copy(message = RouteMessage.READING)
                binding.routePause.setText(if (control.paused) R.string.route_resume else R.string.route_pause)
            }
        }
        binding.routeFinish.setOnClickListener {
            if (finished) back() else { control.stopped = true; binding.routeFinish.isEnabled = false }
        }
    }

    private fun showProgress(value: RouteProgress) {
        binding.routeStatus.text = context.getString(R.string.route_progress, context.getString(value.message.stringId()),
            value.position?.let { "${it.x.toInt()}, ${it.y.toInt()}" } ?: context.getString(R.string.route_no_coordinate), value.count)
        binding.routePause.setText(if (control.paused) R.string.route_resume else R.string.route_pause)
        if (finished) binding.routeFinish.isEnabled = true
    }

    override fun back() { control.stopped = true; work?.cancel(); heartbeat?.cancel(); super.back() }
    override fun onStop() { control.stopped = true; work?.cancel(); heartbeat?.cancel(); super.onStop() }
}

internal fun RouteMessage.stringId(): Int = when (this) {
    RouteMessage.PREPARING -> R.string.route_message_preparing
    RouteMessage.READING -> R.string.route_message_reading
    RouteMessage.RECORDING -> R.string.route_message_recording
    RouteMessage.REPLAYING -> R.string.route_message_replaying
    RouteMessage.PAUSED -> R.string.route_message_paused
    RouteMessage.COMPLETE -> R.string.route_message_complete
    RouteMessage.DONE -> R.string.route_message_done
    RouteMessage.SAVED_DRAFT -> R.string.route_message_saved_draft
    RouteMessage.WRONG_START -> R.string.route_message_wrong_start
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
}
