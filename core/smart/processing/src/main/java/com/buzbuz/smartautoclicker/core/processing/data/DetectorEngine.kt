/*
 * Copyright (C) 2024 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.processing.data

import android.content.Context
import android.content.Intent
import android.media.Image
import android.media.projection.MediaProjectionManager
import android.util.Log

import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.OCRModelsRepository
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModel
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModelState
import com.buzbuz.smartautoclicker.core.base.data.AppComponentsProvider
import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers.IO
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.detection.NativeDetector
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.domain.ext.getAllOCRAlphabets
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.action.ExecuteRoute
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.domain.model.event.TriggerEvent
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.data.processor.ScenarioProcessor
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.settings.domain.SettingsRepository
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingListener
import com.buzbuz.smartautoclicker.core.processing.domain.model.DebugExecutionState
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeFailure
import com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeStopReason

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * Detects [ScreenEvent] conditions on a display and execute its actions.
 *
 * In order to detect, you must start recording the screen to get images to detect on, this can be done by calling
 * [startScreenRecord]. Or, you can start the detection of a list of [ScreenEvent] by using [startDetection].
 * The states of the recording and the detection are available in [state].
 * Once you no longer needs to capture or detect, call [stopDetection] or [stopScreenRecord] to release all processing resources.
 */
