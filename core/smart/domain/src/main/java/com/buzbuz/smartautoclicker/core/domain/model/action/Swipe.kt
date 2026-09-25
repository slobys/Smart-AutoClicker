/*
 * Copyright (C) 2024 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.core.domain.model.action

import android.graphics.Point
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier

/**
 * Swipe action.
 *
 * @param id the unique identifier for the action.
 * @param eventId the identifier of the event for this action.
 * @param name the name of the action.
 * @param swipeDuration the duration between the swipe start and end in milliseconds.
 * @param from the x position of the swipe start.
 * @param to the x position of the swipe end.
 */
data class Swipe(
    override val id: Identifier,
    override val eventId: Identifier,
    override val name: String? = null,
    override var priority: Int,
    val swipeDuration: Long? = null,
    val from: Point? = null,
    val to: Point? = null,
    val verificationEventId: Identifier? = null,
    val verificationTimeoutMs: Long = 1_500L,
    val searchMaxSwipes: Int = 5,
) : Action() {

    override fun isComplete(): Boolean =
        super.isComplete() && swipeDuration != null && from != null && to != null &&
            (verificationEventId == null || (searchMaxSwipes in 1..50 && verificationTimeoutMs in 200..30_000))

    override fun hashCodeNoIds(): Int =
        name.hashCode() + swipeDuration.hashCode() + from.hashCode() + to.hashCode() +
            verificationEventId.hashCode() + verificationTimeoutMs.hashCode() + searchMaxSwipes

    override fun deepCopy(): Swipe = copy(name = "" + name)
}
