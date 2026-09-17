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

import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView

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
) {
    val themedContext = context.getDynamicColorsContext(R.style.AppTheme)
    if (items.size <= 1) {
        MaterialAlertDialogBuilder(themedContext)
            .setTitle(R.string.runtime_switcher_title)
            .setMessage(R.string.runtime_switcher_empty)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .showAsOverlay()
        return
    }

    val binding = DialogRuntimeScenarioSwitchBinding.inflate(LayoutInflater.from(themedContext))
    val smartCount = items.count { item -> item.target.isSmart }
    binding.scenarioSummary.text = themedContext.getString(
        R.string.runtime_switcher_summary,
        items.size,
        smartCount,
        items.size - smartCount,
    )

    lateinit var dialog: AlertDialog
    binding.scenarioList.adapter = RuntimeScenarioAdapter(items) { item ->
        if (item.isCurrent) return@RuntimeScenarioAdapter
        dialog.dismiss()
        onSelected(item.target)
    }
    binding.scenarioList.itemAnimator = null

    dialog = MaterialAlertDialogBuilder(themedContext)
        .setTitle(R.string.runtime_switcher_title)
        .setView(binding.root)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.showAsOverlay()
}

private class RuntimeScenarioAdapter(
    private val items: List<RuntimeScenarioListItem>,
    private val onClicked: (RuntimeScenarioListItem) -> Unit,
) : RecyclerView.Adapter<RuntimeScenarioViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuntimeScenarioViewHolder =
        RuntimeScenarioViewHolder(
            ItemRuntimeScenarioBinding.inflate(LayoutInflater.from(parent.context), parent, false),
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
        binding.scenarioCard.setOnClickListener { boundItem?.let(onClicked) }
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
