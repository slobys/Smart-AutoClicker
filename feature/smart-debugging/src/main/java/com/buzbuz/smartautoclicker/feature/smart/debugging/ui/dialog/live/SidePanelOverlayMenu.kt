/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.dialog.live

import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.buzbuz.smartautoclicker.core.common.overlays.menu.OverlayMenu
import com.buzbuz.smartautoclicker.feature.smart.debugging.R

/** Test results open towards the screen interior, with the toolbar on the outside. */
abstract class SidePanelOverlayMenu : OverlayMenu() {

    protected abstract val resultPanel: View
    private var panelMargins: Pair<Int, Int>? = null

    override fun onStart() {
        super.onStart()
        updatePanelDock()
    }

    override fun onResume() {
        super.onResume()
        // Position is loaded again by OverlayMenu.start(), including when returning to this menu.
        updatePanelDock()
    }

    override fun onOrientationChanged() {
        super.onOrientationChanged()
        updatePanelDock()
    }

    override fun onMenuDockChanged(isOnLeftEdge: Boolean) {
        val content = resultPanel.parent as ConstraintLayout
        val toolbarId = R.id.menu_items
        val (innerMargin, outerMargin) = panelMargins ?: (resultPanel.layoutParams as ViewGroup.MarginLayoutParams)
            .let { it.marginStart to it.marginEnd }.also { panelMargins = it }
        ConstraintSet().apply {
            clone(content)
            listOf(toolbarId, resultPanel.id).forEach { id ->
                clear(id, ConstraintSet.START)
                clear(id, ConstraintSet.END)
                clear(id, ConstraintSet.LEFT)
                clear(id, ConstraintSet.RIGHT)
            }
            // Use physical sides, without mirroring result text or slider values.
            val first = if (isOnLeftEdge) toolbarId else resultPanel.id
            val second = if (isOnLeftEdge) resultPanel.id else toolbarId
            connect(first, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT, if (isOnLeftEdge) 0 else outerMargin)
            connect(second, ConstraintSet.LEFT, first, ConstraintSet.RIGHT, innerMargin)
            connect(second, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT, if (isOnLeftEdge) outerMargin else 0)
            applyTo(content)
        }
        forceWindowResize()
        refreshMenuPositionAfterContentChange()
    }

    private fun updatePanelDock() {
        onMenuDockChanged(isMenuOnLeftHalf())
    }
}
