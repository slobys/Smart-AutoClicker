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
package com.buzbuz.smartautoclicker.core.bitmaps

import android.graphics.Bitmap
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.core.graphics.createBitmap
import com.buzbuz.smartautoclicker.core.base.addDumpTabulationLvl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.PrintWriter
import javax.inject.Inject

internal class BitmapRepositoryImpl @Inject constructor(
    private val bitmapLRUCache: BitmapLRUCache,
    private val conditionBitmapsDataSource: ConditionBitmapsDataSource,
) : BitmapRepository {

    private val conditionBitmapLoadMutex = Mutex()
    // One reusable capture buffer, independent of template-cache eviction and screen orientation history.
    private var displayRecorderBitmap: Bitmap? = null

    override suspend fun saveImageConditionBitmap(bitmap: Bitmap, prefix: String): String {
        val path = conditionBitmapsDataSource.saveBitmap(bitmap, prefix)
        bitmapLRUCache.putImageConditionBitmap(path, bitmap.width, bitmap.height, bitmap)
        return path
    }

    override suspend fun getImageConditionBitmap(path: String, width: Int, height: Int): Bitmap? {
        bitmapLRUCache.getImageConditionBitmap(path, width, height)?.let { return it }

        return conditionBitmapLoadMutex.withLock {
            bitmapLRUCache.getImageConditionBitmap(path, width, height)
                ?: conditionBitmapsDataSource.loadBitmap(path, width, height)
                    ?.also { bitmap ->
                        bitmapLRUCache.putImageConditionBitmap(path, width, height, bitmap)
                    }
        }
    }

    @Synchronized override fun getDisplayRecorderBitmap(width: Int, height: Int): Bitmap {
        displayRecorderBitmap?.takeIf { !it.isRecycled && it.width == width && it.height == height }?.let { return it }
        displayRecorderBitmap = null
        return createBitmap(width, height).also { displayRecorderBitmap = it }
    }

    @Synchronized override fun releaseDisplayRecorderBitmap() {
        displayRecorderBitmap = null
    }

    @Suppress("DEPRECATION")
    override fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> bitmapLRUCache.evictAll()
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> bitmapLRUCache.trimToSize(bitmapLRUCache.maxSize() / 2)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> bitmapLRUCache.trimToSize(bitmapLRUCache.maxSize() / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> bitmapLRUCache.trimToSize(bitmapLRUCache.maxSize() / 2)
        }
    }

    @Synchronized override fun memoryUsage() = BitmapMemoryUsage(
        bitmapLRUCache.size().toLong() * 1024,
        bitmapLRUCache.maxSize().toLong() * 1024,
        displayRecorderBitmap?.allocationByteCount?.toLong() ?: 0,
    )

    override suspend fun deleteImageConditionBitmaps(paths: List<String>) {
        conditionBitmapsDataSource.deleteBitmaps(paths)
    }

    override suspend fun migrateImageConditionBitmap(path: String, width: Int, height: Int): String? {
        Log.d(TAG, "Migrating legacy bitmap $path")

        val legacyBitmap = conditionBitmapsDataSource.loadLegacyBitmap(path, width, height) ?: return null
        conditionBitmapsDataSource.deleteBitmaps(listOf(path))

        return conditionBitmapsDataSource.saveBitmap(legacyBitmap, CONDITION_FILE_PREFIX)
    }

    override fun clearCache() {
        bitmapLRUCache.evictAll()
    }

    override fun dump(writer: PrintWriter, prefix: CharSequence) {
        val contentPrefix = prefix.addDumpTabulationLvl()
        val memory = memoryUsage()

        writer.apply {
            append(prefix).println("* BitmapManager:")
            append(contentPrefix)
                .append("- cacheSize=[${bitmapLRUCache.size()}/${bitmapLRUCache.maxSize()}]; ")
                .append("hit/miss=[${bitmapLRUCache.hitCount()}/${bitmapLRUCache.missCount()}]; ")
                .append("captureBytes=${memory.captureBytes}; ")
                .println()
        }
    }
}

private const val TAG = "BitmapRepository"
