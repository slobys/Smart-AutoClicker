/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import androidx.lifecycle.ViewModel
import com.buzbuz.smartautoclicker.core.processing.routes.*
import com.buzbuz.smartautoclicker.feature.smart.config.R
import java.util.UUID
import javax.inject.Inject

class RouteViewModel @Inject constructor(val store: RouteStore, val runtime: RouteRuntime) : ViewModel() {
    var route: RecordedRoute? = null
    var sampleA: RouteCalibrationSample? = null
    var sampleB: RouteCalibrationSample? = null
    var targetA: RoutePoint? = null
    var targetB: RoutePoint? = null
    var feedback: String? = null
    var feedbackIsError = false

    fun newRoute(width: Int, height: Int, name: String) {
        route = RecordedRoute(UUID.randomUUID().toString(), name, width, height,
            RouteArea(0, 0, 0, 0), RouteArea(0, 0, 0, 0), RouteArea(0, 0, 0, 0), "", RoutePoint(-1.0, -1.0))
        clearCalibration()
    }

    fun load(value: RecordedRoute) {
        route = value
        targetA = null; targetB = null
        sampleA = value.calibration?.first; sampleB = value.calibration?.second
    }

    fun clearCalibration() {
        sampleA = null; sampleB = null; targetA = null; targetB = null
        route = route?.copy(calibration = null)
    }

    fun setSample(first: Boolean, sample: RouteCalibrationSample): Boolean {
        if (first) sampleA = sample else sampleB = sample
        val a = sampleA; val b = sampleB
        val calibration = if (a != null && b != null) RouteCalibration(a, b).takeIf { it.valid() } else null
        route = route?.copy(calibration = calibration)
        return a == null || b == null || calibration != null
    }

    fun configured(): Boolean {
        val r = route ?: return false
        if (!r.valid()) return false
        val areas = if (r.positionMode == RoutePositionMode.MINIMAP) listOf(requireNotNull(r.minimap).area, r.mapArea)
            else listOf(r.xArea, r.yArea, r.mapArea)
        return areas.indices.all { i -> areas.indices.all { j -> i == j || !areas[i].overlaps(areas[j]) } }
    }

    /** Recording completion and replay readiness are deliberately separate. Never bypass runtime guards. */
    fun configurationIssues(): List<Int> {
        val r = route ?: return listOf(R.string.route_invalid)
        return buildList {
            if (r.name.isBlank() || r.name.length > 60) add(R.string.route_need_name)
            if (r.positionMode == RoutePositionMode.COORDINATES) {
                if (!r.xArea.valid(r.screenWidth, r.screenHeight)) add(R.string.route_need_x)
                if (!r.yArea.valid(r.screenWidth, r.screenHeight)) add(R.string.route_need_y)
            } else if (r.minimap?.valid(r.screenWidth, r.screenHeight) != true) add(R.string.route_need_minimap)
            if (!r.mapArea.valid(r.screenWidth, r.screenHeight)) add(R.string.route_need_map_area)
            if (r.mapPng.isEmpty()) add(R.string.route_need_map_capture)
            if (!r.anchor.valid() || r.anchor.x !in 0.0..<r.screenWidth.toDouble() ||
                r.anchor.y !in 0.0..<r.screenHeight.toDouble()) add(R.string.route_need_anchor)
            if (r.joystickDurationMs !in 100..800) add(R.string.route_joystick_duration)
            val areas = if (r.positionMode == RoutePositionMode.COORDINATES) listOf(r.xArea, r.yArea, r.mapArea)
                else listOfNotNull(r.minimap?.area, r.mapArea)
            if (areas.indices.any { i -> (i + 1 until areas.size).any { j -> areas[i].overlaps(areas[j]) } })
                add(R.string.route_need_separate_areas)
            if (isEmpty() && !r.valid()) add(R.string.route_invalid)
        }
    }

    fun operationIssues(operation: RouteOperation): List<Int> = configurationIssues() + buildList {
        val r = route ?: return@buildList
        if (operation != RouteOperation.PREVIEW && r.positionMode == RoutePositionMode.MINIMAP && r.minimap?.tested != true)
            add(R.string.route_need_test)
        if (operation == RouteOperation.REPLAY || operation == RouteOperation.RETURN) {
            if (r.calibration == null) {
                if (sampleA == null) add(R.string.route_need_calibration_a)
                if (sampleB == null) add(R.string.route_need_calibration_b)
                if (sampleA != null && sampleB != null) add(R.string.route_message_bad_calibration)
            }
            if (!r.recordingComplete) add(R.string.route_need_recording)
            else if (r.points.size < 2) add(R.string.route_need_points)
        }
    }

    suspend fun saveCalibratedRecording(): Boolean {
        val value = route ?: return false
        if (!configured() || value.calibration == null || !value.recordingComplete || value.points.size < 2) return false
        store.save(value)
        return true
    }

    fun setMode(mode: RoutePositionMode) {
        if (route?.positionMode == mode) return
        route = route?.copy(positionMode = mode, points = emptyList(), recordingComplete = false, minimap = null)
        clearCalibration()
    }

    private fun RouteArea.overlaps(other: RouteArea) =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}
