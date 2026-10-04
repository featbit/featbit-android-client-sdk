package co.featbit.android.internal

import co.featbit.android.api.*
import org.junit.Assert.*
import org.junit.Test

public class EndpointTest {
    private fun options(
        stream: String,
        poll: String,
        events: String,
        offline: Boolean = false,
    ): Outcome<ClientOptions> =
        ClientOptions.builder()
            .user(user("A"))
            .sdkKey("test-key")
            .streamingUrl(stream)
            .pollingUrl(poll)
            .pollingFallback(true)
            .eventsUrl(events)
            .offline(offline)
            .build()

    @Test
    public fun mixedCaseSchemesShareValidationRequestsAndCacheNamespace() {
        val prefix = "://EXAMPLE.test:8443/Deploy/API%2Fv1/"
        for ((ws, http) in listOf("ws" to "http", "wss" to "https")) {
            val lower = options(ws + prefix, http + prefix, http + prefix).value!!
            for ((stream, poll) in
                listOf(
                    ws.uppercase() to http.uppercase(),
                    ws.replaceFirstChar { it.uppercase() } to
                        http.replaceFirstChar { it.uppercase() },
                )) {
                val built = options(stream + prefix, poll + prefix, poll + prefix)
                assertTrue(built.isSuccess)
                val mixed = built.value!!
                assertNull(onlineError(mixed))
                for (streaming in listOf(true, false)) {
                    val request = SyncProtocol.request(mixed, streaming, 7, user("A"), 1000, 0.5)
                    assertEquals(
                        SyncProtocol.request(lower, streaming, 7, user("A"), 1000, 0.5).url,
                        request.url,
                    )
                    assertEquals(http, request.url.scheme)
                    assertTrue(request.url.encodedPath.startsWith("/Deploy/API%2Fv1/"))
                }
                assertEquals(
                    EventProtocol.request(lower, "[]").url,
                    EventProtocol.request(mixed, "[]").url,
                )
                assertEquals(
                    "/Deploy/API%2Fv1/api/public/insight/track",
                    EventProtocol.request(mixed, "[]").url.encodedPath,
                )
                assertNotNull(cacheNamespace(mixed, null))
                assertEquals(cacheNamespace(lower, null), cacheNamespace(mixed, null))
            }
        }
    }

    @Test
    public fun pathCaseStillSeparatesRequestsAndCacheNamespaces() {
        val upper =
            options("WSS://host/Deploy", "HTTPS://host/Deploy", "HTTPS://host/Deploy").value!!
        val lower =
            options("wss://host/deploy", "https://host/deploy", "https://host/deploy").value!!
        assertNotEquals(cacheNamespace(upper, null), cacheNamespace(lower, null))
        assertNotEquals(
            SyncProtocol.request(upper, true, 0, user("A"), 1000, 0.5).url,
            SyncProtocol.request(lower, true, 0, user("A"), 1000, 0.5).url,
        )
        assertNotEquals(
            EventProtocol.request(upper, "[]").url,
            EventProtocol.request(lower, "[]").url,
        )
    }

    @Test
    public fun invalidEndpointsRemainRejectedByBuilderAndOnlineTransition() {
        for (suffix in
            listOf(
                "://",
                "://name:password@host/Deploy",
                "://host/Deploy?query=value",
                "://host/Deploy#fragment",
                "://host/bad path",
            )) {
            val online = options("WSS$suffix", "HTTPS://host", "HTTPS://host")
            assertFalse(online.isSuccess)
            assertNotNull(
                onlineError(options("WSS$suffix", "HTTPS://host", "HTTPS://host", true).value!!)
            )
            assertFalse(options("WSS://host", "HTTPS$suffix", "HTTPS://host").isSuccess)
            assertNotNull(
                onlineError(options("WSS://host", "HTTPS$suffix", "HTTPS://host", true).value!!)
            )
            assertFalse(options("WSS://host", "HTTPS://host", "HTTPS$suffix").isSuccess)
            assertNotNull(
                onlineError(options("WSS://host", "HTTPS://host", "HTTPS$suffix", true).value!!)
            )
        }
        assertFalse(options("HTTPS://host", "HTTPS://host", "HTTPS://host").isSuccess)
        assertFalse(options("WSS://host", "WSS://host", "HTTPS://host").isSuccess)
        assertFalse(options("WSS://host", "HTTPS://host", "WSS://host").isSuccess)
    }

    @Test
    public fun offlineCreationDefersValidationAndMixedCaseCanGoOnline() {
        val valid =
            options("WsS://host/Deploy", "HtTpS://host/Deploy", "HtTpS://host/Deploy", true).value!!
        val missing = ClientOptions.builder().user(user("A")).offline(true).build().value!!
        val invalid = options("HTTPS://host", "HTTPS://host", "HTTPS://host", true).value!!
        for (config in listOf(valid, missing, invalid)) {
            val clock = FakeClock()
            val workers = ManualWorkers()
            val ticker = ManualTicker()
            val transport = FakeTransport()
            val client =
                LocalClient(
                    config,
                    config.initialUser!!,
                    null,
                    ManualDispatch(),
                    clock,
                    workers,
                    ticker,
                    Diagnostics(config, clock, ManualWorkers()),
                    transportFactory = { transport },
                )
            try {
                client.start()
                workers.drain()
                assertTrue(transport.requests.isEmpty())
                assertEquals(config === valid, client.setOnline(1000).getResult()!!.isSuccess)
                workers.drain()
                assertEquals(config !== valid, client.isOffline())
                assertEquals(if (config === valid) 1 else 0, transport.requests.size)
                if (config === valid)
                    assertEquals(
                        "/Deploy/streaming",
                        transport.requests.single().request.url.encodedPath,
                    )
            } finally {
                client.close()
                workers.drain()
                ticker.action()
            }
        }
    }
}
