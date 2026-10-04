/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

/** A completed tap is not proof of movement. Try nearer points on the same requested segment,
 * never a larger radius, a side step, a later waypoint, or a retry of a failed gesture.
 */
internal class RouteGroundStepGuard {
    sealed interface Decision {
        data class Tap(val scale: Double, val attempt: Int) : Decision
        data object Wait : Decision
        data object Blocked : Decision
    }

    private var target: RoutePoint? = null
    private var dispatchedPosition: RoutePoint? = null
    private var dispatchedAt = 0L
    private var attempts = 0

    fun reset() { target = null; dispatchedPosition = null; attempts = 0 }

    fun observe(destination: RoutePoint, position: RoutePoint, now: Long): Decision {
        if (destination != target) { reset(); target = destination }
        val before = dispatchedPosition
        if (before != null && before.distance(position) >= 1.0 &&
            before.distance(destination) - position.distance(destination) > .5) {
            dispatchedPosition = null
            attempts = 0
        }
        if (dispatchedPosition != null) {
            if (now - dispatchedAt < 3_000) return Decision.Wait
            if (attempts >= 3) return Decision.Blocked
        }
        return Decision.Tap(when (attempts) { 0 -> 1.0; 1 -> .7; else -> .4 }, attempts + 1)
    }

    /** Call only after the OS confirms completion. A failed/uncertain dispatch must pause. */
    fun dispatched(position: RoutePoint, now: Long) {
        dispatchedPosition = position
        dispatchedAt = now
        attempts++
    }
}
