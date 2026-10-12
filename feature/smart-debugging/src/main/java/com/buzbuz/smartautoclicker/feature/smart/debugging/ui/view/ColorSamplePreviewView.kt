/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.debugging.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.buzbuz.smartautoclicker.core.processing.domain.model.ColorSamplePreview

/** Nearest-neighbour magnifier. Its marker outlines the sampled pixel without covering it. */
class ColorSamplePreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private var sampleX = 0
    private var sampleY = 0
    private val paint = Paint().apply { isFilterBitmap = false }
    private val marker = Paint().apply { style = Paint.Style.STROKE }
    private val destination = RectF()
    private val pixel = RectF()

    fun setPreview(preview: ColorSamplePreview?) {
        bitmap = preview?.let { Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888) }
        sampleX = preview?.sampleX ?: 0
        sampleY = preview?.sampleY ?: 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val left = (width - image.width * scale) / 2f
        val top = (height - image.height * scale) / 2f
        destination.set(left, top, left + image.width * scale, top + image.height * scale)
        canvas.drawBitmap(image, null, destination, paint)
        pixel.set(left + sampleX * scale, top + sampleY * scale,
            left + (sampleX + 1) * scale, top + (sampleY + 1) * scale)
        canvas.save()
        @Suppress("DEPRECATION")
        canvas.clipRect(pixel, android.graphics.Region.Op.DIFFERENCE)
        pixel.inset(-1f, -1f)
        marker.color = Color.BLACK
        marker.strokeWidth = 4f
        canvas.drawRect(pixel, marker)
        marker.color = Color.WHITE
        marker.strokeWidth = 2f
        canvas.drawRect(pixel, marker)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        setPreview(null)
        super.onDetachedFromWindow()
    }
}
