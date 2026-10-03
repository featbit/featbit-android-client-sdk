package co.featbit.consumer.kotlin

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import co.featbit.android.api.*
import co.featbit.android.testing.TestDataFactory
import org.json.JSONObject

/** Explicit test-only control surface. Exercises the installed AAR without internal hooks. */
class LifecycleProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Log.i("FeatBitPhase6", JSONObject().put("kind", "activity-created").put("pid", android.os.Process.myPid()).toString())
        setContentView(TextView(this).apply { text = "Platform lifecycle checks\nAutomated foreground/background, network transition and sleep recovery checks." })
    }
    override fun onMultiWindowModeChanged(inMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(inMultiWindowMode, newConfig)
        Log.i("FeatBitPhase6", JSONObject().put("kind", "multi-window").put("enabled", inMultiWindowMode).toString())
    }
}

class LifecycleProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { LifecycleProbe.command(context.applicationContext, intent) }
}

private object LifecycleProbe {
    private var client: FeatBitClient? = null
    private var version = 0
    private fun emit(kind: String, detail: String = "") {
        val info = client?.getConnectionInformation()
        Log.i("FeatBitPhase6", JSONObject().put("kind", kind).put("detail", detail)
            .put("instance", version).put("pid", android.os.Process.myPid())
            .put("background", info?.pauseReasons?.contains(PauseReason.BACKGROUND))
            .put("networkPaused", info?.pauseReasons?.contains(PauseReason.NETWORK_UNAVAILABLE))
            .put("offline", client?.isOffline()).put("confirmed", info?.remoteConfirmed)
            .put("status", info?.status?.name).put("value", client?.stringVariation("phase6", "fallback")).toString())
    }
    fun command(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command") ?: "snapshot"
        when (command) {
            "create" -> {
                if (client != null) { emit("error", "already_created"); return }
                val local = intent.getBooleanExtra("local", false)
                val user = User.builder("phase6-user").name("Phase 6").build().value!!
                val options = if (local) TestDataFactory.getDefault().create(listOf(
                    BootstrapFlag.create("phase6", "local", ValueType.STRING).value!!)).value!!.clientOptions(user).value!!
                else ClientOptions.builder().user(user).sdkKey("phase6-key").mode(SyncMode.POLLING)
                    .pollingUrl("http://127.0.0.1:5196").eventsUrl("http://127.0.0.1:5196")
                    .cacheEnabled(false).disableEvents(intent.getBooleanExtra("disabled", false))
                    .backgroundPolling(intent.getBooleanExtra("background", false))
                    .transitionFlush(intent.getBooleanExtra("transition", false))
                    .pollingIntervalMillis(1_000).backgroundPollingIntervalMillis(900_000)
                    .requestTimeoutMillis(5_000).closeTimeoutMillis(1_000).build().value!!
                ClientFactory.getDefault().create(context, options).observe { outcome ->
                    client = outcome.value; version++
                    emit("created", outcome.code.name)
                    client?.subscribeStatus { emit("status") }
                }
            }
            "snapshot" -> emit("snapshot", intent.getStringExtra("token") ?: "")
            "identify" -> client?.identify(User.builder("phase6-other").name("Other").build().value!!,
                intent.getStringExtra("waitMillis")?.toLongOrNull() ?: 1_000)?.observe { emit("identify", it.code.name) }
            "await" -> client?.awaitReady(1_000)?.observe { emit("await", it.code.name) }
            "offline" -> client?.setOffline(1_000)?.observe { emit("offline", it.code.name) }
            "online" -> client?.setOnline(1_000)?.observe { emit("online", it.code.name) }
            "track" -> { client?.track(intent.getStringExtra("name") ?: "phase6-event"); emit("track") }
            "flush" -> client?.flush()?.observe { emit("flush", "${it.code}:${it.value}") }
            "close" -> client?.close()?.observe { emit("closed", "${it.code}:${it.value?.cleanupComplete}") }
            else -> emit("error", "unknown_command")
        }
    }
}
