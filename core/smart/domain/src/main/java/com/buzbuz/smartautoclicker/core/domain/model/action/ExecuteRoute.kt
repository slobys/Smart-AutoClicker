/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.domain.model.action

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier

/** A reference to a local route. Missing/deleted routes fail closed; names never resolve identity. */
data class ExecuteRoute(
    override val id: Identifier,
    override val eventId: Identifier,
    override val name: String?,
    override var priority: Int,
    val routeId: String = "",
    val timeoutMs: Long = 300_000,
) : Action() {
    override fun isComplete() = super.isComplete() && routeId.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) && timeoutMs in 5_000..3_600_000
    override fun hashCodeNoIds() = 31 * (31 * name.hashCode() + routeId.hashCode()) + timeoutMs.hashCode()
    override fun deepCopy() = copy()
}