@Singleton
class DetectorEngine @Inject constructor(
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
    private val displayConfigManager: DisplayConfigManager,
    private val bitmapRepository: BitmapRepository,
    private val scalingManager: ScalingManager,
    private val displayRecorder: DisplayRecorder,
    private val actionFailureRecorder: ActionFailureRecorder,
    private val actionExecutor: AndroidActionExecutor,
    private val settingsRepository: SettingsRepository,
    private val appComponentsProvider: AppComponentsProvider,
    private val debuggingListener: SmartProcessingListener,
    private val ocrModelsRepository: OCRModelsRepository,
    private val routeRuntime: javax.inject.Provider<com.buzbuz.smartautoclicker.core.processing.routes.RouteRuntime>? = null,
) {

    /** Process the events conditions to detect them on the screen. */
    private var scenarioProcessor: ScenarioProcessor? = null
    /** Detect the condition images on the screen image. */
    private var imageDetector: ImageDetector? = null

    /** Event-level debugger. It suspends the processing coroutine without destroying runtime state. */
    private val runtimeDebugger = RuntimeDebugger()
    internal val debugExecutionState: StateFlow<DebugExecutionState> = runtimeDebugger.state
    internal val lastActionFailure = actionFailureRecorder.lastFailure
    private val _runtimeFailure = MutableStateFlow<RuntimeFailure?>(null)
    internal val runtimeFailure: StateFlow<RuntimeFailure?> = _runtimeFailure

    /** Coroutine scope for the image processing. */
    private var processingScope: CoroutineScope? = null
    private var recordingStartupJob: Job? = null
    private var recordingGeneration = 0L
    private var pendingStop: StopRequest? = null

    private data class StopRequest(
        val releaseProjection: Boolean,
        val reason: RuntimeStopReason,
        val failure: RuntimeFailure? = null,
        val finalState: DetectorState = DetectorState.CREATED,
    )
    /** Coroutine job for the image currently processed. */
    private var processingJob: Job? = null
    /** Coroutine job for the cleaning of the detection once stopped. */
    private var processingShutdownJob: Job? = null
    /** Coroutine job for the debounced orientation change handler. */
    private var orientationChangeJob: Job? = null
    private var memoryMonitorJob: Job? = null

    /**
     * When true, [processScreenImages] will exit its loop after the current frame finishes rather
     * than being canceled mid-execution. Used by orientation-change handling so in-progress action
     * sequences are not interrupted.
     */
    @Volatile private var orientationChangeRequested: Boolean = false

    private val screenOrientationListener: (Context) -> Unit = { onScreenOrientationChanged() }

    /** Backing property for [state].*/
    private val _state = MutableStateFlow(DetectorState.CREATED)
    /** Current state of the detector. */
    internal val state: StateFlow<DetectorState> = _state

    /** Scenario currently processed. Null if not detecting. */
    private var minProcessingDurationNs: Long = DEFAULT_MIN_PROCESSING_DURATION_NS

    /**
     * Start the screen detection.
     *
     * This requires the media projection permission code and its data intent, they both can be retrieved using the
     * results of the activity intent provided by [MediaProjectionManager.createScreenCaptureIntent] (this Intent shows
     * the dialog warning about screen recording privacy). Any attempt to call this method without the correct screen
     * capture intent result will fail safely and report a runtime error.
     *
     * Once started, you can use [startDetection]. Once you are done, call [stopScreenRecord].
     *
     * @param resultCode the result code provided by the screen capture intent activity result callback
     * [android.app.Activity.onActivityResult]
     * @param data the data intent provided by the screen capture intent activity result callback
     * [android.app.Activity.onActivityResult]
     * @param onRecordingStopped called when the screen recording is no longer running and a new request for media
     * projection should be done.
     */
    @Synchronized internal fun startScreenRecord(
        resultCode: Int,
        data: Intent,
        onRecordingStopped: (() -> Unit)?,
    ) {
        if (processingScope != null) {
            Log.w(TAG, "startScreenRecord: Screen record is already started")
            return
        }

        val displaySize = displayConfigManager.displayConfig.sizePx
        if (displaySize.x <= 0 || displaySize.y <= 0) {
            Log.w(TAG, "startScreenRecord: Invalid display size $displaySize")
            return
        }

        _state.value = DetectorState.TRANSITIONING

        Log.i(TAG, "startScreenRecord")

        _runtimeFailure.value = null
        val generation = ++recordingGeneration
        processingScope = CoroutineScope(SupervisorJob() + ioDispatcher.limitedParallelism(1))
        recordingStartupJob = processingScope?.launch(start = CoroutineStart.LAZY) {
          guardExecution("screen_capture_start", RuntimeStopReason.STARTUP_ERROR) {
            displayConfigManager.addOrientationListener(screenOrientationListener)
            displayRecorder.apply {
                startProjection(resultCode, data) {
                    synchronized(this@DetectorEngine) {
                        if (generation != recordingGeneration || processingScope == null) return@startProjection
                        requestStop(StopRequest(true, RuntimeStopReason.PROJECTION_LOST,
                            RuntimeFailure.from("projection_lost", IllegalStateException("Screen capture permission ended"))))
                    }
                    onRecordingStopped?.invoke()
                }
                coroutineContext.ensureActive()
                startScreenRecord(displaySize)
            }
            if (!displayRecorder.validateScreenCapture()) {
                requestStop(StopRequest(true, RuntimeStopReason.STARTUP_ERROR,
                    RuntimeFailure.from("screen_capture_validation", IllegalStateException("No readable screen frame")),
                    DetectorState.ERROR_SCREEN_IMAGE_CAPTURE_FAILED))
            } else synchronized(this@DetectorEngine) {
                if (pendingStop == null) _state.value = DetectorState.RECORDING
            }
          }
        }
        recordingStartupJob?.start()
    }

    /** Route OCR must not compete with normal detection for the reusable screen frame. */
    private var routeSessionActive = false

    @Synchronized internal fun acquireRouteSession(): Boolean {
        if (routeSessionActive || _state.value != DetectorState.RECORDING) return false
        routeSessionActive = true
        return true
    }

    @Synchronized internal fun releaseRouteSession() { routeSessionActive = false }

    /**
     * Start the screen detection.
     *
     * After calling this method, all [Image] displayed on the screen will be checked for the provided clicks conditions
     * fulfillment. For each image, the first event in the list that is detected will be notified through the provided
     * callback.
     * [state] should be RECORDING to capture. Detection can be stopped with [stopDetection] or [stopScreenRecord].
     */
    @Synchronized internal fun startDetection(
        context: Context,
        scenario: Scenario,
        screenEvents: List<ScreenEvent>,
        triggerEvents: List<TriggerEvent>,
        counters: List<Counter>,
        liveDebugging: Boolean,
        generateReport: Boolean,
        imageDetectorFactory: () -> ImageDetector? = NativeDetector::newInstance,
    ) {
        if (routeSessionActive || _state.value != DetectorState.RECORDING) {
            Log.w(TAG, "startDetection: Screen record is not started.")
            return
        }

        _state.value = DetectorState.TRANSITIONING
        _runtimeFailure.value = null

        Log.i(TAG, "startDetection")

        processingScope?.launchProcessingJob {
            actionFailureRecorder.beginSession(scenario.name)
            startMemoryMonitor()
            val detector = imageDetectorFactory()
            if (detector == null) {
                requestStop(StopRequest(true, RuntimeStopReason.STARTUP_ERROR,
                    RuntimeFailure.from("native_detector_init", IllegalStateException("Native detector unavailable")),
                    DetectorState.ERROR_NATIVE_DETECTOR_LIB_NOT_FOUND))
                return@launchProcessingJob
            }
            // Setup native detector
            imageDetector = detector
            detector.init()

            // Setup text detection models if needed
            val requiredAlphabets = screenEvents.getAllOCRAlphabets()
            if (requiredAlphabets.isNotEmpty()) {
                if (!detector.loadOcrModels(requiredAlphabets)) {
                    requestStop(StopRequest(true, RuntimeStopReason.STARTUP_ERROR,
                        RuntimeFailure.from("ocr_model_init", IllegalStateException("OCR model unavailable")),
                        DetectorState.ERROR_OCR_MODEL_NOT_FOUND))
                    return@launchProcessingJob
                }
            }

            // Clear image cache and compute scaling info for detection
            bitmapRepository.clearCache()

            // Set the display projection to the scaled size
            displayRecorder.resizeDisplay(
                displaySize = scalingManager.startScaling(
                    quality = scenario.detectionQuality.toDouble(),
                    screenEvents = screenEvents,
                )
            )

            // Compute minimal processing duration
            val frameLimit = scenario.computeRate
            minProcessingDurationNs =
                if (frameLimit <= 0.0) DEFAULT_MIN_PROCESSING_DURATION_NS
                else (ONE_SECOND_IN_NANO / frameLimit).toLong()

            Log.i(TAG, "Process scenario at ${if (frameLimit == 0.0) "unlimited" else frameLimit} FPS " +
                    "(${minProcessingDurationNs}ns per loop)")

            // Setup listeners if needed
            if (liveDebugging || generateReport) {
                debuggingListener.onSessionStarted(
                    scenario = scenario,
                    counters = counters,
                    generateLiveEvents = liveDebugging,
                    generateReport = generateReport,
                )
            }

            // Instantiate the processor and initialize its detection state.
            scenarioProcessor = ScenarioProcessor(
                processingTag = appComponentsProvider.originalAppId,
                imageDetector = detector,
                scalingManager = scalingManager,
                randomize = scenario.randomize,
                screenEvents = screenEvents,
                triggerEvents = triggerEvents,
                counters = counters,
                bitmapSupplier = bitmapRepository::getImageConditionBitmap,
                screenFrameSupplier = displayRecorder::acquireLatestBitmap,
                androidExecutor = actionExecutor,
                unblockWorkaroundEnabled = settingsRepository.isInputBlockWorkaroundEnabled(),
                onStopRequested = { stopDetection(RuntimeStopReason.SCENARIO_REQUEST) },
                progressListener  = if (liveDebugging || generateReport) debuggingListener else null,
                screenEventConfirmationHits = SCREEN_EVENT_CONFIRMATION_HITS,
                screenEventConfirmationWindow = SCREEN_EVENT_CONFIRMATION_WINDOW,
                singleFrameConfidenceMargin = SINGLE_FRAME_CONFIDENCE_MARGIN,
                beforeEventActions = runtimeDebugger::awaitBeforeActions,
                beforeRouteRead = runtimeDebugger::awaitWithinAction,
                onActionResult = actionFailureRecorder::onActionResult,
                onActionCompleted = actionFailureRecorder::onActionCompleted,
                routeExecutor = ::executeRouteAction,
            )
            scenarioProcessor?.onScenarioStart(context)

            processScreenImages()
        }
    }

    /** The sequential processor releases its borrowed frame before entering this method.
     * Route areas are saved in physical pixels, unlike downscaled scenario detection areas.
     */
    internal suspend fun executeRouteAction(action: ExecuteRoute, beforeRead: suspend () -> Unit): ActionExecutionResult {
        val runtime = routeRuntime?.get() ?: return ActionExecutionResult.Failed("Route runtime unavailable")
        try {
            displayRecorder.resizeDisplay(displayConfigManager.displayConfig.sizePx)
            return runtime.runAction(action, beforeRead)
        } finally {
            // Cancellation/projection loss is restored or released by cleanUpRuntime after joining us.
            if (kotlinx.coroutines.currentCoroutineContext().isActive && _state.value == DetectorState.DETECTING && pendingStop == null)
                displayRecorder.resizeDisplay(scalingManager.refreshScaling())
        }
    }

    /**
     * Called when the orientation of the screen changes.
     * As we now have different screen metrics, we need to stop and start the virtual display with the correct one.
     */
    @Synchronized private fun onScreenOrientationChanged() {
        if (_state.value != DetectorState.DETECTING && _state.value != DetectorState.RECORDING) return

        Log.d(TAG, "onOrientationChanged")

        orientationChangeJob?.cancel()
        orientationChangeJob = processingScope?.launch {
          guardExecution("orientation_change") {
            delay(ORIENTATION_CHANGE_DEBOUNCE_MS.milliseconds)

            if (_state.value == DetectorState.DETECTING) {
                // Signal the loop to exit after the current frame so in-progress actions finish cleanly.
                runtimeDebugger.reset()
                orientationChangeRequested = true
                processingJob?.join()
                orientationChangeRequested = false
                debuggingListener.onEventsProcessingCancelled()
            }

            displayRecorder.resizeDisplay(
                displaySize = scalingManager.refreshScaling(),
            )

            if (_state.value == DetectorState.DETECTING) {
                processingScope?.launchProcessingJob {
                    processScreenImages()
                }
            }
          }
        }
    }

    /**
     * Stop the screen detection started with [startDetection].
     *
     * After a call to this method, the events provided in the start method will no longer be checked on the current
     * image. Note that this will not stop the screen recording, you should still call [stopScreenRecord] to completely
     * release the [DetectorEngine] resources.
     */
    @Synchronized internal fun stopDetection(reason: RuntimeStopReason = RuntimeStopReason.USER_PAUSE) {
        if (processingJob == null && pendingStop == null) return
        requestStop(StopRequest(false, reason))
    }

    internal fun requestDebugPauseAtNextEvent() = runtimeDebugger.requestPauseAtNextEvent()

    internal fun cancelDebugPauseRequest() = runtimeDebugger.cancelPauseRequest()

    internal fun resumeDebugExecution() = runtimeDebugger.resume()

    internal fun stepDebugExecution() = runtimeDebugger.step()

    /**
     * Stop the screen recording and the detection, if any.
     *
     * First, calls [stopDetection] if the detection was active. Then, stop the screen recording and release any related
     * resources.
     */
    internal fun stopScreenRecord(reason: RuntimeStopReason = RuntimeStopReason.SESSION_CLOSED) {
        requestStop(StopRequest(true, reason))
    }

    internal suspend fun awaitStopped() {
        synchronized(this) { processingShutdownJob }?.join()
    }

    /** Stops are accepted even during startup/cleanup. Full release and the first failure take precedence. */
    @Synchronized private fun requestStop(request: StopRequest) {
        val scope = processingScope ?: return
        val previous = pendingStop
        pendingStop = when {
            previous == null -> request
            previous.failure != null -> previous.copy(releaseProjection = previous.releaseProjection || request.releaseProjection)
            request.failure != null -> request.copy(releaseProjection = previous.releaseProjection || request.releaseProjection)
            previous.releaseProjection -> previous
            else -> request
        }
        _state.value = DetectorState.TRANSITIONING
        runtimeDebugger.reset()
        // Cancellation is immediate; cleanup runs in a separate sibling job and joins before freeing native data.
        orientationChangeJob?.cancel()
        recordingStartupJob?.cancel()
        processingJob?.cancel()
        memoryMonitorJob?.cancel()
        if (processingShutdownJob != null) return
        processingShutdownJob = scope.launch(start = CoroutineStart.LAZY) { cleanUpRuntime(scope) }
        processingShutdownJob?.start()
    }

    private suspend fun cleanUpRuntime(scope: CoroutineScope) {
        var cleanupCompleted = true
        suspend fun clean(stage: String, block: suspend () -> Unit) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                cleanupCompleted = false
                Log.e(TAG, "Cleanup failed: $stage", error)
                requestStop(StopRequest(true, RuntimeStopReason.CLEANUP_ERROR, RuntimeFailure.from(stage, error)))
            }
        }
        clean("join_orientation") { orientationChangeJob?.cancelAndJoin() }
        clean("join_startup") { recordingStartupJob?.cancelAndJoin() }
        clean("join_processing") { processingJob?.cancelAndJoin() }
        clean("join_memory_monitor") { memoryMonitorJob?.cancelAndJoin() }
        memoryMonitorJob = null
        orientationChangeJob = null
        recordingStartupJob = null
        processingJob = null
        orientationChangeRequested = false
        val processor = scenarioProcessor.also { scenarioProcessor = null }
        val detector = imageDetector.also { imageDetector = null }
        clean("scenario_end") { processor?.onScenarioEnd() }
        clean("detector_close") { detector?.close() }
        clean("template_cache_clear") { bitmapRepository.clearCache() }
        clean("debug_session_end") { debuggingListener.onSessionEnded() }
        clean("scaling_stop") { scalingManager.stopScaling() }
        minProcessingDurationNs = DEFAULT_MIN_PROCESSING_DURATION_NS

        if (synchronized(this) { pendingStop?.releaseProjection == false }) {
            clean("restore_display_size") { displayRecorder.resizeDisplay(displayConfigManager.displayConfig.sizePx) }
        }
        var projectionReleased = false
        var historyEnded = false
        while (true) {
            val request = synchronized(this) { checkNotNull(pendingStop) }
            if (request.releaseProjection && !projectionReleased) {
                // Never resize a display we are about to destroy (particularly after projection loss).
                clean("remove_orientation_listener") { displayConfigManager.removeOrientationListener(screenOrientationListener) }
                clean("projection_stop") { displayRecorder.stopProjection() }
                projectionReleased = true
            }
            clean("history_end") {
                actionFailureRecorder.endSession(request.reason, request.failure, cleanupCompleted, historyEnded)
            }
            historyEnded = true
            val finished = synchronized(this) {
                if (pendingStop != request) false
                else {
                    pendingStop = null
                    processingShutdownJob = null
                    if (request.releaseProjection) {
                        processingScope = null
                        recordingGeneration++
                    }
                    _runtimeFailure.value = request.failure
                    _state.value = if (request.releaseProjection) request.finalState else DetectorState.RECORDING
                    true
                }
            }
            if (finished) {
                if (request.releaseProjection) scope.cancel()
                return
            }
        }
    }

    private suspend fun guardExecution(
        stage: String,
        reason: RuntimeStopReason = RuntimeStopReason.EXECUTION_ERROR,
        block: suspend () -> Unit,
    ) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e(TAG, "Runtime failed: $stage", error)
            requestStop(StopRequest(true, reason, RuntimeFailure.from(stage, error)))
        }
    }

    @Synchronized private fun startMemoryMonitor() {
        if (pendingStop != null) return
        memoryMonitorJob?.cancel()
        // One sampler per run, including long waits and debugger pauses. Never enqueue per-frame jobs.
        memoryMonitorJob = processingScope?.launch {
            while (isActive) {
                actionFailureRecorder.recordMemorySample(_state.value.name, bitmapRepository)
                delay(30_000)
            }
        }
    }

    /** Process the latest images provided by the [DisplayRecorder]. */
    private suspend fun processScreenImages() {
        synchronized(this) {
            if (pendingStop != null) return
            _state.value = DetectorState.DETECTING
        }

        var processingDurationNs: Long
        while (processingJob?.isActive == true && !orientationChangeRequested) {
            displayRecorder.acquireLatestBitmap()?.let { screenFrame ->
                processingDurationNs = measureNanoTime {
                    scenarioProcessor?.process(screenFrame)
                }

                // Avoid looping infinitely to quickly for nothing.
                if (processingDurationNs < minProcessingDurationNs) {
                    delay(duration = max(
                        a = 1,
                        b = (minProcessingDurationNs - processingDurationNs) / ONE_MILLISECOND_IN_NANO,
                    ).milliseconds)
                }

            } ?: delay(NO_IMAGE_DELAY_MS.milliseconds)
        }
    }

    /**
     * Creates a new job executing the provided job automatically once the job is effectively created.
     * This allows to check the job state correctly within the [block], even quickly after its start, as the [launch]
     * method with the [CoroutineStart.DEFAULT] starts the coroutine execution before returning the resulting [Job].
     *
     * The job will be affected to the [processingJob] variable.
     *
     * @param block the coroutine code which will be invoked in the context of the provided scope.
     */
    @Synchronized private fun CoroutineScope.launchProcessingJob(block: suspend CoroutineScope.() -> Unit) {
        if (pendingStop != null) return
        processingJob = launch(
            start = CoroutineStart.LAZY,
        ) { guardExecution("detection") { block() } }
        processingJob?.start()
    }

    private suspend fun ImageDetector.loadOcrModels(required: Set<OCRAlphabet>): Boolean {
        val ocrDetectModelPath = ocrModelsRepository.getDetectionModel()?.getOCRModelPath()
        val ocrRecoModels = ocrModelsRepository.getTextConditionsRecognitionModels(required)
        if (ocrDetectModelPath.isNullOrEmpty() || ocrRecoModels.size != required.size) {
            Log.e(TAG, "Can't start detection, OCR models config is invalid. " +
                    "Detection:$ocrDetectModelPath; Recognition:$ocrRecoModels")
            return false
        }

        return loadTextDetectionModels(ocrDetectModelPath, ocrRecoModels)
    }
}

