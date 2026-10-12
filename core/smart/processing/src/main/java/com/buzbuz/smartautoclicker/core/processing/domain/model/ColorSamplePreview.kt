/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.domain.model

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect

/** Small owned pixel copy from the detection frame. Never retains the recorder's borrowed bitmap. */
data class ColorSamplePreview(
    val point: Point,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val sampleX: Int,
    val sampleY: Int,
) {
    val color: Int get() = pixels[sampleY * width + sampleX]
}

/** Called synchronously while the processor still owns this frame; no second screenshot is acquired. */
internal fun copyColorSamplePreview(frame: Bitmap?, area: Rect, hit: Point?): ColorSamplePreview? {
    if (frame == null || frame.isRecycled) return null
    val bounds = Rect(area)
    if (!bounds.intersect(0, 0, frame.width, frame.height)) return null
    val point = hit?.takeIf { bounds.contains(it.x, it.y) }?.let(::Point)
        ?: Point(bounds.centerX(), bounds.centerY())
    val left = (point.x - PREVIEW_RADIUS).coerceAtLeast(0)
    val top = (point.y - PREVIEW_RADIUS).coerceAtLeast(0)
    val width = (point.x + PREVIEW_RADIUS + 1).coerceAtMost(frame.width) - left
    val height = (point.y + PREVIEW_RADIUS + 1).coerceAtMost(frame.height) - top
    val pixels = IntArray(width * height)
    frame.getPixels(pixels, 0, width, left, top, width, height)
    return ColorSamplePreview(point, width, height, pixels, point.x - left, point.y - top)
}

private const val PREVIEW_RADIUS = 12
