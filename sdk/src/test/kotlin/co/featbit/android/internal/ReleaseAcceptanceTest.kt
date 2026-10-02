package co.featbit.android.internal

import co.featbit.android.api.*
import org.junit.Assert.*
import org.junit.Test

public class ReleaseAcceptanceTest {
    @Test public fun disabledOrConflictingAttributesNeverSampleAndUnavailableFieldsAreOmitted() {
        val original = user("A", "starter")
        assertSame(original, enrichUser(original, false) { error("must not collect") }.value)
        val collision = User.builder("A").name("A")
            .attribute("featbit.sdk.version", AttributeValue.text("caller").value!!).build().value!!
        assertEquals(OutcomeCode.INVALID, enrichUser(collision, true) { error("must not collect") }.code)
        val result = enrichUser(original, true) { mapOf("applicationVersion" to null, "deviceModel" to "", "osName" to "Android") }.value!!
        assertEquals(setOf("plan", "featbit.sdk.osName"), result.attributes.keys)
        assertEquals("Android", result.attributes["featbit.sdk.osName"]!!.text)
        assertEquals(setOf("plan"), original.attributes.keys)
    }

    @Test public fun identifyResamplesButForegroundDoesNotAndOldSnapshotsStayUnchanged() {
        val source = ControlledSource(false)
        val clock = FakeClock(); val worker = ManualWorkers(); val dispatch = ManualDispatch(); val ticker = ManualTicker()
        var sampled = 0
        var version = "one"
        val enrich: (User) -> Outcome<User> = { input -> enrichUser(input, true) { sampled++; mapOf("applicationVersion" to version) } }
        val options = ClientOptions.builder().user(user("A")).source(source).disableEvents(true).cacheEnabled(false).automaticAttributes(true).build().value!!
        val client = LocalClient(options, enrich(options.initialUser!!).value!!, source.capabilities(), dispatch, clock, worker, ticker,
            Diagnostics(options, clock, ManualWorkers()), enrich = enrich)
        try {
            client.start(); worker.drain(); source.sinks.last().full()
            val first = source.contexts.last().user
            client.lifecycle(false, true); worker.drain(); client.lifecycle(true, true); worker.drain()
            assertEquals(1, sampled)
            version = "two"
            val identified = client.identify(options.initialUser!!, 1000); worker.drain(); source.sinks.last().full()
            assertTrue(identified.getResult()!!.isSuccess)
            assertEquals(2, sampled)
            assertEquals("two", source.contexts.last().user.attributes["featbit.sdk.applicationVersion"]!!.text)
            assertEquals("one", first.attributes["featbit.sdk.applicationVersion"]!!.text)
            assertNotEquals(contextKey(first), contextKey(source.contexts.last().user))
        } finally { client.close(); worker.drain(); ticker.action() }
    }

    @Test public fun everyLogLevelPreservesLossCountsWithSafeReentrantLogging() {
        for (level in LogLevel.values()) {
            val clock = FakeClock(); val worker = ManualWorkers(); val captured = ArrayList<Diagnostic>()
            lateinit var diagnostics: Diagnostics
            val options = ClientOptions.builder().user(user("recognizable-user", "recognizable-attribute"))
                .sdkKey("recognizable-sdk-key").offline(true).logLevel(level)
                .logger { _, diagnostic ->
                    captured.add(diagnostic)
                    diagnostics.report("event_capacity") // reentry must not recursively enqueue
                    throw IllegalStateException("recognizable-logger-secret")
                }.build().value!!
            diagnostics = Diagnostics(options, clock, worker)
            repeat(1000) { diagnostics.loss("event_capacity") }
            assertEquals(1000L, diagnostics.lossCounts()["event_capacity"])
            assertTrue(worker.queue.size <= 1)
            worker.drain()
            assertEquals(if (level.ordinal >= LogLevel.WARN.ordinal) 1 else 0, captured.size)
            assertTrue(captured.all { it.code == "event_capacity" && it.field == "1000" })
            diagnostics.close(); worker.drain()
        }
    }
}