private fun OCRModel.getOCRModelPath(): String? =
    (state as? OCRModelState.Installed)?.path

private suspend fun OCRModelsRepository.getTextConditionsRecognitionModels(required: Set<OCRAlphabet>): Map<String, String> =
    buildMap {
        required.forEach { alphabet ->
            val modelPath = getRecognitionModelPath(alphabet) ?: return@forEach
            put(alphabet.name, modelPath)
        }
    }


/** The different states of the [DetectorEngine]. */
internal enum class DetectorState {
    /** The engine is created and ready to be used. */
    CREATED,
    /**
     * The engine is transitioning between two states.
     * New starts are ignored, but stop requests still cancel startup or upgrade an ongoing cleanup.
     */
    TRANSITIONING,
    /** The screen is being recorded. */
    RECORDING,
    /** The screen is being recorded and the detection is running. */
    DETECTING,
    /** The native lib can't be loaded and the detection can't be used. */
    ERROR_NATIVE_DETECTOR_LIB_NOT_FOUND,
    /** The text detection models required for this scenario are not found. */
    ERROR_OCR_MODEL_NOT_FOUND,
    /** The device's GPU driver can't expose screen capture buffers for CPU access. */
    ERROR_SCREEN_IMAGE_CAPTURE_FAILED,
}

