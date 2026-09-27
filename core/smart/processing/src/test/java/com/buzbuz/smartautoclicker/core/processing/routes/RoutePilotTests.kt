/* SPDX-License-Identifier: GPL-3.0-or-later */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.buzbuz.smartautoclicker.core.processing.routes

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class RoutePilotTests {
    private class FakePort(val clock: () -> Long) : RoutePort {
        var position: RoutePoint? = RoutePoint(10.0, 10.0)
        var movements = 0
        var reads = 0
        var moveSucceeds = true
        var afterRead: (() -> Unit)? = null
        override fun now() = clock()
        override suspend fun read(): RoutePoint? { reads++; afterRead?.invoke(); return position }
        override suspend fun move(offset: RoutePoint): Boolean {
            movements++
            if (moveSucceeds) position = position?.plus(offset * .1)
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
}
