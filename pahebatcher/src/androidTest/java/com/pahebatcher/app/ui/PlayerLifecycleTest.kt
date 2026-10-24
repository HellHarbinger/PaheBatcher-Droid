package com.pahebatcher.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.support.allContentDescriptions
import com.pahebatcher.app.support.allTexts
import com.pahebatcher.app.support.activityIdentity
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.awaitConditionQuietly
import com.pahebatcher.app.support.backStackSize
import com.pahebatcher.app.support.currentPlayerEpisodeId
import com.pahebatcher.app.support.ensureTestMp4
import com.pahebatcher.app.support.isPlayerPaused
import com.pahebatcher.app.support.isPlayerPlaying
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.support.playerDurationSeconds
import com.pahebatcher.app.support.playerEpisodeId
import com.pahebatcher.app.support.playerProbe
import com.pahebatcher.app.support.playerTimeSeconds
import com.pahebatcher.app.support.readSystemSetting
import com.pahebatcher.app.support.saveTestPlaybackPosition
import com.pahebatcher.app.support.playerTestContext
import com.pahebatcher.app.support.seedCompletedEpisodes
import com.pahebatcher.app.support.sliderProgressValues
import com.pahebatcher.app.support.writeSystemSetting
import com.pahebatcher.app.ui.navigation.HomeRoute
import com.pahebatcher.app.ui.navigation.PlayerRoute
import com.pahebatcher.app.ui.navigation.SettingsRoute
import com.pahebatcher.app.ui.navigation.navigateToPlayer
import com.pahebatcher.app.ui.player.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §36-6 — Player lifecycle instrumentation (PHASE11_ARCHITECTURE_V2 §23/§24,
 * F-05/F-13), against the REAL MainActivity, REAL Room database, REAL FFmpeg-
 * generated local MP4 media, and the REAL Media3 player:
 *
 *  1. completed episode plays the local MP4 end-to-end (Downloads Play →
 *     player route, real duration, advancing position, bottom bar hidden);
 *  2. covered-not-popped (Settings pushed over the player) pauses, and
 *     returning does NOT auto-resume (§24.3);
 *  3. backgrounding (activity below RESUMED) pauses, returning does NOT
 *     auto-resume (§24.3);
 *  4. REAL system rotation preserves position + route args + stack + the very
 *     same ExoPlayer instance (§24.4 — manifest has no `configChanges`, so the
 *     system recreates MainActivity with `isChangingConfigurations = true`);
 *  5. in-VM next/previous switches episodes without deepening the stack or
 *     creating another player (§24.2);
 *  6. re-invoking `player(sameId)` while top reuses the single entry (§24.2
 *     launchSingleTop / §36-5 duplicate safety);
 *  7. route exit releases the ExoPlayer exactly once (§24.1 no-leak);
 *  8. cold entry restores the persisted position before play (§24.5/§29).
 *
 * Seeding notes: sessions are wiped per test for a deterministic single Play
 * button; episode ids are run-unique (stale `playback_position` rows are inert
 * — §29 has no delete API by design); the media file is one cached real MP4.
 */
