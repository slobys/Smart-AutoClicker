package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** Deterministic changing frames, including disappearance, not repeated reads of one screenshot. */
@RunWith(AndroidJUnit4::class)
class DynamicBackgroundTests {
    private lateinit var detector: ImageDetector

    @Before fun setUp() {
        detector = NativeDetector.newInstance()!!
        detector.init()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (path, models) = context.extractTestOcrModels()
        assertTrue(detector.loadTextDetectionModels(path, models))
    }

    @After fun tearDown() = detector.close()

    @Test fun color_toleratesMovingMinorityInterference() {
        repeat(8) { frame ->
            withBitmap(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GREEN)
                val canvas = Canvas(this)
                canvas.drawRect(frame * 4f, 0f, frame * 4f + 6, 20f, Paint().apply {
                    color = if (frame % 2 == 0) Color.MAGENTA else Color.BLUE
                })
            }) {
                assertTrue("color missed on frame $frame", detector.detectColor(Color.GREEN, area(it), 4).isDetected)
            }
        }
    }

    @Test fun color_rejectsAverageOfUnrelatedColorsAndSparseTarget() {
        withBitmap(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            Canvas(this).drawRect(20f, 0f, 40f, 20f, Paint().apply { color = Color.BLUE })
        }) {
            assertFalse("red plus blue is not a purple target",
                detector.detectColor(Color.rgb(128, 0, 128), area(it), 4).isDetected)
        }
        withBitmap(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            Canvas(this).drawRect(0f, 0f, 4f, 20f, Paint().apply { color = Color.GREEN })
        }) { assertFalse(detector.detectColor(Color.GREEN, area(it), 4).isDetected) }
    }

    @Test fun color_preservesSinglePixelThresholdAndClearsPreviousHit() {
        withBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }) {
            assertTrue(detector.detectColor(Color.RED, area(it), 0).isDetected)
            assertFalse(detector.detectColor(Color.BLUE, area(it), 4).isDetected)
        }
    }

    @Test fun color_areaSearchTracksSmallMovingPatchesAndRejectsNoise() {
        repeat(8) { frame ->
            withBitmap(background(240, 120, frame).apply {
                Canvas(this).drawRect(25f + frame * 15, 40f, 36f + frame * 15, 52f,
                    Paint().apply { color = Color.GREEN })
            }) {
                assertFalse("legacy coverage must not silently become search",
                    detector.detectColor(Color.GREEN, area(it), 0).isDetected)
                val result = detector.detectColor(Color.GREEN, Rect(10, 10, 230, 110), 0, true)
                assertTrue(result.isDetected)
                assertEquals(Color.GREEN, it.getPixel(result.position.x, result.position.y))
            }
            withBitmap(background(240, 120, frame).apply {
                repeat(20) { index -> setPixel(5 + index * 10, 8 + index * 4, Color.GREEN) }
            }) { assertFalse("isolated particles or stale hit", detector.detectColor(Color.GREEN, area(it), 0, true).isDetected) }
        }
    }

    @Test fun color_areaSearchNeverClicksTheHoleInARing() {
        withBitmap(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            Canvas(this).drawCircle(50f, 50f, 30f, Paint().apply {
                color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f
            })
        }) {
            val result = detector.detectColor(Color.RED, area(it), 0, true)
            assertTrue(result.isDetected)
            assertEquals(Color.RED, it.getPixel(result.position.x, result.position.y))
        }
    }

    @Test fun text_tracksChangingBackgroundAndRejectsAbsentTarget() {
        repeat(8) { frame ->
            withBitmap(textFrame("QUEST", frame)) {
                assertTrue("text missed on frame $frame", detector.detectText("QUEST", "latin", area(it), 4).isDetected)
                assertFalse("wrong text accepted on frame $frame", detector.detectText("RESET", "latin", area(it), 4).isDetected)
            }
            withBitmap(textFrame(null, frame)) {
                assertFalse("stale text on frame $frame", detector.detectText("QUEST", "latin", area(it), 4).isDetected)
            }
        }
    }

    @Test fun number_tracksChangingValuesWithoutReusingPreviousFrame() {
        listOf("16", "18", "124", "42", "16.5", "23.5").forEachIndexed { frame, value ->
            withBitmap(textFrame(value, frame, width = 200)) {
                val result = detector.detectNumber(area(it), 10, NumberFormatType.DOT_DECIMAL)
                assertTrue("number missed: $value (got ${result.numberDetected}, ${result.confidenceRate})", result.isDetected)
                assertEquals(value.toDouble(), result.numberDetected!!, 0.0001)
            }
            withBitmap(textFrame(null, frame, width = 200)) {
                assertFalse("background became a number after $value", detector.detectNumber(area(it), 10).isDetected)
            }
        }
    }

    @Test fun text_colouredThinGlyphsOnChangingLuminance() {
        val failures = mutableListOf<String>()
        repeat(6) { frame ->
            withBitmap(chromaticText("QUEST", frame)) {
                val result = detector.detectText("QUEST", "latin", area(it), 4)
                if (!result.isDetected) failures += "frame $frame: ${result.confidenceRate}"
                assertFalse("absent text in chromatic crop", detector.detectText("RESET", "latin", area(it), 4).isDetected)
            }
        }
        assertTrue("thin coloured text: $failures", failures.isEmpty())
    }

    @Test fun number_colouredThinGlyphsOnChangingLuminance() {
        val failures = mutableListOf<String>()
        listOf("124", "16", "42", "23.5", "18", "12.5").forEachIndexed { frame, value ->
            withBitmap(chromaticText(value, frame)) {
                val result = detector.detectNumber(area(it), 10, NumberFormatType.DOT_DECIMAL)
                if (!result.isDetected || result.numberDetected != value.toDouble())
                    failures += "$value -> ${result.numberDetected} (${result.confidenceRate})"
            }
        }
        assertTrue("thin coloured number: $failures", failures.isEmpty())
    }

    @Test fun image_tracksMovingForegroundOnChangingBackground() {
        val reference = iconFrame(0, 0, 0, 80, 80, true)
        try {
            repeat(8) { frame ->
                val x = 15 + frame * 7
                val y = 8 + frame % 3 * 6
                withBitmap(iconFrame(frame + 1, x, y, 180, 120, true)) {
                    val result = detector.detectImage(reference, 80, 80, area(it), 8)
                    assertTrue("image missed frame $frame (${result.confidenceRate})", result.isDetected)
                    assertTrue("wrong image position ${result.position}",
                        kotlin.math.abs(result.position.x - (x + 40)) <= 2 &&
                            kotlin.math.abs(result.position.y - (y + 40)) <= 2)
                }
                withBitmap(iconFrame(frame + 1, x, y, 180, 120, false)) {
                    assertFalse("stale image on frame $frame", detector.detectImage(reference, 80, 80, area(it), 8).isDetected)
                }
            }
        } finally { reference.recycle() }
    }

    @Test fun image_rejectsChangedForegroundColorAndShape() {
        val reference = iconFrame(0, 0, 0, 80, 80, true)
        try {
            for (wrongShape in listOf(false, true)) {
                withBitmap(iconFrame(3, 30, 10, 180, 120, true,
                    foreground = if (wrongShape) Color.WHITE else Color.RED, wrongShape = wrongShape)) {
                    assertFalse("image decoy accepted", detector.detectImage(reference, 80, 80, area(it), 8).isDetected)
                }
            }
        } finally { reference.recycle() }
    }

    @Test fun image_nearbyScalesOnChangingBackgroundKeepPositionAndRejectDecoys() {
        val reference = iconFrame(0, 0, 0, 80, 80, true)
        try {
            listOf(0.8f, 0.9f, 1.1f, 1.2f).forEachIndexed { frame, scale ->
                for (decoy in listOf(false, true)) {
                    withBitmap(background(200, 140, frame + 1).apply {
                        val canvas = Canvas(this)
                        canvas.translate(30f, 20f)
                        canvas.scale(scale, scale)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = if (decoy) Color.RED else Color.WHITE
                            strokeWidth = 6f; style = Paint.Style.STROKE
                        }
                        canvas.drawCircle(40f, 40f, 23f, paint)
                        canvas.drawLine(40f, 24f, 40f, 52f, paint)
                        canvas.drawLine(40f, 52f, 54f, 46f, paint)
                    }) {
                        val result = detector.detectImage(reference, 80, 80, area(it), 8)
                        if (decoy) assertFalse("scaled wrong-colour icon at $scale", result.isDetected)
                        else {
                            assertTrue("scaled icon at $scale: $result", result.isDetected)
                            assertTrue("wrong scaled position: ${result.position}",
                                kotlin.math.abs(result.position.x - (30 + 40 * scale)) <= 3 &&
                                    kotlin.math.abs(result.position.y - (20 + 40 * scale)) <= 3)
                        }
                    }
                }
            }
        } finally { reference.recycle() }
    }

    private fun textFrame(text: String?, frame: Int, width: Int = 280): Bitmap =
        background(width, 72, frame).apply {
            val canvas = Canvas(this)
            if (text != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 38f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.rgb(30, 35, 30)
                }
                val x = width / 2f + frame % 3 - 1
                canvas.drawText(text, x, 50f, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (frame % 2 == 0) Color.WHITE else Color.rgb(235, 245, 60)
                canvas.drawText(text, x, 50f, paint)
            }
        }

    private fun chromaticText(text: String, frame: Int): Bitmap =
        Bitmap.createBitmap(240, 64, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { index ->
                val value = (110 + 45 * sin((index % width + frame * 25) / 48.0)).toInt()
                Color.rgb(value, value, value)
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
            Canvas(this).drawText(text, 120f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(64, 25, 195, 35)
                textSize = 30f
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
            })
        }

    private fun iconFrame(frame: Int, x: Int, y: Int, width: Int, height: Int, visible: Boolean,
                          foreground: Int = Color.WHITE, wrongShape: Boolean = false): Bitmap =
        background(width, height, frame).apply {
            if (visible) {
                val canvas = Canvas(this)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; strokeWidth = 6f; style = Paint.Style.STROKE }
                canvas.drawCircle(x + 40f, y + 40f, 23f, paint)
                if (wrongShape) canvas.drawLine(x + 24f, y + 40f, x + 56f, y + 40f, paint)
                else {
                    canvas.drawLine(x + 40f, y + 24f, x + 40f, y + 52f, paint)
                    canvas.drawLine(x + 40f, y + 52f, x + 54f, y + 46f, paint)
                }
            }
        }

    private fun background(width: Int, height: Int, frame: Int): Bitmap {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val wave = (sin((x + frame * 21) / 35.0) * 32 + sin((y - frame * 13) / 22.0) * 24).toInt()
            Color.rgb((80 + wave + frame * 11 % 70).coerceIn(0, 255),
                (115 - wave + frame * 7 % 60).coerceIn(0, 255), (145 + wave).coerceIn(0, 255))
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun area(bitmap: Bitmap) = Rect(0, 0, bitmap.width, bitmap.height)

    private inline fun withBitmap(bitmap: Bitmap, test: (Bitmap) -> Unit) {
        try {
            detector.setScreenBitmap(bitmap, "")
            val start = SystemClock.elapsedRealtime()
            test(bitmap)
            Log.i("DynamicBackgroundTests", "${bitmap.width}x${bitmap.height}: ${SystemClock.elapsedRealtime() - start} ms")
        } finally { bitmap.recycle() }
    }
}
