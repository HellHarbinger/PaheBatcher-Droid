package com.pahebatcher.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pahebatcher.app.di.AppViewModelFactory
import com.pahebatcher.app.domain.model.ThemeSettings
import com.pahebatcher.app.ui.navigation.AppNavHost
import com.pahebatcher.app.ui.navigation.PlayerMode
import com.pahebatcher.app.ui.navigation.PlayerTarget
import com.pahebatcher.app.ui.sessionprompt.SessionRefreshViewModel
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import com.pahebatcher.app.ui.theme.shouldUseDarkColors
import com.pahebatcher.app.ui.validation.ValidationOverlayEntry
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single-Activity shell (PHASE11_ARCHITECTURE_V2 §10/§19/§31).
 *
 * Owns:
 *  - theme + edge-to-edge bar styling (§4/§8),
 *  - the flat [AppNavHost] (single NavHost, two-tab bottom bar),
 *  - §19 `navigate_to` consumption on BOTH activity paths (cold [onCreate],
 *    warm [onNewIntent]) feeding one consume-once state the NavHost navigates
 *    from (single-top; unknown values navigate nowhere; a read value is
 *    removed from the Intent so recreation cannot re-navigate),
 *  - the debug-gated ValidationScreen overlay (§31 — not a route),
 *  - final-audit H1 (SPEC §6.10): the root session-refresh prompt, rendered
 *    above every route from the [SessionRefreshViewModel] observation of
 *    [com.pahebatcher.app.domain.session.SessionRefreshSignal].
 *
 * Test seams: [onNavigateTarget] (drives §36-5 warm/duplicate/unknown intent
 * deliveries through the exact point the real handler uses) and [navController]
 * (live back-stack assertions for §36-4/§36-5).
 */
class MainActivity : ComponentActivity() {

    /** §19.2 single ingestion result — non-null exactly once per delivered extra. */
    private val navigateTarget = MutableStateFlow<String?>(null)

    /**
     * §19.2/§36-5 test seam: the ONE entry point both the real [onNewIntent]
     * handler and instrumentation use to deliver a `navigate_to` value. The
     * NavHost's LaunchedEffect consumes it (navigation or discard) and clears it.
     */
    fun onNavigateTarget(target: String?) {
        navigateTarget.value = target
    }

    /**
     * §36-4/§36-5 test seam: the live NavHost controller, assigned via
     * [SideEffect] after every successful composition, so tests can assert the
     * actual back stack (duplicate-tap size, restoration, current route).
     */
    var navController: NavHostController? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold path (§19.2): read + consume the extra exactly once. Removing it
        // here means Activity recreation (rotation) re-reads nothing, while a
        // warm notification tap delivers a FRESH Intent whose extra is intact.
        onNavigateTarget(consumeNavigateTarget(intent))

        setContent {
            val appContainer = (application as PaheBatcherApplication).appContainer
            val themeSettings by appContainer.appConfig.themeSettings
                .collectAsStateWithLifecycle(initialValue = ThemeSettings.DEFAULT)
            PaheBatcherTheme(
                themeMode = themeSettings.themeMode,
                dynamicColor = themeSettings.dynamicColor,
            ) {
                // §4: bar/icon colors derive from the SELECTED scheme (not the system
                // setting) so the "space" background stays continuous — the default
                // DARK theme gets light bar icons even on a light system setting.
                val darkTheme = shouldUseDarkColors(themeSettings.themeMode)
                LaunchedEffect(darkTheme) {
                    val transparent = android.graphics.Color.TRANSPARENT
                    if (darkTheme) {
                        enableEdgeToEdge(
                            statusBarStyle = SystemBarStyle.dark(transparent),
                            navigationBarStyle = SystemBarStyle.dark(transparent),
                        )
                    } else {
                        enableEdgeToEdge(
                            statusBarStyle = SystemBarStyle.light(transparent, transparent),
                            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
                        )
                    }
                }

                var showValidation by remember { mutableStateOf(false) }
                val pendingNavTarget by navigateTarget.collectAsStateWithLifecycle()
                val navController = rememberNavController()
                SideEffect { this.navController = navController }

                val viewModelFactory = remember { AppViewModelFactory(appContainer) }

                // Final-audit H1 (SPEC §6.10): the ROOT session-refresh prompt —
                // above every route, raised by the data layer's detection sites
                // through SessionRefreshSignal. Scoped to the Activity, so a
                // rotation keeps an unanswered prompt and a finished refresh is
                // never re-shown from stale state.
                val sessionPromptViewModel: SessionRefreshViewModel =
                    viewModel(factory = viewModelFactory)
                val sessionPrompt by sessionPromptViewModel.state
                    .collectAsStateWithLifecycle()
                if (sessionPrompt.visible) {
                    AlertDialog(
                        onDismissRequest = sessionPromptViewModel::dismiss,
                        modifier = Modifier.testTag("session_refresh_prompt"),
                        title = { Text("Refresh session") },
                        text = {
                            Text(
                                buildString {
                                    append(
                                        "The WebView session for AnimePahe is no longer " +
                                            "valid, so requests are failing. Refresh it to " +
                                            "continue browsing and downloading.",
                                    )
                                    sessionPrompt.error?.let { append("\n\n"); append(it) }
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = sessionPromptViewModel::refresh,
                                enabled = !sessionPrompt.refreshing,
                            ) {
                                Text(if (sessionPrompt.refreshing) "Refreshing…" else "Refresh now")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = sessionPromptViewModel::dismiss) {
                                Text("Not now")
                            }
                        },
                    )
                }

                // §31: release builds can never reach the validation overlay —
                // the only setter of showValidation is the gated lambda below.
                // A2.1 Decision 3 (NET-014): the overlay (and its spike
                // validators) now lives in the debug source set; the per-variant
                // ValidationOverlayEntry is a no-op in release.
                if (BuildConfig.DEBUG && showValidation) {
                    ValidationOverlayEntry.Content(onBack = { showValidation = false })
                } else {
                    AppNavHost(
                        navController = navController,
                        viewModelFactory = viewModelFactory,
                        pendingNavTarget = pendingNavTarget,
                        onNavTargetConsumed = { onNavigateTarget(null) },
                        onOpenValidation = { if (BuildConfig.DEBUG) showValidation = true },
                    )
                }
            }
        }
    }

