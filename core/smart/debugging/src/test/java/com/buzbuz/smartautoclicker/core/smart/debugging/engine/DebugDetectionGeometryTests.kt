/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.smart.debugging.engine

import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.processing.domain.model.ProcessedConditionResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DebugDetectionGeometryTests {
    private val image = ScreenCondition.Image(Identifier(databaseId = 1), Identifier(databaseId = 1),
        "Image", 20, true, 0, "template", Rect(0, 0, 3, 5), EXACT)
    private fun result(point: Point?, size: Point?, detected: Boolean = true) =
        ProcessedConditionResult.Screen(detected, detected, image, 0.98, point, size)

    @Test fun oddSizedHitKeepsAllPixelsWithoutPadding() {
        assertEquals(Rect(49, 58, 52, 63), result(Point(50, 60), Point(3, 5)).getDetectionArea())
    }
    @Test fun colorPointNeverExpandsOrRecentresTheSearchRectangle() {
        val color = ScreenCondition.Color(Identifier(databaseId = 1), Identifier(databaseId = 1),
            "Color", 20, true, 0, Color.BLUE, Rect(10, 10, 100, 100), true)
        assertNull(result(Point(30, 30), Point(1, 1)).copy(condition = color).getDetectionArea())
    }
    @Test fun onePixelHitAtOriginIsNotMistakenForMissingCoordinates() {
        assertEquals(Rect(0, 0, 1, 1), result(Point(0, 0), Point(1, 1)).getDetectionArea())
        assertNull(result(Point(0, 0), Point(1, 1), false).getDetectionArea())
    }
    @Test fun invalidSizeOrMissingPositionHasNoPhantomHit() {
        assertNull(result(null, Point(1, 1)).getDetectionArea())
        assertNull(result(Point(40, 40), Point()).getDetectionArea())
        assertNull(result(Point(40, 40), Point(-1, 5)).getDetectionArea())
    }
}
