package com.buzbuz.smartautoclicker.core.smart.debugging.engine

import android.content.Context
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.smart.debugging.DebugReportMessage
import com.buzbuz.smartautoclicker.core.smart.debugging.CountersInitMessage
import com.buzbuz.smartautoclicker.core.smart.debugging.data.DebugReportLocalDataSource
import com.buzbuz.smartautoclicker.core.smart.debugging.data.mapping.toCountersInitProtobuf
import com.buzbuz.smartautoclicker.core.smart.debugging.domain.model.report.DebugReportOverview
import com.buzbuz.smartautoclicker.core.smart.debugging.engine.recorder.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DebugMemoryTests {
    @get:Rule val temporary = TemporaryFolder()
    private val event = ScreenEvent(Identifier(databaseId = 1), Identifier(databaseId = 2), "test", AND,
        emptyList(), emptyList(), true, 0, false, 0)
    private val overview = DebugReportOverview(2, 1.seconds, 1, 1.seconds, 1, 0, setOf("counter"))
    private val initial = emptyList<Counter>().toCountersInitProtobuf()
    private fun source() = DebugReportLocalDataSource(mockk<Context> {
        every { cacheDir } returns temporary.root
    })
    private fun engine(dispatcher: kotlinx.coroutines.CoroutineDispatcher, source: DebugReportLocalDataSource) =
        DebugEngine(dispatcher, source, DebugReportOverviewRecorder(), EventOccurrencesRecorder(),
            ScreenConditionOccurrenceRecorder(), CounterValuesRecorder(), EventStateRecorder())

    @Test fun liveDebuggingDoesNotQueueCallbacksBehindDiskWorker() = runTest {
        val source = mockk<DebugReportLocalDataSource>(relaxed = true)
        val engine = engine(StandardTestDispatcher(testScheduler), source)
        engine.onSessionStarted(mockk<Scenario>(relaxed = true), emptyList(), true, false)
        // Deliberately never run the IO dispatcher while producing 100,000 callbacks.
        repeat(20_000) {
            engine.onEventProcessingStarted(event)
            engine.onScreenConditionProcessingStarted()
            engine.onEventProcessingCompleted(event, true, emptyList())
            engine.onEventActionsExecuted(event, emptyList())
            engine.onCounterValueChanged("counter", 0.0, 1.0)
        }
        assertEquals(20_000, engine.lastEventProcessed.value!!.fulfilledCount)
        engine.onSessionEnded()
        assertNull(engine.lastEventProcessed.value)
        assertFalse(engine.isDebuggingSession.value)
        runCurrent()
        coVerify(exactly = 0) { source.startReportWrite() }
        coVerify(exactly = 0) { source.writeMessageToReport(any()) }
    }

    @Test fun overloadedReportIsDiscardedButLiveDebuggingAndNextSessionStillWork() = runTest {
        val source = source()
        val engine = engine(StandardTestDispatcher(testScheduler), source)
        engine.onSessionStarted(mockk<Scenario>(relaxed = true), emptyList(), true, true)
        repeat(10_000) {
            engine.onEventProcessingStarted(event)
            engine.onEventProcessingCompleted(event, true, emptyList())
            engine.onEventActionsExecuted(event, emptyList())
        }
        assertEquals(10_000, engine.lastEventProcessed.value!!.fulfilledCount)
        engine.onSessionEnded()
        runCurrent()
        assertFalse(source.isReportAvailable.value)
        assertTrue(File(temporary.root, "DebugReportStatus.json").readText().contains("queue_overload"))
        engine.onSessionStarted(mockk<Scenario>(relaxed = true), emptyList(), true, true)
        engine.onEventProcessingStarted(event)
        engine.onEventProcessingCompleted(event, true, emptyList())
        engine.onEventActionsExecuted(event, emptyList())
        engine.onSessionEnded()
        runCurrent()
        assertTrue(source.isReportAvailable.value)
        assertEquals(1, source.readMessages().size)
    }

    @Test fun reportQueueIsBoundedAndFinishHasReservedSpace() = runTest {
        val source = mockk<DebugReportLocalDataSource>(relaxed = true)
        val writer = BoundedReportWriter(StandardTestDispatcher(testScheduler), source)
        writer.start(initial)
        repeat(63) { assertTrue(writer.write(DebugReportMessage.getDefaultInstance())) }
        assertEquals(64, writer.pendingCount)
        writer.finish(overview)
        assertEquals(65, writer.pendingCount)
        assertFalse(writer.write(initial))
        runCurrent()
        assertEquals(0, writer.pendingCount)
        coVerify(exactly = 1) { source.stopReportWrite(overview) }
    }

    @Test fun floodingQueueNeverAccumulatesUnboundedJobs() = runTest {
        val source = mockk<DebugReportLocalDataSource>(relaxed = true)
        val writer = BoundedReportWriter(StandardTestDispatcher(testScheduler), source)
        writer.start(initial)
        repeat(100_000) { writer.write(initial) }
        assertEquals(1, writer.pendingCount)
        runCurrent()
        assertEquals(0, writer.pendingCount)
        coVerify(exactly = 1) { source.cancelReportWrite("queue_overload") }
        coVerify(exactly = 0) { source.writeMessageToReport(any()) }
    }

    @Test fun writeFailureDoesNotKillWorkerAndNextSessionCanStart() = runTest {
        val source = mockk<DebugReportLocalDataSource>(relaxed = true)
        coEvery { source.startReportWrite() } throws IOException("disk unavailable") andThen Unit
        val writer = BoundedReportWriter(StandardTestDispatcher(testScheduler), source)
        writer.start(initial)
        runCurrent()
        coVerify { source.cancelReportWrite("io_failure") }
        writer.start(initial)
        writer.finish(overview)
        runCurrent()
        coVerify(exactly = 1) { source.stopReportWrite(overview) }
    }

    @Test fun previousStreamCloseFailureIsContained() = runTest {
        val source = source()
        DebugReportLocalDataSource::class.java.getDeclaredField("messagesOutputStream").apply { isAccessible = true }
            .set(source, object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun close(): Unit = throw IOException("injected close failure")
            })
        source.startReportWrite()
        source.stopReportWrite(overview)
        assertTrue(source.isReportAvailable.value)
    }

    @Test fun failedReportWriteIsNotPublishedAsCompletedAndNextSessionRecovers() = runTest {
        val source = source()
        source.startReportWrite()
        val streamField = DebugReportLocalDataSource::class.java.getDeclaredField("messagesOutputStream")
            .apply { isAccessible = true }
        (streamField.get(source) as OutputStream).close()
        streamField.set(source, object : OutputStream() {
            override fun write(value: Int): Unit = throw IOException("injected write failure")
        })
        source.writeMessageToReport(initial)
        source.stopReportWrite(overview)
        assertFalse(source.isReportAvailable.value)
        assertTrue(File(temporary.root, "DebugReportStatus.json").readText().contains("io_failure"))
        source.startReportWrite()
        source.writeMessageToReport(initial)
        source.stopReportWrite(overview)
        assertTrue(source.isReportAvailable.value)
    }

    @Test fun aSingleLongEventCannotAccumulateUnlimitedCounterChanges() = runTest {
        val source = source()
        val engine = engine(StandardTestDispatcher(testScheduler), source)
        engine.onSessionStarted(mockk<Scenario>(relaxed = true), emptyList(), true, true)
        engine.onEventProcessingStarted(event)
        repeat(100_000) { engine.onCounterValueChanged("counter", it.toDouble(), it + 1.0) }
        engine.onEventProcessingCompleted(event, true, emptyList())
        assertEquals(1, engine.lastEventProcessed.value!!.fulfilledCount)
        engine.onSessionEnded()
        runCurrent()
        assertFalse(source.isReportAvailable.value)
    }

    @Test fun byteBudgetBoundsLargeMessagesBeforeCountLimit() = runTest {
        val source = mockk<DebugReportLocalDataSource>(relaxed = true)
        val writer = BoundedReportWriter(StandardTestDispatcher(testScheduler), source)
        val largeMessage = DebugReportMessage.newBuilder().setCountersInitMessage(
            CountersInitMessage.newBuilder().addInitialValues(
                CountersInitMessage.CounterInitialValues.newBuilder().setName("x".repeat(100 * 1024))
            )
        ).build()
        writer.start(initial)
        repeat(5) { assertTrue(writer.write(largeMessage)) }
        assertFalse(writer.write(largeMessage))
        assertEquals(1, writer.pendingCount)
        runCurrent()
        coVerify(exactly = 1) { source.cancelReportWrite("queue_overload") }
    }

    @Test fun sessionSwitchWhileDiskBlockedKeepsLatestSessionOrdered() = runTest {
        val source = mockk<DebugReportLocalDataSource>(relaxed = true)
        val blocked = CompletableDeferred<Unit>()
        coEvery { source.startReportWrite() } coAnswers { blocked.await() }
        val writer = BoundedReportWriter(StandardTestDispatcher(testScheduler), source)
        writer.start(initial)
        runCurrent()
        repeat(1000) { writer.start(initial); writer.finish(overview) }
        assertEquals(2, writer.pendingCount)
        blocked.complete(Unit)
        runCurrent()
        coVerify(exactly = 2) { source.startReportWrite() }
        coVerify(exactly = 1) { source.stopReportWrite(overview) }
        assertEquals(0, writer.pendingCount)
    }
}
