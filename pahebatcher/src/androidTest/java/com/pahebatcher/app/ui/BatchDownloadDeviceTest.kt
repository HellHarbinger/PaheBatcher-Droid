package com.pahebatcher.app.ui

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.export.OutputPathNames
import com.pahebatcher.app.support.SettingsTestSupport
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.support.playerProbe
import com.pahebatcher.app.ui.navigation.PlayerRoute
import com.pahebatcher.app.ui.navigation.navigateToPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 12C device verification — the BATCH path end to end on a physical device,
 * through the production application (real Activity, real WebView Cloudflare
 * session, real resolver, real WorkManager download, real export):
 *
 * `Home search → details scan → batch mode → select episode 1 by number →
 *  "Download selected (1)" → resolution → DownloadWorker → assembly → export`.
 *
 * The media file this produces is the Android half of the Phase 12C media
 * comparison against the CURRENT Python reference output (the post-fix
 * `SegmentStore.fix_aac_esds` build), so this test doubles as the batch
 * download device verification required by the phase exit criteria.
 *
 * What it asserts, in the order the pipeline can fail:
 *  1. the production scan returns a real listing (`Episode 1` present);
 *  2. batch mode selects episode 1 **by number** (`Download selected (1)` and
 *     row 1 rendered selected);
 *  3. starting the batch reaches Room — a new episode row appears carrying the
 *     RESOLVED stream (proves the foreground resolution ran through the real
 *     WebView/Kwik chain);
 *  4. the row reaches `COMPLETED` with a MediaStore output under the Gate A
 *     per-anime directory `Movies/<sanitized anime title>/` (never the legacy
 *     flat folder), and the batch summary in the UI reports exactly that one
 *     episode queued;
 *  5. the exported file opens and plays through the PRODUCTION playback path
 *     (PlayerRoute → PlaybackSourceResolver → the persisted `content://` URI).
 *
 * The download is real and can take several minutes; every wait is bounded and
 * logs the observed row state so a failure is diagnosable from Logcat.
 */
