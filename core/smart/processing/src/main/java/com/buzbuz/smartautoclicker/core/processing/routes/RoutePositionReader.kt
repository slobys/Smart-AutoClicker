/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import android.graphics.Bitmap
import android.graphics.Rect
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.detection.NumberFormatType
import kotlin.math.abs
import kotlin.math.round

enum class RouteReadIssue {
    READY, WAITING_CONFIRMATION, NO_FRAME, OVERLAY_BLOCKED, MAP_MISMATCH,
    X_UNREADABLE, Y_UNREADABLE, XY_UNREADABLE, INVALID_COORDINATE, POSITION_OUTLIER, MINIMAP_UNCERTAIN,
}

/** OCR candidates are diagnostic only. They must never be used as a movement position. */
data class RouteAxisReading(val value: Double?, val confidence: Double, val accepted: Boolean)
data class RouteReadDiagnostics(
    val issue: RouteReadIssue,
    val mapMatched: Boolean? = null,
    val x: RouteAxisReading? = null,
    val y: RouteAxisReading? = null,
)

/** A calibration can time out with readable but still moving coordinates. */
internal fun RouteReadDiagnostics.positionFailure() = if (issue == RouteReadIssue.READY)
    copy(issue = RouteReadIssue.WAITING_CONFIRMATION) else this

/** Keeps map identity, each OCR result and temporal validation separate without weakening any gate. */
internal class RoutePositionReader(
    private val route: RecordedRoute,
    private val detector: ImageDetector,
    private val map: Bitmap,
    private val preview: Boolean,
    private val minimap: MinimapLocalizer? = null,
) {
    private val filter = RouteCoordinateFilter()
    private var hasAcceptedPosition = false
    var diagnostics = RouteReadDiagnostics(RouteReadIssue.NO_FRAME)
        private set

    fun reset() {
        filter.reset()
        hasAcceptedPosition = false
        diagnostics = RouteReadDiagnostics(RouteReadIssue.NO_FRAME)
    }

    fun read(frame: Bitmap?, blockedArea: RouteArea?): RoutePoint? {
        // Clear every field so a missing frame or blocked selection never displays stale candidates.
        diagnostics = RouteReadDiagnostics(RouteReadIssue.NO_FRAME)
        if (frame == null) return reject(RouteReadIssue.NO_FRAME)
        val areas = listOf(route.mapArea) + if (minimap == null) listOf(route.xArea, route.yArea)
            else listOf(requireNotNull(route.minimap).area)
        if (blockedArea != null && areas.any { it.overlaps(blockedArea) })
            return reject(RouteReadIssue.OVERLAY_BLOCKED)
        try {
            detector.setScreenBitmap(frame, "route:${route.id}")
            val mapMatched = detector.detectImage(map, map.width, map.height, route.mapArea.rect(), 15).isDetected
            diagnostics = diagnostics.copy(mapMatched = mapMatched)
            // Preview may inspect the digits of a wrong map, but never returns them as a position.
            if (!mapMatched && (!preview || minimap != null)) return reject(RouteReadIssue.MAP_MISMATCH)
            if (minimap != null) {
                val position = minimap.locate(MinimapFrames.read(frame, requireNotNull(route.minimap).area))
                diagnostics = diagnostics.copy(issue = if (position == null) RouteReadIssue.MINIMAP_UNCERTAIN else RouteReadIssue.READY)
                return position
            }
            val x = detector.detectNumber(route.xArea.rect(), 15, NumberFormatType.AUTO).axisReading()
            val y = detector.detectNumber(route.yArea.rect(), 15, NumberFormatType.AUTO).axisReading()
            diagnostics = diagnostics.copy(x = x, y = y)
            if (!mapMatched) return reject(RouteReadIssue.MAP_MISMATCH)
            if (!x.accepted || !y.accepted) return reject(when {
                !x.accepted && !y.accepted -> RouteReadIssue.XY_UNREADABLE
                !x.accepted -> RouteReadIssue.X_UNREADABLE
                else -> RouteReadIssue.Y_UNREADABLE
            })
            val px = requireNotNull(x.value); val py = requireNotNull(y.value)
            val candidate = RoutePoint(px, py)
            if (!candidate.valid() || px < 0 || py < 0 || abs(px - round(px)) > .01 || abs(py - round(py)) > .01)
                return reject(RouteReadIssue.INVALID_COORDINATE)
            val position = filter.accept(candidate)
            diagnostics = diagnostics.copy(issue = when {
                position != null -> RouteReadIssue.READY
                hasAcceptedPosition -> RouteReadIssue.POSITION_OUTLIER
                else -> RouteReadIssue.WAITING_CONFIRMATION
            })
            if (position != null) hasAcceptedPosition = true
            return position
        } finally { detector.releaseScreenBitmap(frame) }
    }

    private fun reject(issue: RouteReadIssue): RoutePoint? {
        diagnostics = diagnostics.copy(issue = issue)
        return filter.accept(null)
    }
}

private fun DetectionResult.axisReading() = RouteAxisReading(
    numberDetected?.takeIf { it.isFinite() }, confidenceRate,
    isDetected && numberDetected?.isFinite() == true,
)
private fun RouteArea.rect() = Rect(left, top, right, bottom)
private fun RouteArea.overlaps(other: RouteArea) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
