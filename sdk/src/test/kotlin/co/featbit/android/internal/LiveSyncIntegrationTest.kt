package co.featbit.android.internal

import co.featbit.android.api.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Explicit opt-in; targets the evaluation server's checked-in Fake data provider. */
public class LiveSyncIntegrationTest {
    private fun options(mode: SyncMode): ClientOptions =
        ClientOptions.builder()
            .user(user("3db19c81-e149-4b97-8a0d-79d34531fe59"))
            // Public fictional fixture in evaluation-server/src/Domain/Shared/FakeSeedData.cs.
            .sdkKey("gpnOV3wI3kKAO9q9viC0wQWdKZrVAf2U6gAnxl4lSH3w")
            .streamingUrl("ws://127.0.0.1:5189")
            .pollingUrl("http://127.0.0.1:5189")
            .mode(mode)
            .disableEvents(true)
            .cacheEnabled(false)
            .pollingIntervalMillis(1000)
            .requestTimeoutMillis(1000)
            .build()
            .value!!

    private fun verify(mode: SyncMode) {
        val client = networkClient(options(mode))
        try {
            assertEquals(OutcomeCode.SUCCESS, await(client.awaitReady(5000)).code)
            val values = client.allVariations()
            assertTrue(values.isNotEmpty())
            assertEquals("true", values["returns-true"]?.value)
            assertEquals(OutcomeCode.SUCCESS, await(client.identify(user("phase4-B"), 5000)).code)
            assertTrue(client.getConnectionInformation().remoteConfirmed)
            assertEquals(
                OutcomeCode.SUCCESS,
                await(
                        client.identify(
                            user("3db19c81-e149-4b97-8a0d-79d34531fe59", "updated"),
                            5000,
                        )
                    )
                    .code,
            )
            assertTrue(client.allVariations().isNotEmpty())
            assertEquals(OutcomeCode.SUCCESS, await(client.setOffline(2000)).code)
            assertEquals(OutcomeCode.SUCCESS, await(client.setOnline(2000)).code)
            assertEquals(OutcomeCode.SUCCESS, await(client.awaitReady(5000)).code)
        } finally {
            assertTrue(await(client.close()).value!!.cleanupComplete)
        }
    }

    @Test
    public fun streamingAgainstTargetServer() {
        verify(SyncMode.STREAMING)
    }

    @Test
    public fun pollingAgainstTargetServer() {
        verify(SyncMode.POLLING)
    }

    @Test
    public fun targetServerStreamingOutagePollingAndStreamingRecovery() {
        val offset = AtomicLong()
        val clock =
            object : Clock {
                override fun elapsed() = JvmClock.elapsed() + offset.get()

                override fun wall() = JvmClock.wall()
            }
        val underlying = OkHttpSyncTransport()
        val failed = CountDownLatch(1)
        val io =
            object : SyncTransport {
                @Volatile var streamingUnavailable = true

                override fun start(
                    request: Request,
                    streaming: Boolean,
                    listener: SyncListener,
                ): SyncHandle {
                    if (streaming && streamingUnavailable) {
                        listener.failed(null)
                        failed.countDown()
                        return object : SyncHandle {
                            override fun cancel() = Unit

                            override fun send(text: String) = false
                        }
                    }
                    return underlying.start(request, streaming, listener)
                }

                override fun close() {
                    underlying.close()
                }
            }
        val options =
            ClientOptions.builder()
                .user(user("A"))
                .sdkKey("gpnOV3wI3kKAO9q9viC0wQWdKZrVAf2U6gAnxl4lSH3w")
                .streamingUrl("ws://127.0.0.1:5189")
                .pollingUrl("http://127.0.0.1:5189")
                .pollingFallback(true)
                .disableEvents(true)
                .cacheEnabled(false)
                .build()
                .value!!
        val ticker = ManualTicker()
        val client =
            LocalClient(
                    options,
                    options.initialUser!!,
                    null,
                    Dispatch { it() },
                    clock,
                    BoundedWorkers(),
                    ticker,
                    Diagnostics(options, clock),
                    transportFactory = { io },
                )
                .also { it.start() }
        try {
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            offset.addAndGet(30_001)
            ticker.action()
            assertEquals(OutcomeCode.SUCCESS, await(client.awaitReady(5000)).code)
            assertEquals(SyncMode.POLLING, client.getConnectionInformation().effectiveMode)
            val recovered = CountDownLatch(1)
            client.subscribeStatus { status ->
                if (status.effectiveMode == SyncMode.STREAMING && status.remoteConfirmed)
                    recovered.countDown()
            }
            io.streamingUnavailable = false
            offset.addAndGet(60_001)
            val start = System.nanoTime()
            ticker.action()
            assertTrue(recovered.await(5, TimeUnit.SECONDS))
            assertEquals("true", client.stringVariation("returns-true", ""))
            println(
                "Target server candidate update gap millis=" +
                    (System.nanoTime() - start) / 1_000_000
            )
        } finally {
            val close = client.close()
            // A real ticker is unnecessary for accelerated recovery, but cleanup settles on a tick.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (close.getResult() == null && System.nanoTime() < deadline) {
                ticker.action()
                Thread.sleep(5)
            }
            assertTrue(close.getResult()!!.value!!.cleanupComplete)
        }
    }
}
