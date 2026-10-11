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
package com.buzbuz.smartautoclicker.core.display.recorder

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.view.Surface

import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.bitmaps.BitmapRepository
import com.buzbuz.smartautoclicker.core.display.shadows.ShadowImageReader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as mockWhen
import org.mockito.MockitoAnnotations

import org.robolectric.annotation.Config

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Test the [ImageReaderProxy] class. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q], shadows = [ShadowImageReader::class])
class ImageReaderProxyTests {

    private companion object {
        private const val WIDTH = 1080
        private const val HEIGHT = 1920
        private val SIZE = Point(WIDTH, HEIGHT)

        private const val WIDTH_2 = 720
        private const val HEIGHT_2 = 1280
        private val SIZE_2 = Point(WIDTH_2, HEIGHT_2)
    }

    @Mock private lateinit var mockBitmapRepository: BitmapRepository
    @Mock private lateinit var mockImageReader: ImageReader
    @Mock private lateinit var mockSurface: Surface
    @Mock private lateinit var mockImage: Image
    @Mock private lateinit var mockBitmap: Bitmap

    private lateinit var imageReaderProxy: ImageReaderProxy

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        ShadowImageReader.setMockInstance(mockImageReader)
        mockWhen(mockImageReader.surface).thenReturn(mockSurface)

