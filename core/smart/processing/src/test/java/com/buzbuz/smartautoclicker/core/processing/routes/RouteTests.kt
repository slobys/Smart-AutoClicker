/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import org.junit.Assert.*
import org.junit.Test

internal fun exampleRoute() = RecordedRoute(
    "11111111-1111-1111-1111-111111111111", "Test route", 1920, 1080,
    RouteArea(50, 20, 100, 60), RouteArea(110, 20, 160, 60), RouteArea(50, 70, 160, 110),
    "test", RoutePoint(960.0, 540.0), calibration = exampleCalibration(),
    points = listOf(RoutePoint(10.0, 10.0), RoutePoint(20.0, 10.0), RoutePoint(20.0, 20.0)), recordingComplete = true,
)
internal fun exampleCalibration() = RouteCalibration(
    RouteCalibrationSample(RoutePoint(100.0, 0.0), RoutePoint(10.0, 0.0)),
    RouteCalibrationSample(RoutePoint(0.0, 100.0), RoutePoint(0.0, 10.0)),
)

class RouteTests {
    @Test fun validRouteAndCalibration() { assertTrue(exampleRoute().valid()); assertTrue(exampleCalibration().valid()) }
    @Test fun mapsWorldCoordinatesToScreenAndLimitsStepSize() {
        val c = exampleCalibration()
        assertEquals(RoutePoint(30.0, 40.0), c.screenOffset(RoutePoint(3.0, 4.0)))
        assertEquals(100.0, c.screenOffset(RoutePoint(300.0, 400.0)).distance(RoutePoint(0.0, 0.0)), .001)
        assertEquals(RoutePoint(0.0, 0.0), c.screenOffset(RoutePoint(0.0, 0.0)))
    }
    @Test fun supportsIsometricAndReversedAxes() {
        val c = RouteCalibration(RouteCalibrationSample(RoutePoint(80.0, 40.0), RoutePoint(10.0, 0.0)),
            RouteCalibrationSample(RoutePoint(-80.0, 40.0), RoutePoint(0.0, -10.0)))
        assertTrue(c.valid())
        assertEquals(RoutePoint(0.0, 40.0), c.screenOffset(RoutePoint(5.0, -5.0)))
    }
    @Test fun rejectsParallelCalibrationAndInvalidCoordinates() {
        val c = exampleCalibration()
        assertFalse(c.copy(second = c.first).valid())
        assertFalse(c.copy(first = c.first.copy(mapDelta = RoutePoint(.1, 0.0))).valid())
        assertFalse(exampleRoute().copy(anchor = RoutePoint(Double.NaN, 0.0)).valid())
        assertFalse(exampleRoute().copy(id = "../../private").valid())
        assertFalse(exampleRoute().copy(points = List(2001) { RoutePoint(1.0, 1.0) }).valid())
        assertFalse(exampleRoute().copy(points = listOf(RoutePoint(-1.0, 2.0))).valid())
    }
    @Test fun rejectsOutOfBoundsAndHugeAreas() {
        assertFalse(RouteArea(-1, 0, 20, 20).valid(100, 100))
        assertFalse(RouteArea(0, 0, 800, 800).valid(1000, 1000))
        assertFalse(RouteArea(5, 5, 4, 4).valid(100, 100))
    }
    @Test fun filterRequiresInitialConfirmationAndRejectsJumps() {
        val filter = RouteCoordinateFilter()
        assertNull(filter.accept(RoutePoint(10.0, 10.0)))
        assertEquals(RoutePoint(10.0, 11.0), filter.accept(RoutePoint(10.0, 11.0)))
        assertNull(filter.accept(RoutePoint(999.0, 11.0)))
        assertNull(filter.accept(null))
        assertNull(filter.accept(RoutePoint(Double.NaN, 1.0)))
        assertNull(filter.accept(RoutePoint(-1.0, 1.0)))
        assertEquals(RoutePoint(11.0, 11.0), filter.accept(RoutePoint(11.0, 11.0)))
    }
    @Test fun followerRequiresTwoObservationsAtEveryCheckpoint() {
        val r = exampleRoute(); val f = RouteFollower(r.points, r.tolerance)
        r.points.forEachIndexed { i, p ->
            assertEquals(RouteFollower.Decision.Wait, f.observe(p, i * 1000L))
            assertEquals(i, f.index)
            val second = f.observe(p, i * 1000L + 400)
            assertEquals(i + 1, f.index)
            assertEquals(if (i == r.points.lastIndex) RouteFollower.Decision.Complete else RouteFollower.Decision.Wait, second)
        }
    }
    @Test fun resetAllowsConfirmedReturnOverFortyUnitsAwayButNotASingleJump() {
        val filter = RouteCoordinateFilter()
        val end = RoutePoint(200.0, 200.0)
        val start = RoutePoint(10.0, 10.0)
        filter.accept(end); assertEquals(end, filter.accept(end))
        assertNull(filter.accept(start))
        filter.reset()
        assertNull(filter.accept(start))
        assertEquals(start, filter.accept(start))
        assertNull(filter.accept(end))
    }
    @Test fun wrongStartNeverProducesAMove() {
        val r = exampleRoute(); val f = RouteFollower(r.points, 2.0)
        assertEquals(RouteFollower.Decision.Pause(RouteFollower.Reason.WRONG_START), f.observe(RoutePoint(100.0, 100.0), 0))
    }
    @Test fun lostObservationBreaksArrivalConfirmation() {
        val r = exampleRoute(); val f = RouteFollower(r.points, 2.0)
        f.observe(r.points.first(), 0); f.breakConfirmation()
        assertEquals(RouteFollower.Decision.Wait, f.observe(r.points.first(), 400)); assertEquals(0, f.index)
    }
    @Test fun noProgressPausesAfter15Seconds() {
        val r = exampleRoute(); val f = RouteFollower(r.points, 2.0)
        f.observe(r.points[0], 0); f.observe(r.points[0], 400); f.observe(r.points[0], 800)
        assertEquals(RouteFollower.Decision.Pause(RouteFollower.Reason.STUCK), f.observe(r.points[0], 15_800))
    }
    @Test fun slowProgressStillHas60SecondWaypointLimit() {
        val f = RouteFollower(listOf(RoutePoint(0.0, 0.0), RoutePoint(100.0, 0.0)), 2.0)
        f.observe(RoutePoint(0.0, 0.0), 0); f.observe(RoutePoint(0.0, 0.0), 400)
        for (i in 1..5) f.observe(RoutePoint(i.toDouble(), 0.0), i * 10_000L)
        assertEquals(RouteFollower.Decision.Pause(RouteFollower.Reason.TIMEOUT), f.observe(RoutePoint(6.0, 0.0), 60_400))
    }
    @Test fun pauseTimeDoesNotConsumeTimeoutOrConfirmArrival() {
        val r = exampleRoute(); val f = RouteFollower(r.points, 2.0)
        f.observe(r.points[0], 0); f.resume(100_000)
        assertEquals(RouteFollower.Decision.Wait, f.observe(r.points[0], 100_400)); assertEquals(0, f.index)
    }
}
