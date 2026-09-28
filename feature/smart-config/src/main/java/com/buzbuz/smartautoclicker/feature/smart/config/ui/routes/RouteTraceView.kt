/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.buzbuz.smartautoclicker.core.processing.routes.RoutePoint

/** Small bounded observation trace, not an assertion that unexplored space is traversable. */
class RouteTraceView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5EDFE4.toInt(); strokeWidth = 3f }
    private val path = Path()
    var points: List<RoutePoint> = emptyList()
        set(value) { field = value.takeLast(120); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val left = points.minOf { it.x }; val top = points.minOf { it.y }
        val scale = minOf((width - 24) / maxOf(24.0, points.maxOf { it.x } - left),
            (height - 24) / maxOf(24.0, points.maxOf { it.y } - top))
        fun x(p: RoutePoint) = (12 + (p.x - left) * scale).toFloat()
        fun y(p: RoutePoint) = (12 + (p.y - top) * scale).toFloat()
        paint.style = Paint.Style.STROKE
        path.rewind()
        path.moveTo(x(points.first()), y(points.first()))
        for (i in 1 until points.size) path.lineTo(x(points[i]), y(points[i]))
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x(points.first()), y(points.first()), 4f, paint)
        canvas.drawCircle(x(points.last()), y(points.last()), 7f, paint)
    }
}
