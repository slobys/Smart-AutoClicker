/* Copyright (C) 2026 Kevin Buzeau
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.buzbuz.smartautoclicker.scenarios.list.delete

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.ui.utils.getDynamicColorsContext
import com.buzbuz.smartautoclicker.databinding.DialogScenarioBulkDeleteBinding
import com.buzbuz.smartautoclicker.scenarios.list.adapter.ScenarioGroupSelectionAdapter
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ScenarioBulkDeleteDialog : DialogFragment() {
    private val viewModel: ScenarioBulkDeleteViewModel by viewModels()
    private var binding: DialogScenarioBulkDeleteBinding? = null
    private var confirmationDialog: AlertDialog? = null
    private lateinit var adapter: ScenarioGroupSelectionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val themed = requireContext().getDynamicColorsContext(R.style.AppTheme)
        val views = DialogScenarioBulkDeleteBinding.inflate(layoutInflater.cloneInContext(themed))
        binding = views
        adapter = ScenarioGroupSelectionAdapter { viewModel.select(adapter.getSelection()) }
        views.scenarioList.adapter = adapter
        views.scenarioList.itemAnimator = null
        views.selectAll.setOnClickListener { viewModel.toggleAll() }
        views.buttonCancel.setOnClickListener { dismiss() }
        views.buttonDelete.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel.requestDeletion()
        }
        return MaterialAlertDialogBuilder(themed).setBackgroundInsetStart(0).setBackgroundInsetEnd(0)
            .setBackgroundInsetTop(0).setBackgroundInsetBottom(0)
            .setView(views.root).create().apply { setCanceledOnTouchOutside(false) }
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        val width = minOf((520 * metrics.density).toInt(), metrics.widthPixels - (32 * metrics.density).toInt())
        val height = minOf((600 * metrics.density).toInt(), (metrics.heightPixels * 0.85f).toInt())
        // Bound the whole dialog; only the weighted list may shrink, never the action buttons.
        dialog?.window?.setLayout(width, height)
    }

    private fun render(state: BulkDeleteState) {
        val views = binding ?: return
        adapter.setSelectionEnabled(!state.deleting && state.confirmation == null)
        adapter.setScenarios(state.scenarios, state.selected)
        isCancelable = !state.deleting
        views.buttonCancel.isEnabled = !state.deleting
        views.selectAll.isEnabled = !state.loading && !state.deleting && !state.loadFailed && state.scenarios.isNotEmpty()
        views.selectAll.setText(if (state.selected.isNotEmpty() && state.selected.size == state.scenarios.size)
            R.string.scenario_bulk_clear_selection else R.string.menu_item_title_select_all)
        views.selectionSummary.text = getString(R.string.scenario_bulk_selection_count, state.selected.size, state.scenarios.size)
        views.buttonDelete.isEnabled = state.selected.isNotEmpty() && !state.deleting && !state.loadFailed
        views.buttonDelete.text = if (state.deleting) getString(R.string.scenario_bulk_deleting)
            else getString(R.string.scenario_bulk_delete_count, state.selected.size)
        // Theme-aware neutral colors when disabled; active deletion is clearly destructive.
        views.buttonDelete.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(views.root,
            if (views.buttonDelete.isEnabled) androidx.appcompat.R.attr.colorError
            else com.google.android.material.R.attr.colorSurfaceVariant))
        views.buttonDelete.setTextColor(MaterialColors.getColor(views.root,
            if (views.buttonDelete.isEnabled) com.google.android.material.R.attr.colorOnError
            else com.google.android.material.R.attr.colorOnSurfaceVariant))
        views.progress.isVisible = state.loading || state.deleting
        val status = when {
            state.loadFailed -> getString(R.string.scenario_bulk_load_error)
            state.result?.failed?.let { it > 0 } == true ->
                getString(R.string.scenario_bulk_partial_failure, state.result.deleted, state.result.failed)
            !state.loading && state.scenarios.isEmpty() -> getString(R.string.scenario_bulk_empty)
            else -> null
        }
        views.status.text = status
        views.status.isVisible = status != null
        if (!state.deleting && state.result?.failed == 0) {
            Toast.makeText(requireContext(), getString(R.string.scenario_bulk_deleted, state.result.deleted), Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }
        val pending = state.confirmation
        if (pending != null && confirmationDialog == null) {
            val names = pending.joinToString("\n") { item ->
                val type = getString(if (item.reference.isSmart) R.string.item_title_smart_scenario else R.string.item_title_dumb_scenario)
                val group = item.groupName.ifEmpty { getString(R.string.item_scenario_group_ungrouped) }
                "• ${item.name} ($type · $group)"
            }
            confirmationDialog = MaterialAlertDialogBuilder(views.root.context)
                .setTitle(getString(R.string.scenario_bulk_confirm_title, pending.size))
                .setMessage(getString(R.string.scenario_bulk_confirm_message, names))
                .setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.cancelConfirmation() }
                .setPositiveButton(R.string.scenario_bulk_confirm_delete) { _, _ -> viewModel.confirmDeletion() }
                .setOnCancelListener { viewModel.cancelConfirmation() }
                .create().also { alert ->
                    alert.setOnDismissListener { confirmationDialog = null }
                    alert.show()
                    alert.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                        MaterialColors.getColor(views.root, androidx.appcompat.R.attr.colorError))
                }
        }
    }

    override fun onDestroyView() {
        confirmationDialog?.setOnCancelListener(null)
        confirmationDialog?.dismiss()
        confirmationDialog = null
        binding?.scenarioList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    companion object { const val TAG = "ScenarioBulkDeleteDialog" }
}
