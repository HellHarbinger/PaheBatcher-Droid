package com.pahebatcher.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.ensureTestMp4
import com.pahebatcher.app.support.onTopRoute
import com.pahebatcher.app.support.seedCompletedEpisodes
import com.pahebatcher.app.support.stackSize
import com.pahebatcher.app.ui.navigation.DownloadsRoute
import com.pahebatcher.app.ui.navigation.HomeRoute
import com.pahebatcher.app.ui.navigation.PlayerMode
import com.pahebatcher.app.ui.navigation.PlayerRoute
import com.pahebatcher.app.ui.navigation.navigateToPlayer
import com.pahebatcher.app.ui.player.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §36-5 — Notification intent/navigation tests (PHASE11_ARCHITECTURE_V2 §19,
 * F-18): the `navigate_to` extra is consumed on BOTH activity paths and obeys
 * the §19.3 single-top navigation rule.
 *
 * Covered here (all §36-5 cases, including the player case):
 *  - cold-start tap → Downloads;
 *  - warm tap while on Home → Downloads;
 *  - tap while already on Downloads → NO duplicate entry (stack unchanged);
 *  - tap while on Player → pops the player (releasing its ExoPlayer) and
 *    converges on [Home, Downloads];
 *  - unknown extra value → stays on Home without crash;
 *  - absent extra → plain app open at Home;
 *  - same-episode `player:` target → no-op: no pop, no re-push, no player
 *    churn, mode untouched (AQR-139, AQR-138 active-player path);
 *  - same-episode STREAM target → STREAM mode preserved (AQR-138);
 *  - different-episode `player:` target → in-place replace, exactly ONE
 *    player entry carrying the requested episode (AQR-139);
 *  - blank `player:` ids → consumed without navigation (DECISIONS 118.2,
 *    AQR-145); a valid id still opens the player.
 *
 * AQR-138 (adopted 2026-10-23) — notification targets carry an EXPLICIT
 * playback mode, `player:<episodeId>:<mode>` ([com.pahebatcher.app.ui
 * .navigation.PlayerTarget]):
 *  - stream target cold start → STREAM; download target cold start → DOWNLOAD;
 *  - a different episode's target replaces in the mode IT names (stream →
 *    STREAM, download → DOWNLOAD);
 *  - a same-episode target never switches the active player's mode and never
 *    releases or recreates the player, ViewModel or MediaSession;
 *  - blank, malformed and unsupported-mode targets are consumed without
 *    navigation; the pre-AQR-138 `player:<episodeId>` form still means
 *    DOWNLOAD (DECISIONS 118.2 compatibility).
 *
 * Delivery uses the MainActivity [MainActivity.onNavigateTarget] seam — the
 * exact ingestion point the real `onNewIntent` handler calls — so warm taps are
 * exercised through production code. The cold path uses a REAL launch Intent
 * carrying the extra through [MainActivity.onCreate].
 */
class IntentNavigationTest {

