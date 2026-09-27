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
import android.util.LruCache
import javax.inject.Inject


internal class BitmapLRUCache @Inject constructor() : LruCache<String, Bitmap>(
    cacheBudgetKb(Runtime.getRuntime().maxMemory())
) {

    override fun sizeOf(key: String, bitmap: Bitmap): Int {
        // The cache size will be measured in kilobytes rather than number of items.
        // Round up: thousands of tiny templates must not become zero-cost cache entries.
        return ((bitmap.allocationByteCount.toLong() + 1023) / 1024).coerceAtLeast(1).toInt()
    }

    fun putImageConditionBitmap(path: String, width: Int, height: Int, bitmap: Bitmap) {
        put(getImageConditionKey(path, width, height), bitmap)
    }

    fun getImageConditionBitmap(path: String, width: Int, height: Int): Bitmap? =
        get(getImageConditionKey(path, width, height))

    private fun getImageConditionKey(path: String, width: Int, height: Int): String =
        "key:IMAGE_CONDITION:$path:$width:$height"
}

/** Leave room for screenshots, OCR models and native matching buffers, even on large-heap devices. */
internal fun cacheBudgetKb(maxHeapBytes: Long): Int =
    (maxHeapBytes / 8 / 1024).coerceIn(1, 32 * 1024).toInt()
