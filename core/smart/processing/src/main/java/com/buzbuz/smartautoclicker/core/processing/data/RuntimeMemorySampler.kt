package com.buzbuz.smartautoclicker.core.processing.data

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import org.json.JSONObject

/** Sample only our process and aggregate system RAM. No screenshots or other apps' process lists. */
internal class RuntimeMemorySampler(private val context: Context) {
    @Suppress("DEPRECATION")
    fun sample(state: String, bitmaps: BitmapRepository): JSONObject {
        val runtime = Runtime.getRuntime()
        val row = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("pid", Process.myPid()).put("state", state)
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaMaxBytes", runtime.maxMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        val processMemory = Debug.MemoryInfo()
        Debug.getMemoryInfo(processMemory)
        row.put("pssKb", processMemory.totalPss)
        val processState = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processState)
        row.put("importance", processState.importance)
            .put("lastTrimLevel", processState.lastTrimLevel)

        val cacheBefore = bitmaps.memoryUsage()
        row.put("templateCacheBytes", cacheBefore.templateBytes)
            .put("templateCacheLimitBytes", cacheBefore.templateLimitBytes)
            .put("captureBitmapBytes", cacheBefore.captureBytes)
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.let { manager ->
            val systemMemory = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(systemMemory)
            row.put("systemAvailableBytes", systemMemory.availMem)
                .put("systemTotalBytes", systemMemory.totalMem)
                .put("systemLowMemoryThresholdBytes", systemMemory.threshold)
                .put("systemLowMemory", systemMemory.lowMemory)
            if (systemMemory.lowMemory) {
                // Not all Android releases deliver running-low trim callbacks. This is a backup,
                // not a claim that we can prevent the system from killing a process.
                bitmaps.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                row.put("templateCacheTrimmed", true)
                    .put("templateCacheBytesAfterTrim", bitmaps.memoryUsage().templateBytes)
            }
        }
        return row
    }
}
