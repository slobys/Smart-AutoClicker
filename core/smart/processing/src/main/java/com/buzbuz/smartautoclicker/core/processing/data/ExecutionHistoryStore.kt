package com.buzbuz.smartautoclicker.core.processing.data

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers.IO
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.isFailure
import com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeFailure
import com.buzbuz.smartautoclicker.core.processing.domain.model.RuntimeStopReason
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
    // Retain only the most recent bounded session so an escalated stop can update its outcome.
    private var lastEnded: JSONObject? = null
    private var pending = 0
    private var screenshotCount = 0

    suspend fun begin(name: String) = disk {
        flush()
        lastEnded = null
        active = JSONObject().put("id", UUID.randomUUID().toString()).put("name", name.take(256))
            .put("startedAt", System.currentTimeMillis()).put("actions", JSONArray())
            .put("pid", android.os.Process.myPid()).put("memorySamples", JSONArray())
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            active?.put("packageName", context.packageName)?.put("versionName", info.versionName)
        } catch (_: Exception) { /* Metadata failure must not prevent recording the session. */ }
        pending = 0
        screenshotCount = 0
        flush()
        prune()
    }

    suspend fun end(
        reason: RuntimeStopReason = RuntimeStopReason.USER_PAUSE,
        failure: RuntimeFailure? = null,
        cleanupCompleted: Boolean = true,
        updatePrevious: Boolean = false,
    ) = disk {
        val termination = JSONObject().put("reason", reason.name)
            .put("timestamp", System.currentTimeMillis()).put("cleanupCompleted", cleanupCompleted)
        failure?.let {
            termination.put("stage", it.stage).put("exceptionType", it.exceptionType)
                .put("message", it.message).put("stackTrace", it.stackTrace)
        }
        // Only the same in-progress cleanup may revise an ended session. A later capture/startup
        // failure belongs to runtime-latest, not to an unrelated completed scenario.
        val session = active ?: lastEnded.takeIf { updatePrevious }
        session?.let {
            it.put("endedAt", System.currentTimeMillis()).put("termination", termination)
            writeJson(File(sessionDirectory(it.getString("id")), "session.json"), it)
            lastEnded = it
        }
        // Also retain startup failures that happen before an action-history session exists.
        writeJson(File(root, "runtime-latest.json"), JSONObject()
            .put("sessionId", session?.optString("id") ?: JSONObject.NULL).put("termination", termination))
        active = null
        if (session == null) lastEnded = null
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

    /** Persist one bounded sample even when waiting for a condition and no actions have executed. */
    suspend fun recordMemorySample(sample: JSONObject) = disk {
        val session = active ?: return@disk
        val samples = session.getJSONArray("memorySamples")
        samples.put(sample)
        while (samples.length() > 120) samples.remove(0)
        flush()
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
                zip.putNextEntry(ZipEntry("diagnostics.json"))
                zip.write(exportDiagnostics().toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
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
        writeJson(File(sessionDirectory(session.getString("id")), "session.json"), session)
        pending = 0
    }

    private fun writeJson(target: File, json: JSONObject) {
        target.parentFile?.mkdirs()
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }

    /** Only this app's bounded exit history. No device identifiers, credentials, or other apps' logs. */
    private fun exportDiagnostics(): JSONObject = JSONObject().apply {
        put("exportedAt", System.currentTimeMillis())
        put("sdk", Build.VERSION.SDK_INT)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("latestRuntime", readJson(File(root, "runtime-latest.json")) ?: JSONObject.NULL)
        put("debugReportStatus", context.cacheDir?.let { readJson(File(it, "DebugReportStatus.json")) } ?: JSONObject.NULL)
        put("systemExitHistory", JSONArray())
        try {
            put("packageName", context.packageName)
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            put("versionName", info.versionName)
        } catch (error: Exception) {
            put("versionCollectionError", error.javaClass.name)
        }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                put("systemExitHistoryStatus", "unsupported_before_android_11")
            } else {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (manager == null) put("systemExitHistoryStatus", "unavailable")
                else {
                    manager.getHistoricalProcessExitReasons(context.packageName, 0, 5).take(5).forEach { exit ->
                        getJSONArray("systemExitHistory").put(JSONObject()
                            .put("timestamp", exit.timestamp).put("reasonCode", exit.reason)
                            .put("status", exit.status).put("pid", exit.pid)
                            .put("importance", exit.importance)
                            .put("reasonName", exitReasonName(exit.reason))
                            .put("description", exit.description?.take(1024))
                            .put("pssKb", exit.pss).put("rssKb", exit.rss))
                    }
                    put("systemExitHistoryStatus", "available")
                }
            }
        } catch (error: Exception) {
            put("systemExitHistoryStatus", "unavailable")
            put("collectionError", error.javaClass.name)
        }
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

private fun exitReasonName(reason: Int): String = when (reason) {
    3 -> "LOW_MEMORY"
    4 -> "JAVA_CRASH"
    5 -> "NATIVE_CRASH"
    6 -> "ANR"
    else -> "OTHER_$reason"
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
