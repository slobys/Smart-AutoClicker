package com.buzbuz.smartautoclicker.core.processing.tests

import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.action.Pause
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.ExecutionHistoryStore
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeFailure
import com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeStopReason
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ExecutionHistoryStoreTests {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = mock { on { filesDir } doReturn temporary.root }
    private val event = ScreenEvent(Identifier(databaseId = 1), Identifier(databaseId = 2), "主流程", AND,
        emptyList(), emptyList(), true, 0, false, 0)
    private val action = Pause(Identifier(databaseId = 3), event.id, "等待加载", 0, 100)

    @Test fun endedHistorySurvivesRestartAndKeepsFailureDetails() = runTest {
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("每日任务")
        store.record(event, action, ActionExecutionResult.Failed("无法截图"), 123, null)
        store.end()
        val restarted = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        val summary = restarted.listSessions().single()
        assertEquals("每日任务", summary.name)
        assertEquals(1, summary.count)
        val json = JSONObject(restarted.readSession(summary.id)!!)
        assertTrue(json.has("endedAt"))
        val row = json.getJSONArray("actions").getJSONObject(0)
        assertEquals("无法截图", row.getString("detail"))
        assertEquals(123, row.getInt("durationMs"))
    }

    @Test fun actionHistoryIsBoundedToLatest500() = runTest {
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("边界")
        repeat(510) { store.record(event, action.copy(name = "动作$it"), ActionExecutionResult.Success, 1, null) }
        store.end()
        val summary = store.listSessions().single()
        val records = JSONObject(store.readSession(summary.id)!!).getJSONArray("actions")
        assertEquals(500, records.length())
        assertEquals("动作10", records.getJSONObject(0).getString("action"))
        assertEquals("动作509", records.getJSONObject(499).getString("action"))
    }

    @Test fun sessionHistoryIsBoundedTo20WithoutRemovingUnrelatedFiles() = runTest {
        val unrelated = File(temporary.root, "execution-history/user-file.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        repeat(25) { store.begin("场景$it"); store.end() }
        assertEquals(20, store.listSessions().size)
        assertEquals("keep", unrelated.readText())
    }

    @Test fun exportContainsOnlySessionAndAtMostFivePrivateScreenshots() = runTest {
        val image = File(temporary.root, "debug/action_failure_latest.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("失败截图")
        repeat(8) { store.record(event, action, ActionExecutionResult.Failed("失败"), 1, image.path) }
        store.end()
        val id = store.listSessions().single().id
        File(temporary.root, "execution-history/$id/not-exported.txt").writeText("private")
        val output = ByteArrayOutputStream()
        store.exportSession(id, output)
        val names = mutableListOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name; zip.closeEntry() }
        }
        assertEquals(7, names.size)
        assertTrue(names.contains("session.json"))
        assertTrue(names.contains("diagnostics.json"))
        assertFalse(names.contains("not-exported.txt"))
    }

    @Test fun refusesForeignScreenshotPathsAndTraversalIds() = runTest {
        val image = temporary.newFile("unrelated.png").apply { writeText("private") }
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("安全")
        store.record(event, action, ActionExecutionResult.Failed("失败"), 1, image.path)
        store.end()
        val row = JSONObject(store.readSession(store.listSessions().single().id)!!).getJSONArray("actions").getJSONObject(0)
        assertFalse(row.has("screenshot"))
        assertNull(store.readSession("../unrelated.png"))
        try { store.exportSession("../unrelated.png", ByteArrayOutputStream()); fail("Traversal accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun diagnosticsStorageFailureDoesNotThrowIntoScenario() = runTest {
        File(temporary.root, "execution-history").writeText("not a directory")
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("无空间")
        store.record(event, action, ActionExecutionResult.Failed("失败"), 1, null)
        store.end()
        assertTrue(store.listSessions().isEmpty())
    }

    @Test fun runtimeFailurePersistsStageStackAndCleanupOutcome() = runTest {
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("异常停止")
        store.end(RuntimeStopReason.EXECUTION_ERROR,
            RuntimeFailure.from("detection", IllegalStateException("Image buffer invalid")), false)
        val restarted = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        val session = JSONObject(restarted.readSession(restarted.listSessions().single().id)!!)
        val stop = session.getJSONObject("termination")
        assertEquals("EXECUTION_ERROR", stop.getString("reason"))
        assertEquals("detection", stop.getString("stage"))
        assertTrue(stop.getString("stackTrace").contains("Image buffer invalid"))
        assertFalse(stop.getBoolean("cleanupCompleted"))
    }

    @Test fun escalatedStopUpdatesOnlyTheMostRecentSession() = runTest {
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("第一次")
        val first = store.listSessions().single().id
        store.end()
        store.end(RuntimeStopReason.PROJECTION_LOST, updatePrevious = true)
        assertEquals("PROJECTION_LOST", JSONObject(store.readSession(first)!!)
            .getJSONObject("termination").getString("reason"))
        store.begin("第二次")
        store.end(RuntimeStopReason.SCENARIO_REQUEST)
        assertEquals("PROJECTION_LOST", JSONObject(store.readSession(first)!!)
            .getJSONObject("termination").getString("reason"))
    }

    @Test fun olderAndroidExportsAnExplicitUnsupportedExitStatus() = runTest {
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("兼容")
        store.end()
        val json = exportedDiagnostics(store)
        assertEquals("unsupported_before_android_11", json.getString("systemExitHistoryStatus"))
        assertEquals(0, json.getJSONArray("systemExitHistory").length())
    }

    @Test fun laterStartupFailureDoesNotOverwriteACompletedSession() = runTest {
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("已正常结束")
        val first = store.listSessions().single().id
        store.end(RuntimeStopReason.SCENARIO_REQUEST)
        store.end(RuntimeStopReason.STARTUP_ERROR,
            RuntimeFailure.from("screen_capture_start", IllegalStateException("Permission expired")))
        assertEquals("SCENARIO_REQUEST", JSONObject(store.readSession(first)!!)
            .getJSONObject("termination").getString("reason"))
        val latest = exportedDiagnostics(store).getJSONObject("latestRuntime")
        assertTrue(latest.isNull("sessionId"))
        assertEquals("STARTUP_ERROR", latest.getJSONObject("termination").getString("reason"))
    }

    @Test @Config(sdk = [Build.VERSION_CODES.R])
    fun android11ExportsOnlyFiveExitRecordsForOwnPackage() = runTest {
        val manager: ActivityManager = mock()
        val exit: ApplicationExitInfo = mock {
            on { reason } doReturn ApplicationExitInfo.REASON_LOW_MEMORY
            on { timestamp } doReturn 1234L
            on { description } doReturn "low memory"
        }
        val ownContext: Context = mock {
            on { filesDir } doReturn temporary.root
            on { packageName } doReturn "test.klickr"
            on { getSystemService(Context.ACTIVITY_SERVICE) } doReturn manager
        }
        whenever(manager.getHistoricalProcessExitReasons("test.klickr", 0, 5)).thenReturn(List(7) { exit })
        val store = ExecutionHistoryStore(ownContext, StandardTestDispatcher(testScheduler))
        store.begin("系统记录")
        store.end()
        val json = exportedDiagnostics(store)
        assertEquals("available", json.getString("systemExitHistoryStatus"))
        assertEquals(5, json.getJSONArray("systemExitHistory").length())
        assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY,
            json.getJSONArray("systemExitHistory").getJSONObject(0).getInt("reasonCode"))
        verify(manager).getHistoricalProcessExitReasons("test.klickr", 0, 5)
    }

    private suspend fun exportedDiagnostics(store: ExecutionHistoryStore): JSONObject {
        val output = ByteArrayOutputStream()
        store.exportSession(store.listSessions().single().id, output)
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("Missing diagnostics.json")
                if (entry.name == "diagnostics.json") return JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    @Test fun memorySamplesPersistWithoutActionsAndAreBoundedAcrossRestart() = runTest {
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("等待目标出现")
        repeat(130) { index ->
            store.recordMemorySample(JSONObject().put("timestamp", index).put("pssKb", 12345)
                .put("state", "DETECTING").put("systemLowMemory", false))
        }
        // Simulate process death without end(): samples must already be durable.
        val restarted = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        val session = JSONObject(restarted.readSession(restarted.listSessions().single().id)!!)
        val samples = session.getJSONArray("memorySamples")
        assertEquals(120, samples.length())
        assertEquals(10, samples.getJSONObject(0).getInt("timestamp"))
        assertEquals(129, samples.getJSONObject(119).getInt("timestamp"))
        assertEquals(0, session.getJSONArray("actions").length())
        assertFalse(session.has("endedAt"))
        assertTrue(session.has("pid"))
    }

    @Test fun endedAndNewSessionsDoNotShareMemorySamples() = runTest {
        val store = ExecutionHistoryStore(context, StandardTestDispatcher(testScheduler))
        store.begin("第一轮")
        val firstId = store.listSessions().single().id
        store.recordMemorySample(JSONObject().put("timestamp", 1))
        store.end()
        store.recordMemorySample(JSONObject().put("timestamp", 2))
        store.begin("第二轮")
        val secondId = store.listSessions().first { it.id != firstId }.id
        store.recordMemorySample(JSONObject().put("timestamp", 3))
        assertEquals(1, JSONObject(store.readSession(firstId)!!).getJSONArray("memorySamples").length())
        val current = JSONObject(store.readSession(secondId)!!).getJSONArray("memorySamples")
        assertEquals(1, current.length())
        assertEquals(3, current.getJSONObject(0).getInt("timestamp"))
    }
}
