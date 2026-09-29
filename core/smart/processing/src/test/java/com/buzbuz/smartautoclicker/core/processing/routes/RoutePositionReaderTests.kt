/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import android.graphics.Bitmap
import android.graphics.Rect
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.detection.NumberFormatType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RoutePositionReaderTests {
    private val route = RecordedRoute("00000000-0000-0000-0000-000000000001", "Test", 200, 100,
        RouteArea(10, 40, 30, 60), RouteArea(35, 40, 55, 60), RouteArea(10, 10, 60, 30), "unused", RoutePoint(100.0, 70.0))
    private val detector = mock<ImageDetector>()
    private val frame = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
    private val map = Bitmap.createBitmap(50, 20, Bitmap.Config.ARGB_8888)
    private var mapMatches = true
    private var x = number(154.0)
    private var y = number(147.0)
    private fun number(value: Double?, accepted: Boolean = true, confidence: Double = .99) =
        DetectionResult(isDetected = accepted, confidenceRate = confidence, numberDetected = value)
    private fun reader(preview: Boolean = false) = RoutePositionReader(route, detector, map, preview)

    @Before fun setup() {
        whenever(detector.detectImage(any(), any(), any(), any(), any())).thenAnswer { DetectionResult(isDetected = mapMatches) }
        whenever(detector.detectNumber(eq(Rect(10, 40, 30, 60)), eq(15), eq(NumberFormatType.AUTO))).thenAnswer { x }
        whenever(detector.detectNumber(eq(Rect(35, 40, 55, 60)), eq(15), eq(NumberFormatType.AUTO))).thenAnswer { y }
    }
    @After fun cleanup() { frame.recycle(); map.recycle() }

    @Test fun validNumbersNeedConsecutiveConfirmation() {
        val reader = reader()
        assertNull(reader.read(frame, null))
        assertEquals(RouteReadIssue.WAITING_CONFIRMATION, reader.diagnostics.issue)
        assertEquals(154.0, reader.diagnostics.x!!.value!!, .0)
        assertEquals(RoutePoint(154.0, 147.0), reader.read(frame, null))
        assertEquals(RouteReadIssue.READY, reader.diagnostics.issue)
        assertEquals(true, reader.diagnostics.mapMatched)
        verify(detector, times(2)).releaseScreenBitmap(frame)
    }
    @Test fun wrongMapSkipsOcrDuringMovementOperations() {
        mapMatches = false
        val reader = reader()
        repeat(3) { assertNull(reader.read(frame, null)) }
        assertEquals(RouteReadIssue.MAP_MISMATCH, reader.diagnostics.issue)
        assertNull(reader.diagnostics.x)
        verify(detector, never()).detectNumber(any(), any(), any())
    }
    @Test fun wrongMapPreviewExposesEachDigitButNeverReturnsPosition() {
        mapMatches = false
        val reader = reader(preview = true)
        repeat(3) { assertNull(reader.read(frame, null)) }
        assertEquals(RouteReadIssue.MAP_MISMATCH, reader.diagnostics.issue)
        assertEquals(false, reader.diagnostics.mapMatched)
        assertEquals(154.0, reader.diagnostics.x!!.value!!, .0)
        assertEquals(147.0, reader.diagnostics.y!!.value!!, .0)
        mapMatches = true
        assertNull(reader.read(frame, null)) // Wrong-map candidates did not prime the position filter.
        assertNotNull(reader.read(frame, null))
    }
    @Test fun missingXIsNotReportedAsMissingYOrMap() {
        x = number(null, false)
        val reader = reader()
        assertNull(reader.read(frame, null))
        assertEquals(RouteReadIssue.X_UNREADABLE, reader.diagnostics.issue)
        assertEquals(true, reader.diagnostics.mapMatched)
        assertEquals(147.0, reader.diagnostics.y!!.value!!, .0)
    }
    @Test fun lowConfidenceYRemainsDiagnosticOnly() {
        y = number(147.0, false, .70)
        val reader = reader()
        repeat(3) { assertNull(reader.read(frame, null)) }
        assertEquals(RouteReadIssue.Y_UNREADABLE, reader.diagnostics.issue)
        assertFalse(reader.diagnostics.y!!.accepted)
        assertEquals(.70, reader.diagnostics.y!!.confidence, .0)
    }
    @Test fun bothUnreadableAreExplicit() {
        x = number(null, false); y = x
        val reader = reader()
        assertNull(reader.read(frame, null))
        assertEquals(RouteReadIssue.XY_UNREADABLE, reader.diagnostics.issue)
    }
    @Test fun nonfiniteReadingsCannotBypassGate() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            x = number(value)
            val reader = reader()
            assertNull(reader.read(frame, null))
            assertEquals(RouteReadIssue.X_UNREADABLE, reader.diagnostics.issue)
            assertNull(reader.diagnostics.x!!.value)
        }
    }
    @Test fun fractionalNegativeOrOutOfRangeNumbersNeverBecomePositions() {
        for (value in listOf(154.147, -1.0, 100_001.0)) {
            x = number(value)
            val reader = reader()
            repeat(3) { assertNull(reader.read(frame, null)) }
            assertEquals(RouteReadIssue.INVALID_COORDINATE, reader.diagnostics.issue)
        }
    }
    @Test fun actualZeroIsStillAValidCoordinateNotAMissingValue() {
        x = number(0.0); y = x
        val reader = reader()
        assertNull(reader.read(frame, null))
        assertEquals(RoutePoint(0.0, 0.0), reader.read(frame, null))
    }
    @Test fun overlayOnAnySelectedAreaSkipsAllDetection() {
        val reader = reader(true)
        for (area in listOf(route.xArea, route.yArea, route.mapArea)) {
            assertNull(reader.read(frame, area))
            assertEquals(RouteReadIssue.OVERLAY_BLOCKED, reader.diagnostics.issue)
            assertNull(reader.diagnostics.mapMatched)
        }
        verifyNoInteractions(detector)
    }
    @Test fun blockedOrMissingFrameClearsOldCandidatesAndStartupConfirmation() {
        for (blocked in listOf(false, true)) {
            val reader = reader()
            assertNull(reader.read(frame, null))
            assertNull(reader.read(if (blocked) frame else null, if (blocked) route.xArea else null))
            assertEquals(if (blocked) RouteReadIssue.OVERLAY_BLOCKED else RouteReadIssue.NO_FRAME, reader.diagnostics.issue)
            assertNull(reader.diagnostics.x)
            assertNull(reader.diagnostics.y)
            assertNull(reader.diagnostics.mapMatched)
            assertNull(reader.read(frame, null))
            assertNotNull(reader.read(frame, null))
        }
    }
    @Test fun largeJumpIsDistinctFromUnreadableAndResetRequiresFreshConfirmation() {
        val reader = reader()
        reader.read(frame, null); reader.read(frame, null)
        x = number(300.0)
        assertNull(reader.read(frame, null))
        assertEquals(RouteReadIssue.POSITION_OUTLIER, reader.diagnostics.issue)
        reader.reset()
        assertNull(reader.read(frame, null))
        assertEquals(RouteReadIssue.WAITING_CONFIRMATION, reader.diagnostics.issue)
        assertEquals(RoutePoint(300.0, 147.0), reader.read(frame, null))
    }
    @Test fun nativeFailureStillReleasesScreenshot() {
        whenever(detector.detectImage(any(), any(), any(), any(), any())).thenThrow(IllegalStateException("native failure"))
        assertThrows(IllegalStateException::class.java) { reader().read(frame, null) }
        verify(detector).releaseScreenBitmap(frame)
    }
    @Test fun readableButUnsettledCalibrationTimeoutNeverClaimsPositionIsReady() {
        val ready = RouteReadDiagnostics(RouteReadIssue.READY, true, RouteAxisReading(154.0, .99, true))
        val timeout = ready.positionFailure()
        assertEquals(RouteReadIssue.WAITING_CONFIRMATION, timeout.issue)
        assertEquals(ready.x, timeout.x)
        val mismatch = RouteReadDiagnostics(RouteReadIssue.MAP_MISMATCH, false)
        assertEquals(mismatch, mismatch.positionFailure())
    }
}
