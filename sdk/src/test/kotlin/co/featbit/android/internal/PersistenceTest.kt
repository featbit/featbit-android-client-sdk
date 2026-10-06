package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import org.junit.Assert.*
import org.junit.Test

internal class MemoryStorage : AtomicStorage {
    var bytes: ByteArray? = null
    var failRead = false
    var failWrite = false
    var reads = 0
    var writes = 0
    var beforeRead: () -> Unit = {}
    var beforeWrite: () -> Unit = {}

    override fun read(): ByteArray? {
        reads++
        beforeRead()
        check(!failRead)
        return bytes?.clone()
    }

    override fun replace(bytes: ByteArray) {
        beforeWrite()
        check(!failWrite)
        this.bytes = bytes.clone()
        writes++
    }
}

private class DiskHarness(
    val storage: MemoryStorage = MemoryStorage(),
    val clock: FakeClock = FakeClock(),
) {
    val worker = ManualWorkers()
    val disk = DiskCoordinator(storage, worker, clock)
    val reports = ArrayList<String>()

    fun save(u: User, vararg records: FlagRecord) {
        check(
            disk.commit(
                contextKey(u),
                CachedSnapshot(
                    frozen(records.associateBy { it.key }),
                    records.maxOfOrNull { it.timestamp } ?: 0,
                ),
                reports::add,
            )
        )
    }

    fun load(u: User): CachedSnapshot? {
        var result: CachedSnapshot? = null
        check(disk.load(contextKey(u), disk.epoch(), reports::add) { result = it })
        worker.drain()
        return result
    }
}

private class CachedClient(
    val h: DiskHarness,
    val source: ControlledSource = ControlledSource(),
    bootstrap: List<BootstrapFlag>? = null,
    offline: Boolean = false,
) {
    val worker = ManualWorkers()
    val dispatch = ManualDispatch()
    val ticker = ManualTicker()
    val options =
        ClientOptions.builder()
            .user(user("A"))
            .source(source)
            .disableEvents(true)
            .offline(offline)
            .apply { if (bootstrap != null) bootstrap(bootstrap) }
            .build()
            .value!!
    val client =
        LocalClient(
            options,
            user("A"),
            source.capabilities(),
            dispatch,
            h.clock,
            worker,
            ticker,
            Diagnostics(options, h.clock, ManualWorkers()),
            cache = h.disk,
        )

    init {
        client.start()
        worker.drain()
    }
}

public class PersistenceTest {
    @Test
    public fun completeContextAndNamespaceIsolation() {
        val a =
            User.builder("A")
                .name("n")
                .attribute("x", AttributeValue.nullValue())
                .attribute("y", AttributeValue.text("").value!!)
                .build()
                .value!!
        val reordered =
            User.builder("A")
                .name("n")
                .attribute("y", AttributeValue.text("").value!!)
                .attribute("x", AttributeValue.nullValue())
                .build()
                .value!!
        assertEquals(contextKey(a), contextKey(reordered))
        val alternatives =
            listOf(
                user("A"),
                user("B"),
                user("A", ""),
                User.builder("A")
                    .name("n")
                    .attribute("x", AttributeValue.omittedValue())
                    .attribute("y", AttributeValue.text("").value!!)
                    .build()
                    .value!!,
                User.builder("A")
                    .name("n")
                    .attribute("featbit.sdk.version", AttributeValue.text("1").value!!)
                    .build()
                    .value!!,
            )
        alternatives.forEach { assertNotEquals(contextKey(a), contextKey(it)) }
        fun options(
            key: String = "secret",
            url: String = "wss://host/prefix",
            cache: Boolean = true,
        ) =
            ClientOptions.builder()
                .user(a)
                .offline(true)
                .sdkKey(key)
                .streamingUrl(url)
                .cacheEnabled(cache)
                .build()
                .value!!
        val builtIn = cacheNamespace(options(), null)!!
        assertFalse(builtIn.contains("secret"))
        assertNotEquals(builtIn, cacheNamespace(options(key = "other"), null))
        assertNotEquals(builtIn, cacheNamespace(options(url = "wss://host/other"), null))
        assertEquals(builtIn, cacheNamespace(options(url = "wss://HOST/prefix"), null))
        assertNull(cacheNamespace(options(cache = false), null))
        assertNull(cacheNamespace(options(), SourceCapabilities(Provenance.LOCAL, false, "local")))
        assertNull(cacheNamespace(options(), SourceCapabilities(Provenance.REMOTE, true)))
        assertNotEquals(
            builtIn,
            cacheNamespace(options(), SourceCapabilities(Provenance.REMOTE, true, "custom-v1")),
        )
        assertNull(
            cacheNamespace(ClientOptions.builder().user(a).offline(true).build().value!!, null)
        )
        val custom =
            ClientOptions.builder()
                .user(a)
                .source(ControlledSource())
                .disableEvents(true)
                .sdkKey("environment")
                .build()
                .value!!
        assertNotNull(
            cacheNamespace(
                custom,
                SourceCapabilities(Provenance.REMOTE, true, "https://deployment/custom-v1"),
            )
        )
    }

