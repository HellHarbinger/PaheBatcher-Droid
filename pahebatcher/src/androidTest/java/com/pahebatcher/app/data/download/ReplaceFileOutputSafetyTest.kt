package com.pahebatcher.app.data.download

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.data.export.ExportOutcome
import com.pahebatcher.app.data.export.PriorOutputRetirement
import com.pahebatcher.app.data.hls.AesKeyCache
import com.pahebatcher.app.data.hls.FileNameGenerator
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.worker.DownloadWorker
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Replace file` (DECISIONS 152) — output safety, cases 11–19.
 *
 * The property every case below defends is the same one the feature promises:
 * **the export the user already has is never destroyed by a replacement that
 * did not produce a verified new file, and the replaced export is only removed
 * AFTER the new one is committed and referenced.**
 *
 * Everything below the fakes is REAL:
 * - real Room database (the production singleton) and real rows,
 * - real filesystem writes, renames and byte comparisons,
 * - real `DownloadWorker` runs (cases 15–16) muxing on-device-generated TS
 *   with the real FFmpegKit,
 * - real MediaStore items and real `ContentResolver` deletes (cases 17–19).
 *
 * The only fake is the in-process HLS source (test-only, never a production
 * implementation — production uses `OkHttpHlsClient`).
 */
@RunWith(AndroidJUnit4::class)
class ReplaceFileOutputSafetyTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var contentResolver: ContentResolver

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-replace-safety-$runStamp"

    /** MediaStore items this test created — removed in teardown. */
    private val createdMediaUris = mutableListOf<Uri>()

    /** Episode rows this test created — their work is cancelled and rows removed in teardown. */
    private val createdEpisodeIds = mutableListOf<String>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        contentResolver = context.contentResolver
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        val workManager = WorkManager.getInstance(context)
        createdEpisodeIds.forEach { id ->
            workManager.cancelUniqueWork("episode_download_$id")
            runBlocking { db.episodeDownloadDao().delete(id) }
            segmentDirFor(id).deleteRecursively()
        }
        createdEpisodeIds.clear()
        createdMediaUris.forEach { uri ->
            runCatching { contentResolver.delete(uri, null, null) }
        }
        createdMediaUris.clear()
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 11. finishReplacement: commits the new output, clears the flag, KEEPS
    //     the retirement reference.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun finish_replacement_commits_new_output_clears_the_flag_and_keeps_the_retirement_reference() =
        runBlocking {
            val dao = db.episodeDownloadDao()
            val episodeId = "ep-rep-safety-11-$runStamp"
            val oldPath = segmentDirFor(episodeId).absolutePath + "/Ep_001_-_Eleven_old.mp4"
            val newPath = segmentDirFor(episodeId).absolutePath + "/Ep_001_-_Eleven.mp4"
            val priorUri = "content://media/external/file/11${runStamp}"
            dao.upsert(
                seed(
                    episodeId = episodeId,
                    state = EpisodeDownloadEntity.STATE_RESOLVING,
                    outputPath = oldPath,
                    replaceRequested = true,
                    replacePriorUri = priorUri,
                ),
            )

            val landed = dao.finishReplacement(
                episodeId = episodeId,
                expectedState = EpisodeDownloadEntity.STATE_RESOLVING,
                outputPath = newPath,
            )

            assertEquals("the commit write must land", 1, landed)
            val row = dao.getById(episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
            assertEquals(newPath, row.outputPath)
            assertFalse(
                "replaceRequested must be cleared once the new output is committed",
                row.replaceRequested,
            )
            assertEquals(
                "replacePriorUri must SURVIVE the commit so the exporter can retire the old export",
                priorUri,
                row.replacePriorUri,
            )
            dao.clearReplacementIntent(episodeId) // no-op (flag already 0) — teardown hygiene
            dao.delete(episodeId)
        }

    // ─────────────────────────────────────────────────────────────────────
    // 12. restoreCompletedForUncommittedReplacement: CAS-guarded restore.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun restore_of_an_uncommitted_replacement_is_cas_guarded_and_clears_both_columns() = runBlocking {
        val dao = db.episodeDownloadDao()
        val episodeId = "ep-rep-safety-12-$runStamp"
        val exportPath = segmentDirFor(episodeId).absolutePath + "/Ep_001_-_Twelve.mp4"
        dao.upsert(
            seed(
                episodeId = episodeId,
                state = EpisodeDownloadEntity.STATE_DOWNLOADING,
                outputPath = exportPath,
                replaceRequested = true,
                replacePriorUri = "content://media/external/file/12${runStamp}",
            ),
        )

        val lost = dao.restoreCompletedForUncommittedReplacement(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_ASSEMBLING,
        )
        assertEquals("a newer state must win — the restore must not land", 0, lost)
        var row = dao.getById(episodeId)!!
        assertEquals(
            "the refused write must leave the row untouched",
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            row.state,
        )
        assertTrue(row.replaceRequested)

        val landed = dao.restoreCompletedForUncommittedReplacement(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_DOWNLOADING,
        )
        assertEquals("the restore must land from the state the run owns", 1, landed)
        row = dao.getById(episodeId)!!
        assertEquals(
            "an uncommitted replacement returns to the COMPLETED state that describes its surviving export",
            EpisodeDownloadEntity.STATE_COMPLETED,
            row.state,
        )
        assertEquals("the surviving export path is never rewritten", exportPath, row.outputPath)
        assertFalse(row.replaceRequested)
        assertNull("nothing to stage and nothing to retire — both columns cleared", row.replacePriorUri)
        assertNull(row.errorMessage)
        dao.delete(episodeId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 13. clearReplacementIntent is guarded on replaceRequested = 1.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun clearing_the_replacement_intent_never_drops_a_committed_rows_pending_retirement() = runBlocking {
        val dao = db.episodeDownloadDao()
        val episodeId = "ep-rep-safety-13-$runStamp"
        val pendingPrior = "content://media/external/file/13${runStamp}"
        dao.upsert(
            seed(
                episodeId = episodeId,
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = segmentDirFor(episodeId).absolutePath + "/Ep_001_-_Thirteen.mp4",
                replaceRequested = false,
                replacePriorUri = pendingPrior,
            ),
        )

        val cleared = dao.clearReplacementIntent(episodeId)

        assertEquals("an in-flight intent is not marked — nothing to clear", 0, cleared)
        val row = dao.getById(episodeId)!!
        assertEquals(
            "the pending retirement of a COMMITPLETED row must survive an unguarded clear",
            pendingPrior,
            row.replacePriorUri,
        )
        assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
        dao.delete(episodeId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 14. beginReplacement keeps the output references and is idempotent.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun claiming_a_replacement_keeps_the_output_references_and_a_second_claim_is_a_no_op() = runBlocking {
        val dao = db.episodeDownloadDao()
        val episodeId = "ep-rep-safety-14-$runStamp"
        val exportPath = segmentDirFor(episodeId).absolutePath + "/Ep_001_-_Fourteen.mp4"
        dao.upsert(
            seed(
                episodeId = episodeId,
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = exportPath,
                outputUri = "content://media/external/file/14${runStamp}",
                replaceRequested = false,
                replacePriorUri = null,
            ),
        )

        val claimed = dao.beginReplacement(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_COMPLETED,
            replacePriorUri = "content://media/external/file/14${runStamp}",
        )
        assertEquals("the first confirmation must claim the row", 1, claimed)
        var row = dao.getById(episodeId)!!
        assertEquals(EpisodeDownloadEntity.STATE_RESOLVING, row.state)
        assertTrue(row.replaceRequested)
        assertEquals(
            "the export being replaced is recorded for the later retirement",
            "content://media/external/file/14${runStamp}",
            row.replacePriorUri,
        )
        assertEquals(
            "the previous export PATH must survive the whole replacement",
            exportPath,
            row.outputPath,
        )
        assertEquals(
            "the previous export URI must survive the whole replacement",
            "content://media/external/file/14${runStamp}",
            row.outputUri,
        )

        val second = dao.beginReplacement(
            episodeId = episodeId,
            expectedState = EpisodeDownloadEntity.STATE_COMPLETED,
            replacePriorUri = null,
        )
        assertEquals("a second confirmation must never start a competing run", 0, second)
        row = dao.getById(episodeId)!!
        assertTrue("the in-flight intent is untouched by the refused claim", row.replaceRequested)
        assertEquals(exportPath, row.outputPath)
        dao.delete(episodeId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 15. Real Worker replacement SUCCESS: staged output is committed ONTO the
    //     previous export's path; staging is removed; no second copy remains.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun a_successful_replacement_commits_the_new_file_over_the_old_export_and_leaves_no_second_copy(): Unit =
        runBlocking {
            val dao = db.episodeDownloadDao()
            val episodeId = "ep-rep-safety-15-$runStamp"
            val title = "Replace Safety Fifteen"
            val segmentDir = segmentDirFor(episodeId)
            val exportName = FileNameGenerator.episodeFileName(
                FileNameGenerator.formatEpisodePrefix(1.0f),
                title,
            )
            val oldExport = File(segmentDir, exportName)
            oldExport.parentFile?.mkdirs()
            val oldMarker = "OLD-EXPORT-MARKER-$runStamp".toByteArray()
            oldExport.writeBytes(oldMarker)

            val playlistUrl = "https://cdn.local.test/replace-safety-15/playlist.m3u8"
            dao.upsert(
                seed(
                    episodeId = episodeId,
                    state = EpisodeDownloadEntity.STATE_QUEUED,
                    title = title,
                    m3u8Url = playlistUrl,
                    outputPath = oldExport.absolutePath,
                    outputUri = null,
                    replaceRequested = true,
                    replacePriorUri = null,
                ),
            )

            val result = runReplacementWorker(
                episodeId = episodeId,
                hls = ReplaceLocalHlsSource.successSource(playlistUrl, "replace-safety-15"),
            )

            assertTrue("the replacement run must succeed", result)

            // ── The old export's PATH now holds the NEW, real MP4 ──
            assertTrue("the committed export must exist at the recorded path", oldExport.exists())
            val committed = oldExport.readBytes()
            assertTrue("the committed file must be a real MP4 (ftyp box)", committed.isMp4())
            assertFalse(
                "the OLD export bytes must be gone — the replacement really replaced them",
                committed.contentEquals(oldMarker),
            )

            // ── Staging is gone and no second copy remains ──
            val stagingDir = File(segmentDir, DownloadWorker.SEGMENT_STAGING_DIR_NAME)
            assertFalse(
                "the staging directory must be removed after the commit",
                stagingDir.exists(),
            )
            val mp4s = segmentDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }
            assertEquals(
                "exactly ONE export must remain — never a second copy of the episode",
                1,
                mp4s?.size ?: 0,
            )
            val leftovers = segmentDir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".ts") || it.name.endsWith(".tmp")) }
            assertTrue(
                "no staged segment may survive the commit",
                leftovers.isNullOrEmpty(),
            )

            // ── Row state ──
            val row = dao.getById(episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
            assertFalse(row.replaceRequested)
            assertEquals("the recorded path is unchanged by the in-place commit", oldExport.absolutePath, row.outputPath)
            assertNull("no prior was recorded for an app-private export", row.replacePriorUri)

            dao.delete(episodeId)
            segmentDir.deleteRecursively()
        }

    // ─────────────────────────────────────────────────────────────────────
    // 15b. A2.1 closure (keepTemp audit): the replacement STAGING obeys
    //      `Keep temporary files` — ON retains the staged segments after the
    //      commit, and the commit itself stays exactly as safe as the
    //      keepTemp=false leg above (which is preserved unchanged).
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun a_successful_replacement_with_keepTemp_true_retains_staging_and_still_commits_one_valid_output(): Unit =
        runBlocking {
            val dao = db.episodeDownloadDao()
            val episodeId = "ep-rep-safety-15k-$runStamp"
            val title = "Replace Safety Fifteen Keep"
            val segmentDir = segmentDirFor(episodeId)
            val exportName = FileNameGenerator.episodeFileName(
                FileNameGenerator.formatEpisodePrefix(1.0f),
                title,
            )
            val oldExport = File(segmentDir, exportName)
            oldExport.parentFile?.mkdirs()
            val oldMarker = "OLD-EXPORT-MARKER-KEEP-$runStamp".toByteArray()
            oldExport.writeBytes(oldMarker)

            val playlistUrl = "https://cdn.local.test/replace-safety-15-keep/playlist.m3u8"
            dao.upsert(
                seed(
                    episodeId = episodeId,
                    state = EpisodeDownloadEntity.STATE_QUEUED,
                    title = title,
                    m3u8Url = playlistUrl,
                    outputPath = oldExport.absolutePath,
                    outputUri = null,
                    replaceRequested = true,
                    replacePriorUri = null,
                ),
            )

            val result = runReplacementWorker(
                episodeId = episodeId,
                hls = ReplaceLocalHlsSource.successSource(playlistUrl, "replace-safety-15-keep"),
                config = DownloadConfig(keepTemp = true),
            )

            assertTrue("the keepTemp=true replacement run must succeed", result)

            // ── The commit is exactly as safe as the default leg ──
            assertTrue("the committed export must exist at the recorded path", oldExport.exists())
            val committed = oldExport.readBytes()
            assertTrue("the committed file must be a real MP4 (ftyp box)", committed.isMp4())
            assertFalse(
                "the OLD export bytes must be gone — the replacement really replaced them",
                committed.contentEquals(oldMarker),
            )

            // ── keepTemp=true: the staging directory is RETAINED with its
            //    segments (the muxed output was MOVED out, never copied back) ──
            val stagingDir = File(segmentDir, DownloadWorker.SEGMENT_STAGING_DIR_NAME)
            assertTrue(
                "keepTemp=true: the staging directory must be retained after the commit",
                stagingDir.exists(),
            )
            val stagedSegments = stagingDir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(".ts") }
            assertEquals(
                "keepTemp=true: every completed staged segment must be retained",
                3,
                stagedSegments.size,
            )
            val topLevelLeftovers = segmentDir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".ts") || it.name.endsWith(".tmp")) }
            assertTrue(
                "staged segments may only live INSIDE the retained staging directory",
                topLevelLeftovers.isNullOrEmpty(),
            )
            val mp4s = segmentDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }
            assertEquals(
                "exactly ONE top-level export must remain — staging retention never duplicates it",
                1,
                mp4s?.size ?: 0,
            )

            // ── Row state — identical to the default leg ──
            val row = dao.getById(episodeId)!!
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, row.state)
            assertFalse(row.replaceRequested)
            assertEquals("the recorded path is unchanged by the in-place commit", oldExport.absolutePath, row.outputPath)
            assertNull("no prior was recorded for an app-private export", row.replacePriorUri)

            dao.delete(episodeId)
            segmentDir.deleteRecursively() // also removes the retained staging dir
        }

    // ─────────────────────────────────────────────────────────────────────
    // 16. Real Worker replacement FAILURE: the old export survives
    //     byte-for-byte, the row returns to COMPLETED (never FAILED), and the
    //     notification says a REPLACEMENT failed while the file was kept.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun a_failed_replacement_preserves_the_existing_export_byte_for_byte_and_reports_a_failed_replacement(): Unit =
        runBlocking {
            val dao = db.episodeDownloadDao()
            val episodeId = "ep-rep-safety-16-$runStamp"
            val title = "Replace Safety Sixteen"
            val segmentDir = segmentDirFor(episodeId)
            val exportName = FileNameGenerator.episodeFileName(
                FileNameGenerator.formatEpisodePrefix(1.0f),
                title,
            )
            val oldExport = File(segmentDir, exportName)
            oldExport.parentFile?.mkdirs()
            val oldMarker = "SURVIVING-EXPORT-BYTES-$runStamp".toByteArray()
            oldExport.writeBytes(oldMarker)

            val playlistUrl = "https://cdn.local.test/replace-safety-16/missing.m3u8"
            dao.upsert(
                seed(
                    episodeId = episodeId,
                    state = EpisodeDownloadEntity.STATE_QUEUED,
                    title = title,
                    m3u8Url = playlistUrl,
                    outputPath = oldExport.absolutePath,
                    outputUri = null,
                    replaceRequested = true,
                    replacePriorUri = null,
                ),
            )

            val runReturnedSuccess = runReplacementWorker(
                episodeId = episodeId,
                hls = ReplaceLocalHlsSource.failureSource(playlistUrl),
            )

            assertFalse(
                "a permanent stream failure must end as a failure Result (never a success " +
                    "that would hide the loss behind a green run)",
                runReturnedSuccess,
            )
            assertTrue("the old export file must still exist", oldExport.exists())
            assertTrue(
                "the old export must be PRESERVED BYTE-FOR-BYTES — a failed replacement never destroys it",
                oldExport.readBytes().contentEquals(oldMarker),
            )

            val row = dao.getById(episodeId)!!
            assertEquals(
                "an uncommitted replacement returns to COMPLETED — the export it overlaid is still there",
                EpisodeDownloadEntity.STATE_COMPLETED,
                row.state,
            )
            assertEquals("the surviving export path is untouched", oldExport.absolutePath, row.outputPath)
            assertFalse("the in-flight intent must not outlive the run", row.replaceRequested)
            assertNull("nothing to retire when nothing was replaced", row.replacePriorUri)
            assertNull("a restored row reports no download failure", row.errorMessage)

            // The failure notification, when the platform lets it post, must say
            // a REPLACEMENT failed and the existing file was kept — never a
            // plain "download failed" for an episode that is still completed.
            // AQR-112: where the platform cannot demonstrate a post at all, the
            // sub-check is SKIPPED via Assume (the repo's documented convention,
            // BatchFailureNotificationTest) instead of passing silently, and the
            // fixture cleanup below runs either way.
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val manager = DownloadNotificationManager(context)
            val platformAllowsPosts =
                notificationManager.areNotificationsEnabled() &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            try {
                Assume.assumeTrue(
                    "notifications are disabled for this app on this device",
                    platformAllowsPosts,
                )
                val posted = notificationManager.activeNotifications
                    .firstOrNull { it.id == manager.terminalIdFor(episodeId) }
                assertNotNull(
                    "with posts allowed the terminal failure notification must be active",
                    posted,
                )
                val text = posted!!.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                assertNotNull("a posted failure notification must carry content text", text)
                assertTrue(
                    "the failure must be reported as a FAILED REPLACEMENT whose existing file was kept",
                    text!!.contains(DownloadNotificationManager.REPLACEMENT_KEPT_EXISTING_SUFFIX),
                )
            } finally {
                notificationManager.cancel(manager.terminalIdFor(episodeId))
                dao.delete(episodeId)
                segmentDir.deleteRecursively()
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // 17. Retirement refuses while the new output is NOT committed.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun retiring_the_prior_output_refuses_before_the_new_output_is_committed() = runBlocking {
        val dao = db.episodeDownloadDao()
        val episodeId = "ep-rep-safety-17-$runStamp"
        val prior = insertVisibleMediaItem("replace_safety_17_$runStamp.mp4")
        val priorUri = prior.toString()
        dao.upsert(
            seed(
                episodeId = episodeId,
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = null,
                replaceRequested = false,
                replacePriorUri = priorUri,
            ),
        )
        val episode = dao.getById(episodeId)!!
        val retirement = PriorOutputRetirement(contentResolver, dao)

        assertFalse(
            "a FAILED export outcome means the new output is NOT committed — nothing may be retired",
            retirement.retire(episode, ExportOutcome.Failed("export did not complete")),
        )
        assertFalse(
            "an outcome whose URI IS the prior proves nothing new is referenced — the prior stays",
            retirement.retire(episode, ExportOutcome.AlreadyExported(priorUri)),
        )

        assertTrue("the prior MediaStore item must survive both refusals", mediaItemExists(prior))
        assertEquals(
            "the retirement reference must be kept so a later pass retries",
            priorUri,
            dao.getById(episodeId)!!.replacePriorUri,
        )
        dao.delete(episodeId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 18. Retirement refuses while ANOTHER row still references the prior
    //     (cross-episode overwrite protection).
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun retiring_the_prior_output_refuses_while_another_row_still_references_it() = runBlocking {
        val dao = db.episodeDownloadDao()
        val episodeId = "ep-rep-safety-18a-$runStamp"
        val otherId = "ep-rep-safety-18b-$runStamp"
        val prior = insertVisibleMediaItem("replace_safety_18_$runStamp.mp4")
        val priorUri = prior.toString()
        dao.upsert(
            seed(
                episodeId = episodeId,
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = null,
                replaceRequested = false,
                replacePriorUri = priorUri,
            ),
        )
        dao.upsert(
            seed(
                episodeId = otherId,
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = null,
                outputUri = priorUri,
                replaceRequested = false,
                replacePriorUri = null,
            ),
        )
        val retirement = PriorOutputRetirement(contentResolver, dao)

        val retired = retirement.retire(
            dao.getById(episodeId)!!,
            ExportOutcome.Exported("content://media/external/file/other${runStamp}"),
        )

        assertFalse(
            "a URI two rows point at belongs to neither episode alone — it must never be deleted",
            retired,
        )
        assertTrue("the shared item must survive", mediaItemExists(prior))
        assertEquals(
            "the retirement reference is kept until the file is really removable",
            priorUri,
            dao.getById(episodeId)!!.replacePriorUri,
        )
        dao.delete(episodeId)
        dao.delete(otherId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 19. Retirement deletes the prior exactly once, after the new output is
    //     committed, and is idempotent afterwards.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun retiring_the_prior_output_deletes_it_once_the_new_output_is_committed_and_is_idempotent() =
        runBlocking {
            val dao = db.episodeDownloadDao()
            val episodeId = "ep-rep-safety-19-$runStamp"
            val prior = insertVisibleMediaItem("replace_safety_19_old_$runStamp.mp4")
            val replacement = insertVisibleMediaItem("replace_safety_19_new_$runStamp.mp4")
            val priorUri = prior.toString()
            dao.upsert(
                seed(
                    episodeId = episodeId,
                    state = EpisodeDownloadEntity.STATE_COMPLETED,
                    outputPath = null,
                    outputUri = replacement.toString(),
                    replaceRequested = false,
                    replacePriorUri = priorUri,
                ),
            )
            val retirement = PriorOutputRetirement(contentResolver, dao)

            val retired = retirement.retire(
                dao.getById(episodeId)!!,
                ExportOutcome.Exported(replacement.toString()),
            )

            assertTrue("the committed replacement makes the prior removable", retired)
            assertFalse("the prior MediaStore item must be deleted", mediaItemExists(prior))
            assertTrue(
                "the NEW export must never be touched by the retirement of the old one",
                mediaItemExists(replacement),
            )
            assertNull(
                "the retirement reference dies with the export it described",
                dao.getById(episodeId)!!.replacePriorUri,
            )

            // Idempotent second pass: the reference is already gone, so the
            // call is a no-op and never deletes anything else.
            val again = retirement.retire(dao.getById(episodeId)!!, ExportOutcome.Exported(replacement.toString()))
            assertTrue("a second retirement pass must see the goal state", again)
            assertTrue("the replacement export must still exist", mediaItemExists(replacement))

            dao.delete(episodeId)
        }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun segmentDirFor(episodeId: String): File =
        File(Phase10TestSupport.segmentDirFor(context, episodeId))

    private fun seed(
        episodeId: String,
        state: String,
        title: String = "Replace Safety Episode",
        m3u8Url: String? = null,
        outputPath: String? = null,
        outputUri: String? = null,
        replaceRequested: Boolean,
        replacePriorUri: String?,
    ): EpisodeDownloadEntity {
        createdEpisodeIds.add(episodeId)
        return EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = title,
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://animepahe.com/play/$sessionId/eps-$episodeId",
        state = state,
        retryCount = 0,
        errorMessage = null,
        createdAt = runStamp,
        updatedAt = runStamp,
        m3u8Url = m3u8Url,
        userAgent = "ReplaceSafetyUA",
        referer = "https://example.com",
        segmentDir = segmentDirFor(episodeId).absolutePath,
        outputPath = outputPath,
        outputUri = outputUri,
        replaceRequested = replaceRequested,
        replacePriorUri = replacePriorUri,
        )
    }

    /**
     * Run the REAL `DownloadWorker` in-process against [hls]; `true` when the
     * run returned `Result.success()`. [config] defaults to the shipped
     * `DownloadConfig()` — the A2.1 closure leg passes `keepTemp = true`.
     */
    private suspend fun runReplacementWorker(
        episodeId: String,
        hls: HlsClient,
        config: DownloadConfig = DownloadConfig(),
    ): Boolean {
        val worker = TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
            .setInputData(workDataOf("episode_id" to episodeId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = DownloadWorker(
                    context = appContext,
                    params = workerParameters,
                    dao = db.episodeDownloadDao(),
                    notificationManager = DownloadNotificationManager(appContext),
                    hlsClient = hls,
                    keyCache = AesKeyCache(),
                    config = config,
                )
            })
            .build()
        return worker.startWork().get(180, java.util.concurrent.TimeUnit.SECONDS).let {
            it == androidx.work.ListenableWorker.Result.success()
        }
    }

    /** Direct MediaStore insert of a visible `video/mp4` item we own. */
    private fun insertVisibleMediaItem(displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/PaheBatcherReplaceTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore insert must succeed", uri)
        contentResolver.openOutputStream(uri!!, "w")!!.use { it.write("media-bytes-$displayName".toByteArray()) }
        createdMediaUris.add(uri)
        return uri
    }

    /** True when the item behind [uri] is still present in MediaStore. */
    private fun mediaItemExists(uri: Uri): Boolean = contentResolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns._ID),
        null,
        null,
        null,
    )?.use { it.count > 0 } ?: false

    private fun ByteArray.isMp4(): Boolean =
        size > 12 && String(this, 4, 4, Charsets.US_ASCII) == "ftyp"
}

/**
 * In-process HLS source for the replacement safety runs: a real playlist plus
 * on-device-generated TS bytes, or a permanent 404 for the failure case.
 * Test-only — production uses `OkHttpHlsClient`.
 */
private class ReplaceLocalHlsSource(
    private val playlistUrl: String,
    private val playlist: String?,
    private val segments: Map<String, ByteArray>,
) : HlsClient {

    companion object {
        private const val SEGMENTS = 3

        /** A source that serves a valid playlist plus real TS segments. */
        fun successSource(playlistUrl: String, prefix: String): ReplaceLocalHlsSource {
            val baseUrl = "https://cdn.local.test/$prefix/"
            val segmentBytes = mutableMapOf<String, ByteArray>()
            (0 until SEGMENTS).forEach { i ->
                val url = "${baseUrl}seg$i.ts"
                val ts = File.createTempFile("replace_seg$i", ".ts")
                try {
                    check(generateValidTs(ts)) { "TS generation $i failed on device" }
                    segmentBytes[url] = ts.readBytes()
                } finally {
                    ts.delete()
                }
            }
            val playlist = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:1")
                appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                repeat(SEGMENTS) { i ->
                    appendLine("#EXTINF:1.0,")
                    appendLine("seg$i.ts")
                }
                appendLine("#EXT-X-ENDLIST")
            }
            return ReplaceLocalHlsSource(playlistUrl, playlist, segmentBytes)
        }

        /** A source whose playlist 404s permanently (stream expired). */
        fun failureSource(playlistUrl: String) = ReplaceLocalHlsSource(playlistUrl, null, emptyMap())

        /** Generate one valid MPEG-TS segment on-device (proven FFmpegKit command). */
        private fun generateValidTs(out: File): Boolean {
            val cmd = "-y -f lavfi -i color=c=blue:s=320x240:d=1:r=25 " +
                "-c:v libopenh264 -pix_fmt yuv420p " +
                "-f mpegts ${out.absolutePath}"
            return ReturnCode.isSuccess(FFmpegKit.execute(cmd).returnCode)
        }
    }

    override suspend fun getText(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long,
    ): HlsTextResult {
        val body = playlist ?: return HlsTextResult.HttpError(404, url)
        return if (url == playlistUrl) HlsTextResult.Success(body) else HlsTextResult.HttpError(404, url)
    }

    override suspend fun getBytes(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long,
    ): HlsBytesResult {
        val bytes = segments[url] ?: return HlsBytesResult.HttpError(404, url)
        return HlsBytesResult.Success(bytes)
    }
}
