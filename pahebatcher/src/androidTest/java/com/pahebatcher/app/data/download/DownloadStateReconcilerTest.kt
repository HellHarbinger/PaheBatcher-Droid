package com.pahebatcher.app.data.download

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.pahebatcher.app.BuildConfig
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.export.ExportDestinationRouter
import com.pahebatcher.app.data.export.ExportOutcome
import com.pahebatcher.app.data.export.MediaStoreExporter
import com.pahebatcher.app.data.export.OutputPathNames
import com.pahebatcher.app.data.export.OutputTreeRepository
import com.pahebatcher.app.data.export.SafExporter
import com.pahebatcher.app.data.export.SafTestSupport
import com.pahebatcher.app.data.hls.FileNameGenerator
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.DownloadSessionDao
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.testdocs.PBTestDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tests for DownloadStateReconciler.
 *
 *
 * Verifies:
 * - QUEUED without m3u8Url: no action
 * - QUEUED with m3u8Url: enqueue (checked via state)
 * - RESOLVING: reset to QUEUED
 * - DOWNLOADING: check WorkManager, re-enqueue if needed
 * - ASSEMBLING: check output, re-enqueue if incomplete
 * - COMPLETED: verify invariant
 * - FAILED/CANCELLED: no action
 * - Idempotent: running twice doesn't duplicate effects
 */
@RunWith(AndroidJUnit4::class)
class DownloadStateReconcilerTest {

    private lateinit var database: PaheDatabase
    private lateinit var dao: EpisodeDownloadDao
    private lateinit var sessionDao: DownloadSessionDao
    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver

    /** Run-unique tokens for the §36-14 MediaStore fixtures (teardown removes them). */
    private val mediaTokens = mutableSetOf<String>()

    private val testSession = DownloadSessionEntity(
        sessionId = "sess-reconcile-1",
        title = "Test Session",
        animeSession = "anime-123",
        host = "animepahe.com",
        quality = 2,
        audioLang = "sub",
        outputDir = "/downloads",
        cacheDir = "/cache",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        state = "QUEUED"
    )

    private val testEpisode = EpisodeDownloadEntity(
        episodeId = "ep-reconcile-1",
        sessionId = "sess-reconcile-1",
        episodeNumber = 1.0f,
        episodeSession = "ep-sess-1",
        title = "Reconcile Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        contentResolver = context.contentResolver
        database = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.episodeDownloadDao()
        sessionDao = database.downloadSessionDao()
        // Insert parent session for FK constraint
        runBlocking { sessionDao.insertIfAbsent(testSession) }
    }

