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
package com.buzbuz.smartautoclicker.core.processing.tests.processor

import com.buzbuz.smartautoclicker.core.processing.data.processor.ScreenEventStabilityTracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenEventStabilityTrackerTests {

    @Test
    fun `one positive frame should not be confirmed`() {
        val tracker = ScreenEventStabilityTracker(requiredHits = 2, windowSize = 3)

        assertFalse(tracker.isConfirmed(eventId = 1L, isFulfilled = true))
    }

    @Test
    fun `two positive frames should be confirmed`() {
        val tracker = ScreenEventStabilityTracker(requiredHits = 2, windowSize = 3)

        assertFalse(tracker.isConfirmed(eventId = 1L, isFulfilled = true))
        assertTrue(tracker.isConfirmed(eventId = 1L, isFulfilled = true))
    }

    @Test
    fun `two positives in three frames should tolerate one missed frame`() {
        val tracker = ScreenEventStabilityTracker(requiredHits = 2, windowSize = 3)

        assertFalse(tracker.isConfirmed(eventId = 1L, isFulfilled = true))
        assertFalse(tracker.isConfirmed(eventId = 1L, isFulfilled = false))
        assertTrue(tracker.isConfirmed(eventId = 1L, isFulfilled = true))
    }

    @Test
    fun `reset should discard previous positive frames`() {
        val tracker = ScreenEventStabilityTracker(requiredHits = 2, windowSize = 3)

        assertFalse(tracker.isConfirmed(eventId = 1L, isFulfilled = true))
        tracker.reset(eventId = 1L)
        assertFalse(tracker.isConfirmed(eventId = 1L, isFulfilled = true))
    }
}
