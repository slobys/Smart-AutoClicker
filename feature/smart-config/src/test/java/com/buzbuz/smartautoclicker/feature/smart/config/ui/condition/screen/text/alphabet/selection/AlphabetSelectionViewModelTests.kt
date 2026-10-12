/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.selection

import android.graphics.Rect
import androidx.lifecycle.ViewModelStore
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.OCRModelsRepository
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModel
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModelState
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.domain.model.EditedElementState
import com.buzbuz.smartautoclicker.feature.smart.config.domain.model.IEditionState
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.text.alphabet.AlphabetSelectionItem
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AlphabetSelectionViewModelTests {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val edited = MutableStateFlow(EditedElementState<ScreenCondition>(text(), false, true))
    private val models = MutableStateFlow(setOf(model(OCRAlphabet.LATIN), model(OCRAlphabet.CHINESE_SIMPLIFIED)))
    private val edition = mockk<EditionRepository>(relaxed = true)
    private val repository = mockk<OCRModelsRepository>()
    private lateinit var viewModel: AlphabetSelectionViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val state = mockk<IEditionState>()
        every { edition.editionState } returns state
        every { state.editedScreenConditionState } returns edited
        every { state.getEditedCondition<ScreenCondition>() } answers { edited.value.value }
        every { edition.isEditingCondition } returns MutableStateFlow(true)
        every { edition.updateEditedCondition(any()) } answers {
            edited.value = EditedElementState(firstArg(), true, true)
        }
        every { repository.recognitionModels } returns models
        coEvery { repository.getRecognitionModel(any()) } answers {
            val alphabet = firstArg<OCRAlphabet>()
            models.value.find { it.alphabet == alphabet }
        }
        coEvery { repository.downloadRecognitionModel(any()) } just Runs
        viewModel = newViewModel()
    }

    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun openingExistingLatinConditionDoesNotChangeItsLanguage() = runTest(dispatcher) {
        assertEquals(OCRAlphabet.LATIN, viewModel.confirmationState.first().alphabet)
        assertTrue(viewModel.confirmationState.first().canConfirm)
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
    }

    @Test fun selectingChineseIsOnlyDraftUntilConfirmed() = runTest(dispatcher) {
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        assertEquals(OCRAlphabet.CHINESE_SIMPLIFIED, viewModel.confirmationState.first().alphabet)
        assertEquals(OCRAlphabet.LATIN, (edited.value.value as ScreenCondition.Text).alphabet)
        assertTrue(viewModel.items.first().filterIsInstance<AlphabetSelectionItem.Alphabet>()
            .single { it.selected }.alphabet == OCRAlphabet.CHINESE_SIMPLIFIED)
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
    }

    @Test fun confirmationAppliesChoiceAndKeepsOtherConditionFields() = runTest(dispatcher) {
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        val latest = text().copy(name = "Updated", text = "师门", detectionArea = Rect(120, 130, 240, 260), threshold = 20)
        edited.value = EditedElementState(latest, true, true)
        assertTrue(viewModel.confirmSelection())
        assertEquals(latest.copy(alphabet = OCRAlphabet.CHINESE_SIMPLIFIED), edited.value.value)
        verify(exactly = 1) { edition.updateEditedCondition(any()) }
    }

    @Test fun closingWithoutConfirmationKeepsOriginalOnReopen() = runTest(dispatcher) {
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        store.clear()
        val reopened = newViewModel()
        assertEquals(OCRAlphabet.LATIN, reopened.confirmationState.first().alphabet)
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
    }

    @Test fun uninstalledChineseDefaultRequiresDownloadButDoesNotFallBackToLatin() = runTest(dispatcher) {
        edited.value = EditedElementState(text().copy(alphabet = OCRAlphabet.CHINESE_SIMPLIFIED), false, true)
        models.value = setOf(model(OCRAlphabet.LATIN), model(OCRAlphabet.CHINESE_SIMPLIFIED, OCRModelState.Downloadable))
        assertEquals(OCRAlphabet.CHINESE_SIMPLIFIED, viewModel.confirmationState.first().alphabet)
        assertFalse(viewModel.confirmationState.first().canConfirm)
        assertFalse(viewModel.confirmSelection())
        assertEquals(R.string.alphabet_selection_model_unavailable, viewModel.confirmationState.first().errorMessage)
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
    }

    @Test fun downloadCompletionEnablesConfirmationForSelectedChinese() = runTest(dispatcher) {
        edited.value = EditedElementState(text().copy(alphabet = OCRAlphabet.CHINESE_SIMPLIFIED), false, true)
        models.value = setOf(model(OCRAlphabet.CHINESE_SIMPLIFIED, OCRModelState.Downloading(50)))
        assertFalse(viewModel.confirmationState.first().canConfirm)
        models.value = setOf(model(OCRAlphabet.CHINESE_SIMPLIFIED))
        assertTrue(viewModel.confirmationState.first().canConfirm)
        assertTrue(viewModel.confirmSelection())
    }

    @Test fun clickingUnavailableModelCannotStageIt() = runTest(dispatcher) {
        models.value = setOf(model(OCRAlphabet.LATIN), model(OCRAlphabet.CHINESE_SIMPLIFIED, OCRModelState.Downloadable))
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        assertEquals(OCRAlphabet.LATIN, viewModel.confirmationState.first().alphabet)
        viewModel.downloadModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        coVerify(exactly = 1) { repository.downloadRecognitionModel(OCRAlphabet.CHINESE_SIMPLIFIED) }
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
    }

    @Test fun confirmationRechecksModelEvenIfUiStillShowsInstalled() = runTest(dispatcher) {
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        assertTrue(viewModel.confirmationState.first().canConfirm)
        coEvery { repository.getRecognitionModel(OCRAlphabet.CHINESE_SIMPLIFIED) } returns
            model(OCRAlphabet.CHINESE_SIMPLIFIED, OCRModelState.Downloadable)
        assertFalse(viewModel.confirmSelection())
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
    }

    @Test fun changedConditionCannotReceivePreviousDraftDuringConfirmation() = runTest(dispatcher) {
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        val ready = CompletableDeferred<OCRModel.Recognition>()
        coEvery { repository.getRecognitionModel(any()) } coAnswers { ready.await() }
        val result = async { viewModel.confirmSelection() }
        edited.value = EditedElementState(text().copy(id = Identifier(databaseId = 9)), false, true)
        ready.complete(model(OCRAlphabet.CHINESE_SIMPLIFIED))
        assertFalse(result.await())
        assertEquals(R.string.alphabet_selection_condition_changed, viewModel.confirmationState.first().errorMessage)
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
        assertEquals(OCRAlphabet.LATIN, viewModel.confirmationState.first().alphabet)
    }

    @Test fun repeatedConfirmationWhileCheckingDoesNotSaveTwice() = runTest(dispatcher) {
        val ready = CompletableDeferred<OCRModel.Recognition>()
        coEvery { repository.getRecognitionModel(any()) } coAnswers { ready.await() }
        val first = async { viewModel.confirmSelection() }
        assertFalse(viewModel.confirmationState.first().canConfirm)
        assertTrue(viewModel.confirmationState.first().isConfirming)
        assertFalse(viewModel.confirmSelection())
        ready.complete(model(OCRAlphabet.LATIN))
        assertTrue(first.await())
        assertFalse(viewModel.confirmationState.first().isConfirming)
        verify(exactly = 1) { edition.updateEditedCondition(any()) }
    }

    @Test fun absentOrNonTextConditionCannotBeConfirmed() = runTest(dispatcher) {
        edited.value = EditedElementState(null, false, false)
        assertFalse(viewModel.confirmationState.first().canConfirm)
        assertFalse(viewModel.confirmSelection())
        edited.value = EditedElementState(ScreenCondition.Color(id = Identifier(databaseId = 4), eventId = Identifier(databaseId = 2),
            name = "Color", threshold = 0, shouldBeDetected = true, priority = 0,
            detectionArea = Rect(0, 0, 1, 1), color = android.graphics.Color.RED), false, true)
        assertFalse(viewModel.confirmSelection())
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
    }

    @Test fun modelCheckFailureShowsErrorAndRetryCanSucceedWithoutChangingOriginal() = runTest(dispatcher) {
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        coEvery { repository.getRecognitionModel(any()) } throws IOException("Model read failed")
        assertFalse(viewModel.confirmSelection())
        assertEquals(R.string.alphabet_selection_apply_failed, viewModel.confirmationState.first().errorMessage)
        assertFalse(viewModel.confirmationState.first().isConfirming)
        assertTrue(viewModel.confirmationState.first().canConfirm)
        assertEquals(OCRAlphabet.LATIN, (edited.value.value as ScreenCondition.Text).alphabet)
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
        coEvery { repository.getRecognitionModel(any()) } returns model(OCRAlphabet.CHINESE_SIMPLIFIED)
        assertTrue(viewModel.confirmSelection())
        assertNull(viewModel.confirmationState.first().errorMessage)
        assertEquals(OCRAlphabet.CHINESE_SIMPLIFIED, (edited.value.value as ScreenCondition.Text).alphabet)
    }

    @Test fun selectingAnotherInstalledLanguageClearsPreviousError() = runTest(dispatcher) {
        coEvery { repository.getRecognitionModel(any()) } returns null
        assertFalse(viewModel.confirmSelection())
        assertNotNull(viewModel.confirmationState.first().errorMessage)
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        assertNull(viewModel.confirmationState.first().errorMessage)
    }

    @Test fun editorThatIgnoresUpdateCannotReportSuccess() = runTest(dispatcher) {
        viewModel.selectModel(OCRAlphabet.CHINESE_SIMPLIFIED)
        every { edition.updateEditedCondition(any()) } returns Unit
        assertFalse(viewModel.confirmSelection())
        assertEquals(R.string.alphabet_selection_apply_failed, viewModel.confirmationState.first().errorMessage)
        assertEquals(OCRAlphabet.LATIN, (edited.value.value as ScreenCondition.Text).alphabet)
    }

    @Test fun editorFailureShowsErrorAndRestoresControls() = runTest(dispatcher) {
        every { edition.updateEditedCondition(any()) } throws IllegalStateException("Editor closed")
        assertFalse(viewModel.confirmSelection())
        assertEquals(R.string.alphabet_selection_apply_failed, viewModel.confirmationState.first().errorMessage)
        assertFalse(viewModel.confirmationState.first().isConfirming)
        assertTrue(viewModel.confirmationState.first().canConfirm)
    }

    @Test fun coroutineCancellationIsNotConvertedToFailureOrSaved() = runTest(dispatcher) {
        val cancelled = CancellationException("Page closed")
        coEvery { repository.getRecognitionModel(any()) } throws cancelled
        try {
            viewModel.confirmSelection()
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertNull(viewModel.confirmationState.first().errorMessage)
        assertFalse(viewModel.confirmationState.first().isConfirming)
        verify(exactly = 0) { edition.updateEditedCondition(any()) }
    }

    private fun newViewModel() = AlphabetSelectionViewModel(edition, repository).also { store.put("picker", it) }

    private fun text() = ScreenCondition.Text(
        id = Identifier(databaseId = 1), eventId = Identifier(databaseId = 2), name = "Text", threshold = 20,
        priority = 0, shouldBeDetected = true, text = "target", detectionArea = Rect(100, 200, 300, 240),
        alphabet = OCRAlphabet.LATIN,
    )

    private fun model(alphabet: OCRAlphabet, state: OCRModelState = OCRModelState.Installed("model/$alphabet")) =
        OCRModel.Recognition(state, alphabet)
}