    @After
    fun teardown() {
        // Remove any Movies rows created by the §36-14 fixtures (legacy flat
        // + per-anime directories — Gate A scope, token-filtered).
        if (mediaTokens.isNotEmpty()) {
            // Bundle + MATCH_INCLUDE: the legacy overload cannot see pending
            // rows, so cleanup would leak them between tests.
            val args = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? " +
                        "AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(OutputPathNames.MOVIES_PREFIX + "%", BuildConfig.APPLICATION_ID),
                )
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", MediaStore.MediaColumns.DISPLAY_NAME),
                args,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("_id")
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val doomed = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    if (mediaTokens.any { name.contains(it) }) doomed += cursor.getLong(idIdx)
                }
                doomed.forEach { id ->
                    contentResolver.delete(
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        null, null,
                    )
                }
            }
        }
        database.close()
    }

    @Test
    fun reconcile_emptyDatabase_doesNothing() {
        runBlocking {
            val workManager = WorkManager.getInstance(context)
            // AQR-099: "does nothing" must be falsifiable in both directions —
            // no row may appear AND no work may be enqueued — not just "did not
            // throw". The premise (in-memory DB starts empty) is asserted first,
            // then both effects are checked after the reconcile pass.
            assertEquals(
                "the premise: this test's in-memory database starts empty",
                0,
                dao.getTotalEpisodeCount(),
            )
            // AQR-099: the work diff must cover every WorkInfo state (the repo's
            // WorkQuery idiom, TriggerNextDeferredEpisodeTest) — a pre/post set
            // difference of work ids, so history from earlier tests in this
            // process cannot masquerade as new work here.
            val allStates = WorkQuery.Builder.fromStates(
                listOf(
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED,
                ),
            ).build()
            val workBefore = workManager.getWorkInfos(allStates).get().map { it.id }.toSet()

            DownloadStateReconciler(dao, workManager).reconcile()

            assertEquals("an empty database gains no rows", 0, dao.getTotalEpisodeCount())
            val newWork = workManager.getWorkInfos(allStates).get().filter { it.id !in workBefore }
            assertTrue("an empty database enqueues no work", newWork.isEmpty())
        }
    }

    @Test
    fun reconcile_resolving_resetsToQueued() {
        runBlocking {
            dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_RESOLVING))
            val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
            reconciler.reconcile()

            val episode = dao.getById("ep-reconcile-1")
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, episode?.state)
        }
    }

    @Test
    fun reconcile_completed_withValidOutput_staysCompleted() {
        runBlocking {
            // Create a temp file to simulate valid output
            val tempFile = File(context.filesDir, "test_output.mp4")
            try {
                tempFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02))

                dao.upsert(testEpisode.copy(
                    state = EpisodeDownloadEntity.STATE_COMPLETED,
                    outputPath = tempFile.absolutePath
                ))

                val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
                reconciler.reconcile()

                val episode = dao.getById("ep-reconcile-1")
                assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, episode?.state)
            } finally {
                // AQR-100: cleanup runs even when an assertion fails, so a failed
                // run never leaves this fixed-name fixture behind in filesDir.
                tempFile.delete()
            }
        }
    }

    @Test
    fun reconcile_completed_outputInsideSegmentDir_survivesAndCleansSegments() {
        runBlocking {
            // the assembled MP4 lives INSIDE
            // segmentDir. Startup reconciliation must remove leftover segment
            // artifacts WITHOUT deleting the output — otherwise the next
            // reconciliation flips the episode to FAILED.
            val segDir = File(context.filesDir, "reconcile_segdir_${System.currentTimeMillis()}")
            segDir.mkdirs()
            val output = File(segDir, "Ep 001 - Reconcile Episode.mp4")
            output.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
            val leftoverTs = File(segDir, "000000.ts")
            leftoverTs.writeBytes("leftover segment".toByteArray())
            val leftoverTmp = File(segDir, "000001.tmp")
            leftoverTmp.writeBytes("partial segment".toByteArray())

            dao.upsert(testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = output.absolutePath,
                segmentDir = segDir.absolutePath
            ))

            val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
            reconciler.reconcile()

            var episode = dao.getById("ep-reconcile-1")
            assertEquals(
                "Output inside segmentDir must keep the episode COMPLETED",
                EpisodeDownloadEntity.STATE_COMPLETED, episode?.state
            )
            assertTrue("Output must survive reconciliation", output.exists())
            assertEquals(4, output.length())
            assertFalse("Leftover .ts must be cleaned", leftoverTs.exists())
            assertFalse("Leftover .tmp must be cleaned", leftoverTmp.exists())

            // Idempotency: a second startup reconciliation must reach the same
            // result (pre-corrective behavior: first run deleted the output,
            // second run flipped the episode to FAILED)
            reconciler.reconcile()
            episode = dao.getById("ep-reconcile-1")
            assertEquals(
                EpisodeDownloadEntity.STATE_COMPLETED, episode?.state
            )
            assertTrue("Output must survive repeated reconciliation", output.exists())

            // Cleanup
            segDir.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------
    // A2.1 Decision 4 (DL-005): the sweep HONOURS `Keep temporary files`
    // -----------------------------------------------------------------------

    @Test
    fun reconcile_completed_keepTempOn_preservesLeftoverSegments() {
        runBlocking {
            // With the setting ON, a COMPLETED episode's leftover .ts/.tmp are
            // diagnostic data the user asked to keep — startup reconciliation
            // must never delete them behind the user's back (the pre-A2.1 sweep
            // disagreed with EpisodeDownloader, which already honoured the
            // setting at completion time).
            val segDir = File(context.filesDir, "reconcile_segdir_keep_${System.currentTimeMillis()}")
            segDir.mkdirs()
            val output = File(segDir, "Ep 001 - Reconcile Episode.mp4")
            output.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
            val leftoverTs = File(segDir, "000000.ts")
            leftoverTs.writeBytes("leftover segment".toByteArray())
            val leftoverTmp = File(segDir, "000001.tmp")
            leftoverTmp.writeBytes("partial segment".toByteArray())

            dao.upsert(testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = output.absolutePath,
                segmentDir = segDir.absolutePath
            ))

            val reconciler = DownloadStateReconciler(
                dao,
                WorkManager.getInstance(context),
                keepTempProvider = { true },
            )
            reconciler.reconcile()

            val episode = dao.getById("ep-reconcile-1")
            assertEquals(
                "keepTemp changes cleanup ONLY — never the state",
                EpisodeDownloadEntity.STATE_COMPLETED, episode?.state
            )
            assertTrue("Output must survive reconciliation", output.exists())
            assertTrue("keepTemp ON: the leftover .ts must be preserved", leftoverTs.exists())
            assertTrue("keepTemp ON: the leftover .tmp must be preserved", leftoverTmp.exists())

            // Idempotency: a second pass under the same setting keeps them too.
            reconciler.reconcile()
            assertTrue("keepTemp ON: segments survive repeated reconciliation", leftoverTs.exists())

            segDir.deleteRecursively()
        }
    }

    @Test
    fun reconcile_completed_keepTempOffExplicitly_cleansLeftoverSegments() {
        runBlocking {
            // The setting is CONSULTED, not assumed: an explicit false sweeps
            // exactly like the shipped default (pinning both values per
            // Decision 4's test requirement).
            val segDir = File(context.filesDir, "reconcile_segdir_nokeep_${System.currentTimeMillis()}")
            segDir.mkdirs()
            val output = File(segDir, "Ep 001 - Reconcile Episode.mp4")
            output.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
            val leftoverTs = File(segDir, "000000.ts")
            leftoverTs.writeBytes("leftover segment".toByteArray())

            dao.upsert(testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = output.absolutePath,
                segmentDir = segDir.absolutePath
            ))

            val reconciler = DownloadStateReconciler(
                dao,
                WorkManager.getInstance(context),
                keepTempProvider = { false },
            )
            reconciler.reconcile()

            assertEquals(
                EpisodeDownloadEntity.STATE_COMPLETED,
                dao.getById("ep-reconcile-1")?.state
            )
            assertTrue("Output must survive reconciliation", output.exists())
            assertFalse("keepTemp OFF: the leftover .ts must be cleaned", leftoverTs.exists())

            segDir.deleteRecursively()
        }
    }

    @Test
    fun reconcile_completed_keepTempReadPerPass_neverSnapshotted() {
        runBlocking {
            // Fresh-read semantics (same live-read contract as wifiOnly/
            // autoResume): flipping the switch applies to the very next
            // reconcile pass, not the next app construction.
            var keepTemp = true
            val segDir = File(context.filesDir, "reconcile_segdir_flip_${System.currentTimeMillis()}")
            segDir.mkdirs()
            val output = File(segDir, "Ep 001 - Reconcile Episode.mp4")
            output.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
            val leftoverTs = File(segDir, "000000.ts")
            leftoverTs.writeBytes("leftover segment".toByteArray())

            dao.upsert(testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = output.absolutePath,
                segmentDir = segDir.absolutePath
            ))

            val reconciler = DownloadStateReconciler(
                dao,
                WorkManager.getInstance(context),
                keepTempProvider = { keepTemp },
            )

            reconciler.reconcile()
            assertTrue("first pass (on) keeps the segments", leftoverTs.exists())

            keepTemp = false
            reconciler.reconcile()
            assertFalse(
                "the second pass must observe the flipped setting — a " +
                    "snapshot taken at construction would have kept them",
                leftoverTs.exists()
            )

            segDir.deleteRecursively()
        }
    }

    @Test
    fun reconcile_completed_withMissingOutput_marksFailed() {
        runBlocking {
            // A2.1 closure (keepTemp audit GAP-5): the demotion must NEVER
            // destroy resumable segment data — cleanup lives strictly AFTER the
            // validity gate, so a missing output returns before any sweep.
            val segDir = File(context.filesDir, "reconcile_segdir_missing_${System.currentTimeMillis()}")
            try {
                segDir.mkdirs()
                val leftoverTs = File(segDir, "000000.ts")
                leftoverTs.writeBytes("resumable segment".toByteArray())

                dao.upsert(testEpisode.copy(
                    state = EpisodeDownloadEntity.STATE_COMPLETED,
                    outputPath = "/nonexistent/path/output.mp4",
                    segmentDir = segDir.absolutePath
                ))

                // Default keepTempProvider is FALSE — the sweep would clean if it
                // ever ran on this pass; the demotion must return before it.
                val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
                reconciler.reconcile()

                val episode = dao.getById("ep-reconcile-1")
                assertEquals(EpisodeDownloadEntity.STATE_FAILED, episode?.state)
                assertEquals("file missing", episode?.errorMessage)
                assertTrue("the segment directory must survive the demotion", segDir.exists())
                assertTrue("completed .ts must survive the demotion for retry/resume", leftoverTs.exists())
            } finally {
                segDir.deleteRecursively()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // A2.1 closure (keepTemp audit GAP-2): the ASSEMBLING recovery branch ──
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun reconcile_assembling_withOutput_repairsToCompleted_withoutTouchingSegments_thenSweepHonoursKeepTemp() {
        runBlocking {
            val segDir = File(context.filesDir, "reconcile_segdir_asm_ok_${System.currentTimeMillis()}")
            try {
                segDir.mkdirs()
                val output = File(segDir, "Ep 001 - Assembling Episode.mp4")
                output.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
                val leftoverTs = File(segDir, "000000.ts")
                leftoverTs.writeBytes("resumable segment".toByteArray())

                dao.upsert(testEpisode.copy(
                    state = EpisodeDownloadEntity.STATE_ASSEMBLING,
                    outputPath = null, // ASSEMBLING rows never carry a persisted outputPath
                    segmentDir = segDir.absolutePath
                ))

                // Pass 1: the interrupted assembly is REPAIRED to COMPLETED, and
                // the repair pass itself deletes nothing.
                DownloadStateReconciler(dao, WorkManager.getInstance(context)).reconcile()
                val repaired = dao.getById("ep-reconcile-1")
                assertEquals(
                    "the interrupted assembly must be repaired to COMPLETED",
                    EpisodeDownloadEntity.STATE_COMPLETED,
                    repaired?.state
                )
                assertEquals(
                    "the repaired outputPath is the real file",
                    output.absolutePath,
                    repaired?.outputPath
                )
                assertTrue("the output must survive the repair", output.exists())
                assertTrue("the repair pass must not delete resumable segments", leftoverTs.exists())

                // Pass 2: keepTemp ON — the completed row's leftovers stay.
                DownloadStateReconciler(
                    dao,
                    WorkManager.getInstance(context),
                    keepTempProvider = { true },
                ).reconcile()
                assertTrue("keepTemp ON: leftovers survive the COMPLETED sweep", leftoverTs.exists())

                // Pass 3: keepTemp OFF — the same leftovers are swept, output kept.
                DownloadStateReconciler(
                    dao,
                    WorkManager.getInstance(context),
                    keepTempProvider = { false },
                ).reconcile()
                assertFalse("keepTemp OFF: leftovers are cleaned on the later pass", leftoverTs.exists())
                assertTrue("the sweep never deletes the verified output", output.exists())
            } finally {
                segDir.deleteRecursively()
            }
        }
    }

    @Test
    fun reconcile_assembling_withoutOutput_reenqueuesAndKeepsSegments() {
        runBlocking {
            val episodeId = "ep-reconcile-asm-missing-${System.currentTimeMillis()}"
            val segDir = File(context.filesDir, "reconcile_segdir_asm_no_${System.currentTimeMillis()}")
            segDir.mkdirs()
            val leftoverTs = File(segDir, "000000.ts")
            leftoverTs.writeBytes("resumable segment".toByteArray())

            dao.upsert(testEpisode.copy(
                episodeId = episodeId,
                state = EpisodeDownloadEntity.STATE_ASSEMBLING,
                outputPath = null,
                segmentDir = segDir.absolutePath
            ))

            val workManager = WorkManager.getInstance(context)
            // AQR-103: the unique-work name is fresh per run, so absence before
            // reconcile + presence afterwards makes the enqueue causal — and the
            // work must still be PENDING (not some pre-finished info), which the
            // bare presence check could not distinguish.
            val uniqueName = "episode_download_$episodeId"
            assertTrue(
                "the run-unique work name must have no pre-existing history",
                workManager.getWorkInfosForUniqueWork(uniqueName).get().isEmpty()
            )
            try {
                DownloadStateReconciler(dao, workManager).reconcile()

                val row = dao.getById(episodeId)
                assertEquals(
                    "an incomplete assembly is never state-flipped by reconciliation",
                    EpisodeDownloadEntity.STATE_ASSEMBLING,
                    row?.state
                )
                val work = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                assertTrue(
                    "the interrupted assembly must be re-enqueued for recovery",
                    work.isNotEmpty()
                )
                assertTrue(
                    "the re-enqueued work must be PENDING recovery (not already finished)",
                    work.any { !it.state.isFinished }
                )
                assertTrue(
                    "reconciliation must never fabricate an output",
                    segDir.listFiles().orEmpty().none { it.name.endsWith(".mp4") }
                )
                assertTrue(
                    "completed segments must survive the re-enqueue pass",
                    leftoverTs.exists()
                )
            } finally {
                workManager.cancelUniqueWork(uniqueName)
                dao.delete(episodeId)
                segDir.deleteRecursively()
            }
        }
    }

    @Test
    fun reconcile_completed_withEmptyPath_marksFailed() {
        runBlocking {
            dao.upsert(testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputPath = null
            ))

            val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
            reconciler.reconcile()

            val episode = dao.getById("ep-reconcile-1")
            assertEquals(EpisodeDownloadEntity.STATE_FAILED, episode?.state)
        }
    }

    @Test
    fun reconcile_failed_noAction() {
        runBlocking {
            dao.upsert(testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_FAILED,
                errorMessage = "Some error"
            ))
            val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
            reconciler.reconcile()

            val episode = dao.getById("ep-reconcile-1")
            assertEquals(EpisodeDownloadEntity.STATE_FAILED, episode?.state)
            assertEquals("Some error", episode?.errorMessage)
        }
    }

    @Test
    fun reconcile_cancelled_noAction() {
        runBlocking {
            dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_CANCELLED))
            val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
            reconciler.reconcile()

            val episode = dao.getById("ep-reconcile-1")
            assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, episode?.state)
        }
    }

    @Test
    fun reconcile_paused_isNeverRestartedResetOrAutoResumed() {
        // SPEC §20/§25: PAUSED is a deliberate user stop, not a stale row. Every
        // recovery rule that exists for a LIVE row would be exactly wrong here —
        // so reconciliation (which runs on every app start, and is the closest
        // Android gets to "process death → restart") must leave it alone.
        runBlocking {
            val wm = WorkManager.getInstance(context)
            dao.upsert(
                testEpisode.copy(
                    state = EpisodeDownloadEntity.STATE_PAUSED,
                    m3u8Url = "https://example.com/paused/playlist.m3u8",
                    completedSegments = 5,
                    totalSegments = 20,
                    downloadedBytes = 12_345L,
                ),
            )
            val reconciler = DownloadStateReconciler(dao, wm)
            reconciler.reconcile()

            val episode = dao.getById("ep-reconcile-1")
            assertEquals(EpisodeDownloadEntity.STATE_PAUSED, episode?.state)
            assertEquals("progress must survive the restart", 5, episode?.completedSegments)
            assertEquals(12_345L, episode?.downloadedBytes)

            val infos = wm.getWorkInfosForUniqueWork("episode_download_ep-reconcile-1").get()
            assertTrue(
                "a paused episode must not gain live work on restart",
                infos.none { !it.state.isFinished },
            )
        }
    }

    @Test
    fun reconcile_queued_withoutM3u8Url_noAction() {
        runBlocking {
            dao.upsert(testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_QUEUED,
                m3u8Url = null
            ))
            val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
            reconciler.reconcile()

            // State should remain QUEUED (awaiting foreground resolution)
            val episode = dao.getById("ep-reconcile-1")
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, episode?.state)
        }
    }

    @Test
    fun reconcile_isIdempotent() {
        runBlocking {
            dao.upsert(testEpisode.copy(state = EpisodeDownloadEntity.STATE_RESOLVING))
            val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))

            // Run reconciliation twice
            reconciler.reconcile()
            reconciler.reconcile()

            // Should still be QUEUED (not double-processed)
            val episode = dao.getById("ep-reconcile-1")
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, episode?.state)
        }
    }

    // ------------------------------------------------------------------
    // Phase 11 §36-14: outputUri-aware invariant branches
    // (local vs exported vs user-deleted vs no-resolver fallback)
    // ------------------------------------------------------------------

    @Test
    fun reconcile_completed_exportedValidUri_staysCompleted_andCleansLocalDuplicate() {
        runBlocking {
            val token = "pbxexp${System.nanoTime()}".also { mediaTokens += it }
            val title = "Reconciled Export $token"
            val local = File(context.filesDir, "pbx_recon_export_$token.mp4")
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            local.writeBytes(bytes)
            val exporter = MediaStoreExporter(contentResolver, dao, sessionDao)
            val exported = testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                title = title,
                outputPath = local.absolutePath,
            )
            dao.upsert(exported)

            val outcome = exporter.export(exported)
            assertTrue("fixture must export, got $outcome", outcome is ExportOutcome.Exported)
            val uri = (outcome as ExportOutcome.Exported).uri

            // Recreate the local duplicate a kill-after-X5 would leave behind.
            local.writeBytes(bytes)

            DownloadStateReconciler(
                dao, WorkManager.getInstance(context), contentResolver, exporter,
            ).reconcile()

            val episode = dao.getById("ep-reconcile-1")!!
            assertEquals(
                "URI-resolving export keeps the row COMPLETED",
                EpisodeDownloadEntity.STATE_COMPLETED, episode.state,
            )
            assertEquals("outputUri unchanged by reconcile", uri, episode.outputUri)
            assertFalse("surviving local duplicate must be cleaned", local.exists())

            contentResolver.delete(android.net.Uri.parse(uri), null, null)
            local.delete()
        }
    }

    @Test
    fun reconcile_completed_userDeletedExport_marksFailedFileMissing() {
        runBlocking {
            val token = "pbxdel${System.nanoTime()}".also { mediaTokens += it }
            val title = "Deleted Export $token"
            val local = File(context.filesDir, "pbx_recon_deleted_$token.mp4")
            local.writeBytes(ByteArray(4096) { (it % 251).toByte() })
            val exporter = MediaStoreExporter(contentResolver, dao, sessionDao)
            val exported = testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                title = title,
                outputPath = local.absolutePath,
            )
            dao.upsert(exported)

            val outcome = exporter.export(exported)
            assertTrue("fixture must export, got $outcome", outcome is ExportOutcome.Exported)
            // Export ran X6: local is already gone, only the store copy remains.
            assertFalse(local.exists())

            // User deletes the exported video from the gallery.
            contentResolver.delete(android.net.Uri.parse((outcome as ExportOutcome.Exported).uri), null, null)

            DownloadStateReconciler(
                dao, WorkManager.getInstance(context), contentResolver, exporter,
            ).reconcile()

            val episode = dao.getById("ep-reconcile-1")!!
            assertEquals(
                "user-deleted export flips the row to FAILED (§29)",
                EpisodeDownloadEntity.STATE_FAILED, episode.state,
            )
            assertEquals("file missing", episode.errorMessage)

            local.delete()
        }
    }

    @Test
    fun reconcile_completed_severedUri_destinationSurvives_repairsInsteadOfDemoting() {
        runBlocking {
            // §3.1 (DECISIONS 157): MediaStore URI churn (backup/restore, storage
            // migration) leaves a COMPLETED row whose remembered `outputUri` no
            // longer resolves while the export file ITSELF survives at the same
            // destination name. The demotion's "file missing" would be FALSE
            // there — and would contradict the submission gate, which calls the
            // very same probe "already downloaded" (Slice B M-1: one question,
            // one answer). The reconciler must RE-POINT the row onto the
            // surviving URI instead of demoting it.
            val token = "pbxrep${System.nanoTime()}".also { mediaTokens += it }
            val title = "Repaired Export $token"
            val local = File(context.filesDir, "pbx_recon_repair_$token.mp4")
            local.writeBytes(ByteArray(8_192) { (it % 251).toByte() })
            val exporter = MediaStoreExporter(contentResolver, dao, sessionDao)
            val exported = testEpisode.copy(
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                title = title,
                outputPath = local.absolutePath,
            )
            dao.upsert(exported)

            val outcome = exporter.export(exported)
            assertTrue("fixture must export, got $outcome", outcome is ExportOutcome.Exported)
            val uri = (outcome as ExportOutcome.Exported).uri

            // Sever the row's pointer (URI churn): the ROW forgets where the
            // file is; the file itself stays visible in Movies/<title>/.
            dao.updateOutputUri(exported.episodeId, "content://media/external/video/media/999999999")

            DownloadStateReconciler(
                dao, WorkManager.getInstance(context), contentResolver, exporter,
            ).reconcile()

            val episode = dao.getById(exported.episodeId)!!
            assertEquals(
                "a severed row over a surviving export is repaired, never demoted",
                EpisodeDownloadEntity.STATE_COMPLETED, episode.state,
            )
            assertEquals("re-pointed onto the surviving export's URI", uri, episode.outputUri)
            assertNull("and no failure reason is recorded", episode.errorMessage)

            contentResolver.delete(android.net.Uri.parse(uri), null, null)
            local.delete()
        }
    }

    @Test
    fun reconcile_completed_deletedSafExport_marksFailedFileMissing() {
        runBlocking {
            // a SAF-routed export whose
            // destination child was deleted/moved by the user — the persisted
            // outputUri no longer resolves — must demote exactly like the
            // MediaStore variant above.
            val token = "pbxsaf${System.nanoTime()}"
            val config = com.pahebatcher.app.data.config.AppConfig(context)
            val tree =
                com.pahebatcher.app.testdocs.PBTestDocumentsProvider.treeUri(
                    com.pahebatcher.app.testdocs.PBTestDocumentsProvider.ROOT_RENAME,
                )
            try {
                val title = "SAF Reconcile $token"
                val local = File(context.filesDir, "pbx_recon_saf_$token.mp4")
                local.writeBytes(ByteArray(4096) { (it % 251).toByte() })
                val safExporter = com.pahebatcher.app.data.export.SafExporter(
                    contentResolver, dao, config, sessionDao,
                )
                dao.upsert(
                    testEpisode.copy(
                        state = EpisodeDownloadEntity.STATE_COMPLETED,
                        title = title,
                        outputPath = local.absolutePath,
                    ),
                )
                val result = safExporter.export(
                    dao.getById("ep-reconcile-1")!!, tree.toString(),
                )
                assertTrue(
                    "fixture must export to SAF, got $result",
                    result is com.pahebatcher.app.data.export.SafExportResult.Completed &&
                        result.outcome is ExportOutcome.Exported,
                )
                assertFalse("export ran X6", local.exists())
                val uri = dao.getById("ep-reconcile-1")!!.outputUri
                assertNotNull("fixture must have persisted outputUri (X4)", uri)

                // User deletes the exported video from the SAF tree.
                android.provider.DocumentsContract.deleteDocument(
                    contentResolver, android.net.Uri.parse(uri!!),
                )

                DownloadStateReconciler(
                    dao, WorkManager.getInstance(context), contentResolver,
                    MediaStoreExporter(contentResolver, dao, sessionDao),
                ).reconcile()

                val episode = dao.getById("ep-reconcile-1")!!
                assertEquals(
                    "deleted SAF export flips the row to FAILED (§29)",
                    EpisodeDownloadEntity.STATE_FAILED, episode.state,
                )
                assertEquals("file missing", episode.errorMessage)
            } finally {
                com.pahebatcher.app.data.export.SafTestSupport.clearTreeChildren(
                    context, tree,
                )
            }
        }
    }

    @Test
    fun reconcile_completed_revokedSafGrantUri_marksFailedFileMissing() {
        runBlocking {
            // Revocation shape (DECISIONS 92.3/103.4): a COMPLETED row whose
            // persisted outputUri is a SAF tree document of an EXTERNAL
            // provider whose grant is gone. The resolver query fails with
            // SecurityException — the exact post-revocation state of a real
            // external SAF tree (our in-package test provider cannot reproduce
            // it: same-UID access bypasses grants) — and §29 must demote
            // instead of trusting the dangling reference.
            val externalTree = android.provider.DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", "primary:Download",
            )
            val revokedUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                externalTree, "primary:Download",
            )
            dao.upsert(
                testEpisode.copy(
                    state = EpisodeDownloadEntity.STATE_COMPLETED,
                    outputPath = File(context.filesDir, "pbx_never_exported.mp4").absolutePath,
                    outputUri = revokedUri.toString(),
                ),
            )

            DownloadStateReconciler(
                dao, WorkManager.getInstance(context), contentResolver,
                MediaStoreExporter(contentResolver, dao, sessionDao),
            ).reconcile()

            val episode = dao.getById("ep-reconcile-1")!!
            assertEquals(
                "revoked SAF reference demotes the row (§29)",
                EpisodeDownloadEntity.STATE_FAILED, episode.state,
            )
            assertEquals("file missing", episode.errorMessage)
        }
    }

    @Test
    fun reconcile_completed_uriWithoutResolver_fallsBackToOutputPathRule() {
        runBlocking {
            // Host-constructed reconciler WITHOUT a ContentResolver (documented
            // §29 extension): the URI branch is unreachable, so the pre-export
            // outputPath rule governs both directions.
            val tempFile = File(context.filesDir, "test_no_resolver.mp4")
            tempFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02))

            dao.upsert(
                testEpisode.copy(
                    state = EpisodeDownloadEntity.STATE_COMPLETED,
                    outputPath = tempFile.absolutePath,
                    outputUri = "content://media/external/video/media/999999999",
                ),
            )
            val reconciler = DownloadStateReconciler(dao, WorkManager.getInstance(context))
            reconciler.reconcile()

            var episode = dao.getById("ep-reconcile-1")!!
            assertEquals(
                "fallback: existing local output keeps COMPLETED despite the URI",
                EpisodeDownloadEntity.STATE_COMPLETED, episode.state,
            )

            // …and the fallback's failure direction still demotes honestly.
            assertTrue(tempFile.delete())
            reconciler.reconcile()

            episode = dao.getById("ep-reconcile-1")!!
            assertEquals(
                EpisodeDownloadEntity.STATE_FAILED, episode.state,
            )
            assertEquals("file missing", episode.errorMessage)
        }
    }

    // ------------------------------------------------------------------
    // tree grants never
    // resurrect FAILED rows — recovery stays the explicit Retry
    // ------------------------------------------------------------------

    @Test
    fun grantingOrSwitchingASafTree_neverResurrectsAFailedRow() = runBlocking {
        // The FAILED row is fed through the PRODUCTION startup shape: the
        // export-destination router (SAF configured) is injected as the
        // reconciler's export owner, exactly like PaheBatcherApplication
        // wires AppContainer.episodeExporter. If any path wrongly routed the
        // FAILED row into an export, the tripwires below fire: outputUri gets
        // persisted and/or a child appears in the tree.
        val config = AppConfig(context)
        val outputTrees = OutputTreeRepository(contentResolver, config)
        val router = ExportDestinationRouter(
            outputTrees = outputTrees,
            config = config,
            safExporter = SafExporter(contentResolver, dao, config, sessionDao),
            mediaStoreExporter = MediaStoreExporter(contentResolver, dao, sessionDao),
        )
        val renameTree = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_RENAME)
        val noRenameTree = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_NORENAME)
        try {
            // Roots must exist so a (wrongful) SAF export would actually land
            // in the tree where the child tripwire can see it.
            for (rootId in listOf(
                PBTestDocumentsProvider.ROOT_RENAME,
                PBTestDocumentsProvider.ROOT_NORENAME,
            )) {
                File(context.getExternalFilesDir("pb_testdocs"), rootId).mkdirs()
            }

            dao.upsert(
                testEpisode.copy(
                    state = EpisodeDownloadEntity.STATE_FAILED,
                    errorMessage = "resolution failed",
                ),
            )

            // 1. Grant the rename-capable tree → reconcile. A REAL persistable
            //    grant keeps the router's SAF route armed (an ungranted tree
            //    would validate Unusable and silently fall back to MediaStore,
            //    weakening the child tripwire below).
            SafTestSupport.establishPersistableGrant(context, renameTree)
            outputTrees.setOutputTree(renameTree.toString())
            DownloadStateReconciler(
                dao, WorkManager.getInstance(context), contentResolver, router,
            ).reconcile()
            var episode = dao.getById("ep-reconcile-1")!!
            assertEquals(
                "granting a SAF tree must not resurrect a FAILED row (103.2)",
                EpisodeDownloadEntity.STATE_FAILED,
                episode.state,
            )
            assertEquals(
                "recovery stays the explicit Retry — the error is preserved",
                "resolution failed",
                episode.errorMessage,
            )
            assertNull("no export was ever attempted for the row", episode.outputUri)

            // 2. Switch to a different tree — the production pick sequence for
            //    the second tree (grant + setOutputTree) → reconcile again.
            SafTestSupport.establishPersistableGrant(context, noRenameTree)
            outputTrees.setOutputTree(noRenameTree.toString())
            DownloadStateReconciler(
                dao, WorkManager.getInstance(context), contentResolver, router,
            ).reconcile()
            episode = dao.getById("ep-reconcile-1")!!
            assertEquals(
                "switching SAF trees must not resurrect a FAILED row (103.2)",
                EpisodeDownloadEntity.STATE_FAILED,
                episode.state,
            )
            assertEquals("resolution failed", episode.errorMessage)
            assertNull("still no export attempted after the switch", episode.outputUri)

            assertTrue(
                "no child was ever created in either tree for the FAILED row",
                safChildNames(renameTree).isEmpty() && safChildNames(noRenameTree).isEmpty(),
            )
        } finally {
            runBlocking { outputTrees.clearOutputTree() }
            SafTestSupport.releasePersistableGrant(context, renameTree)
            SafTestSupport.releasePersistableGrant(context, noRenameTree)
            SafTestSupport.clearTreeChildren(context, renameTree)
            SafTestSupport.clearTreeChildren(context, noRenameTree)
        }
    }

    /** Display names of every child under [tree]'s root document. */
    private fun safChildNames(tree: android.net.Uri): List<String> {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            android.provider.DocumentsContract.getTreeDocumentId(tree),
        )
        return contentResolver.query(
            childrenUri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } ?: emptyList()
    }
}