@RunWith(AndroidJUnit4::class)
class BatchDownloadDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    /** Latch state before this suite pre-set it — restored in [tearDown]. */
    private var savedAskedOnce = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = PaheDatabase.getInstance(context)
        // Python reference defaults — the media comparison assumes quality 1080,
        // exactly like the reference run. Audio needs no reset line anymore:
        // the batch takes episode 1's OWN row audio (per-episode selection,
        // SPEC §22), and this fixture's episode 1 carries `jpn` — the same
        // variant the reference downloads.
        SettingsTestSupport.resetToDefaults(context)
        // Decision 95.A precondition: the one-time first-enqueue
        // POST_NOTIFICATIONS prompt is orthogonal UX behavior, covered end to
        // end by FirstEnqueuePromptTest and NotificationPermissionTest. This
        // suite drives the REAL AnimeDetails enqueue path, so a fresh latch
        // would arm the real system dialog — and because the device screen
        // times out during the minutes-long download, the deferred launch
        // lands on the first frame after completion, pausing MainActivity in
        // the middle of the final summary assertion ("no compose
        // hierarchies"). Pre-setting the latch to the realistic "user already
        // answered" state removes that dialog; every batch assertion below is
        // unchanged.
        val appConfig = AppConfig(context)
        runBlocking {
            savedAskedOnce = appConfig
                .getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, false)
                .first()
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, true)
        }
    }

    @After
    fun tearDown() {
        // Restore the prompt latch this suite pre-set (JUnit runs this even on
        // failure), so the prompt-gated suites still see the state they expect.
        runBlocking {
            AppConfig(context).setBoolean(
                AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE,
                savedAskedOnce,
            )
        }
        // Nothing to cancel: the batch either finished or the assertions above
        // already recorded why it did not. The rows are left in place — they are
        // the evidence for this phase, and the Downloads screen owns them.
    }

    /** Snapshot of every row created by this run (newest last). */
    private fun rowsSince(since: Long): List<EpisodeDownloadEntity> = runBlocking {
        db.episodeDownloadDao().observeAllOrdered().first()
            .filter { it.createdAt >= since - 5_000L }
            .sortedBy { it.createdAt }
    }

    /**
     * Bounded poll over the REAL Room rows. Logs the observed state every 15 s
     * so a slow or stuck download is visible in Logcat rather than a bare
     * timeout.
     */
    private fun awaitRow(
        since: Long,
        timeoutMs: Long,
        predicate: (EpisodeDownloadEntity) -> Boolean,
    ): EpisodeDownloadEntity? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLogged = 0L
        while (System.currentTimeMillis() < deadline) {
            val rows = rowsSince(since)
            rows.firstOrNull(predicate)?.let { return it }
            val now = System.currentTimeMillis()
            if (now - lastLogged >= 15_000L) {
                lastLogged = now
                Log.i(
                    TAG,
                    "ADB_PROGRESS " + rows.joinToString(" | ") {
                        "${it.episodeId} state=${it.state} " +
                            "segments=${it.completedSegments}/${it.totalSegments} bytes=${it.downloadedBytes}"
                    },
                )
            }
            Thread.sleep(1_000L)
        }
        return rowsSince(since).firstOrNull(predicate)
    }

    /** MediaStore display name of an exported output (for the adb pull step). */
    private fun displayNameOf(uriString: String): String? = runCatching {
        val uri = Uri.parse(uriString)
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private fun sizeOf(uriString: String): Long? = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { it.statSize }
    }.getOrNull()

    @Test
    fun batchSelectingOneEpisodeDownloadsItThroughTheProductionPipeline() {
        val startedAt = System.currentTimeMillis()

        // 1. Real search over the production (WebView-backed) HTTP session.
        //    The row is selected by its TAG, not by its text: the exact query
        //    text also exists in the search field, so a text lookup would tap
        //    the field and never navigate.
        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.onNodeWithTag("search_field").performTextInput("One Piece")
        composeRule.onNodeWithTag("search_field").performImeAction()
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithTag("search_result_row").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("search_result_row")[0]
            .assert(hasText("One Piece", substring = true))

        // 2. Production navigation into the details destination.
        composeRule.onAllNodesWithTag("search_result_row")[0].performClick()

        // 3. The real scan must return a listing before anything can be batched.
        //    One Piece is the WORST case the pipeline has: ~1,160 episodes over
        //    ~47 API pages plus variant discovery and the Cloudflare handshake —
        //    measured at ~256 s on this device, so the bound is 10 minutes.
        //    The state is logged while waiting so a slow scan is visible.
        val listingWaitStart = System.currentTimeMillis()
        var listingReady = false
        try {
            composeRule.waitUntil(600_000) {
                composeRule.onAllNodesWithTag("anime_details_episode").fetchSemanticsNodes().isNotEmpty()
                    .also { if (it) listingReady = true }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB_DIAG listing wait failed after ${System.currentTimeMillis() - listingWaitStart}ms: ${e.message}")
            throw e
        }
        assertTrue(
            "the production scan must produce the listing for One Piece",
            listingReady,
        )
        composeRule.onNodeWithText("Episode 1").assertExists()

        // 4. Batch mode → select episode 1 (by NUMBER) → start the batch.
        composeRule.onNodeWithTag("anime_details_batch_toggle").performClick()
        composeRule.onNodeWithTag("anime_details_batch_panel").assertIsDisplayed()
        composeRule.onAllNodesWithTag("anime_details_episode")[0].performClick()
        composeRule.onNodeWithText("Download selected (1)").assertIsDisplayed()
        composeRule.onAllNodesWithTag("anime_details_episode")[0].assertIsSelected()
        composeRule.onNodeWithTag("anime_details_batch_download").performClick()

        // 5. Resolution succeeded → a NEW row exists with the resolved stream.
        val queued = awaitRow(startedAt, timeoutMs = 5 * 60_000L) { row ->
            row.m3u8Url != null
        }
        assertNotNull(
            "the batch must resolve episode 1 and persist the resolved stream " +
                "(rows: ${rowsSince(startedAt).joinToString { "${it.episodeId}/${it.state}/${it.errorMessage}" }})",
            queued,
        )
        Log.i(TAG, "ADB_RESOLVED episodeId=${queued!!.episodeId} audio=${queued.audio} number=${queued.episodeNumber}")
        assertEquals(
            "the batch resolves the row's own audio (jpn for this fixture) — per-episode choice, no global audio_lang",
            "jpn",
            queued.audio,
        )
        assertEquals("selection by episode number queues exactly episode 1", 1f, queued.episodeNumber)

        // 6. The real worker downloads, assembles and exports the file.
        //
        //    COMPLETED is written BEFORE the MediaStore export runs (DownloadWorker
        //    `updateCompletedIfState` → `exportToMediaStore`), so waiting for the state
        //    alone and reading outputUri immediately is a race the test can lose. The
        //    contract this test means to verify is "the MP4 was exported", so wait for
        //    the exported row itself (export is idempotent and reconciled on startup,
        //    so a missing uri here would be a real export failure, not a timing note).
        val completed = awaitRow(startedAt, timeoutMs = 25 * 60_000L) { row ->
            row.state == EpisodeDownloadEntity.STATE_COMPLETED && row.outputUri != null
        }
        assertNotNull(
            "the batch download must complete with its file exported into " +
                "Movies/<anime title>/ (rows: " +
                rowsSince(startedAt).joinToString {
                    "${it.episodeId} ${it.state} ${it.completedSegments}/${it.totalSegments} err=${it.errorMessage}"
                } + ")",
            completed,
        )
        val outputUri = completed!!.outputUri
        assertNotNull(
            "the completed episode must be exported into its per-anime directory",
            outputUri,
        )

        // 6b. Gate A destination check: the REAL session title (from Room)
        //     derives the directory; the exported row must live there and
        //     must NOT be in the legacy flat folder.
        val session = runBlocking {
            db.downloadSessionDao().getById(completed.sessionId)
        }
        assertNotNull("the parent session row must exist", session)
        val expectedDir = OutputPathNames.mediaStoreRelativePath(
            session!!.title, session.sessionId,
        )
        val actualDir = relativePathOf(outputUri!!)
        Log.i(TAG, "ADB_DEST expected=$expectedDir actual=$actualDir sessionTitle=${session.title}")
        assertEquals(
            "the export must land in the sanitized per-anime directory",
            expectedDir, actualDir,
        )
        assertNotEquals(
            "Gate A: a NEW export must never use the legacy flat folder",
            OutputPathNames.LEGACY_RELATIVE_PATH, actualDir,
        )

        // 7. The UI reports the finished batch honestly (the summary line is
        //    "Done: queued 1 · skipped 0 · failed 0 of 1", so match its prefix).
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithText("Done: queued 1", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Evidence line for the Phase 12C media comparison (parsed from Logcat).
        val name = displayNameOf(outputUri!!)
        val bytes = sizeOf(outputUri)
        Log.i(
            TAG,
            "ADB_OUT episodeId=${completed.episodeId} displayName=$name sizeBytes=$bytes " +
                "outputPath=${completed.outputPath} outputUri=$outputUri",
        )
        assertNotNull("the exported file must have a display name for the adb pull", name)
        assertTrue(
            "the exported file must be a non-trivial MP4, was $bytes bytes",
            (bytes ?: 0L) > 1_000_000L,
        )
        assertEquals(
            "exactly one episode was batched",
            1,
            rowsSince(startedAt).count { it.episodeNumber == 1f },
        )

        // 8. Gate A device verification: the exported file opens and plays
        //    through the PRODUCTION playback path — PlayerRoute →
        //    PlaybackSourceResolver → the persisted MediaStore content:// URI
        //    (same seam SafPlaybackDeviceTest verifies for SAF children).
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigateToPlayer(completed.episodeId)
        }
        assertTrue(
            "player route must open for the exported episode",
            awaitCondition(20_000) { topRouteIs() },
        )
        val loaded = awaitCondition(30_000) {
            (composeRule.playerProbe()?.durationMs ?: 0L) > 0L
        }
        assertTrue(
            "the exported MediaStore file must load as real media " +
                "(probe=${composeRule.playerProbe()})",
            loaded,
        )
        val playing = awaitCondition(30_000) {
            composeRule.playerProbe()?.isPlaying == true
        }
        assertTrue(
            "the exported file must play through the production player " +
                "(probe=${composeRule.playerProbe()})",
            playing,
        )
        Log.i(TAG, "ADB_PLAYBACK verified for episodeId=${completed.episodeId}")
    }

    /** The `RELATIVE_PATH` of the MediaStore row behind [uriString]. */
    private fun relativePathOf(uriString: String): String? = runCatching {
        context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Top-of-stack check used by the playback verification. */
    private fun topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<PlayerRoute>(activity) }
    }

    private companion object {
        const val TAG = "BatchDownloadDeviceTest"
    }
}
