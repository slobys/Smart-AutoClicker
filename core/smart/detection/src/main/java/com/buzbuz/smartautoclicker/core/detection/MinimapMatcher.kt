/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.detection

/** Translation-only matching for a north-up, fixed-zoom, player-centred minimap.
 * No native state or retained screenshot. Coordinates are in a normalized 192 px square.
 */
@androidx.annotation.Keep
class MinimapMatcher {
    init { System.loadLibrary("smartautoclicker") }

    data class Match(val dx: Double, val dy: Double, val confidence: Double, val inliers: Int)

    fun match(reference: ByteArray, current: ByteArray, maskRadius: Int): Match? {
        require(reference.size == SIZE * SIZE && current.size == reference.size)
        require(maskRadius in 8..40)
        val result = matchNative(reference, current, maskRadius)
        if (result.size != 4 || result.any { !it.isFinite() }) return null
        return Match(result[0], result[1], result[2], result[3].toInt())
    }

    private external fun matchNative(reference: ByteArray, current: ByteArray, maskRadius: Int): DoubleArray

    companion object { const val SIZE = 192 }
}
