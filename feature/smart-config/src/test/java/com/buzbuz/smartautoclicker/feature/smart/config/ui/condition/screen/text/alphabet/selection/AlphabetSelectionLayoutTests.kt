/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModel
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModelState
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.DialogAlphabetSelectionBinding
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.AlphabetModelItemAdapter
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.AlphabetSelectionItem
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.toUiState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlphabetSelectionLayoutTests {
    @Test fun confirmationIsFilledAndHasPressFeedbackInActualBottomSheetTheme() {
        val binding = DialogAlphabetSelectionBinding.inflate(LayoutInflater.from(dialogContext()))
        val button = binding.buttonConfirmAlphabet
        button.isEnabled = true
        val fill = button.backgroundTintList!!.getColorForState(button.drawableState, Color.TRANSPARENT)
        assertEquals("Confirmation must not inherit the bottom sheet's text-only button", 255, Color.alpha(fill))
        assertEquals(MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary), fill)
        assertTrue(ColorUtils.calculateContrast(button.currentTextColor, fill) >= 4.5)
        assertNotNull(button.rippleColor)
        assertTrue(button.rippleColor!!.getColorForState(intArrayOf(android.R.attr.state_pressed), 0) != 0)
    }

    @Test @Config(qualifiers = "zh-rCN-night-xhdpi")
    fun darkBottomSheetAlsoHasFilledAccessibleConfirmation() =
        confirmationIsFilledAndHasPressFeedbackInActualBottomSheetTheme()

    @Test fun applyingStateHasImmediateFeedbackAndPreventsRepeatOrDismiss() {
        val binding = DialogAlphabetSelectionBinding.inflate(LayoutInflater.from(dialogContext()))
        binding.updateConfirmationState(AlphabetConfirmationUiState(OCRAlphabet.CHINESE_SIMPLIFIED, false, isConfirming = true))
        assertEquals("正在应用…", binding.buttonConfirmAlphabet.text.toString())
        assertFalse(binding.buttonConfirmAlphabet.isEnabled)
        assertFalse(binding.layoutTopBar.buttonDismiss.isEnabled)
        assertEquals("正在应用：简体中文…", binding.textSelectedAlphabet.text.toString())
    }

    @Test fun failedConfirmationShowsReasonAndAllowsRetryOrClosing() {
        val binding = DialogAlphabetSelectionBinding.inflate(LayoutInflater.from(dialogContext()))
        binding.updateConfirmationState(AlphabetConfirmationUiState(
            OCRAlphabet.CHINESE_SIMPLIFIED, true, errorMessage = R.string.alphabet_selection_apply_failed,
        ))
        assertTrue(binding.textSelectedAlphabet.text.toString().contains("应用失败"))
        assertEquals(MaterialColors.getColor(binding.textSelectedAlphabet, androidx.appcompat.R.attr.colorError),
            binding.textSelectedAlphabet.currentTextColor)
        assertTrue(binding.buttonConfirmAlphabet.isEnabled)
        assertTrue(binding.layoutTopBar.buttonDismiss.isEnabled)
        assertEquals("确定并返回", binding.buttonConfirmAlphabet.text.toString())
        binding.updateConfirmationState(AlphabetConfirmationUiState(OCRAlphabet.LATIN, true))
        assertEquals("已选择：拉丁字母。点击“确定”后生效。", binding.textSelectedAlphabet.text.toString())
        assertEquals(MaterialColors.getColor(binding.textSelectedAlphabet, com.google.android.material.R.attr.colorOnSurfaceVariant),
            binding.textSelectedAlphabet.currentTextColor)
    }

    @Test fun missingModelDisablesConfirmationAndExplainsWhatToDo() {
        val binding = DialogAlphabetSelectionBinding.inflate(LayoutInflater.from(dialogContext()))
        binding.updateConfirmationState(AlphabetConfirmationUiState(OCRAlphabet.CHINESE_SIMPLIFIED, false))
        assertFalse(binding.buttonConfirmAlphabet.isEnabled)
        assertTrue(binding.textSelectedAlphabet.text.toString().contains("请先下载"))
        assertTrue(binding.layoutTopBar.buttonDismiss.isEnabled)
    }

    @Test fun portraitFooterIsVisibleAndDoesNotOverlapScrollableList() = checkLayout(360, 640, "portrait")
    @Test fun narrowFooterAndTitleRemainReadable() = checkLayout(280, 480, "narrow")
    @Test @Config(qualifiers = "zh-rCN-land-xhdpi")
    fun landscapeFooterIsVisible() = checkLayout(640, 360, "landscape")

    private fun checkLayout(widthDp: Int, heightDp: Int, name: String) {
        val context = dialogContext()
        val binding = DialogAlphabetSelectionBinding.inflate(LayoutInflater.from(context))
        binding.layoutTopBar.apply {
            dialogTitle.setText(R.string.dialog_title_alphabet_selection)
            buttonSave.visibility = View.GONE
            buttonDelete.visibility = View.GONE
        }
        binding.textSelectedAlphabet.text = context.getString(R.string.alphabet_selection_ready, "简体中文")
        binding.buttonConfirmAlphabet.isEnabled = true
        val adapter = AlphabetModelItemAdapter {}
        binding.layoutLoadableList.apply {
            list.layoutManager = LinearLayoutManager(context)
            list.adapter = adapter
            list.visibility = View.VISIBLE
            loading.visibility = View.GONE
        }
        adapter.submitList(buildList {
            add(AlphabetSelectionItem.Header(R.string.item_alphabet_selection_confirm_header))
            OCRAlphabet.entries.forEach { alphabet ->
                add(OCRModel.Recognition(
                    if (alphabet == OCRAlphabet.CHINESE_SIMPLIFIED || alphabet == OCRAlphabet.LATIN) OCRModelState.Installed("path")
                    else OCRModelState.Downloadable, alphabet,
                ).toUiState(alphabet == OCRAlphabet.CHINESE_SIMPLIFIED))
            }
        })
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        binding.root.layout(0, 0, width, height)
        val button = binding.buttonConfirmAlphabet
        val footer = button.parent as View
        assertEquals("确定并返回", button.text.toString())
        assertTrue(button.height >= 56 * density)
        assertTrue(footer.top >= binding.layoutLoadableList.root.bottom)
        assertTrue(footer.bottom <= height)
        assertTrue(binding.layoutLoadableList.root.height > 100 * density)
        assertTrue(binding.layoutTopBar.dialogTitle.width > 100 * density)
        var clicked = false
        button.setOnClickListener { clicked = true }
        assertTrue(button.performClick())
        assertTrue(clicked)
        System.getenv("KLICKR_ALPHABET_PREVIEW_DIR")?.let { dir ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            binding.root.draw(Canvas(bitmap))
            File(dir).mkdirs()
            File(dir, "language-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun dialogContext() = BottomSheetDialog(
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), com.buzbuz.smartautoclicker.core.ui.R.style.AppTheme_TranslucentActivity),
    ).context
}
