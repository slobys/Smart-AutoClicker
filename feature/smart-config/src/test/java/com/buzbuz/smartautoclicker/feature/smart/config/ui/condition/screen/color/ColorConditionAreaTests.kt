/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.color

import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import androidx.lifecycle.ViewModelStore
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.MonitoredViewsManager
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.domain.model.IEditionState
import com.buzbuz.smartautoclicker.feature.smart.config.domain.model.EditedElementState
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ColorConditionAreaTests {
    private val condition = ScreenCondition.Color(
        id = Identifier(databaseId = 1L), eventId = Identifier(databaseId = 2L),
        name = "Color", threshold = 5, priority = 0, shouldBeDetected = true,
        color = Color.RED, detectionArea = Rect(100, 100, 101, 101),
    )
    private val state = mockk<IEditionState>()
    private val repository = mockk<EditionRepository>(relaxed = true)
    private val store = ViewModelStore()
    private lateinit var viewModel: ColorConditionViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.editionState } returns state
        every { repository.isEditingCondition } returns flowOf(true)
        every { state.editedScreenConditionState } returns MutableStateFlow(EditedElementState(condition, false, true))
        every { state.getEditedCondition<ScreenCondition.Color>() } returns condition
        viewModel = ColorConditionViewModel(repository, mockk<MonitoredViewsManager>(relaxed = true))
        store.put("color", viewModel)
    }

    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun selectingRegionCopiesBoundsAndPreservesChosenColor() {
        val area = Rect(200, 300, 350, 400)
        viewModel.setDetectionArea(area)
        val saved = slot<ScreenCondition.Color>()
        verify { repository.updateEditedCondition(capture(saved)) }
        assertEquals(condition.copy(detectionArea = area), saved.captured)
        area.setEmpty()
        assertEquals(Rect(200, 300, 350, 400), saved.captured.detectionArea)
    }

    @Test fun emptySelectionCannotOverwriteExistingCondition() {
        viewModel.setDetectionArea(Rect())
        verify(exactly = 0) { repository.updateEditedCondition(any()) }
    }

    @Test fun precisePixelPickerStillRestoresSinglePixelArea() {
        viewModel.setPosition(PointF(123f, 456f))
        verify { repository.updateEditedCondition(condition.copy(detectionArea = Rect(123, 456, 124, 457))) }
    }
}
