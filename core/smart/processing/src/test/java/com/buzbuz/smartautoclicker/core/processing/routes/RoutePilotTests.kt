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
        port.afterRead = { port.position = port.position!! + RoutePoint(1.0, 0.0); if (port.reads >= 20) c.stopped = true }
        val result = RoutePilot(port, c) {}.record(exampleRoute()) { saved += it }
        assertEquals(0, port.movements); assertTrue(result.recordingComplete)
        assertTrue(saved.any { !it.recordingComplete }); assertEquals(result, saved.last())
        assertTrue(result.points.size < port.reads)
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

    @Test fun reverseChecksEndpointNotOriginalStartAndCannotStartFarAway() = runTest {
        val route = exampleRoute().copy(points = listOf(RoutePoint(10.0, 10.0), RoutePoint(50.0, 10.0)))
        val port = FakePort { testScheduler.currentTime }
        val c = RouteRunControl()
        var blocked: RouteProgress? = null
        RoutePilot(port, c) { if (it.message == RouteMessage.WRONG_START) { blocked = it; c.stopped = true } }
            .replay(route, returning = true)
        assertEquals(route.points.last(), blocked?.expectedPosition)
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
        assertTrue(port.movements in 1..10)
    }

    @Test fun reverseAtEndpointCanStopBeforeDispatch() = runTest {
        val port = FakePort { testScheduler.currentTime }.apply { position = exampleRoute().points.last() }
        val c = RouteRunControl()
        RoutePilot(port, c) { if (it.count == 1) c.stopped = true }.replay(exampleRoute(), returning = true)
        assertEquals(0, port.movements)
    }
}
