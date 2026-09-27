/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.OCRModelsRepository
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRModelState
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.common.actions.AndroidGestureResult
import com.buzbuz.smartautoclicker.core.detection.NativeDetector
import com.buzbuz.smartautoclicker.core.detection.NumberFormatType
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.processing.data.DetectorEngine
import com.buzbuz.smartautoclicker.core.processing.data.DetectorState
import kotlinx.coroutines.Dispatchers
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
import kotlin.math.abs
import kotlin.math.round

enum class RouteOperation { PREVIEW, RECORD, REPLAY, CALIBRATE }
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

    private fun checkEnvironment(width: Int, height: Int) {
        if (engine.state.value != DetectorState.RECORDING) throw RouteFailure(RouteMessage.SERVICE_STOPPED)
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
    ): RouteRunResult {
        require(route.valid())
        check(busy.compareAndSet(false, true)) { "Route already running" }
        var acquired = false
        try {
            acquired = engine.acquireRouteSession()
            if (!acquired) throw RouteFailure(RouteMessage.SERVICE_STOPPED)
            // Native OCR has thread affinity. Own exactly one worker and release it on every exit path.
            Executors.newSingleThreadExecutor { task -> Thread(task, "route-recorder") }.asCoroutineDispatcher().use { worker ->
                return withContext(worker) {
                    checkEnvironment(route.screenWidth, route.screenHeight)
                    report(RouteProgress(RouteMessage.PREPARING))
                    val detector = NativeDetector.newInstance() ?: throw RouteFailure(RouteMessage.FAILED)
                    var template: Bitmap? = null
                    try {
                        detector.init()
                        val detection = (models.getDetectionModel()?.state as? OCRModelState.Installed)?.path
                        val recognition = models.getRecognitionModelPath(OCRAlphabet.LATIN)
                        if (detection == null || recognition == null ||
                            !detector.loadTextDetectionModels(detection, mapOf(OCRAlphabet.LATIN.name to recognition)))
                            throw RouteFailure(RouteMessage.MODELS_MISSING)
                        val bytes = Base64.decode(route.mapPng, Base64.NO_WRAP)
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        require(options.outWidth == route.mapArea.right - route.mapArea.left &&
                            options.outHeight == route.mapArea.bottom - route.mapArea.top)
                        val map = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw RouteFailure(RouteMessage.FAILED)
                        template = map
                        val filter = RouteCoordinateFilter()
                        val port = object : RoutePort {
                            override fun now() = SystemClock.elapsedRealtime()
                            override suspend fun read(): RoutePoint? {
                                checkEnvironment(route.screenWidth, route.screenHeight)
                                val frame = recorder.acquireLatestBitmap() ?: return null
                                if (frame.width != route.screenWidth || frame.height != route.screenHeight)
                                    throw RouteFailure(RouteMessage.RESOLUTION_CHANGED)
                                try {
                                    detector.setScreenBitmap(frame, "route:${route.id}")
                                    if (!detector.detectImage(map, map.width, map.height, route.mapArea.rect(), 15).isDetected)
                                        return filter.accept(null)
                                    val x = detector.detectNumber(route.xArea.rect(), 15, NumberFormatType.AUTO)
                                    val y = detector.detectNumber(route.yArea.rect(), 15, NumberFormatType.AUTO)
                                    val px = x.numberDetected; val py = y.numberDetected
                                    if (!x.isDetected || !y.isDetected || px == null || py == null ||
                                        abs(px - round(px)) > 0.01 || abs(py - round(py)) > 0.01) return filter.accept(null)
                                    return filter.accept(RoutePoint(px, py))
                                } finally { detector.releaseScreenBitmap(frame) }
                            }
                            override suspend fun move(offset: RoutePoint): Boolean = withContext(Dispatchers.Main.immediate) {
                                currentCoroutineContext().ensureActive()
                                checkEnvironment(route.screenWidth, route.screenHeight)
                                if (control.stopped || control.paused) return@withContext false
                                val target = route.anchor + offset
                                if (target.x !in 8.0..(route.screenWidth - 8.0) || target.y !in 8.0..(route.screenHeight - 8.0))
                                    return@withContext false
                                // Never tap our own controls, coordinate fields, or map label.
                                if (listOfNotNull(route.xArea, route.yArea, route.mapArea, control.blockedArea).any {
                                    it.contains(target) || (route.control == RouteControl.JOYSTICK && it.contains(route.anchor))
                                })
                                    return@withContext false
                                val path = Path().apply {
                                    if (route.control == RouteControl.JOYSTICK) {
                                        moveTo(route.anchor.x.toFloat(), route.anchor.y.toFloat())
                                        lineTo(target.x.toFloat(), target.y.toFloat())
                                    } else moveTo(target.x.toFloat(), target.y.toFloat())
                                }
                                val gesture = GestureDescription.Builder().addStroke(
                                    GestureDescription.StrokeDescription(path, 0, if (route.control == RouteControl.JOYSTICK) 500 else 70)
                                ).build()
                                actions.dispatchGesture(gesture) == AndroidGestureResult.COMPLETED
                            }
                        }
                        delay(700)
                        val pilot = RoutePilot(port, control, report)
                        when (operation) {
                            RouteOperation.RECORD -> RouteRunResult(route = pilot.record(route, store::save))
                            RouteOperation.CALIBRATE -> RouteRunResult(sample = pilot.calibrate(requireNotNull(calibrationOffset)))
                            RouteOperation.PREVIEW -> { pilot.preview(); RouteRunResult() }
                            RouteOperation.REPLAY -> { pilot.replay(route); RouteRunResult() }
                        }
                    } finally {
                        try { detector.close() } finally { template?.recycle() }
                    }
                }
            }
        } finally { if (acquired) engine.releaseRouteSession(); busy.set(false) }
    }
}

private fun RouteArea.rect() = Rect(left, top, right, bottom)
private fun RouteArea.overlaps(other: RouteArea) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
