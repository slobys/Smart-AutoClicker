package com.buzbuz.smartautoclicker.localservice

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "zh-rCN-w960dp-h600dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuntimeScenarioDialogTests {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dialogs = mutableListOf<AlertDialog>()
    @After fun closeDialogs() { dialogs.forEach { it.dismiss() }; ShadowLooper.idleMainLooper() }

    @Test fun compactSwitcherUsesTwoColumnsAndFitsThreeRows() {
        val sizing = runtimeSwitcherSizing(context, 11)
        assertEquals(2, sizing.columns)
        assertEquals(sizing.rowHeightPx * 3, sizing.listHeightPx)
        assertTrue(sizing.widthPx < context.resources.displayMetrics.widthPixels)
        val dialog = switcher(11)
        assertEquals(2, (dialog.findViewById<RecyclerView>(R.id.scenario_list)!!.layoutManager as GridLayoutManager).spanCount)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility != View.VISIBLE)
        assertTrue(dialog.findViewById<MaterialButton>(R.id.button_cancel)!!.isEnabled)
    }

    @Test fun fewScriptsDoNotReserveEmptyRows() {
        val sizing = runtimeSwitcherSizing(context, 2)
        assertEquals(sizing.rowHeightPx, sizing.listHeightPx)
        assertEquals(0, runtimeSwitcherSizing(context, 0).listHeightPx)
    }

    @Test fun cancelIsAnActualButtonAndDismissesImmediately() {
        var selections = 0
        val dialog = switcher(4, { selections++ })
        dialog.findViewById<MaterialButton>(R.id.button_cancel)!!.performClick()
        assertFalse(dialog.isShowing)
        assertEquals(0, selections)
    }

    @Test fun emptySwitcherStillOffersCreation() {
        var created = 0
        val dialog = switcher(0, onCreate = { created++ })
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.scenario_empty)!!.visibility)
        dialog.findViewById<MaterialButton>(R.id.button_create)!!.performClick()
        assertEquals(1, created)
        assertFalse(dialog.isShowing)
    }

    @Test fun narrowScreenKeepsDialogWithinBounds() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w320dp-h640dp-port-mdpi")
        val sizing = runtimeSwitcherSizing(context, 50)
        assertEquals(1, sizing.columns)
        assertTrue(sizing.widthPx <= context.resources.displayMetrics.widthPixels - 16)
    }

    @Test fun largeFontsUseReadableSingleColumn() {
        val resources = context.resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(android.content.res.Configuration(resources.configuration).apply { fontScale = 1.8f }, resources.displayMetrics)
        assertEquals(1, runtimeSwitcherSizing(context, 11).columns)
    }

    @Test fun creatorRejectsWhitespaceWithVisibleFieldError() = runTest {
        var created = 0
        val dialog = creator(backgroundScope) { _, _ -> created++ }
        dialog.findViewById<TextInputEditText>(R.id.scenario_name)!!.setText("   ")
        dialog.findViewById<MaterialButton>(R.id.button_create)!!.performClick()
        runCurrent()
        assertEquals(0, created)
        assertNotNull(dialog.findViewById<TextInputLayout>(R.id.name_layout)!!.error)
        assertTrue(dialog.isShowing)
    }

    @Test fun creatorMarksInitialTypeAndMovesCheckmarkWhenTypeChanges() = runTest {
        var createdKind: RuntimeScenarioKind? = null
        val dialog = creator(backgroundScope, RuntimeScenarioKind.SMART) { _, kind -> createdKind = kind }
        val smart = dialog.findViewById<MaterialButton>(R.id.type_smart)!!
        val simple = dialog.findViewById<MaterialButton>(R.id.type_dumb)!!
        assertTypeSelection(smart, simple)
        simple.performClick()
        assertTypeSelection(simple, smart)
        // Tapping the selected segment must not leave both types unselected.
        simple.performClick()
        assertTypeSelection(simple, smart)
        smart.performClick()
        assertTypeSelection(smart, simple)
        dialog.findViewById<MaterialButton>(R.id.button_create)!!.performClick()
        runCurrent()
        assertEquals(RuntimeScenarioKind.SMART, createdKind)
    }

    @Test fun creatorKeepsChosenTypeVisibleWhileCreating() = runTest {
        val dialog = creator(backgroundScope) { _, _ -> awaitCancellation() }
        dialog.findViewById<MaterialButton>(R.id.button_create)!!.performClick()
        runCurrent()
        val simple = dialog.findViewById<MaterialButton>(R.id.type_dumb)!!
        assertFalse(simple.isEnabled)
        assertFalse(dialog.findViewById<MaterialAutoCompleteTextView>(R.id.group_name)!!.isEnabled)
        assertTypeSelection(simple, dialog.findViewById<MaterialButton>(R.id.type_smart)!!)
    }

    @Test fun groupSuggestionsDoNotOverwriteInputAndAreCollectedOnlyWhileDialogIsOpen() = runTest {
        val groups = MutableStateFlow(listOf("Daily", "任务", "Daily"))
        var savedGroup: String? = null
        val dialog = showRuntimeScenarioCreator(context, backgroundScope, RuntimeScenarioKind.SMART, {}, groups) { _, _, group ->
            savedGroup = group
        }.also { dialogs += it }
        runCurrent()
        val input = dialog.findViewById<MaterialAutoCompleteTextView>(R.id.group_name)!!
        assertEquals(2, input.adapter.count)
        input.setText(" 新分组 ", false)
        groups.value = listOf("Daily", "任务", "备用")
        runCurrent()
        assertEquals(" 新分组 ", input.text.toString())
        assertEquals(3, input.adapter.count)
        dialog.findViewById<MaterialButton>(R.id.button_create)!!.performClick()
        runCurrent(); ShadowLooper.idleMainLooper(); runCurrent()
        assertEquals("新分组", savedGroup)
        assertEquals(0, groups.subscriptionCount.value)
    }

    @Test fun typedExistingGroupUsesCanonicalNameAndBlankStaysUngrouped() = runTest {
        val groups = MutableStateFlow(listOf("Daily"))
        val saved = mutableListOf<String>()
        for (name in listOf(" daily ", " ")) {
            val dialog = showRuntimeScenarioCreator(context, backgroundScope, RuntimeScenarioKind.DUMB, {}, groups) { _, _, group ->
                saved += group
            }.also { dialogs += it }
            runCurrent()
            dialog.findViewById<MaterialAutoCompleteTextView>(R.id.group_name)!!.setText(name, false)
            dialog.findViewById<MaterialButton>(R.id.button_create)!!.performClick()
            runCurrent(); ShadowLooper.idleMainLooper()
        }
        assertEquals(listOf("Daily", ""), saved)
    }

    @Test fun currentScriptUsesFilledCardAndCheckmarkAndRecyclingClearsSelection() {
        assertSwitcherSelectionAndRecycling()
    }

    @Test @Config(qualifiers = "zh-rCN-w960dp-h600dp-land-night-xhdpi")
    fun selectionRemainsReadableInDarkTheme() = runTest {
        val dialog = creator(backgroundScope) { _, _ -> }
        assertTypeSelection(dialog.findViewById<MaterialButton>(R.id.type_dumb)!!,
            dialog.findViewById<MaterialButton>(R.id.type_smart)!!)
        assertSwitcherSelectionAndRecycling()
    }

    @Test fun creatorPreventsRepeatedSubmissionAndShowsBusyState() = runTest {
        val finish = CompletableDeferred<Unit>()
        var created = 0
        val dialog = creator(backgroundScope) { _, _ -> created++; finish.await() }
        val button = dialog.findViewById<MaterialButton>(R.id.button_create)!!
        button.performClick(); button.performClick(); runCurrent()
        assertEquals(1, created)
        assertFalse(button.isEnabled)
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.creation_progress)!!.visibility)
        assertFalse(dialog.findViewById<MaterialButton>(R.id.button_cancel)!!.isEnabled)
        finish.complete(Unit); runCurrent()
        assertFalse(dialog.isShowing)
    }

    @Test fun creatorFailureIsVisibleAndAllowsRetry() = runTest {
        val dialog = creator(backgroundScope) { _, _ -> error("DB failure") }
        dialog.findViewById<MaterialButton>(R.id.button_create)!!.performClick(); runCurrent()
        assertTrue(dialog.isShowing)
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.creation_status)!!.visibility)
        assertTrue(dialog.findViewById<MaterialButton>(R.id.button_create)!!.isEnabled)
    }

    @Test fun dismissingCreatorCancelsPendingWork() = runTest {
        var cancelled = false
        val dialog = creator(backgroundScope) { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }
        dialog.findViewById<MaterialButton>(R.id.button_create)!!.performClick(); runCurrent()
        dialog.dismiss(); ShadowLooper.idleMainLooper(); runCurrent()
        assertTrue(cancelled)
    }

    private fun assertTypeSelection(selected: MaterialButton, unselected: MaterialButton) {
        assertTrue(selected.isChecked)
        assertNotNull(selected.icon)
        assertTrue(selected.typeface?.isBold == true || selected.paint.isFakeBoldText)
        assertFalse(unselected.isChecked)
        assertNull(unselected.icon)
        assertFalse(unselected.typeface?.isBold == true || unselected.paint.isFakeBoldText)
        val selectedBackground = selected.backgroundTintList!!.getColorForState(selected.drawableState, 0)
        assertEquals(MaterialColors.getColor(selected, androidx.appcompat.R.attr.colorPrimary), selectedBackground)
        assertReadable(selected.currentTextColor, selectedBackground)
        assertReadable(selected.iconTint!!.getColorForState(selected.drawableState, 0), selectedBackground)
        val surface = MaterialColors.getColor(unselected, com.google.android.material.R.attr.colorSurface)
        val unselectedBackground = ColorUtils.compositeColors(
            unselected.backgroundTintList!!.getColorForState(unselected.drawableState, 0), surface)
        assertNotEquals(selectedBackground, unselectedBackground)
        assertReadable(unselected.currentTextColor, unselectedBackground)
    }

    private fun assertSwitcherSelectionAndRecycling() {
        val dialog = switcher(3)
        val list = dialog.findViewById<RecyclerView>(R.id.scenario_list)!!
        @Suppress("UNCHECKED_CAST")
        val adapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        val holder = adapter.createViewHolder(list, 0)
        val card = holder.itemView as MaterialCardView
        val title = card.findViewById<TextView>(R.id.scenario_name)
        val details = card.findViewById<TextView>(R.id.scenario_details)
        val badge = card.findViewById<TextView>(R.id.current_badge)
        val icon = card.findViewById<ImageView>(R.id.scenario_icon)
        for (position in listOf(0, 1, 0)) {
            adapter.bindViewHolder(holder, position)
            val current = position == 0
            assertEquals(current, card.isSelected)
            assertEquals(current, title.typeface?.isBold == true || title.paint.isFakeBoldText)
            assertEquals(if (current) View.VISIBLE else View.GONE, badge.visibility)
            assertEquals(MaterialColors.getColor(card, if (current) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorSurface), card.cardBackgroundColor.defaultColor)
            assertReadable(title.currentTextColor, card.cardBackgroundColor.defaultColor)
            assertReadable(details.currentTextColor, card.cardBackgroundColor.defaultColor)
            assertReadable(icon.imageTintList!!.defaultColor, icon.backgroundTintList!!.defaultColor)
            if (current) {
                assertNotNull(badge.compoundDrawablesRelative[0])
                assertReadable(badge.currentTextColor, card.cardBackgroundColor.defaultColor)
                assertTrue(card.contentDescription.contains(context.getString(R.string.runtime_switcher_current)))
            } else {
                assertFalse(card.contentDescription.contains(context.getString(R.string.runtime_switcher_current)))
            }
        }
    }

    private fun assertReadable(foreground: Int, background: Int) {
        val contrast = ColorUtils.calculateContrast(foreground, background)
        assertTrue("Text/icon contrast must be at least 4.5:1, got $contrast", contrast >= 4.5)
    }

    private fun creator(scope: CoroutineScope, kind: RuntimeScenarioKind = RuntimeScenarioKind.DUMB,
                        create: suspend (String, RuntimeScenarioKind) -> Unit): AlertDialog =
        showRuntimeScenarioCreator(context, scope, kind, {}) { name, selectedKind, _ ->
            create(name, selectedKind)
        }.also { dialogs += it }

    private fun switcher(count: Int, selected: (RuntimeScenarioTarget) -> Unit = {}, onCreate: () -> Unit = {}): AlertDialog =
        showRuntimeScenarioSwitcher(context, (1..count).map { index ->
            RuntimeScenarioListItem(RuntimeScenarioTarget.Smart(Scenario(id = Identifier(databaseId = index.toLong()),
                name = "脚本 $index", detectionQuality = 800, randomize = false)), index == 1)
        }, selected, onCreate).also { dialogs += it }
}
