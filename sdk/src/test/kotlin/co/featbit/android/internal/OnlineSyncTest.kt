package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

internal class FakeTransport : SyncTransport {
    class Exchange(val request: Request, val streaming: Boolean, val listener: SyncListener) :
        SyncHandle {
        var canceled = false
        val sent = ArrayList<String>()

        override fun cancel() {
            canceled = true
        }

        override fun send(text: String): Boolean {
            sent.add(text)
            return true
        }

        fun data(user: String = "A", value: String = "one", time: Long = 1, kind: String = "full") {
            val body = envelope(user, value, time, kind)
            if (streaming) listener.message(body) else listener.response(200, body, null)
        }
    }

    val requests = ArrayList<Exchange>()
    var closed = false
    var duringStart: (() -> Unit)? = null

    override fun start(request: Request, streaming: Boolean, listener: SyncListener): SyncHandle {
        val exchange = Exchange(request, streaming, listener)
        requests.add(exchange)
        duringStart?.invoke()
        if (streaming) listener.opened()
        return exchange
    }

    override fun close() {
        closed = true
    }
}

internal fun envelope(
    user: String = "A",
    value: String = "one",
    time: Long = 1,
    kind: String = "full",
): String =
    """{"messageType":"data-sync","data":{"eventType":"$kind","userKeyId":"$user","featureFlags":[{"id":"flag","variation":"$value","variationType":"string","timestamp":$time}]}}"""

internal class OnlineHarness(
    mode: SyncMode = SyncMode.STREAMING,
    fallback: Boolean = false,
    background: Boolean = false,
    grace: Long = 0,
    bootstrap: Boolean = false,
    initial: PlatformState = PlatformState(foreground = true),
    cached: Map<User, List<FlagRecord>> = emptyMap(),
    fullSyncInterval: Long = 86_400_000,
) {
    val clock = FakeClock()
    val workers = ManualWorkers()
    val dispatch = ManualDispatch()
    val ticker = ManualTicker()
    val transport = FakeTransport()
    val disk =
        DiskCoordinator(MemoryStorage(), workers, clock).also { disk ->
            cached.forEach { (user, records) ->
                check(
                    disk.commit(
                        contextKey(user),
                        CachedSnapshot(
                            records.associateBy { it.key },
                            records.maxOfOrNull { it.timestamp } ?: 0,
                        ),
                        {},
                    )
                )
            }
            workers.drain()
        }
    val options =
        ClientOptions.builder()
            .user(user("A"))
            .sdkKey("test-key")
            .streamingUrl("ws://localhost/prefix")
            .pollingUrl("http://localhost/prefix")
            .mode(mode)
            .pollingFallback(fallback)
            .backgroundPolling(background)
            .flagGraceMillis(grace)
            .disableEvents(true)
            .cacheEnabled(false)
            .requestTimeoutMillis(5_000)
            .fullSyncIntervalMillis(fullSyncInterval)
            .synchronizationHeader("X-Gateway", "sync-only")
            .eventHeader("X-Event", "event-only")
            .apply { if (bootstrap) bootstrap(listOf(flag("flag", "bootstrap"))) }
            .build()
            .value!!
    val client =
        LocalClient(
            options,
            options.initialUser!!,
            null,
            dispatch,
            clock,
            workers,
            ticker,
            Diagnostics(options, clock, ManualWorkers()),
            transportFactory = { transport },
            random = { 0.5 },
            initialPlatformState = initial,
            cache = if (cached.isEmpty()) null else disk,
        )

    init {
        client.start()
        workers.drain()
        ticker.action()
        workers.drain()
    }

    val last: FakeTransport.Exchange
        get() = transport.requests.last()

    fun advance(time: Long) {
        clock.now += time
        ticker.action()
        workers.drain()
        dispatch.drain()
    }

    fun close() {
        client.close()
        workers.drain()
        ticker.action()
        assertTrue(transport.closed)
    }
}