        imageReaderProxy = ImageReaderProxy(mockBitmapRepository)
    }

    @After
    fun tearDown() {
        ShadowImageReader.reset()
    }

    // ---- resize ----

    @Test
    fun resize_createsNewImageReader() {
        imageReaderProxy.resize(SIZE)

        assertEquals(1, ShadowImageReader.getInstanceCreationCount())
    }

    @Test
    fun resize_surfaceIsFromNewReader() {
        imageReaderProxy.resize(SIZE)

        assertEquals(mockSurface, imageReaderProxy.surface)
    }

    @Test
    fun resize_closesOldReader() {
        imageReaderProxy.resize(SIZE)
        imageReaderProxy.resize(SIZE_2)

        verify(mockImageReader).close()
    }

    @Test
    fun resize_createsSecondReaderAfterFirst() {
        imageReaderProxy.resize(SIZE)
        imageReaderProxy.resize(SIZE_2)

        assertEquals(2, ShadowImageReader.getInstanceCreationCount())
    }

    // ---- close ----

    @Test
    fun close_closesReader() {
        imageReaderProxy.resize(SIZE)
        imageReaderProxy.close()

        verify(mockImageReader).close()
    }

    @Test
    fun close_withoutResize_doesNotCrash() {
        // Should not throw even though no reader was created
        imageReaderProxy.close()

        verify(mockImageReader, never()).close()
    }

    @Test
    fun close_makesGetLastFrameReturnNull() {
        imageReaderProxy.resize(SIZE)
        imageReaderProxy.close()

        assertNull(imageReaderProxy.getLastFrame())
    }

    // ---- getLastFrame ----

    @Test
    fun getLastFrame_withoutResize_returnsNull() {
        assertNull(imageReaderProxy.getLastFrame())
    }

    @Test
    fun getLastFrame_newFrameAvailable_returnsBitmap() {
        setupImageMock(mockImage, WIDTH, HEIGHT)
        mockWhen(mockBitmapRepository.getDisplayRecorderBitmap(WIDTH, HEIGHT)).thenReturn(mockBitmap)
        mockWhen(mockBitmap.width).thenReturn(WIDTH)
        mockWhen(mockBitmap.height).thenReturn(HEIGHT)
        mockWhen(mockImageReader.acquireLatestImage()).thenReturn(mockImage)
        imageReaderProxy.resize(SIZE)

        val result = imageReaderProxy.getLastFrame()

        assertEquals(mockBitmap, result)
    }

    @Test
    fun getLastFrame_noNewFrame_returnsCachedFrame() {
        setupImageMock(mockImage, WIDTH, HEIGHT)
        mockWhen(mockBitmapRepository.getDisplayRecorderBitmap(WIDTH, HEIGHT)).thenReturn(mockBitmap)
        mockWhen(mockBitmap.width).thenReturn(WIDTH)
        mockWhen(mockBitmap.height).thenReturn(HEIGHT)
        // First call returns an image, second returns null
        mockWhen(mockImageReader.acquireLatestImage())
            .thenReturn(mockImage)
            .thenReturn(null)
        imageReaderProxy.resize(SIZE)

        imageReaderProxy.getLastFrame() // primes the cache
        val result = imageReaderProxy.getLastFrame()

        assertEquals(mockBitmap, result)
    }

    @Test
    fun acquireLatestFrame_noNewFrame_returnsNullInsteadOfCachedFrame() {
        setupImageMock(mockImage, WIDTH, HEIGHT)
        mockWhen(mockBitmapRepository.getDisplayRecorderBitmap(WIDTH, HEIGHT)).thenReturn(mockBitmap)
        mockWhen(mockBitmap.width).thenReturn(WIDTH)
        mockWhen(mockBitmap.height).thenReturn(HEIGHT)
        mockWhen(mockImageReader.acquireLatestImage())
            .thenReturn(mockImage)
            .thenReturn(null)
        imageReaderProxy.resize(SIZE)

        assertEquals(mockBitmap, imageReaderProxy.acquireLatestFrame())
        assertNull(imageReaderProxy.acquireLatestFrame())
    }

    @Test
    fun getLastFrame_afterResize_doesNotReturnStaleFrame() {
        setupImageMock(mockImage, WIDTH, HEIGHT)
        mockWhen(mockBitmapRepository.getDisplayRecorderBitmap(WIDTH, HEIGHT)).thenReturn(mockBitmap)
        mockWhen(mockBitmap.width).thenReturn(WIDTH)
        mockWhen(mockBitmap.height).thenReturn(HEIGHT)
        mockWhen(mockImageReader.acquireLatestImage())
            .thenReturn(mockImage) // first resize: frame available
            .thenReturn(null)       // second resize: no new frame yet
        imageReaderProxy.resize(SIZE)
        imageReaderProxy.getLastFrame() // cache a frame from the first reader

        imageReaderProxy.resize(SIZE_2) // resize resets lastFrame

        assertNull(imageReaderProxy.getLastFrame())
    }

    @Test
    fun getLastFrame_runtimeException_throwsScreenCaptureException() {
        mockWhen(mockImageReader.acquireLatestImage()).thenThrow(RuntimeException("gralloc lock failed"))
        imageReaderProxy.resize(SIZE)

        assertThrows(ScreenCaptureException::class.java) {
            imageReaderProxy.getLastFrame()
        }
    }

    @Test
    fun getLastFrame_unsupportedOperationException_returnsNull() {
        mockWhen(mockImageReader.acquireLatestImage()).thenThrow(UnsupportedOperationException("unsupported format"))
        imageReaderProxy.resize(SIZE)

        assertNull(imageReaderProxy.getLastFrame())
    }

    // ---- helpers ----

    @Test
    fun paddedPortraitAndLandscapeFrames_keepRgbaPixelsRegardlessOfBufferOrder() {
        for (size in listOf(Point(3, 7), Point(7, 3))) {
            for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
                assertPaddedPixels(size, order, pixelStride = 4)
            }
        }
    }

    @Test
    fun paddedPlaneWithLargerPixelStride_doesNotCopyPaddingAsPixels() {
        assertPaddedPixels(Point(3, 7), ByteOrder.BIG_ENDIAN, pixelStride = 8)
        assertPaddedPixels(Point(7, 3), ByteOrder.LITTLE_ENDIAN, pixelStride = 8, rowPadding = 0)
        assertPaddedPixels(Point(3, 7), ByteOrder.BIG_ENDIAN, pixelStride = 5)
    }

    private fun assertPaddedPixels(size: Point, order: ByteOrder, pixelStride: Int, rowPadding: Int = 12) {
        val rowStride = size.x * pixelStride + rowPadding
        // The last row need not have trailing padding in Image.Plane's buffer.
        val buffer = ByteBuffer.allocate(rowStride * (size.y - 1) + (size.x - 1) * pixelStride + 4)
        val expected = IntArray(size.x * size.y) { index ->
            Color.rgb(20 + index * 3, 70 + index * 2, 150 - index)
        }
        expected.forEachIndexed { index, color ->
            val offset = index / size.x * rowStride + index % size.x * pixelStride
            buffer.put(offset, Color.red(color).toByte())
            buffer.put(offset + 1, Color.green(color).toByte())
            buffer.put(offset + 2, Color.blue(color).toByte())
            buffer.put(offset + 3, 0xff.toByte())
        }
        buffer.order(order)
        val image = mock(Image::class.java)
        val plane = mock(Image.Plane::class.java)
        mockWhen(image.width).thenReturn(size.x)
        mockWhen(image.height).thenReturn(size.y)
        mockWhen(plane.rowStride).thenReturn(rowStride)
        mockWhen(plane.pixelStride).thenReturn(pixelStride)
        mockWhen(plane.buffer).thenReturn(buffer)
        mockWhen(image.planes).thenReturn(arrayOf(plane))
        val bitmap = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
        mockWhen(mockBitmapRepository.getDisplayRecorderBitmap(size.x, size.y)).thenReturn(bitmap)
        mockWhen(mockImageReader.acquireLatestImage()).thenReturn(image)
        imageReaderProxy.resize(size)
        try {
            val result = imageReaderProxy.acquireLatestFrame()!!
            expected.forEachIndexed { index, color ->
                assertEquals("size=$size, order=$order, stride=$pixelStride, pixel=$index",
                    color, result.getPixel(index % size.x, index / size.x))
            }
            assertEquals("reader buffer must not be consumed", 0, buffer.position())
            verify(image).close()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Sets up a mock [Image] with the minimal plane/buffer structure needed for [ImageReaderProxy.getLastFrame]
     * to complete the pixel-copy path without crashing.
     *
     * We set rowStride == width * pixelStride so the "no padding" branch is taken, which calls
     * [Bitmap.copyPixelsFromBuffer] — the simplest path with the fewest moving parts.
     */
    private fun setupImageMock(image: Image, width: Int, height: Int) {
        mockWhen(image.width).thenReturn(width)
        mockWhen(image.height).thenReturn(height)

        val pixelStride = 4
        val rowStride = width * pixelStride
        val buffer = ByteBuffer.allocate(rowStride * height)

        val plane = mock(Image.Plane::class.java)
        mockWhen(plane.pixelStride).thenReturn(pixelStride)
        mockWhen(plane.rowStride).thenReturn(rowStride)
        mockWhen(plane.buffer).thenReturn(buffer)
        mockWhen(image.planes).thenReturn(arrayOf(plane))
    }
}
