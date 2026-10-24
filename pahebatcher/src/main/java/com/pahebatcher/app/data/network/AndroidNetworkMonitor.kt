package com.pahebatcher.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * The ONE application-scoped connectivity source —
 * `ConnectivityManager`'s default-network callback exposed as [NetworkStatus].
 * Framework-only; no new dependency (AGENTS §17).
 *
 * Contract:
 *  - Constructed once in `AppContainer`; every consumer shares the single
 *    [stateIn] stream, so exactly ONE platform callback exists for the process
 *    (DECISIONS 97.1 "one application-scoped NetworkMonitor").
 *  - Initial value [NetworkStatus.UNKNOWN] (97.2) until registration delivers
 *    the platform's first report — subscribers never see a fabricated state.
 *  - Registration performs one synchronous state query first, because in
 *    airplane mode there is no default network and no callback would ever fire:
 *    the query reports OFFLINE honestly instead of leaving UNKNOWN forever.
 *  - Every later transition comes from the platform callback — event-driven,
 *    no polling (97.4).
 *  - The callback is registered against the main looper handler
 *    (`registerDefaultNetworkCallback(callback, handler)`): the callbackFlow
 *    block runs on an IO-capable dispatcher with no Looper of its own.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivityManager: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /**
     * Process-lifetime scope for the single shared stream. The monitor itself
     * is application-scoped (built once in AppContainer), so this scope lives
     * exactly as long as the process — same lifetime as the Application.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val networkStatus: StateFlow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            // One truth for every event: re-query the ACTIVE network rather than
            // trusting the callback's own network argument — during a handover the
            // reported network may already be the dying one.
            override fun onAvailable(network: Network) {
                trySend(currentStatus())
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }

            override fun onUnavailable() {
                trySend(NetworkStatus.OFFLINE)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(currentStatus())
            }
        }

        // First report: the platform's synchronous view of today's state (this is
        // what makes airplane mode observable at all — see class KDoc). After
        // this, only callbacks emit.
        trySend(currentStatus())

        connectivityManager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, NetworkStatus.UNKNOWN)

    /**
     * ACTIVE-network truth: no active network at all → OFFLINE; capabilities
     * must both carry traffic (INTERNET) and have completed the platform's
     * connectivity check (VALIDATED) → ONLINE; anything else (captive portal,
     * not yet validated) → OFFLINE.
     */
    private fun currentStatus(): NetworkStatus {
        val capabilities = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork,
        ) ?: return NetworkStatus.OFFLINE
        val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (online) NetworkStatus.ONLINE else NetworkStatus.OFFLINE
    }
}
