/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.os.SystemClock
import android.util.Base64
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.OCRModelsRepository
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModelState
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.common.actions.AndroidGestureResult
import com.buzbuz.smartautoclicker.core.detection.NativeDetector
import com.buzbuz.smartautoclicker.core.detection.MinimapMatcher
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.processing.data.DetectorEngine
import com.buzbuz.smartautoclicker.core.processing.data.DetectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import com.buzbuz.smartautoclicker.core.domain.model.action.ExecuteRoute
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class RouteOperation { PREVIEW, RECORD, REPLAY, RETURN, CALIBRATE }
data class RouteRunResult(val route: RecordedRoute? = null, val sample: RouteCalibrationSample? = null)

@Singleton
class RouteRuntime @Inject internal constructor(
    private val recorder: DisplayRecorder,
    private val display: DisplayConfigManager,
    private val engine: DetectorEngine,
    private val models: OCRModelsRepository,
    private val actions: AndroidActionExecutor,
    private val store: RouteStore,
) {
    private val busy = AtomicBoolean(false)

    private fun checkEnvironment(width: Int, height: Int, inScenario: Boolean = false) {
        val expected = if (inScenario) DetectorState.DETECTING else DetectorState.RECORDING
        if (engine.state.value != expected) throw RouteFailure(RouteMessage.SERVICE_STOPPED)
        val size = display.displayConfig.sizePx
        if (size.x != width || size.y != height) throw RouteFailure(RouteMessage.RESOLUTION_CHANGED)
    }

    suspend fun captureMap(area: RouteArea, width: Int, height: Int, control: RouteRunControl): String = withContext(Dispatchers.IO) {
        require(area.valid(width, height))
        if (!engine.acquireRouteSession()) throw RouteFailure(RouteMessage.SERVICE_STOPPED)
        try {
            checkEnvironment(width, height)
            delay(700) // Allow the selector and configuration sheet to disappear.
            if (control.stopped) throw RouteFailure(RouteMessage.FAILED)
            if (control.blockedArea?.overlaps(area) == true) throw RouteFailure(RouteMessage.GESTURE_FAILED)
            val frame = withTimeoutOrNull(3_000) {
                var bitmap: Bitmap? = null
                while (bitmap == null) { bitmap = recorder.acquireLatestBitmap(); if (bitmap == null) delay(100) }
                bitmap
            } ?: throw RouteFailure(RouteMessage.SERVICE_STOPPED)
            checkEnvironment(width, height)
            if (frame.width != width || frame.height != height) throw RouteFailure(RouteMessage.RESOLUTION_CHANGED)
            val crop = Bitmap.createBitmap(frame, area.left, area.top, area.right - area.left, area.bottom - area.top)
            try {
                val bytes = ByteArrayOutputStream().also { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
                Base64.encodeToString(bytes, Base64.NO_WRAP).also { require(it.length <= 350_000) }
            } finally { if (crop !== frame) crop.recycle() }
        } finally { engine.releaseRouteSession() }
    }

    suspend fun run(
        route: RecordedRoute,
        operation: RouteOperation,
        control: RouteRunControl,
        calibrationOffset: RoutePoint? = null,
        report: (RouteProgress) -> Unit,
    ): RouteRunResult = runInternal(route, operation, control, calibrationOffset, false, {}, report)

    /** Called only by the scenario's sequential action loop, after releasing its screenshot.
     * A route failure stops the parent event, never silently advances to an attack/click.
     */
    internal suspend fun runAction(action: ExecuteRoute, beforeRead: suspend () -> Unit): ActionExecutionResult {
        if (!action.isComplete()) return ActionExecutionResult.Failed("Invalid route reference or timeout")
        return try {
            val route = store.load(action.routeId) ?: return ActionExecutionResult.Failed("Local route is missing: ${action.routeId}")
            if (!route.recordingComplete || route.calibration == null || route.points.size < 2)
                return ActionExecutionResult.Failed("Route must be recorded and calibrated first")
            withTimeoutOrNull(action.timeoutMs) {
                var completed = false
                runInternal(route, RouteOperation.REPLAY, RouteRunControl(), null, true, beforeRead) { progress ->
                    if (progress.message == RouteMessage.COMPLETE) completed = true
                    if (progress.message in setOf(RouteMessage.WRONG_START, RouteMessage.POSITION_JUMP, RouteMessage.STUCK, RouteMessage.TIMEOUT,
                            RouteMessage.LOST_POSITION, RouteMessage.GESTURE_FAILED)) throw RouteFailure(progress.message, progress.diagnostics)
                }
                if (completed) ActionExecutionResult.Success else ActionExecutionResult.Failed("Route interrupted")
            } ?: ActionExecutionResult.TimedOut(action.timeoutMs)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RouteFailure) { ActionExecutionResult.Failed("Route: ${failure.reason}" +
            (failure.diagnostics?.let { " (${it.issue})" } ?: "")) }
        catch (failure: Exception) {
            android.util.Log.w("RouteRuntime", "Route action failed", failure)
            ActionExecutionResult.Failed("Route data or runtime unavailable")
        }
    }

    private suspend fun runInternal(
        route: RecordedRoute, operation: RouteOperation, control: RouteRunControl,
        calibrationOffset: RoutePoint?, inScenario: Boolean, beforeRead: suspend () -> Unit,
        report: (RouteProgress) -> Unit,
    ): RouteRunResult {
        require(route.valid())
        check(busy.compareAndSet(false, true)) { "Route already running" }
        var acquired = false
        try {
            if (!inScenario) {
                acquired = engine.acquireRouteSession()
                if (!acquired) throw RouteFailure(RouteMessage.SERVICE_STOPPED)
            }
            // Native OCR has thread affinity. Own exactly one worker and release it on every exit path.
            Executors.newSingleThreadExecutor { task -> Thread(task, "route-recorder") }.asCoroutineDispatcher().use { worker ->
                return withContext(worker) {
                    checkEnvironment(route.screenWidth, route.screenHeight, inScenario)
                    report(RouteProgress(RouteMessage.PREPARING))
                    val detector = NativeDetector.newInstance() ?: throw RouteFailure(RouteMessage.FAILED)
                    var template: Bitmap? = null
                    try {
                        detector.init()
                        if (route.positionMode == RoutePositionMode.COORDINATES) {
                            val detection = (models.getDetectionModel()?.state as? OCRModelState.Installed)?.path
                            val recognition = models.getRecognitionModelPath(OCRAlphabet.LATIN)
                            if (detection == null || recognition == null ||
                                !detector.loadTextDetectionModels(detection, mapOf(OCRAlphabet.LATIN.name to recognition)))
                                throw RouteFailure(RouteMessage.MODELS_MISSING)
                        }
                        val minimap = route.minimap?.takeIf { route.positionMode == RoutePositionMode.MINIMAP }?.let {
                            if (operation != RouteOperation.PREVIEW && !it.tested) throw RouteFailure(RouteMessage.LOCALIZATION_WEAK)
                            MinimapLocalizer(it, operation == RouteOperation.RECORD || operation == RouteOperation.PREVIEW,
                                initialPosition = if (operation == RouteOperation.RETURN) route.points.lastOrNull() else route.points.firstOrNull(),
                                match = MinimapMatcher()::match)
                        }
                        val bytes = Base64.decode(route.mapPng, Base64.NO_WRAP)
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        require(options.outWidth == route.mapArea.right - route.mapArea.left &&
                            options.outHeight == route.mapArea.bottom - route.mapArea.top)
                        val map = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw RouteFailure(RouteMessage.FAILED)
                        template = map
                        val reader = RoutePositionReader(route, detector, map, operation == RouteOperation.PREVIEW, minimap)
                        val port = object : RoutePort {
                            override var epoch: Long = 0L
                                private set
                            override fun now() = SystemClock.elapsedRealtime()
                            override fun resetObservation() = reader.reset()
                            override suspend fun read(): RoutePoint? {
                                val beforeGate = now()
                                beforeRead()
                                if (now() - beforeGate > 200) epoch++ // Reconfirm after a debugger pause, never reuse an arrival hit.
                                checkEnvironment(route.screenWidth, route.screenHeight, inScenario)
                                val frame = recorder.acquireLatestBitmap()
                                if (frame != null && (frame.width != route.screenWidth || frame.height != route.screenHeight))
                                    throw RouteFailure(RouteMessage.RESOLUTION_CHANGED)
                                return reader.read(frame, control.blockedArea)
                            }
                            override suspend fun move(offset: RoutePoint): Boolean = move(RouteMotion(offset,
                                if (route.control == RouteControl.JOYSTICK) route.joystickDurationMs else 70))
                            override suspend fun move(motion: RouteMotion): Boolean = withContext(Dispatchers.Main.immediate) {
                                currentCoroutineContext().ensureActive()
                                checkEnvironment(route.screenWidth, route.screenHeight, inScenario)
                                if (control.stopped || control.paused) return@withContext false
                                val target = route.anchor + motion.offset
                                if (target.x !in 8.0..(route.screenWidth - 8.0) || target.y !in 8.0..(route.screenHeight - 8.0))
                                    return@withContext false
                                // Never tap our own controls, coordinate fields, or map label.
                                val protected = if (minimap == null) listOf(route.xArea, route.yArea) else listOf(requireNotNull(route.minimap).area)
                                if ((protected + listOfNotNull(route.mapArea, control.blockedArea)).any {
                                    it.contains(target) || (route.control == RouteControl.JOYSTICK && it.contains(route.anchor))
                                })
                                    return@withContext false
                                val path = Path().apply {
                                    // Fixed joystick: touch and hold at its effective deflection.
                                    // A slow drag from the centre would spend most time inside its deadzone.
                                    moveTo(target.x.toFloat(), target.y.toFloat())
                                }
                                val gesture = GestureDescription.Builder().addStroke(
                                    GestureDescription.StrokeDescription(path, 0, motion.durationMs)
                                ).build()
                                actions.dispatchGesture(gesture) == AndroidGestureResult.COMPLETED
                            }
                        }
                        delay(700)
                        val trace = ArrayDeque<RoutePoint>()
                        val progress: (RouteProgress) -> Unit = { value ->
                            value.position?.let { if (trace.lastOrNull()?.distance(it)?.let { d -> d >= 1 } != false) {
                                if (trace.size >= 120) trace.removeFirst()
                                trace.addLast(it)
                            } }
                            val directional = value.copy(returning = operation == RouteOperation.RETURN,
                                diagnostics = reader.diagnostics)
                            report(if (minimap == null) directional else directional.copy(confidence = minimap.quality, trace = trace.toList()))
                        }
                        val pilot = RoutePilot(port, control, progress)
                        fun withLandmarks(r: RecordedRoute) = if (minimap == null) r else r.copy(minimap = minimap.snapshot())
                        try { when (operation) {
                            RouteOperation.RECORD -> RouteRunResult(route = withLandmarks(pilot.record(route) { store.save(withLandmarks(it)) }))
                            RouteOperation.CALIBRATE -> RouteRunResult(sample = pilot.calibrate(requireNotNull(calibrationOffset)))
                            RouteOperation.PREVIEW -> if (minimap == null) { pilot.preview(); RouteRunResult() } else {
                                val result = testMinimap(port, control, progress)
                                // Test landmarks are not copied into the route: recording must start at its own origin.
                                RouteRunResult(route = route.copy(minimap = route.minimap?.copy(tested = result)))
                            }
                            RouteOperation.REPLAY, RouteOperation.RETURN -> {
                                pilot.replay(route, returning = operation == RouteOperation.RETURN); RouteRunResult()
                            }
                        } } catch (failure: RouteFailure) {
                            if (failure.reason == RouteMessage.LOST_POSITION)
                                throw RouteFailure(failure.reason, reader.diagnostics.positionFailure()).also { it.initCause(failure) }
                            throw failure
                        }
                    } finally {
                        try { detector.close() } finally { template?.recycle() }
                    }
                }
            }
        } finally { if (acquired) engine.releaseRouteSession(); busy.set(false) }
    }

    suspend fun captureMinimap(area: RouteArea, width: Int, height: Int, control: RouteRunControl): RouteMinimap {
        require(area.right - area.left in 96..512 && area.bottom - area.top in 96..512)
        val png = captureMap(area, width, height, control)
        return withContext(Dispatchers.Default) {
            val bytes = Base64.decode(png, Base64.NO_WRAP)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                val gray = MinimapFrames.read(bitmap, RouteArea(0, 0, bitmap.width, bitmap.height))
                // A constant or repeated texture cannot be used as a navigation reference.
                if (MinimapMatcher().match(gray, gray, 16) == null) throw RouteFailure(RouteMessage.LOCALIZATION_WEAK)
                RouteMinimap(area, keyframes = listOf(RouteKeyframe(RoutePoint(50_000.0, 50_000.0), MinimapFrames.encode(gray))))
            } finally { bitmap.recycle() }
        }
    }
}

private fun RouteArea.overlaps(other: RouteArea) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
