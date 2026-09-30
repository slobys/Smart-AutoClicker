/*
 * Copyright (C) 2026 Kevin Buzeau
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.buzbuz.smartautoclicker.localservice

import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.Companion.showAsOverlay
import com.buzbuz.smartautoclicker.core.ui.utils.getDynamicColorsContext
import com.buzbuz.smartautoclicker.databinding.DialogRuntimeScenarioCreateBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.util.Log

internal fun showRuntimeScenarioCreator(
    context: Context,
    scope: CoroutineScope,
    initialKind: RuntimeScenarioKind,
    onDismissed: () -> Unit,
    onCreate: suspend (String, RuntimeScenarioKind) -> Unit,
): AlertDialog {
    val themed = context.getDynamicColorsContext(R.style.AppTheme)
    val binding = DialogRuntimeScenarioCreateBinding.inflate(LayoutInflater.from(themed))
    val dialog = MaterialAlertDialogBuilder(themed)
        .setBackgroundInsetStart(0).setBackgroundInsetEnd(0).setView(binding.root).create()
    var creationJob: Job? = null
    var creating = false
    fun setCreating(value: Boolean) {
        creating = value
        binding.buttonCreate.isEnabled = !value
        binding.buttonCancel.isEnabled = !value
        binding.scenarioName.isEnabled = !value
        binding.typeSmart.isEnabled = !value
        binding.typeDumb.isEnabled = !value
        binding.creationProgress.isVisible = value
        binding.buttonCreate.setText(if (value) R.string.runtime_creating else R.string.runtime_create_and_edit)
        dialog.setCancelable(!value)
        dialog.setCanceledOnTouchOutside(false)
    }
    binding.scenarioName.filters = arrayOf(InputFilter.LengthFilter(context.resources.getInteger(R.integer.name_max_length)))
    binding.scenarioName.setText(context.getString(R.string.default_scenario_name))
    binding.scenarioType.addOnButtonCheckedListener { _, _, _ ->
        // Keep a non-color selection cue, including while creation disables the buttons.
        listOf(binding.typeSmart, binding.typeDumb).forEach { button ->
            button.setIconResource(if (button.isChecked) R.drawable.ic_confirm else 0)
            button.setTypeface(null, if (button.isChecked) Typeface.BOLD else Typeface.NORMAL)
        }
    }
    binding.scenarioType.check(if (initialKind == RuntimeScenarioKind.SMART) R.id.type_smart else R.id.type_dumb)
    binding.buttonCancel.setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        dialog.dismiss()
    }
    binding.buttonCreate.setOnClickListener {
        if (creating) return@setOnClickListener
        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val name = binding.scenarioName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.nameLayout.error = context.getString(R.string.runtime_create_name_required)
            return@setOnClickListener
        }
        binding.nameLayout.error = null
        binding.creationStatus.isVisible = false
        setCreating(true)
        val kind = if (binding.scenarioType.checkedButtonId == R.id.type_smart) RuntimeScenarioKind.SMART else RuntimeScenarioKind.DUMB
        creationJob = scope.launch {
            try {
                onCreate(name, kind)
                dialog.dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("RuntimeScenarioCreator", "Unable to create scenario", error)
                if (dialog.isShowing) {
                    setCreating(false)
                    binding.creationStatus.setText(R.string.runtime_create_error)
                    binding.creationStatus.isVisible = true
                }
            }
        }
    }
    dialog.setOnDismissListener { creationJob?.cancel(); onDismissed() }
    dialog.setCanceledOnTouchOutside(false)
    dialog.showAsOverlay()
    dialog.window?.apply {
        clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setLayout(runtimeSwitcherSizing(context, 0).widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    return dialog
}
