/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import android.graphics.Rect
import com.buzbuz.smartautoclicker.core.processing.routes.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
class RouteSelectionTests {
    private fun route() = RouteViewModel(mock(), mock()).apply { newRoute(1920, 1080, "Test") }.route!!

    @Test fun newRouteHasExplicitVisibleSelectionWithoutAnyEditedCondition() {
        val route = route()
        for (kind in 0..3) {
            val selection = route.selectionFor(kind)
            assertFalse(selection.initialArea.isEmpty)
            assertTrue(Rect(0, 0, 1920, 1080).contains(selection.initialArea))
            assertEquals(Rect(0, 0, 8, 8), selection.minimalArea)
        }
    }

    @Test fun reopenRestoresEachRoutesOwnArea() {
        val r = route().copy(xArea = RouteArea(40, 110, 185, 175), yArea = RouteArea(230, 110, 385, 175),
            mapArea = RouteArea(40, 30, 360, 105), minimap = RouteMinimap(RouteArea(500, 30, 692, 222)))
        assertEquals(Rect(40, 110, 185, 175), r.selectionFor(0).initialArea)
        assertEquals(Rect(230, 110, 385, 175), r.selectionFor(1).initialArea)
        assertEquals(Rect(40, 30, 360, 105), r.selectionFor(2).initialArea)
        assertEquals(Rect(500, 30, 692, 222), r.selectionFor(3).initialArea)
    }

    @Test fun wrongStartTextContainsCurrentExpectedAndDistanceNotUnreadable() {
        val text = RouteProgress(RouteMessage.WRONG_START, RoutePoint(110.0, 110.0),
            expectedPosition = RoutePoint(100.0, 100.0), allowedDistance = 5.0).statusText(RuntimeEnvironment.getApplication())
        assertTrue(text.contains("110, 110"))
        assertTrue(text.contains("100, 100"))
        assertTrue(text.contains("14.1"))
        assertTrue(text.contains("5.0"))
        assertTrue(text.contains("复查继续"))
        assertFalse(text.contains("未识别"))
    }

    @Test fun missingObservationIsNotPresentedAsAnActualPosition() {
        val text = RouteProgress(RouteMessage.LOST_POSITION, count = 3).statusText(RuntimeEnvironment.getApplication())
        assertTrue(text.contains("未识别"))
        assertFalse(text.contains("需返回"))
    }
    @Test fun approachStatusDoesNotTellUserToWalkManuallyOrResume() {
        val text = RouteProgress(RouteMessage.APPROACHING_START, RoutePoint(107.0, 100.0),
            expectedPosition = RoutePoint(100.0, 100.0)).statusText(RuntimeEnvironment.getApplication())
        assertTrue(text.contains("自动接近起点"))
        assertTrue(text.contains("100, 100"))
        assertTrue(text.contains("小步移动"))
        assertFalse(text.contains("复查继续"))
    }
    @Test fun returnStatusNamesEndpointAndCompletionNamesStart() {
        val context = RuntimeEnvironment.getApplication()
        val blocked = RouteProgress(RouteMessage.WRONG_START, RoutePoint(200.0, 200.0),
            expectedPosition = RoutePoint(110.0, 110.0), allowedDistance = 10.0, returning = true).statusText(context)
        assertTrue(blocked.contains("距离录制终点过远"))
        assertTrue(blocked.contains("终点允许范围"))
        assertFalse(blocked.contains("距离录制起点"))
        assertTrue(RouteProgress(RouteMessage.APPROACHING_START, returning = true).statusText(context).contains("准备返程"))
        assertTrue(RouteProgress(RouteMessage.REPLAYING, returning = true).statusText(context).contains("沿原路返回"))
        val completed = RouteProgress(RouteMessage.COMPLETE, returning = true).statusText(context)
        assertTrue(completed.contains("已返回录制起点"))
        assertFalse(completed.contains("到达路线终点"))
    }
}
