/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import com.buzbuz.smartautoclicker.core.detection.MinimapMatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MinimapRouteTests {
    @Test fun returnRelocalizesAtMidRouteAndResetDoesNotRebaseOrigin() {
        val frames = (0..8).map { i -> RouteKeyframe(RoutePoint(50_000.0 + i * 24, 50_000.0),
            MinimapFrames.encode(ByteArray(192 * 192) { i.toByte() })) }
        val c = config().copy(keyframes = frames)
        var currentX = 50_024.0
        val locator = MinimapLocalizer(c, false, frames.last().position, allowGlobalStart = true) { reference, _, _ ->
            val dx = 50_000.0 + reference[0] * 24 - currentX
            if (kotlin.math.abs(dx) <= 12) MinimapMatcher.Match(dx, 0.0, .9, 8) else null
        }
        assertEquals(RoutePoint(currentX, 50_000.0), locator.locate(bytes))
        currentX = 50_192.0
        assertNull(locator.locate(bytes)) // No unexplained jump while running.
        locator.reset()
        assertEquals(RoutePoint(currentX, 50_000.0), locator.locate(bytes))
        assertEquals(c, locator.snapshot())
    }

    private val bytes = ByteArray(192 * 192)
    private fun config() = RouteMinimap(RouteArea(100, 100, 292, 292), keyframes = listOf(
        RouteKeyframe(RoutePoint(50_000.0, 50_000.0), MinimapFrames.encode(bytes))), tested = true)

    @Test fun visualRouteRoundTrip() {
        val route = exampleRoute().copy(positionMode = RoutePositionMode.MINIMAP, minimap = config(), joystickDurationMs = 300)
        assertEquals(route, RouteStore.decode(RouteStore.encode(route)))
    }
    @Test fun versionOneRemainsReadable() {
        val old = JSONObject(RouteStore.encode(exampleRoute())).apply {
            put("version", 1); remove("positionMode"); remove("minimap"); remove("joystickDurationMs")
        }
        assertEquals(exampleRoute(), RouteStore.decode(old.toString()))
    }
    @Test fun minimapNeedsLandmarksAndBoundedRegion() {
        assertFalse(config().copy(keyframes = emptyList()).valid(1920, 1080))
        assertFalse(config().copy(area = RouteArea(0, 0, 800, 800)).valid(1920, 1080))
        assertFalse(config().copy(keyframes = List(33) { config().keyframes.first() }).valid(1920, 1080))
    }
    @Test fun locatorReturnsWorldPositionNotScreenTranslation() {
        val locator = MinimapLocalizer(config(), false) { _, _, _ -> MinimapMatcher.Match(-10.0, 4.0, .9, 8) }
        assertEquals(RoutePoint(50_010.0, 49_996.0), locator.locate(bytes))
    }
    @Test fun returnLocalizesNearLastLandmarkAndTraversesBackWithoutRebasing() {
        val frames = (0..5).map { i -> RouteKeyframe(RoutePoint(50_000.0 + i * 24, 50_000.0),
            MinimapFrames.encode(ByteArray(192 * 192) { i.toByte() })) }
        val c = config().copy(keyframes = frames)
        var currentX = 50_120.0
        val match: (ByteArray, ByteArray, Int) -> MinimapMatcher.Match? = { reference, _, _ ->
            val dx = 50_000.0 + reference[0] * 24 - currentX
            if (kotlin.math.abs(dx) <= 24) MinimapMatcher.Match(dx, 0.0, .9, 8) else null
        }
        assertNull(MinimapLocalizer(c, false, match = match).locate(bytes))
        val locator = MinimapLocalizer(c, false, initialPosition = frames.last().position, match = match)
        for (step in 0..10) {
            currentX = 50_120.0 - step * 12
            assertEquals(RoutePoint(currentX, 50_000.0), locator.locate(bytes))
        }
        assertEquals(c, locator.snapshot())
    }
    @Test fun choosingReturnLandmarkDoesNotBypassConflictProtection() {
        val c = config().copy(keyframes = (0..5).map { i -> config().keyframes.first().copy(
            position = RoutePoint(50_000.0 + i * 24, 50_000.0)) })
        val locator = MinimapLocalizer(c, false, initialPosition = c.keyframes.last().position) { _, _, _ ->
            MinimapMatcher.Match(0.0, 0.0, .9, 8)
        }
        assertNull(locator.locate(bytes))
    }
    @Test fun locatorNeverReturnsLastCoordinateOnFailure() {
        var succeed = true
        val locator = MinimapLocalizer(config(), false) { _, _, _ -> if (succeed) MinimapMatcher.Match(0.0, 0.0, .9, 8) else null }
        assertNotNull(locator.locate(bytes)); succeed = false
        assertNull(locator.locate(bytes)); assertEquals(0.0, locator.quality, 0.0)
    }
    @Test fun locatorRejectsJump() {
        var shift = 0.0
        val locator = MinimapLocalizer(config(), false) { _, _, _ -> MinimapMatcher.Match(shift, 0.0, .9, 8) }
        assertNotNull(locator.locate(bytes)); shift = 50.0
        assertNull(locator.locate(bytes))
    }
    @Test fun conflictingLandmarksAreRejected() {
        val c = config().copy(keyframes = config().keyframes + config().keyframes.first().copy(position = RoutePoint(50_020.0, 50_000.0)))
        val locator = MinimapLocalizer(c, false) { _, _, _ -> MinimapMatcher.Match(0.0, 0.0, .9, 8) }
        assertNull(locator.locate(bytes))
    }
    @Test fun replayDoesNotLearnNewLandmarks() {
        val locator = MinimapLocalizer(config(), false) { _, _, _ -> MinimapMatcher.Match(-30.0, 0.0, .9, 8) }
        locator.locate(bytes)
        assertEquals(1, locator.snapshot().keyframes.size)
    }
    @Test fun recordingAddsOnlySpacedLandmarks() {
        val locator = MinimapLocalizer(config(), true) { _, _, _ -> MinimapMatcher.Match(-25.0, 0.0, .9, 8) }
        locator.locate(bytes)
        assertEquals(2, locator.snapshot().keyframes.size)
    }
    @Test fun invalidEncodedLandmarkFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            MinimapLocalizer(config().copy(keyframes = listOf(RouteKeyframe(RoutePoint(0.0, 0.0), "invalid"))), false) { _, _, _ -> null }
        }
    }
    @Test fun observationTestNeverMovesAndRequiresTravel() = runTest {
        val control = RouteRunControl()
        var reads = 0; var moves = 0
        val port = object : RoutePort {
            override fun now() = testScheduler.currentTime
            override suspend fun read() = RoutePoint(50_000.0 + reads++, 50_000.0)
            override suspend fun move(offset: RoutePoint): Boolean { moves++; return true }
        }
        assertTrue(testMinimap(port, control) {})
        assertTrue(reads >= 20); assertEquals(0, moves)
    }
    @Test fun standingStillCannotPassLocalizationTest() = runTest {
        val port = object : RoutePort {
            override fun now() = testScheduler.currentTime
            override suspend fun read() = RoutePoint(50_000.0, 50_000.0)
            override suspend fun move(offset: RoutePoint): Boolean = error("Observe-only must not move")
        }
        assertFalse(testMinimap(port, RouteRunControl()) {})
        assertEquals(120_000, testScheduler.currentTime)
    }
    @Test fun unreliableObservationsCannotPassTest() = runTest {
        var reads = 0
        val port = object : RoutePort {
            override fun now() = testScheduler.currentTime
            override suspend fun read() = if (++reads % 2 == 0) RoutePoint(50_000.0 + reads, 50_000.0) else null
            override suspend fun move(offset: RoutePoint): Boolean = error("Observe-only must not move")
        }
        assertFalse(testMinimap(port, RouteRunControl()) {})
    }
    @Test fun shortJoystickMoveKeepsEffectiveRadiusAndShortensHold() {
        val r = exampleRoute().copy(control = RouteControl.JOYSTICK, joystickDurationMs = 500)
        val motion = routeMotion(r, RoutePoint(2.0, 0.0))
        assertEquals(100.0, motion.offset.distance(RoutePoint(0.0, 0.0)), .01)
        assertEquals(100, motion.durationMs)
    }
    @Test fun joystickNeverExceedsCalibrationHold() {
        val r = exampleRoute().copy(control = RouteControl.JOYSTICK, joystickDurationMs = 300)
        assertEquals(300, routeMotion(r, RoutePoint(200.0, 100.0)).durationMs)
    }
    @Test fun groundTapKeepsShortDistanceOffset() {
        val motion = routeMotion(exampleRoute(), RoutePoint(2.0, 0.0))
        assertEquals(20.0, motion.offset.x, .01); assertEquals(70, motion.durationMs)
    }
}
