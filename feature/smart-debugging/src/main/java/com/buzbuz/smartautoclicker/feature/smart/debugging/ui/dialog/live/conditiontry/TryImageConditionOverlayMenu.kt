/*
 * Copyright (C) 2025 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.conditiontry

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.buzbuz.smartautoclicker.core.base.isStopScenarioKey
import com.buzbuz.smartautoclicker.core.common.overlays.base.viewModels
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.feature.smart.debugging.R
import com.buzbuz.smartautoclicker.feature.smart.debugging.databinding.OverlayTryImageConditionMenuBinding
import com.buzbuz.smartautoclicker.feature.smart.debugging.di.DebuggingViewModelsEntryPoint
import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.SidePanelOverlayMenu
import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live.uistate.ScreenConditionResultUiState
import com.buzbuz.smartautoclicker.feature.smart.debugging.ui.view.DebugOverlayView
import com.google.android.material.slider.Slider

import kotlinx.coroutines.launch


class TryImageConditionOverlayMenu(
    private val scenario: Scenario,
    private val imageCondition: ScreenCondition,
    private val onNewThresholdSelected: (Int) -> Unit,
) : SidePanelOverlayMenu() {

    /** The view model for this dialog. */
    private val viewModel: TryImageConditionViewModel by viewModels(
        entryPoint = DebuggingViewModelsEntryPoint::class.java,
        creator = { tryImageConditionViewModel() },
    )

    private lateinit var viewBinding: OverlayTryImageConditionMenuBinding

    override val resultPanel: View get() = viewBinding.layoutResult

    override fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup {
        viewBinding = OverlayTryImageConditionMenuBinding.inflate(LayoutInflater.from(context)).apply {
            sliderThreshold.apply {
                valueFrom = MIN_THRESHOLD
                valueTo = MAX_THRESHOLD
                value = imageCondition.threshold.toFloat()

                addOnChangeListener(Slider.OnChangeListener { _, sliderValue, fromUser ->
                    if (fromUser) viewModel.setThreshold(sliderValue.toInt())
                })
            }
        }

        val color = imageCondition as? ScreenCondition.Color
        viewBinding.colorPreviewPanel.isVisible = color != null
        if (color != null) {
            viewBinding.targetColor.background = swatch(color.color)
            viewBinding.colorValues.text = context.getString(R.string.overlay_color_values,
                color.color.toHexColor(), "—")
        }

        return viewBinding.root
    }

    override fun onCreateOverlayView(): View = DebugOverlayView(context).apply {
        setConditions(listOf(imageCondition))
    }

    override fun onStart() {
        super.onStart()
        (screenOverlayView as? DebugOverlayView)?.setConditions(listOf(imageCondition))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.displayResults.collect(::updateDetectionResults) }
                launch { viewModel.thresholdText.collect(viewBinding.valueThreshold::setText) }
            }
        }

        viewModel.startTry(context, scenario, imageCondition)
    }

    override fun onStop() {
        viewBinding.colorPreview.setPreview(null)
        (screenOverlayView as? DebugOverlayView)?.apply { clear(); setConditions(emptyList()) }
        viewModel.stopTry()
        onNewThresholdSelected(viewModel.getSelectedThreshold())
    }

    override fun onMenuItemClicked(viewId: Int) {
        when (viewId) {
            R.id.btn_back -> {
                viewModel.stopTry()
                back()
            }
        }
    }

    override fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (!keyEvent.isStopScenarioKey()) return false

        if (keyEvent.action == KeyEvent.ACTION_DOWN) {
            viewModel.stopTry()
            back()
        }

        return true
    }

    private fun updateDetectionResults(results: ScreenConditionResultUiState?) {
        (screenOverlayView as? DebugOverlayView)?.setResults(results?.let { listOf(it) } ?: emptyList())
        viewBinding.valueResult.text = results?.resultText ?: "—"
        viewBinding.detectionStatus.setText(when (results?.isDetected) {
            true -> R.string.overlay_target_found
            false -> R.string.overlay_target_not_found
            null -> R.string.overlay_waiting_frame
        })
        viewBinding.conditionStatus.setText(when (results?.positive) {
            true -> R.string.overlay_condition_fulfilled
            false -> R.string.overlay_condition_not_fulfilled
            null -> R.string.overlay_range_legend
        })
        val color = imageCondition as? ScreenCondition.Color ?: return
        val preview = results?.colorPreview
        viewBinding.colorPreview.setPreview(preview)
        viewBinding.currentColor.background = swatch(preview?.color ?: Color.TRANSPARENT)
        viewBinding.colorValues.text = context.getString(R.string.overlay_color_values,
            color.color.toHexColor(), preview?.color?.toHexColor() ?: "—")
        viewBinding.samplePosition.text = preview?.let {
            context.getString(if (color.findInArea && results?.isDetected == true && results.samplePoint != null)
                R.string.overlay_sample_hit else R.string.overlay_sample_center,
                it.point.x, it.point.y)
        } ?: context.getString(R.string.overlay_waiting_frame)
    }

    private fun swatch(color: Int) = GradientDrawable().apply {
        setColor(color)
        setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), Color.WHITE)
        cornerRadius = 3 * context.resources.displayMetrics.density
    }
}

private fun Int.toHexColor(): String = String.format(java.util.Locale.ROOT, "#%06X", this and 0xFFFFFF)
