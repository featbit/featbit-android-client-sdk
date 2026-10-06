package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import co.featbit.android.kotlin.ClientAdapters
import co.featbit.android.testing.*
import java.util.ArrayDeque
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

internal class FakeClock : Clock {
    var now = 0L
    var utc = 1_000L

    override fun elapsed() = now

    override fun wall() = utc
}

internal class ManualDispatch : Dispatch {
    val queue = ArrayDeque<() -> Unit>()

    override fun post(action: () -> Unit) {
        queue.add(action)
    }

    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst()()
    }
}

internal class ManualWorkers : Workers {
    val queue = ArrayDeque<() -> Unit>()
    var accept = true
    var stopped = false

    override fun execute(action: () -> Unit): Boolean {
        if (!accept) return false
        queue.add(action)
        return true
    }

    override fun close() {
        stopped = true
    }

    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst()()
    }
}

internal class ManualTicker : Ticker {
    var action: () -> Unit = {}
    var stopped = false

    override fun start(action: () -> Unit) {
        this.action = action
    }

    override fun close() {
        stopped = true
    }
}

internal class ControlledSource(val remote: Boolean = true) : DataSourceFactory {
    val sinks = ArrayList<SourceUpdateSink>()
    val contexts = ArrayList<SourceSessionContext>()
    var stops = 0
    var throwStart = false
    var stopCompletes = true

    override fun capabilities() =
        SourceCapabilities(if (remote) Provenance.REMOTE else Provenance.LOCAL, remote)

    override fun validate() = Outcome.success(SourceValidation.VALID)

    override fun create(
        context: SourceSessionContext,
        sink: SourceUpdateSink,
    ): Outcome<DataSource> {
        sinks.add(sink)
        contexts.add(context)
        return Outcome.success(
            object : DataSource {
                override fun start(completion: Completion<SourceStarted>) {
                    if (throwStart) throw IllegalStateException("secret should never escape")
                    completion.onComplete(Outcome.success(SourceStarted.STARTED))
                }

                override fun stop(completion: Completion<SourceStopped>) {
                    stops++
                    if (stopCompletes) completion.onComplete(Outcome.success(SourceStopped.STOPPED))
                }
            }
        )
    }
}

internal class Harness(
    source: DataSourceFactory? = ControlledSource(),
    offline: Boolean = false,
    bootstrap: List<BootstrapFlag>? = null,
    anonymous: AnonymousRepository? = null,
    anonymousEnabled: Boolean = false,
) {
    val clock = FakeClock()
    val dispatch = ManualDispatch()
    val workers = ManualWorkers()
    val ticker = ManualTicker()
    val options: ClientOptions =
        ClientOptions.builder()
            .user(user("A"))
            .source(source)
            .offline(offline)
            .disableEvents(true)
            .cacheEnabled(false)
            .anonymousEnabled(anonymousEnabled)
            .closeTimeoutMillis(100)
            .apply { if (bootstrap != null) bootstrap(bootstrap) }
            .build()
            .value!!
    val client =
        LocalClient(
            options,
            options.initialUser!!,
            source?.capabilities(),
            dispatch,
            clock,
            workers,
            ticker,
            Diagnostics(options, clock, ManualWorkers()),
            anonymous,
        )

    init {
        client.start()
        workers.drain()
    }

    fun advance(ms: Long) {
        clock.now += ms
        ticker.action()
    }

    fun close() {
        client.close()
        workers.drain()
        ticker.action()
        dispatch.drain()
    }
}

internal fun user(key: String, attr: String? = null): User =
    User.builder(key)
        .name(key)
        .apply { if (attr != null) attribute("plan", AttributeValue.text(attr).value!!) }
        .build()
        .value!!

internal fun flag(key: String, value: String, type: ValueType = ValueType.STRING): BootstrapFlag =
    BootstrapFlag.create(key, value, type).value!!

internal fun record(
    key: String,
    value: String,
    time: Long = 1,
    archived: Boolean = false,
    type: String = "string",
): FlagRecord =
    FlagRecord.builder(key, value, type, time)
        .archived(archived)
        .reason("server reason")
        .build()
        .value!!

internal fun SourceUpdateSink.full(vararg flags: FlagRecord): SourceUpdateResult =
    submit(FullUpdate.create(flags.toList()).value!!).getResult()!!.value!!

internal fun SourceUpdateSink.patch(vararg flags: FlagRecord): SourceUpdateResult =
    submit(PatchUpdate.create(flags.toList()).value!!).getResult()!!.value!!

public class LocalRuntimeTest {
    @Test
    public fun largeJsonArrayEvaluatesBeyondFormerNodeLimit() {
        val h = Harness()
        val raw = List(50_001) { "0" }.joinToString(",", "[", "]")
        (h.options.source as ControlledSource)
            .sinks
            .last()
            .full(record("large", raw, type = "json"))
        val detail = h.client.jsonVariationDetail("large", FbValue.jsonNull())
        assertEquals(EvaluationReason.MATCH, detail.reason)
        assertEquals(50_001, h.client.jsonVariation("large", FbValue.jsonNull()).asArray()!!.size)
        assertNull(Conversion.json("[".repeat(65) + "0" + "]".repeat(65)))
        h.close()
    }