/**
 * Waiting delay after getting a null image.
 * This is to avoid spamming when there is no image.
 */
private const val NO_IMAGE_DELAY_MS = 20L
/** Debounce delay for orientation changes, to avoid restarting detection on every intermediate rotation event. */
private const val ORIENTATION_CHANGE_DEBOUNCE_MS = 100L

/** The value of 1 second in nanoseconds. */
private const val ONE_SECOND_IN_NANO = 1000000000L
/** The value of 1 milliseconds  in nanoseconds.*/
private const val ONE_MILLISECOND_IN_NANO = 1000000L
/** The default minimal processing duration in nanoseconds. */
private const val DEFAULT_MIN_PROCESSING_DURATION_NS = ONE_MILLISECOND_IN_NANO

/** Require two positive detections among the last three processed frames before executing actions. */
private const val SCREEN_EVENT_CONFIRMATION_HITS = 2
private const val SCREEN_EVENT_CONFIRMATION_WINDOW = 3
/**
 * Normalized confidence margin (0.5 percentage point) required above each image condition's own
 * acceptance threshold before the match can bypass the multi-frame stability filter.
 */
private const val SINGLE_FRAME_CONFIDENCE_MARGIN = 0.005

/** Tag for logs. */
private const val TAG = "DetectorEngine"
