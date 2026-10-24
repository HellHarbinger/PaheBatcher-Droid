package com.pahebatcher.app.data.download

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.pahebatcher.app.BuildConfig
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.export.OutputPathNames
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 11.5E-6 — submission guard for [DownloadRepository.queueResolvedEpisode].
 *
 * The seam's KDoc promises idempotency ("a repeated call never starts a second
 * download of the same episode"), but before this guard `resolveComplete()`
 * ran unconditionally after `enqueue()`'s non-terminal early-return, so a
 * re-submission could:
 *
 *  * flip a live `DOWNLOADING`/`ASSEMBLING` row back to `QUEUED` while its
 *    Worker owned it (understating `countActive()` and letting the soft
 *    `maxParallel` gate admit an extra episode), and
 *  * `REPLACE` a `COMPLETED` row with a fresh entity, erasing
 *    `outputPath`/`outputUri`/`retryCount` (§17.1: re-download "would clobber
 *    a verified output"; Python skips an episode whose output already exists —
 *    `downloader.py:60-62` `run()`).
 *
 * The suppression is deliberately narrow: `QUEUED`/`RESOLVING` rows keep the
 * `resolveComplete()` recovery path (the details-screen escape from a zombie
 * row, LIMITATIONS §24 finding 2), and `FAILED`/`CANCELLED` rows — plus
 * `COMPLETED` rows whose output is genuinely gone — still re-queue.
 *
 * Runs against the REAL Room database singleton and the REAL application
 * WorkManager (custom DownloadWorkerFactory registered by PaheBatcherApplication).
 */
