/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.backup.data.ext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

class ZipStreamExtTests {

    @Test
    fun readAndCopyEntryFile_whenInputFails_deletesPartialOutput() {
        val output = File.createTempFile("backup-entry", ".tmp").apply { delete() }
        val failingInput = object : InputStream() {
            private var reads = 0

            override fun read(): Int = if (reads++ == 0) 1 else throw IOException("Broken archive")
        }

        try {
            assertThrows(IOException::class.java) {
                failingInput.readAndCopyEntryFile(output)
            }
            assertFalse(output.exists())
        } finally {
            output.delete()
        }
    }
}
