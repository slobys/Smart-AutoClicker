package com.buzbuz.smartautoclicker.core.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.detection.utils.extractTestOcrModels
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/** Optional on-device fixtures: do not publish user screenshots or bundle another runtime model. */
@RunWith(AndroidJUnit4::class)
class ChineseTextMatcherTests {
    private var detector: ImageDetector? = null
    private lateinit var fixtures: File

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fixtures = File(context.filesDir, "ocr-fixtures")
        val model = File(fixtures, "chinese_simplified")
        val available = listOf("rec.ncnn.param", "rec.ncnn.bin", "dict.txt").all { File(model, it).isFile }
        if (InstrumentationRegistry.getArguments().getString("requireChineseModel") == "true")
            assertTrue("Chinese model fixtures missing in $model", available)
        assumeTrue("Install optional Chinese model fixtures", available)
        val (path, models) = context.extractTestOcrModels()
        detector = NativeDetector.newInstance()!!.also {
            it.init()
            assertTrue(it.loadTextDetectionModels(path, models + (MODEL to model.absolutePath)))
        }
    }

    @After fun tearDown() { detector?.close() }

    @Test fun colouredChinese_tracksChangingBackgroundAndDisappearance() {
        repeat(8) { frame ->
            withFrame(chineseFrame("师门", frame)) { bitmap ->
                val result = detector!!.detectText("师门", MODEL, area(bitmap), 20)
                assertTrue("Chinese missed frame $frame: $result", result.isDetected)
                assertTrue("Click not on the title: ${result.position}", result.position.y in 18..60)
            }
            withFrame(chineseFrame(null, frame)) { bitmap ->
                assertFalse("Stale or invented Chinese on frame $frame",
                    detector!!.detectText("师门", MODEL, area(bitmap), 20).isDetected)
            }
        }
    }

    @Test fun chinese_doesNotTurnSimilarOrPartialWordsIntoTarget() {
        listOf("帅门", "师内", "师", "门", "任务", "师兄").forEachIndexed { frame, text ->
            withFrame(chineseFrame(text, frame)) { bitmap ->
                assertFalse("Confusable '$text' was accepted as 师门 at 80%",
                    detector!!.detectText("师门", MODEL, area(bitmap), 20).isDetected)
            }
        }
    }

    @Test fun chinese_recognizesOtherTargetsWithoutGameSpecificSubstitution() {
        listOf("任务", "采集", "背包", "师门").forEachIndexed { frame, text ->
            withFrame(chineseFrame(text, frame)) { bitmap ->
                assertTrue("Missing Chinese target $text",
                    detector!!.detectText(text, MODEL, area(bitmap), 0).isDetected)
            }
        }
    }

    @Test fun colouredChinese_overlappingDimLetteringDoesNotHideBrightTitle() {
        repeat(8) { frame ->
            withFrame(chineseFrame("师门", frame, ghost = true)) { bitmap ->
                assertTrue("Overlapping lettering hid target at frame $frame",
                    detector!!.detectText("师门", MODEL, area(bitmap), 20).isDetected)
            }
            withFrame(chineseFrame("帅门", frame, ghost = true)) { bitmap ->
                assertFalse("Overlapping lettering invented 师门 at frame $frame",
                    detector!!.detectText("师门", MODEL, area(bitmap), 20).isDetected)
            }
        }
    }

    @Test fun dimChineseOtherTargetsOnChangingBackground() {
        listOf("任务", "背包", "采集").forEach { word ->
            repeat(6) { frame ->
                withFrame(chineseFrame(word, frame, dim = true)) { bitmap ->
                    assertTrue("dim $word frame $frame",
                        detector!!.detectText(word, MODEL, area(bitmap), 0).isDetected)
                    assertFalse("invented word on dim frame",
                        detector!!.detectText("购买", MODEL, area(bitmap), 0).isDetected)
                }
            }
        }
    }

    @Test fun mixedChineseAndNumbersUseSameDigitModelAsNumberOnly() {
        val (path, models) = InstrumentationRegistry.getInstrumentation().targetContext.extractTestOcrModels()
        val latinPath = models.values.first()
        val single = NativeDetector.newInstance()!!
        single.init()
        try {
            assertTrue(single.loadTextDetectionModels(path, mapOf("LATIN" to latinPath)))
            assertTrue(detector!!.loadTextDetectionModels(path,
                mapOf("LATIN" to latinPath, MODEL to File(fixtures, "chinese_simplified").absolutePath)))
            listOf("124", "16.5", "-23.5", "18", "0.25").forEach { value ->
                withFrame(Bitmap.createBitmap(220, 64, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(30, 45, 55))
                    Canvas(this).drawText(value, 8f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 40f; color = Color.YELLOW; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                }) { bitmap ->
                    single.setScreenBitmap(bitmap, "")
                    val expected = single.detectNumber(area(bitmap), 20, NumberFormatType.DOT_DECIMAL)
                    val actual = detector!!.detectNumber(area(bitmap), 20, NumberFormatType.DOT_DECIMAL)
                    assertTrue("single Latin missing $value: $expected", expected.isDetected)
                    assertEquals(value.toDouble(), actual.numberDetected!!, 0.00001)
                    assertEquals(expected.confidenceRate, actual.confidenceRate, 0.000001)
                }
            }
            // Reloading without Latin must not retain the old model id or choose Chinese silently.
            assertTrue(detector!!.loadTextDetectionModels(path, mapOf(MODEL to File(fixtures, "chinese_simplified").absolutePath)))
            withFrame(chineseFrame("师门", 0)) {
                assertFalse(detector!!.detectNumber(area(it), 100).isDetected)
                assertTrue(detector!!.detectText("师门", MODEL, area(it), 20).isDetected)
            }
        } finally { single.close() }
    }

    private fun chineseFrame(text: String?, frame: Int, ghost: Boolean = false, dim: Boolean = false): Bitmap =
        Bitmap.createBitmap(110, 260, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { index ->
                val wave = (42 * sin((index % width + frame * 17) / 19.0) +
                    27 * sin((index / width - frame * 13) / 23.0)).toInt()
                Color.rgb(90 + wave, 116 + wave, 82 + wave)
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f }
            if (text != null) {
                if (ghost) {
                    paint.color = Color.rgb(112, 109, 8)
                    canvas.drawText("采集", 9f + frame % 4, 37f, paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = Color.rgb(42, 48, 28)
                canvas.drawText(text, 7f + frame % 3, 48f, paint)
                paint.style = Paint.Style.FILL
                paint.color = listOf(Color.YELLOW, Color.rgb(100, 255, 150), Color.CYAN,
                    Color.MAGENTA, Color.rgb(255, 80, 80), Color.rgb(60, 100, 255))[frame % 6]
                if (dim) paint.color = Color.rgb(Color.red(paint.color) * 2 / 3,
                    Color.green(paint.color) * 2 / 3, Color.blue(paint.color) * 2 / 3)
                canvas.drawText(text, 7f + frame % 3, 48f, paint)
            }
            paint.color = Color.WHITE
            canvas.drawText("前往", 7f, 104f, paint)
            paint.color = Color.GREEN
            canvas.drawText("长寿村", 7f, 165f, paint)
            paint.color = Color.YELLOW
            canvas.drawText("掌门", 7f, 232f, paint)
        }

    private fun area(bitmap: Bitmap) = Rect(0, 0, bitmap.width, bitmap.height)

    private inline fun withFrame(bitmap: Bitmap, block: (Bitmap) -> Unit) {
        try {
            detector!!.setScreenBitmap(bitmap, "")
            block(bitmap)
        } finally { bitmap.recycle() }
    }

    @Test fun privateScreenshot_exactSelectionAndTitleAtDifferentScales() {
        val file = File(fixtures, "screen.png")
        assumeTrue("Private screenshot not provided", file.isFile)
        val original = BitmapFactory.decodeFile(file.absolutePath)!!
        val misses = mutableListOf<String>()
        try {
            for (scale in listOf(1f, 0.675f, 0.5f)) {
                val bitmap = Bitmap.createScaledBitmap(original,
                    (original.width * scale).toInt(), (original.height * scale).toInt(), true)
                try {
                    detector!!.setScreenBitmap(bitmap, "")
                    for (roi in listOf(Rect(1568, 259, 1663, 749), Rect(1568, 264, 1653, 310),
                        Rect(1558, 252, 1838, 319))) {
                        val area = Rect((roi.left * scale).toInt(), (roi.top * scale).toInt(),
                            (roi.right * scale).toInt(), (roi.bottom * scale).toInt())
                        val start = SystemClock.elapsedRealtime()
                        val result = detector!!.detectText("师门", MODEL, area, 20)
                        val summary = "$scale $area detected=${result.isDetected} score=${result.confidenceRate} " +
                            "position=${result.position} elapsed=${SystemClock.elapsedRealtime() - start}ms"
                        Log.i(TAG, summary)
                        if (!result.isDetected) misses += summary
                        else assertTrue("Target coordinate outside title: $summary",
                            result.position.x in (1568 * scale).toInt()..(1653 * scale).toInt() &&
                                result.position.y in (264 * scale).toInt()..(310 * scale).toInt())
                    }
                } finally { if (bitmap !== original) bitmap.recycle() }
            }
        } finally { original.recycle() }
        assertTrue("Chinese screenshot misses: $misses", misses.isEmpty())
    }

    private companion object {
        const val MODEL = "CHINESE_SIMPLIFIED"
        const val TAG = "ChineseOcrTest"
    }
}
