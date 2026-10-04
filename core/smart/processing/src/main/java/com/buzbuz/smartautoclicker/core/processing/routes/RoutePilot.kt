/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

enum class RouteMessage {
    PREPARING, READING, RECORDING, APPROACHING_START, REPLAYING, PAUSED, COMPLETE, SAVED_DRAFT, DONE,
    WRONG_START, POSITION_JUMP, STUCK, TIMEOUT, LOST_POSITION, BAD_CALIBRATION, RESOLUTION_CHANGED,
    SERVICE_STOPPED, MODELS_MISSING, GESTURE_FAILED, LIMIT_REACHED, FAILED, LOCALIZATION_PASSED, LOCALIZATION_WEAK,
}
data class RouteProgress(val message: RouteMessage, val position: RoutePoint? = null, val count: Int = 0,
    val confidence: Double? = null, val trace: List<RoutePoint> = emptyList(), val observations: Int? = null,
    val expectedPosition: RoutePoint? = null, val allowedDistance: Double? = null, val returning: Boolean = false,
    val diagnostics: RouteReadDiagnostics? = null)

class RouteRunControl {
    @Volatile var stopped = false
    @Volatile var paused = false
    @Volatile var blockedArea: RouteArea? = null
}

internal interface RoutePort {
    val epoch: Long get() = 0L
    fun now(): Long
    /** Null means no reliable observation; never interpreted as arrival or as coordinate zero. */
    suspend fun read(): RoutePoint?
    fun resetObservation() = Unit
    suspend fun move(offset: RoutePoint): Boolean
    suspend fun move(motion: RouteMotion): Boolean = move(motion.offset)
}

class RouteFailure(val reason: RouteMessage, val diagnostics: RouteReadDiagnostics? = null) : Exception(reason.name)

/** At least 20 observations and 12 px travelled with >=80% valid samples. Never dispatches input. */
internal suspend fun testMinimap(port: RoutePort, control: RouteRunControl, report: (RouteProgress) -> Unit): Boolean {
    val start = port.now()
    var first: RoutePoint? = null
    var good = 0; var total = 0; var travelled = 0.0
    while (!control.stopped && port.now() - start < 120_000) {
        delay(400)
        val p = port.read()
        total++
        if (p != null) {
            good++
            if (first == null) first = p
            travelled = maxOf(travelled, first.distance(p))
        }
        val passed = good >= 20 && good.toDouble() / total >= .8 && travelled >= 12
        report(RouteProgress(if (passed) RouteMessage.LOCALIZATION_PASSED else RouteMessage.READING, p, observations = good))
        if (passed) return true
    }
    report(RouteProgress(RouteMessage.LOCALIZATION_WEAK, observations = good))
    return false
}

