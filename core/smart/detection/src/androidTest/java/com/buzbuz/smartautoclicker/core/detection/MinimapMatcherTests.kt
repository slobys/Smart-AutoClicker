/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.detection

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Runs the shipped JNI/OpenCV code, including animated overlays and ambiguous maps. */
@RunWith(AndroidJUnit4::class)
class MinimapMatcherTests {
    private val matcher = MinimapMatcher()
    private fun world(seed: Int): ByteArray {
        val random = Random(seed)
        val pixels = ByteArray(384 * 384) { 20 }
        // Irregular road intersections/room outlines, not a single matching icon.
        repeat(220) {
            val x = random.nextInt(2, 350); val y = random.nextInt(2, 350)
            val w = random.nextInt(5, 32); val h = random.nextInt(5, 32)
            val color = random.nextInt(80, 240).toByte()
            for (dy in 0..h) for (dx in 0..w)
                if (dy < 2 || dx < 2 || dy >= h - 1 || dx >= w - 1) pixels[(y + dy) * 384 + x + dx] = color
        }
        return pixels
    }
    private fun frame(world: ByteArray, x: Int = 70, y: Int = 70, animation: Int = 0): ByteArray =
        ByteArray(192 * 192) { i ->
            val px = i % 192; val py = i / 192
            if (px in 88..104 && py in 88..104) (100 + animation % 155).toByte()
            else world[(y + py) * 384 + x + px]
        }

    @Test fun stationaryMapMatches() {
        val image = frame(world(32))
        val result = requireNotNull(matcher.match(image, image, 16))
        assertEquals(0.0, result.dx, .1); assertEquals(0.0, result.dy, .1)
        assertTrue(result.inliers >= 5)
    }
    @Test fun scrollingMapRecoversBothDirectionsDespiteAnimatedPlayer() {
        val map = world(32); val reference = frame(map)
        listOf(12 to 0, -10 to 4, 0 to 14, 16 to -16, -24 to -8).forEach { (dx, dy) ->
            val result = requireNotNull(matcher.match(reference, frame(map, 70 + dx, 70 + dy, 143), 16))
            assertEquals(-dx.toDouble(), result.dx, 1.0); assertEquals(-dy.toDouble(), result.dy, 1.0)
        }
    }
    @Test fun wrongMapIsRejected() { assertNull(matcher.match(frame(world(32)), frame(world(987)), 16)) }
    @Test fun blankMapIsRejected() { assertNull(matcher.match(ByteArray(192 * 192), ByteArray(192 * 192), 16)) }
    @Test fun repeatedTilesAreRejected() {
        val tiles = ByteArray(192 * 192) { i -> if ((i % 192 / 6 + i / 192 / 6) % 2 == 0) 10 else 120 }
        assertNull(matcher.match(tiles, tiles, 16))
    }
    @Test fun occludedMapIsRejected() {
        assertNull(matcher.match(frame(world(32)), ByteArray(192 * 192) { 30 }, 16))
    }
    @Test fun rotationIsNotMistakenForMovement() {
        val a = frame(world(32)); val b = ByteArray(a.size) { i -> a[(191 - i % 192) * 192 + i / 192] }
        assertNull(matcher.match(a, b, 16))
    }
    @Test fun zoomChangeIsRejected() {
        val a = frame(world(32)); val b = ByteArray(a.size) { i -> a[(48 + i / 192 / 2) * 192 + 48 + i % 192 / 2] }
        assertNull(matcher.match(a, b, 16))
    }
    @Test fun largeTeleportIsRejected() { val map = world(32); assertNull(matcher.match(frame(map), frame(map, 170, 150), 16)) }
    @Test fun changingFramesStayBoundedAndDoNotKeepLastSuccess() {
        val map = world(32); val reference = frame(map)
        repeat(120) { index ->
            val result = matcher.match(reference, frame(map, 70 + index % 16, 70, index), 16)
            assertNotNull("frame $index", result)
            assertEquals(-(index % 16).toDouble(), result!!.dx, 1.0)
        }
        assertNull(matcher.match(reference, ByteArray(reference.size), 16))
    }
    @Test fun badInputIsRejectedBeforeNativeAccess() {
        assertThrows(IllegalArgumentException::class.java) { matcher.match(ByteArray(2), ByteArray(2), 16) }
        assertThrows(IllegalArgumentException::class.java) { matcher.match(ByteArray(192 * 192), ByteArray(192 * 192), 200) }
    }
}