public class OnlineSyncTest {
    @Test
    public fun configuredFullSyncIntervalIsUsedByBothModes() {
        for (mode in listOf(SyncMode.STREAMING, SyncMode.POLLING)) {
            val h = OnlineHarness(mode, fullSyncInterval = 60_000)
            h.last.data(time = 10)
            h.advance(30_000)
            if (mode == SyncMode.STREAMING) h.last.listener.message("pong")
            else h.last.listener.response(304, "", null)
            h.advance(29_999)
            val previous = h.last
            h.advance(1)
            assertNotSame(previous, h.last)
            assertFullRequest(h.last)
            h.last.data(time = 1)
            h.close()
        }
    }

    private fun assertFullRequest(exchange: FakeTransport.Exchange) {
        if (exchange.streaming) assertTrue(exchange.sent.single().contains("\"timestamp\":0"))
        else assertEquals("0", exchange.request.url.queryParameter("timestamp"))
    }

    @Test
    public fun startupAndIdentifyReconcileCachedRecordsAndArchivesInBothModes() {
        for (mode in listOf(SyncMode.STREAMING, SyncMode.POLLING)) {
            val h =
                OnlineHarness(
                    mode,
                    cached =
                        mapOf(
                            user("A") to
                                listOf(
                                    record("flag", "cached", 100),
                                    record("archived", "old", 101, true),
                                ),
                            user("B") to listOf(record("flag", "cached-b", 200)),
                        ),
                )
            assertEquals("cached", h.client.stringVariation("flag", "fallback"))
            assertFullRequest(h.last)
            h.last.listener.failed(503)
            h.advance(500)
            assertFullRequest(h.last)
            assertEquals("cached", h.client.stringVariation("flag", "fallback"))
            val empty =
                """{"messageType":"data-sync","data":{"eventType":"full","userKeyId":"A","featureFlags":[]}}"""
            h.last.listener.message(empty)
            h.workers.drain()
            assertEquals("fallback", h.client.stringVariation("flag", "fallback"))
            var saved: CachedSnapshot? = null
            h.disk.load(contextKey(user("A")), h.disk.epoch(), {}) { saved = it }
            h.workers.drain()
            assertTrue(saved!!.records.isEmpty())
            assertEquals(0L, saved!!.cursor)
            val old = h.last
            h.client.identify(user("B"), 1000)
            h.advance(0)
            assertEquals("cached-b", h.client.stringVariation("flag", "fallback"))
            assertFullRequest(h.last)
            old.data(value = "stale")
            h.last.data(user = "B", value = "restored", time = 1)
            assertEquals("restored", h.client.stringVariation("flag", "fallback"))
            h.close()
        }
    }

    @Test
    public fun periodicFullRetriesUntilFullCommitAndDoesNotClearReadableValues() {
        for (mode in listOf(SyncMode.STREAMING, SyncMode.POLLING)) {
            val h = OnlineHarness(mode)
            h.last.data(time = 100)
            // Keep a stream alive up to the refresh boundary; polling can wait until its next tick.
            repeat(2879) {
                h.clock.now += 30_000
                if (mode == SyncMode.STREAMING) h.last.listener.message("pong")
            }
            h.clock.now += 29_999
            if (mode == SyncMode.STREAMING) h.last.data(value = "patch", time = 101, kind = "patch")
            if (mode == SyncMode.STREAMING) {
                h.advance(0)
                assertEquals(1, h.transport.requests.size)
                assertEquals("patch", h.client.stringVariation("flag", "fallback"))
            }
            h.advance(1)
            assertFullRequest(h.last)
            val retained = h.client.stringVariation("flag", "fallback")
            h.last.listener.response(304, "", null)
            h.advance(500)
            assertFullRequest(h.last)
            assertEquals(retained, h.client.stringVariation("flag", "fallback"))
            val awaitingFull = h.last
            h.last.data(value = "incomplete", time = 102, kind = "patch")
            h.advance(1000)
            if (mode == SyncMode.STREAMING) {
                assertSame(awaitingFull, h.last)
                assertFalse(awaitingFull.canceled)
            } else assertNotSame(awaitingFull, h.last)
            assertFullRequest(h.last)
            assertEquals(retained, h.client.stringVariation("flag", "fallback"))
            h.last.data(value = "full", time = 1)
            assertEquals("full", h.client.stringVariation("flag", "fallback"))
            if (mode == SyncMode.STREAMING) h.last.listener.ended(1000)
            h.advance(if (mode == SyncMode.STREAMING) 500 else 30_000)
            if (mode == SyncMode.STREAMING)
                assertTrue(h.last.sent.single().contains("\"timestamp\":1"))
            else assertEquals("1", h.last.request.url.queryParameter("timestamp"))
            h.close()
        }
    }

