/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.domain.model.action

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.database.entity.CompleteActionEntity
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.toDomain
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.toEntity
import org.junit.Assert.*
import org.junit.Test

class ExecuteRouteTests {
    private val action = ExecuteRoute(Identifier(databaseId = 1), Identifier(databaseId = 2), "Walk", 3,
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 600_000)
    @Test fun entityRoundTripRetainsIdentityAndTimeout() {
        assertEquals(action, CompleteActionEntity(action.toEntity(), emptyList(), emptyList()).toDomain())
    }
    @Test fun missingOrUnsafeRouteCannotBeSaved() {
        assertFalse(action.copy(routeId = "../secret").isComplete())
        assertFalse(action.copy(routeId = "").isComplete())
        assertThrows(IllegalStateException::class.java) { action.copy(routeId = "").toEntity() }
    }
    @Test fun timeoutMustBeBounded() {
        assertFalse(action.copy(timeoutMs = 0).isComplete())
        assertFalse(action.copy(timeoutMs = Long.MAX_VALUE).isComplete())
    }
    @Test fun copyKeepsReferenceAndChangesOnlyActionIdentity() {
        val copied = action.copyBase(id = Identifier(databaseId = 9)) as ExecuteRoute
        assertEquals(action.routeId, copied.routeId); assertEquals(action.timeoutMs, copied.timeoutMs)
        assertEquals(action, action.deepCopy())
    }
}