    @Test
    public fun firstSnapshotRestartMetadataEmptyAndLowerCursorFull() {
        val h = DiskHarness()
        val rich =
            FlagRecord.builder("flag", "x", "future", 9)
                .archived(true)
                .reason("reason")
                .variationOptions(listOf(VariationOption("id", "x")))
                .build()
                .value!!
        h.save(user("A"), rich)
        h.worker.drain()
        assertEquals(1, h.storage.writes)
        val restarted = DiskHarness(h.storage, h.clock)
        val loaded = restarted.load(user("A"))!!
        assertEquals(9L, loaded.cursor)
        assertEquals("future", loaded.records.getValue("flag").variationType)
        assertEquals("reason", loaded.records.getValue("flag").reason)
        assertTrue(loaded.records.getValue("flag").archived)
        assertEquals(
            listOf(VariationOption("id", "x")),
            loaded.records.getValue("flag").variationOptions,
        )
        restarted.save(user("A"), record("flag", "lower", 1))
        restarted.worker.drain()
        assertEquals(1L, DiskHarness(h.storage, h.clock).load(user("A"))!!.cursor)
        restarted.save(user("A"))
        restarted.worker.drain()
        assertTrue(DiskHarness(h.storage, h.clock).load(user("A"))!!.records.isEmpty())
    }

    @Test
    public fun logicalCommitOrderCoalescesAcrossClientsIncludingEqualTimestamps() {
        val h = DiskHarness()
        val a = CachedClient(h)
        val b = CachedClient(h)
        a.source.sinks.last().full(record("f", "A", 10))
        b.source.sinks.last().full(record("f", "B", 10))
        h.worker.drain()
        assertEquals(
            "B",
            DiskHarness(h.storage, h.clock).load(user("A"))!!.records.getValue("f").variation,
        )
        // The older client is allowed to commit later, even with a lower full cursor.
        a.source.sinks.last().full(record("f", "A-later", 1))
        h.worker.drain()
        assertEquals(
            "A-later",
            DiskHarness(h.storage, h.clock).load(user("A"))!!.records.getValue("f").variation,
        )
        assertEquals("B", b.client.stringVariation("f", "fallback"))
    }

    @Test
    public fun clearBarrierCannotBeBypassedByOldDrainMarkersOrPostClearWrites() {
        val h = DiskHarness()
        h.save(user("A"), record("f", "old"))
        var cleared = false
        assertTrue(
            h.disk.clear(null, h.reports::add) { ok ->
                cleared = ok
                assertTrue(DiskHarness(h.storage, h.clock).load(user("A")) == null)
            }
        )
        h.save(user("A"), record("f", "new"))
        h.worker.drain()
        assertTrue(cleared)
        assertEquals(
            "new",
            DiskHarness(h.storage, h.clock).load(user("A"))!!.records.getValue("f").variation,
        )
    }

    @Test
    public fun clearDuringPhysicalWriteErasesAdmittedOldWork() {
        val h = DiskHarness()
        h.storage.beforeWrite = {
            h.storage.beforeWrite = {}
            assertTrue(h.disk.clear(null, h.reports::add) { assertTrue(it) })
        }
        h.save(user("A"), record("f", "old"))
        h.worker.drain()
        assertNull(DiskHarness(h.storage, h.clock).load(user("A")))
    }