/** Pure control loop, tested without a game, OCR, or wall-clock sleeps. */
internal class RoutePilot(
    private val port: RoutePort,
    private val control: RouteRunControl,
    private val report: (RouteProgress) -> Unit,
) {
    private suspend fun tick(interval: Long = 400) { delay(interval); currentCoroutineContext().ensureActive() }

    suspend fun record(route: RecordedRoute, checkpoint: suspend (RecordedRoute) -> Unit): RecordedRoute {
        val points = mutableListOf<RoutePoint>()
        var lastGood = port.now()
        var savedAt = lastGood
        var last: RoutePoint? = null
        while (!control.stopped && points.size < MAX_ROUTE_POINTS) {
            tick(200)
            val position = try { port.read() } catch (failure: RouteFailure) {
                checkpoint(route.copy(points = points.toList(), recordingComplete = false))
                throw failure
            }
            if (position == null) {
                report(RouteProgress(RouteMessage.READING, count = points.size))
                if (port.now() - lastGood > 5_000) {
                    val draft = route.copy(points = points.toList(), recordingComplete = false)
                    checkpoint(draft)
                    throw RouteFailure(RouteMessage.LOST_POSITION)
                }
                continue
            }
            lastGood = port.now()
            last = position
            // A three-unit discard radius erased small bends before replay ever saw them.
            if (points.isEmpty() || points.last().distance(position) >= 1.0) points.add(position)
            report(RouteProgress(RouteMessage.RECORDING, position, points.size))
            if (port.now() - savedAt >= 5_000) {
                checkpoint(route.copy(points = points.toList(), recordingComplete = false))
                savedAt = port.now()
            }
        }
        if (points.size >= MAX_ROUTE_POINTS) {
            checkpoint(route.copy(points = points.toList(), recordingComplete = false))
            throw RouteFailure(RouteMessage.LIMIT_REACHED)
        }
        if (last != null && points.lastOrNull() != last) points.add(last)
        return route.copy(points = points.toList(), recordingComplete = points.size >= 2 && port.now() - lastGood <= 1_200)
            .also { checkpoint(it) }
    }

    suspend fun preview() {
        while (!control.stopped) {
            tick()
            report(RouteProgress(RouteMessage.READING, port.read()))
        }
    }

    private suspend fun stablePosition(timeout: Long = 10_000): RoutePoint {
        val start = port.now()
        var previous: RoutePoint? = null
        var hits = 0
        while (!control.stopped && port.now() - start < timeout) {
            tick()
            val p = port.read()
            hits = if (p != null && previous?.distance(p)?.let { it <= .5 } == true) hits + 1 else 0
            previous = p
            report(RouteProgress(RouteMessage.READING, p))
            if (hits >= 2) return p!!
        }
        throw RouteFailure(RouteMessage.LOST_POSITION)
    }

    suspend fun calibrate(offset: RoutePoint): RouteCalibrationSample {
        require(offset.valid() && offset.distance(RoutePoint(0.0, 0.0)) in 16.0..300.0)
        val before = stablePosition()
        if (control.stopped || !port.move(offset)) throw RouteFailure(RouteMessage.GESTURE_FAILED)
        // Observe during the move, not only after a blind sleep: automatic game detours are
        // not valid measurements of the requested direction. Keep at most 32 small positions.
        val samples = mutableListOf(before)
        val started = port.now()
        var previous: RoutePoint? = null
        var hits = 0
        var after: RoutePoint? = null
        while (!control.stopped && port.now() - started < 12_000) {
            tick()
            val p = port.read()
            report(RouteProgress(RouteMessage.READING, p))
            hits = if (p != null && previous?.distance(p)?.let { it <= .5 } == true) hits + 1 else 0
            previous = p
            if (p != null) {
                if (samples.last().distance(p) >= .5) samples.add(p)
                if (samples.size > 32) throw RouteFailure(RouteMessage.BAD_CALIBRATION)
                if (hits >= 2 && before.distance(p) >= 3 && port.now() - started >= 1_200) { after = p; break }
            }
        }
        val end = after ?: throw RouteFailure(if (previous == null) RouteMessage.LOST_POSITION else RouteMessage.BAD_CALIBRATION)
        val delta = end - before
        val length = delta.distance(RoutePoint(0.0, 0.0))
        val deviation = maxOf(1.5, length * .15)
        val travelled = samples.zipWithNext().sumOf { (a, b) -> a.distance(b) }
        if (length !in 3.0..100.0 || travelled > length * 1.3 + 1 || samples.any { p ->
                val step = p - before
                val projection = (step.x * delta.x + step.y * delta.y) / length
                kotlin.math.abs(step.x * delta.y - step.y * delta.x) / length > deviation ||
                    projection < -1 || projection > length + 1
            }) throw RouteFailure(RouteMessage.BAD_CALIBRATION)
        return RouteCalibrationSample(offset, delta)
    }

    suspend fun replay(route: RecordedRoute, returning: Boolean = false) {
        val calibration = route.calibration ?: throw RouteFailure(RouteMessage.BAD_CALIBRATION)
        require(route.recordingComplete && route.points.size >= 2 && calibration.valid())
        // Reverse the traversal only, never the saved route or its coordinate/calibration basis.
        var points = route.points
        fun newFollower() = RouteFollower(points, route.tolerance, route.entryRadius(),
            lookAheadDistance = minOf(12.0, route.entryRadius()))
        var follower = if (returning) null else newFollower()
        var previous: RoutePoint? = null
        var lastKnown: RoutePoint? = null
        var lastGood = port.now()
        var lastMove = port.now() - 2_000
        var wasPaused = false
        var observationEpoch = port.epoch
        while (!control.stopped) {
            tick()
            if (control.paused) { wasPaused = true; continue }
            if (wasPaused) {
                follower?.resume(port.now()); previous = null; lastGood = port.now(); wasPaused = false
                port.resetObservation()
            }
            val position = port.read()
            if (observationEpoch != port.epoch) {
                observationEpoch = port.epoch
                follower?.resume(port.now()); previous = null; lastGood = port.now()
                port.resetObservation()
                continue // Discard the observation spanning a debugger pause.
            }
            if (position == null) {
                follower?.breakConfirmation(); previous = null
                if (port.now() - lastGood > 3_000) pause(RouteMessage.LOST_POSITION, follower?.index ?: 0)
                else report(RouteProgress(RouteMessage.READING, count = follower?.index ?: 0))
                continue
            }
            if (follower == null) {
                val entry = route.returnEntry(position)
                if (entry.points == null) {
                    lastGood = port.now()
                    pause(RouteMessage.WRONG_START, 0, position, entry.target, entry.allowedDistance)
                    continue
                }
                points = entry.points
                follower = newFollower()
            }
            val activeFollower = follower
            // Manual relocation during a pause is not an instruction to cut across the map.
            if (activeFollower.hasStarted && lastKnown?.distance(position)?.let { it > 15.0 } == true) {
                pause(RouteMessage.POSITION_JUMP, activeFollower.index, position, lastKnown, 15.0); continue
            }
            lastGood = port.now()
            // A rejected start is not a valid resume anchor. Otherwise returning from the
            // endpoint to the real start immediately triggers the relocation guard again.
            val decision = activeFollower.observe(position, port.now())
            if (decision is RouteFollower.Decision.Pause) {
                val wrongStart = decision.reason == RouteFollower.Reason.WRONG_START
                pause(RouteMessage.valueOf(decision.reason.name), activeFollower.index, position,
                    if (wrongStart) points.first() else null,
                    if (wrongStart) activeFollower.startTolerance else null)
                continue
            }
            lastKnown = position
            val approaching = activeFollower.index == 0
            report(RouteProgress(if (approaching) RouteMessage.APPROACHING_START else RouteMessage.REPLAYING,
                position, activeFollower.index, expectedPosition = if (approaching) points.first() else null))
            when (decision) {
                RouteFollower.Decision.Complete -> { report(RouteProgress(RouteMessage.COMPLETE, position, activeFollower.index)); return }
                RouteFollower.Decision.Wait -> Unit
                is RouteFollower.Decision.Pause -> Unit // Handled above, before updating the resume anchor.
                is RouteFollower.Decision.Move -> {
                    // Wait for two settled observations; do not replace a movement still in progress.
                    // A completed gesture plus two new settled observations is enough; a fixed
                    // 1.5 s penalty on every tiny sample made dense recordings crawl.
                    val minimumInterval = if (route.control == RouteControl.GROUND_TAP) 800 else 400
                    if (previous?.distance(position)?.let { it <= .5 } == true && port.now() - lastMove >= minimumInterval) {
                        currentCoroutineContext().ensureActive()
                        if (!control.paused && !control.stopped) {
                            if (!port.move(routeMotion(route, decision.target - position))) pause(RouteMessage.GESTURE_FAILED, activeFollower.index, position)
                            lastMove = port.now()
                            previous = null
                            continue
                        }
                    }
                }
            }
            previous = position
        }
    }

    private fun pause(reason: RouteMessage, count: Int, position: RoutePoint? = null,
        expectedPosition: RoutePoint? = null, allowedDistance: Double? = null) {
        control.paused = true
        report(RouteProgress(reason, position, count, expectedPosition = expectedPosition, allowedDistance = allowedDistance))
    }
}
