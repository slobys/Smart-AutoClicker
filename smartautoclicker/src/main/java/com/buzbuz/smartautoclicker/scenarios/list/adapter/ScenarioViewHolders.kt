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
package com.buzbuz.smartautoclicker.scenarios.list.adapter

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.scenarios.list.model.ScenarioListUiState
import com.buzbuz.smartautoclicker.core.base.extensions.setLeftCompoundDrawable
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbScenario
import com.buzbuz.smartautoclicker.databinding.ItemDumbScenarioBinding
import com.buzbuz.smartautoclicker.databinding.ItemEmptyScenarioBinding
import com.buzbuz.smartautoclicker.databinding.ItemScenarioGroupHeaderBinding
import com.buzbuz.smartautoclicker.databinding.ItemSmartScenarioBinding
import com.buzbuz.smartautoclicker.scenarios.list.model.getTimeSinceString
import kotlinx.coroutines.Job

import java.util.Locale

class EmptyScenarioHolder(
    private val viewBinding: ItemEmptyScenarioBinding,
    private val startScenarioListener: ((ScenarioListUiState.Item.ScenarioItem.Empty) -> Unit),
    private val deleteScenarioListener: ((ScenarioListUiState.Item.ScenarioItem.Empty) -> Unit),
    private val favoriteClickedListener: ((ScenarioListUiState.Item.ScenarioItem.Empty) -> Unit),
    private val groupClickedListener: ((ScenarioListUiState.Item.ScenarioItem.Empty) -> Unit),
): RecyclerView.ViewHolder(viewBinding.root) {

    fun onBind(scenarioItem: ScenarioListUiState.Item.ScenarioItem.Empty) = viewBinding.apply {
        scenarioName.text = scenarioItem.displayName
        scenarioName.setLeftCompoundDrawable(
            if (scenarioItem.scenario is DumbScenario) R.drawable.ic_dumb
            else R.drawable.ic_smart
        )

        buttonStart.setOnClickListener { startScenarioListener(scenarioItem) }
        buttonDelete.setOnClickListener { deleteScenarioListener(scenarioItem) }
        buttonFavorite.setIconResource(
            if (scenarioItem.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star
        )
        buttonFavorite.setOnClickListener { favoriteClickedListener(scenarioItem) }
        scenarioGroup.text = scenarioItem.groupName.ifEmpty {
            root.context.getString(R.string.item_scenario_group_ungrouped)
        }
        scenarioGroup.setOnClickListener { groupClickedListener(scenarioItem) }
    }
}

/** ViewHolder for the [ScenarioAdapter]. */
class DumbScenarioViewHolder(
    private val viewBinding: ItemDumbScenarioBinding,
    private val startScenarioListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val expandCollapseListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val exportClickListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val copyClickedListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val deleteScenarioListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val favoriteClickedListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val groupClickedListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
) : RecyclerView.ViewHolder(viewBinding.root) {

    fun onBind(scenarioItem: ScenarioListUiState.Item.ScenarioItem.Valid.Dumb) = viewBinding.apply {
        scenarioName.text = scenarioItem.displayName
        bindOrganization(
            isFavorite = scenarioItem.isFavorite,
            groupName = scenarioItem.groupName,
            onFavoriteClicked = { favoriteClickedListener(scenarioItem) },
            onGroupClicked = { groupClickedListener(scenarioItem) },
        )

        if (scenarioItem.showExportCheckbox) {
            buttonStart.visibility = View.GONE
            buttonStart.isEnabled = false
            buttonFavorite.visibility = View.GONE
            scenarioGroup.isEnabled = false
            buttonExpandCollapse.visibility = View.INVISIBLE
            buttonExpandCollapse.isEnabled = false
            buttonExport.apply {
                visibility = View.VISIBLE
                isChecked = scenarioItem.checkedForExport
            }
            topDivider.visibility = View.GONE
            root.setOnClickListener { exportClickListener(scenarioItem) }

        } else {
            buttonStart.visibility = View.VISIBLE
            buttonStart.isEnabled = true
            buttonFavorite.visibility = View.VISIBLE
            scenarioGroup.isEnabled = true
            buttonExpandCollapse.visibility = View.VISIBLE
            buttonExpandCollapse.isEnabled = true
            buttonExport.visibility = View.GONE
            topDivider.visibility = View.VISIBLE
            root.setOnClickListener { startScenarioListener(scenarioItem) }
        }

        if (!scenarioItem.showExportCheckbox && scenarioItem.expanded) {
            scenarioDetails.visibility = View.VISIBLE
            buttonExpandCollapse.setIconResource(R.drawable.ic_chevron_up)

            executionCount.text = String.format(Locale.getDefault(), "%d", scenarioItem.startCount)
            lastExecution.text = root.context.getTimeSinceString(scenarioItem.lastStartTimestamp)
            clickCount.text = String.format(Locale.getDefault(), "%d", scenarioItem.clickCount)
            swipeCount.text = String.format(Locale.getDefault(), "%d", scenarioItem.swipeCount)
            pauseCount.text = String.format(Locale.getDefault(), "%d", scenarioItem.pauseCount)

            repeatLimit.text = scenarioItem.repeatText
            durationLimit.text = scenarioItem.maxDurationText
        } else {
            buttonExpandCollapse.setIconResource(R.drawable.ic_chevron_down)
            scenarioDetails.visibility = View.GONE
        }

        buttonCopy.setOnClickListener { copyClickedListener(scenarioItem) }
        buttonStart.setOnClickListener { startScenarioListener(scenarioItem) }
        buttonExpandCollapse.setOnClickListener { expandCollapseListener(scenarioItem) }
        buttonDelete.setOnClickListener { deleteScenarioListener(scenarioItem) }
        buttonExport.setOnClickListener { exportClickListener(scenarioItem) }
    }
}

/** ViewHolder for the [ScenarioAdapter]. */
class SmartScenarioViewHolder(
    private val viewBinding: ItemSmartScenarioBinding,
    bitmapProvider: (ScreenCondition.Image, onBitmapLoaded: (Bitmap?) -> Unit) -> Job?,
    private val startScenarioListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val expandCollapseListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val exportClickListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val copyClickedListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val deleteScenarioListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val favoriteClickedListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
    private val groupClickedListener: ((ScenarioListUiState.Item.ScenarioItem.Valid) -> Unit),
) : RecyclerView.ViewHolder(viewBinding.root) {

    private val eventsAdapter = ScenarioEventsAdapter(bitmapProvider)

    init {
        viewBinding.listEvent.adapter = eventsAdapter
    }

    fun onBind(scenarioItem: ScenarioListUiState.Item.ScenarioItem.Valid.Smart) = viewBinding.apply {
        scenarioName.text = scenarioItem.displayName
        bindOrganization(
            isFavorite = scenarioItem.isFavorite,
            groupName = scenarioItem.groupName,
            onFavoriteClicked = { favoriteClickedListener(scenarioItem) },
            onGroupClicked = { groupClickedListener(scenarioItem) },
        )

        if (scenarioItem.showExportCheckbox) {
            buttonStart.visibility = View.GONE
            buttonStart.isEnabled = false
            buttonFavorite.visibility = View.GONE
            scenarioGroup.isEnabled = false
            buttonExpandCollapse.visibility = View.INVISIBLE
            buttonExpandCollapse.isEnabled = false
            buttonExport.apply {
                visibility = View.VISIBLE
                isChecked = scenarioItem.checkedForExport
            }
            topDivider.visibility = View.GONE
            root.setOnClickListener { exportClickListener(scenarioItem) }
        } else {
            buttonStart.visibility = View.VISIBLE
            buttonStart.isEnabled = true
            buttonFavorite.visibility = View.VISIBLE
            scenarioGroup.isEnabled = true
            buttonExpandCollapse.visibility = View.VISIBLE
            buttonExpandCollapse.isEnabled = true
            buttonExport.visibility = View.GONE
            topDivider.visibility = View.VISIBLE
            root.setOnClickListener { startScenarioListener(scenarioItem) }
        }

        if (!scenarioItem.showExportCheckbox && scenarioItem.expanded) {
            scenarioDetails.visibility = View.VISIBLE
            buttonExpandCollapse.setIconResource(R.drawable.ic_chevron_up)

            executionCount.text = String.format(Locale.getDefault(), "%d", scenarioItem.startCount)
            lastExecution.text = root.context.getTimeSinceString(scenarioItem.lastStartTimestamp)
            detectionQuality.text = String.format(Locale.getDefault(), "%d", scenarioItem.detectionQuality)
            triggerEventCount.text = String.format(Locale.getDefault(), "%d", scenarioItem.triggerEventCount)

            eventsAdapter.submitList(scenarioItem.eventsItems)
            if (scenarioItem.eventsItems.isEmpty()) {
                listEvent.visibility = View.GONE
                noImageEvents.visibility = View.VISIBLE
            } else {
                listEvent.visibility = View.VISIBLE
                noImageEvents.visibility = View.GONE
            }
        } else {
            buttonExpandCollapse.setIconResource(R.drawable.ic_chevron_down)
            scenarioDetails.visibility = View.GONE
        }

        buttonCopy.setOnClickListener { copyClickedListener(scenarioItem) }
        buttonStart.setOnClickListener { startScenarioListener(scenarioItem) }
        buttonExpandCollapse.setOnClickListener { expandCollapseListener(scenarioItem) }
        buttonDelete.setOnClickListener { deleteScenarioListener(scenarioItem) }
        buttonExport.setOnClickListener { exportClickListener(scenarioItem) }
    }
}

class ScenarioGroupHeaderViewHolder(
    private val viewBinding: ItemScenarioGroupHeaderBinding,
    private val collapseClickedListener: (ScenarioListUiState.Item.GroupHeader) -> Unit,
    private val manageClickedListener: (ScenarioListUiState.Item.GroupHeader) -> Unit,
) : RecyclerView.ViewHolder(viewBinding.root) {

    fun onBind(item: ScenarioListUiState.Item.GroupHeader) = viewBinding.apply {
        groupName.text = item.name
        groupCount.text = String.format(Locale.getDefault(), "%d", item.scenarioCount)
        buttonCollapseGroup.setIconResource(
            if (item.isCollapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up
        )
        buttonCollapseGroup.contentDescription = root.context.getString(
            if (item.isCollapsed) R.string.content_desc_expand_scenario_group
            else R.string.content_desc_collapse_scenario_group
        )
        root.setOnClickListener { collapseClickedListener(item) }
        buttonCollapseGroup.setOnClickListener { collapseClickedListener(item) }
        buttonManageGroup.setOnClickListener { manageClickedListener(item) }
    }
}

private fun ItemDumbScenarioBinding.bindOrganization(
    isFavorite: Boolean,
    groupName: String,
    onFavoriteClicked: () -> Unit,
    onGroupClicked: () -> Unit,
) {
    buttonFavorite.setIconResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star)
    buttonFavorite.setOnClickListener { onFavoriteClicked() }
    scenarioGroup.text = groupName.ifEmpty { root.context.getString(R.string.item_scenario_group_ungrouped) }
    scenarioGroup.setOnClickListener { onGroupClicked() }
}

private fun ItemSmartScenarioBinding.bindOrganization(
    isFavorite: Boolean,
    groupName: String,
    onFavoriteClicked: () -> Unit,
    onGroupClicked: () -> Unit,
) {
    buttonFavorite.setIconResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star)
    buttonFavorite.setOnClickListener { onFavoriteClicked() }
    scenarioGroup.text = groupName.ifEmpty { root.context.getString(R.string.item_scenario_group_ungrouped) }
    scenarioGroup.setOnClickListener { onGroupClicked() }
}
