package com.buzbuz.smartautoclicker.localservice

import android.app.Application
import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "zh-rCN-w960dp-h600dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
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

    private fun creator(scope: CoroutineScope, create: suspend (String, RuntimeScenarioKind) -> Unit): AlertDialog =
        showRuntimeScenarioCreator(context, scope, RuntimeScenarioKind.DUMB, {}, create).also { dialogs += it }

    private fun switcher(count: Int, selected: (RuntimeScenarioTarget) -> Unit = {}, onCreate: () -> Unit = {}): AlertDialog =
        showRuntimeScenarioSwitcher(context, (1..count).map { index ->
            RuntimeScenarioListItem(RuntimeScenarioTarget.Smart(Scenario(id = Identifier(databaseId = index.toLong()),
                name = "脚本 $index", detectionQuality = 800, randomize = false)), index == 1)
        }, selected, onCreate).also { dialogs += it }
}
