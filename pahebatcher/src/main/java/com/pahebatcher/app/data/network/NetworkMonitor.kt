package com.pahebatcher.app.data.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Connectivity as observed by the ONE application-scoped
 * [NetworkMonitor], consumed per screen through ViewModel UiState — never from a
 * composable-owned callback (AGENTS §4: UI components do not handle network state).
 */
enum class NetworkStatus {
    /**
     * No callback has been delivered yet (DECISIONS 97.2). Treated as **online**:
     * an offline banner must never be shown on a guess before the platform has
     * reported anything — false offline claims are as dishonest as false success.
     */
    UNKNOWN,

    /** The default network is up and can carry traffic. */
    ONLINE,

    /** The default network is absent or cannot carry traffic (SPEC §27). */
    OFFLINE,
}

/**
 * Read-only connectivity source (DECISIONS 97.1): one application-scoped
 * implementation in production ([AndroidNetworkMonitor]), injected into the
 * network-dependent ViewModels. Event-driven only — no polling, no timers
 * (DECISIONS 97.4); restoring connectivity clears the banner through the same
 * Flow.
 */
interface NetworkMonitor {

    /**
     * Current connectivity. Emits [NetworkStatus.UNKNOWN] first (before the
     * platform's initial callback) and then every transition.
     */
    val networkStatus: Flow<NetworkStatus>
}

/**
 * No monitor wired (pre-DI construction and tests): permanently [NetworkStatus.UNKNOWN],
 * which DECISIONS 97.2 defines as online — i.e. exactly the behavior that existed
 * before this phase, with no connectivity observation at all. Production always
 * injects [AndroidNetworkMonitor] through `AppContainer`.
 */
object UnknownNetworkMonitor : NetworkMonitor {
    override val networkStatus: Flow<NetworkStatus> = flowOf(NetworkStatus.UNKNOWN)
}
