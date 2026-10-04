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
) {
    val clock = FakeClock()
    val workers = ManualWorkers()
    val dispatch = ManualDispatch()
    val ticker = ManualTicker()
    val transport = FakeTransport()
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
        )

    init {
        client.start()
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