@RunWith(AndroidJUnit4::class)
class QueueResolvedEpisodeGuardTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-guard-test"

    /** `episodeDownloadId()` = `${animeSession}_${epStr}_${audio}` (whole numbers). */
    private fun episodeId(number: Int) = "${sessionId}_${number}_${EpisodeInfo.AUDIO_JAPANESE}"

    private fun markerEpisode(
        number: Int,
        state: String,
        block: (EpisodeDownloadEntity) -> EpisodeDownloadEntity = { it },
    ) = block(
        EpisodeDownloadEntity(
            episodeId = episodeId(number),
            sessionId = sessionId,
            episodeNumber = number.toFloat(),
            episodeSession = "eps-guard-$number",
            title = "Guard Test Episode $number",
            fansub = "TestFansub",
            audio = EpisodeInfo.AUDIO_JAPANESE,
            playUrl = "https://animepahe.com/play/$sessionId/eps-guard-$number",
            state = state,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ),
    )

    private fun submission(number: Int, m3u8Url: String) = ResolvedEpisodeSubmission(
        animeSession = sessionId,
        animeTitle = "Guard Test Anime",
        host = "animepahe.com",
        quality = 1080,
        episode = EpisodeInfo(
            number = number.toFloat(),
            session = "eps-guard-$number",
            title = "",
            fansub = "",
            audio = EpisodeInfo.AUDIO_JAPANESE,
            playUrl = "https://animepahe.com/play/$sessionId/eps-guard-$number",
        ),
        stream = StreamInfo(
            url = m3u8Url,
            userAgent = "FreshUA",
            referer = "https://kwik.cx/e/abc",
            title = "Guard Test Anime Ep. $number :: animepahe",
        ),
    )

    private fun workInfos(number: Int) =
        workManager.getWorkInfosForUniqueWork("episode_download_${episodeId(number)}").get()

    private fun assertNoWorkerEnqueued(number: Int) =
        assertTrue(
            "a suppressed submission must not register any work",
            workInfos(number).isEmpty(),
        )

    /**
     * Await the enqueued WorkInfo instead of racing it.
     *
     * [DownloadRepository.enqueueWorker] calls `enqueueUniqueWork` and returns;
     * WorkManager applies that write to its OWN database asynchronously, so
     * reading straight back is a coin toss about scheduler timing rather than a
     * statement about the hand-off. This waits for the write to land. What is
     * asserted (work exists) is unchanged — only the timing dependency goes.
     */
    private fun awaitWorkerRegistered(number: Int, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (workInfos(number).isNotEmpty()) return true
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(50)
        }
    }

    /**
     * Slice B (DECISIONS 146): a repository wired with the REAL resolver, which
     * is what `AppContainer` gives production. Host-constructed tests keep the
     * default `contentResolver = null`; these twins exist precisely to prove
     * the difference is observable rather than asserted.
     */
    private fun resolverRepository() = DownloadRepository(
        context,
        db.episodeDownloadDao(),
        db.downloadSessionDao(),
        contentResolver = context.contentResolver,
    )

    /** Run-unique token so teardown can find and remove the MediaStore fixture. */
    private var mediaToken: String? = null

    /**
     * A REAL `content://` reference the resolver can resolve: a MediaStore row
     * owned by this app, carrying real bytes.
     *
     * Hand-writing `content://media/external/video/media/999999999` would only
     * ever exercise the unresolvable branch, so a test that wants to prove the
     * resolver VERIFIES something has to give it something verifiable.
     */
    private fun insertResolvableOutput(number: Int): Uri {
        val token = "pbxguard${number}_${System.nanoTime()}"
        mediaToken = token
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, OutputPathNames.MOVIES_PREFIX + "PaheBatcher/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
            "the MediaStore fixture insert must succeed"
        }
        resolver.openOutputStream(uri, "w")!!.use { it.write(ByteArray(2048) { (it % 251).toByte() }) }
        return uri
    }

    /**
     * §36-14 hygiene: remove the pending fixture row. The legacy query overload
     * cannot see IS_PENDING rows, so Bundle + MATCH_INCLUDE is required or the
     * row leaks onto the device between runs.
     */
    private fun deleteFixtureMediaRow() {
        val token = mediaToken ?: return
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$token.mp4"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val doomed = mutableListOf<Long>()
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id"),
            args,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            while (cursor.moveToNext()) doomed += cursor.getLong(idIdx)
        }
        doomed.forEach { id ->
            resolver.delete(
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                null,
                null,
            )
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        // 1..10 covers every episode number this class touches, including the
        // three Slice B twins (9 and 10) — otherwise a live unique-work chain
        // would leak into the next test that reuses the name.
        (1..10).forEach { workManager.cancelUniqueWork("episode_download_${episodeId(it)}") }
        deleteFixtureMediaRow()
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    // ── Suppressed: a Worker owns the row ────────────────────────────────────

    @Test
    fun resubmission_of_a_live_downloading_row_is_suppressed() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(
                markerEpisode(1, EpisodeDownloadEntity.STATE_DOWNLOADING).copy(
                    m3u8Url = "https://cdn.example.com/marker-live.m3u8",
                    userAgent = "MarkerUA",
                    referer = "https://example.com/marker",
                    segmentDir = "${context.filesDir}/segments/guard-live",
                ),
            )

            repository.queueResolvedEpisode(
                submission(1, "https://cdn.example.com/fresh.m3u8"),
            )

            val row = dao.getById(episodeId(1))
            assertEquals(
                "the live row's state must not be flipped back to QUEUED",
                EpisodeDownloadEntity.STATE_DOWNLOADING,
                row?.state,
            )
            assertEquals(
                "the live Worker's persisted stream must not be overwritten",
                "https://cdn.example.com/marker-live.m3u8",
                row?.m3u8Url,
            )
            assertEquals(
                "the live Worker's persisted User-Agent must not be overwritten",
                "MarkerUA",
                row?.userAgent,
            )
            assertNoWorkerEnqueued(1)
        }
    }

    @Test
    fun resubmission_of_an_assembling_row_is_suppressed() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(
                markerEpisode(2, EpisodeDownloadEntity.STATE_ASSEMBLING).copy(
                    m3u8Url = "https://cdn.example.com/marker-assembling.m3u8",
                    segmentDir = "${context.filesDir}/segments/guard-assembling",
                ),
            )

            repository.queueResolvedEpisode(
                submission(2, "https://cdn.example.com/fresh.m3u8"),
            )

            val row = dao.getById(episodeId(2))
            assertEquals(
                EpisodeDownloadEntity.STATE_ASSEMBLING,
                row?.state,
            )
            assertEquals(
                "https://cdn.example.com/marker-assembling.m3u8",
                row?.m3u8Url,
            )
            assertNoWorkerEnqueued(2)
        }
    }

    // ── Suppressed: COMPLETED with a present output (Python run():60-62) ─────

    @Test
    fun resubmission_of_a_completed_row_with_a_local_output_is_suppressed() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val localOutput = File(context.cacheDir, "guard-completed.mp4")
            localOutput.writeBytes("already-assembled-mp4".toByteArray())
            try {
                dao.upsert(
                    markerEpisode(3, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                        m3u8Url = "https://cdn.example.com/marker-done.m3u8",
                        outputPath = localOutput.absolutePath,
                        outputUri = null,
                        retryCount = 2,
                        segmentDir = "${context.filesDir}/segments/guard-done",
                    ),
                )

                repository.queueResolvedEpisode(
                    submission(3, "https://cdn.example.com/fresh.m3u8"),
                )

                val row = dao.getById(episodeId(3))
                assertEquals(
                    "a valid COMPLETED row must stay COMPLETED",
                    EpisodeDownloadEntity.STATE_COMPLETED,
                    row?.state,
                )
                assertEquals(
                    "the output reference must survive a re-submission",
                    localOutput.absolutePath,
                    row?.outputPath,
                )
                assertEquals(2, row?.retryCount)
                assertEquals(
                    "the persisted stream must not be overwritten",
                    "https://cdn.example.com/marker-done.m3u8",
                    row?.m3u8Url,
                )
                assertNoWorkerEnqueued(3)
            } finally {
                localOutput.delete()
            }
        }
    }

    /**
     * Slice B (DECISIONS 146) — the NO-RESOLVER fallback, reframed.
     *
     * This repository was built exactly as every host-constructed test builds
     * it, with `contentResolver = null`, so a `content://` reference CANNOT be
     * verified. The 11.5E-6 implementation trusted any non-blank URI outright,
     * which let the repository report "already downloaded" for a reference
     * nobody had ever checked — while `DownloadStateReconciler` independently
     * resolved that same column and would have demoted the row to `FAILED` at
     * the next launch. Both now share [EpisodeOutputVerifier], and its
     * documented conservative rule is what this asserts: an unverifiable
     * reference is not proof, the `outputPath` rule decides, and the episode is
     * genuinely re-downloadable.
     */
    @Test
    fun an_unverifiable_export_uri_is_never_treated_as_a_present_output() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            // Exported row (X6): MediaStore URI kept, local file removed — and
            // the path this test names does not exist either.
            dao.upsert(
                markerEpisode(4, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                    m3u8Url = "https://cdn.example.com/marker-exported.m3u8",
                    outputPath = "${context.filesDir}/segments/guard-exported/Ep_004.mp4",
                    outputUri = "content://media/external/video/media/999999999",
                    segmentDir = "${context.filesDir}/segments/guard-exported",
                ),
            )

            val outcome = repository.queueResolvedEpisode(
                submission(4, "https://cdn.example.com/fresh.m3u8"),
            )

            assertEquals(
                "no output could be verified — the episode is re-downloaded",
                EnqueueOutcome.OutputMissing,
                outcome,
            )
            val row = dao.getById(episodeId(4))
            assertEquals(
                EpisodeDownloadEntity.STATE_QUEUED,
                row?.state,
            )
            assertEquals("https://cdn.example.com/fresh.m3u8", row?.m3u8Url)
            assertTrue(
                "the re-queue must register its Worker",
                awaitWorkerRegistered(4),
            )
        }
    }

    /**
     * Slice B (DECISIONS 146) — the resolver-injected twin of the test above.
     *
     * The same X6 shape, but this repository is wired with the REAL
     * `ContentResolver`, exactly as `AppContainer` wires production, so the
     * persisted URI is genuinely resolved before anything is reported. A live
     * MediaStore row carrying real bytes is the only honest way to obtain such
     * a reference — a hand-written `content://…/999999999` resolves to nothing
     * and would only assert the fallback again.
     *
     * Episode 9 rather than 4: each of these three twins uses its own number so
     * that WorkManager's per-episode unique-work chain from a sibling test can
     * never be mistaken for this one's.
     */
    @Test
    fun a_resolved_export_uri_is_suppressed_when_the_resolver_can_verify_it() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val liveExport = insertResolvableOutput(9)
            // X6 shape: the URI is kept and the app-private file is gone.
            val staleLocal = File(context.cacheDir, "guard-adopted-absent.mp4")
            staleLocal.delete()
            dao.upsert(
                markerEpisode(9, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                    m3u8Url = "https://cdn.example.com/marker-exported.m3u8",
                    outputPath = staleLocal.absolutePath,
                    outputUri = liveExport.toString(),
                    segmentDir = "${context.filesDir}/segments/guard-exported-live",
                ),
            )

            val outcome = resolverRepository().queueResolvedEpisode(
                submission(9, "https://cdn.example.com/fresh.m3u8"),
            )

            assertEquals(
                "a real, non-empty export is a real download",
                EnqueueOutcome.AlreadyDownloaded,
                outcome,
            )
            val row = dao.getById(episodeId(9))
            assertEquals(
                "a verified COMPLETED row must stay COMPLETED",
                EpisodeDownloadEntity.STATE_COMPLETED,
                row?.state,
            )
            assertEquals(
                "the MediaStore reference must survive a re-submission",
                liveExport.toString(),
                row?.outputUri,
            )
            assertEquals(
                "the persisted stream must not be overwritten",
                "https://cdn.example.com/marker-exported.m3u8",
                row?.m3u8Url,
            )
            assertNoWorkerEnqueued(9)
        }
    }

    /**
     * Slice B (DECISIONS 146) — an unresolvable URI must never crash and must
     * never claim a download.
     *
     * With a resolver wired, `content://…/999999999` names no MediaStore row:
     * the query yields nothing, the descriptor open throws, and the verifier
     * reports "does not resolve" instead of propagating. The episode is then
     * re-downloaded, which is the only honest answer for a reference whose
     * backing file is gone. Nothing is deleted or demoted here — the row simply
     * stops being treated as a finished download.
     */
    @Test
    fun an_unresolvable_export_uri_falls_through_to_a_real_re_download() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val vanished = File(context.cacheDir, "guard-unresolvable.mp4")
            vanished.delete()
            dao.upsert(
                markerEpisode(10, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                    m3u8Url = "https://cdn.example.com/marker-gone.m3u8",
                    outputPath = vanished.absolutePath,
                    outputUri = "content://media/external/video/media/999999999",
                    segmentDir = "${context.filesDir}/segments/guard-unresolvable",
                ),
            )

            val outcome = resolverRepository().queueResolvedEpisode(
                submission(10, "https://cdn.example.com/fresh.m3u8"),
            )

            assertEquals(EnqueueOutcome.OutputMissing, outcome)
            val row = dao.getById(episodeId(10))
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
            assertEquals("https://cdn.example.com/fresh.m3u8", row?.m3u8Url)
            assertTrue(awaitWorkerRegistered(10))
        }
    }

    // ── Allowed: COMPLETED whose output is genuinely gone ────────────────────

    @Test
    fun resubmission_of_a_completed_row_whose_output_is_gone_proceeds() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val vanished = File(context.cacheDir, "guard-vanished.mp4")
            vanished.delete() // ensure it does not exist
            dao.upsert(
                markerEpisode(5, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                    outputPath = vanished.absolutePath,
                    outputUri = null,
                    segmentDir = "${context.filesDir}/segments/guard-vanished",
                ),
            )

            repository.queueResolvedEpisode(
                submission(5, "https://cdn.example.com/fresh.m3u8"),
            )

            val row = dao.getById(episodeId(5))
            assertEquals(
                "a COMPLETED row without any output is re-downloadable",
                EpisodeDownloadEntity.STATE_QUEUED,
                row?.state,
            )
            assertEquals("https://cdn.example.com/fresh.m3u8", row?.m3u8Url)
            assertTrue("the re-queue must register its Worker", workInfos(5).isNotEmpty())
        }
    }

    @Test
    fun resubmission_of_a_completed_row_with_an_empty_output_file_proceeds() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val emptyOutput = File(context.cacheDir, "guard-empty.mp4")
            assertTrue(emptyOutput.createNewFile())
            assertEquals(0L, emptyOutput.length())
            try {
                dao.upsert(
                    markerEpisode(8, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                        outputPath = emptyOutput.absolutePath,
                        outputUri = null,
                        segmentDir = "${context.filesDir}/segments/guard-empty",
                    ),
                )

                repository.queueResolvedEpisode(
                    submission(8, "https://cdn.example.com/fresh.m3u8"),
                )

                val row = dao.getById(episodeId(8))
                assertEquals(
                    "a zero-byte output fails Python's st_size > 0 rule — re-queue",
                    EpisodeDownloadEntity.STATE_QUEUED,
                    row?.state,
                )
                assertEquals("https://cdn.example.com/fresh.m3u8", row?.m3u8Url)
                assertTrue(workInfos(8).isNotEmpty())
            } finally {
                emptyOutput.delete()
            }
        }
    }

    // ── Allowed: the recovery and re-queue paths ─────────────────────────────

    @Test
    fun resubmission_recovers_a_queued_row_without_stream_info() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            // Zombie row: queued with no stream (LIMITATIONS §24 finding 2) —
            // the details-screen re-submission is its escape route and must
            // keep working through resolveComplete().
            dao.upsert(
                markerEpisode(6, EpisodeDownloadEntity.STATE_QUEUED).copy(
                    m3u8Url = null,
                    userAgent = null,
                    referer = null,
                    segmentDir = "${context.filesDir}/segments/guard-zombie",
                ),
            )

            repository.queueResolvedEpisode(
                submission(6, "https://cdn.example.com/fresh.m3u8"),
            )

            val row = dao.getById(episodeId(6))
            assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
            assertEquals(
                "the fresh stream must repopulate the stream-info-less row",
                "https://cdn.example.com/fresh.m3u8",
                row?.m3u8Url,
            )
            assertEquals("FreshUA", row?.userAgent)
            assertTrue("the recovered row must get its Worker", workInfos(6).isNotEmpty())
        }
    }

    @Test
    fun resubmission_of_a_failed_row_proceeds() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            dao.upsert(
                markerEpisode(7, EpisodeDownloadEntity.STATE_FAILED).copy(
                    errorMessage = "previous error",
                    segmentDir = "${context.filesDir}/segments/guard-failed",
                ),
            )

            repository.queueResolvedEpisode(
                submission(7, "https://cdn.example.com/fresh.m3u8"),
            )

            val row = dao.getById(episodeId(7))
            assertEquals(
                "FAILED is the legitimate re-queue case",
                EpisodeDownloadEntity.STATE_QUEUED,
                row?.state,
            )
            assertEquals("https://cdn.example.com/fresh.m3u8", row?.m3u8Url)
            assertTrue(workInfos(7).isNotEmpty())
        }
    }
}
