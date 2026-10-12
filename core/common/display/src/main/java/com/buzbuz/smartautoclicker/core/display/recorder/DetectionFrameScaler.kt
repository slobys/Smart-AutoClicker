/*
 * Copyright (C) 2026 Smart-AutoClicker contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.buzbuz.smartautoclicker.core.display.recorder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

/**
 * Explicitly scales a COMPLETE physical screen into the detector's coordinate system.
 * Owns at most one reusable destination; never alters the capture/screenshot buffer.
 * Like ImageReaderProxy, returned bitmaps are borrowed until the next acquisition/resize.
 * All calls are serialized by DisplayRecorder and consumers finish before resizing.
 */
internal class DetectionFrameScaler {
    private val destination = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Replace transparent pixels too, rather than compositing over the preceding frame.
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    fun resize(size: Point) {
        require(size.x > 0 && size.y > 0)
        if (destination.width() == size.x && destination.height() == size.y) return
        clear()
        destination.set(0, 0, size.x, size.y)
    }

    fun scale(source: Bitmap): Bitmap {
        check(!destination.isEmpty)
        if (source.width == destination.width() && source.height == destination.height()) return source
        val output = bitmap ?: Bitmap.createBitmap(
            destination.width(), destination.height(), Bitmap.Config.ARGB_8888,
        ).also {
            bitmap = it
            canvas = Canvas(it)
        }
        canvas!!.drawBitmap(source, null, destination, paint)
        return output
    }

    fun clear() {
        // Do not recycle a bitmap that may still be referenced by a screenshot/debug consumer.
        canvas = null
        bitmap = null
        destination.setEmpty()
    }
}
