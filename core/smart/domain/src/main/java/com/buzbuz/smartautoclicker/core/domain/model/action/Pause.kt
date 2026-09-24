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

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier

/**
 * Pause action.
 *
 * @param id the unique identifier for the action.
 * @param eventId the identifier of the event for this action.
 * @param name the name of the action.
 * @param pauseDuration the duration of the pause in milliseconds.
 */
data class Pause(
    override val id: Identifier,
    override val eventId: Identifier,
    override val name: String? = null,
    override var priority: Int,
    val pauseDuration: Long? = null,
    val waitMode: WaitMode = WaitMode.FIXED_DELAY,
    val waitTargetEventId: Identifier? = null,
    val timeoutBehavior: TimeoutBehavior = TimeoutBehavior.STOP,
    val maxRetries: Int = 0,
    val fallbackEventId: Identifier? = null,
    val confirmationFrames: Int = DEFAULT_CONFIRMATION_FRAMES,
    val changeThresholdPercent: Int = DEFAULT_CHANGE_THRESHOLD_PERCENT,
) : Action() {

    enum class WaitMode { FIXED_DELAY, TARGET_APPEARS, TARGET_DISAPPEARS, SCREEN_STABLE, SCREEN_CHANGED }
    enum class TimeoutBehavior { RETRY, SKIP, STOP, EXECUTE_FALLBACK }

    override fun isComplete(): Boolean =
        super.isComplete() &&
            pauseDuration != null && pauseDuration > 0 &&
            confirmationFrames > 0 && changeThresholdPercent in 1..100 && maxRetries >= 0 &&
            (waitMode !in TARGET_WAIT_MODES || waitTargetEventId != null) &&
            (timeoutBehavior != TimeoutBehavior.EXECUTE_FALLBACK || fallbackEventId != null)

    override fun hashCodeNoIds(): Int =
        name.hashCode() + pauseDuration.hashCode() + waitMode.hashCode() + waitTargetEventId.hashCode() + timeoutBehavior.hashCode() +
            maxRetries + fallbackEventId.hashCode() + confirmationFrames + changeThresholdPercent


    override fun deepCopy(): Pause = copy(name = "" + name)
}

const val DEFAULT_CONFIRMATION_FRAMES = 3
const val DEFAULT_CHANGE_THRESHOLD_PERCENT = 4
private val TARGET_WAIT_MODES = setOf(Pause.WaitMode.TARGET_APPEARS, Pause.WaitMode.TARGET_DISAPPEARS)
