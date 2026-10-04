package co.featbit.android.internal

import co.featbit.android.api.*
import org.junit.Assert.*
import org.junit.Test

public class OnlineCacheTest {
    @Test
    public fun lateCacheCannotRetroactivelyAuthorize304OrReplaceRemoteCommit() {
        val clock = FakeClock()
        val diskWorker = ManualWorkers()
        val store = MemoryStorage()
        val disk = DiskCoordinator(store, diskWorker, clock)
        disk.commit(
            contextKey(user("A")),
            CachedSnapshot(mapOf("flag" to record("flag", "cached", 50)), 50),
        ) {}
        diskWorker.drain()
        val options =
            ClientOptions.builder()
                .user(user("A"))
                .sdkKey("key")
                .mode(SyncMode.POLLING)
                .pollingUrl("http://localhost")
                .disableEvents(true)
                .build()
                .value!!
        val worker = ManualWorkers()
        val ticker = ManualTicker()
        val transport = FakeTransport()
        val client =
            LocalClient(
                options,
                user("A"),
                null,
                ManualDispatch(),
                clock,
                worker,
                ticker,
                Diagnostics(options, clock, ManualWorkers()),
                cache = disk,
                transportFactory = { transport },
                random = { 0.5 },
            )
        client.start()
        worker.drain()
        val request = transport.requests.single()
        assertEquals("0", request.request.url.queryParameter("timestamp"))
        diskWorker.drain()
        assertEquals("cached", client.stringVariation("flag", ""))
        request.listener.response(304, "", null)
        assertFalse(client.getConnectionInformation().remoteConfirmed)
        clock.now += 500
        ticker.action()
        worker.drain()
        assertEquals("0", transport.requests.last().request.url.queryParameter("timestamp"))
        transport.requests.last().data(value = "remote", time = 1)
        diskWorker.drain()
        assertEquals("remote", client.stringVariation("flag", ""))
        client.close()
        worker.drain()
        ticker.action()
    }

    @Test
    public fun cacheArrivingBeforeNewIdentifyRequestProvidesPairedCursorAnd304Confirmation() {
        val clock = FakeClock()
        val diskWorker = ManualWorkers()
        val disk = DiskCoordinator(MemoryStorage(), diskWorker, clock)
        disk.commit(contextKey(user("B")), CachedSnapshot(emptyMap(), 0)) {}
        diskWorker.drain()
        val worker = ManualWorkers()
        val ticker = ManualTicker()
        val transport = FakeTransport()
        val options =
            ClientOptions.builder()
                .user(user("A"))
                .sdkKey("key")
                .mode(SyncMode.POLLING)
                .pollingUrl("http://localhost")
                .disableEvents(true)
                .bootstrap(listOf(flag("flag", "bootstrap")))
                .build()
                .value!!
        val client =
            LocalClient(
                options,
                user("A"),
                null,
                ManualDispatch(),
                clock,
                worker,
                ticker,
                Diagnostics(options, clock, ManualWorkers()),
                cache = disk,
                transportFactory = { transport },
                random = { 0.5 },
            )
        client.start()
        worker.drain()
        client.setOffline(1000)
        worker.drain()
        ticker.action()
        client.identify(user("B"), 1000)
        diskWorker.drain()
        assertEquals("fallback", client.stringVariation("flag", "fallback"))
        client.setOnline(1000)
        worker.drain()
        val wait = client.awaitReady(1000)
        assertNull(wait.getResult())
        transport.requests.last().listener.response(304, "", null)
        assertEquals(ReadyResult.REMOTE_CONFIRMED, wait.getResult()!!.value)
        client.close()
        worker.drain()
        ticker.action()
    }

    @Test
    public fun lateCacheBaselineInvalidatesPatchInsteadOfMergingDifferentRequestData() {
        val clock = FakeClock()
        val diskWorker = ManualWorkers()
        val disk = DiskCoordinator(MemoryStorage(), diskWorker, clock)
        disk.commit(
            contextKey(user("A")),
            CachedSnapshot(mapOf("cached-only" to record("cached-only", "old", 10)), 10),
        ) {}
        diskWorker.drain()
        val worker = ManualWorkers()
        val ticker = ManualTicker()
        val transport = FakeTransport()
        val options =
            ClientOptions.builder()
                .user(user("A"))
                .sdkKey("key")
                .streamingUrl("ws://localhost")
                .disableEvents(true)
                .build()
                .value!!
        val client =
            LocalClient(
                options,
                user("A"),
                null,
                ManualDispatch(),
                clock,
                worker,
                ticker,
                Diagnostics(options, clock, ManualWorkers()),
                cache = disk,
                transportFactory = { transport },
            )
        client.start()
        worker.drain()
        diskWorker.drain()
        transport.requests.single().data(value = "patch", kind = "patch")
        assertFalse(client.getConnectionInformation().remoteConfirmed)
        assertEquals("missing", client.stringVariation("flag", "missing"))
        assertEquals("old", client.stringVariation("cached-only", ""))
        client.close()
        worker.drain()
        ticker.action()
    }
}
