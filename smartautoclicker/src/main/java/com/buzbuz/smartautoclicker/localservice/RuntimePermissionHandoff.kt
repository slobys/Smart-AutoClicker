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
import android.widget.Toast

import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.feature.qstile.ui.QSTileLauncherActivity

internal class RuntimePermissionHandoff(private val context: Context) {
    fun requestSmartScenario(scenarioId: Long) {
        Toast.makeText(context, R.string.runtime_switcher_projection_required, Toast.LENGTH_LONG).show()
        context.startActivity(QSTileLauncherActivity.getStartIntent(context, scenarioId, isSmartScenario = true))
    }
}
