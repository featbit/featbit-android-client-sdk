package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

public class CacheAlignmentTest {
    private class Cache(
        val storage: MemoryStorage = MemoryStorage(),
        val clock: FakeClock = FakeClock(),
    ) {
        val worker = ManualWorkers()
        val disk = DiskCoordinator(storage, worker, clock)
        val errors = ArrayList<String>()

        fun save(
            key: String,
            policy: CachePolicy = CachePolicy(),
            records: List<FlagRecord> = listOf(record("f", key)),
        ) {
            assertTrue(
                disk.commitWithPolicy(
                    contextKey(user(key)),
                    CachedSnapshot(
                        records.associateBy { it.key },
                        records.maxOfOrNull { it.timestamp } ?: 0,
                    ),
                    policy,
                    errors::add,
                )
            )
            worker.drain()
        }

        fun load(key: String, policy: CachePolicy = CachePolicy()): CachedSnapshot? {
            var result: CachedSnapshot? = null
            assertTrue(
                disk.loadWithPolicy(contextKey(user(key)), disk.epoch(), errors::add, policy) {
                    result = it
                }
            )
            worker.drain()
            return result
        }
    }

    @Test
    public fun idleTtlTouchSurvivesRestartAndExpiresAtBoundary() {
        val h = Cache()
        val policy = CachePolicy(5, 100)
        h.save("A", policy)
        h.clock.utc += 99
        assertNotNull(h.load("A", policy))
        h.clock.utc += 99
        val restarted = Cache(h.storage, h.clock)
        assertNotNull(restarted.load("A", policy))
        h.clock.utc += 100
        assertNull(Cache(h.storage, h.clock).load("A", policy))
        assertNull(Cache(h.storage, h.clock).load("A", policy))
    }

    @Test
    public fun configurableCapacityEvictsLeastRecentlyUsedAndSupportsMoreThanFive() {
        val h = Cache()
        val large = CachePolicy(8, 10000)
        repeat(8) {
            h.save("$it", large)
            h.clock.utc++
        }
        val restarted = Cache(h.storage, h.clock)
        repeat(8) {
            assertNotNull(restarted.load("$it", large))
            h.clock.utc++
        }
        val small = CachePolicy(2, 10000)
        restarted.load("0", small)
        assertNotNull(restarted.load("0", small))
        assertNotNull(restarted.load("7", small))
        assertNull(restarted.load("1", small))
        restarted.save("new", small)
        assertNull(restarted.load("0", small))
        assertNotNull(restarted.load("new", small))
    }

    @Test
    public fun failedDiskWriteRetainsNewestContextInMemory() {
        val h = Cache()
        h.storage.failWrite = true
        h.save("A")
        h.save("B")
        assertEquals("A", h.load("A")!!.records.getValue("f").variation)
        assertTrue(h.errors.contains("cache_write_failed"))
        assertNull(h.storage.bytes)
    }

    @Test
    public fun archivedPayloadIsDiscardedInMemoryAndDisk() {
        val h = Cache()
        val huge = "private-value".repeat(10000)
        h.save(
            "A",
            records =
                listOf(
                    FlagRecord.builder("f", huge, "json", 20)
                        .archived(true)
                        .reason(huge)
                        .variationOptions(listOf(VariationOption("id", huge)))
                        .build()
                        .value!!
                ),
        )
        assertTrue(h.storage.bytes!!.size < 512)
        for (cache in listOf(h, Cache(h.storage, h.clock))) {
            val result = cache.load("A")!!
            val marker = result.records.getValue("f")
            assertEquals(20L, result.cursor)
            assertTrue(marker.archived)
            assertEquals("", marker.variation)
            assertEquals("", marker.variationType)
            assertNull(marker.variationOptions)
        }
    }

