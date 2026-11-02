package co.featbit.consumer.kotlin

import android.content.Context
import android.util.Log
import co.featbit.android.api.*
import co.featbit.android.kotlin.ClientAdapters
import kotlinx.coroutines.*

/** Opt-in debug device probe. Credentials are supplied at runtime, never saved in source. */
object LiveSyncSmoke {
    fun verify(context: Context, key: String, base: String, done: (String) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val adapter = ClientAdapters.getDefault()
                for (mode in listOf(SyncMode.STREAMING, SyncMode.POLLING)) {
                    val identity = "android-live-sync-${mode.name}"
                    fun options(offline: Boolean) =
                        ClientOptions.builder()
                            .user(User.builder(identity).name(identity).build().value!!)
                            .sdkKey(key)
                            .streamingUrl(base.replaceFirst("http", "ws"))
                            .pollingUrl(base)
                            .disableEvents(true)
                            .pollingIntervalMillis(1000)
                            .requestTimeoutMillis(5000)
                            .mode(mode)
                            .offline(offline)
                            .build()
                            .value!!
                    suspend fun create(offline: Boolean) =
                        adapter
                            .await(
                                ClientFactory.getDefault().create(context, options(offline)),
                                10000,
                            )
                            .value!!
                    suspend fun ready(client: FeatBitClient) {
                        check(adapter.await(client.awaitReady(10000), 12000).isSuccess)
                        check(client.allVariations().isNotEmpty())
                        check(client.getConnectionInformation().remoteConfirmed)
                    }
                    suspend fun close(client: FeatBitClient) {
                        check(adapter.await(client.close(), 10000).value!!.cleanupComplete)
                    }
                    val first = create(false)
                    val expected =
                        try {
                            ready(first)
                            Log.i(
                                "FeatBitConsumer",
                                "LIVE_CHECK $mode startup flags=${first.allVariations().size}",
                            )
                            first.allVariations().mapValues { it.value.value }
                        } finally {
                            close(first)
                        }
                    val client = create(true)
                    try {
                        withTimeout(10000) {
                            while (
                                client.allVariations().mapValues { it.value.value } != expected
                            ) delay(50)
                        }
                        check(!client.getConnectionInformation().remoteConfirmed)
                        Log.i("FeatBitConsumer", "LIVE_CHECK $mode persisted-cache")
                        check(adapter.await(client.setOnline(2000), 5000).isSuccess)
                        ready(client)
                        for (id in listOf("$identity-B", identity)) {
                            check(
                                adapter
                                    .await(
                                        client.identify(
                                            User.builder(id).name(id).build().value!!,
                                            10000,
                                        ),
                                        12000,
                                    )
                                    .isSuccess
                            )
                            ready(client)
                        }
                        Log.i("FeatBitConsumer", "LIVE_CHECK $mode identify-A-B-A")
                        // Debug-only acceleration: change the elapsed deadline under the SDK gate.
                        // Networking, decoding, full commit and the next jittered deadline stay
                        // real.
                        fun field(target: Any, name: String) =
                            target.javaClass.getDeclaredField(name).apply { isAccessible = true }
                        val gate = field(client, "gate").get(client)!!
                        val online = field(client, "online").get(client)!!
                        val next = field(online, "nextFullAt")
                        val force = field(online, "forceFull")
                        synchronized(gate) {
                            check(next.getLong(online) > android.os.SystemClock.elapsedRealtime())
                            next.setLong(online, 0)
                        }
                        withTimeout(10000) {
                            while (
                                !synchronized(gate) {
                                    next.getLong(online) >
                                        android.os.SystemClock.elapsedRealtime() &&
                                        !force.getBoolean(online)
                                }
                            ) delay(50)
                        }
                        check(client.allVariations().mapValues { it.value.value } == expected)
                        Log.i("FeatBitConsumer", "LIVE_CHECK $mode accelerated-periodic-full")
                        check(adapter.await(client.setOffline(2000), 5000).isSuccess)
                        check(client.allVariations().mapValues { it.value.value } == expected)
                        check(adapter.await(client.setOnline(2000), 5000).isSuccess)
                        ready(client)
                        Log.i("FeatBitConsumer", "LIVE_CHECK $mode offline-online")
                    } finally {
                        close(client)
                    }
                }
                done(
                    "LIVE_SYNC_PASS Streaming/Polling/cache/Identify/accelerated-full/offline/close"
                )
            } catch (failure: Exception) {
                Log.e("FeatBitConsumer", "Live probe failed", failure)
                done("LIVE_SYNC_FAIL ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
    }
}
