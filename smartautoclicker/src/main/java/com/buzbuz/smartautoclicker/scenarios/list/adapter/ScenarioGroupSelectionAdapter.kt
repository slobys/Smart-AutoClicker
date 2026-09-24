/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.scenarios.list.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.databinding.ItemScenarioGroupSelectionBinding
import com.buzbuz.smartautoclicker.scenarios.list.model.GroupableScenario

class ScenarioGroupSelectionAdapter(
    private val onSelectionChanged: (Int) -> Unit,
) : ListAdapter<GroupableScenario, ScenarioGroupSelectionAdapter.ViewHolder>(DiffCallback) {

    private val selected = mutableSetOf<GroupableScenario.Reference>()

    fun setScenarios(
        scenarios: List<GroupableScenario>,
        initialSelection: Set<GroupableScenario.Reference>,
    ) {
        selected.clear()
        selected.addAll(initialSelection)
        submitList(scenarios)
        onSelectionChanged(selected.size)
    }

    fun getSelection(): Set<GroupableScenario.Reference> = selected.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemScenarioGroupSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemScenarioGroupSelectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GroupableScenario) = binding.groupScenarioCheckbox.apply {
            setOnCheckedChangeListener(null)
            binding.scenarioName.text = item.name
            binding.currentGroup.text = item.groupName.ifEmpty {
                binding.root.context.getString(R.string.item_scenario_group_ungrouped)
            }
            binding.scenarioType.setImageResource(
                if (item.reference.isSmart) R.drawable.ic_smart else R.drawable.ic_dumb
            )
            contentDescription = item.name
            isChecked = item.reference in selected
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(item.reference) else selected.remove(item.reference)
                onSelectionChanged(selected.size)
            }
            binding.root.setOnClickListener { isChecked = !isChecked }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<GroupableScenario>() {
        override fun areItemsTheSame(oldItem: GroupableScenario, newItem: GroupableScenario): Boolean =
            oldItem.reference == newItem.reference

        override fun areContentsTheSame(oldItem: GroupableScenario, newItem: GroupableScenario): Boolean =
            oldItem == newItem
    }
}
