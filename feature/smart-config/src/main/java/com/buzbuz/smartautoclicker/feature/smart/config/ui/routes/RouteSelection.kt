/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import android.graphics.Rect
import com.buzbuz.smartautoclicker.core.processing.routes.RecordedRoute
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.areaselector.SelectorUiState

/** Route configuration is independent of whichever screen condition was last edited. */
internal fun RecordedRoute.selectionFor(kind: Int): SelectorUiState {
    val area = when (kind) { 0 -> xArea; 1 -> yArea; 2 -> mapArea; 3 -> minimap?.area; else -> error("Unknown route area") }
    val initial = area?.takeIf { it.valid(screenWidth, screenHeight) }
        ?.let { Rect(it.left, it.top, it.right, it.bottom) } ?: run {
        val width = (if (kind == 3) 192 else 128).coerceAtMost(screenWidth / 2)
        val height = (if (kind == 3) 192 else 64).coerceAtMost(screenHeight / 2)
        val left = (screenWidth - width) / 2
        val top = (screenHeight - height) / 2
        Rect(left, top, left + width, top + height)
    }
    // The selector adds its outline around this minimum content size.
    return SelectorUiState(initial, Rect(0, 0, 8, 8))
}