    @Test
    public fun legacyV1AndV2ArchivesMigrateWithoutLosingCursor() {
        for (version in 1..2) {
            val h = Cache()
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                fun text(value: String) {
                    out.writeInt(value.length)
                    value.forEach { out.writeChar(it.code) }
                }
                out.writeInt(0x46424331)
                out.writeInt(version)
                out.writeInt(1)
                text(contextKey(user("A")))
                out.writeLong(h.clock.utc)
                out.writeLong(h.clock.utc)
                text("REMOTE")
                out.writeLong(50)
                out.writeInt(1)
                text("f")
                text("old-large-value".repeat(100))
                text("string")
                out.writeLong(20)
                out.writeBoolean(true)
                if (version == 2) out.writeBoolean(true)
                out.writeBoolean(true)
                text("flag archived")
                out.writeInt(-1)
            }
            h.storage.bytes = bytes.toByteArray()
            val loaded = h.load("A")!!
            assertEquals(50L, loaded.cursor)
            assertEquals(20L, loaded.records.getValue("f").timestamp)
            assertEquals("", loaded.records.getValue("f").variation)
            assertTrue(h.storage.bytes!!.size < 512)
            assertTrue(Cache(h.storage, h.clock).load("A")!!.records.getValue("f").archived)
        }
    }

    @Test
    public fun disabledPersistenceStillRestoresRemoteUsersAndArchiveVersions() {
        val source = ControlledSource()
        val h = Harness(source, bootstrap = listOf(flag("f", "bootstrap")))
        source.sinks.last().full(record("f", "value", 10))
        source.sinks.last().patch(record("f", "large", 20, true))
        h.workers.drain()
        h.client.identify(user("B"), 1000)
        h.workers.drain()
        source.sinks.last().full(record("f", "B", 30))
        h.workers.drain()
        h.client.identify(user("A"), 1000)
        h.workers.drain()
        assertEquals("fallback", h.client.stringVariation("f", "fallback"))
        source.sinks.last().patch(record("f", "stale", 19))
        assertEquals("fallback", h.client.stringVariation("f", "fallback"))
        source.sinks.last().patch(record("f", "restored", 20))
        assertEquals("restored", h.client.stringVariation("f", "fallback"))
        h.close()
    }

    @Test
    public fun memoryContextRetentionUsesConfiguredTtlAndCapacityWithoutExpiringActiveValues() {
        val source = ControlledSource()
        val h =
            Harness(
                source,
                bootstrap = listOf(flag("f", "bootstrap")),
                maxCachedUsers = 1,
                userCacheTtlMillis = 100,
            )
        source.sinks.last().full(record("f", "A"))
        h.workers.drain()
        h.clock.utc += 100
        assertEquals("A", h.client.stringVariation("f", "fallback"))
        h.client.identify(user("A"), 1000)
        h.workers.drain()
        assertEquals("bootstrap", h.client.stringVariation("f", "fallback"))
        source.sinks.last().full(record("f", "A2"))
        h.workers.drain()
        h.client.identify(user("B"), 1000)
        h.workers.drain()
        source.sinks.last().full(record("f", "B"))
        h.workers.drain()
        h.client.identify(user("A"), 1000)
        h.workers.drain()
        assertEquals("bootstrap", h.client.stringVariation("f", "fallback"))
        h.close()
    }

    @Test
    public fun startupPrefersEmptyAndArchivedCacheOverBootstrapButExpiredCacheFallsBack() {
        for (records in listOf(emptyList(), listOf(record("f", "old", 20, true)))) {
            for (expired in listOf(false, true)) {
                val h = Cache()
                h.save("A", records = records)
                if (expired) h.clock.utc += 604_800_000
                val options =
                    ClientOptions.builder()
                        .user(user("A"))
                        .offline(true)
                        .disableEvents(true)
                        .bootstrap(listOf(flag("f", "bootstrap")))
                        .build()
                        .value!!
                val workers = ManualWorkers()
                val ticker = ManualTicker()
                val client =
                    LocalClient(
                        options,
                        user("A"),
                        null,
                        ManualDispatch(),
                        h.clock,
                        workers,
                        ticker,
                        Diagnostics(options, h.clock, ManualWorkers()),
                        cache = h.disk,
                    )
                client.start()
                assertEquals("fallback", client.stringVariation("f", "fallback"))
                h.worker.drain()
                assertEquals(
                    if (expired) "bootstrap" else "fallback",
                    client.stringVariation("f", "fallback"),
                )
                assertFalse(client.getConnectionInformation().remoteConfirmed)
                client.close()
                workers.drain()
                ticker.action()
            }
        }
    }

    @Test
    public fun NoChangeAndRejectedPatchesDoNotRefreshHistoricalTtl() {
        val source = ControlledSource()
        val h =
            Harness(source, bootstrap = listOf(flag("f", "bootstrap")), userCacheTtlMillis = 100)
        val sink = source.sinks.last()
        val baseline = sink.full(record("f", "A", 10)).baseline!!
        h.workers.drain()
        h.clock.utc += 99
        sink.submit(NoChange(baseline))
        sink.patch(record("f", "stale", 9))
        h.workers.drain()
        h.clock.utc += 1
        h.client.identify(user("A"), 1000)
        h.workers.drain()
        assertEquals("bootstrap", h.client.stringVariation("f", "fallback"))
        h.close()
    }

    @Test
    public fun newCacheAndRefreshOptionsValidateAndKeepJsDefaults() {
        fun builder() = ClientOptions.builder().user(user("A")).offline(true)
        val defaults = builder().build().value!!
        assertEquals(5, defaults.maxCachedUsers)
        assertEquals(604800000L, defaults.userCacheTtlMillis)
        assertEquals(86400000L, defaults.fullSyncIntervalMillis)
        assertFalse(builder().maxCachedUsers(0).build().isSuccess)
        assertFalse(builder().userCacheTtlMillis(0).build().isSuccess)
        assertFalse(builder().fullSyncIntervalMillis(-1).build().isSuccess)
        assertTrue(
            builder()
                .maxCachedUsers(8)
                .userCacheTtlMillis(1)
                .fullSyncIntervalMillis(1)
                .build()
                .isSuccess
        )
    }
}
