package com.buzbuz.smartautoclicker.core.processing.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers.IO
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.isFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ExecutionHistorySummary(val id: String, val name: String, val startedAt: Long, val count: Int)

/** Local-only bounded diagnostics. Successful actions are flushed in batches; failures immediately. */
@Singleton
class ExecutionHistoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(IO) private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val root get() = File(context.filesDir, "execution-history")
    private var active: JSONObject? = null
    private var pending = 0
    private var screenshotCount = 0

    suspend fun begin(name: String) = disk {
        flush()
        active = JSONObject().put("id", UUID.randomUUID().toString()).put("name", name.take(256))
            .put("startedAt", System.currentTimeMillis()).put("actions", JSONArray())
        pending = 0
        screenshotCount = 0
        flush()
        prune()
    }

    suspend fun end() = disk {
        active?.put("endedAt", System.currentTimeMillis())
        flush()
        active = null
        prune()
    }

    suspend fun record(event: Event, action: Action, result: ActionExecutionResult, durationMs: Long, screenshotPath: String?) = disk {
        val session = active ?: return@disk
        val records = session.getJSONArray("actions")
        val row = JSONObject().put("timestamp", System.currentTimeMillis())
            .put("event", event.name.take(256)).put("eventId", event.id.databaseId)
            .put("action", action.name.orEmpty().take(256)).put("actionId", action.id.databaseId)
            .put("durationMs", durationMs.coerceAtLeast(0)).put("result", result.label())
            .put("detail", result.detail().take(1024))
        if (result.isFailure && screenshotPath != null && screenshotCount < 5) {
            val source = File(screenshotPath)
            // Only copy our recorder's private diagnostic images, never a user-supplied path.
            if (source.parentFile?.canonicalFile == File(context.filesDir, "debug").canonicalFile &&
                source.isFile && source.length() <= 5 * 1024 * 1024) {
                try {
                    val name = "failure-$screenshotCount.png"
                    source.copyTo(File(sessionDirectory(session.getString("id")), name), overwrite = true)
                    screenshotCount++
                    row.put("screenshot", name)
                } catch (error: Exception) {
                    Log.w("ExecutionHistory", "Unable to retain screenshot; keeping action details", error)
                }
            }
        }
        records.put(row)
        while (records.length() > 500) records.remove(0)
        pending++
        if (pending >= 16 || result.isFailure) { flush(); prune() }
    }

    suspend fun listSessions(): List<ExecutionHistorySummary> = withContext(dispatcher) {
        mutex.withLock {
            sessionFiles().mapNotNull { file -> readJson(file)?.let {
                ExecutionHistorySummary(it.optString("id"), it.optString("name"), it.optLong("startedAt"), it.optJSONArray("actions")?.length() ?: 0)
            } }.sortedByDescending { it.startedAt }
        }
    }

    suspend fun readSession(id: String): String? = withContext(dispatcher) {
        mutex.withLock {
            if (!validId(id)) return@withLock null
            if (active?.optString("id") == id) active?.toString(2)
            else readJson(File(root, "$id/session.json"))?.toString(2)
        }
    }

    suspend fun exportSession(id: String, output: OutputStream) = withContext(dispatcher) {
        mutex.withLock {
            require(validId(id))
            if (active?.optString("id") == id) flush()
            val folder = File(root, id)
            require(File(folder, "session.json").isFile)
            ZipOutputStream(output).use { zip ->
                folder.listFiles().orEmpty().filter { it.name == "session.json" || it.name.matches(Regex("failure-[0-4]\\.png")) }
                    .forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
        }
    }

    private suspend fun disk(block: () -> Unit) = withContext(dispatcher) {
        mutex.withLock {
            try { block() } catch (error: Exception) {
                // Diagnostics must never stop a user's scenario when storage is full.
                Log.w("ExecutionHistory", "Unable to write local diagnostics", error)
            }
        }
    }

    private fun flush() {
        val session = active ?: return
        val file = AtomicFile(File(sessionDirectory(session.getString("id")), "session.json"))
        val output = file.startWrite()
        try {
            output.write(session.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            pending = 0
        } catch (error: Exception) { file.failWrite(output); throw error }
    }

    private fun validId(id: String) = id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
    private fun sessionDirectory(id: String): File {
        require(validId(id))
        return File(root, id).apply { check(isDirectory || mkdirs()) }
    }
    private fun sessionFiles() = root.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }
        .map { File(it, "session.json") }.filter { it.isFile }
    private fun readJson(file: File): JSONObject? = try {
        if (file.length() > 4 * 1024 * 1024) null else JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun prune() {
        val directories = root.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }
            .sortedBy { File(it, "session.json").lastModified() }.toMutableList()
        var bytes = directories.sumOf { folder -> folder.listFiles().orEmpty().sumOf { it.length() } }
        while (directories.size > 20 || bytes > 64L * 1024 * 1024) {
            val oldest = directories.firstOrNull { it.name != active?.optString("id") } ?: break
            bytes -= oldest.listFiles().orEmpty().sumOf { it.length() }
            // Only immediate files created by this store, in its UUID-named private directory.
            oldest.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            oldest.delete()
            directories.remove(oldest)
        }
    }
}

private fun ActionExecutionResult.label(): String = when (this) {
    ActionExecutionResult.Success -> "成功"
    is ActionExecutionResult.Failed -> "失败"
    is ActionExecutionResult.TimedOut -> "超时"
    is ActionExecutionResult.Cancelled -> "取消"
    is ActionExecutionResult.Skipped -> "跳过"
}
private fun ActionExecutionResult.detail(): String = when (this) {
    ActionExecutionResult.Success -> ""
    is ActionExecutionResult.Failed -> reason
    is ActionExecutionResult.TimedOut -> "超过 ${timeoutMs} 毫秒"
    is ActionExecutionResult.Cancelled -> reason
    is ActionExecutionResult.Skipped -> reason
}
