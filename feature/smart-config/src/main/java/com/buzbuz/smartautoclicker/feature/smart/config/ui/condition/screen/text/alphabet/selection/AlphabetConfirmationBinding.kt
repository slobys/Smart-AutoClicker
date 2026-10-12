/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.selection

import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.DialogAlphabetSelectionBinding
import com.buzbuz.smartautoclicker.feature.smart.config.ui.common.formatters.getDisplayNameResId
import com.google.android.material.color.MaterialColors

/** Keep the progress, error and enabled states consistent with the confirmation check. */
internal fun DialogAlphabetSelectionBinding.updateConfirmationState(state: AlphabetConfirmationUiState) {
    buttonConfirmAlphabet.isEnabled = state.canConfirm
    buttonConfirmAlphabet.setText(
        if (state.isConfirming) R.string.alphabet_selection_applying else R.string.alphabet_selection_confirm,
    )
    layoutTopBar.buttonDismiss.isEnabled = !state.isConfirming
    val context = root.context
    val name = state.alphabet?.let { context.getString(it.getDisplayNameResId()) }.orEmpty()
    textSelectedAlphabet.text = when {
        state.isConfirming -> context.getString(R.string.alphabet_selection_applying_language, name)
        state.errorMessage != null -> context.getString(state.errorMessage)
        else -> context.getString(
            if (state.canConfirm) R.string.alphabet_selection_ready else R.string.alphabet_selection_not_ready, name,
        )
    }
    textSelectedAlphabet.setTextColor(MaterialColors.getColor(
        textSelectedAlphabet,
        if (state.errorMessage != null) androidx.appcompat.R.attr.colorError
        else com.google.android.material.R.attr.colorOnSurfaceVariant,
    ))
}
