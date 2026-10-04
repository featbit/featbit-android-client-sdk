package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

internal fun okhttp3.Request.payload(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

internal class EventHarness(
    capacity: Int = 100,
    disabled: Boolean = false,
    offline: Boolean = false,
    remote: Boolean = true,
    transition: Boolean = false,
    configure: (ClientOptions.Builder) -> Unit = {},
) {
    val source = ControlledSource(remote)
    val clock = FakeClock()
    val workers = ManualWorkers()
    val dispatch = ManualDispatch()
    val ticker = ManualTicker()
    val transport = FakeTransport()
    val logs = ManualWorkers()
    val options =
        ClientOptions.builder()
            .user(user("A", "private"))
            .source(source)
            .sdkKey("test-key")
            .eventsUrl("http://localhost/prefix")
            .cacheEnabled(false)
            .disableEvents(disabled)
            .offline(offline)
            .requestTimeoutMillis(5_000)
            .closeTimeoutMillis(3_000)
            .eventCapacity(capacity)
            .transitionFlush(transition)
            .eventHeader("X-Event", "event-only")
            .apply(configure)
            .build()
            .value!!
    val diagnostics = Diagnostics(options, clock, logs)
    val client =
        LocalClient(
            options,
            options.initialUser!!,
            source.capabilities(),
            dispatch,
            clock,
            workers,
            ticker,
            diagnostics,
            eventTransportFactory = { transport },
            random = { 1.0 },
        )

    init {
        client.start()
        workers.drain()
    }

    val last: FakeTransport.Exchange
        get() = transport.requests.last()

    fun pump() {
        ticker.action()
        workers.drain()
        dispatch.drain()
    }

    fun advance(ms: Long) {
        clock.now += ms
        pump()
    }

    fun reply(status: Int = 200) {
        last.listener.response(status, "", null)
        pump()
    }

    fun flush(): Operation<FlushResult> = client.flush().also { workers.drain() }

    fun remoteRecord(value: String = "true") =
        FlagRecord.builder("flag", value, "boolean", 1)
            .variationOptions(
                listOf(VariationOption("11111111-1111-1111-1111-111111111111", value))
            )
            .build()
            .value!!

    fun close() {
        client.close()
        advance(3_000)
    }
}

public class EventsTest {
    @Test
    public fun requestConstructionFailureHasFiniteBudgetAndDoesNotLogCredentials() {
        val h = EventHarness(configure = { it.sdkKey("invalid\ncredential") })
        h.client.track("one")
        val wait = h.flush()
        h.advance(1000)
        h.advance(2000)
        assertEquals(FlushResult.PROCESSED_WITH_LOSS, wait.getResult()!!.value)
        assertEquals(1L, h.diagnostics.lossCounts()["event_retry_exhausted"])
        assertTrue(h.transport.requests.isEmpty())
        h.close()
    }

    @Test
    public fun retryAfterDelaysSendingWithoutChangingPayload() {
        val h = EventHarness()
        h.client.track("one")
        h.flush()
        val payload = h.last.request.payload()
        h.last.listener.response(429, "", "3")
        h.advance(2999)
        assertEquals(1, h.transport.requests.size)
        h.advance(1)
        assertEquals(2, h.transport.requests.size)
        assertEquals(payload, h.last.request.payload())
        h.reply()
        h.close()
    }

    @Test
    public fun sleepPastBackgroundBudgetRejectsResponseEvenBeforeNextTimerTick() {
        val h = EventHarness(transition = true)
        h.client.track("one")
        val wait = h.flush()
        val old = h.last
        h.client.lifecycle(false, true)
        h.clock.now += 2001 // A socket callback can execute before the delayed lifecycle timer.
        old.listener.response(403, "", null)
        assertNull(wait.getResult())
        h.client.lifecycle(true, true)
        h.advance(1000)
        assertEquals(2, h.transport.requests.size)
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        h.close()
    }

    @Test
    public fun sleepPastCloseDeadlineRejectsAcknowledgementBeforeCleanupTick() {
        val h = EventHarness()
        h.client.track("one")
        val close = h.client.close()
        h.pump()
        h.clock.now += 3000
        h.last.listener.response(200, "", null)
        h.pump()
        assertEquals(1L, close.getResult()!!.value!!.undeliveredEvents)
    }

    @Test
    public fun dispatchRevocationAndLateHandleAttachmentCannotLeakNewRequests() {
        val h = EventHarness()
        h.client.track("queued")
        val wait = h.client.flush()
        h.client.setOffline(1000)
        h.workers.drain()
        assertTrue(h.transport.requests.isEmpty())
        assertEquals(OutcomeCode.DEFERRED, wait.getResult()!!.code)
        h.transport.duringStart = { h.client.setOffline(1000) }
        h.client.setOnline(1000)
        h.workers.drain()
        h.pump()
        assertTrue(h.last.canceled)
        h.transport.duringStart = null
        h.client.setOnline(1000)
        h.advance(2000)
        assertEquals(1, h.transport.requests.size)
        h.last.listener.response(200, "", null)
        h.pump()
        assertEquals(2, h.transport.requests.size)
        h.reply()
        h.close()
    }

    @Test
    public fun callbackReentryCanIdentifyTrackAndFlushWithoutChangingPriorCoverage() {
        val h = EventHarness()
        h.client.track("first")
        val first = h.flush()
        var next: Operation<FlushResult>? = null
        first.observe {
            assertEquals(FlushResult.ALL_DELIVERED, it.value)
            h.client.identify(user("B"), 1000)
            h.client.track("second")
            next = h.client.flush()
        }
        h.reply()
        h.pump()
        assertNotNull(next)
        assertTrue(h.last.request.payload().contains("second"))
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, next!!.getResult()!!.value)
        h.close()
    }

    @Test
    public fun operationCapacityBoundsSharedReadinessAndFlushWaiters() {
        val h = EventHarness()
        h.client.track("one")
        repeat(128) { assertNull(h.client.awaitReady(10_000).getResult()) }
        repeat(128) { assertNull(h.client.flush().getResult()) }
        assertEquals(OutcomeCode.CAPACITY_EXCEEDED, h.client.flush().getResult()!!.code)
        assertEquals(OutcomeCode.CAPACITY_EXCEEDED, h.client.awaitReady(10_000).getResult()!!.code)
        h.close()
    }

    @Test
    public fun invalidOrAmbiguousVariationMappingDoesNotAffectEvaluation() {
        val h = EventHarness()
        val id = "11111111-1111-1111-1111-111111111111"
        for (options in
            listOf(
                emptyList(),
                listOf(VariationOption(id, "false")),
                listOf(VariationOption(id, "true"), VariationOption(id, "true")),
                listOf(
                    VariationOption(id, "true"),
                    VariationOption("22222222-2222-2222-2222-222222222222", "true"),
                ),
            )) {
            h.source.sinks
                .last()
                .full(
                    FlagRecord.builder("flag", "true", "boolean", 1)
                        .variationOptions(options)
                        .build()
                        .value!!
                )
            assertTrue(h.client.boolVariation("flag", false))
        }
        assertEquals(4L, h.diagnostics.lossCounts()["event_invalid_metadata"])
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        h.close()
    }

    @Test
    public fun synchronizationTerminalDoesNotStopTrackOrConfirmedEvaluationEvents() {
        val h = EventHarness()
        h.source.sinks.last().full(h.remoteRecord())
        h.source.sinks.last().report(SourceStatus(SourceState.TERMINAL, null))
        assertEquals(SyncStatus.TERMINAL, h.client.getConnectionInformation().status)
        assertTrue(h.client.boolVariation("flag", false))
        h.client.track("healthy_event")
        val wait = h.flush()
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        assertEquals(2, Json.parseToJsonElement(h.last.request.payload()).jsonArray.size)
        h.close()
    }

    @Test
    public fun allPrivateFilteringIncludesAutomaticAttributesButLeavesSyncContextComplete() {
        val h = EventHarness(configure = { it.allAttributesPrivate(true) })
        val user =
            User.builder("A")
                .name("A")
                .attribute("featbit.sdk.deviceModel", AttributeValue.text("secret-device").value!!)
                .attribute("not valid!", AttributeValue.text("secret").value!!)
                .build()
                .value!!
        h.client.identify(user, 1000)
        h.workers.drain()
        assertEquals(2, h.source.contexts.last().user.attributes.size)
        assertEquals(TrackResult.ACCEPTED, h.client.track("one").value)
        val wait = h.flush()
        val body = h.last.request.payload()
        assertFalse(body.contains("secret"))
        assertTrue(body.contains("\"customizedProperties\":[]"))
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        h.close()
    }

    @Test
    public fun byteCapacityAndBatchesStayBoundedAndLossCountersSurviveLogThrottling() {
        val logged = ArrayList<Diagnostic>()
        val h =
            EventHarness(
                capacity = 1000,
                configure = { it.logger { _, diagnostic -> logged.add(diagnostic) } },
            )
        val user =
            User.builder("large")
                .name("Large")
                .apply {
                    repeat(90) {
                        attribute("attribute_$it", AttributeValue.text("x".repeat(2048)).value!!)
                    }
                }
                .build()
                .value!!
        h.client.identify(user, 1000)
        h.workers.drain()
        repeat(100) { h.client.track("event_$it") }
        assertTrue(h.diagnostics.lossCounts().getValue("event_capacity") > 1)
        h.flush()
        assertTrue(h.last.request.payload().toByteArray().size <= 262_144)
        assertEquals(1, Json.parseToJsonElement(h.last.request.payload()).jsonArray.size)
        h.close()
        h.logs.drain()
        assertEquals(
            h.diagnostics.lossCounts()["event_capacity"].toString(),
            logged.last { it.code == "event_capacity" }.field,
        )
        assertFalse(logged.toString().contains("Large"))
    }

    @Test
    public fun timeoutBudgetIsNotResetByRepeatedOfflineOnlineAndLateSuccess() {
        val h = EventHarness()
        h.client.track("one")
        h.flush()
        repeat(3) {
            val old = h.last
            h.client.setOffline(1000)
            h.client.setOnline(1000)
            h.advance(2000)
            old.listener.response(200, "", null)
            h.pump()
        }
        assertEquals(3, h.transport.requests.size)
        assertEquals(1L, h.diagnostics.lossCounts()["event_retry_exhausted"])
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        h.close()
    }

    @Test
    public fun trackBeforeReadyDeduplicatesFilteredSnapshotsAndPreservesFirstTimestamp() {
        val h = EventHarness(configure = { it.privateAttribute("plan") })
        assertFalse(h.client.getConnectionInformation().remoteConfirmed)
        assertEquals(TrackResult.ACCEPTED, h.client.track("purchase", 2.0).value)
        h.clock.utc = 2_000
        assertEquals(TrackResult.DEDUPLICATED, h.client.track("purchase", 2.0).value)
        h.client.identify(user("A", "changed"), 1000)
        h.workers.drain()
        assertEquals(TrackResult.DEDUPLICATED, h.client.track("purchase", 2.0).value)
        val wait = h.flush()
        val payload = h.last.request.payload()
        assertFalse(payload.contains("private"))
        assertFalse(payload.contains("changed"))
        assertTrue(payload.contains("\"timestamp\":1000"))
        assertTrue(payload.contains("\"appType\":\"Android\""))
        assertEquals(1, Json.parseToJsonElement(payload).jsonArray.size)
        assertEquals("/prefix/api/public/insight/track", h.last.request.url.encodedPath)
        assertEquals("event-only", h.last.request.header("X-Event"))
        assertNull(h.last.request.header("X-Gateway"))
        assertEquals("test-key", h.last.request.header("Authorization"))
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        h.close()
    }

    @Test
    public fun evaluationRequiresRemoteConfirmationValidMetadataAndSuccessfulConversion() {
        val h = EventHarness(configure = { it.bootstrap(listOf(flag("bootstrap", "true"))) })
        h.client.stringVariation("bootstrap", "")
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        h.source.sinks.last().full(h.remoteRecord())
        h.client.numberVariation("flag", 0.0)
        h.client.allVariations()
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        assertTrue(h.client.boolVariation("flag", false))
        h.client.stringVariation("flag", "")
        val wait = h.flush()
        val body = h.last.request.payload()
        assertFalse(body.contains("sendToExperiment"))
        assertTrue(body.contains("11111111-1111-1111-1111-111111111111"))
        assertEquals(1, Json.parseToJsonElement(body).jsonArray.size)
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        h.source.sinks.last().full(record("flag", "true", type = "boolean"))
        assertTrue(h.client.boolVariation("flag", false))
        assertEquals(1L, h.diagnostics.lossCounts()["event_invalid_metadata"])
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        h.close()
    }

    @Test
    public fun localSourcesCannotManufactureEvaluationEvents() {
        val h = EventHarness(remote = false)
        h.source.sinks.last().full(h.remoteRecord())
        assertTrue(h.client.boolVariation("flag", false))
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        assertEquals(TrackResult.ACCEPTED, h.client.track("local_metric").value)
        h.close()
    }

    @Test
    public fun distinctGroupsAndUsersAreNeverDeduplicatedAcrossFlush() {
        val h = EventHarness()
        h.client.track("same")
        val first = h.flush()
        h.client.track("same")
        h.client.identify(user("B"), 1000)
        h.workers.drain()
        h.client.track("same")
        val second = h.flush()
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, first.getResult()!!.value)
        assertNull(second.getResult())
        val body = h.last.request.payload()
        assertEquals(2, Json.parseToJsonElement(body).jsonArray.size)
        assertTrue(body.contains("\"keyId\":\"B\""))
        assertTrue(body.contains("\"keyId\":\"A\""))
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, second.getResult()!!.value)
        h.close()
    }

    @Test
    public fun overflowIsOutsideFlushCoverageAndConcurrentLossCountsOnce() {
        val h = EventHarness(capacity = 1)
        h.client.track("accepted")
        assertEquals(OutcomeCode.CAPACITY_EXCEEDED, h.client.track("rejected").code)
        val a = h.flush()
        val b = h.flush()
        h.reply(500)
        h.advance(1_000)
        h.reply(400)
        h.advance(2_000)
        h.reply(429)
        assertEquals(FlushResult.PROCESSED_WITH_LOSS, a.getResult()!!.value)
        assertEquals(FlushResult.PROCESSED_WITH_LOSS, b.getResult()!!.value)
        assertEquals(
            mapOf("event_capacity" to 1L, "event_retry_exhausted" to 1L),
            h.diagnostics.lossCounts(),
        )
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        h.client.track("later")
        val later = h.flush()
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, later.getResult()!!.value)
        h.close()
    }

    @Test
    public fun capacityRejectedCallsDoNotTurnSuccessfulFlushIntoLoss() {
        val h = EventHarness(capacity = 1)
        h.client.track("one")
        h.client.track("two")
        val wait = h.flush()
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        assertEquals(1L, h.diagnostics.lossCounts()["event_capacity"])
        h.close()
    }

    @Test
    public fun offlineRetainsOriginalBatchLateErrorsCannotTerminateOnlineReplay() {
        val h = EventHarness()
        h.client.track("before")
        val oldWait = h.flush()
        val old = h.last
        h.client.setOffline(2000)
        h.workers.drain()
        assertTrue(old.canceled)
        assertEquals(OutcomeCode.DEFERRED, oldWait.getResult()!!.code)
        assertEquals(TrackResult.SUPPRESSED, h.client.track("offline").value)
        assertEquals(OutcomeCode.DEFERRED, h.flush().getResult()!!.code)
        h.client.identify(user("B"), 1000)
        h.client.setOnline(1000)
        h.advance(2000)
        assertEquals(1, h.transport.requests.size)
        old.listener.response(403, "", null)
        h.pump()
        assertEquals(2, h.transport.requests.size)
        assertEquals(old.request.payload(), h.last.request.payload())
        val next = h.flush()
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, next.getResult()!!.value)
        assertEquals(OutcomeCode.DEFERRED, oldWait.getResult()!!.code)
        assertEquals(TrackResult.ACCEPTED, h.client.track("online").value)
        h.close()
    }

    @Test
    public fun terminalDeliveryFinalizesAllAndCannotBeResetButFlagsStillWork() {
        val h = EventHarness()
        h.client.track("sent")
        val wait = h.flush()
        h.client.track("open")
        h.reply(401)
        assertEquals(OutcomeCode.TERMINAL_FAILURE, wait.getResult()!!.code)
        assertEquals(2L, h.diagnostics.lossCounts()["event_terminal_loss"])
        h.client.setOffline(1000)
        h.client.setOnline(1000)
        h.workers.drain()
        assertEquals(TrackResult.SUPPRESSED, h.client.track("later").value)
        assertEquals(OutcomeCode.TERMINAL_FAILURE, h.flush().getResult()!!.code)
        h.source.sinks.last().full(h.remoteRecord())
        assertTrue(h.client.boolVariation("flag", false))
        assertEquals(1, h.transport.requests.size)
        h.close()
    }

    @Test
    public fun waitingTimeoutDoesNotCancelRetainedDeliveryOrPoisonLaterFlush() {
        val h = EventHarness()
        h.client.track("one")
        val first = h.flush()
        h.client.lifecycle(true, false)
        h.advance(5000)
        assertEquals(OutcomeCode.TIMED_OUT, first.getResult()!!.code)
        h.last.listener.response(200, "", null)
        h.client.lifecycle(true, true)
        val next = h.flush()
        h.pump()
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, next.getResult()!!.value)
        h.close()
    }

    @Test
    public fun disableEventsConstructsNoTransportOrLossAndFlushIsDisabled() {
        val h = EventHarness(disabled = true)
        h.source.sinks.last().full(h.remoteRecord())
        h.client.boolVariation("flag", false)
        assertEquals(TrackResult.SUPPRESSED, h.client.track("one").value)
        assertEquals(OutcomeCode.DISABLED, h.flush().getResult()!!.code)
        h.advance(60_000)
        h.close()
        assertTrue(h.transport.requests.isEmpty())
        assertFalse(h.transport.closed)
        assertTrue(h.diagnostics.lossCounts().isEmpty())
    }

    @Test
    public fun backgroundAndForegroundSealSeparateGroupsAndMissedTicksDoNotSplit() {
        val h = EventHarness()
        h.client.track("same")
        h.client.lifecycle(false, true)
        h.client.track("same")
        h.advance(90_000)
        assertEquals(TrackResult.DEDUPLICATED, h.client.track("same").value)
        assertTrue(h.transport.requests.isEmpty())
        h.client.lifecycle(true, true)
        h.client.track("same")
        val wait = h.flush()
        assertEquals(3, Json.parseToJsonElement(h.last.request.payload()).jsonArray.size)
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        h.close()
    }

    @Test
    public fun transitionBudgetExcludesNewBackgroundEventsAndLateAcknowledgement() {
        val h = EventHarness(transition = true)
        h.client.track("before")
        h.client.lifecycle(false, true)
        h.client.track("background")
        h.pump()
        val old = h.last
        assertFalse(old.request.payload().contains("background"))
        h.advance(2000)
        assertTrue(old.canceled)
        old.listener.response(200, "", null)
        h.pump()
        assertEquals(1, h.transport.requests.size)
        h.client.lifecycle(true, true)
        h.advance(2000)
        assertEquals(old.request.payload(), h.last.request.payload())
        h.reply()
        val wait = h.flush()
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        h.close()
    }

    @Test
    public fun offlineCloseReportsLossWithoutSendingAndConcurrentCloseSharesResult() {
        val h = EventHarness()
        h.client.track("one")
        h.client.setOffline(1000)
        h.workers.drain()
        val close = h.client.close()
        assertSame(close, h.client.close())
        h.pump()
        assertEquals(1L, close.getResult()!!.value!!.undeliveredEvents)
        assertTrue(close.getResult()!!.value!!.cleanupComplete)
        assertTrue(h.transport.requests.isEmpty())
    }

    @Test
    public fun closeWaitsForFinalDeliveryAndHasFiniteDeadlineWithUnresponsiveTransport() {
        val h = EventHarness()
        h.client.track("one")
        val close = h.client.close()
        h.pump()
        assertNull(close.getResult())
        h.reply()
        assertEquals(CloseResult(0, true), close.getResult()!!.value)
        val blocked = EventHarness()
        blocked.client.track("one")
        val timed = blocked.client.close()
        blocked.pump()
        blocked.advance(3000)
        assertEquals(CloseResult(1, false), timed.getResult()!!.value)
        assertTrue(blocked.transport.closed)
        assertTrue(blocked.last.canceled)
        blocked.last.listener.response(200, "", null)
        assertEquals(CloseResult(1, false), timed.getResult()!!.value)
    }

    @Test
    public fun physicalBatchesAreLimitedAndPeriodicFlushNeedsNoExplicitWait() {
        val h = EventHarness()
        repeat(75) { h.client.track("event_$it") }
        h.advance(30_000)
        assertEquals(50, Json.parseToJsonElement(h.last.request.payload()).jsonArray.size)
        h.reply()
        assertEquals(25, Json.parseToJsonElement(h.last.request.payload()).jsonArray.size)
        h.reply()
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        h.close()
    }

    @Test
    public fun eventGroupAndPayloadBoundsRejectNewWorkWithoutUnboundedTasks() {
        val h = EventHarness(capacity = 1000)
        h.client.lifecycle(false, true)
        repeat(256) {
            h.client.track("same")
            h.flush()
        }
        assertEquals(OutcomeCode.CAPACITY_EXCEEDED, h.client.track("same").code)
        assertTrue(h.transport.requests.isEmpty())
        assertTrue(h.workers.queue.isEmpty())
        h.close()
    }

    @Test
    public fun expiryAndInvalidMetadataRemainObservableWithoutPollutingNewFlush() {
        val h = EventHarness()
        h.client.track("old")
        h.client.lifecycle(false, true)
        h.advance(86_400_000)
        assertEquals(1L, h.diagnostics.lossCounts()["event_expired"])
        assertEquals(FlushResult.EMPTY, h.flush().getResult()!!.value)
        h.close()
    }

    @Test
    public fun trackOnlyRequiresNonemptyNameAndFiniteValueAndAttributesCompareByMeaning() {
        val h = EventHarness()
        assertEquals(OutcomeCode.INVALID, h.client.track("").code)
        listOf("has space", "a".repeat(129), "你好", " ").forEach {
            assertEquals(TrackResult.ACCEPTED, h.client.track(it).value)
        }
        assertEquals(OutcomeCode.INVALID, h.client.track("finite", Double.NaN).code)
        val a =
            User.builder("same")
                .name("Same")
                .attribute("x", AttributeValue.text("1").value!!)
                .attribute("y", AttributeValue.text("2").value!!)
                .build()
                .value!!
        val b =
            User.builder("same")
                .name("Same")
                .attribute("y", AttributeValue.text("2").value!!)
                .attribute("x", AttributeValue.text("1").value!!)
                .build()
                .value!!
        h.client.identify(a, 1000)
        h.workers.drain()
        h.client.track("same")
        h.client.identify(b, 1000)
        h.workers.drain()
        assertEquals(TrackResult.DEDUPLICATED, h.client.track("same").value)
        val wait = h.flush()
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        h.close()
    }

    @Test
    public fun eventFieldsKeepTheirOriginalFormatAndHaveNoIndividualLengthLimits() {
        val h = EventHarness()
        val key = "用户 / " + "k".repeat(257)
        val name = "用户名称".repeat(100)
        val attribute = " 属性/!? " + "a".repeat(129)
        val value = "value".repeat(500)
        val user =
            User.builder(key)
                .name(name)
                .attribute(attribute, AttributeValue.text(value).value!!)
                .build()
                .value!!
        h.client.identify(user, 1000)
        h.workers.drain()
        val flag = "开关 / " + "f".repeat(129)
        val selectedId = "variation / 自定义"
        h.source.sinks
            .last()
            .full(
                FlagRecord.builder(flag, "true", "boolean", 1)
                    .variationOptions(
                        listOf(
                            VariationOption(selectedId, "true"),
                            VariationOption(selectedId.uppercase(), "false"),
                        )
                    )
                    .build()
                    .value!!
            )
        assertTrue(h.client.boolVariation(flag, false))
        val wait = h.flush()
        val payload =
            Json.parseToJsonElement(h.last.request.payload()).jsonArray.single().jsonObject
        val sentUser = payload.getValue("user").jsonObject
        assertEquals(key, sentUser.getValue("keyId").jsonPrimitive.content)
        assertEquals(name, sentUser.getValue("name").jsonPrimitive.content)
        val property = sentUser.getValue("customizedProperties").jsonArray.single().jsonObject
        assertEquals(attribute, property.getValue("name").jsonPrimitive.content)
        assertEquals(value, property.getValue("value").jsonPrimitive.content)
        val variation = payload.getValue("variations").jsonArray.single().jsonObject
        assertEquals(flag, variation.getValue("featureFlagKey").jsonPrimitive.content)
        assertEquals(
            selectedId,
            variation.getValue("variation").jsonObject.getValue("id").jsonPrimitive.content,
        )
        assertTrue(h.diagnostics.lossCounts().isEmpty())
        h.reply()
        assertEquals(FlushResult.ALL_DELIVERED, wait.getResult()!!.value)
        h.close()
    }
}