    @Test
    public fun publishedMemorySurvivesClearButUnpublishedLoadsAreFenced() {
        val h = DiskHarness()
        h.save(user("A"), record("f", "cached"))
        h.worker.drain()
        val first = CachedClient(h)
        h.worker.drain()
        assertEquals("cached", first.client.stringVariation("f", "fallback"))
        val second = CachedClient(h)
        val op = first.client.clearCache(CacheScope.NAMESPACE, 100)
        h.worker.drain()
        assertTrue(op.getResult()!!.isSuccess)
        assertEquals("cached", first.client.stringVariation("f", "fallback"))
        assertEquals("fallback", second.client.stringVariation("f", "fallback"))
        assertNull(DiskHarness(h.storage, h.clock).load(user("A")))
    }

    @Test
    public fun finalEpochCheckAndPublicationAreIndivisibleWithClear() {
        val h = DiskHarness()
        h.save(user("A"), record("f", "cached"))
        h.worker.drain()
        val epoch = h.disk.epoch()
        var publish: (() -> Unit)? = null
        var visible = false
        h.disk.load(contextKey(user("A")), epoch, h.reports::add) { snapshot ->
            publish = { h.disk.withEpoch(epoch) { visible = snapshot != null } }
        }
        h.worker.drain()
        h.disk.clear(null, h.reports::add) {}
        publish!!()
        assertFalse(visible)
        h.worker.drain()
    }

    @Test
    public fun bootstrapCreationIdentifyCacheEmptyAndMissPrecedence() {
        val h = DiskHarness()
        h.save(user("A"), record("f", "cached"))
        h.save(user("B"))
        h.worker.drain()
        val c = CachedClient(h, bootstrap = listOf(flag("f", "bootstrap")), offline = true)
        assertEquals("bootstrap", c.client.stringVariation("f", "fallback"))
        c.client.identify(user("A"), 100)
        c.worker.drain()
        assertEquals("fallback", c.client.stringVariation("f", "fallback"))
        h.worker.drain()
        assertEquals("cached", c.client.stringVariation("f", "fallback"))
        c.client.identify(user("B"), 100)
        c.worker.drain()
        h.worker.drain()
        assertEquals("fallback", c.client.stringVariation("f", "fallback"))
        c.client.identify(user("missing"), 100)
        c.worker.drain()
        assertEquals("fallback", c.client.stringVariation("f", "fallback"))
        h.worker.drain()
        assertEquals("bootstrap", c.client.stringVariation("f", "fallback"))
        assertFalse(c.client.getConnectionInformation().remoteConfirmed)
    }

    @Test
    public fun lateCacheAndMissCannotReplaceRemoteIdentifyOrClose() {
        for (hit in listOf(false, true)) {
            val h = DiskHarness()
            if (hit) {
                h.save(user("A"), record("f", "cached"))
                h.worker.drain()
            }
            val c = CachedClient(h, bootstrap = listOf(flag("f", "bootstrap")))
            c.client.identify(user("A"), 100)
            c.worker.drain()
            c.source.sinks.last().full(record("f", "remote"))
            h.worker.drain()
            assertEquals("remote", c.client.stringVariation("f", "fallback"))
            c.client.identify(user("A"), 100)
            c.worker.drain()
            c.client.identify(user("B"), 100)
            c.worker.drain()
            h.worker.drain()
            assertEquals("bootstrap", c.client.stringVariation("f", "fallback"))
            c.client.identify(user("A"), 100)
            c.worker.drain()
            c.client.close()
            c.worker.drain()
            h.worker.drain()
            assertEquals("fallback", c.client.stringVariation("f", "fallback"))
        }
    }

    @Test
    public fun cacheNeverConfirmsOnlineReadinessAndLocalSourcesNeverWriteIt() {
        val h = DiskHarness()
        h.save(user("A"), record("f", "cached"))
        h.worker.drain()
        val c = CachedClient(h)
        val ready = c.client.awaitReady(100)
        h.worker.drain()
        assertNull(ready.getResult())
        assertFalse(c.client.getConnectionInformation().remoteConfirmed)
        val local = CachedClient(h, ControlledSource(false))
        local.source.sinks.last().full(record("f", "local"))
        h.worker.drain()
        assertEquals(
            "cached",
            DiskHarness(h.storage, h.clock).load(user("A"))!!.records.getValue("f").variation,
        )
    }

