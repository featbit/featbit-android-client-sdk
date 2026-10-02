package co.featbit.consumer.kotlin

import android.content.Context
import co.featbit.android.api.*
import co.featbit.android.kotlin.ClientAdapters
import kotlinx.coroutines.*

object EventSmoke {
    fun verify(context: Context, done: (String) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val adapter = ClientAdapters.getDefault()
                val options = ClientOptions.builder()
                    .user(User.builder("kotlin-phase5").name("Kotlin").attribute("private", AttributeValue.text("hidden").value!!).build().value!!)
                    .sdkKey("gpnOV3wI3kKAO9q9viC0wQWdKZrVAf2U6gAnxl4lSH3w")
                    .pollingUrl("http://127.0.0.1:5189").mode(SyncMode.POLLING).eventsUrl("http://127.0.0.1:5189")
                    .allAttributesPrivate(true).cacheEnabled(false).build().value!!
                val client = adapter.await(ClientFactory.getDefault().create(context, options), 10000).value!!
                try {
                    check(client.track("before_ready").value == TrackResult.ACCEPTED)
                    check(client.track("before_ready").value == TrackResult.DEDUPLICATED)
                    check(adapter.await(client.awaitReady(10000), 12000).isSuccess)
                    check(client.boolVariation("returns-true", false))
                    check(adapter.await(client.flush(), 12000).value == FlushResult.ALL_DELIVERED)
                    client.track("retained")
                    check(adapter.await(client.setOffline(2000), 5000).isSuccess)
                    check(client.track("offline").value == TrackResult.SUPPRESSED)
                    check(adapter.await(client.flush(), 5000).code == OutcomeCode.DEFERRED)
                    check(adapter.await(client.setOnline(2000), 5000).isSuccess)
                    check(adapter.await(client.flush(), 12000).value in setOf(FlushResult.EMPTY, FlushResult.ALL_DELIVERED))
                    client.track("final_close")
                } finally {
                    val closed = adapter.await(client.close(), 10000).value!!
                    check(closed.cleanupComplete && closed.undeliveredEvents == 0L)
                }
                done("PHASE5_PASS Kotlin Track/Evaluation/Privacy/Flush/Offline/Close")
            } catch (failure: Exception) { done("PHASE5_FAIL Kotlin $failure") }
        }
    }
}
