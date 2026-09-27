/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import com.buzbuz.smartautoclicker.core.processing.routes.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class RouteViewModelTests {
    private fun configuredModel() = RouteViewModel(mock(), mock()).apply {
        newRoute(1920, 1080, "Route")
        route = route!!.copy(xArea = RouteArea(0, 0, 50, 50), yArea = RouteArea(50, 0, 100, 50),
            mapArea = RouteArea(0, 50, 200, 100), mapPng = "test", anchor = RoutePoint(960.0, 540.0))
    }
    @Test fun emptyFormCannotRun() {
        val m = RouteViewModel(mock(), mock()); m.newRoute(1920, 1080, "Route")
        assertFalse(m.configured())
    }
    @Test fun overlappingCoordinateFieldsCannotRun() {
        val m = configuredModel(); assertTrue(m.configured())
        m.route = m.route!!.copy(yArea = RouteArea(0, 0, 50, 50))
        assertFalse(m.configured())
    }
    @Test fun calibrationRequiresTwoNonParallelDirections() {
        val m = configuredModel()
        val a = RouteCalibrationSample(RoutePoint(100.0, 0.0), RoutePoint(10.0, 0.0))
        assertTrue(m.setSample(true, a)); assertNull(m.route!!.calibration)
        assertFalse(m.setSample(false, a)); assertNull(m.route!!.calibration)
        assertTrue(m.setSample(false, RouteCalibrationSample(RoutePoint(0.0, 100.0), RoutePoint(0.0, 10.0))))
        assertNotNull(m.route!!.calibration)
        m.clearCalibration(); assertNull(m.route!!.calibration); assertNull(m.sampleA)
    }
}
