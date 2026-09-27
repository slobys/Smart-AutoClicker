package com.buzbuz.smartautoclicker.core.bitmaps

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BitmapMemoryTests {
    @Test fun cacheBudgetReservesHeapForNativeDetectionAndHasAHardCap() {
        assertEquals(8 * 1024, cacheBudgetKb(64L * 1024 * 1024))
        assertEquals(32 * 1024, cacheBudgetKb(256L * 1024 * 1024))
        assertEquals(32 * 1024, cacheBudgetKb(2L * 1024 * 1024 * 1024))
        assertEquals(1, cacheBudgetKb(1))
    }

    @Test fun tinyTemplatesHaveNonzeroCostAndAreEvictedWithoutRecycling() {
        val cache = BitmapLRUCache().apply { resize(4) }
        val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        repeat(1000) { cache.putImageConditionBitmap("$it", 1, 1, image) }
        assertEquals(4, cache.size())
        assertEquals(4, cache.snapshot().size)
        assertNull(cache.getImageConditionBitmap("0", 1, 1))
        assertFalse(image.isRecycled)
    }

    @Test fun templateEvictionAndLowMemoryNeverEvictTheActiveCaptureBuffer() {
        val cache = BitmapLRUCache().apply { resize(4) }
        val repository = BitmapRepositoryImpl(cache, mock())
        val capture = repository.getDisplayRecorderBitmap(192, 108)
        repeat(1000) {
            cache.putImageConditionBitmap("$it", 1, 1, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            assertSame(capture, repository.getDisplayRecorderBitmap(192, 108))
        }
        repository.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(0L, repository.memoryUsage().templateBytes)
        assertSame(capture, repository.getDisplayRecorderBitmap(192, 108))
        assertFalse(capture.isRecycled)
    }

    @Test fun orientationAndCloseRetainOnlyOneCaptureReference() {
        val repository = BitmapRepositoryImpl(BitmapLRUCache(), mock())
        val portrait = repository.getDisplayRecorderBitmap(108, 192)
        val landscape = repository.getDisplayRecorderBitmap(192, 108)
        assertNotSame(portrait, landscape)
        assertEquals(landscape.allocationByteCount.toLong(), repository.memoryUsage().captureBytes)
        repository.releaseDisplayRecorderBitmap()
        assertEquals(0L, repository.memoryUsage().captureBytes)
        assertFalse(portrait.isRecycled)
        assertFalse(landscape.isRecycled)
        assertNotSame(landscape, repository.getDisplayRecorderBitmap(192, 108))
    }

    @Test fun hiddenUiTrimsTemplatesButDoesNotTreatItAsCriticalMemoryPressure() {
        val cache = BitmapLRUCache().apply { resize(8) }
        val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        repeat(8) { cache.putImageConditionBitmap("$it", 1, 1, image) }
        BitmapRepositoryImpl(cache, mock()).trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(4, cache.size())
    }
}
