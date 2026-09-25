package com.buzbuz.smartautoclicker.core.processing.tests

import android.content.Context
import android.os.Build
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.action.Pause
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.processing.data.ExecutionHistoryStore
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
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
        assertEquals(6, names.size)
        assertTrue(names.contains("session.json"))
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
}
