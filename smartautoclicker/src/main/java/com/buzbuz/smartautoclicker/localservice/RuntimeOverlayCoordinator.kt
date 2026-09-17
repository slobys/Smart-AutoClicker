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

import com.buzbuz.smartautoclicker.core.common.overlays.base.Overlay
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Serialised root-overlay replacement with a completion check. */
internal class RuntimeOverlayCoordinator(
    private val context: Context,
    private val overlayManager: OverlayManager,
) {
    suspend fun replaceRoot(newOverlay: Overlay) {
        check(closeAll()) { "Timed out while replacing the runtime scenario menu" }
        overlayManager.navigateTo(context, newOverlay)
    }

    suspend fun closeAll(): Boolean {
        overlayManager.closeAll(context)
        return withTimeoutOrNull(RUNTIME_TRANSITION_TIMEOUT_MS) {
            overlayManager.backStackTopFlow.first { overlay -> overlay == null }
            true
        } == true
    }
}

internal const val RUNTIME_TRANSITION_TIMEOUT_MS = 10_000L