    @Test
    public fun longWaitsDoNotExpireEarlyOrOverflow() {
        val h = Harness()
        val waiting = h.client.awaitReady(600_000)
        h.advance(300_001)
        assertNull(waiting.getResult())
        h.advance(299_999)
        assertEquals(OutcomeCode.TIMED_OUT, waiting.getResult()!!.code)
        val huge = h.client.identify(user("B"), Long.MAX_VALUE)
        h.advance(1)
        assertNull(huge.getResult())
        assertEquals(Long.MAX_VALUE, h.clock.deadlineAfter(Long.MAX_VALUE))
        h.workers.drain()
        (h.options.source as ControlledSource).sinks.last().full(record("f", "value"))
        assertEquals(OutcomeCode.SUCCESS, huge.getResult()!!.code)
        assertEquals(OutcomeCode.INVALID, h.client.awaitReady(0).getResult()!!.code)
        assertEquals(OutcomeCode.INVALID, h.client.identify(user("C"), -1).getResult()!!.code)
        h.close()
    }

    @Test
    public fun coroutineLongTimeoutDoesNotOverflow(): Unit = runBlocking {
        val clock = FakeClock().apply { now = 1_000 }
        val dispatch = ManualDispatch()
        val op = ResultOperation<String>(dispatch, CallbackBudget(), clock)
        val waiting =
            async(start = CoroutineStart.UNDISPATCHED) {
                CoroutineAdapters.await(op, Long.MAX_VALUE)
            }
        yield()
        op.settle(Outcome.success("ready"))
        dispatch.drain()
        assertEquals("ready", waiting.await().value)
        assertEquals(OutcomeCode.INVALID, CoroutineAdapters.await(op, 0).code)
    }

    @Test
    public fun physicallyBlockedExtensionWorkersCannotHoldCloseDeadline() {
        val entered = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val source =
            object : DataSourceFactory {
                override fun capabilities() = SourceCapabilities(Provenance.LOCAL, false)

                override fun validate() = Outcome.success(SourceValidation.VALID)

                override fun create(
                    context: SourceSessionContext,
                    sink: SourceUpdateSink,
                ): Outcome<DataSource> =
                    Outcome.success(
                        object : DataSource {
                            override fun start(completion: Completion<SourceStarted>) {
                                entered.countDown()
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                sink.full(record("f", "late"))
                                completion.onComplete(Outcome.success(SourceStarted.STARTED))
                            }

                            override fun stop(completion: Completion<SourceStopped>) {
                                completion.onComplete(Outcome.success(SourceStopped.STOPPED))
                            }
                        }
                    )
            }
        val options =
            ClientOptions.builder()
                .user(user("A"))
                .source(source)
                .disableEvents(true)
                .closeTimeoutMillis(10)
                .build()
                .value!!
        val clock = FakeClock()
        val workers = BoundedWorkers()
        val ticker = ManualTicker()
        val client =
            LocalClient(
                options,
                options.initialUser!!,
                source.capabilities(),
                ManualDispatch(),
                clock,
                workers,
                ticker,
                Diagnostics(options, clock, ManualWorkers()),
            )
        try {
            client.start()
            // Wait for first invocation before replacing its authority.
            val until = System.nanoTime() + 2_000_000_000
            while (entered.count == 2L && System.nanoTime() < until) Thread.yield()
            assertEquals(1L, entered.count)
            client.identify(user("B"), 100)
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val close = client.close()
            clock.now = 10
            ticker.action()
            assertFalse(close.getResult()!!.value!!.cleanupComplete)
            assertEquals("fallback", client.stringVariation("f", "fallback"))
        } finally {
            release.countDown()
            workers.close()
        }
    }

    @Test
    public fun coroutineDeadlineCountsElapsedSleepWithoutCancellingOperation(): Unit = runBlocking {
        val clock = FakeClock()
        val dispatch = ManualDispatch()
        val op = ResultOperation<String>(dispatch, CallbackBudget(), clock)
        val waiting =
            async(start = CoroutineStart.UNDISPATCHED) {
                ClientAdapters.getDefault().await(op, 10_000)
            }
        yield()
        clock.now = 20_000
        op.settle(Outcome.success("late delivery"))
        dispatch.drain()
        assertEquals(OutcomeCode.TIMED_OUT, withTimeout(1000) { waiting.await() }.code)
        assertEquals("late delivery", op.getResult()!!.value)
    }

    @Test
    public fun testDataKeepsUnchangedFlagTimestampsAcrossMutationAndRestart() {
        val clock = FakeClock()
        val dispatch = ManualDispatch()
        val data = LocalTestData(listOf(flag("a", "one"), flag("b", "one")), clock, dispatch)
        var last = emptyList<FlagRecord>()
        val sink =
            object : SourceUpdateSink {
                override fun submit(update: SourceUpdate): Operation<SourceUpdateResult> {
                    last = (update as FullUpdate).records
                    return ResultOperation<SourceUpdateResult>(dispatch, CallbackBudget()).apply {
                        settle(
                            Outcome.success(
                                SourceUpdateResult(
                                    SourceUpdateCode.COMMITTED,
                                    last.size,
                                    0,
                                    null,
                                    null,
                                )
                            )
                        )
                    }
                }

                override fun report(status: SourceStatus) = Outcome.success(StatusAccepted.ACCEPTED)
            }
        val first = data.create(SourceSessionContext(user("A"), null), sink).value!!
        first.start {}
        clock.utc = 10
        data.update(flag("b", "two"))
        assertEquals(1000L, last.first { it.key == "a" }.timestamp)
        assertEquals(10L, last.first { it.key == "b" }.timestamp)
        first.stop {}
        clock.utc = 20
        data.create(SourceSessionContext(user("B"), null), sink).value!!.start {}
        assertEquals(10L, last.first { it.key == "b" }.timestamp)
        assertFalse(
            FlagRecord.builder("f", "v", "string", 1)
                .variationOptions(listOf(null))
                .build()
                .isSuccess
        )
    }

