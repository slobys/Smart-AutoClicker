package com.buzbuz.smartautoclicker.feature.backup.data.ext

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class SizeLimitedInputStreamTests {

    @Test
    fun read_whenContentFitsLimit_returnsAllBytesAndUpdatesTotal() {
        val content = ByteArray(32) { it.toByte() }
        var totalRead = 0L
        val stream = SizeLimitedInputStream(ByteArrayInputStream(content), content.size.toLong()) {
            totalRead += it
        }

        assertArrayEquals(content, stream.readBytes())
        assertEquals(content.size.toLong(), totalRead)
    }

    @Test
    fun read_whenContentExceedsLimit_throws() {
        val stream = SizeLimitedInputStream(ByteArrayInputStream(ByteArray(33)), 32L) { }

        assertThrows(BackupSizeLimitExceededException::class.java) {
            stream.readBytes()
        }
    }

    @Test
    fun close_doesNotCloseOwningStream() {
        val source = TrackingInputStream(byteArrayOf(1, 2, 3))
        SizeLimitedInputStream(source, 3L) { }.close()

        assertEquals(false, source.closed)
    }
}

private class TrackingInputStream(content: ByteArray) : ByteArrayInputStream(content) {
    var closed: Boolean = false

    override fun close() {
        closed = true
        super.close()
    }
}
