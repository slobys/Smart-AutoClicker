/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RouteStoreTests {
    @get:Rule val directory = TemporaryFolder()
    private val context: Context get() = mock { on { filesDir } doReturn directory.root }

    @Test fun roundTripAndRestartPreserveAllFields() = runTest {
        val route = exampleRoute(); RouteStore(context).save(route)
        assertEquals(route, RouteStore(context).list().single())
    }
    @Test fun interruptedDraftRemainsDraftAfterRestart() = runTest {
        RouteStore(context).save(exampleRoute().copy(recordingComplete = false))
        assertFalse(RouteStore(context).list().single().recordingComplete)
    }
    @Test fun corruptFileDoesNotHideOtherRoutes() = runTest {
        val store = RouteStore(context); store.save(exampleRoute())
        File(directory.root, "routes/${UUID.randomUUID()}.json").writeText("broken")
        assertEquals(listOf(exampleRoute()), store.list())
    }
    @Test fun mismatchedIdentityCannotExecuteAnotherRoute() = runTest {
        val store = RouteStore(context)
        val route = exampleRoute()
        store.save(route)
        File(directory.root, "routes/${route.id}.json").writeText(
            RouteStore.encode(route.copy(id = UUID.randomUUID().toString())),
        )
        try { store.load(route.id); fail("Wrong route identity") } catch (_: IllegalArgumentException) { }
        assertTrue(store.list().isEmpty())
        assertTrue(store.summaries().isEmpty())
    }
    @Test fun rejectsUnsupportedVersionAndTooManyPoints() {
        val json = JSONObject(RouteStore.encode(exampleRoute())).put("version", 9)
        assertThrows(IllegalArgumentException::class.java) { RouteStore.decode(json.toString()) }
        val oversized = exampleRoute().copy(points = List(2001) { RoutePoint(1.0, 2.0) })
        assertThrows(IllegalArgumentException::class.java) { RouteStore.decode(RouteStore.encode(oversized)) }
    }
    @Test fun refusesToEvictExistingRoutesWhenFull() = runTest {
        val store = RouteStore(context)
        repeat(50) { store.save(exampleRoute().copy(id = UUID.randomUUID().toString())) }
        try { store.save(exampleRoute()); fail("Storage must be bounded") } catch (_: IllegalStateException) { }
        assertEquals(50, store.list().size)
        store.save(store.list().first().copy(name = "Updated"))
        assertTrue(store.list().any { it.name == "Updated" })
    }
    @Test fun deleteIsLimitedToOneValidatedRouteId() = runTest {
        val store = RouteStore(context); store.save(exampleRoute())
        val untouched = File(directory.root, "keep.txt").apply { writeText("keep") }
        try { store.delete("../keep"); fail("Unsafe id") } catch (_: IllegalArgumentException) { }
        store.delete(exampleRoute().id)
        assertTrue(store.list().isEmpty()); assertEquals("keep", untouched.readText())
    }
}
