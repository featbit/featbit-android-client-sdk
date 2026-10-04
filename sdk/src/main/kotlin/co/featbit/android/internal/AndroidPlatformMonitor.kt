package co.featbit.android.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Application context only. All native registration, snapshots and detachment run on main. */
internal class AndroidPlatformMonitor(
    private val context: Context,
    private val diagnostics: Diagnostics,
) {
    val relay = PlatformStateRelay()
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var lifecycle: Lifecycle? = null
    private var connectivity: ConnectivityManager? = null
    private var networkRegistered = false
    private var powerRegistered = false
    private val networks = AvailableNetworks<Network>()
    private val observer = LifecycleEventObserver { owner, _ ->
        relay.update {
            it.copy(foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    }
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                post {
                    if (networkRegistered)
                        relay.update { it.copy(network = networks.available(network)) }
                }
            }

            override fun onLost(network: Network) {
                post {
                    if (networkRegistered)
                        relay.update { it.copy(network = networks.lost(network)) }
                }
            }
        }
    private val powerReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updatePower()
            }
        }

    private fun post(action: () -> Unit) {
        handler.post { if (!closed.get()) action() }
    }

    fun start(ready: () -> Unit, failed: () -> Unit) = post {
        try {
            lifecycle =
                ProcessLifecycleOwner.get().lifecycle.also {
                    it.addObserver(observer)
                    relay.update { state ->
                        state.copy(foreground = it.currentState.isAtLeast(Lifecycle.State.STARTED))
                    }
                }
            // Register and take the snapshot in the same main task. Native callbacks are posted
            // behind it, so a queued loss can never be overwritten by initial state adoption.
            try {
                connectivity =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                connectivity?.let { manager ->
                    val request =
                        NetworkRequest.Builder()
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build()
                    manager.registerNetworkCallback(request, callback)
                    networkRegistered = true
                    @Suppress("DEPRECATION") // API 21 snapshot; later changes use callbacks.
                    val initialNetworks =
                        manager.allNetworks.filter { network ->
                            manager
                                .getNetworkCapabilities(network)
                                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        }
                    networks.initialize(initialNetworks)
                    relay.update { it.copy(network = networks.available) }
                }
            } catch (_: Exception) {
                if (networkRegistered)
                    try {
                        connectivity?.unregisterNetworkCallback(callback)
                    } catch (_: Exception) {}
                networkRegistered = false
                relay.update { it.copy(network = true) }
                diagnostics.report("connectivity_observer_unavailable")
            }
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    // Only protected system broadcasts are received; no exported app commands.
                    context.registerReceiver(
                        powerReceiver,
                        IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
                    )
                    powerRegistered = true
                    updatePower()
                } catch (_: Exception) {
                    diagnostics.report("power_observer_unavailable")
                }
            }
            if (!closed.get()) ready()
        } catch (_: Exception) {
            failed()
        }
    }

    private fun updatePower() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                relay.update { it.copy(executionAllowed = manager?.isDeviceIdleMode != true) }
            } catch (_: Exception) {
                relay.update { it.copy(executionAllowed = true) }
                diagnostics.report("power_observer_unavailable")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        relay.close()
        handler.post {
            lifecycle?.removeObserver(observer)
            lifecycle = null
            if (networkRegistered)
                try {
                    connectivity?.unregisterNetworkCallback(callback)
                } catch (_: Exception) {}
            if (powerRegistered)
                try {
                    context.unregisterReceiver(powerReceiver)
                } catch (_: Exception) {}
            networkRegistered = false
            powerRegistered = false
            connectivity = null
        }
    }
}