    @Test
    public fun fullRefreshPreservesRetryAfterAndExponentialBackoff() {
        for (mode in listOf(SyncMode.STREAMING, SyncMode.POLLING)) {
            for (retryAfter in listOf<String?>("120", null)) {
                val h = OnlineHarness(mode)
                h.last.data(time = 100)
                if (mode == SyncMode.STREAMING) h.last.listener.ended(1000)
                h.clock.now = 86_400_000 - 100
                h.advance(0)
                h.last.listener.failed(429, retryAfter)
                val count = h.transport.requests.size
                // Streaming already has one failed reconnect, so its second backoff is 1000 ms.
                val delay =
                    if (retryAfter != null) 120_000L
                    else if (mode == SyncMode.STREAMING) 1000L else 500L
                h.advance(100)
                assertEquals(count, h.transport.requests.size)
                h.advance(delay - 101)
                assertEquals(count, h.transport.requests.size)
                h.advance(1)
                assertEquals(count + 1, h.transport.requests.size)
                assertFullRequest(h.last)
                h.last.data(time = 1)
                h.close()
            }
        }
    }

    @Test
    public fun streamingBroadcastBeforeFullDoesNotInterruptStartupOrIdentify() {
        val h = OnlineHarness()
        val ready = h.client.awaitReady(1000)
        val startup = h.last
        startup.data(value = "broadcast", time = 10, kind = "patch")
        assertNull(ready.getResult())
        assertEquals("fallback", h.client.stringVariation("flag", "fallback"))
        h.workers.drain()
        assertFalse(startup.canceled)
        startup.data(value = "full", time = 11)
        assertEquals(OutcomeCode.SUCCESS, ready.getResult()!!.code)
        val identify = h.client.identify(user("B"), 1000)
        h.workers.drain()
        val current = h.last
        current.data(user = "B", value = "broadcast", time = 20, kind = "patch")
        assertNull(identify.getResult())
        startup.data(value = "stale", time = 100)
        current.data(user = "B", value = "full-b", time = 21)
        assertEquals(OutcomeCode.SUCCESS, identify.getResult()!!.code)
        assertEquals("full-b", h.client.stringVariation("flag", "fallback"))
        h.workers.drain()
        assertFalse(current.canceled)
        h.close()
    }

    @Test
    public fun streamingBroadcastsCannotExtendFullRequestDeadline() {
        val h = OnlineHarness()
        val stream = h.last
        repeat(4) {
            h.advance(1000)
            stream.data(kind = "patch")
            assertSame(stream, h.last)
            assertFalse(stream.canceled)
        }
        h.advance(1000)
        assertTrue(stream.canceled)
        stream.data(value = "too-late")
        assertEquals("fallback", h.client.stringVariation("flag", "fallback"))
        h.advance(500)
        assertNotSame(stream, h.last)
        assertFullRequest(h.last)
        h.last.data(value = "recovered")
        assertEquals("recovered", h.client.stringVariation("flag", "fallback"))
        h.close()
    }