    private fun launchIntent(target: String? = null): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(
            context.packageManager.getLaunchIntentForPackage(context.packageName),
        ) { "launch intent not found" }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (target != null) intent.putExtra("navigate_to", target)
        return intent
    }

    private fun deliverWarm(scenario: ActivityScenario<MainActivity>, target: String) {
        scenario.onActivity { it.onNavigateTarget(target) }
    }

    @Test
    fun coldStartTapOpensDownloads() {
        ActivityScenario.launch<MainActivity>(launchIntent("downloads")).use { scenario ->
            assertTrue(
                "cold tap with navigate_to=downloads must open Downloads",
                awaitCondition { scenario.onTopRoute<DownloadsRoute>() },
            )
            assertEquals(2, scenario.stackSize())
        }
    }

    @Test
    fun plainOpenLandsOnHome() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(
                "no extra → plain open at Home",
                awaitCondition { scenario.onTopRoute<HomeRoute>() },
            )
            assertEquals(1, scenario.stackSize())
        }
    }

    @Test
    fun warmTapWhileOnHomeOpensDownloads() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })

            deliverWarm(scenario, "downloads")

            assertTrue(
                "warm tap while on Home must open Downloads",
                awaitCondition { scenario.onTopRoute<DownloadsRoute>() },
            )
            assertEquals(2, scenario.stackSize())
        }
    }

    @Test
    fun tapWhileOnDownloadsCreatesNoDuplicateEntry() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            deliverWarm(scenario, "downloads")
            assertTrue(awaitCondition { scenario.onTopRoute<DownloadsRoute>() })

            val sizeBefore = scenario.stackSize()
            assertEquals(2, sizeBefore)

            // Second delivery — singleTop + restoreState must converge on ONE
            // downloads entry (§19.3 duplicate-tap safety).
            deliverWarm(scenario, "downloads")
            Thread.sleep(1_000) // give the LaunchedEffect time to consume (or no-op)

            assertTrue(
                "still on Downloads after duplicate delivery",
                scenario.onTopRoute<DownloadsRoute>(),
            )
            assertEquals(
                "duplicate tap must not deepen the back stack",
                sizeBefore,
                scenario.stackSize(),
            )
        }
    }

    @Test
    fun unknownExtraValueNavigatesNowhereWithoutCrash() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })

            deliverWarm(scenario, "definitely-not-a-route")
            Thread.sleep(1_000)

            assertTrue(
                "unknown value is discarded without navigation",
                scenario.onTopRoute<HomeRoute>(),
            )
            assertEquals(1, scenario.stackSize())
        }
    }

    @Test
    fun unknownValueDeliveredWhileOnDownloadsKeepsDownloads() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            deliverWarm(scenario, "downloads")
            assertTrue(awaitCondition { scenario.onTopRoute<DownloadsRoute>() })

            deliverWarm(scenario, "bogus-target")
            Thread.sleep(1_000)

            assertTrue(scenario.onTopRoute<DownloadsRoute>())
            assertEquals(2, scenario.stackSize())
        }
    }

    /**
     * §36-5 player case (§19.5): a notification tap while the Player is open
     * unwinds to the top-level Downloads tab — the player entry is popped
     * FIRST (releasing its ExoPlayer, the §24.3 exit proof) and the stack
     * converges on [Home, Downloads] without stacking.
     *
     * Seeds a REAL COMPLETED episode with real local MP4 media so a genuine
     * ExoPlayer exists to release — the release is asserted through the
     * instrumentation-visible create/release accounting, not inferred.
     */
    @Test
    fun tapWhileOnPlayerReturnsToDownloadsAndReleasesPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })

            val db = PaheDatabase.getInstance(context)
            val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
            try {
                val liveBefore = PlayerViewModel.livePlayerCount.get()
                val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

                scenario.onActivity { it.navController!!.navigateToPlayer(episodeId) }
                assertTrue(
                    "player route opens for the seeded episode",
                    awaitCondition(15_000) { scenario.onTopRoute<PlayerRoute>() },
                )
                assertTrue(
                    "player creates exactly one ExoPlayer",
                    awaitCondition(15_000) {
                        PlayerViewModel.livePlayerCount.get() == liveBefore + 1
                    },
                )

                deliverWarm(scenario, "downloads")

                assertTrue(
                    "notification tap while on Player returns to Downloads",
                    awaitCondition(10_000) { scenario.onTopRoute<DownloadsRoute>() },
                )
                assertEquals(
                    "stack converges on [Home, Downloads] (player popped)",
                    2,
                    scenario.stackSize(),
                )
                assertTrue(
                    "popped player entry released its ExoPlayer (no leak)",
                    awaitCondition(10_000) {
                        PlayerViewModel.releasedPlayerCount.get() >= releasedBefore + 1 &&
                            PlayerViewModel.livePlayerCount.get() == liveBefore
                    },
                )
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // AQR-138/139/145 — `player:<episodeId>` target handling (§19, §24.2)
    // -----------------------------------------------------------------------

    /** The top [PlayerRoute] while a player entry is top, else null. */
    private fun topPlayerRoute(scenario: ActivityScenario<MainActivity>): PlayerRoute? {
        var route: PlayerRoute? = null
        scenario.onActivity { activity ->
            val entry = activity.navController?.currentBackStackEntry
            if (entry?.destination?.hasRoute<PlayerRoute>() == true) {
                route = entry.toRoute<PlayerRoute>()
            }
        }
        return route
    }

    /** Count of [PlayerRoute] entries in the ACTIVE back stack. */
    private fun playerEntryCount(scenario: ActivityScenario<MainActivity>): Int {
        var count = -1
        scenario.onActivity { activity ->
            count = activity.navController?.currentBackStack?.value
                ?.count { it.destination.hasRoute<PlayerRoute>() } ?: -1
        }
        return count
    }

    /**
     * The top [PlayerViewModel] while a player entry is top, else null — the
     * test seam for observing the load's honest verdict (`uiState.loadError`).
     * This is the SAME instance production created via
     * `viewModel(factory = viewModelFactory)` scoped to the back-stack entry
     * (both use the class-canonical default key), so reading it observes real
     * production state. Returns null only if the entry is not a player or the
     * VM cannot be resolved.
     */
    private fun topPlayerViewModel(scenario: ActivityScenario<MainActivity>): PlayerViewModel? {
        var vm: PlayerViewModel? = null
        scenario.onActivity { activity ->
            val entry = activity.navController?.currentBackStackEntry
            if (entry?.destination?.hasRoute<PlayerRoute>() == true) {
                vm = runCatching {
                    ViewModelProvider(entry)[PlayerViewModel::class.java]
                }.getOrNull()
            }
        }
        return vm
    }

    /**
     * AQR-139 — same-episode `player:<id>` delivery while that player entry is
     * top is a NO-OP: no `popBackStack()` runs (entry, ViewModel and ExoPlayer
     * untouched) and no re-navigation runs (stack and route args unchanged).
     * Repeating the delivery stays idempotent (§19.3 consume-once + §24.2
     * re-entry: no second player entry ever).
     */
    @Test
    fun sameEpisodePlayerTargetWhilePlayerIsTopIsANoOp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
            try {
                val liveBefore = PlayerViewModel.livePlayerCount.get()
                val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

                scenario.onActivity { it.navController!!.navigateToPlayer(episodeId) }
                assertTrue(
                    "player route opens for the seeded episode",
                    awaitCondition(15_000) { scenario.onTopRoute<PlayerRoute>() },
                )
                assertTrue(
                    "player creates exactly one ExoPlayer",
                    awaitCondition(15_000) {
                        PlayerViewModel.livePlayerCount.get() == liveBefore + 1
                    },
                )

                val stackBefore = scenario.stackSize()
                val routeBefore = topPlayerRoute(scenario)
                assertEquals(episodeId, routeBefore?.episodeId)

                deliverWarm(scenario, "player:$episodeId")
                Thread.sleep(1_500) // effect consumes (or no-ops) exactly once

                assertTrue(
                    "same-episode target leaves the player on top",
                    scenario.onTopRoute<PlayerRoute>(),
                )
                assertEquals(
                    "no pop and no re-push — stack unchanged",
                    stackBefore,
                    scenario.stackSize(),
                )
                assertEquals(
                    "route args unchanged (entry not recreated)",
                    routeBefore,
                    topPlayerRoute(scenario),
                )
                assertEquals(
                    "no player was released by the delivery",
                    releasedBefore,
                    PlayerViewModel.releasedPlayerCount.get(),
                )
                assertEquals(
                    "no player was created by the delivery",
                    liveBefore + 1,
                    PlayerViewModel.livePlayerCount.get(),
                )

                // A repeated delivery must remain idempotent.
                deliverWarm(scenario, "player:$episodeId")
                Thread.sleep(1_500)
                assertTrue(scenario.onTopRoute<PlayerRoute>())
                assertEquals(stackBefore, scenario.stackSize())
                assertEquals(routeBefore, topPlayerRoute(scenario))
                assertEquals(releasedBefore, PlayerViewModel.releasedPlayerCount.get())
                assertEquals(liveBefore + 1, PlayerViewModel.livePlayerCount.get())
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    /**
     * AQR-138 (active-player path) — while a stream session's player entry is
     * top, a MediaSession tap for the SAME episode must preserve
     * [PlayerMode.STREAM]: the same-episode no-op means the route never falls
     * back to the [PlayerMode.DOWNLOAD] default, the stack is unchanged and no
     * player is released or created.
     *
     * The target delivered is the one production now emits for a stream
     * session, `player:<id>:stream` (AQR-138); the legacy bare `player:<id>`
     * form is exercised by [sameEpisodePlayerTargetWhilePlayerIsTopIsANoOp].
     *
     * Playback itself is NOT asserted — the seeded `playUrl` is a fake host so
     * reconstruction honestly fails; this pins routing/mode only.
     */
    @Test
    fun sameEpisodeStreamPlayerTargetPreservesStreamMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
            try {
                scenario.onActivity {
                    it.navController!!.navigateToPlayer(episodeId, PlayerMode.STREAM)
                }
                assertTrue(
                    "stream player route opens",
                    awaitCondition(15_000) { scenario.onTopRoute<PlayerRoute>() },
                )
                assertEquals(
                    "route seeds STREAM mode",
                    PlayerMode.STREAM,
                    topPlayerRoute(scenario)?.mode,
                )
                // Let the STREAM load reach its steady state (the seeded fake
                // playUrl cannot resolve — no playback claim is made) before
                // sampling the player accounting.
                Thread.sleep(2_000)

                val stackBefore = scenario.stackSize()
                val routeBefore = topPlayerRoute(scenario)
                val liveBefore = PlayerViewModel.livePlayerCount.get()
                val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

                deliverWarm(scenario, "player:$episodeId:stream")
                Thread.sleep(1_500)

                assertTrue(
                    "same-episode target leaves the stream player on top",
                    scenario.onTopRoute<PlayerRoute>(),
                )
                assertEquals(
                    "STREAM mode preserved — never flipped to the DOWNLOAD default",
                    PlayerMode.STREAM,
                    topPlayerRoute(scenario)?.mode,
                )
                assertEquals(episodeId, topPlayerRoute(scenario)?.episodeId)
                assertEquals(stackBefore, scenario.stackSize())
                assertEquals(routeBefore, topPlayerRoute(scenario))
                assertEquals(
                    "no player was released by the delivery",
                    releasedBefore,
                    PlayerViewModel.releasedPlayerCount.get(),
                )
                assertEquals(
                    "no player was created by the delivery",
                    liveBefore,
                    PlayerViewModel.livePlayerCount.get(),
                )

                // Idempotent on repetition.
                deliverWarm(scenario, "player:$episodeId:stream")
                Thread.sleep(1_500)
                assertEquals(PlayerMode.STREAM, topPlayerRoute(scenario)?.mode)
                assertEquals(stackBefore, scenario.stackSize())
                assertEquals(releasedBefore, PlayerViewModel.releasedPlayerCount.get())
                assertEquals(liveBefore, PlayerViewModel.livePlayerCount.get())
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    /**
     * AQR-139 — a DIFFERENT episode's `player:` target while the player is top
     * replaces the entry in place through navigateToPlayer's own popUpTo +
     * launchSingleTop (§24.2): exactly ONE player entry survives, carrying the
     * requested episode, at the same stack depth, with the same player instance
     * (in-place reseed — no release, no second ExoPlayer).
     */
    @Test
    fun differentEpisodePlayerTargetReplacesPlayerEntryInPlace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val ids = seedCompletedEpisodes(db, ensureTestMp4(), count = 2)
            val first = ids[0]
            val second = ids[1]
            try {
                val liveBefore = PlayerViewModel.livePlayerCount.get()
                val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

                scenario.onActivity { it.navController!!.navigateToPlayer(first) }
                assertTrue(
                    "player route opens for the first episode",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == first
                    },
                )
                assertTrue(
                    "player creates exactly one ExoPlayer",
                    awaitCondition(15_000) {
                        PlayerViewModel.livePlayerCount.get() == liveBefore + 1
                    },
                )

                val stackBefore = scenario.stackSize()
                deliverWarm(scenario, "player:$second")
                assertTrue(
                    "different-episode target lands on the requested episode",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == second
                    },
                )
                assertEquals(
                    "stack depth unchanged — replace-in-place, never stacked",
                    stackBefore,
                    scenario.stackSize(),
                )
                assertEquals(
                    "exactly ONE player entry exists",
                    1,
                    playerEntryCount(scenario),
                )
                assertEquals(
                    "same player instance reseeded — nothing released",
                    releasedBefore,
                    PlayerViewModel.releasedPlayerCount.get(),
                )
                assertEquals(
                    "one live player — no second ExoPlayer",
                    liveBefore + 1,
                    PlayerViewModel.livePlayerCount.get(),
                )
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    /**
     * AQR-145 — an empty (`player:`) or whitespace-only (`player:   `) id is
     * consumed WITHOUT navigation (DECISIONS 118.2 default-routing fallback,
     * §19.1 discard): no `PlayerRoute("")` is ever created, nothing moves, and
     * the consume-once rule means the value is never re-processed. An unknown
     * target discards the same way, and a VALID non-empty id still opens the
     * player with that exact episode — behaviour preserved for valid ids.
     */
    @Test
    fun blankPlayerTargetsAreConsumedWithoutNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })

            deliverWarm(scenario, "player:")
            Thread.sleep(1_000)
            assertTrue(
                "empty id is discarded — still Home, no player",
                scenario.onTopRoute<HomeRoute>(),
            )
            assertEquals(1, scenario.stackSize())

            deliverWarm(scenario, "player:   ")
            Thread.sleep(1_000)
            assertTrue(
                "whitespace-only id is discarded — still Home, no player",
                scenario.onTopRoute<HomeRoute>(),
            )
            assertEquals(1, scenario.stackSize())

            deliverWarm(scenario, "not-a-known-target")
            Thread.sleep(1_000)
            assertTrue("unknown target discards the same way", scenario.onTopRoute<HomeRoute>())
            assertEquals(1, scenario.stackSize())

            // A valid non-empty id must still route exactly as before.
            val db = PaheDatabase.getInstance(context)
            val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
            try {
                deliverWarm(scenario, "player:$episodeId")
                assertTrue(
                    "valid id opens the player",
                    awaitCondition(15_000) { scenario.onTopRoute<PlayerRoute>() },
                )
                assertEquals(episodeId, topPlayerRoute(scenario)?.episodeId)
                assertEquals(2, scenario.stackSize())
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // AQR-138 — notification targets carry an EXPLICIT playback mode
    // (`player:<episodeId>:<mode>`, PlayerTarget)
    // -----------------------------------------------------------------------

    /**
     * AQR-138 cold start — a STREAM target delivered on the REAL launch intent
     * opens the episode in [PlayerMode.STREAM]. The mode comes from the target
     * itself, never from the route's implicit DOWNLOAD default, which is what
     * the pre-AQR-138 bare form always fell back to.
     */
    @Test
    fun coldStartStreamTargetOpensStreamPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = PaheDatabase.getInstance(context)
        val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
        try {
            ActivityScenario.launch<MainActivity>(
                launchIntent("player:$episodeId:stream"),
            ).use { scenario ->
                assertTrue(
                    "stream target cold start lands on the player for that episode",
                    awaitCondition(20_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == episodeId
                    },
                )
                assertEquals(
                    "STREAM is the mode the target named — not the DOWNLOAD default",
                    PlayerMode.STREAM,
                    topPlayerRoute(scenario)?.mode,
                )
                assertEquals(
                    "player pushed once over Home",
                    2,
                    scenario.stackSize(),
                )
            }
        } finally {
            runBlocking { db.downloadSessionDao().deleteAll() }
        }
    }

    /**
     * AQR-138 cold start — the explicit DOWNLOAD target opens the episode in
     * [PlayerMode.DOWNLOAD], so the download half of the contract is pinned by
     * a mode token rather than by the route default.
     */
    @Test
    fun coldStartDownloadTargetOpensDownloadPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = PaheDatabase.getInstance(context)
        val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
        try {
            ActivityScenario.launch<MainActivity>(
                launchIntent("player:$episodeId:download"),
            ).use { scenario ->
                assertTrue(
                    "download target cold start lands on the player for that episode",
                    awaitCondition(20_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == episodeId
                    },
                )
                assertEquals(
                    "DOWNLOAD is the mode the target named",
                    PlayerMode.DOWNLOAD,
                    topPlayerRoute(scenario)?.mode,
                )
                assertEquals(2, scenario.stackSize())
            }
        } finally {
            runBlocking { db.downloadSessionDao().deleteAll() }
        }
    }

    /**
     * AQR-138 (different episode) — while a DOWNLOAD player is active, a
     * STREAM target for ANOTHER episode replaces the entry in place (§24.2)
     * carrying [PlayerMode.STREAM]. Exactly ONE player entry survives, at the
     * same depth, with the same live player (no release, no second ExoPlayer).
     */
    @Test
    fun differentEpisodeStreamTargetReplacesPlayerInStreamMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val ids = seedCompletedEpisodes(db, ensureTestMp4(), count = 2)
            val first = ids[0]
            val second = ids[1]
            try {
                val liveBefore = PlayerViewModel.livePlayerCount.get()
                val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

                scenario.onActivity { it.navController!!.navigateToPlayer(first) }
                assertTrue(
                    "download player opens for the first episode",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == first &&
                            topPlayerRoute(scenario)?.mode == PlayerMode.DOWNLOAD
                    },
                )
                assertTrue(
                    "player creates exactly one ExoPlayer",
                    awaitCondition(15_000) {
                        PlayerViewModel.livePlayerCount.get() == liveBefore + 1
                    },
                )
                val stackBefore = scenario.stackSize()

                deliverWarm(scenario, "player:$second:stream")
                assertTrue(
                    "stream target for another episode lands on THAT episode in STREAM",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == second &&
                            topPlayerRoute(scenario)?.mode == PlayerMode.STREAM
                    },
                )
                assertEquals("stack depth unchanged — replace-in-place", stackBefore, scenario.stackSize())
                assertEquals("exactly ONE player entry exists", 1, playerEntryCount(scenario))
                assertEquals(
                    "same player instance reseeded — nothing released",
                    releasedBefore,
                    PlayerViewModel.releasedPlayerCount.get(),
                )
                assertEquals(
                    "one live player — no second ExoPlayer",
                    liveBefore + 1,
                    PlayerViewModel.livePlayerCount.get(),
                )
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    /**
     * AQR-138 (different episode) — the mirror case: while a STREAM player is
     * active, a DOWNLOAD target for ANOTHER episode replaces the entry in place
     * carrying [PlayerMode.DOWNLOAD], with the same single-entry / single-player
     * guarantees.
     */
    @Test
    fun differentEpisodeDownloadTargetReplacesPlayerInDownloadMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val ids = seedCompletedEpisodes(db, ensureTestMp4(), count = 2)
            val first = ids[0]
            val second = ids[1]
            try {
                val liveBefore = PlayerViewModel.livePlayerCount.get()
                val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

                scenario.onActivity {
                    it.navController!!.navigateToPlayer(first, PlayerMode.STREAM)
                }
                assertTrue(
                    "stream player opens for the first episode",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == first &&
                            topPlayerRoute(scenario)?.mode == PlayerMode.STREAM
                    },
                )
                // Just-in-time STREAM contract (StreamPlaybackLifecycleTest): a
                // STREAM entry creates its ExoPlayer ONLY inside ensurePlayer(),
                // which loadStreamPlaylist reaches after planPlayerLoad and
                // resolveStreamFor both succeed. Over a synthetic seed the load
                // settles honestly with an error, so no ExoPlayer exists yet —
                // the previous eager-creation assertion here was stale.
                // Awaiting the verdict also guarantees this load has finished
                // before the DOWNLOAD target is delivered (no load race).
                // Just-in-time STREAM contract (StreamPlaybackLifecycleTest): the
                // ExoPlayer is only created inside ensurePlayer(), which
                // loadStreamPlaylist reaches AFTER planPlayerLoad and
                // resolveStreamFor both succeed. Over a synthetic seed the load
                // settles honestly with an error, so no ExoPlayer exists yet —
                // the previous eager-creation assertion here was stale. Awaiting
                // the verdict also guarantees this STREAM load has finished
                // before the DOWNLOAD target is delivered (no load race). The VM
                // is re-fetched each poll because navigation sets the route
                // before the composable creates it.
                assertTrue(
                    "the STREAM load must reach its honest verdict (it cannot resolve a synthetic seed)",
                    awaitCondition(15_000) {
                        topPlayerViewModel(scenario)?.uiState?.value?.loadError != null
                    },
                )
                assertEquals(
                    "STREAM creation is just-in-time — no ExoPlayer is created until a stream resolves",
                    liveBefore,
                    PlayerViewModel.livePlayerCount.get(),
                )
                val stackBefore = scenario.stackSize()

                deliverWarm(scenario, "player:$second:download")
                assertTrue(
                    "download target for another episode lands on THAT episode in DOWNLOAD",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == second &&
                            topPlayerRoute(scenario)?.mode == PlayerMode.DOWNLOAD
                    },
                )
                assertEquals("stack depth unchanged — replace-in-place", stackBefore, scenario.stackSize())
                assertEquals("exactly ONE player entry exists", 1, playerEntryCount(scenario))
                assertEquals(
                    "same player instance reseeded — nothing released",
                    releasedBefore,
                    PlayerViewModel.releasedPlayerCount.get(),
                )
                assertEquals(
                    "one live player — no second ExoPlayer",
                    liveBefore + 1,
                    PlayerViewModel.livePlayerCount.get(),
                )
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    /**
     * AQR-138 (same episode, mode conflict) — a STREAM target for the episode
     * already playing as a DOWNLOAD is still a NO-OP: the same-episode rule
     * keys on the episode id alone, so the running player's mode is preserved
     * and nothing is released or recreated. The reverse (download target over
     * an active STREAM player) is pinned by
     * [sameEpisodeDownloadTargetDoesNotSwitchAnActiveStreamPlayer].
     */
    @Test
    fun sameEpisodeStreamTargetDoesNotSwitchAnActiveDownloadPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
            try {
                val liveBefore = PlayerViewModel.livePlayerCount.get()

                scenario.onActivity { it.navController!!.navigateToPlayer(episodeId) }
                assertTrue(
                    "download player opens for the seeded episode",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.mode == PlayerMode.DOWNLOAD
                    },
                )
                assertTrue(
                    "player creates exactly one ExoPlayer",
                    awaitCondition(15_000) {
                        PlayerViewModel.livePlayerCount.get() == liveBefore + 1
                    },
                )
                val stackBefore = scenario.stackSize()
                val routeBefore = topPlayerRoute(scenario)
                val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

                deliverWarm(scenario, "player:$episodeId:stream")
                Thread.sleep(1_500)

                assertTrue("player still on top", scenario.onTopRoute<PlayerRoute>())
                assertEquals(
                    "DOWNLOAD mode preserved — a same-episode target never switches mode",
                    PlayerMode.DOWNLOAD,
                    topPlayerRoute(scenario)?.mode,
                )
                assertEquals("route args unchanged (entry not recreated)", routeBefore, topPlayerRoute(scenario))
                assertEquals(stackBefore, scenario.stackSize())
                assertEquals(
                    "no player was released by the delivery",
                    releasedBefore,
                    PlayerViewModel.releasedPlayerCount.get(),
                )
                assertEquals(
                    "no player was created by the delivery",
                    liveBefore + 1,
                    PlayerViewModel.livePlayerCount.get(),
                )
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    /**
     * AQR-138 (same episode, mode conflict, mirror) — a DOWNLOAD target for the
     * episode already streaming is a NO-OP that preserves [PlayerMode.STREAM],
     * with no release and no recreation.
     */
    @Test
    fun sameEpisodeDownloadTargetDoesNotSwitchAnActiveStreamPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
            try {
                scenario.onActivity {
                    it.navController!!.navigateToPlayer(episodeId, PlayerMode.STREAM)
                }
                assertTrue(
                    "stream player opens for the seeded episode",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.mode == PlayerMode.STREAM
                    },
                )
                // Let the STREAM load reach its steady state (the seeded fake
                // playUrl cannot resolve — no playback claim is made) before
                // sampling the player accounting.
                Thread.sleep(2_000)
                val stackBefore = scenario.stackSize()
                val routeBefore = topPlayerRoute(scenario)
                val liveBefore = PlayerViewModel.livePlayerCount.get()
                val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

                deliverWarm(scenario, "player:$episodeId:download")
                Thread.sleep(1_500)

                assertTrue("player still on top", scenario.onTopRoute<PlayerRoute>())
                assertEquals(
                    "STREAM mode preserved — a same-episode target never switches mode",
                    PlayerMode.STREAM,
                    topPlayerRoute(scenario)?.mode,
                )
                assertEquals(routeBefore, topPlayerRoute(scenario))
                assertEquals(stackBefore, scenario.stackSize())
                assertEquals(releasedBefore, PlayerViewModel.releasedPlayerCount.get())
                assertEquals(liveBefore, PlayerViewModel.livePlayerCount.get())
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    /**
     * AQR-145/AQR-138 — targets that cannot be trusted are CONSUMED without
     * navigation (§19.1 discard): an unsupported mode token is never silently
     * downgraded to a mode the target did not name, a trailing separator is not
     * a mode, and a blank episode id never becomes a `PlayerRoute("")`. A valid
     * explicit target afterwards still routes, so valid ids are unaffected.
     */
    @Test
    fun unsupportedAndMalformedPlayerTargetsAreConsumedWithoutNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
            try {
                listOf(
                    "player:$episodeId:loud" to "unsupported mode token",
                    "player:$episodeId:STREAM" to "mode token is case-sensitive",
                    "player:$episodeId:" to "empty mode token",
                    "player::stream" to "blank episode id",
                ).forEach { (target, why) ->
                    deliverWarm(scenario, target)
                    Thread.sleep(1_000)
                    assertTrue(
                        "$why ($target) must be discarded without navigation",
                        scenario.onTopRoute<HomeRoute>(),
                    )
                    assertEquals("$why must not deepen the stack", 1, scenario.stackSize())
                }

                // Valid ids are unaffected by the stricter parsing.
                deliverWarm(scenario, "player:$episodeId:stream")
                assertTrue(
                    "a valid explicit target still opens the player",
                    awaitCondition(15_000) {
                        scenario.onTopRoute<PlayerRoute>() &&
                            topPlayerRoute(scenario)?.episodeId == episodeId &&
                            topPlayerRoute(scenario)?.mode == PlayerMode.STREAM
                    },
                )
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }

    /**
     * AQR-138 compatibility — the pre-AQR-138 `player:<episodeId>` form (no
     * mode segment) still means [PlayerMode.DOWNLOAD] (DECISIONS 118.2), so
     * every target already written against the old contract keeps behaving
     * exactly as before. Production no longer EMITS this form for the
     * media-session path; it survives for the documented contract and for
     * targets that never carried a mode.
     */
    @Test
    fun legacyPlayerTargetWithoutModeStillOpensDownloadPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            assertTrue(awaitCondition { scenario.onTopRoute<HomeRoute>() })
            val db = PaheDatabase.getInstance(context)
            val episodeId = seedCompletedEpisodes(db, ensureTestMp4()).single()
            try {
                deliverWarm(scenario, "player:$episodeId")
                assertTrue(
                    "legacy target opens the player",
                    awaitCondition(15_000) { scenario.onTopRoute<PlayerRoute>() },
                )
                assertEquals(episodeId, topPlayerRoute(scenario)?.episodeId)
                assertEquals(
                    "legacy form resolves to DOWNLOAD (DECISIONS 118.2 compatibility)",
                    PlayerMode.DOWNLOAD,
                    topPlayerRoute(scenario)?.mode,
                )
                assertEquals(2, scenario.stackSize())
            } finally {
                runBlocking { db.downloadSessionDao().deleteAll() }
            }
        }
    }
}
