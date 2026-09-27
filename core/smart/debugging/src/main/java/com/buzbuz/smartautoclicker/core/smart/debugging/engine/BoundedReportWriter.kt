package com.buzbuz.smartautoclicker.core.smart.debugging.engine

import android.util.Log
import com.buzbuz.smartautoclicker.core.smart.debugging.DebugReportMessage
import com.buzbuz.smartautoclicker.core.smart.debugging.data.DebugReportLocalDataSource
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.model.report.DebugReportOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/** One disk worker, not one coroutine per detection callback. Producers never wait for disk IO. */
internal class BoundedReportWriter(
    dispatcher: CoroutineDispatcher,
    private val source: DebugReportLocalDataSource,
) {
    private data class Work(val generation: Long, val bytes: Int = 0, val run: suspend () -> Unit)
    private val lock = Any()
    private val pending = ArrayDeque<Work>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var generation = 0L
    private var accepting = false
    private var queuedBytes = 0
    internal val pendingCount: Int get() = synchronized(lock) { pending.size }

    init {
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            for (signal in wakeUp) {
                while (true) {
                    val work = synchronized(lock) {
                        pending.pollFirst()?.also { queuedBytes -= it.bytes }
                    } ?: break
                    try {
                        work.run()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e(TAG, "Report IO failed; scenario and live debugging remain active", error)
                        synchronized(lock) {
                            if (generation == work.generation) {
                                accepting = false
                                pending.clear()
                                queuedBytes = 0
                            }
                        }
                        // Run cleanup on the same worker, before any subsequent session's start.
                        try { source.cancelReportWrite("io_failure") }
                        catch (cleanupError: Exception) {
                            if (cleanupError is CancellationException) throw cleanupError
                            Log.w(TAG, "Unable to close failed debug report", cleanupError)
                        }
                    }
                }
            }
        }
    }

    fun start(initialCounters: DebugReportMessage) = synchronized(lock) {
        generation++
        pending.clear()
        queuedBytes = 0
        accepting = true
        val bytes = initialCounters.serializedSize
        if (bytes > MAX_MESSAGE_BYTES) {
            abortLocked()
        } else {
            queuedBytes = bytes
            pending.add(Work(generation, bytes) {
                source.startReportWrite()
                source.writeMessageToReport(initialCounters)
            })
            wakeUp.trySend(Unit)
        }
        Unit
    }

    fun write(message: DebugReportMessage): Boolean = synchronized(lock) {
        if (!accepting) return false
        val bytes = message.serializedSize
        if (pending.size >= MAX_PENDING_MESSAGES || bytes > MAX_MESSAGE_BYTES || queuedBytes + bytes > MAX_QUEUED_BYTES) {
            // Dropping individual counter deltas would make a report misleading. Discard this report
            // as a whole instead; live statistics and the independent execution history continue.
            abortLocked()
            return false
        }
        queuedBytes += bytes
        pending.add(Work(generation, bytes) { source.writeMessageToReport(message) })
        wakeUp.trySend(Unit)
        true
    }

    fun finish(overview: DebugReportOverview) = synchronized(lock) {
        if (accepting) {
            accepting = false
            // One reserved lifecycle slot: a full queue must not prevent closing the report.
            pending.add(Work(generation) { source.stopReportWrite(overview) })
            wakeUp.trySend(Unit)
        }
        Unit
    }

    fun cancel() = synchronized(lock) { abortLocked() }

    private fun abortLocked() {
        accepting = false
        pending.clear()
        queuedBytes = 0
        pending.add(Work(generation) { source.cancelReportWrite() })
        wakeUp.trySend(Unit)
        Log.w(TAG, "Debug report disabled for this session: bounded write queue exhausted")
    }
}

private const val MAX_PENDING_MESSAGES = 64
private const val MAX_MESSAGE_BYTES = 128 * 1024
private const val MAX_QUEUED_BYTES = 512 * 1024
private const val TAG = "BoundedReportWriter"
