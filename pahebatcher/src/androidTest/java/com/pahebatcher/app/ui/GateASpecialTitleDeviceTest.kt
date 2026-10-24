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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate A Device Tests 2 (+5) — production flow with a SPECIAL-CHARACTER title
 * and a batch of TWO episodes from the same anime, on a physical device:
 *
 * `Home search ("Rearguard") → details scan → batch mode → select episodes
 *  1 and 2 → "Download selected (2)" → resolution → DownloadWorker ×2 →
 *  assembly ×2 → export ×2 into Movies/<sanitized anime title>/`.
 *
 * What it asserts, in the order the pipeline can fail:
 *  1. the production scan returns a real listing (`Episode 1` / `Episode 2`);
 *  2. batch mode selects TWO episode numbers (`Download selected (2)` and
 *     both rows selected);
 *  3. starting the batch reaches Room — two NEW episode rows appear carrying
 *     the RESOLVED streams (episodes 1 and 2, audio jpn);
 *  4. both rows reach `COMPLETED` with MediaStore outputs under the SAME
 *     per-anime directory derived from the REAL session title — special
 *     characters removed per Python `sanitize()` parity, never the legacy
 *     flat folder, never a fallback constant — and the run left EXACTLY
 *     `episodes` exported rows and no more;
 *  5. the batch summary reports exactly `queued 2`;
 *  6. EVERY exported file opens and plays through the PRODUCTION playback
 *     path (PlayerRoute → PlaybackSourceResolver → content://), backing out
 *     of the player between them.
 *
 * Why playback lives here rather than in its own class: it used to.
 * `GateASecondEpisodePlaybackDeviceTest` re-queried THIS run's session for
 * its exports, but that precondition can never hold — a full run executes
 * classes alphabetically, so `GateASecond…` starts BEFORE `GateASp…`; the
 * Gradle class filter honours only the FIRST class handed to it; and the
 * connected task uninstalls both packages after every invocation, so no
 * cross-invocation state can satisfy it either (LIMITATIONS §41.5). Playing
 * every export inside the same method that produced them removes the
 * ordering dependency entirely, and that class was deleted.
 *
 * Parameterized through instrumentation arguments so the identical flow can
 * serve the Unicode-title device test and the legacy-adoption leg:
 *  `-e query <search> -e rowText <row substring> -e episodes <n>
 *   -e expectedCase special|unicode|none [-e expectedDir <relative path>]`
 *  (defaults: Rearguard / Rearguard / 2 / special). `expectedDir` asserts an
 *  exact destination instead of the computed per-anime path — used where a
 *  byte-identical re-download must re-adopt a pre-Gate-A legacy file.
 */
@RunWith(AndroidJUnit4::class)
class GateASpecialTitleDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    private val query: String
        get() = InstrumentationRegistry.getArguments().getString("query")
            ?: "Rearguard"
    private val rowText: String
        get() = InstrumentationRegistry.getArguments().getString("rowText")
            ?: "Rearguard"
    private val episodeCount: Int
        get() = InstrumentationRegistry.getArguments().getString("episodes")
            ?.toIntOrNull() ?: 2
    private val expectedCase: String
        get() = InstrumentationRegistry.getArguments().getString("expectedCase")
            ?: "special"
    /**
     * Optional exact RELATIVE_PATH to assert instead of the computed
     * per-anime path — used by the legacy-adoption leg (`Movies/PaheBatcher/`)
     * where a byte-identical re-download must adopt the pre-Gate-A file.
     */
    private val expectedDirOverride: String?
        get() = InstrumentationRegistry.getArguments().getString("expectedDir")

    /** Latch state before this suite pre-set it — restored in [tearDown]. */
    private var savedAskedOnce = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = PaheDatabase.getInstance(context)
        SettingsTestSupport.resetToDefaults(context)
        // Same Decision 95.A precondition as BatchDownloadDeviceTest: pre-set
        // the one-time notification prompt latch so the deferred system dialog
        // cannot pause MainActivity mid-assertion during the long download.
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
        runBlocking {
            AppConfig(context).setBoolean(
                AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE,
                savedAskedOnce,
            )
        }
        // Rows stay in place — they are the Gate A device evidence.
    }

    /** Snapshot of every row created by this run (newest last). */
    private fun rowsSince(since: Long): List<EpisodeDownloadEntity> = runBlocking {
        db.episodeDownloadDao().observeAllOrdered().first()
            .filter { it.createdAt >= since - 5_000L }
            .sortedBy { it.createdAt }
    }

    /** Bounded poll until at least [minCount] rows match — logs progress. */
    private fun awaitRows(
        since: Long,
        timeoutMs: Long,
        minCount: Int,
        predicate: (EpisodeDownloadEntity) -> Boolean,
    ): List<EpisodeDownloadEntity> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLogged = 0L
        while (System.currentTimeMillis() < deadline) {
            val rows = rowsSince(since).filter(predicate)
            if (rows.size >= minCount) return rows
            val now = System.currentTimeMillis()
            if (now - lastLogged >= 15_000L) {
                lastLogged = now
                Log.i(
                    TAG,
                    "ADB_PROGRESS matched=${rows.size}/$minCount " +
                        rowsSince(since).joinToString(" | ") {
                            "${it.episodeId} num=${it.episodeNumber} state=${it.state} " +
                                "seg=${it.completedSegments}/${it.totalSegments} " +
                                "bytes=${it.downloadedBytes} err=${it.errorMessage}"
                        },
                )
            }
            Thread.sleep(1_000L)
        }
        return rowsSince(since).filter(predicate)
    }

    private fun sizeOf(uriString: String): Long? = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { it.statSize }
    }.getOrNull()

    private fun relativePathOf(uriString: String): String? = runCatching {
        context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun displayNameOf(uriString: String): String? = runCatching {
        context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    @Test
    fun specialCharTitleBatchOfTwoExportsBothIntoTheSanitizedAnimeDirectory() {
        val startedAt = System.currentTimeMillis()
        Log.i(
            TAG,
            "ADB_CASE query=$query rowText=$rowText episodes=$episodeCount case=$expectedCase",
        )

        // 1. Real search over the production (WebView-backed) HTTP session.
        composeRule.onNodeWithTag("search_field").performClick()
        composeRule.onNodeWithTag("search_field").performTextInput(query)
        composeRule.onNodeWithTag("search_field").performImeAction()
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithTag("search_result_row").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("search_result_row")[0]
            .assert(hasText(rowText, substring = true))

        // 2. Production navigation into the details destination.
        composeRule.onAllNodesWithTag("search_result_row")[0].performClick()

        // 3. The real scan must return a listing before anything can be batched.
        composeRule.waitUntil(600_000) {
            composeRule.onAllNodesWithTag("anime_details_episode")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Episode 1").assertExists()
        composeRule.onNodeWithText("Episode 2").assertExists()

        // 4. Batch mode → select episode numbers 1 and 2 → start the batch.
        composeRule.onNodeWithTag("anime_details_batch_toggle").performClick()
        composeRule.onNodeWithTag("anime_details_batch_panel").assertIsDisplayed()
        repeat(episodeCount) { i ->
            composeRule.onAllNodesWithTag("anime_details_episode")[i].performClick()
        }
        composeRule.onNodeWithText("Download selected ($episodeCount)").assertIsDisplayed()
        repeat(episodeCount) { i ->
            composeRule.onAllNodesWithTag("anime_details_episode")[i].assertIsSelected()
        }
        composeRule.onNodeWithTag("anime_details_batch_download").performClick()

        // 5. Resolution succeeded → episodeCount NEW rows with resolved streams.
        val resolved = awaitRows(startedAt, timeoutMs = 8 * 60_000L, minCount = episodeCount) {
            it.m3u8Url != null
        }
        assertTrue(
            "the batch must resolve $episodeCount episodes (got ${resolved.size}: " +
                resolved.joinToString { "${it.episodeId}/${it.state}/${it.errorMessage}" } + ")",
            resolved.size >= episodeCount,
        )
        val numbers = resolved.map { it.episodeNumber }.toSet()
        val expectedNumbers = (1..episodeCount).map { it.toFloat() }.toSet()
        assertTrue(
            "episodes $expectedNumbers must all be resolved, got $numbers",
            numbers.containsAll(expectedNumbers),
        )
        resolved.forEach { row ->
            assertEquals(
                "the batch resolves each row's own audio (jpn for this fixture) — per-episode choice, no global audio_lang",
                "jpn", row.audio,
            )
        }
        Log.i(TAG, "ADB_RESOLVED " + resolved.joinToString { "${it.episodeId} num=${it.episodeNumber}" })

        // 6. The real worker downloads, assembles and exports BOTH files
        //    (COMPLETED precedes export → wait for the exported row itself).
        val completed = awaitRows(
            startedAt, timeoutMs = 35 * 60_000L, minCount = episodeCount,
        ) { row ->
            row.state == EpisodeDownloadEntity.STATE_COMPLETED && row.outputUri != null
        }
        assertTrue(
            "all $episodeCount episodes must complete with exported files (got " +
                completed.size + ": " +
                rowsSince(startedAt).joinToString {
                    "${it.episodeId} ${it.state} ${it.completedSegments}/${it.totalSegments} err=${it.errorMessage}"
                } + ")",
            completed.size >= episodeCount,
        )
        // The run must have left EXACTLY its own exports — this is the assertion
        // `GateASecondEpisodePlaybackDeviceTest` carried, scoped to rows this run
        // created (`rowsSince` is bounded by `startedAt`) so it cannot be
        // satisfied by, or fail because of, an earlier run's evidence.
        assertEquals(
            "the batch must have left exactly its $episodeCount exported episodes, " +
                "with no strays from this run",
            episodeCount,
            rowsSince(startedAt).count {
                it.state == EpisodeDownloadEntity.STATE_COMPLETED && it.outputUri != null
            },
        )

        // 6b. Gate A destination check — every export under ONE directory
        //     derived from the REAL session title (Room).
        val sessionIds = completed.map { it.sessionId }.toSet()
        assertEquals("both episodes belong to the same session", 1, sessionIds.size)
        val session = runBlocking { db.downloadSessionDao().getById(completed.first().sessionId) }
        assertNotNull("the parent session row must exist", session)
        val rawTitle = session!!.title
        val expectedDir = expectedDirOverride
            ?: OutputPathNames.mediaStoreRelativePath(rawTitle, session.sessionId)
        val dirName = OutputPathNames.directoryName(rawTitle, session.sessionId)
        Log.i(
            TAG,
            "ADB_DEST sessionTitle=[$rawTitle] dirName=$dirName expected=$expectedDir " +
                "override=$expectedDirOverride case=$expectedCase",
        )

        // Case-specific proof that THIS run exercised the intended title shape
        // (skipped on the legacy-adoption leg, which asserts an exact path).
        if (expectedDirOverride == null && expectedCase == "unicode") {
            assertTrue(
                "unicode case needs a non-Latin session title (got: $rawTitle)",
                rawTitle.any { it.code > 127 },
            )
            assertTrue(
                "non-Latin characters must SURVIVE in the directory ($dirName)",
                dirName.any { it.code > 127 },
            )
        } else if (expectedDirOverride == null) {
            val removable = rawTitle.filter { c ->
                !(c.isLetterOrDigit() || c == ' ' || c == '-' || c == '.' || c == '_')
            }
            assertTrue(
                "special-character case not exercised — nothing to sanitize in " +
                    "\"$rawTitle\"",
                removable.isNotEmpty() || rawTitle.contains(' '),
            )
            if (removable.isNotEmpty()) {
                assertTrue(
                    "removed characters must not survive in the directory " +
                        "($dirName from \"$rawTitle\")",
                    removable.none { dirName.contains(it) },
                )
            }
            assertFalse("directory name must not contain spaces", dirName.contains(' '))
        }

        val displayNames = mutableListOf<String>()
        completed.take(episodeCount).forEach { row ->
            val uri = row.outputUri!!
            val actualDir = relativePathOf(uri)
            if (expectedDirOverride != null) {
                assertEquals(
                    "the legacy-adoption run must resolve to the expected " +
                        "destination (byte-identical pre-Gate-A file)",
                    expectedDirOverride, actualDir,
                )
            } else {
                assertEquals(
                    "episode ${row.episodeNumber} must land in the sanitized per-anime directory",
                    expectedDir, actualDir,
                )
                assertNotEquals(
                    "a NEW export must never use the legacy flat folder",
                    OutputPathNames.LEGACY_RELATIVE_PATH, actualDir,
                )
            }
            val size = sizeOf(uri)
            Log.i(
                TAG,
                "ADB_OUT num=${row.episodeNumber} displayName=${displayNameOf(uri)} " +
                    "sizeBytes=$size uri=$uri dir=$actualDir",
            )
            assertTrue(
                "episode ${row.episodeNumber} must be a non-trivial MP4, was $size bytes",
                (size ?: 0L) > 1_000_000L,
            )
            displayNames += displayNameOf(uri) ?: ""
        }
        assertEquals(
            "the two episodes must export under distinct file names: $displayNames",
            episodeCount, displayNames.toSet().size,
        )

        // 7. The UI reports the finished batch honestly.
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithText("Done: queued $episodeCount", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 8. Production playback of EVERY freshly exported file — the contract
        //    `GateASecondEpisodePlaybackDeviceTest` used to own. It is asserted
        //    here, inside the method that produced the exports, so it depends on
        //    neither class ordering, nor a multi-class filter that honours only
        //    its first class, nor surviving the connected task's package wipe
        //    (LIMITATIONS §41.5).
        completed.forEach { row -> assertPlays(row.episodeId) }
        Log.i(TAG, "ADB_PLAYBACK count=${completed.size} of $episodeCount")
    }

    /**
     * Plays [episodeId] through production, asserts it really plays, then backs
     * OUT of the player so the next episode can drive its own navigation.
     */
    private fun assertPlays(episodeId: String) {
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigateToPlayer(episodeId)
        }
        assertTrue(
            "player route must open for the exported episode $episodeId",
            awaitCondition(20_000) { topRouteIs() },
        )
        val loaded = awaitCondition(30_000) {
            (composeRule.playerProbe()?.durationMs ?: 0L) > 0L
        }
        assertTrue(
            "the exported file must load as real media for $episodeId " +
                "(probe=${composeRule.playerProbe()})",
            loaded,
        )
        val playing = awaitCondition(30_000) {
            composeRule.playerProbe()?.isPlaying == true
        }
        assertTrue(
            "the exported file must play through the production player for " +
                "$episodeId (probe=${composeRule.playerProbe()})",
            playing,
        )
        Log.i(TAG, "ADB_PLAYBACK verified episodeId=$episodeId")
        // Leave the player before driving the next episode.
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        assertTrue(
            "the player must close again after episode $episodeId",
            awaitCondition(15_000) { !topRouteIs() },
        )
    }

    /** Top-of-stack check used by the playback verification. */
    private fun topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<PlayerRoute>(activity) }
    }

    private companion object {
        const val TAG = "GateASpecialTitleDeviceTest"
    }
}
