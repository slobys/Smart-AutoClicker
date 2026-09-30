/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.localservice

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.WindowManager

import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager

import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.Companion.showAsOverlay
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.core.ui.utils.getDynamicColorsContext
import com.buzbuz.smartautoclicker.databinding.DialogRuntimeScenarioSwitchBinding
import com.buzbuz.smartautoclicker.databinding.ItemRuntimeScenarioBinding

import com.google.android.material.R.attr.colorOnPrimaryContainer
import com.google.android.material.R.attr.colorOutlineVariant
import com.google.android.material.R.attr.colorSurface
import com.google.android.material.R.attr.colorSurfaceVariant
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal sealed interface RuntimeScenarioTarget {
    val databaseId: Long
    val name: String
    val isSmart: Boolean
    val itemCount: Int

    data class Smart(val scenario: Scenario) : RuntimeScenarioTarget {
        override val databaseId: Long = scenario.id.databaseId
        override val name: String = scenario.name
        override val isSmart: Boolean = true
        override val itemCount: Int = scenario.eventCount
    }

    data class Dumb(val scenario: DumbScenario) : RuntimeScenarioTarget {
        override val databaseId: Long = scenario.id.databaseId
        override val name: String = scenario.name
        override val isSmart: Boolean = false
        override val itemCount: Int = scenario.dumbActions.size
    }
}

internal data class RuntimeScenarioListItem(
    val target: RuntimeScenarioTarget,
    val isCurrent: Boolean,
)

internal fun showRuntimeScenarioSwitcher(
    context: Context,
    items: List<RuntimeScenarioListItem>,
    onSelected: (RuntimeScenarioTarget) -> Unit,
    onCreate: () -> Unit,
): AlertDialog {
    val themedContext = context.getDynamicColorsContext(R.style.AppTheme)
    val binding = DialogRuntimeScenarioSwitchBinding.inflate(LayoutInflater.from(themedContext))
    val sizing = runtimeSwitcherSizing(context, items.size)
    val smartCount = items.count { item -> item.target.isSmart }
    binding.scenarioSummary.text = themedContext.getString(
        R.string.runtime_switcher_summary,
        items.size,
        smartCount,
        items.size - smartCount,
    )

    lateinit var dialog: AlertDialog
    binding.scenarioList.layoutManager = GridLayoutManager(themedContext, sizing.columns)
    binding.scenarioList.layoutParams.height = sizing.listHeightPx
    binding.scenarioList.adapter = RuntimeScenarioAdapter(items, sizing.rowHeightPx) { item ->
        dialog.dismiss()
        if (!item.isCurrent) onSelected(item.target)
    }
    binding.scenarioList.itemAnimator = null
    binding.scenarioList.isVisible = items.isNotEmpty()
    binding.scenarioEmpty.isVisible = items.isEmpty()
    binding.buttonCancel.setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        dialog.dismiss()
    }
    binding.buttonCreate.setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        dialog.dismiss()
        onCreate()
    }

    dialog = MaterialAlertDialogBuilder(themedContext)
        .setBackgroundInsetStart(0)
        .setBackgroundInsetEnd(0)
        .setView(binding.root)
        .create()
    dialog.showAsOverlay()
    dialog.window?.setLayout(sizing.widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
    return dialog
}

internal data class RuntimeSwitcherSizing(val widthPx: Int, val columns: Int, val rowHeightPx: Int, val listHeightPx: Int)

internal fun runtimeSwitcherSizing(context: Context, itemCount: Int): RuntimeSwitcherSizing {
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    val widthDp = minOf(480f, metrics.widthPixels / density - 32f).coerceAtLeast(200f)
    val columns = if (widthDp >= 320f && fontScale <= 1.4f) 2 else 1
    val rowHeightDp = (80 * fontScale).toInt()
    val visibleRows = ((metrics.heightPixels / density - 144 * fontScale) / rowHeightDp).toInt().coerceIn(1, 3)
    val rows = ((itemCount + columns - 1) / columns).coerceAtMost(visibleRows)
    return RuntimeSwitcherSizing((widthDp * density).toInt(), columns,
        (rowHeightDp * density).toInt(), (rows * rowHeightDp * density).toInt())
}

private class RuntimeScenarioAdapter(
    private val items: List<RuntimeScenarioListItem>,
    private val rowHeightPx: Int,
    private val onClicked: (RuntimeScenarioListItem) -> Unit,
) : RecyclerView.Adapter<RuntimeScenarioViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuntimeScenarioViewHolder =
        RuntimeScenarioViewHolder(
            ItemRuntimeScenarioBinding.inflate(LayoutInflater.from(parent.context), parent, false).apply {
                root.layoutParams.height = rowHeightPx - (8 * parent.resources.displayMetrics.density).toInt()
            },
            onClicked,
        )

    override fun onBindViewHolder(holder: RuntimeScenarioViewHolder, position: Int) =
        holder.bind(items[position])

    override fun getItemCount(): Int = items.size
}

private class RuntimeScenarioViewHolder(
    private val binding: ItemRuntimeScenarioBinding,
    onClicked: (RuntimeScenarioListItem) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    private var boundItem: RuntimeScenarioListItem? = null

    init {
        binding.scenarioCard.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            boundItem?.let(onClicked)
        }
    }

    fun bind(item: RuntimeScenarioListItem) {
        boundItem = item
        val context = binding.root.context
        val target = item.target

        binding.scenarioName.text = target.name
        binding.scenarioDetails.text = context.getString(
            if (target.isSmart) R.string.runtime_switcher_smart_details
            else R.string.runtime_switcher_dumb_details,
            target.itemCount,
        )
        binding.scenarioIcon.setImageResource(if (target.isSmart) R.drawable.ic_smart else R.drawable.ic_dumb)
        ImageViewCompat.setImageTintList(
            binding.scenarioIcon,
            ColorStateList.valueOf(MaterialColors.getColor(binding.scenarioIcon, colorOnPrimaryContainer)),
        )
        binding.currentBadge.isVisible = item.isCurrent

        binding.scenarioCard.apply {
            strokeWidth = resources.displayMetrics.density.times(if (item.isCurrent) 2f else 1f).toInt()
            setStrokeColor(
                MaterialColors.getColor(
                    this,
                    if (item.isCurrent) androidx.appcompat.R.attr.colorPrimary else colorOutlineVariant,
                )
            )
            setCardBackgroundColor(MaterialColors.getColor(this, if (item.isCurrent) colorSurfaceVariant else colorSurface))
            contentDescription = listOfNotNull(
                target.name,
                binding.scenarioDetails.text,
                context.getString(R.string.runtime_switcher_current).takeIf { item.isCurrent },
            ).joinToString(", ")
        }
    }
}
