/* SPDX-License-Identifier: GPL-3.0-or-later */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.buzbuz.smartautoclicker.core.processing.routes

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class RoutePilotTests {
    @Test fun slowStartingMovementIsNotRetappedBeforeObservationGrace() = runTest {
        var position = RoutePoint(10.0, 10.0)
        var target = position
        var sentAt: Long? = null
        var taps = 0
        val port = object : RoutePort {
            override fun now() = testScheduler.currentTime
            override suspend fun read(): RoutePoint {
                if (sentAt?.let { now() - it >= 2_400 } == true) { position = target; sentAt = null }
                return position
            }
            override suspend fun move(offset: RoutePoint): Boolean {
                assertNull("Do not replace a delayed but pending movement", sentAt)
                taps++; target = position + offset * .1; sentAt = now()
                return true
            }
        }
        withTimeout(15_000) { RoutePilot(port, RouteRunControl()) {}.replay(exampleRoute()) }
        assertEquals(2, taps)
        assertEquals(exampleRoute().points.last(), position)
    }

    @Test fun positionLossAfterACompletedTapNeverUsesFallbackPoints() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply { applyMovements = false }
        port.afterRead = { if (port.movements > 0) port.position = null }
        val control = RouteRunControl()
        withTimeout(10_000) {
            RoutePilot(port, control) { if (it.message == RouteMessage.LOST_POSITION) control.stopped = true }
                .replay(exampleRoute())
        }
        assertEquals(1, port.movements)
        assertTrue(control.paused)
    }

    @Test fun ignoredGroundTapUsesNearerCollinearPointWithoutSkippingTurn() = runTest {
        var position = RoutePoint(10.0, 10.0)
        val taps = mutableListOf<RoutePoint>()
        val route = exampleRoute()
        val original = route.points.toList()
        val updates = mutableListOf<RouteProgress>()
        val port = object : RoutePort {
            override fun now() = testScheduler.currentTime
            override suspend fun read() = position
            override suspend fun move(offset: RoutePoint): Boolean {
                taps.add(offset)
                if (taps.size != 1) position += offset * .1 // First screen point hits a decoration.
                return true
            }
        }
        withTimeout(15_000) { RoutePilot(port, RouteRunControl(), updates::add).replay(route) }
        assertEquals(RoutePoint(100.0, 0.0), taps[0])
        assertEquals(RoutePoint(70.0, 0.0), taps[1])
        assertEquals(RoutePoint(30.0, 0.0), taps[2])
        assertEquals(RoutePoint(0.0, 100.0), taps[3])
        assertEquals(original, route.points)
        assertEquals(route.points.last(), position)
        assertTrue(updates.any { it.message == RouteMessage.ADJUSTING_STEP && it.stepAttempt == 2 })
        assertTrue(updates.any { it.message == RouteMessage.WAITING_MOVEMENT })
    }

    @Test fun completedGroundGesturesWithoutMovementPauseAfterThreeAttempts() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply { applyMovements = false }
        val control = RouteRunControl()
        var paused: RouteProgress? = null
        withTimeout(15_000) {
            RoutePilot(port, control) { if (it.message == RouteMessage.STUCK) { paused = it; control.stopped = true } }
                .replay(exampleRoute())
        }
        assertEquals(3, port.movements)
        assertTrue(control.paused)
        assertEquals(exampleRoute().points[1], paused?.expectedPosition)
        assertEquals(RoutePoint(10.0, 10.0), paused?.position)
    }

    @Test fun pausingBeforeNearerTapSuppressesDispatchAndCanResumeSafely() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply { applyMovements = false }
        val control = RouteRunControl()
        val job = launch {
            RoutePilot(port, control) { if (it.message == RouteMessage.ADJUSTING_STEP) control.paused = true }
                .replay(exampleRoute())
        }
        advanceTimeBy(8_000); runCurrent()
        assertTrue(control.paused)
        assertEquals(1, port.movements)
        port.applyMovements = true; control.paused = false
        advanceUntilIdle(); job.join()
        assertEquals(exampleRoute().points.last(), port.position)
        assertEquals(3, port.movements)
    }

    @Test fun returnAtOriginCompletesWithoutSendingAMovement() = runTest {
        val port = FakePort { testScheduler.currentTime }
        val updates = mutableListOf<RouteProgress>()
        withTimeout(5_000) { RoutePilot(port, RouteRunControl(), updates::add).replay(exampleRoute(), returning = true) }
        assertEquals(0, port.movements)
        assertEquals(RouteMessage.COMPLETE, updates.last().message)
        assertTrue(port.reads >= 4)
    }

    @Test fun calibrationDoesNotAcceptAStationaryCharacterOrRepeatedNulls() = runTest {
        for (unreadable in listOf(false, true)) {
            val port = FakePort { testScheduler.currentTime }.apply { applyMovements = false }
            port.afterRead = { if (unreadable && port.movements > 0) port.position = null }
            try { RoutePilot(port, RouteRunControl()) {}.calibrate(RoutePoint(100.0, 0.0)); fail("Invalid calibration") }
            catch (failure: RouteFailure) {
                assertEquals(if (unreadable) RouteMessage.LOST_POSITION else RouteMessage.BAD_CALIBRATION, failure.reason)
            }
            assertEquals(1, port.movements)
        }
    }

    @Test fun recordingKeepsSmallTurnsAndNeverUsesCalibrationTargets() = runTest {
        val path = listOf(RoutePoint(10.0, 10.0), RoutePoint(12.0, 10.0),
            RoutePoint(12.0, 12.0), RoutePoint(14.0, 12.0), RoutePoint(14.0, 14.0))
        val port = FakePort { testScheduler.currentTime }
        val control = RouteRunControl()
        port.afterRead = {
            port.position = path[port.reads - 1]
            if (port.reads == path.size) control.stopped = true
        }
        val result = RoutePilot(port, control) {}.record(exampleRoute().copy(calibration = null)) {}
        assertEquals(path, result.points)
        assertTrue(result.recordingComplete)
        assertEquals(0, port.movements)
    }

    @Test fun returnCanJoinTheMiddleAndKeepsEarlierCorners() = runTest {
        val route = exampleRoute()
        val port = FakePort { testScheduler.currentTime }.apply { position = RoutePoint(20.0, 15.0) }
        val updates = mutableListOf<RouteProgress>()
        withTimeout(10_000) { RoutePilot(port, RouteRunControl(), updates::add).replay(route, returning = true) }
        assertEquals(listOf(route.points[1], route.points[0]), port.visited)
        assertEquals(RouteMessage.COMPLETE, updates.last().message)
        assertEquals(exampleRoute(), route)
    }

    @Test fun obstructedCalibrationRejectsACurvedDetour() = runTest {
        val path = listOf(RoutePoint(10.0, 10.0), RoutePoint(10.0, 16.0),
            RoutePoint(15.0, 16.0), RoutePoint(20.0, 16.0), RoutePoint(20.0, 10.0))
        val port = FakePort { testScheduler.currentTime }.apply { applyMovements = false }
        port.afterRead = { if (port.movements > 0) port.position = path[(port.reads - 4).coerceIn(0, path.lastIndex)] }
        try {
            RoutePilot(port, RouteRunControl()) {}.calibrate(RoutePoint(100.0, 0.0))
            fail("Detoured calibration must not become a direction basis")
        } catch (failure: RouteFailure) { assertEquals(RouteMessage.BAD_CALIBRATION, failure.reason) }
        assertEquals(1, port.movements)
    }

    @Test fun denseStraightRecordingDoesNotStopAtEverySample() = runTest {
        val route = exampleRoute().copy(points = (0..10).map { RoutePoint(10.0 + it * 3, 10.0) })
        val original = route.points.toList()
        val port = FakePort { testScheduler.currentTime }
        withTimeout(8_000) { RoutePilot(port, RouteRunControl()) {}.replay(route) }
        assertEquals(route.points.last(), port.position)
        assertEquals(4, port.movements)
        assertEquals(original, route.points)
        assertTrue(port.visited.zipWithNext().all { (a, b) -> b.x > a.x })
    }

    private class FakePort(val clock: () -> Long) : RoutePort {
        override var epoch: Long = 0
        var position: RoutePoint? = RoutePoint(10.0, 10.0)
        var movements = 0
        val visited = mutableListOf<RoutePoint>()
        var applyMovements = true
        var reads = 0
        var moveSucceeds = true
        var filter: RouteCoordinateFilter? = null
        var resets = 0
        var afterRead: (() -> Unit)? = null
        override fun now() = clock()
        override suspend fun read(): RoutePoint? { reads++; afterRead?.invoke(); return if (filter == null) position else filter!!.accept(position) }
        override fun resetObservation() { resets++; filter?.reset() }
        override suspend fun move(offset: RoutePoint): Boolean {
            movements++
            if (moveSucceeds && applyMovements) position = position?.plus(offset * .1)
            position?.let(visited::add)
            return moveSucceeds
        }
    }

    @Test fun replayArrivesInOrderAndReturns() = runTest {
        val port = FakePort { testScheduler.currentTime }; val updates = mutableListOf<RouteProgress>()
        RoutePilot(port, RouteRunControl(), updates::add).replay(exampleRoute())
        assertEquals(exampleRoute().points.last(), port.position)
        assertEquals(2, port.movements)
        assertEquals(RouteMessage.COMPLETE, updates.last().message)
    }
    @Test fun badStartPausesWithoutClicking() = runTest {
        val port = FakePort { testScheduler.currentTime }; port.position = RoutePoint(200.0, 200.0)
        val c = RouteRunControl()
        RoutePilot(port, c) { if (it.message == RouteMessage.WRONG_START) c.stopped = true }.replay(exampleRoute())
        assertEquals(0, port.movements); assertTrue(c.paused)
    }
    @Test fun returningToStartAfterWrongStartCanResume() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply {
            position = RoutePoint(200.0, 200.0)
            filter = RouteCoordinateFilter()
        }
        val control = RouteRunControl()
        var pauses = 0
        withTimeout(10_000) {
            RoutePilot(port, control) { update ->
                if (update.message == RouteMessage.WRONG_START && ++pauses == 1) launch {
                    delay(800)
                    port.position = exampleRoute().points.first()
                    control.paused = false
                }
            }.replay(exampleRoute())
        }
        assertEquals(1, pauses)
        assertEquals(1, port.resets)
        assertEquals(2, port.movements)
        assertEquals(exampleRoute().points.last(), port.position)
    }
    @Test fun wrongStartReportsActualPositionInsteadOfUnreadable() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply { position = RoutePoint(200.0, 200.0) }
        val control = RouteRunControl()
        var paused: RouteProgress? = null
        RoutePilot(port, control) {
            if (it.message == RouteMessage.WRONG_START) { paused = it; control.stopped = true }
        }.replay(exampleRoute())
        assertEquals(port.position, paused?.position)
        assertEquals(exampleRoute().points.first(), paused?.expectedPosition)
        assertEquals(exampleRoute().entryRadius(), paused!!.allowedDistance!!, .001)
    }
    @Test fun movingAwayDuringPauseStillCannotCutAcrossTheMap() = runTest {
        val port = FakePort { testScheduler.currentTime }
        val control = RouteRunControl()
        var interrupted = false
        var guard: RouteProgress? = null
        withTimeout(10_000) {
            RoutePilot(port, control) {
                if (!interrupted && it.message == RouteMessage.REPLAYING && it.count == 1) {
                    interrupted = true
                    control.paused = true
                    launch { delay(800); port.position = RoutePoint(200.0, 200.0); control.paused = false }
                }
                if (it.message == RouteMessage.POSITION_JUMP) { guard = it; control.stopped = true }
            }.replay(exampleRoute())
        }
        assertEquals(RoutePoint(200.0, 200.0), guard?.position)
        assertEquals(exampleRoute().points.first(), guard?.expectedPosition)
        assertEquals(0, port.movements)
    }
    @Test fun coordinateLossDoesNotGenerateMoves() = runTest {
        val port = FakePort { testScheduler.currentTime }; port.position = null
        val c = RouteRunControl()
        RoutePilot(port, c) { if (it.message == RouteMessage.LOST_POSITION) c.stopped = true }.replay(exampleRoute())
        assertTrue(c.paused); assertEquals(0, port.movements)
    }
    @Test fun rejectedGesturePausesInsteadOfRetryingBlindly() = runTest {
        val port = FakePort { testScheduler.currentTime }; port.moveSucceeds = false
        val c = RouteRunControl()
        RoutePilot(port, c) { if (it.message == RouteMessage.GESTURE_FAILED) c.stopped = true }.replay(exampleRoute())
        assertTrue(c.paused); assertEquals(1, port.movements)
    }
    @Test fun pauseAndResumeKeepControlResponsive() = runTest {
        val port = FakePort { testScheduler.currentTime }; val c = RouteRunControl().apply { paused = true }
        val job = launch { RoutePilot(port, c) {}.replay(exampleRoute()) }
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, port.movements)
        c.paused = false; advanceUntilIdle(); job.join()
        assertEquals(2, port.movements)
    }
    @Test fun stopBetweenObservationAndDispatchSuppressesMovement() = runTest {
        val port = FakePort { testScheduler.currentTime }; val c = RouteRunControl()
        RoutePilot(port, c) { if (port.reads >= 3) c.stopped = true }.replay(exampleRoute())
        assertEquals(0, port.movements)
    }
    @Test fun cancellationStopsFurtherReadsAndMovements() = runTest {
        val port = FakePort { testScheduler.currentTime }; val c = RouteRunControl()
        val job = launch { RoutePilot(port, c) {}.replay(exampleRoute()) }
        advanceTimeBy(900); job.cancelAndJoin(); val readCount = port.reads
        advanceTimeBy(20_000); assertEquals(readCount, port.reads); assertEquals(0, port.movements)
    }
    @Test fun recordingUsesNoGesturesAndSavesDraftsThenCompletedRoute() = runTest {
        val port = FakePort { testScheduler.currentTime }; val c = RouteRunControl()
        val saved = mutableListOf<RecordedRoute>()
        port.afterRead = { port.position = port.position!! + RoutePoint(1.0, 0.0); if (port.reads >= 30) c.stopped = true }
        val result = RoutePilot(port, c) {}.record(exampleRoute()) { saved += it }
        assertEquals(0, port.movements); assertTrue(result.recordingComplete)
        assertTrue(saved.any { !it.recordingComplete }); assertEquals(result, saved.last())
        assertEquals(port.reads, result.points.size) // One-unit samples are no longer discarded.
    }
    @Test fun interruptionSavesNonReplayableDraft() = runTest {
        val port = FakePort { testScheduler.currentTime }; val c = RouteRunControl()
        val saved = mutableListOf<RecordedRoute>()
        port.afterRead = { if (port.reads > 3) port.position = null }
        try { RoutePilot(port, c) {}.record(exampleRoute()) { saved += it }; fail("Expected lost position") }
        catch (e: RouteFailure) { assertEquals(RouteMessage.LOST_POSITION, e.reason) }
        assertFalse(saved.last().recordingComplete); assertEquals(0, port.movements)
    }
    @Test fun calibratesOneBoundedMovement() = runTest {
        val port = FakePort { testScheduler.currentTime }
        val sample = RoutePilot(port, RouteRunControl()) {}.calibrate(RoutePoint(100.0, 0.0))
        assertEquals(RoutePoint(10.0, 0.0), sample.mapDelta); assertEquals(1, port.movements)
    }
    @Test fun previewNeverMovesCharacter() = runTest {
        val port = FakePort { testScheduler.currentTime }; val c = RouteRunControl()
        port.afterRead = { if (port.reads >= 10) c.stopped = true }
        RoutePilot(port, c) {}.preview(); assertEquals(0, port.movements)
    }
    @Test fun debuggerResumeRequiresFreshArrivalConfirmations() = runTest {
        val port = FakePort { testScheduler.currentTime }
        val control = RouteRunControl()
        val route = exampleRoute().copy(points = listOf(RoutePoint(10.0, 10.0), RoutePoint(10.0, 10.0)))
        var completedAt = 0
        port.afterRead = { if (port.reads == 2) port.epoch++ }
        RoutePilot(port, control) { if (it.message == RouteMessage.COMPLETE) completedAt = port.reads }.replay(route)
        // A hit before a pause must not combine with one after the pause to advance a waypoint.
        assertTrue(completedAt >= 5)
        assertEquals(0, port.movements)
    }

    @Test fun nearStartAutomaticallyApproachesBeforeFollowingEveryWaypoint() = runTest {
        val route = exampleRoute()
        val port = FakePort { testScheduler.currentTime }.apply { position = RoutePoint(3.0, 10.0) }
        val updates = mutableListOf<RouteProgress>()
        withTimeout(20_000) { RoutePilot(port, RouteRunControl(), updates::add).replay(route) }
        assertEquals(route.points, port.visited)
        assertEquals(3, port.movements)
        assertTrue(updates.any { it.message == RouteMessage.APPROACHING_START && it.expectedPosition == route.points.first() })
        assertEquals(RouteMessage.COMPLETE, updates.last().message)
    }

    @Test fun returningNearEndpointVisitsCornerThenStartWithoutChangingRecording() = runTest {
        val route = exampleRoute()
        val saved = route.copy(points = route.points.toList())
        val port = FakePort { testScheduler.currentTime }.apply { position = RoutePoint(27.0, 20.0) }
        withTimeout(20_000) { RoutePilot(port, RouteRunControl()) {}.replay(route, returning = true) }
        assertEquals(route.points.reversed(), port.visited)
        assertEquals(route.points.first(), port.position)
        assertEquals(saved, route)
    }

    @Test fun reverseCannotJoinFarAwayFromTheRecordedCorridor() = runTest {
        val route = exampleRoute().copy(points = listOf(RoutePoint(10.0, 10.0), RoutePoint(50.0, 10.0)))
        val port = FakePort { testScheduler.currentTime }.apply { position = RoutePoint(30.0, 100.0) }
        val c = RouteRunControl()
        var blocked: RouteProgress? = null
        RoutePilot(port, c) { if (it.message == RouteMessage.WRONG_START) { blocked = it; c.stopped = true } }
            .replay(route, returning = true)
        assertEquals(RoutePoint(30.0, 10.0), blocked?.expectedPosition)
        assertEquals(0, port.movements)
    }

    @Test fun lostCoordinatesDuringReturnPauseWithoutMovement() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply { position = null }
        val c = RouteRunControl()
        RoutePilot(port, c) { if (it.message == RouteMessage.LOST_POSITION) c.stopped = true }
            .replay(exampleRoute(), returning = true)
        assertTrue(c.paused)
        assertEquals(0, port.movements)
    }

    @Test fun approachCanBePausedBeforeAnyGestureAndThenContinued() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply { position = RoutePoint(3.0, 10.0) }
        val c = RouteRunControl()
        var pausedOnce = false
        withTimeout(30_000) {
            RoutePilot(port, c) {
                if (!pausedOnce && it.message == RouteMessage.APPROACHING_START) {
                    pausedOnce = true; c.paused = true
                    launch { delay(5_000); assertEquals(0, port.movements); c.paused = false }
                }
            }.replay(exampleRoute())
        }
        assertEquals(3, port.movements)
    }

    @Test fun blockedApproachStillStopsAfterNoProgress() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply {
            position = RoutePoint(3.0, 10.0); applyMovements = false
        }
        val c = RouteRunControl()
        var stuck = false
        withTimeout(20_000) {
            RoutePilot(port, c) { if (it.message == RouteMessage.STUCK) { stuck = true; c.stopped = true } }.replay(exampleRoute())
        }
        assertTrue(stuck); assertTrue(c.paused)
        assertEquals(RoutePoint(3.0, 10.0), port.position)
        assertTrue(port.movements in 1..20)
    }

    @Test fun reverseAtEndpointCanStopBeforeDispatch() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply { position = exampleRoute().points.last() }
        val c = RouteRunControl()
        RoutePilot(port, c) { if (it.count == 1) c.stopped = true }.replay(exampleRoute(), returning = true)
        assertEquals(0, port.movements)
    }

    @Test fun denseCornerRouteAndReturnKeepTheTurnAndBothEndpoints() = runTest {
        val points = (0..10).map { RoutePoint(10.0 + it * 3, 10.0) } +
            (1..10).map { RoutePoint(40.0, 10.0 + it * 3) }
        val route = exampleRoute().copy(points = points)
        val port = FakePort { testScheduler.currentTime }
        val control = RouteRunControl()
        withTimeout(15_000) { RoutePilot(port, control) {}.replay(route) }
        assertTrue(port.visited.contains(RoutePoint(40.0, 10.0)))
        assertEquals(points.last(), port.position)
        assertEquals(8, port.movements)
        assertTrue(port.visited.all { it.y == 10.0 || it.x == 40.0 })
        port.visited.clear()
        withTimeout(15_000) { RoutePilot(port, control) {}.replay(route, returning = true) }
        assertEquals(points.first(), port.position)
        assertTrue(port.visited.contains(RoutePoint(40.0, 10.0)))
        assertEquals(points, route.points)
    }

    @Test fun movingCharacterDoesNotGetRetargetedBeforeItSettles() = runTest {
        var position = RoutePoint(10.0, 10.0)
        var destination = position
        var gestures = 0
        val route = exampleRoute()
        val port = object : RoutePort {
            override fun now() = testScheduler.currentTime
            override suspend fun read(): RoutePoint {
                val delta = destination - position
                val distance = delta.distance(RoutePoint(0.0, 0.0))
                if (distance > 0) position += delta * minOf(1.0, 2.0 / distance)
                return position
            }
            override suspend fun move(offset: RoutePoint): Boolean {
                assertEquals("Do not replace a gesture still walking", destination, position)
                gestures++
                destination = position + offset * .1
                return true
            }
        }
        withTimeout(15_000) { RoutePilot(port, RouteRunControl()) {}.replay(route) }
        assertEquals(route.points.last(), position)
        assertEquals(2, gestures)
    }
}
