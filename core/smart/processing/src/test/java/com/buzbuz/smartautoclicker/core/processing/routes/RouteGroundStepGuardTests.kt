/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import org.junit.Assert.*
import org.junit.Test

class RouteGroundStepGuardTests {
    private val origin = RoutePoint(10.0, 10.0)
    private val destination = RoutePoint(20.0, 10.0)

    @Test fun noMovementWaitsThenTriesOnlyThreeNearerPoints() {
        val guard = RouteGroundStepGuard()
        listOf(1.0, .7, .4).forEachIndexed { i, scale ->
            val at = i * 3_000L
            assertEquals(RouteGroundStepGuard.Decision.Tap(scale, i + 1), guard.observe(destination, origin, at))
            guard.dispatched(origin, at)
            assertEquals(RouteGroundStepGuard.Decision.Wait, guard.observe(destination, origin, at + 2_999))
        }
        assertEquals(RouteGroundStepGuard.Decision.Blocked, guard.observe(destination, origin, 9_000))
    }

    @Test fun onlyActualForwardDisplacementResetsTheBudget() {
        val guard = RouteGroundStepGuard()
        guard.observe(destination, origin, 0); guard.dispatched(origin, 0)
        assertEquals(RouteGroundStepGuard.Decision.Tap(.7, 2), guard.observe(destination, origin, 3_000))
        assertEquals(RouteGroundStepGuard.Decision.Tap(.7, 2), guard.observe(destination, RoutePoint(9.0, 10.0), 3_000))
        assertEquals(RouteGroundStepGuard.Decision.Tap(.7, 2), guard.observe(destination, RoutePoint(10.0, 11.0), 3_000))
        assertEquals(RouteGroundStepGuard.Decision.Tap(.7, 2), guard.observe(destination, RoutePoint(10.4, 10.0), 3_000))
        assertEquals(RouteGroundStepGuard.Decision.Tap(1.0, 1), guard.observe(destination, RoutePoint(11.0, 10.0), 3_000))
    }

    @Test fun changedTargetOrExplicitResumeStartsANewBudget() {
        val guard = RouteGroundStepGuard()
        guard.observe(destination, origin, 0); guard.dispatched(origin, 0)
        assertEquals(RouteGroundStepGuard.Decision.Tap(1.0, 1), guard.observe(RoutePoint(20.0, 20.0), origin, 1))
        guard.dispatched(origin, 1); guard.reset()
        assertEquals(RouteGroundStepGuard.Decision.Tap(1.0, 1), guard.observe(destination, origin, 2))
    }
}