    @Test
    public fun currentContextClearDoesNotEraseOtherContextsAndReportsFailures() {
        val h = DiskHarness()
        h.save(user("A"), record("f", "A"))
        h.save(user("B"), record("f", "B"))
        h.worker.drain()
        val c = CachedClient(h, offline = true)
        h.worker.drain()
        val clear = c.client.clearCache(CacheScope.CURRENT_CONTEXT, 100)
        h.worker.drain()
        assertTrue(clear.getResult()!!.isSuccess)
        assertNull(DiskHarness(h.storage, h.clock).load(user("A")))
        assertNotNull(DiskHarness(h.storage, h.clock).load(user("B")))
        h.storage.failWrite = true
        val failed = c.client.clearCache(CacheScope.NAMESPACE, 100)
        h.worker.drain()
        assertEquals(OutcomeCode.STORAGE_FAILED, failed.getResult()!!.code)
        assertEquals("A", c.client.stringVariation("f", "fallback"))
    }

    @Test
    public fun timeoutCloseAndCapacityNeverFabricateSuccessfulClearing() {
        val h = DiskHarness()
        val c = CachedClient(h, offline = true)
        val timed = c.client.clearCache(CacheScope.NAMESPACE, 1)
        h.clock.now += 1
        c.ticker.action()
        h.worker.drain()
        assertEquals(OutcomeCode.TIMED_OUT, timed.getResult()!!.code)
        val closed = c.client.clearCache(CacheScope.NAMESPACE, 100)
        c.client.close()
        h.worker.drain()
        assertEquals(OutcomeCode.CLOSED, closed.getResult()!!.code)
        val rejected = DiskHarness()
        rejected.worker.accept = false
        assertFalse(rejected.disk.clear(null, rejected.reports::add) {})
        assertEquals(0L, rejected.disk.epoch())
    }

    @Test
    public fun lruCorruptionAndStorageFailure() {
        val h = DiskHarness()
        for (n in 0..4) {
            h.save(user("$n"), record("f", "$n"))
            h.worker.drain()
            h.clock.utc++
        }
        h.load(user("0"))
        h.clock.utc++
        h.save(user("5"), record("f", "5"))
        h.worker.drain()
        val restart = DiskHarness(h.storage, h.clock)
        assertNotNull(restart.load(user("0")))
        assertNull(restart.load(user("1")))
        val bad = MemoryStorage().apply { bytes = byteArrayOf(0, 1, 2) }
        val corrupt = DiskHarness(bad)
        assertNull(corrupt.load(user("A")))
        assertEquals(listOf("cache_read_failed"), corrupt.reports)
        val unreadable = DiskHarness(MemoryStorage().apply { failRead = true })
        assertNull(unreadable.load(user("A")))
        assertEquals(listOf("cache_read_failed"), unreadable.reports)
        val write = DiskHarness(MemoryStorage().apply { failWrite = true })
        write.save(user("A"), record("f", "x"))
        write.worker.drain()
        assertEquals(listOf("cache_write_failed"), write.reports)
    }

    @Test
    public fun cacheSurvivesAgeClockRollbackAndSubsequentWrites() {
        val h = DiskHarness()
        h.save(user("A"), record("f", "cached"))
        h.worker.drain()
        h.clock.utc += 365L * 24 * 60 * 60 * 1000
        assertEquals(
            "cached",
            DiskHarness(h.storage, h.clock).load(user("A"))!!.records.getValue("f").variation,
        )
        h.save(user("B"), record("f", "other"))
        h.worker.drain()
        assertNotNull(DiskHarness(h.storage, h.clock).load(user("A")))
        h.clock.utc = 0
        assertNotNull(DiskHarness(h.storage, h.clock).load(user("A")))
        h.save(user("C"), record("f", "third"))
        h.worker.drain()
        assertNotNull(DiskHarness(h.storage, h.clock).load(user("A")))
        assertNotNull(DiskHarness(h.storage, h.clock).load(user("B")))
    }

