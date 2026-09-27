/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import androidx.lifecycle.ViewModel
import com.buzbuz.smartautoclicker.core.processing.routes.*
import java.util.UUID
import javax.inject.Inject

class RouteViewModel @Inject constructor(val store: RouteStore, val runtime: RouteRuntime) : ViewModel() {
    var route: RecordedRoute? = null
    var sampleA: RouteCalibrationSample? = null
    var sampleB: RouteCalibrationSample? = null
    var targetA: RoutePoint? = null
    var targetB: RoutePoint? = null

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
        val areas = listOf(r.xArea, r.yArea, r.mapArea)
        return areas.indices.all { i -> areas.indices.all { j -> i == j || !areas[i].overlaps(areas[j]) } }
    }

    private fun RouteArea.overlaps(other: RouteArea) =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}
