package com.buzbuz.smartautoclicker.core.processing.data

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapMemoryUsage
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RuntimeMemorySamplerTests {
    private val manager: ActivityManager = mock()
    private val context: Context = mock {
        on { getSystemService(Context.ACTIVITY_SERVICE) } doReturn manager
    }
    private val bitmaps: BitmapRepository = mock {
        on { memoryUsage() } doReturn BitmapMemoryUsage(4096, 8192, 8294400)
    }

    @Test fun lowSystemMemoryTrimsOnlyReloadableCacheAndRecordsEvidence() {
        doAnswer {
            it.getArgument<ActivityManager.MemoryInfo>(0).apply {
                availMem = 10; totalMem = 100; threshold = 15; lowMemory = true
            }
        }.whenever(manager).getMemoryInfo(any())
        val sample = RuntimeMemorySampler(context).sample("DETECTING", bitmaps)
        verify(bitmaps).trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        verify(bitmaps, never()).releaseDisplayRecorderBitmap()
        assertTrue(sample.getBoolean("systemLowMemory"))
        assertEquals(10L, sample.getLong("systemAvailableBytes"))
        assertEquals(8294400L, sample.getLong("captureBitmapBytes"))
        assertTrue(sample.has("pid"))
        assertTrue(sample.has("importance"))
        assertTrue(sample.has("nativeAllocatedBytes"))
    }

    @Test fun normalSamplingDoesNotClearTheCacheOrChangeRecognitionState() {
        val sample = RuntimeMemorySampler(context).sample("RECORDING", bitmaps)
        assertEquals("RECORDING", sample.getString("state"))
        verify(bitmaps, never()).trimMemory(any())
        verify(bitmaps, never()).clearCache()
        assertFalse(sample.has("templateCacheTrimmed"))
    }
}
