package com.pahebatcher.app.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.allTexts
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.ensureTestMp4
import com.pahebatcher.app.support.isPlayerPlaying
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.support.playerProbe
import com.pahebatcher.app.testdocs.PBTestDocumentsProvider
import com.pahebatcher.app.ui.navigation.PlayerRoute
import com.pahebatcher.app.ui.navigation.navigateToPlayer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **playback through a SAF child URI and honest
 * failure when that URI is inaccessible** (the review's mandated acceptance
 * test), against the REAL MainActivity, REAL Room, REAL Media3 player, and a
 * REAL document of the debug [PBTestDocumentsProvider] (the same
 * `DocumentsContract` semantics the production SafExporter writes to).
 *
 * Fixtures reproduce the POST-EXPORT row shape exactly: `outputUri` points at
 * the SAF child and `outputPath` is null (X6 already removed the local source),
 * so [com.pahebatcher.app.data.playback.PlaybackSourceResolver] must resolve
 * `outputUri` — the branch no player test covered before (PlayerLifecycleTest
 * plays only local `outputPath` files).
 *
 * Honest failure scope: an instrumentation test cannot revoke a same-UID
 * grant (in-package provider access bypasses grants — see
 * DownloadStateReconcilerTest's revocation case), so "inaccessible" is
 * exercised as a document the provider CANNOT OPEN (its backing file is gone),
 * the same `FileNotFoundException` a revoked/moved external child produces at
 * open time. The contract asserted: the player surfaces its error control
 * (Retry) and NEVER claims playback — no silent hang, no fake progress.
 */
@RunWith(AndroidJUnit4::class)
class SafPlaybackDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database: PaheDatabase get() = PaheDatabase.getInstance(context)

    private val tree: Uri
        get() = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_RENAME)

    private val treeRoot: File
        get() = File(context.getExternalFilesDir("pb_testdocs"), PBTestDocumentsProvider.ROOT_RENAME)

    private val plantedFiles = mutableListOf<File>()

    @Before
    fun setup() {
        // Deterministic playlists — the same wipe seedCompletedEpisodes does.
        runBlocking { database.downloadSessionDao().deleteAll() }
        if (!treeRoot.exists()) treeRoot.mkdirs()
    }

    @After
    fun teardown() {
        plantedFiles.forEach { it.delete() }
        plantedFiles.clear()
        runBlocking { database.downloadSessionDao().deleteAll() }
    }

    // ------------------------------------------------------------------
    // DECISIONS 92.8 — playback through a SAF child URI
    // ------------------------------------------------------------------

    @Test
    fun safChildPlayback_playsTheRealExportedDocument() {
        val child = plantSafChild("SAF Playback Episode 001.mp4")
        val episodeId = seedSafEpisode(outputUri = child.toString())

        openPlayer(episodeId)

        val loaded = awaitCondition(20_000) {
            (composeRule.playerProbe()?.durationMs ?: 0L) >= 55_000L
        }
        assertTrue(
            "the SAF child document must load as the real ~60s media " +
                "(probe=${composeRule.playerProbe()} texts=${composeRule.allTexts()})",
            loaded,
        )
        val playing = awaitCondition(15_000) {
            composeRule.playerProbe()?.isPlaying == true
        }
        assertTrue(
            "playback over the content:// SAF child must be active " +
                "(probe=${composeRule.playerProbe()} texts=${composeRule.allTexts()})",
            playing,
        )
        assertEquals(
            "no error control for a resolvable SAF child",
            0,
            composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().size,
        )
    }

    // ------------------------------------------------------------------
    // DECISIONS 92.8 — honest failure when that URI is inaccessible
    // ------------------------------------------------------------------

    @Test
    fun inaccessibleSafChild_failsHonestlyAndNeverClaimsPlayback() {
        // The row references a document whose backing file does not exist —
        // provider openDocument throws FileNotFoundException, the exact open
        // failure a moved/deleted child produces.
        val dangling = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            "${PBTestDocumentsProvider.ROOT_RENAME}/SAF Gone Episode 99.mp4",
        )
        val episodeId = seedSafEpisode(outputUri = dangling.toString())

        openPlayer(episodeId)

        val surfaced = awaitCondition(20_000) {
            composeRule.allTexts().any { it == "Retry" }
        }
        assertTrue(
            "an unopenable SAF child must surface the player's error Retry " +
                "control, never a silent hang " +
                "(probe=${composeRule.playerProbe()} texts=${composeRule.allTexts()})",
            surfaced,
        )

        // Give any (wrong) playback a chance to start, then prove it never did.
        Thread.sleep(1_000)
        assertFalse(
            "the player must never claim playback of an inaccessible URI " +
                "(probe=${composeRule.playerProbe()})",
            composeRule.isPlayerPlaying(),
        )
        assertTrue(
            "no media duration may ever be exposed for an unopenable child " +
                "(probe=${composeRule.playerProbe()})",
            (composeRule.playerProbe()?.durationMs ?: 0L) < 55_000L,
        )
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Plants the real cached MP4 as a document of the rename-capable tree. */
    private fun plantSafChild(name: String): Uri {
        if (!treeRoot.exists()) treeRoot.mkdirs()
        val file = File(treeRoot, name)
        File(ensureTestMp4()).copyTo(file, overwrite = true)
        plantedFiles += file
        return DocumentsContract.buildDocumentUriUsingTree(
            tree,
            "${PBTestDocumentsProvider.ROOT_RENAME}/$name",
        )
    }

    /**
     * One COMPLETED row whose ONLY source is [outputUri] — the post-export
     * invariant (X6 removed `outputPath`), run-unique ids so every test gets
     * a fresh player seed.
     */
    private fun seedSafEpisode(outputUri: String): String {
        val sessionId = "safplay-${System.nanoTime()}"
        val episodeId = "safplay-ep-${System.nanoTime()}"
        runBlocking {
            Phase10TestSupport.insertSession(database, sessionId)
            database.episodeDownloadDao().upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = sessionId,
                    episodeNumber = 1f,
                    episodeSession = "es-$episodeId",
                    title = "SAF Playback Test",
                    fansub = "test",
                    audio = "sub",
                    playUrl = "https://animepahe.local/play/$episodeId",
                    totalSegments = 1,
                    completedSegments = 1,
                    state = EpisodeDownloadEntity.STATE_COMPLETED,
                    outputPath = null,
                    outputUri = outputUri,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    segmentDir = null,
                ),
            )
        }
        return episodeId
    }

    /** Opens `player(episodeId)` through the MainActivity navController seam. */
    private fun openPlayer(episodeId: String) {
        composeRule.runOnIdle {
            composeRule.activity.navController!!.navigateToPlayer(episodeId)
        }
        assertTrue(
            "player route opens for $episodeId",
            awaitCondition(15_000) { topRouteIs() },
        )
    }

    private fun topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<PlayerRoute>(activity) }
    }
}
