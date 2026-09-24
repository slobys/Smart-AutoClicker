/*
 * Copyright (C) 2026 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.core.common.overlays.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMenuCollapsePolicyTests {

    @Test
    fun userRequestCanCollapseWhileAutomaticCollapseIsBlocked() {
        assertTrue(
            shouldCollapseOverlayMenu(
                isMenuCollapsed = false,
                isUserInitiated = true,
                canAutoCollapse = false,
            ),
        )
    }

    @Test
    fun automaticRequestRemainsBlockedWhileDebugContentIsVisible() {
        assertFalse(
            shouldCollapseOverlayMenu(
                isMenuCollapsed = false,
                isUserInitiated = false,
                canAutoCollapse = false,
            ),
        )
    }

    @Test
    fun expandedMenuCanStillCollapseAutomaticallyWhenAllowed() {
        assertTrue(
            shouldCollapseOverlayMenu(
                isMenuCollapsed = false,
                isUserInitiated = false,
                canAutoCollapse = true,
            ),
        )
    }

    @Test
    fun collapsedMenuDoesNotCollapseAgain() {
        assertFalse(
            shouldCollapseOverlayMenu(
                isMenuCollapsed = true,
                isUserInitiated = true,
                canAutoCollapse = true,
            ),
        )
    }

    @Test
    fun concealedLauncherLeavesOnlyHandleVisibleOnLeftEdge() {
        assertEquals(
            -190,
            calculateConcealedEdgePosition(
                displayWidth = 1920,
                menuWidth = 200,
                visibleHandleWidth = 10,
                isOnLeftEdge = true,
            ),
        )
    }

    @Test
    fun concealedLauncherLeavesOnlyHandleVisibleOnRightEdge() {
        assertEquals(
            1910,
            calculateConcealedEdgePosition(
                displayWidth = 1920,
                menuWidth = 200,
                visibleHandleWidth = 10,
                isOnLeftEdge = false,
            ),
        )
    }

    @Test
    fun concealedLauncherClampsHandleToMenuWidth() {
        assertEquals(
            0,
            calculateConcealedEdgePosition(
                displayWidth = 1920,
                menuWidth = 40,
                visibleHandleWidth = 80,
                isOnLeftEdge = true,
            ),
        )
    }
}
