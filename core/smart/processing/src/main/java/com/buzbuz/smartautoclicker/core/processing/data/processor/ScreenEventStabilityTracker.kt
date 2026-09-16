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
package com.buzbuz.smartautoclicker.core.processing.data.processor

/** Confirms screen events only after enough positive detections in a short rolling window. */
internal class ScreenEventStabilityTracker(
    private val requiredHits: Int,
    private val windowSize: Int,
) {

    private val histories: MutableMap<Long, ArrayDeque<Boolean>> = mutableMapOf()

    init {
        require(windowSize > 0) { "windowSize must be positive" }
        require(requiredHits in 1..windowSize) { "requiredHits must be within the window" }
    }

    fun isConfirmed(eventId: Long, isFulfilled: Boolean): Boolean {
        val history = histories.getOrPut(eventId) { ArrayDeque(windowSize) }
        if (history.size == windowSize) history.removeFirst()
        history.addLast(isFulfilled)

        // Never trigger on a frame that did not fulfill the event, even if older frames did.
        return isFulfilled && history.count { it } >= requiredHits
    }

    fun reset(eventId: Long) {
        histories.remove(eventId)
    }

    fun resetAll() {
        histories.clear()
    }
}