    @Test
    public fun incrementalPollInFlightAtDeadlineCannotAcknowledgeFullRefresh() {
        val h = OnlineHarness(SyncMode.POLLING)
        h.last.data(time = 10)
        h.clock.now = 86_400_000 - 1000
        h.advance(0)
        assertEquals("10", h.last.request.url.queryParameter("timestamp"))
        val incremental = h.last
        h.advance(1000)
        assertSame(incremental, h.last)
        incremental.listener.response(304, "", null)
        h.advance(30_000)
        assertFullRequest(h.last)
        h.last.data(time = 2)
        h.advance(30_000)
        assertEquals("2", h.last.request.url.queryParameter("timestamp"))
        h.close()
    }

    @Test
    public fun overdueRefreshWaitsUntilOnlineAndClosedClientNeverRefreshes() {
        val h = OnlineHarness()
        h.last.data(time = 10)
        h.client.setOffline(1000)
        h.advance(0)
        val count = h.transport.requests.size
        h.advance(86_400_000)
        assertEquals(count, h.transport.requests.size)
        assertEquals("one", h.client.stringVariation("flag", "fallback"))
        h.client.setOnline(1000)
        h.advance(0)
        assertFullRequest(h.last)
        h.last.data()
        h.close()
        val closedCount = h.transport.requests.size
        h.advance(86_400_000)
        assertEquals(closedCount, h.transport.requests.size)
    }

    @Test
    public fun backgroundSuspendsRefreshAndForegroundReconcilesImmediately() {
        val h = OnlineHarness()
        h.last.data(time = 10)
        h.client.lifecycle(false, true)
        h.advance(86_400_000)
        assertEquals(1, h.transport.requests.size)
        h.client.lifecycle(true, true)
        h.advance(0)
        assertFullRequest(h.last)
        assertEquals("one", h.client.stringVariation("flag", "fallback"))
        h.close()
    }

    @Test
    public fun streamingHandshakeDoesNotConfirmAndEqualTimestampPatchesRemainOrdered() {
        val h = OnlineHarness()
        val wait = h.client.awaitReady(1000)
        assertNull(wait.getResult())
        assertEquals(1, h.last.sent.size)
        assertTrue(h.last.sent.single().contains("\"timestamp\":0"))
        h.last.data(time = 9007199254740993)
        assertEquals(ReadyResult.REMOTE_CONFIRMED, wait.getResult()!!.value)
        h.last.data(value = "two", time = 9007199254740993, kind = "patch")
        h.last.data(value = "old", time = 2, kind = "patch")
        assertEquals("two", h.client.stringVariation("flag", "fallback"))
        h.last.listener.ended(1000)
        h.advance(500)
        assertEquals(2, h.transport.requests.size)
        assertTrue(h.last.sent.single().contains("9007199254740993"))
        h.close()
    }

    @Test
    public fun identifySameKeyAndABARacesRejectOldDataErrorsAndTimers() {
        val h = OnlineHarness()
        val a = h.last
        a.data()
        val bWait = h.client.identify(user("B"), 1000)
        h.workers.drain()
        val b = h.last
        val aWait = h.client.identify(user("A", "changed"), 1000)
        h.workers.drain()
        assertEquals(OutcomeCode.SUPERSEDED, bWait.getResult()!!.code)
        a.data(value = "stale")
        b.data("B")
        a.listener.ended(4003)
        assertEquals("missing", h.client.stringVariation("flag", "missing"))
        assertNull(aWait.getResult())
        h.last.data(value = "current")
        assertEquals("current", h.client.stringVariation("flag", "missing"))
        assertEquals(OutcomeCode.SUCCESS, aWait.getResult()!!.code)
        val previous = h.last
        h.client.identify(user("A", "again"), 1000)
        h.workers.drain()
        previous.data(value = "stale-same-key")
        assertFalse(h.client.getConnectionInformation().remoteConfirmed)
        h.close()
    }

