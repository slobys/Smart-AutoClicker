/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native OCR against changing map coordinates, not a mock of the recognition result. */
@RunWith(AndroidJUnit4::class)
class RouteCoordinateDetectionTests {
    @Test fun separateIntegerFieldsSurviveDigitChangesAndMovingBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (detect, recognize) = context.extractTestOcrModels()
        val detector = requireNotNull(NativeDetector.newInstance())
        detector.init()
        try {
            assertTrue(detector.loadTextDetectionModels(detect, recognize))
            listOf(9 to 88, 10 to 89, 99 to 100, 127 to 256).forEachIndexed { frame, (x, y) ->
                val bitmap = Bitmap.createBitmap(720, 400, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.rgb(10 + frame * 15, 30, 40))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.MONOSPACE }
                    canvas.drawText(x.toString(), 30f, 85f, paint)
                    canvas.drawText(y.toString(), 230f, 85f, paint)
                    paint.color = Color.CYAN
                    canvas.drawCircle(200f + frame * 65, 250f, 35f, paint)
                    detector.setScreenBitmap(bitmap, "route-test-$frame")
                    val dx = detector.detectNumber(Rect(20, 30, 185, 105), 15, NumberFormatType.AUTO)
                    val dy = detector.detectNumber(Rect(220, 30, 385, 105), 15, NumberFormatType.AUTO)
                    assertTrue("X $x was not detected: $dx", dx.isDetected)
                    assertTrue("Y $y was not detected: $dy", dy.isDetected)
                    assertEquals(x.toDouble(), requireNotNull(dx.numberDetected), .01)
                    assertEquals(y.toDouble(), requireNotNull(dy.numberDetected), .01)
                    detector.releaseScreenBitmap(bitmap)
                } finally { bitmap.recycle() }
            }
        } finally { detector.close() }
    }

    @Test fun fixedMapMarkerDoesNotMatchDifferentMap() {
        val detector = requireNotNull(NativeDetector.newInstance())
        detector.init()
        val frame = Bitmap.createBitmap(720, 400, Bitmap.Config.ARGB_8888)
        var template: Bitmap? = null
        try {
            val canvas = Canvas(frame)
            canvas.drawColor(Color.BLACK)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.MONOSPACE }
            canvas.drawText("MAP A", 30f, 85f, p)
            val area = Rect(20, 30, 190, 105)
            val marker = Bitmap.createBitmap(frame, area.left, area.top, area.width(), area.height())
            template = marker
            detector.setScreenBitmap(frame, "map-a")
            assertTrue(detector.detectImage(marker, marker.width, marker.height, area, 15).isDetected)
            detector.releaseScreenBitmap(frame)
            canvas.drawColor(Color.BLACK)
            canvas.drawText("CAVE", 30f, 85f, p)
            detector.setScreenBitmap(frame, "map-changed")
            assertFalse(detector.detectImage(marker, marker.width, marker.height, area, 15).isDetected)
            detector.releaseScreenBitmap(frame)
        } finally { detector.close(); template?.recycle(); frame.recycle() }
    }
}