    @Test
    public fun concurrentCommitsPublishWholeSnapshotsAndKeepPatchOrderAtAdmission() {
        val source = ControlledSource()
        val h = Harness(source)
        val sink = source.sinks.single()
        sink.full(record("a", "0"), record("b", "0"))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writes =
                executor.submit {
                    repeat(500) { n -> sink.full(record("a", "$n"), record("b", "$n")) }
                }
            val reads =
                executor.submit {
                    repeat(5_000) {
                        val all = h.client.allVariations()
                        assertEquals(all["a"]!!.value, all["b"]!!.value)
                    }
                }
            writes.get(10, java.util.concurrent.TimeUnit.SECONDS)
            reads.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("499", h.client.stringVariation("a", "fallback"))
        } finally {
            executor.shutdown()
            h.close()
        }
    }

    @Test
    public fun anonymousPreparationCannotUndoLaterIdentifyAndFailureKeepsCurrentContext() {
        val callbacks = ArrayList<(Outcome<AnonymousIdentity>) -> Unit>()
        val repo =
            object : AnonymousRepository {
                var revision = 1L

                override fun prepare(
                    reset: Boolean,
                    completion: (Outcome<AnonymousIdentity>) -> Unit,
                ) {
                    callbacks.add(completion)
                }

                override fun withCurrent(identity: AnonymousIdentity, adopt: () -> Unit): Boolean =
                    synchronized(this) {
                        if (identity.revision != revision) false
                        else {
                            adopt()
                            true
                        }
                    }
            }
        val source = ControlledSource()
        val h = Harness(source, anonymous = repo, anonymousEnabled = true)
        source.sinks.single().full(record("f", "A"))
        val anonymous = h.client.resetAnonymousIdentity(100)
        h.workers.drain()
        assertEquals("A", h.client.stringVariation("f", "fallback"))
        val b = h.client.identify(user("B"), 100)
        h.workers.drain()
        callbacks[0](Outcome.success(AnonymousIdentity("anonymous", 1)))
        assertEquals(OutcomeCode.SUPERSEDED, anonymous.getResult()!!.code)
        assertEquals("B", source.contexts.last().user.key)
        val failedReset = h.client.resetAnonymousIdentity(100)
        h.workers.drain()
        callbacks[1](Outcome.failure(OutcomeCode.STORAGE_FAILED, Diagnostic("secret")))
        assertEquals(OutcomeCode.STORAGE_FAILED, failedReset.getResult()!!.code)
        source.sinks.last().full(record("f", "B"))
        assertTrue(b.getResult()!!.isSuccess)
        val valid = h.client.identifyAnonymous(100)
        h.workers.drain()
        callbacks[2](Outcome.success(AnonymousIdentity("anonymous", 1)))
        h.workers.drain()
        assertEquals("anonymous", source.contexts.last().user.key)
        source.sinks.last().full()
        assertTrue(valid.getResult()!!.isSuccess)
        h.close()
    }

    @Test
    public fun extensionFailureOverloadAndLateQueuedCreationAreContained() {
        val source = ControlledSource().apply { throwStart = true }
        val h = Harness(source)
        assertEquals("custom_exception", h.client.getConnectionInformation().failure!!.code)
        h.client.setOffline(100)
        h.workers.drain()
        h.workers.accept = false
        h.client.setOnline(100)
        assertEquals("extension_capacity", h.client.getConnectionInformation().failure!!.code)
        h.close()
        val fresh = ControlledSource()
        val other = Harness(fresh)
        other.client.identify(user("B"), 100)
        other.client.close()
        other.workers.drain()
        assertEquals(1, fresh.sinks.size)
        assertTrue(other.client.close().getResult()!!.value!!.cleanupComplete)
    }

    @Test
    public fun runtimeFixturesAndConversionCostProbe() {
        val rows =
            javaClass.getResourceAsStream("/fixtures/v1/conversions.tsv")!!.bufferedReader().use {
                it.readLines()
            }
        rows
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val fields = line.split('\t')
                val converted: Any? =
                    when (fields[0]) {
                        "number" -> Conversion.number(fields[1])
                        "boolean" -> Conversion.boolean(fields[1])
                        else -> Conversion.json(fields[1])
                    }
                assertEquals(line, fields[2] == "valid", converted != null)
            }
        val payloads = List(5_000) { "{\"index\":$it,\"items\":[null,true,1,\"text\"]}" }
        val eagerStart = System.nanoTime()
        val eager = payloads.map { Conversion.json(it)!! }
        val eagerNanos = System.nanoTime() - eagerStart
        val first = System.nanoTime()
        val lazy = Conversion.json(payloads.first())!!
        val firstNanos = System.nanoTime() - first
        val repeat = System.nanoTime()
        repeat(5_000) { assertEquals(lazy, Conversion.json(payloads.first())) }
        val repeatedNanos = System.nanoTime() - repeat
        val cached = System.nanoTime()
        repeat(5_000) { assertEquals(lazy, eager.first()) }
        val cachedNanos = System.nanoTime() - cached
        println(
            "CONVERSION_PROBE records=5000 rawChars=${payloads.sumOf { it.length }} eagerNs=$eagerNanos firstNs=$firstNanos repeatedNs=$repeatedNanos cachedNs=$cachedNanos retainedParsedNodes=${5000 * 8}"
        )
    }

    @Test
    public fun bootstrapShadowArchiveAndFullReplacementFollowSpec() {
        val source = ControlledSource()
        val h = Harness(source, bootstrap = listOf(flag("a", "default"), flag("local", "kept")))
        val c = h.client
        val s = source.sinks.single()
        assertEquals("default", c.stringVariation("a", "fallback"))
        assertFalse(c.getConnectionInformation().remoteConfirmed)
        val first = s.full(record("a", "remote", 100), record("b", "present", 200))
        assertEquals(200L, first.baseline!!.cursor)
        assertEquals("kept", c.stringVariation("local", "fallback"))
        s.full(record("b", "lower full", 1))
        assertEquals("fallback", c.stringVariation("a", "fallback"))
        assertEquals("lower full", c.stringVariation("b", "fallback"))
        assertEquals(1L, s.patch(record("b", "archived", 1, true)).baseline!!.cursor)
        assertEquals(
            EvaluationReason.FLAG_NOT_FOUND,
            c.stringVariationDetail("b", "fallback").reason,
        )
        assertEquals(1, s.patch(record("b", "stale", 0)).skippedRecords)
        s.patch(record("b", "restored", 1))
        assertEquals("restored", c.stringVariation("b", "fallback"))
        assertEquals(0L, s.full().baseline!!.cursor)
        assertEquals(setOf("local"), c.allVariations().keys)
        h.close()
    }

    @Test
    public fun readyRequiresCommitAndExactRemoteBaseline() {
        val source = ControlledSource()
        val h = Harness(source, bootstrap = emptyList())
        val waiting = h.client.awaitReady(20)
        assertNull(waiting.getResult())
        val fake =
            object : Baseline {
                override val records = emptyMap<String, FlagRecord>()
                override val cursor = 0L
            }
        val sink = source.sinks.single()
        assertEquals(
            SourceUpdateCode.INVALID,
            sink.submit(NoChange(fake)).getResult()!!.value!!.code,
        )
        val baseline = sink.full().baseline!!
        assertEquals(ReadyResult.REMOTE_CONFIRMED, waiting.getResult()!!.value)
        assertEquals(
            SourceUpdateCode.COMMITTED,
            sink.submit(NoChange(baseline)).getResult()!!.value!!.code,
        )
        sink.patch(record("f", "x"))
        assertEquals(
            SourceUpdateCode.INVALID,
            sink.submit(NoChange(baseline)).getResult()!!.value!!.code,
        )
        h.close()
    }

    @Test
    public fun identifyRejectsOldSessionsIncludingSameKeyAndABA() {
        val source = ControlledSource()
        val h = Harness(source)
        source.sinks[0].full(record("f", "A"))
        val b = h.client.identify(user("B"), 100)
        assertEquals("A", h.client.stringVariation("f", "fallback"))
        h.workers.drain()
        assertEquals(
            EvaluationReason.CLIENT_NOT_READY,
            h.client.stringVariationDetail("f", "fallback").reason,
        )
        assertEquals(SourceUpdateCode.INACTIVE, source.sinks[0].full(record("f", "old A")).code)
        h.workers.drain()
        val a = h.client.identify(user("A", "new"), 100)
        h.workers.drain()
        assertEquals(OutcomeCode.SUPERSEDED, b.getResult()!!.code)
        h.workers.drain()
        assertEquals(SourceUpdateCode.INACTIVE, source.sinks[1].full(record("f", "old B")).code)
        source.sinks[2].full(record("f", "new A"))
        assertEquals(ReadyResult.REMOTE_CONFIRMED, a.getResult()!!.value)
        assertEquals("new A", h.client.stringVariation("f", "fallback"))
        val same = h.client.identify(user("A", "other"), 100)
        h.workers.drain()
        assertEquals(SourceUpdateCode.INACTIVE, source.sinks[2].full().code)
        source.sinks[3].full()
        assertTrue(same.getResult()!!.isSuccess)
        h.close()
    }

    @Test
    public fun offlineTransitionAbortsWaitAndResumesWithFreshAuthority() {
        val source = ControlledSource()
        val h = Harness(source)
        val wait = h.client.awaitReady(100)
        val off = h.client.setOffline(100)
        assertTrue(h.client.isOffline())
        assertEquals(OutcomeCode.DEFERRED, wait.getResult()!!.code)
        assertEquals(SourceUpdateCode.INACTIVE, source.sinks[0].full(record("f", "late")).code)
        val online = h.client.setOnline(100)
        assertEquals(ModeResult.ONLINE, online.getResult()!!.value)
        assertEquals(OutcomeCode.SUPERSEDED, off.getResult()!!.code)
        h.workers.drain()
        source.sinks.last().full(record("f", "fresh"))
        val offline = h.client.setOffline(100)
        h.workers.drain()
        assertTrue(offline.getResult()!!.isSuccess)
        assertEquals("fresh", h.client.stringVariation("f", "fallback"))
        val identified = h.client.identify(user("C"), 100)
        h.workers.drain()
        assertEquals(ReadyResult.OFFLINE_LOCAL, identified.getResult()!!.value)
        assertEquals("fallback", h.client.stringVariation("f", "fallback"))
        h.close()
    }

    @Test
    public fun offlineMissingEndpointsStayOfflineAndUnavailableApisAreExplicit() {
        val h = Harness(null, true)
        assertEquals(OutcomeCode.INVALID, h.client.setOnline(10).getResult()!!.code)
        assertTrue(h.client.isOffline())
        assertEquals(
            OutcomeCode.DISABLED,
            h.client.clearCache(CacheScope.NAMESPACE, 10).getResult()!!.code,
        )
        assertEquals(TrackResult.SUPPRESSED, h.client.track("metric").value)
        assertEquals(OutcomeCode.DISABLED, h.client.flush().getResult()!!.code)
        h.close()
    }

    @Test
    public fun typedReadsRequireDeclarationsWhileGenericReadsIgnoreFallbackType() {
        val source = ControlledSource()
        val h = Harness(source)
        try {
            source.sinks
                .single()
                .full(
                    record("b", "true", type = "boolean"),
                    record("n", "1.9", type = "number"),
                    record("s", "true", type = "string"),
                    record("ns", "1.9", type = "string"),
                    record("js", "{}", type = "string"),
                    record("j", "{}", type = "json"),
                    record("large", "9007199254740993", type = "number"),
                )
            val c: FeatBitClient = h.client
            assertTrue(c.boolVariation("b", false))
            assertTrue(c.boolVariationDetail("b", false).value)
            assertFalse(c.boolVariation("s", false))
            assertEquals(EvaluationReason.WRONG_TYPE, c.boolVariationDetail("s", false).reason)
            assertEquals(9.0, c.numberVariation("ns", 9.0), 0.0)
            assertEquals(EvaluationReason.WRONG_TYPE, c.numberVariationDetail("ns", 9.0).reason)
            assertEquals("fallback", c.stringVariation("b", "fallback"))
            assertEquals(
                EvaluationReason.WRONG_TYPE,
                c.stringVariationDetail("n", "fallback").reason,
            )
            assertEquals("true", c.stringVariation("s", "fallback"))
            val fallback = FbValue.jsonNull()
            assertSame(fallback, c.jsonVariation("js", fallback))
            assertEquals(EvaluationReason.WRONG_TYPE, c.jsonVariationDetail("js", fallback).reason)
            assertEquals("fallback", c.jsonTextVariation("js", "fallback"))
            assertEquals(
                EvaluationReason.WRONG_TYPE,
                c.jsonTextVariationDetail("js", "fallback").reason,
            )
            assertEquals("{}", c.jsonTextVariation("j", "fallback"))
            assertEquals(FbValue.Kind.BOOLEAN, c.variation("b", fallback).kind)
            assertEquals(FbValue.Kind.NUMBER, c.variation("n", fallback).kind)
            assertEquals(FbValue.Kind.STRING, c.variation("s", fallback).kind)
            assertEquals(FbValue.Kind.OBJECT, c.variation("j", fallback).kind)
            assertEquals(1.9, c.numberVariation("n", 0.0), 0.0)
            assertEquals(9007199254740992.0, c.numberVariation("large", 0.0), 0.0)
        } finally {
            h.close()
        }
    }

    @Test
    public fun typedGenericAndJsonConversionsHaveIndependentSemantics() {
        val source = ControlledSource()
        val h = Harness(source)
        source.sinks
            .single()
            .full(
                record("text", "TrUe"),
                record("number", "  -1.25e2 \t", type = "number"),
                record("json", "{\"a\":[null,true,1,\"x\"]}", type = "json"),
                record("null", "null", type = "json"),
                record("future", "false", type = "future"),
                record("bad", "[1,]", type = "json"),
            )
        val c = h.client
        val fallback = FbValue.jsonNull()
        assertFalse(c.boolVariation("text", false))
        assertEquals(EvaluationReason.WRONG_TYPE, c.boolVariationDetail("text", false).reason)
        assertEquals(FbValue.Kind.STRING, c.variation("text", fallback).kind)
        assertEquals(-125.0, c.numberVariation("number", 0.0), 0.0)
        assertEquals("server reason", c.variationDetail("number", fallback).explanation)
        assertEquals(
            FbValue.Kind.NULL,
            c.jsonVariation("json", fallback).asObject()!!["a"]!!.asArray()!![0].kind,
        )
        assertEquals(EvaluationReason.MATCH, c.jsonVariationDetail("null", fallback).reason)
        assertEquals(EvaluationReason.WRONG_TYPE, c.variationDetail("future", fallback).reason)
        assertTrue(c.boolVariation("future", true))
        assertEquals(EvaluationReason.WRONG_TYPE, c.jsonVariationDetail("bad", fallback).reason)
        assertEquals("[1,]", c.jsonTextVariation("bad", "fallback"))
        assertEquals(EvaluationReason.ERROR, c.variationDetail("", fallback).reason)
        try {
            (c.allVariations() as MutableMap).clear()
            fail()
        } catch (_: UnsupportedOperationException) {}
        h.close()
    }

    @Test
    public fun numericAndJsonBoundaryFixtures() {
        for (bad in
            listOf(
                "",
                "NaN",
                "Infinity",
                "1e999",
                "0x1",
                "1f",
                "1 2",
                "--1",
                "0x1.0p2",
            )) assertNull(bad, Conversion.number(bad))
        for (good in
            listOf(
                "0",
                "-0",
                "+1",
                ".5",
                "1.",
                " 1e-3 ",
                Double.MAX_VALUE.toString(),
            )) assertNotNull(good, Conversion.number(good))
        for (bad in
            listOf(
                "+1",
                "01",
                ".5",
                "1.",
                "[1,]",
                "{x:1}",
                "NaN",
                "1e999",
                "\"a\nb\"",
                "true false",
                "[".repeat(65) + "0" + "]".repeat(65),
            )) assertNull(bad, Conversion.json(bad))
        for (good in
            listOf(
                "null",
                "true",
                "false",
                "-1e2",
                "\"\\u0061\\n\"",
                "[]",
                "{}",
                "{\"x\":1,\"x\":2}",
            )) assertNotNull(good, Conversion.json(good))
    }

    @Test
    public fun changesCoalesceUnionAndSurviveDeletionAndReentry() {
        val source = ControlledSource()
        val h = Harness(source)
        val sink = source.sinks.single()
        val changes = ArrayList<FlagChange>()
        val keyChanges = ArrayList<FlagChange>()
        val sub =
            h.client
                .subscribeChanges {
                    changes.add(it)
                    h.client.allVariations()
                }
                .value!!
        val key = h.client.subscribeFlag("a") { keyChanges.add(it) }.value!!
        assertTrue(sub.initialValues.isEmpty())
        sink.full(record("a", "1"))
        sink.patch(record("b", "2"))
        h.dispatch.drain()
        assertEquals(setOf("a", "b"), changes.single().keys)
        sink.patch(record("a", "1", 2))
        h.dispatch.drain()
        assertEquals(1, changes.size)
        sink.full(record("b", "2"))
        h.dispatch.drain()
        assertEquals(2, keyChanges.size)
        sink.patch(record("a", "3"))
        h.dispatch.drain()
        assertEquals(3, keyChanges.size)
        sink.patch(record("a", "4"))
        key.registration.close()
        h.dispatch.drain()
        assertEquals(3, keyChanges.size)
        sub.registration.close()
        h.close()
    }

    @Test
    public fun statusSanitizesExternalDiagnosticsAndTerminalCannotRestart() {
        val source = ControlledSource()
        val h = Harness(source)
        val statuses = ArrayList<ConnectionInformation>()
        h.client.subscribeStatus { statuses.add(it) }
        h.dispatch.drain()
        val sink = source.sinks.single()
        sink.report(SourceStatus(SourceState.TERMINAL, Diagnostic("secret", "credential")))
        h.dispatch.drain()
        assertEquals("custom_terminal", statuses.last().failure!!.code)
        assertNull(statuses.last().failure!!.field)
        h.client.setOffline(100)
        h.workers.drain()
        val identified = h.client.identify(user("offline-terminal"), 100)
        h.workers.drain()
        assertEquals(ReadyResult.OFFLINE_LOCAL, identified.getResult()!!.value)
        h.client.setOnline(100)
        h.workers.drain()
        assertEquals(1, source.sinks.size)
        assertEquals(OutcomeCode.TERMINAL_FAILURE, h.client.awaitReady(100).getResult()!!.code)
        h.close()
    }

    @Test
    public fun elapsedDeadlinesSurviveWallClockRollbackAndLateConfirmation() {
        val source = ControlledSource()
        val h = Harness(source)
        val pending = h.client.awaitReady(10)
        h.clock.utc = 0
        h.clock.now = 11
        source.sinks.single().full()
        assertEquals(OutcomeCode.TIMED_OUT, pending.getResult()!!.code)
        assertEquals(ReadyResult.REMOTE_CONFIRMED, h.client.awaitReady(10).getResult()!!.value)
        h.close()
    }

    @Test
    public fun saturatedWaitsAndBlockedCallbacksCannotStarveClose() {
        val source = ControlledSource()
        val h = Harness(source)
        val waits = (1..256).map { h.client.awaitReady(100) }
        assertEquals(OutcomeCode.CAPACITY_EXCEEDED, h.client.awaitReady(100).getResult()!!.code)
        var delivered = 0
        waits.forEach { assertTrue(it.observe { delivered++ }.isSuccess) }
        assertEquals(OutcomeCode.CAPACITY_EXCEEDED, waits.first().observe {}.code)
        val close = h.client.close()
        assertSame(close, h.client.close())
        h.workers.drain()
        assertTrue(close.getResult()!!.value!!.cleanupComplete)
        assertEquals(0, delivered)
        assertTrue(waits.all { it.getResult()!!.code == OutcomeCode.CLOSED })
        h.dispatch.drain()
        assertEquals(256, delivered)
        assertEquals(SyncStatus.CLOSED, h.client.getConnectionInformation().status)
    }

    @Test
    public fun detachedQueuedCallbacksRetainBoundedDeliverySlots() {
        val d = ManualDispatch()
        val budget = CallbackBudget(2)
        val op = ResultOperation<String>(d, budget)
        op.settle(Outcome.success("done"))
        repeat(2) { op.observe { fail("detached") }.value!!.close() }
        assertEquals(OutcomeCode.CAPACITY_EXCEEDED, op.observe {}.code)
        assertEquals(2, d.queue.size)
        d.drain()
        assertTrue(op.observe {}.isSuccess)
        d.drain()
    }

    @Test
    public fun callbackReentryFailureAndIndependentDetach() {
        val source = ControlledSource()
        val h = Harness(source)
        val ready = h.client.awaitReady(100)
        var calls = 0
        ready.observe { throw IllegalStateException("application") }
        ready.observe {
            calls++
            h.client.close()
        }
        val detached = ready.observe { fail() }.value!!
        detached.close()
        source.sinks.single().full()
        h.dispatch.drain()
        h.workers.drain()
        assertEquals(1, calls)
        assertEquals(ReadyResult.REMOTE_CONFIRMED, ready.getResult()!!.value)
    }

    @Test
    public fun uncooperativeStopHasBoundedCloseAndNoLateAuthority() {
        val source = ControlledSource().apply { stopCompletes = false }
        val h = Harness(source)
        source.sinks.single().full(record("f", "kept"))
        val close = h.client.close()
        h.workers.drain()
        assertNull(close.getResult())
        h.advance(100)
        assertFalse(close.getResult()!!.value!!.cleanupComplete)
        assertEquals("kept", h.client.stringVariation("f", "fallback"))
        assertEquals(SourceUpdateCode.CLOSED, source.sinks.single().full(record("f", "late")).code)
        assertTrue(h.workers.stopped)
    }

    @Test
    public fun controlledLifecycleGatesCustomSessions() {
        val source = ControlledSource()
        val h = Harness(source)
        h.client.lifecycle(false, false)
        h.workers.drain()
        assertEquals(
            setOf(PauseReason.BACKGROUND, PauseReason.NETWORK_UNAVAILABLE),
            h.client.getConnectionInformation().pauseReasons,
        )
        assertEquals(SourceUpdateCode.INACTIVE, source.sinks.single().full().code)
        h.client.lifecycle(true, false)
        h.workers.drain()
        assertEquals(1, source.sinks.size)
        h.client.lifecycle(true, true)
        h.workers.drain()
        assertEquals(2, source.sinks.size)
        h.close()
    }

    @Test
    public fun largeFullAndPatchCommitWithoutResourceLimits() {
        val source = ControlledSource()
        val h = Harness(source, bootstrap = listOf(flag("local", "default")))
        val sink = source.sinks.single()
        val wait = h.client.awaitReady(100)
        val large = "x".repeat(1024 * 1024 + 1)
        val full =
            sink
                .submit(
                    FullUpdate.create(
                            List(9) { record("big$it", large) } + record("local", "overwritten")
                        )
                        .value!!
                )
                .getResult()!!
                .value!!
        assertEquals(SourceUpdateCode.COMMITTED, full.code)
        assertTrue(wait.getResult()!!.isSuccess)
        assertEquals(large, h.client.stringVariation("big0", "fallback"))
        assertEquals("overwritten", h.client.stringVariation("local", "fallback"))
        val many = List(50_001) { record("k$it", "x", 4) }
        val patch = sink.submit(PatchUpdate.create(many).value!!).getResult()!!.value!!
        assertEquals(SourceUpdateCode.COMMITTED, patch.code)
        assertEquals("x", h.client.stringVariation("k50000", "fallback"))
        assertEquals(
            SourceUpdateCode.COMMITTED,
            sink.submit(NoChange(patch.baseline!!)).getResult()!!.value!!.code,
        )
        sink.full(record("f", "initial", 3))
        repeat(500) { sink.patch(record("f", "$it", 3)) }
        assertEquals(1, sink.patch(record("f", "last", 3)).baseline!!.records.size)
        h.close()
    }

    @Test
    public fun largeBootstrapMetadataAndJsonRemainReadable() {
        val text = "x".repeat(1024 * 1024 + 1)
        val key = "k".repeat(1025)
        val h = Harness(ControlledSource(), bootstrap = listOf(flag(key, text)))
        assertEquals(text, h.client.stringVariation(key, "fallback"))
        val source = ControlledSource()
        val custom = Harness(source)
        val item =
            FlagRecord.builder(key, text, "t".repeat(1025), 1)
                .reason(text)
                .variationOptions(List(50_001) { VariationOption("id$it", "v") })
                .build()
                .value!!
        assertEquals(SourceUpdateCode.COMMITTED, source.sinks.single().full(item).code)
        assertEquals(
            EvaluationReason.WRONG_TYPE,
            custom.client.stringVariationDetail(key, "fallback").reason,
        )
        assertEquals(text, custom.client.allVariations()[key]!!.value)
        assertEquals(text, Conversion.json("\"$text\"")!!.asString())
        h.close()
        custom.close()
    }

    @Test
    public fun testDataMutationsReadinessOfflineAndClockRollback() {
        val clock = FakeClock()
        val dispatch = ManualDispatch()
        val data = LocalTestData(listOf(flag("f", "first")), clock, dispatch)
        val owner = Any()
        assertTrue(data.bind(owner))
        assertFalse(data.bind(Any()))
        val h = Harness(data)
        assertEquals(ReadyResult.CUSTOM_LOCAL, h.client.awaitReady(10).getResult()!!.value)
        assertFalse(h.client.getConnectionInformation().remoteConfirmed)
        assertEquals(TestDataResult.COMMITTED, data.update(flag("f", "second")).getResult()!!.value)
        clock.utc = 0
        assertEquals(
            TestDataResult.COMMITTED,
            data.update(flag("f", "rollback")).getResult()!!.value,
        )
        assertEquals("rollback", h.client.stringVariation("f", "fallback"))
        data.remove("f")
        assertEquals("fallback", h.client.stringVariation("f", "fallback"))
        data.update(flag("f", "again"))
        h.client.setOffline(10)
        h.workers.drain()
        assertEquals(
            TestDataResult.SAVED_FOR_NEXT_START,
            data.update(flag("f", "saved")).getResult()!!.value,
        )
        assertEquals("again", h.client.stringVariation("f", "fallback"))
        h.client.setOnline(10)
        h.workers.drain()
        assertEquals("saved", h.client.stringVariation("f", "fallback"))
        h.client.identify(user("B"), 10)
        h.workers.drain()
        assertEquals("saved", h.client.stringVariation("f", "fallback"))
        h.close()
        data.unbind(owner)
        assertTrue(data.bind(Any()))
    }

    @Test
    public fun testDataAcceptsLargeValuesAndRejectsDuplicateKeys() {
        val data = LocalTestData(listOf(flag("f", "good")), FakeClock(), ManualDispatch())
        val h = Harness(data)
        val large = "x".repeat(1024 * 1024 + 1)
        assertEquals(
            TestDataResult.COMMITTED,
            data.replace(listOf(flag("f", large))).getResult()!!.value,
        )
        val duplicate = data.replace(listOf(flag("f", "one"), flag("f", "two"))).getResult()!!
        assertEquals(OutcomeCode.INVALID, duplicate.code)
        assertEquals("duplicate_flag_key", duplicate.diagnostic!!.code)
        h.client.identify(user("B"), 10)
        h.workers.drain()
        assertEquals(large, h.client.stringVariation("f", "fallback"))
        assertEquals(
            "invalid_configuration",
            ClientOptions.builder()
                .user(user("A"))
                .offline(true)
                .bootstrap(listOf(flag("f", "one"), flag("f", "two")))
                .build()
                .diagnostic!!
                .code,
        )
        h.close()
    }

    @Test
    public fun flowCompletesAndCancellationDetachesOnlyWait(): Unit = runBlocking {
        val source = ControlledSource()
        val h = Harness(source)
        val adapters = ClientAdapters.getDefault()
        val values = ArrayList<FlagChange>()
        val collecting =
            launch(start = CoroutineStart.UNDISPATCHED) {
                adapters.changes(h.client).toList(values)
            }
        yield()
        source.sinks.single().full(record("f", "one"))
        h.dispatch.drain()
        yield()
        assertTrue(values.single().allFlagsChanged)
        val pending = h.client.identify(user("B"), 100)
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { adapters.await(pending, 100) }
        waiting.cancelAndJoin()
        h.workers.drain()
        source.sinks.last().full()
        assertTrue(pending.getResult()!!.isSuccess)
        h.close()
        yield()
        withTimeout(1000) { collecting.join() }
    }

    @Test
    public fun loggerIsBoundedRateLimitedAndExceptionIsolated() {
        val clock = FakeClock()
        val worker = ManualWorkers()
        var calls = 0
        val options =
            ClientOptions.builder()
                .user(user("A"))
                .offline(true)
                .logger { _, diagnostic ->
                    assertEquals("safe", diagnostic.code)
                    calls++
                    throw IllegalStateException("logger")
                }
                .build()
                .value!!
        val diagnostics = Diagnostics(options, clock, worker)
        repeat(1000) { diagnostics.report("safe") }
        assertEquals(1, worker.queue.size)
        worker.drain()
        assertEquals(1, calls)
        clock.now += 60_000
        diagnostics.report("safe")
        worker.drain()
        assertEquals(2, calls)
        diagnostics.close()
    }

    @Test
    public fun explicitCustomTransportAndTestDataConflictsAreRejected() {
        val source = ControlledSource()
        assertFalse(
            ClientOptions.builder()
                .user(user("A"))
                .source(source)
                .disableEvents(true)
                .mode(SyncMode.STREAMING)
                .build()
                .isSuccess
        )
        assertFalse(
            ClientOptions.builder()
                .user(user("A"))
                .source(source)
                .disableEvents(true)
                .pollingIntervalMillis(30_000)
                .build()
                .isSuccess
        )
        val data = LocalTestData(emptyList(), FakeClock(), ManualDispatch())
        assertFalse(
            ClientOptions.builder().user(user("A")).source(data).offline(true).build().isSuccess
        )
        assertTrue(data.clientOptions(user("A")).isSuccess)
    }
}
