package co.featbit.android.internal

import co.featbit.android.api.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit opt-in. Captures actual Android requests for the target-domain verifier. */
public class LiveEventIntegrationTest {
    @Test public fun eventsAgainstTargetAndExportExactPayloadForDomainValidation() {
        val captured = java.util.Collections.synchronizedList(ArrayList<String>())
        val options = ClientOptions.builder().user(user("android-phase5", "do-not-retain"))
            .sdkKey("gpnOV3wI3kKAO9q9viC0wQWdKZrVAf2U6gAnxl4lSH3w")
            .pollingUrl("http://127.0.0.1:5189").mode(SyncMode.POLLING)
            .eventsUrl("http://127.0.0.1:5189").privateAttribute("plan").cacheEnabled(false).build().value!!
        val transport = OkHttpSyncTransport(readResponseBody = false)
        val capture = object : SyncTransport {
            override fun start(request: Request, streaming: Boolean, listener: SyncListener): SyncHandle {
                captured.add(request.payload()); return transport.start(request, streaming, listener)
            }
            override fun close() = transport.close()
        }
        val client = LocalClient(options, options.initialUser!!, null, Dispatch { it() }, JvmClock,
            BoundedWorkers(), DeadlineTicker(), Diagnostics(options, JvmClock), eventTransportFactory = { capture }).also { it.start() }
        try {
            assertEquals(OutcomeCode.SUCCESS, await(client.awaitReady(5000)).code)
            assertTrue(client.boolVariation("returns-true", false))
            assertEquals(TrackResult.ACCEPTED, client.track("android_purchase", 2.5).value)
            assertEquals(FlushResult.ALL_DELIVERED, await(client.flush()).value)
            client.track("final_close")
            assertEquals(CloseResult(0, true), await(client.close()).value)
            val payload = captured.joinToString(",", "[", "]") { it.removePrefix("[").removeSuffix("]") }
            assertFalse(payload.contains("sendToExperiment")); assertFalse(payload.contains("do-not-retain"))
            assertTrue(payload.contains("variations")); assertTrue(payload.contains("Android"))
            File(requireNotNull(System.getProperty("featbit.liveEventPayload")) {
                "Run live integration tests with -PliveIntegration"
            }).writeText(payload)
        } finally { await(client.close()) }
    }
}
