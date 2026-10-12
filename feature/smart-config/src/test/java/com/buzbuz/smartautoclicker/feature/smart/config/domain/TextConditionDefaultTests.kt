/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.domain

import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.feature.smart.config.data.ScenarioEditor
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TextConditionDefaultTests {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun builder(): EditedItemsBuilder {
        val editor = ScenarioEditor()
        editor.startEdition(Scenario(Identifier(databaseId = 1), "Test", 600), emptyList(), emptyList(), emptyList())
        return EditedItemsBuilder(mockk<BitmapRepository>(), editor).also {
            editor.startEventEdition(it.createNewImageEvent(context))
        }
    }

    @Test fun everyNewTextConditionDefaultsToSimplifiedChinese() {
        val builder = builder()
        repeat(3) { assertEquals(OCRAlphabet.CHINESE_SIMPLIFIED, builder.createNewTextCondition(context).alphabet) }
    }

    @Test fun copyingExistingTextConditionsPreservesTheirLanguageAndRoi() {
        val builder = builder()
        OCRAlphabet.entries.forEach { language ->
            val original = builder.createNewTextCondition(context).copy(
                alphabet = language, text = "target", detectionArea = android.graphics.Rect(10, 20, 110, 60), threshold = 15,
            )
            val copy = builder.createNewScreenConditionFrom(original) as ScreenCondition.Text
            assertEquals(language, copy.alphabet)
            assertEquals(original.detectionArea, copy.detectionArea)
            assertEquals(original.threshold, copy.threshold)
            assertEquals(original.text, copy.text)
        }
    }
}
