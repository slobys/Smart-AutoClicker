/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import android.graphics.Bitmap
import android.util.Base64
import com.buzbuz.smartautoclicker.core.detection.MinimapMatcher
import kotlin.math.hypot

internal object MinimapFrames {
    fun read(bitmap: Bitmap, area: RouteArea): ByteArray {
        require(area.valid(bitmap.width, bitmap.height))
        val crop = Bitmap.createBitmap(bitmap, area.left, area.top, area.right - area.left, area.bottom - area.top)
        var scaled: Bitmap? = null
        return try {
            scaled = Bitmap.createScaledBitmap(crop, MinimapMatcher.SIZE, MinimapMatcher.SIZE, true)
            val pixels = IntArray(MinimapMatcher.SIZE * MinimapMatcher.SIZE)
            scaled.getPixels(pixels, 0, MinimapMatcher.SIZE, 0, 0, MinimapMatcher.SIZE, MinimapMatcher.SIZE)
            ByteArray(pixels.size) { i ->
                val c = pixels[i]
                (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8).toByte()
            }
        } finally {
            if (scaled !== crop && scaled !== bitmap) scaled?.recycle()
            if (crop !== bitmap) crop.recycle()
        }
    }
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP).also {
        require(it.size == MinimapMatcher.SIZE * MinimapMatcher.SIZE)
    }
}

/** Absolute relocalization against saved keyframes, not unbounded frame-to-frame drift.
 * The current landmark and its neighbours are tried; loss never silently rebases the origin.
 */
internal class MinimapLocalizer(
    config: RouteMinimap,
    private val allowLearning: Boolean,
    initialPosition: RoutePoint? = null,
    private val allowGlobalStart: Boolean = false,
    private val match: (ByteArray, ByteArray, Int) -> MinimapMatcher.Match?,
) {
    private val initial = config
    private val frames = config.keyframes.toMutableList()
    private val pixels = frames.map { MinimapFrames.decode(it.gray) }.toMutableList()
    // Returning begins at the recorded endpoint, which can be many landmarks from the start.
    private var index = initialPosition?.let { p -> frames.indices.minByOrNull { frames[it].position.distance(p) } } ?: 0
    private var previous: RoutePoint? = null
    private var needsRelocalization = allowGlobalStart
    var quality: Double = 0.0
        private set
    var inliers: Int = 0
        private set

    fun snapshot() = initial.copy(keyframes = frames.toList())

    /** Drop the continuity guard after an explicit pause, never change saved landmark coordinates. */
    fun reset() { previous = null; quality = 0.0; inliers = 0; needsRelocalization = allowGlobalStart }

    fun locate(frame: ByteArray): RoutePoint? {
        quality = 0.0; inliers = 0
        val search = if (needsRelocalization) frames.indices else maxOf(0, index - 1)..minOf(frames.lastIndex, index + 2)
        val candidates = search.mapNotNull { i ->
            match(pixels[i], frame, initial.markerRadius)?.let { result ->
                Triple(i, frames[i].position - RoutePoint(result.dx, result.dy), result)
            }
        }
        val best = candidates.maxByOrNull { it.third.inliers * it.third.confidence } ?: return null
        // Different matching landmarks must agree in world space. Repeated-looking rooms are unsafe.
        if (candidates.any { it.second.distance(best.second) > 3.0 }) return null
        if (previous?.distance(best.second)?.let { it > 20.0 } == true) return null
        index = best.first
        previous = best.second
        needsRelocalization = false
        quality = best.third.confidence; inliers = best.third.inliers
        if (allowLearning && frames.last().position.distance(best.second) >= 24.0) {
            if (frames.size >= MAX_ROUTE_KEYFRAMES) throw RouteFailure(RouteMessage.LIMIT_REACHED)
            frames.add(RouteKeyframe(best.second, MinimapFrames.encode(frame)))
            pixels.add(frame.copyOf())
            index = frames.lastIndex
        }
        return best.second
    }
}

/** Fixed-stick movement keeps the calibrated deflection (outside its deadzone) and adjusts time.
 * This differs from ground taps, where a smaller offset means a nearer destination.
 */
internal data class RouteMotion(val offset: RoutePoint, val durationMs: Long)
internal fun routeMotion(route: RecordedRoute, delta: RoutePoint): RouteMotion {
    val calibration = requireNotNull(route.calibration)
    val offset = calibration.screenOffset(delta)
    if (route.control == RouteControl.GROUND_TAP) return RouteMotion(offset, 70)
    val radius = minOf(hypot(calibration.first.screenDelta.x, calibration.first.screenDelta.y),
        hypot(calibration.second.screenDelta.x, calibration.second.screenDelta.y))
    val magnitude = hypot(offset.x, offset.y).coerceAtLeast(.001)
    val fraction = (magnitude / radius).coerceIn(.2, 1.0)
    return RouteMotion(offset * (radius / magnitude), (route.joystickDurationMs * fraction).toLong().coerceIn(100, 800))
}