    @Test
    public fun snapshotAboveFormerByteLimitPersistsAndRestoresWithoutEvictingOtherContexts() {
        val h = DiskHarness()
        val c = CachedClient(h)
        h.save(user("B"), record("f", "other"))
        h.worker.drain()
        val huge = "x".repeat(6 * 1024 * 1024) // UTF-16 encoding exceeds the former 10 MiB limit.
        assertEquals(SourceUpdateCode.COMMITTED, c.source.sinks.last().full(record("f", huge)).code)
        h.worker.drain()
        assertEquals(huge, c.client.stringVariation("f", "fallback"))
        assertTrue(h.storage.bytes!!.size > 10 * 1024 * 1024)
        val restart = DiskHarness(h.storage, h.clock)
        assertEquals(huge, restart.load(user("A"))!!.records.getValue("f").variation)
        assertEquals("other", restart.load(user("B"))!!.records.getValue("f").variation)
        assertTrue(h.reports.isEmpty())
    }

    @Test
    public fun anonymousCreationRestartResetFailureAndRevisionFencing() {
        val storage = MemoryStorage()
        val worker = ManualWorkers()
        val repo = PersistentAnonymous(storage, worker)
        var first: AnonymousIdentity? = null
        repo.prepare(false) { first = it.value }
        worker.drain()
        assertNotNull(first)
        assertEquals(1, storage.writes)
        val restarted = PersistentAnonymous(storage, worker)
        restarted.prepare(false) { assertEquals(first!!.key, it.value!!.key) }
        worker.drain()
        storage.failWrite = true
        repo.prepare(true) { assertEquals(OutcomeCode.STORAGE_FAILED, it.code) }
        worker.drain()
        assertTrue(repo.withCurrent(first!!) {})
        storage.failWrite = false
        var second: AnonymousIdentity? = null
        repo.prepare(true) { second = it.value }
        worker.drain()
        assertNotEquals(first!!.key, second!!.key)
        assertFalse(repo.withCurrent(first!!) { fail("obsolete revision") })
        assertTrue(repo.withCurrent(second!!) {})
        PersistentAnonymous(storage, worker).prepare(false) {
            assertEquals(second!!.key, it.value!!.key)
        }
        worker.drain()
    }

    @Test
    public fun anonymousOrderedResetsAndAdmittedWriteAfterSupersession() {
        val storage = MemoryStorage()
        val worker = ManualWorkers()
        val repo = PersistentAnonymous(storage, worker)
        val h = Harness(anonymous = repo, anonymousEnabled = true, offline = true)
        val reset = h.client.resetAnonymousIdentity(100)
        h.workers.drain() // admitted to the independent persistence queue
        h.client.identify(user("B"), 100)
        worker.drain()
        assertEquals(OutcomeCode.SUPERSEDED, reset.getResult()!!.code)
        var one: AnonymousIdentity? = null
        var two: AnonymousIdentity? = null
        repo.prepare(true) { one = it.value }
        repo.prepare(true) { two = it.value }
        worker.drain()
        assertTrue(two!!.revision > one!!.revision)
        PersistentAnonymous(storage, worker).prepare(false) {
            assertEquals(two!!.key, it.value!!.key)
        }
        worker.drain()
        val returned = h.client.identifyAnonymous(100)
        h.workers.drain()
        worker.drain()
        assertTrue(returned.getResult()!!.isSuccess)
        h.close()
    }

    @Test
    public fun anonymousCorruptionAndInitialWriteFailureNeverPublishEphemeralKey() {
        val worker = ManualWorkers()
        for (storage in
            listOf(
                MemoryStorage().apply { bytes = byteArrayOf(1) },
                MemoryStorage().apply { failRead = true },
                MemoryStorage().apply { failWrite = true },
            )) {
            val repo = PersistentAnonymous(storage, worker)
            repo.prepare(false) {
                assertEquals(OutcomeCode.STORAGE_FAILED, it.code)
                assertNull(it.value)
            }
            worker.drain()
        }
    }
}
