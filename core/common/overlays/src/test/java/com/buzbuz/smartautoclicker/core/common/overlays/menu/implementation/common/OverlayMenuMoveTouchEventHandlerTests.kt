/*
 * Copyright (C) 2026 Kevin Buzeau
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
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.common.overlays.testutils.mockSimpleRawEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.annotation.Config
import org.mockito.Mockito.`when` as mockWhen

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class OverlayMenuMoveTouchEventHandlerTests {

    @Test
    fun tapWithinTouchSlopReturnsClickWithoutMoving() {
        val movedPositions = mutableListOf<Point>()
        var dragFinishedCount = 0
        val view = createViewAt(Point(100, 200))
        val handler = OverlayMenuMoveTouchEventHandler(
            onMenuMoved = movedPositions::add,
            touchSlop = 12,
            onDragFinished = { dragFinishedCount++ },
        )

        assertEquals(
            OverlayMenuMoveTouchResult.HANDLED,
            handler.onTouchEvent(view, mockSimpleRawEvent(MotionEvent.ACTION_DOWN, 40f, 60f)),
        )
        handler.onTouchEvent(view, mockSimpleRawEvent(MotionEvent.ACTION_MOVE, 48f, 68f))
        assertEquals(
            OverlayMenuMoveTouchResult.CLICK,
            handler.onTouchEvent(view, mockSimpleRawEvent(MotionEvent.ACTION_UP, 48f, 68f)),
        )

        assertEquals(0, movedPositions.size)
        assertEquals(0, dragFinishedCount)
        verify(view, never()).performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun moveBeyondTouchSlopDragsFromInitialPosition() {
        val movedPositions = mutableListOf<Point>()
        var dragFinishedCount = 0
        val view = createViewAt(Point(100, 200))
        val handler = OverlayMenuMoveTouchEventHandler(
            onMenuMoved = movedPositions::add,
            touchSlop = 12,
            onDragFinished = { dragFinishedCount++ },
        )

        handler.onTouchEvent(view, mockSimpleRawEvent(MotionEvent.ACTION_DOWN, 40f, 60f))
        handler.onTouchEvent(view, mockSimpleRawEvent(MotionEvent.ACTION_MOVE, 70f, 95f))
        assertEquals(
            OverlayMenuMoveTouchResult.HANDLED,
            handler.onTouchEvent(view, mockSimpleRawEvent(MotionEvent.ACTION_UP, 70f, 95f)),
        )

        assertEquals(1, movedPositions.size)
        assertEquals(130, movedPositions.single().x)
        assertEquals(235, movedPositions.single().y)
        assertEquals(1, dragFinishedCount)
        verify(view).performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun defaultHandlerKeepsImmediateMoveButtonBehaviour() {
        val movedPositions = mutableListOf<Point>()
        val view = createViewAt(Point(50, 75))
        val handler = OverlayMenuMoveTouchEventHandler(movedPositions::add)

        handler.onTouchEvent(view, mockSimpleRawEvent(MotionEvent.ACTION_DOWN, 10f, 20f))
        handler.onTouchEvent(view, mockSimpleRawEvent(MotionEvent.ACTION_MOVE, 15f, 30f))

        assertEquals(1, movedPositions.size)
        assertEquals(55, movedPositions.single().x)
        assertEquals(85, movedPositions.single().y)
        verify(view).performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun createViewAt(position: Point): View {
        val view = mock(View::class.java)
        val layoutParams = WindowManager.LayoutParams().apply {
            x = position.x
            y = position.y
        }
        mockWhen(view.layoutParams).thenReturn(layoutParams)
        return view
    }
}