    @Test
    public fun readinessTimeoutDoesNotStopRecoveryAndTerminalRejectionCannotBeReset() {
        val h = OnlineHarness()
        val wait = h.client.awaitReady(1)
        h.advance(1)
        assertEquals(OutcomeCode.TIMED_OUT, wait.getResult()!!.code)
        repeat(12) {
            h.last.listener.failed(null)
            h.advance(60_000)
        }
        h.last.data()
        assertEquals(SyncStatus.READY, h.client.getConnectionInformation().status)
        h.last.listener.ended(4003)
        val count = h.transport.requests.size
        h.client.setOffline(100)
        h.workers.drain()
        h.client.setOnline(100)
        h.client.identify(user("B"), 100)
        h.advance(1_000_000)
        assertEquals(count, h.transport.requests.size)
        assertEquals(SyncStatus.TERMINAL, h.client.getConnectionInformation().status)
        h.close()
    }

    @Test
    public fun pollingSerializesUsesCursorAndMatching304AndScopedHeaders() {
        val h = OnlineHarness(SyncMode.POLLING)
        assertEquals(
            "/prefix/api/public/sdk/client/latest-all?timestamp=0",
            h.last.request.url.encodedPath + "?" + h.last.request.url.encodedQuery,
        )
        assertEquals("test-key", h.last.request.header("Authorization"))
        assertEquals("sync-only", h.last.request.header("X-Gateway"))
        assertNull(h.last.request.header("X-Event"))
        h.advance(1000)
        assertEquals(1, h.transport.requests.size)
        h.last.listener.response(304, "", null)
        assertFalse(h.client.getConnectionInformation().remoteConfirmed)
        h.advance(500)
        h.last.data(time = 12)
        h.advance(29_999)
        assertEquals(2, h.transport.requests.size)
        h.advance(1)
        assertEquals("12", h.last.request.url.queryParameter("timestamp"))
        h.last.listener.response(304, "", null)
        assertEquals(SyncStatus.READY, h.client.getConnectionInformation().status)
        h.close()
    }

    @Test
    public fun retryAfterAndMalformedHttpResponseRetainValuesAndContinue() {
        val h = OnlineHarness(SyncMode.POLLING, bootstrap = true)
        h.last.listener.response(429, "", "120")
        h.advance(119_999)
        assertEquals(1, h.transport.requests.size)
        h.advance(1)
        assertEquals(2, h.transport.requests.size)
        h.last.listener.response(200, "malformed", null)
        assertEquals("bootstrap", h.client.stringVariation("flag", ""))
        h.advance(1000)
        h.last.data()
        assertEquals(SyncStatus.READY, h.client.getConnectionInformation().status)
        h.close()
    }

    @Test
    public fun fallbackCandidateFencesPollingAndCommitsOnlyOnValidData() {
        val h = OnlineHarness(fallback = true)
        h.last.listener.failed(null)
        h.advance(30_000)
        assertFalse(h.last.streaming)
        assertEquals(SyncMode.POLLING, h.client.getConnectionInformation().effectiveMode)
        h.last.data(value = "poll", time = 10)
        h.advance(30_000)
        val outstandingPoll = h.last
        h.advance(30_000)
        h.advance(1)
        assertTrue(h.last.streaming)
        val candidate = h.last
        assertTrue(outstandingPoll.canceled)
        assertEquals(RecoveryStatus.PROBING, h.client.getConnectionInformation().recovery)
        outstandingPoll.data(value = "late", time = 100)
        assertEquals("poll", h.client.stringVariation("flag", ""))
        h.advance(50)
        assertSame(candidate, h.last)
        candidate.data(value = "stream", time = 2)
        assertEquals("stream", h.client.stringVariation("flag", ""))
        assertEquals(SyncMode.STREAMING, h.client.getConnectionInformation().effectiveMode)
        h.close()
    }

