/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import com.buzbuz.smartautoclicker.core.processing.routes.*
import com.buzbuz.smartautoclicker.feature.smart.config.R
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

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

    @Test fun recordedRouteReportsBothMissingCalibrationStepsWithoutDiscardingPoints() {
        val m = recordedModel()
        assertEquals(listOf(R.string.route_need_calibration_a, R.string.route_need_calibration_b),
            m.operationIssues(RouteOperation.REPLAY))
        assertTrue(m.operationIssues(RouteOperation.RECORD).isEmpty())
        assertTrue(m.operationIssues(RouteOperation.PREVIEW).isEmpty())
        assertEquals(2, m.route!!.points.size)
    }

    @Test fun completingCalibrationAfterRecordingMakesOriginalRouteReplayableAndSavesIt() = runTest {
        val m = recordedModel()
        val original = m.route!!
        m.setSample(true, sampleA)
        assertEquals(listOf(R.string.route_need_calibration_b), m.operationIssues(RouteOperation.REPLAY))
        assertFalse(m.saveCalibratedRecording())
        verifyNoInteractions(m.store)
        m.setSample(false, sampleB)
        assertTrue(m.operationIssues(RouteOperation.REPLAY).isEmpty())
        assertTrue(m.saveCalibratedRecording())
        verify(m.store).save(m.route!!)
        assertEquals(original.id, m.route!!.id)
        assertEquals(original.points, m.route!!.points)
    }

    @Test fun loadingRouteRestoresCalibrationAndClearsPreviousTransientSelection() {
        val m = recordedModel()
        val saved = m.route!!.copy(calibration = RouteCalibration(sampleA, sampleB))
        m.targetA = RoutePoint(500.0, 500.0)
        m.load(saved)
        assertNull(m.targetA)
        assertTrue(m.operationIssues(RouteOperation.REPLAY).isEmpty())
        m.load(saved.copy(calibration = null))
        assertNull(m.sampleA)
        assertNull(m.sampleB)
        assertEquals(2, m.operationIssues(RouteOperation.REPLAY).size)
    }

    @Test fun invalidFormReportsSpecificFieldsAndOverlaps() {
        val m = configuredModel()
        m.route = m.route!!.copy(name = "", mapPng = "", anchor = RoutePoint(-1.0, -1.0), yArea = m.route!!.xArea)
        assertEquals(listOf(R.string.route_need_name, R.string.route_need_map_capture,
            R.string.route_need_anchor, R.string.route_need_separate_areas), m.configurationIssues())
        m.newRoute(1920, 1080, "New")
        assertTrue(R.string.route_need_x in m.configurationIssues())
        assertTrue(R.string.route_need_y in m.configurationIssues())
        assertTrue(R.string.route_need_map_area in m.configurationIssues())
    }

    @Test fun draftAndInsufficientPointsAreDifferentFromMissingCalibration() {
        val m = recordedModel()
        m.route = m.route!!.copy(calibration = RouteCalibration(sampleA, sampleB), recordingComplete = false)
        assertEquals(listOf(R.string.route_need_recording), m.operationIssues(RouteOperation.REPLAY))
        m.route = m.route!!.copy(recordingComplete = true, points = listOf(RoutePoint(100.0, 100.0)))
        assertEquals(listOf(R.string.route_need_points), m.operationIssues(RouteOperation.REPLAY))
    }

    @Test fun minimapTestIsRequiredForMovementButNotPreview() {
        val m = recordedModel()
        m.route = m.route!!.copy(positionMode = RoutePositionMode.MINIMAP,
            minimap = RouteMinimap(RouteArea(500, 30, 692, 222), keyframes = listOf(
                RouteKeyframe(RoutePoint(50000.0, 50000.0), "A".repeat(MINIMAP_BYTES_BASE64)))))
        assertTrue(m.operationIssues(RouteOperation.PREVIEW).isEmpty())
        assertEquals(listOf(R.string.route_need_test), m.operationIssues(RouteOperation.CALIBRATE))
        assertTrue(R.string.route_need_test in m.operationIssues(RouteOperation.REPLAY))
    }

    @Test fun invalidHoldReportsIssueAndDoesNotSave() = runTest {
        val m = recordedModel()
        m.route = m.route!!.copy(calibration = RouteCalibration(sampleA, sampleB), joystickDurationMs = 0)
        assertEquals(listOf(R.string.route_joystick_duration), m.configurationIssues())
        assertFalse(m.saveCalibratedRecording())
        verifyNoInteractions(m.store)
    }

    private fun recordedModel() = configuredModel().apply {
        route = route!!.copy(recordingComplete = true,
            points = listOf(RoutePoint(100.0, 100.0), RoutePoint(110.0, 100.0)))
    }
    private val sampleA = RouteCalibrationSample(RoutePoint(100.0, 0.0), RoutePoint(10.0, 0.0))
    private val sampleB = RouteCalibrationSample(RoutePoint(0.0, 100.0), RoutePoint(0.0, 10.0))
}
