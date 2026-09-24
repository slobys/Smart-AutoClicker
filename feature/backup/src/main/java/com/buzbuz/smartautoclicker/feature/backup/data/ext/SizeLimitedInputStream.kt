/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.backup.data.ext

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Limits the uncompressed bytes consumed from one backup archive entry. */
internal class SizeLimitedInputStream(
    source: InputStream,
    private val maxBytes: Long,
    private val onBytesRead: (Long) -> Unit,
) : FilterInputStream(source) {

    private var bytesRead: Long = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) recordRead(1L)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) recordRead(count.toLong())
        return count
    }

    override fun close() = Unit // The owning ZipInputStream is closed by BackupEngine.

    private fun recordRead(count: Long) {
        bytesRead += count
        if (bytesRead > maxBytes) {
            throw BackupSizeLimitExceededException("Backup entry exceeds $maxBytes uncompressed bytes")
        }
        onBytesRead(count)
    }
}

internal class BackupSizeLimitExceededException(message: String) : IOException(message)
