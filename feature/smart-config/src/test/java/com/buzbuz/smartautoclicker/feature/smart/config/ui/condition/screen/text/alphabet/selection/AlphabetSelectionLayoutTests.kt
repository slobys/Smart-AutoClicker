/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
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
    @Test fun portraitFooterIsVisibleAndDoesNotOverlapScrollableList() = checkLayout(360, 640, "portrait")
    @Test fun narrowFooterAndTitleRemainReadable() = checkLayout(280, 480, "narrow")
    @Test @Config(qualifiers = "zh-rCN-land-xhdpi")
    fun landscapeFooterIsVisible() = checkLayout(640, 360, "landscape")

    private fun checkLayout(widthDp: Int, heightDp: Int, name: String) {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.ScenarioConfigTheme)
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
        assertEquals("确定", button.text.toString())
        assertTrue(button.height >= 48 * density)
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
}
