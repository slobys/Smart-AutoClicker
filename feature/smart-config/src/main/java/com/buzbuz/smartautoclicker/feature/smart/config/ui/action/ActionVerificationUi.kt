package com.buzbuz.smartautoclicker.feature.smart.config.ui.action

import android.content.Context
import android.text.InputType
import androidx.core.view.isVisible
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.Companion.showAsOverlay
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.domain.model.action.Click
import com.buzbuz.smartautoclicker.core.domain.model.action.Swipe
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.ui.bindings.fields.*
import com.buzbuz.smartautoclicker.core.ui.utils.MinMaxInputFilter
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.IncludeActionVerificationBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Shared, optional configuration. Rendering never writes back into the editor. */
internal class ActionVerificationUi(
    private val context: Context,
    private val binding: IncludeActionVerificationBinding,
    private val search: Boolean,
    private val events: () -> List<ScreenEvent>,
    onTarget: (Identifier?) -> Unit,
    onTimeout: (Long) -> Unit,
    onCount: (Int) -> Unit,
) {
    private var rendering = false
    init {
        binding.description.setText(if (search) R.string.action_search_help else R.string.action_verification_help)
        binding.buttonTarget.setOnClickListener {
            val choices = events()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.action_verification_pick)
                .setItems((listOf(context.getString(R.string.action_verification_off)) + choices.map { it.name }).toTypedArray()) { _, index ->
                    onTarget(choices.getOrNull(index - 1)?.id)
                }
                .setNegativeButton(android.R.string.cancel, null).create().showAsOverlay()
        }
        binding.fieldTimeout.apply {
            setLabel(if (search) R.string.action_search_interval else R.string.action_verification_timeout)
            textField.filters = arrayOf(MinMaxInputFilter(0, if (search) 30_000 else 300_000))
            setOnTextChangedListener { if (!rendering) onTimeout(it.toString().toLongOrNull() ?: 0) }
        }
        binding.fieldMaxSwipes.apply {
            setLabel(R.string.action_search_max)
            textField.filters = arrayOf(MinMaxInputFilter(0, 50))
            setOnTextChangedListener { if (!rendering) onCount(it.toString().toIntOrNull() ?: 0) }
        }
    }

    fun render(action: Action) {
        val target: Identifier?
        val timeout: Long
        val count: Int
        when (action) {
            is Click -> { target = action.verificationEventId; timeout = action.verificationTimeoutMs; count = 0 }
            is Swipe -> { target = action.verificationEventId; timeout = action.verificationTimeoutMs; count = action.searchMaxSwipes }
            else -> return
        }
        rendering = true
        try {
            val name = events().firstOrNull { it.id == target }?.name
            binding.buttonTarget.text = when {
                target == null -> context.getString(R.string.action_verification_off)
                name == null -> context.getString(R.string.action_verification_missing)
                else -> context.getString(R.string.action_verification_target, name)
            }
            binding.fieldTimeout.root.isVisible = target != null
            binding.fieldMaxSwipes.root.isVisible = target != null && search
            binding.fieldTimeout.setText(timeout.toString(), InputType.TYPE_CLASS_NUMBER)
            binding.fieldTimeout.setError(timeout !in (if (search) 200L else 400L)..(if (search) 30_000L else 300_000L))
            binding.fieldMaxSwipes.setText(count.toString(), InputType.TYPE_CLASS_NUMBER)
            binding.fieldMaxSwipes.setError(count !in 1..50)
        } finally { rendering = false }
    }
}
