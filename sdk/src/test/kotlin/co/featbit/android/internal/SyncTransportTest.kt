package co.featbit.android.internal

import co.featbit.android.api.*
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

internal object JvmClock : Clock {
    override fun elapsed() = System.nanoTime() / 1_000_000

    override fun wall() = System.currentTimeMillis()
}

internal fun networkClient(
    options: ClientOptions,
    clock: Clock = JvmClock,
    ticker: Ticker = DeadlineTicker(),
): LocalClient =
    LocalClient(
            options,
            options.initialUser!!,
            null,
            Dispatch { it() },
            clock,
            BoundedWorkers(),
            ticker,
            Diagnostics(options, clock),
        )
        .also { it.start() }

internal fun <T> await(operation: Operation<T>, timeout: Long = 10_000): Outcome<T> {
    val latch = CountDownLatch(1)
    operation.observe { latch.countDown() }
    check(latch.await(timeout, TimeUnit.MILLISECONDS)) { "Operation did not complete" }
    return operation.getResult()!!
}

// A failed localhost route can send later attempts to ::1 although the fixture only binds IPv4.
// Pin the listening address so timeout/retry tests exercise the same server on every attempt.
internal fun MockWebServer.boundUrl(path: String): HttpUrl =
    url(path).newBuilder().host(InetAddress.getByName(hostName).hostAddress!!).build()

internal fun networkOptions(server: MockWebServer, mode: SyncMode): ClientOptions =
    ClientOptions.builder()
        .user(user("A"))
        .sdkKey("fake-key")
        .mode(mode)
        .disableEvents(true)
        .cacheEnabled(false)
        .streamingUrl(server.boundUrl("/prefix").toString().replaceFirst("http", "ws"))
        .pollingUrl(server.boundUrl("/prefix").toString())
        .pollingIntervalMillis(1000)
        .requestTimeoutMillis(2000)
        .synchronizationHeader("X-Gateway", "scope-sync")
        .eventHeader("X-Event", "scope-events")
        .build()
        .value!!

public class SyncTransportTest {
    @Test
    public fun realHttpUsesRawAuthorizationAndRejectsEveryRedirect() {
        MockWebServer().use { server ->
            MockWebServer().use { target ->
                server.start()
                target.start()
                val io = OkHttpSyncTransport()
                try {
                    for (code in listOf(301, 302, 303, 307, 308)) {
                        for (location in
                            listOf(
                                server.url("/same").toString(),
                                target.url("/other").toString(),
                            )) {
                            server.enqueue(
                                MockResponse().setResponseCode(code).setHeader("Location", location)
                            )
                            val done = CountDownLatch(1)
                            var result = 0
                            val options = networkOptions(server, SyncMode.POLLING)
                            io.start(
                                SyncProtocol.request(
                                    options,
                                    false,
                                    12,
                                    user("A"),
                                    JvmClock.wall(),
                                    0.5,
                                ),
                                false,
                                object : SyncListener {
                                    override fun opened() = Unit

                                    override fun message(text: String) = Unit

                                    override fun response(
                                        status: Int,
                                        body: String,
                                        retryAfter: String?,
                                    ) {
                                        result = status
                                        done.countDown()
                                    }

                                    override fun failed(status: Int?, retryAfter: String?) {
                                        done.countDown()
                                    }

                                    override fun ended(code: Int) = Unit
                                },
                            )
                            assertTrue(done.await(5, TimeUnit.SECONDS))
                            assertEquals(code, result)
                            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                            assertEquals("POST", request.method)
                            assertEquals(
                                "/prefix/api/public/sdk/client/latest-all?timestamp=12",
                                request.path,
                            )
                            assertEquals("fake-key", request.getHeader("Authorization"))
                            assertEquals("scope-sync", request.getHeader("X-Gateway"))
                            assertNull(request.getHeader("X-Event"))
                            assertTrue(request.body.readUtf8().contains("\"keyId\":\"A\""))
                        }
                    }
                    assertEquals(10, server.requestCount)
                    assertEquals(0, target.requestCount)
                } finally {
                    io.close()
                }
            }
        }
    }

    @Test
    public fun realWebSocketFramesSynchronizeAndIdentifyOpensNewSocket() {
        MockWebServer().use { server ->
            server.start()
            val received = java.util.concurrent.LinkedBlockingQueue<String>()
            fun socket(user: String, value: String) =
                MockResponse()
                    .withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                received.add(text)
                                webSocket.send(envelope(user, value))
                            }
                        }
                    )
            server.enqueue(socket("A", "first"))
            server.enqueue(socket("B", "second"))
            val client = networkClient(networkOptions(server, SyncMode.STREAMING))
            try {
                assertEquals(ReadyResult.REMOTE_CONFIRMED, await(client.awaitReady(5000)).value)
                assertEquals("first", client.stringVariation("flag", ""))
                val first = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("scope-sync", first.getHeader("X-Gateway"))
                assertNull(first.getHeader("X-Event"))
                assertTrue(first.path!!.startsWith("/prefix/streaming?type=client&token="))
                assertTrue(received.poll(5, TimeUnit.SECONDS)!!.contains("\"keyId\":\"A\""))
                assertEquals(OutcomeCode.SUCCESS, await(client.identify(user("B"), 5000)).code)
                assertEquals("second", client.stringVariation("flag", ""))
                assertTrue(received.poll(5, TimeUnit.SECONDS)!!.contains("\"keyId\":\"B\""))
                assertEquals(2, server.requestCount)
            } finally {
                assertTrue(await(client.close()).value!!.cleanupComplete)
            }
        }
    }

    @Test
    public fun realPollingTimeoutRecoversAndCloseRetainsData() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setBody(envelope(value = "recovered")))
            val client = networkClient(networkOptions(server, SyncMode.POLLING))
            try {
                assertEquals(OutcomeCode.TIMED_OUT, await(client.awaitReady(100)).code)
                assertEquals(OutcomeCode.SUCCESS, await(client.awaitReady(8000)).code)
                assertEquals("recovered", client.stringVariation("flag", ""))
            } finally {
                await(client.close())
            }
            assertEquals("recovered", client.stringVariation("flag", ""))
        }
    }

    @Test
    public fun webSocketRedirectIsNotFollowed() {
        MockWebServer().use { server ->
            MockWebServer().use { target ->
                server.start()
                target.start()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", target.url("/streaming"))
                )
                val io = OkHttpSyncTransport()
                val done = CountDownLatch(1)
                var receivedStatus: Int? = null
                try {
                    val options = networkOptions(server, SyncMode.STREAMING)
                    io.start(
                        SyncProtocol.request(options, true, 0, user("A"), JvmClock.wall(), 0.5),
                        true,
                        object : SyncListener {
                            override fun opened() = Unit

                            override fun message(text: String) = Unit

                            override fun response(status: Int, body: String, retryAfter: String?) =
                                Unit

                            override fun failed(status: Int?, retryAfter: String?) {
                                receivedStatus = status
                                done.countDown()
                            }

                            override fun ended(code: Int) = Unit
                        },
                    )
                    assertTrue(done.await(5, TimeUnit.SECONDS))
                    assertEquals(302, receivedStatus)
                    assertEquals(1, server.requestCount)
                    assertEquals(0, target.requestCount)
                } finally {
                    io.close()
                }
            }
        }
    }
}
