/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import kotlin.math.abs
import kotlin.math.hypot

data class RoutePoint(val x: Double, val y: Double) {
    fun distance(other: RoutePoint): Double = hypot(x - other.x, y - other.y)
    operator fun minus(other: RoutePoint) = RoutePoint(x - other.x, y - other.y)
    operator fun plus(other: RoutePoint) = RoutePoint(x + other.x, y + other.y)
    operator fun times(scale: Double) = RoutePoint(x * scale, y * scale)
    fun valid() = x.isFinite() && y.isFinite() && abs(x) <= 100_000 && abs(y) <= 100_000
}

data class RouteArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun valid(width: Int, height: Int) = left >= 0 && top >= 0 && right <= width && bottom <= height &&
        right - left >= 8 && bottom - top >= 8 && (right - left).toLong() * (bottom - top) <= 262_144
    fun contains(point: RoutePoint) = point.x >= left && point.x < right && point.y >= top && point.y < bottom
}

enum class RouteControl { GROUND_TAP, JOYSTICK }

/** One short, explicitly requested movement: screen offset and observed map displacement. */
data class RouteCalibrationSample(val screenDelta: RoutePoint, val mapDelta: RoutePoint)

data class RouteCalibration(val first: RouteCalibrationSample, val second: RouteCalibrationSample) {
    fun valid(): Boolean {
        val vectors = listOf(first.screenDelta, second.screenDelta, first.mapDelta, second.mapDelta)
        if (vectors.any { !it.valid() }) return false
        val lengths = vectors.map { hypot(it.x, it.y) }
        if (lengths.take(2).any { it !in 16.0..300.0 } || lengths.drop(2).any { it !in 3.0..100.0 }) return false
        return abs(cross(first.mapDelta, second.mapDelta)) / (lengths[2] * lengths[3]) >= 0.25 &&
            abs(cross(first.screenDelta, second.screenDelta)) / (lengths[0] * lengths[1]) >= 0.25
    }

    /** Maps world displacement to screen displacement, limited to the calibrated movement radius. */
    fun screenOffset(delta: RoutePoint): RoutePoint {
        require(valid() && delta.valid())
        val determinant = cross(first.mapDelta, second.mapDelta)
        val a = cross(delta, second.mapDelta) / determinant
        val b = cross(first.mapDelta, delta) / determinant
        val offset = first.screenDelta * a + second.screenDelta * b
        val radius = minOf(hypot(first.screenDelta.x, first.screenDelta.y), hypot(second.screenDelta.x, second.screenDelta.y))
        return offset * minOf(1.0, radius / hypot(offset.x, offset.y).coerceAtLeast(0.001))
    }

    private fun cross(a: RoutePoint, b: RoutePoint) = a.x * b.y - a.y * b.x
}

data class RecordedRoute(
    val id: String,
    val name: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val xArea: RouteArea,
    val yArea: RouteArea,
    val mapArea: RouteArea,
    val mapPng: String,
    val anchor: RoutePoint,
    val control: RouteControl = RouteControl.GROUND_TAP,
    val calibration: RouteCalibration? = null,
    val tolerance: Double = 2.0,
    val points: List<RoutePoint> = emptyList(),
    val recordingComplete: Boolean = false,
) {
    fun valid(): Boolean = id.matches(ROUTE_ID) && name.isNotBlank() && name.length <= 60 &&
        screenWidth in 100..8192 && screenHeight in 100..8192 &&
        listOf(xArea, yArea, mapArea).all { it.valid(screenWidth, screenHeight) } &&
        mapPng.length in 1..350_000 && anchor.valid() && anchor.x in 0.0..<screenWidth.toDouble() &&
        anchor.y in 0.0..<screenHeight.toDouble() && tolerance.isFinite() && tolerance in 1.0..5.0 &&
        (calibration == null || calibration.valid()) && points.size <= MAX_ROUTE_POINTS &&
        points.all { it.valid() && it.x >= 0 && it.y >= 0 }
}

const val MAX_ROUTE_POINTS = 2_000
internal val ROUTE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Rejects impossible coordinates and isolated OCR jumps. Never substitutes an unreadable value with zero. */
class RouteCoordinateFilter {
    private var previous: RoutePoint? = null
    private var startup: RoutePoint? = null

    fun accept(point: RoutePoint?): RoutePoint? {
        if (point == null || !point.valid() || point.x < 0 || point.y < 0) {
            startup = null
            return null
        }
        val last = previous
        if (last == null) {
            val candidate = startup
            startup = point
            if (candidate == null || candidate.distance(point) > 2.0) return null
        } else if (last.distance(point) > 40.0) return null
        previous = point
        return point
    }
}

/** Event-independent, clock-injected follower. Only confirmed positions advance the route. */
class RouteFollower(private val points: List<RoutePoint>, private val tolerance: Double) {
    init {
        require(points.size in 2..MAX_ROUTE_POINTS && points.all { it.valid() })
        require(tolerance.isFinite() && tolerance in 1.0..5.0)
    }

    var index = 0
        private set
    private var started = false
    private var hits = 0
    private var segmentStarted = 0L
    private var progressAt = 0L
    private var bestDistance = Double.POSITIVE_INFINITY

    sealed interface Decision {
        data class Move(val target: RoutePoint) : Decision
        data object Wait : Decision
        data object Complete : Decision
        data class Pause(val reason: Reason) : Decision
    }
    enum class Reason { WRONG_START, STUCK, TIMEOUT }

    fun observe(position: RoutePoint, now: Long): Decision {
        require(position.valid())
        if (!started) {
            if (position.distance(points.first()) > maxOf(5.0, tolerance * 2)) return Decision.Pause(Reason.WRONG_START)
            started = true
            resetTimers(now)
        }
        if (index >= points.size) return Decision.Complete
        val distance = position.distance(points[index])
        if (distance <= tolerance) {
            if (++hits < 2) return Decision.Wait
            index++
            resetTimers(now)
            return if (index >= points.size) Decision.Complete else Decision.Wait
        }
        hits = 0
        if (distance < bestDistance - 0.5) {
            bestDistance = distance
            progressAt = now
        }
        if (now - segmentStarted >= 60_000) return Decision.Pause(Reason.TIMEOUT)
        if (now - progressAt >= 15_000) return Decision.Pause(Reason.STUCK)
        return Decision.Move(points[index])
    }

    /** A pause cannot supply the second arrival confirmation or consume a timeout. */
    fun resume(now: Long) { hits = 0; resetTimers(now) }
    fun breakConfirmation() { hits = 0 }
    private fun resetTimers(now: Long) {
        hits = 0
        segmentStarted = now
        progressAt = now
        bestDistance = Double.POSITIVE_INFINITY
    }
}
