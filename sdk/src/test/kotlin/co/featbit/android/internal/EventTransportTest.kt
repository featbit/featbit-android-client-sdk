package co.featbit.android.internal

import co.featbit.android.api.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

public class EventTransportTest {
    private fun client(server: MockWebServer): LocalClient {
        val source = ControlledSource()
        val options = ClientOptions.builder().user(user("http", "secret")).sdkKey("test-key").source(source)
            .eventsUrl(server.boundUrl("/prefix").toString()).cacheEnabled(false).privateAttribute("plan")
            .eventHeader("X-Event", "only-event").requestTimeoutMillis(1000).closeTimeoutMillis(1000).build().value!!
        return LocalClient(options, options.initialUser!!, source.capabilities(), Dispatch { it() }, JvmClock,
            BoundedWorkers(), DeadlineTicker(), Diagnostics(options, JvmClock), random = { 0.0 }).also { it.start() }
    }
    @Test public fun realHttpRetriesIdenticalFilteredPayloadAndHonors2xx() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(MockResponse().setResponseCode(202))
            val client = client(server)
            try {
                client.track("purchase", 2.0)
                assertEquals(FlushResult.ALL_DELIVERED, await(client.flush()).value)
                val first = server.takeRequest(5, TimeUnit.SECONDS)!!
                val retry = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/prefix/api/public/insight/track", first.path)
                assertEquals("only-event", first.getHeader("X-Event")); assertNull(first.getHeader("X-Gateway"))
                assertEquals("test-key", first.getHeader("Authorization"))
                val payload = first.body.readUtf8(); assertEquals(payload, retry.body.readUtf8()); assertFalse(payload.contains("secret"))
                assertEquals(2, server.requestCount)
            } finally { assertTrue(await(client.close()).value!!.cleanupComplete) }
        }
    }
    @Test public fun realRedirectsDoNotForwardCredentialsAndRetryExhaustionIsNotTerminal() {
        MockWebServer().use { server -> MockWebServer().use { target ->
            server.start(); target.start()
            repeat(3) { server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/leak"))) }
            val client = client(server)
            try {
                client.track("one")
                assertEquals(FlushResult.PROCESSED_WITH_LOSS, await(client.flush()).value)
                assertEquals(3, server.requestCount); assertEquals(0, target.requestCount)
                server.enqueue(MockResponse().setResponseCode(204)); client.track("two")
                assertEquals(FlushResult.ALL_DELIVERED, await(client.flush()).value)
            } finally { await(client.close()) }
        } }
    }
    @Test public fun realHungRequestTimeoutCancelsAndAllowsLaterRetry() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setResponseCode(200))
            val client = client(server)
            try {
                client.track("one")
                assertEquals(OutcomeCode.TIMED_OUT, await(client.flush()).code)
                assertNotNull("Initial hung request was not received", server.takeRequest(5, TimeUnit.SECONDS))
                assertNotNull("Retry after cancellation was not received", server.takeRequest(5, TimeUnit.SECONDS))
                val later = await(client.flush())
                assertTrue(later.value in setOf(FlushResult.EMPTY, FlushResult.ALL_DELIVERED))
            } finally { await(client.close()) }
        }
    }
}