    @Test
    public fun candidateDeadlineCannotExtendAndFailuresResumePollingWithIndependentError() {
        val h = OnlineHarness(fallback = true)
        h.last.listener.failed(null)
        h.advance(30_000)
        h.last.data(value = "poll")
        h.advance(60_000)
        val candidate = h.last
        assertTrue(candidate.streaming)
        h.advance(15_001)
        candidate.data(value = "late")
        h.advance(1)
        assertFalse(h.last.streaming)
        assertEquals("poll", h.client.stringVariation("flag", ""))
        assertNotNull(h.client.getConnectionInformation().candidateFailure)
        assertNull(h.client.getConnectionInformation().failure)
        h.close()
    }

    @Test
    public fun backgroundPollingAndGraceRestoreStreamingWithoutOverlap() {
        val h = OnlineHarness(background = true, grace = 1000)
        val stream = h.last
        stream.data()
        h.client.lifecycle(false, true)
        h.workers.drain()
        assertTrue(stream.canceled)
        assertFalse(h.last.streaming)
        val poll = h.last
        poll.data()
        h.client.lifecycle(true, true)
        h.workers.drain()
        assertTrue(h.last.streaming)
        poll.data(value = "old")
        assertEquals("one", h.client.stringVariation("flag", ""))
        h.close()
    }

    @Test
    public fun offlineAndCloseRevokeQueuedDispatchAndLateHandleAttachment() {
        val h = OnlineHarness()
        h.transport.duringStart = { h.client.setOffline(1000) }
        h.client.identify(user("B"), 1000)
        h.workers.drain()
        assertTrue(h.last.canceled)
        h.last.data("B", "late")
        assertFalse(h.client.getConnectionInformation().remoteConfirmed)
        h.transport.duringStart = null
        h.client.setOnline(1000)
        val count = h.transport.requests.size
        h.close()
        assertEquals(count, h.transport.requests.size)
        h.last.listener.failed(401)
        assertEquals(SyncStatus.CLOSED, h.client.getConnectionInformation().status)
    }

    @Test
    public fun applicationPingAndInactivityUseElapsedClock() {
        val h = OnlineHarness()
        h.last.data()
        val socket = h.last
        h.advance(18_000)
        assertEquals(SyncProtocol.PING, socket.sent.last())
        socket.listener.message(SyncProtocol.PING)
        h.clock.utc = -1000
        h.advance(35_999)
        assertFalse(socket.canceled)
        h.advance(1)
        assertTrue(socket.canceled)
        h.close()
    }

    @Test
    public fun candidateRejectionIsTerminalButStaleCandidateRejectionIsIgnored() {
        val h = OnlineHarness(fallback = true)
        h.last.listener.failed(null)
        h.advance(30_000)
        h.last.data()
        h.advance(60_000)
        val candidate = h.last
        h.client.lifecycle(false, true)
        h.workers.drain()
        candidate.listener.ended(4003)
        assertNotEquals(SyncStatus.TERMINAL, h.client.getConnectionInformation().status)
        h.client.lifecycle(true, true)
        h.workers.drain()
        h.last.data()
        h.advance(120_000)
        assertTrue(h.last.streaming)
        h.last.listener.ended(4003)
        assertEquals(SyncStatus.TERMINAL, h.client.getConnectionInformation().status)
        h.close()
    }

    @Test
    public fun explicitPollingNeverProbesAndNetworkPauseRejectsOldErrors() {
        val h = OnlineHarness(SyncMode.POLLING)
        val old = h.last
        h.client.lifecycle(true, false)
        h.workers.drain()
        old.listener.failed(403)
        h.advance(1_000_000)
        assertEquals(1, h.transport.requests.size)
        h.client.lifecycle(true, true)
        h.workers.drain()
        h.last.data()
        h.advance(1_000_000)
        assertTrue(h.transport.requests.none { it.streaming })
        h.close()
    }
}
