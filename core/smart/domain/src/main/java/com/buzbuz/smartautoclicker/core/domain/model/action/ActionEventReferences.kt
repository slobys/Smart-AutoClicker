package com.buzbuz.smartautoclicker.core.domain.model.action

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier

enum class ActionEventReferenceSlot { VERIFICATION, WAIT_TARGET, FALLBACK }

/** Active single-event references (event-control actions manage their own list). */
fun Action.eventReferences(): Map<ActionEventReferenceSlot, Identifier> = buildMap {
    when (val action = this@eventReferences) {
        is Click -> action.verificationEventId?.let { put(ActionEventReferenceSlot.VERIFICATION, it) }
        is Swipe -> action.verificationEventId?.let { put(ActionEventReferenceSlot.VERIFICATION, it) }
        is Pause -> if (action.waitMode != Pause.WaitMode.FIXED_DELAY) {
            if (action.waitMode == Pause.WaitMode.TARGET_APPEARS || action.waitMode == Pause.WaitMode.TARGET_DISAPPEARS) {
                action.waitTargetEventId?.let { put(ActionEventReferenceSlot.WAIT_TARGET, it) }
            }
            if (action.timeoutBehavior == Pause.TimeoutBehavior.EXECUTE_FALLBACK) {
                action.fallbackEventId?.let { put(ActionEventReferenceSlot.FALLBACK, it) }
            }
        }
        else -> Unit
    }
}

fun Action.replaceEventReference(slot: ActionEventReferenceSlot, replacement: Identifier): Action = when (this) {
    is Click -> if (slot == ActionEventReferenceSlot.VERIFICATION) copy(verificationEventId = replacement) else this
    is Swipe -> if (slot == ActionEventReferenceSlot.VERIFICATION) copy(verificationEventId = replacement) else this
    is Pause -> when (slot) {
        ActionEventReferenceSlot.WAIT_TARGET -> copy(waitTargetEventId = replacement)
        ActionEventReferenceSlot.FALLBACK -> copy(fallbackEventId = replacement)
        else -> this
    }
    else -> this
}
