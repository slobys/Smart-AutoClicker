/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMenuCollapseTests {

    @Test
    fun debugPanelIsVisibleWhenDebuggingAndMenuIsExpanded() {
        assertTrue(shouldShowDebugPanel(isDebugging = true, isMenuCollapsed = false))
    }

    @Test
    fun debugPanelRemainsVisibleWhenMenuIsCollapsed() {
        assertTrue(shouldShowDebugPanel(isDebugging = true, isMenuCollapsed = true))
    }

    @Test
    fun debugPanelRemainsHiddenWhenDebuggingIsDisabled() {
        assertFalse(shouldShowDebugPanel(isDebugging = false, isMenuCollapsed = false))
    }
}