@RunWith(AndroidJUnit4::class)
class PlayerLifecycleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var db: PaheDatabase
    private lateinit var mediaPath: String

    @Before
    fun setUp() {
        db = PaheDatabase.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        mediaPath = ensureTestMp4()
    }

    // -----------------------------------------------------------------------
    // 1. End-to-end: Downloads Play → real local MP4 playback
    // -----------------------------------------------------------------------

    @Test
    fun completedEpisodePlaysRealLocalMp4EndToEnd() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()

        composeRule.onNodeWithText("Downloads").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Play").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Play").performClick()

        assertTrue(
            "Play opens the single player route",
            awaitCondition(15_000) { topRouteIs<PlayerRoute>() },
        )
        assertTrue(
            "always-visible episode title renders",
            awaitCondition(10_000) { titleShows("1") },
        )
        awaitPlaying()
        assertEquals(
            "valid local media produces no error/retry state",
            0,
            composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().size,
        )

        composeRule.waitForIdle()
        // §36-6 progress truth = the entry's ExoPlayer itself; the rendered clock
        // (the only visual progress signal on this text-only surface) is asserted
        // for liveness right after — a frozen label must not mask a stalled player,
        // nor a stalled player a frozen label.
        val advanced = awaitCondition(6_000) {
            (composeRule.playerProbe()?.positionMs ?: -1L) >= 1_500L
        }
        assertTrue("playback position advances (probe=${composeRule.playerProbe()})", advanced)
        val textFirst = composeRule.playerTimeSeconds().minOrNull()
        val sliderFirst = composeRule.sliderProgressValues()
        Thread.sleep(2_000)
        val textLater = composeRule.playerTimeSeconds().minOrNull()
        val sliderLater = composeRule.sliderProgressValues()
        val ticks = textFirst != null && textLater != null && textLater > textFirst
        assertTrue(
            "player position text ticks (first=$textFirst later=$textLater " +
                "sliderFirst=$sliderFirst sliderLater=$sliderLater " +
                "texts=${composeRule.allTexts()} probe=${composeRule.playerProbe()})",
            ticks,
        )
        val sliderAdvanced = sliderFirst.isNotEmpty() &&
            sliderLater.isNotEmpty() &&
            sliderLater.max() > sliderFirst.max()
        assertTrue(
            "progress slider thumb advances (first=$sliderFirst later=$sliderLater " +
                "probe=${composeRule.playerProbe()})",
            sliderAdvanced,
        )
        assertEquals(
            "bottom bar hidden while the player is top (§10.1)",
            0,
            composeRule.onAllNodesWithText("Home").fetchSemanticsNodes().size,
        )
        assertEquals(
            "player route carries only the episode id (§10.3)",
            episodeId,
            composeRule.runOnIdle { currentPlayerEpisodeId(composeRule.activity) },
        )
    }

    // -----------------------------------------------------------------------
    // 2. Covered-not-popped pause + no auto-resume (§24.3)
    // -----------------------------------------------------------------------

    @Test
    fun coveringPlayerPausesAndReturningDoesNotAutoResume() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()
        openPlayer(episodeId)
        awaitPlaying()
        val stackBefore = stackSize()
        val liveBefore = PlayerViewModel.livePlayerCount.get()
        val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

        // Test-seam push: in-app nothing can cover the player while it is top
        // (bar hidden, back pops, notification pops first) — §36-5 invariant.
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigate(SettingsRoute)
        }
        assertTrue(
            "Settings covers the player (stack +1)",
            awaitCondition(10_000) {
                stackSize() == stackBefore + 1 && topRouteIs<SettingsRoute>()
            },
        )
        Thread.sleep(500) // allow the entry's ON_PAUSE → pause + persist to land

        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(
            "back returns to the SAME player entry",
            awaitCondition(10_000) {
                topRouteIs<PlayerRoute>() && stackSize() == stackBefore
            },
        )
        assertTrue(
            "no auto-resume on re-visibility (§24.3)",
            awaitCondition(10_000) { composeRule.isPlayerPaused() },
        )
        Thread.sleep(500)
        val frozen = requireNotNull(composeRule.playerProbe()).positionMs
        Thread.sleep(1_500)
        assertEquals(
            "position frozen while paused (still paused, no resume)",
            frozen,
            composeRule.playerProbe()?.positionMs,
        )
        assertEquals(
            "player entry survived the cover — no new player",
            liveBefore,
            PlayerViewModel.livePlayerCount.get(),
        )
        assertEquals(
            "no player was released while merely covered",
            releasedBefore,
            PlayerViewModel.releasedPlayerCount.get(),
        )
    }

    // -----------------------------------------------------------------------
    // 3. Background pause + no auto-resume (§24.3)
    // -----------------------------------------------------------------------

    @Test
    fun backgroundingPausesAndReturningDoesNotAutoResume() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()
        openPlayer(episodeId)
        awaitPlaying()
        val liveBefore = PlayerViewModel.livePlayerCount.get()
        val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(1_000) // entry drops below RESUMED → pause + persist
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertTrue(
            "background return never auto-resumes (§24.3)",
            awaitCondition(10_000) { composeRule.isPlayerPaused() },
        )
        assertTrue(topRouteIs<PlayerRoute>())
        Thread.sleep(500)
        val frozen = requireNotNull(composeRule.playerProbe()).positionMs
        Thread.sleep(1_500)
        assertEquals(
            "position frozen across re-visibility (still paused)",
            frozen,
            composeRule.playerProbe()?.positionMs,
        )
        assertEquals(liveBefore, PlayerViewModel.livePlayerCount.get())
        assertEquals(releasedBefore, PlayerViewModel.releasedPlayerCount.get())
    }

    // -----------------------------------------------------------------------
    // 4. Real system rotation preserves position + state + player (§24.4)
    // -----------------------------------------------------------------------

    @Test
    fun rotationPreservesPositionRouteStackAndPlayerInstance() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()
        openPlayer(episodeId)
        awaitPlaying()
        val ticking = awaitCondition(10_000) {
            (composeRule.playerProbe()?.positionMs ?: -1L) >= 1_000L
        }
        assertTrue("position ticking before rotation (probe=${composeRule.playerProbe()})", ticking)
        val before = requireNotNull(composeRule.playerProbe()).positionMs
        val stackBefore = stackSize()
        val liveBefore = PlayerViewModel.livePlayerCount.get()
        val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

        val accelerometer = readSystemSetting("accelerometer_rotation")
        val rotation = readSystemSetting("user_rotation")
        val identityBefore = composeRule.activityRule.scenario.activityIdentity()
        try {
            writeSystemSetting("accelerometer_rotation", "0")
            writeSystemSetting("user_rotation", if (rotation == "0") "1" else "0")

            assertTrue(
                "real system rotation recreates MainActivity",
                awaitConditionQuietly(20_000) {
                    val identity = composeRule.activityRule.scenario.activityIdentity()
                    identity != -1 && identity != identityBefore
                },
            )
            assertTrue(
                "player route + episode arg survive rotation",
                awaitConditionQuietly(15_000) {
                    topRouteIs<PlayerRoute>() &&
                        composeRule.activityRule.scenario.playerEpisodeId() == episodeId
                },
            )
            assertEquals(
                "back-stack depth preserved across rotation",
                stackBefore,
                stackSize(),
            )
            assertTrue(
                "playback resumed after rotation (was playing before)",
                awaitCondition(15_000) { composeRule.isPlayerPlaying() },
            )
            val neverRewound = awaitCondition(15_000) {
                (composeRule.playerProbe()?.positionMs ?: -1L) >= before
            }
            assertTrue(
                "position never rewound by rotation (before=${before}ms probe=${composeRule.playerProbe()})",
                neverRewound,
            )
            assertEquals(
                "same ViewModel → no additional ExoPlayer created",
                liveBefore,
                PlayerViewModel.livePlayerCount.get(),
            )
            assertEquals(
                "same ViewModel → no ExoPlayer released during rotation",
                releasedBefore,
                PlayerViewModel.releasedPlayerCount.get(),
            )
        } finally {
            writeSystemSetting("user_rotation", rotation)
            writeSystemSetting("accelerometer_rotation", accelerometer)
        }
    }

    // -----------------------------------------------------------------------
    // 5. In-VM next/previous — no stacked routes, no second player (§24.2)
    // -----------------------------------------------------------------------

    @Test
    fun inPlayerNextAndPreviousSwitchEpisodesWithoutStackingRoutes() {
        val ids = seedCompletedEpisodes(db, mediaPath, count = 2)
        openPlayer(ids[0])
        assertTrue(
            "seeded episode 1 title renders",
            awaitCondition(10_000) { titleShows("1") },
        )
        awaitPlaying()
        val stackBefore = stackSize()
        val liveBefore = PlayerViewModel.livePlayerCount.get()

        composeRule.onNodeWithContentDescription("Next").performClick()
        assertTrue(
            "Next switches to episode 2 in place",
            awaitCondition(10_000) { titleShows("2") },
        )
        assertTrue(topRouteIs<PlayerRoute>())
        assertEquals(
            "next must not deepen the stack (§24.2 in-VM switch)",
            stackBefore,
            stackSize(),
        )

        composeRule.onNodeWithContentDescription("Previous").performClick()
        assertTrue(
            "Previous returns to episode 1 in place",
            awaitCondition(10_000) { titleShows("1") },
        )
        assertTrue(topRouteIs<PlayerRoute>())
        assertEquals(stackBefore, stackSize())
        assertEquals(
            "route arg unchanged — switching never routes through navigate",
            ids[0],
            composeRule.runOnIdle { currentPlayerEpisodeId(composeRule.activity) },
        )
        assertEquals(
            "still exactly one ExoPlayer",
            liveBefore,
            PlayerViewModel.livePlayerCount.get(),
        )
        assertTrue(
            "controls + title still coherent after switching",
            composeRule.allContentDescriptions().any { it == "Next" } && titleShows("1"),
        )
    }

    // -----------------------------------------------------------------------
    // 6. Re-invoking player(sameId) while top — single entry, single player
    // -----------------------------------------------------------------------

    @Test
    fun reInvokingPlayerForSameEpisodeDoesNotDuplicateEntryOrPlayer() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()
        openPlayer(episodeId)
        awaitPlaying()
        val stackBefore = stackSize()
        val liveBefore = PlayerViewModel.livePlayerCount.get()
        val releasedBefore = PlayerViewModel.releasedPlayerCount.get()

        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigateToPlayer(episodeId)
        }
        Thread.sleep(1_000) // allow any (incorrect) navigation to settle

        assertTrue(topRouteIs<PlayerRoute>())
        assertEquals(
            "launchSingleTop reuses the existing entry (no duplicate)",
            stackBefore,
            stackSize(),
        )
        assertEquals(
            "re-entry must not construct another ExoPlayer",
            liveBefore,
            PlayerViewModel.livePlayerCount.get(),
        )
        assertEquals(
            "re-entry must not release the live ExoPlayer",
            releasedBefore,
            PlayerViewModel.releasedPlayerCount.get(),
        )
        assertEquals(
            "route arg still the same episode",
            episodeId,
            composeRule.runOnIdle { currentPlayerEpisodeId(composeRule.activity) },
        )
    }

    // -----------------------------------------------------------------------
    // 7. Route exit releases the ExoPlayer (§24.1 no-leak)
    // -----------------------------------------------------------------------

    @Test
    fun poppingThePlayerEntryReleasesItsExoPlayer() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()
        val liveBeforeOpen = PlayerViewModel.livePlayerCount.get()
        val releasedBefore = PlayerViewModel.releasedPlayerCount.get()
        openPlayer(episodeId)
        awaitPlaying()
        assertEquals(
            "opening the player created exactly one ExoPlayer",
            liveBeforeOpen + 1,
            PlayerViewModel.livePlayerCount.get(),
        )

        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(
            "Back pops the player to Home",
            awaitCondition(10_000) { topRouteIs<HomeRoute>() },
        )
        assertEquals(1, stackSize())
        assertTrue(
            "player entry exit released the ExoPlayer exactly once",
            awaitCondition(10_000) {
                PlayerViewModel.releasedPlayerCount.get() >= releasedBefore + 1 &&
                    PlayerViewModel.livePlayerCount.get() == liveBeforeOpen
            },
        )
    }

    // -----------------------------------------------------------------------
    // 8. Cold entry restores the persisted position before play (§24.5/§29)
    // -----------------------------------------------------------------------

    @Test
    fun coldPlayerEntryRestoresSavedPositionBeforePlay() {
        val episodeId = seedCompletedEpisodes(db, mediaPath).single()
        saveTestPlaybackPosition(db, episodeId, positionMs = 8_000, durationMs = 60_000)

        openPlayer(episodeId)
        val restored = awaitCondition(6_000) {
            (composeRule.playerProbe()?.positionMs ?: -1L) >= 7_000L
        }
        assertTrue(
            "first position sample ≥ 7s within 6s of entry (restored, not 0) probe=${composeRule.playerProbe()}",
            restored,
        )
        // §36-6 probes are authoritative (rendered time labels race the
        // controls auto-hide on cold entry — timeline arrival can exceed the
        // initial controls-visible window on a cold emulator start).
        assertTrue(
            "duration discriminator: real ~60s media is loaded (probe=${composeRule.playerProbe()}, " +
                "texts=${composeRule.allTexts()})",
            awaitCondition(10_000) { (composeRule.playerProbe()?.durationMs ?: 0L) >= 55_000L },
        )
        val seedCopies = playerTestContext().filesDir
            .listFiles { f -> f.isDirectory && f.name.startsWith("p11_seed_") }
            ?.joinToString { d -> "${d.name}=${d.listFiles()?.sumOf { it.length() } ?: 0L}" }
            ?: "none"
        assertTrue(
            "restored playback plays (not paused) probe=${composeRule.playerProbe()} " +
                "cachedBytes=${java.io.File(mediaPath).length()} copies=$seedCopies",
            awaitCondition(10_000) {
                composeRule.playerProbe()?.isPlaying == true
            },
        )
        assertEquals(
            "no load error for a restored, valid episode",
            0,
            composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().size,
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Opens `player(episodeId)` through the MainActivity navController seam. */
    private fun openPlayer(episodeId: String) {
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigateToPlayer(episodeId)
        }
        assertTrue(
            "player route opens for $episodeId",
            awaitCondition(15_000) { topRouteIs<PlayerRoute>() },
        )
    }

    /** Awaits active playback + the real ~60 s duration exposure. */
    private fun awaitPlaying(timeoutMs: Long = 15_000) {
        assertTrue(
            "playback is active (Pause control exposed)",
            awaitCondition(timeoutMs) { composeRule.isPlayerPlaying() },
        )
        assertTrue(
            "real ~60s duration exposed",
            awaitCondition(timeoutMs) { (composeRule.playerDurationSeconds() ?: 0.0) >= 55.0 },
        )
    }

    /** True when the always-visible title names the given episode label. */
    private fun titleShows(episodeLabel: String): Boolean =
        composeRule.allTexts().any { text ->
            text.startsWith("Phase11 Player Test") && text.endsWith("Episode $episodeLabel")
        }

    private fun stackSize(): Int {
        val activity = composeRule.activity // read on the test thread (canonical usage)
        return composeRule.runOnIdle { backStackSize(activity) }
    }

    private inline fun <reified T : Any> topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<T>(activity) }
    }
}