    /** Warm path (§19.2): same single ingestion point as the cold path. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onNavigateTarget(consumeNavigateTarget(intent))
    }

    /**
     * Reads the §19 routing extra and REMOVES it (consume-once): a recreated
     * Activity holds the same Intent instance, so rotation never re-navigates;
     * unknown values are returned as-is and discarded by the NavHost without
     * navigation or crash (§19.1).
     */
    private fun consumeNavigateTarget(intent: Intent?): String? {
        val target = intent?.getStringExtra(EXTRA_NAVIGATE_TO) ?: return null
        intent.removeExtra(EXTRA_NAVIGATE_TO)
        return target
    }

    companion object {
        /** Exact extra name attached by DownloadNotificationManager (§19). */
        const val EXTRA_NAVIGATE_TO = "navigate_to"

        /**
         * `Replace file` (DECISIONS 152): the §19 routing prefix for the
         * confirmation flow, `replace:<episodeId>`.
         *
         * Encoded as a single string on the same §19 contract the player target
         * uses (`player:<episodeId>:<mode>` since AQR-138) so it travels the one
         * navigation contract every other notification target
         * already uses — no new intent mechanism, no second deep-link parser.
         * The string is the WHOLE command: it identifies which episode's
         * confirmation to open and nothing else, so it can never carry — or
         * be mistaken for — something destructive.
         */
        const val NAV_TARGET_REPLACE_PREFIX = "replace:"

        /** Builds the §19 target for episode [episodeId]'s confirmation. */
        fun replaceTarget(episodeId: String): String = NAV_TARGET_REPLACE_PREFIX + episodeId

        /** Media-session activity request code (§7); numeric value pinned by `MediaSessionLifecycleTest`. */
        const val MEDIA_SESSION_REQUEST_CODE = -9002

        /**
         * Creates the session activity PendingIntent for
         * MediaSession.setSessionActivity. Encodes the player target as
         * "player:<episodeId>:<mode>" ([com.pahebatcher.app.ui.navigation.PlayerTarget],
         * AQR-138) so MainActivity's single-string navigation contract
         * (EXTRA_NAVIGATE_TO) can route it to PlayerRoute(episodeId, mode): a
         * media notification posted for a STREAM session re-opens STREAM, one
         * posted for a DOWNLOAD session re-opens DOWNLOAD. The mode is always
         * explicit here — no notification target relies on the route's implicit
         * default.
         * Request code is [MEDIA_SESSION_REQUEST_CODE] (-9002), disjoint from the
         * watchlist namespace (-9001: `WATCHLIST_NOTIFICATION_ID`, reused as its
         * content-intent request code), batch-content (-9003), and the masked
         * hashCode-derived progress codes (`DownloadNotificationManager.requestCodeFor`).
         * (The batch-failure NOTIFICATION id also reads -9002 — a notification id
         * and an activity request code are separate namespaces; PB-001/AQR-057.)
         */
        fun createSessionActivityIntent(
            context: Context,
            episodeId: String?,
            mode: PlayerMode,
        ): android.app.PendingIntent {
            val target = episodeId?.let { PlayerTarget.encode(it, mode) } ?: ""
            val intent = Intent(
                context,
                MainActivity::class.java
            ).apply {
                putExtra(EXTRA_NAVIGATE_TO, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return android.app.PendingIntent.getActivity(
                context,
                MEDIA_SESSION_REQUEST_CODE,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainActivityPreview() {
    PaheBatcherTheme {
        androidx.compose.material3.Text("PaheBatcher")
    }
}
