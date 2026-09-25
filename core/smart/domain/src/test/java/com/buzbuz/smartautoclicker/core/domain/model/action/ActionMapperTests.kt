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

import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.toDomain
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.toEntity

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ActionMapperTests {

    @Test
    fun clickVerification_roundTripAndTemporaryReferenceRemapping() {
        val click = ActionTestsData.getNewClick(eventId = ActionTestsData.ACTION_EVENT_ID).copy(
            verificationEventId = com.buzbuz.smartautoclicker.core.base.identifier.Identifier(databaseId = 91),
            verificationTimeoutMs = 20_000,
        )
        val complete = com.buzbuz.smartautoclicker.core.database.entity.CompleteActionEntity(click.toEntity(), emptyList(), emptyList())
        assertEquals(click, complete.toDomain())
        val imported = complete.toDomain(cleanIds = true) as Click
        assertEquals(91L, imported.verificationEventId!!.tempId)
        val state = com.buzbuz.smartautoclicker.core.domain.data.ScenarioUpdateState()
        state.addEventIdMapping(91, 201)
        assertEquals(201L, state.getVerificationEventDatabaseId(imported))
    }

    @Test
    fun swipeSearch_roundTripPreservesLimits() {
        val swipe = ActionTestsData.getNewSwipe(eventId = ActionTestsData.ACTION_EVENT_ID).copy(
            verificationEventId = com.buzbuz.smartautoclicker.core.base.identifier.Identifier(databaseId = 91),
            verificationTimeoutMs = 600, searchMaxSwipes = 9,
        )
        val complete = com.buzbuz.smartautoclicker.core.database.entity.CompleteActionEntity(swipe.toEntity(), emptyList(), emptyList())
        assertEquals(swipe, complete.toDomain())
        assertEquals(91L, (complete.toDomain(cleanIds = true) as Swipe).verificationEventId!!.tempId)
    }

    @Test
    fun waitTargets_importedIdsAndReferenceReplacementStayIndependent() {
        val pause = ActionTestsData.getNewPause(eventId = ActionTestsData.ACTION_EVENT_ID).copy(
            waitMode = Pause.WaitMode.TARGET_APPEARS,
            waitTargetEventId = com.buzbuz.smartautoclicker.core.base.identifier.Identifier(databaseId = 91),
            timeoutBehavior = Pause.TimeoutBehavior.EXECUTE_FALLBACK,
            fallbackEventId = com.buzbuz.smartautoclicker.core.base.identifier.Identifier(databaseId = 92),
        )
        val complete = com.buzbuz.smartautoclicker.core.database.entity.CompleteActionEntity(pause.toEntity(), emptyList(), emptyList())
        val imported = complete.toDomain(cleanIds = true) as Pause
        assertEquals(91L, imported.waitTargetEventId!!.tempId)
        assertEquals(92L, imported.fallbackEventId!!.tempId)
        val replaced = pause.replaceEventReference(ActionEventReferenceSlot.FALLBACK,
            com.buzbuz.smartautoclicker.core.base.identifier.Identifier(databaseId = 93)) as Pause
        assertEquals(pause.waitTargetEventId, replaced.waitTargetEventId)
        assertEquals(93L, replaced.fallbackEventId!!.databaseId)
        assertEquals(2, replaced.eventReferences().size)
        assertEquals(0, replaced.copy(waitMode = Pause.WaitMode.FIXED_DELAY).eventReferences().size)
    }

    @Test
    fun click_toEntity() {
        assertEquals(
            ActionTestsData.getNewClickEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewClick(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity(),
        )
    }

    @Test
    fun click_toDomain() {
        assertEquals(
            ActionTestsData.getNewClick(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewClickEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }

    @Test
    fun swipe_toEntity() {
        assertEquals(
            ActionTestsData.getNewSwipeEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewSwipe(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity(),
        )
    }

    @Test
    fun swipe_toDomain() {
        assertEquals(
            ActionTestsData.getNewSwipe(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewSwipeEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }

    @Test
    fun pause_toEntity() {
        assertEquals(
            ActionTestsData.getNewPauseEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewPause(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity(),
        )
    }

    @Test
    fun pause_toDomain() {
        assertEquals(
            ActionTestsData.getNewPause(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewPauseEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }

    @Test
    fun intent_toEntity() {
        assertEquals(
            ActionTestsData.getNewIntentEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewIntent(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity()
        )
    }

    @Test
    fun intent_toDomain() {
        assertEquals(
            ActionTestsData.getNewIntent(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewIntentEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }

    @Test
    fun toggleEvent_toEntity() {
        assertEquals(
            ActionTestsData.getNewToggleEventEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewToggleEvent(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity()
        )
    }

    @Test
    fun toggleEvent_toDomain() {
        assertEquals(
            ActionTestsData.getNewToggleEvent(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewToggleEventEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }

    @Test
    fun changeCounter_toEntity() {
        assertEquals(
            ActionTestsData.getNewChangeCounterEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewChangeCounter(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity(),
        )
    }

    @Test
    fun changeCounter_toDomain() {
        assertEquals(
            ActionTestsData.getNewChangeCounter(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewChangeCounterEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }

    @Test
    fun changeCounter_detectedNumberAndAbsoluteDifference_roundTrip() {
        val conditionId = 987L
        val domain = ActionTestsData.getNewChangeCounter(
            eventId = ActionTestsData.ACTION_EVENT_ID,
            operation = ChangeCounter.OperationType.ABS_DIFF,
            detectedNumberConditionId = conditionId,
        )

        assertEquals(
            ActionTestsData.getNewChangeCounterEntity(
                eventId = ActionTestsData.ACTION_EVENT_ID,
                operation = ChangeCounter.OperationType.ABS_DIFF,
                detectedNumberConditionId = conditionId,
            ).action,
            domain.toEntity(),
        )
        assertEquals(
            domain,
            ActionTestsData.getNewChangeCounterEntity(
                eventId = ActionTestsData.ACTION_EVENT_ID,
                operation = ChangeCounter.OperationType.ABS_DIFF,
                detectedNumberConditionId = conditionId,
            ).toDomain(),
        )
    }

    @Test
    fun notification_toEntity() {
        assertEquals(
            ActionTestsData.getNewNotificationEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewNotification(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity(),
        )
    }

    @Test
    fun notification_toDomain() {
        assertEquals(
            ActionTestsData.getNewNotification(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewNotificationEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }

    @Test
    fun systemAction_toEntity() {
        assertEquals(
            ActionTestsData.getNewSystemActionEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewSystemAction(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity(),
        )
    }

    @Test
    fun systemAction_toDomain() {
        assertEquals(
            ActionTestsData.getNewSystemAction(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewSystemActionEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }

    @Test
    fun setText_toEntity() {
        assertEquals(
            ActionTestsData.getNewSetTextEntity(eventId = ActionTestsData.ACTION_EVENT_ID).action,
            ActionTestsData.getNewSetText(eventId = ActionTestsData.ACTION_EVENT_ID).toEntity(),
        )
    }

    @Test
    fun setText_toDomain() {
        assertEquals(
            ActionTestsData.getNewSetText(eventId = ActionTestsData.ACTION_EVENT_ID),
            ActionTestsData.getNewSetTextEntity(eventId = ActionTestsData.ACTION_EVENT_ID).toDomain(),
        )
    }
}
