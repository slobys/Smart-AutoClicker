package com.buzbuz.smartautoclicker.core.processing.tests

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.OCRModelsRepository
import com.buzbuz.smartautoclicker.core.base.data.AppComponentsProvider
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.display.recorder.ScreenCaptureException
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.domain.model.OR
import com.buzbuz.smartautoclicker.core.domain.model.action.ChangeCounter
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.ActionFailureRecorder
import com.buzbuz.smartautoclicker.core.processing.data.DetectorEngine
import com.buzbuz.smartautoclicker.core.processing.data.DetectorState
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.data.processor.ActionExecutor
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingListener
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import com.buzbuz.smartautoclicker.core.settings.domain.SettingsRepository
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeStopReason
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Regression coverage for runtime cleanup; hardware is deliberately fault-injected. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class DetectorEngineRecoveryTests {
    private val config = mockk<DisplayConfigManager>(relaxed = true)
    private val bitmaps = mockk<BitmapRepository>(relaxed = true)
    private val scaling = mockk<ScalingManager>(relaxed = true)
    private val recorder = mockk<DisplayRecorder>(relaxed = true)
    private val failures = mockk<ActionFailureRecorder>(relaxed = true)
    private val actions = mockk<AndroidActionExecutor>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val components = mockk<AppComponentsProvider>(relaxed = true)
    private val listener = mockk<SmartProcessingListener>(relaxed = true)
    private val models = mockk<OCRModelsRepository>(relaxed = true)
    private val detector = mockk<ImageDetector>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val intent = mockk<Intent>(relaxed = true)
    private lateinit var projectionStopped: () -> Unit
    private lateinit var engine: DetectorEngine

    @Before
    fun setup() {
        val size = Point(1080, 1920)
        every { config.displayConfig } returns DisplayConfig(
            sizePx = size,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            safeInsetTopPx = 0,
            roundedCorners = emptyMap(),
        )
        every { scaling.startScaling(any(), any()) } returns size
        every { components.originalAppId } returns "exit.audit"
        every { settings.isInputBlockWorkaroundEnabled() } returns false
        coEvery { recorder.startProjection(any(), any(), any()) } coAnswers {
            projectionStopped = thirdArg()
        }
        coEvery { recorder.validateScreenCapture() } returns true
        coEvery { recorder.acquireLatestBitmap() } returns null
    }

    @After
    fun teardown() = clearAllMocks()

    @Test
    fun runtimeCaptureFailureStopsSafelyAndRecordsCause() = runTest {
        startDetecting()
        coEvery { recorder.acquireLatestBitmap() } throws ScreenCaptureException(IllegalStateException("injected buffer failure"))
        advanceTimeBy(25)
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        assertNotNull(engine.runtimeFailure.value)
        verify(exactly = 1) { detector.close() }
        coVerify(exactly = 1) { recorder.stopProjection() }
        coVerify { failures.endSession(RuntimeStopReason.EXECUTION_ERROR, any(), true) }
    }

    @Test
    fun memoryMonitorSamplesWhileWaitingAndStopsWithTheSession() = runTest {
        startDetecting()
        coVerify(atLeast = 1) { failures.recordMemorySample(any(), bitmaps) }
        advanceTimeBy(30_001)
        runCurrent()
        coVerify(exactly = 2) { failures.recordMemorySample(any(), bitmaps) }
        engine.stopScreenRecord()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 2) { failures.recordMemorySample(any(), bitmaps) }
        verify(atLeast = 1) { bitmaps.clearCache() }
    }

    @Test
    fun fullStopNeverResizesAnInvalidDisplay() = runTest {
        startDetecting()
        coEvery { recorder.resizeDisplay(any()) } throws IllegalStateException("invalid virtual display")
        engine.stopScreenRecord()
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        assertNull(engine.runtimeFailure.value)
        // Only the resize when starting detection, never one during full shutdown.
        coVerify(exactly = 1) { recorder.resizeDisplay(any()) }
        coVerify(exactly = 1) { recorder.stopProjection() }
    }

    @Test
    fun pauseResizeFailureEscalatesToFullCleanup() = runTest {
        startDetecting()
        coEvery { recorder.resizeDisplay(any()) } throws IllegalStateException("invalid display")
        engine.stopDetection()
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        coVerify { failures.endSession(RuntimeStopReason.CLEANUP_ERROR, any(), false) }
        coVerify(exactly = 1) { recorder.stopProjection() }
    }

    @Test
    fun detectorCloseFailureDoesNotSkipProjectionOrHistory() = runTest {
        startDetecting()
        every { detector.close() } throws IllegalStateException("native close")
        engine.stopScreenRecord()
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        coVerify { recorder.stopProjection() }
        coVerify { failures.endSession(RuntimeStopReason.CLEANUP_ERROR, any(), false) }
    }

    @Test
    fun projectionReleaseFailureStillCompletesAndRecordsIncompleteCleanup() = runTest {
        startDetecting()
        coEvery { recorder.stopProjection() } throws IllegalStateException("dead binder")
        engine.stopScreenRecord()
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        coVerify { failures.endSession(RuntimeStopReason.CLEANUP_ERROR, any(), false, true) }
    }

    @Test
    fun projectionLossWhileWritingHistoryUpdatesTheSameCleanupOutcome() = runTest {
        startDetecting()
        coEvery { failures.endSession(RuntimeStopReason.USER_PAUSE, null, true, false) } coAnswers {
            projectionStopped()
        }
        engine.stopDetection()
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        coVerify(exactly = 1) { recorder.stopProjection() }
        coVerify { failures.endSession(RuntimeStopReason.PROJECTION_LOST, any(), true, true) }
    }

    @Test
    fun projectionLossDuringPauseUpgradesToFullStop() = runTest {
        startDetecting()
        engine.stopDetection()
        assertEquals(DetectorState.TRANSITIONING, engine.state.value)
        projectionStopped()
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        coVerify(exactly = 1) { recorder.stopProjection() }
        coVerify { failures.endSession(RuntimeStopReason.PROJECTION_LOST, any(), true) }
    }

    @Test
    fun projectionLossDuringStartupCannotReviveRecording() = runTest {
        val validation = CompletableDeferred<Boolean>()
        coEvery { recorder.validateScreenCapture() } coAnswers { validation.await() }
        createEngine()
        engine.startScreenRecord(0, intent, null)
        runCurrent()
        projectionStopped()
        validation.complete(true)
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        coVerify(exactly = 1) { recorder.stopProjection() }
    }

    @Test
    fun stopDuringDetectionInitializationReleasesDetector() = runTest {
        val initialized = CompletableDeferred<Unit>()
        coEvery { failures.beginSession(any()) } coAnswers { initialized.await() }
        createEngine()
        engine.startScreenRecord(0, intent, null)
        runCurrent()
        beginDetection()
        runCurrent()
        engine.stopScreenRecord()
        initialized.complete(Unit)
        runCurrent()
        assertEquals(DetectorState.CREATED, engine.state.value)
        verify(exactly = 0) { detector.init() }
        coVerify(exactly = 1) { recorder.stopProjection() }
    }

    @Test
    fun repeatedStopRequestsReleaseResourcesOnlyOnce() = runTest {
        startDetecting()
        repeat(10) { engine.stopScreenRecord() }
        runCurrent()
        engine.stopScreenRecord()
        runCurrent()
        verify(exactly = 1) { detector.close() }
        coVerify(exactly = 1) { recorder.stopProjection() }
    }

    @Test
    fun staleProjectionCallbackCannotStopNewSession() = runTest {
        createEngine()
        engine.startScreenRecord(0, intent, null)
        runCurrent()
        val staleCallback = projectionStopped
        engine.stopScreenRecord()
        runCurrent()
        engine.startScreenRecord(0, intent, null)
        runCurrent()
        staleCallback()
        runCurrent()
        assertEquals(DetectorState.RECORDING, engine.state.value)
        assertNull(engine.runtimeFailure.value)
        engine.stopScreenRecord()
        runCurrent()
    }

    @Test
    fun normalPauseKeepsProjectionAndCanRestart() = runTest {
        startDetecting()
        repeat(25) {
            engine.stopDetection()
            runCurrent()
            assertEquals(DetectorState.RECORDING, engine.state.value)
            assertNull(engine.runtimeFailure.value)
            beginDetection()
            runCurrent()
            assertEquals(DetectorState.DETECTING, engine.state.value)
        }
        coVerify(exactly = 0) { recorder.stopProjection() }
        engine.stopScreenRecord()
        runCurrent()
    }

    @Test
    fun oneThousandCounterActionsCompleteWithoutRequestingStop() = runTest {
        val scenarioId = Identifier(databaseId = 10)
        val eventId = Identifier(databaseId = 20)
        val increment = ChangeCounter(
            id = Identifier(databaseId = 30), eventId = eventId,
            name = "Increment", priority = 0, counterName = "loops",
            operation = ChangeCounter.OperationType.ADD,
            operationValue = CounterOperationValue.Number(1.0),
        )
        val event = ScreenEvent(
            eventId, scenarioId, "Loop", OR, listOf(increment), emptyList(),
            true, 0, cooldownMs = 0, keepDetecting = false,
        )
        val state = ProcessingState(
            screenEvents = listOf(event), triggerEvents = emptyList(),
            counters = listOf(Counter("loops", 0.0, scenarioId)), progressListener = null,
        )
        var stopRequests = 0
        var completedActions = 0
        val executor = ActionExecutor(
            androidExecutor = actions, processingState = state, randomize = false,
            onStopRequested = { stopRequests++ },
            onActionCompleted = { _, _, _, _ -> completedActions++ },
        )
        repeat(1_000) {
            assertEquals(ActionExecutionResult.Success, executor.executeActions(event))
        }
        assertEquals(1_000.0, state.getCounterValue("loops")!!, 0.0)
        assertEquals(1_000, completedActions)
        assertEquals(0, stopRequests)
    }

    private fun TestScope.createEngine() {
        engine = DetectorEngine(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            displayConfigManager = config,
            bitmapRepository = bitmaps,
            scalingManager = scaling,
            displayRecorder = recorder,
            actionFailureRecorder = failures,
            actionExecutor = actions,
            settingsRepository = settings,
            appComponentsProvider = components,
            debuggingListener = listener,
            ocrModelsRepository = models,
        )
    }

    @Test
    fun routeSessionExcludesDetectionUntilReleased() = runTest {
        createEngine()
        assertEquals(false, engine.acquireRouteSession())
        engine.startScreenRecord(0, intent, null)
        runCurrent()
        assertEquals(true, engine.acquireRouteSession())
        assertEquals(false, engine.acquireRouteSession())
        beginDetection()
        runCurrent()
        assertEquals(DetectorState.RECORDING, engine.state.value)
        verify(exactly = 0) { detector.init() }
        engine.releaseRouteSession()
        beginDetection()
        runCurrent()
        assertEquals(DetectorState.DETECTING, engine.state.value)
        assertEquals(false, engine.acquireRouteSession())
        engine.stopScreenRecord()
        runCurrent()
    }

    private fun TestScope.startDetecting() {
        createEngine()
        engine.startScreenRecord(0, intent, null)
        runCurrent()
        assertEquals(DetectorState.RECORDING, engine.state.value)
        beginDetection()
        runCurrent()
        assertEquals(DetectorState.DETECTING, engine.state.value)
    }

    private fun beginDetection() {
        engine.startDetection(
            context = context,
            scenario = Scenario(id = Identifier(databaseId = 1L), name = "Exit audit", detectionQuality = 600),
            screenEvents = emptyList(), triggerEvents = emptyList(), counters = emptyList(),
            liveDebugging = false, generateReport = false, imageDetectorFactory = { detector },
        )
    }
}
