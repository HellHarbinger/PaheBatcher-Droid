package com.pahebatcher.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The offline banner on the
 * network-dependent surfaces (Home/Search, Anime Details, Watchlist).
 *
 * Stateless and dumb on purpose — the ViewModel has already decided the screen
 * is [com.pahebatcher.app.data.network.NetworkStatus.OFFLINE]; this only renders
 * the honest indication. Offline-capable screens (Downloads, Player, Sessions,
 * Settings, Console — SPEC §27's own list) never call it.
 *
 * A polite live region announces the appearance/disappearance to TalkBack (V2
 * §21 / Decision 55: status changes are announced, state is text, never silent).
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("offline_banner")
            // DECISIONS 97.3 / Decision 55: `mergeDescendants = true` makes this
            // status block its OWN semantics node — it absorbs its Text instead of
            // being flattened into an interactive ancestor's merged node (Home's
            // long-press root is clickable, and interactive/plain nodes merge
            // differently). That scopes the polite live region to THIS message,
            // so TalkBack announces exactly the offline status — not the whole
            // screen — and keeps the node addressable by tag in the merged tree.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = "You're offline — network features are unavailable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
