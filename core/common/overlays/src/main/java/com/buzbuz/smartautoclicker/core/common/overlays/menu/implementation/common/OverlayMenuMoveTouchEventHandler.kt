/*
 * Copyright (C) 2023 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common

import android.graphics.Point
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

internal class OverlayMenuMoveTouchEventHandler(
    private val onMenuMoved: (Point) -> Unit,
    private val touchSlop: Int = 0,
    private val onDragFinished: () -> Unit = {},
) {

    /** The initial position of the overlay menu when pressing the move menu item. */
    private var moveInitialViewPosition: Point = Point(0, 0)
    /** The initial position of the touch event that as initiated the move of the overlay menu. */
    private var moveInitialTouchPosition: Point = Point(0, 0)
    /** Whether the current gesture has crossed the movement threshold. */
    private var isDragging: Boolean = false

    fun onTouchEvent(viewToMove: View, event: MotionEvent): OverlayMenuMoveTouchResult =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onDownEvent(viewToMove, event)
                isDragging = touchSlop == 0
                if (isDragging) viewToMove.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                OverlayMenuMoveTouchResult.HANDLED
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && hasCrossedTouchSlop(event)) {
                    isDragging = true
                    viewToMove.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                if (isDragging) onMoveEvent(event)
                OverlayMenuMoveTouchResult.HANDLED
            }

            MotionEvent.ACTION_UP -> finishGesture()

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) onDragFinished()
                isDragging = false
                OverlayMenuMoveTouchResult.HANDLED
            }

            else -> OverlayMenuMoveTouchResult.IGNORED
        }

    private fun onDownEvent(viewToMove: View, event: MotionEvent) {
        val layoutParams = (viewToMove.layoutParams as WindowManager.LayoutParams)
        moveInitialViewPosition = Point(layoutParams.x, layoutParams.y)
        moveInitialTouchPosition = Point(event.rawX.toInt(), event.rawY.toInt())
    }

    private fun onMoveEvent(event: MotionEvent) {
        onMenuMoved(
            Point(
                moveInitialViewPosition.x + (event.rawX.toInt() - moveInitialTouchPosition.x),
                moveInitialViewPosition.y + (event.rawY.toInt() - moveInitialTouchPosition.y),
            )
        )
    }

    private fun hasCrossedTouchSlop(event: MotionEvent): Boolean =
        abs(event.rawX.toInt() - moveInitialTouchPosition.x) > touchSlop ||
                abs(event.rawY.toInt() - moveInitialTouchPosition.y) > touchSlop

    private fun finishGesture(): OverlayMenuMoveTouchResult {
        val result = if (isDragging) {
            onDragFinished()
            OverlayMenuMoveTouchResult.HANDLED
        } else {
            OverlayMenuMoveTouchResult.CLICK
        }
        isDragging = false
        return result
    }
}

internal enum class OverlayMenuMoveTouchResult {
    HANDLED,
    CLICK,
    IGNORED,
}
