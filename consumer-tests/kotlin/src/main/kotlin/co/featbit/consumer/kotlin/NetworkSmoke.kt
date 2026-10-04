package co.featbit.consumer.kotlin

import android.content.Context
import co.featbit.android.api.*
import co.featbit.android.kotlin.ClientAdapters
import kotlinx.coroutines.*

/** Opt-in device check using only published AAR APIs and suspend adapters. */
object NetworkSmoke {
    fun verify(context: Context, done: (String) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val adapter = ClientAdapters.getDefault()
                for (mode in listOf(SyncMode.STREAMING, SyncMode.POLLING)) {
                    val options =
                        ClientOptions.builder()
                            .user(User.builder("kotlin-phase4").name("Kotlin").build().value!!)
                            .sdkKey("gpnOV3wI3kKAO9q9viC0wQWdKZrVAf2U6gAnxl4lSH3w")
                            .streamingUrl("ws://127.0.0.1:5189")
                            .pollingUrl("http://127.0.0.1:5189")
                            .disableEvents(true)
                            .cacheEnabled(false)
                            .requestTimeoutMillis(1000)
                            .mode(mode)
                            .build()
                            .value!!
                    val client =
                        adapter
                            .await(ClientFactory.getDefault().create(context, options), 10000)
                            .value!!
                    try {
                        check(adapter.await(client.awaitReady(10000), 12000).isSuccess)
                        check(client.boolVariation("returns-true", false))
                        check(
                            adapter
                                .await(
                                    client.identify(
                                        User.builder("kotlin-phase4-B")
                                            .name("Kotlin B")
                                            .build()
                                            .value!!,
                                        10000,
                                    ),
                                    12000,
                                )
                                .isSuccess
                        )
                        check(adapter.await(client.setOffline(2000), 5000).isSuccess)
                        check(adapter.await(client.setOnline(2000), 5000).isSuccess)
                        check(adapter.await(client.awaitReady(10000), 12000).isSuccess)
                    } finally {
                        check(adapter.await(client.close(), 10000).value!!.cleanupComplete)
                    }
                }
                done("PHASE4_PASS Kotlin Streaming/Polling/Identify/Offline/Close")
            } catch (failure: Exception) {
                done("PHASE4_FAIL Kotlin $failure")
            }
        }
    }
}
