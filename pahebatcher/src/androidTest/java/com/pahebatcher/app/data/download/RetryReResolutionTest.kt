package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pahebatcher.app.data.hls.AesKeyCache
import com.pahebatcher.app.data.hls.EpisodeDownloader
import com.pahebatcher.app.data.hls.SegmentFileStore
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolutionException
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.RecordStreamResolver
import com.pahebatcher.app.support.RetryTestStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 11.5E-7 — §24.1 Download Retry re-resolution.
 *
 * The pre-11.5E-7 `reEnqueue()` cleared the stream info and enqueued a Worker
 * directly, so every Downloads-screen Retry failed deterministically with
 * "No stream info available" (the Worker may never resolve). The fixed contract:
 *
 *   FAILED/CANCELLED → Retry → RESOLVING → fresh [StreamResolver.resolve] from
 *   the persisted `playUrl` → the EXISTING `queueResolvedEpisode()` path →
 *   unique Worker (KEEP) → segment resume → assembly.
 *
 * Runs against the REAL Room database singleton and the REAL application
 * WorkManager (custom DownloadWorkerFactory registered by PaheBatcherApplication).
 * The resolver is a test double — production uses the app-scoped WebView
 * resolver (AppContainer); mocks in tests are permitted, never in production.
 */
@RunWith(AndroidJUnit4::class)
class RetryReResolutionTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-retry-$runStamp"

    /** `episodeDownloadId()` = `<animeSession>_<epStr>_<audio>` (whole numbers). */
    private fun episodeId(number: Int) =
        "${sessionId}_${number}_${EpisodeInfo.AUDIO_JAPANESE}"

    private fun repositoryWith(resolver: StreamResolver?): DownloadRepository =
        DownloadRepository(
            context,
            db.episodeDownloadDao(),
            db.downloadSessionDao(),
            streamResolver = resolver,
        )

    private fun playUrl(number: Int) =
        "https://animepahe.com/play/$sessionId/eps-retry-$number"

    private fun seedEpisode(
        number: Int,
        state: String,
        block: (EpisodeDownloadEntity) -> EpisodeDownloadEntity = { it },
    ) = block(
        EpisodeDownloadEntity(
            episodeId = episodeId(number),
            sessionId = sessionId,
            episodeNumber = number.toFloat(),
            episodeSession = "eps-retry-$number",
            title = "Retry Episode $number",
            fansub = "RetryFansub",
            audio = EpisodeInfo.AUDIO_JAPANESE,
            playUrl = playUrl(number),
            state = state,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ),
    )

    private fun workInfos(number: Int): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork("episode_download_${episodeId(number)}").get()

    private fun assertNoWorker(number: Int) =
        assertTrue("no work may exist for episode $number", workInfos(number).isEmpty())

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        (1..18).forEach { workManager.cancelUniqueWork("episode_download_${episodeId(it)}") }
        workManager.cancelUniqueWork("episode_download_legacy-${sessionId}-ep15")
        (1..18).forEach {
            File(context.filesDir, "downloads/${episodeId(it)}").deleteRecursively()
        }
        File(context.filesDir, "downloads/legacy-${sessionId}-ep15").deleteRecursively()
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    // ── Test A: FAILED retry re-resolves ─────────────────────────────────────

    @Test
    fun failed_retry_re_resolves_fresh_and_queues_one_worker() = runBlocking {
        val dao = db.episodeDownloadDao()
        var stateDuringResolve: String? = null
        val resolver = RecordStreamResolver { _ ->
            // Prove the session is IN RESOLVING while the resolver runs.
            stateDuringResolve = dao.getById(episodeId(1))?.state
            RetryTestStream.streamInfo()
        }
        dao.upsert(
            seedEpisode(1, EpisodeDownloadEntity.STATE_FAILED).copy(
                m3u8Url = "https://cdn.example.com/stale.m3u8",
                userAgent = "StaleUA",
                referer = "https://stale.example.com",
                errorMessage = "previous failure",
                retryCount = 2,
                completedSegments = 5,
                totalSegments = 10,
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(1)),
            ),
        )

        repositoryWith(resolver).reEnqueue(episodeId(1))

        // The resolver received the PERSISTED playUrl plus session quality and
        // the episode's own audio — nothing was lost in the retry hand-off.
        assertEquals(1, resolver.calls.size)
        val call = resolver.calls[0]
        assertEquals(playUrl(1), call.playUrl)
        assertEquals(db.downloadSessionDao().getById(sessionId)?.quality, call.quality)
        assertEquals(EpisodeInfo.AUDIO_JAPANESE, call.audio)
        assertEquals(
            "the session must be RESOLVING while the fresh resolution runs",
            EpisodeDownloadEntity.STATE_RESOLVING,
            stateDuringResolve,
        )

        // State was read immediately after reEnqueue returned — the queued
        // hand-off completed; the enqueued Worker may progress afterwards.
        val row = dao.getById(episodeId(1))
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)

        // Fresh stream persisted; stale credentials replaced, not merged.
        assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)
        assertEquals(RetryTestStream.USER_AGENT, row?.userAgent)
        assertEquals(RetryTestStream.REFERER, row?.referer)
        assertNotNull(row?.segmentDir)

        // Identity, playUrl and progress survived; retryCount counted ONE attempt.
        assertEquals(playUrl(1), row?.playUrl)
        assertEquals("eps-retry-1", row?.episodeSession)
        assertEquals(3, row?.retryCount)
        assertEquals(5, row?.completedSegments)
        assertEquals(10, row?.totalSegments)
        assertNull("the stale reason died with the retry claim", row?.errorMessage)

        // Exactly one Worker: one enqueue, never a duplicate active job.
        val infos = workInfos(1)
        assertEquals("exactly one unique work entry", 1, infos.size)
        assertTrue(infos.count { !it.state.isFinished } <= 1)
    }

    // ── Test B: CANCELLED retry re-resolves ──────────────────────────────────

    @Test
    fun cancelled_retry_re_resolves_fresh_and_queues_one_worker() = runBlocking {
        val dao = db.episodeDownloadDao()
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }
        dao.upsert(
            seedEpisode(2, EpisodeDownloadEntity.STATE_CANCELLED).copy(
                m3u8Url = "https://cdn.example.com/cancelled-stale.m3u8",
                userAgent = "StaleUA",
                errorMessage = null,
                retryCount = 1,
                // Stale export reference from an earlier, later-demoted export:
                // it must NOT survive into the fresh attempt (S-4).
                outputUri = "content://media/external/video/media/888888888",
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(2)),
            ),
        )

        repositoryWith(resolver).reEnqueue(episodeId(2))

        assertEquals(1, resolver.calls.size)
        assertEquals(playUrl(2), resolver.calls[0].playUrl)

        val row = dao.getById(episodeId(2))
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
        assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)
        assertEquals(playUrl(2), row?.playUrl)
        assertNull(
            "the stale export URI must die with the claim — §29 prefers outputUri and would demote the FRESH download at the next startup",
            row?.outputUri,
        )
        assertEquals("a CANCELLED retry counts exactly one attempt", 2, row?.retryCount)

        val infos = workInfos(2)
        assertEquals(1, infos.size)
        assertTrue(infos.count { !it.state.isFinished } <= 1)
    }

    // ── Test C: retry preserves completed segments; only missing fetched ─────

    @Test
    fun retry_preserves_completed_segments_and_downloads_only_missing() = runBlocking {
        val dao = db.episodeDownloadDao()
        val number = 3
        val segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(number))
        val store = SegmentFileStore(File(segmentDir))
        // Phase 8 persisted segment identity: three completed .ts files.
        store.writeSeg(0, "seg-zero-bytes".toByteArray())
        store.writeSeg(1, "seg-one-bytes".toByteArray())
        store.writeSeg(2, "seg-two-bytes".toByteArray())

        dao.upsert(
            seedEpisode(number, EpisodeDownloadEntity.STATE_FAILED).copy(
                m3u8Url = "https://cdn.example.com/stale.m3u8",
                completedSegments = 3,
                totalSegments = 4,
                segmentDir = segmentDir,
            ),
        )

        repositoryWith(RecordStreamResolver()).reEnqueue(episodeId(number))

        // The retry hand-off left every completed segment byte-identical and
        // the persisted progress/segmentDir untouched.
        assertArrayEquals("seg-zero-bytes".toByteArray(), store.segPath(0).readBytes())
        assertArrayEquals("seg-one-bytes".toByteArray(), store.segPath(1).readBytes())
        assertArrayEquals("seg-two-bytes".toByteArray(), store.segPath(2).readBytes())
        val row = dao.getById(episodeId(number))
        assertEquals(3, row?.completedSegments)
        assertEquals(4, row?.totalSegments)
        assertEquals(segmentDir, row?.segmentDir)
        assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)

        // Run the REAL downloader (production SegmentFileStore + EpisodeDownloader,
        // test-double HlsClient) on the post-retry row: only the MISSING segment
        // may be fetched — pre-existing ones are never re-downloaded.
        val playlistUrl = RetryTestStream.M3U8_URL
        val segUrls = (0..3).map { "https://cdn.local.test/retry/seg$it.ts" }
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:10")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            segUrls.forEach { url ->
                appendLine("#EXTINF:10.0,")
                appendLine(url)
            }
        }
        val hlsClient = CountingHlsClient(
            text = mapOf(playlistUrl to playlist),
            bytes = mapOf(segUrls[3] to "seg-three-bytes".toByteArray()),
        )
        val downloader = EpisodeDownloader(
            hlsClient = hlsClient,
            keyCache = AesKeyCache(),
            segmentStore = SegmentFileStore(File(row!!.segmentDir!!)),
            hlsWorkers = 4,
            // keepTemp=true: this test's contract is the ON-DISK segment bytes
            // surviving assembly (the default keepTemp=false cleans them up
            // after a successful assemble — covered by EpisodeDownloaderTest).
            keepTemp = true,
        )

        val result = downloader.download(
            streamInfo = StreamInfo(
                url = playlistUrl,
                userAgent = RetryTestStream.USER_AGENT,
                referer = RetryTestStream.REFERER,
            ),
        )

        assertTrue("download after retry must succeed", result.success)
        assertEquals(4, result.completedSegments)
        assertEquals("segment 0 must not be refetched", 0, hlsClient.count(segUrls[0]))
        assertEquals("segment 1 must not be refetched", 0, hlsClient.count(segUrls[1]))
        assertEquals("segment 2 must not be refetched", 0, hlsClient.count(segUrls[2]))
        assertEquals("only the missing segment is fetched", 1, hlsClient.count(segUrls[3]))
        // Original bytes still on disk (Phase 8 identity preserved).
        assertArrayEquals("seg-zero-bytes".toByteArray(), store.segPath(0).readBytes())
        assertArrayEquals("seg-one-bytes".toByteArray(), store.segPath(1).readBytes())
        assertArrayEquals("seg-two-bytes".toByteArray(), store.segPath(2).readBytes())
        assertTrue(store.hasSeg(3))
    }

    // ── Test D: retry refused while a Worker owns the row ────────────────────

    @Test
    fun retry_is_refused_while_downloading_or_assembling() = runBlocking {
        val dao = db.episodeDownloadDao()
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }
        dao.upsert(
            seedEpisode(4, EpisodeDownloadEntity.STATE_DOWNLOADING).copy(
                m3u8Url = "https://cdn.example.com/live.m3u8",
                userAgent = "LiveUA",
                completedSegments = 7,
                retryCount = 1,
            ),
        )
        dao.upsert(
            seedEpisode(5, EpisodeDownloadEntity.STATE_ASSEMBLING).copy(
                m3u8Url = "https://cdn.example.com/assembling.m3u8",
                retryCount = 1,
            ),
        )

        repositoryWith(resolver).reEnqueue(episodeId(4))
        repositoryWith(resolver).reEnqueue(episodeId(5))

        assertEquals("an active row must never trigger resolution", 0, resolver.calls.size)
        val downloading = dao.getById(episodeId(4))
        assertEquals(EpisodeDownloadEntity.STATE_DOWNLOADING, downloading?.state)
        assertEquals("https://cdn.example.com/live.m3u8", downloading?.m3u8Url)
        assertEquals("LiveUA", downloading?.userAgent)
        assertEquals(1, downloading?.retryCount)
        assertEquals(7, downloading?.completedSegments)

        val assembling = dao.getById(episodeId(5))
        assertEquals(EpisodeDownloadEntity.STATE_ASSEMBLING, assembling?.state)
        assertEquals(1, assembling?.retryCount)

        assertNoWorker(4)
        assertNoWorker(5)
    }

    // ── Test E: double retry claims exactly once ─────────────────────────────

    @Test
    fun double_retry_claims_the_row_exactly_once() = runBlocking {
        val dao = db.episodeDownloadDao()
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }
        dao.upsert(
            seedEpisode(6, EpisodeDownloadEntity.STATE_FAILED).copy(
                m3u8Url = "https://cdn.example.com/stale.m3u8",
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(6)),
            ),
        )
        val repo = repositoryWith(resolver)

        coroutineScope {
            launch { repo.reEnqueue(episodeId(6)) }
            launch { repo.reEnqueue(episodeId(6)) }
        }

        // Exactly one claim: one resolver call, ONE retryCount increment.
        assertEquals("a double-tap must resolve exactly once", 1, resolver.calls.size)
        val row = dao.getById(episodeId(6))
        assertEquals(1, row?.retryCount)
        assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)
        assertEquals(playUrl(6), row?.playUrl)
        assertTrue(
            "final state must be coherent (got ${row?.state})",
            row?.state in setOf(
                EpisodeDownloadEntity.STATE_QUEUED,
                EpisodeDownloadEntity.STATE_DOWNLOADING,
                EpisodeDownloadEntity.STATE_FAILED,
            ),
        )

        // One enqueueUniqueWork → one work entry, never two active jobs.
        val infos = workInfos(6)
        assertEquals("exactly one unique work entry", 1, infos.size)
        assertTrue(infos.count { !it.state.isFinished } <= 1)
    }

    // ── Test F: COMPLETED + valid output stays protected (11.5E-6 rule) ─────

    /**
     * The local-file half of the 11.5E-6 rule, unchanged by Slice B.
     *
     * `outputUri` is null, so the §29 rule never consults a resolver: the
     * `outputPath` decides, the file is real and non-empty, and the retry is
     * refused before it touches the stream.
     */
    @Test
    fun retry_of_completed_output_stays_suppressed() = runBlocking {
        val dao = db.episodeDownloadDao()
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }
        val localOutput = File(context.cacheDir, "retry-completed-$runStamp.mp4")
        localOutput.writeBytes("already-assembled-mp4".toByteArray())
        try {
            dao.upsert(
                seedEpisode(7, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                    m3u8Url = "https://cdn.example.com/done.m3u8",
                    outputPath = localOutput.absolutePath,
                    outputUri = null,
                    retryCount = 2,
                    segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(7)),
                ),
            )

            repositoryWith(resolver).reEnqueue(episodeId(7))

            assertEquals("a valid COMPLETED output must never re-resolve", 0, resolver.calls.size)
            val local = dao.getById(episodeId(7))
            assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, local?.state)
            assertEquals(localOutput.absolutePath, local?.outputPath)
            assertEquals(2, local?.retryCount)
            assertNoWorker(7)
        } finally {
            localOutput.delete()
        }
    }

    /**
     * Slice B (DECISIONS 146) — the exported half of that rule, reframed.
     *
     * This row's only evidence is `content://media/external/video/media/999999999`,
     * and `repositoryWith(...)` wires no `ContentResolver` (the default every
     * host-constructed test uses). Before this slice the repository treated any
     * non-blank URI as proof of a finished download while `DownloadStateReconciler`
     * independently resolved the SAME column and would have demoted the row at
     * the next launch — the two could disagree about one row. Both now share
     * [EpisodeOutputVerifier], whose documented conservative rule is what this
     * asserts: an unverifiable reference proves nothing, the `outputPath` rule
     * decides, the app-private file named there does not exist, and the retry is
     * therefore a legitimate re-download rather than a suppressed no-op.
     *
     * The resolver-injected counterpart — where a real, non-empty MediaStore row
     * IS verified and suppresses the retry — lives in
     * `QueueResolvedEpisodeGuardTest.a_resolved_export_uri_is_suppressed_when_the_resolver_can_verify_it`.
     */
    @Test
    fun retry_of_an_exported_row_without_a_resolver_falls_back_to_the_path_rule() = runBlocking {
        val dao = db.episodeDownloadDao()
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }
        dao.upsert(
            seedEpisode(8, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                outputPath = "${context.filesDir}/downloads/ep8/Ep_008.mp4",
                outputUri = "content://media/external/video/media/999999999",
                retryCount = 1,
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(8)),
            ),
        )

        repositoryWith(resolver).reEnqueue(episodeId(8))

        assertEquals(
            "an unverifiable URI must not freeze the episode in COMPLETED",
            1,
            resolver.calls.size,
        )
        val row = dao.getById(episodeId(8))
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
        assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)
        // `beginRetry` clears the reference this download can no longer prove —
        // it is not silently carried into a fresh attempt.
        assertNull(row?.outputUri)
        assertEquals(2, row?.retryCount)
        assertEquals(1, workInfos(8).size)
    }

    @Test
    fun completed_with_gone_output_re_resolves() = runBlocking {
        val dao = db.episodeDownloadDao()
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }
        val vanished = File(context.cacheDir, "retry-vanished-$runStamp.mp4")
        vanished.delete()
        dao.upsert(
            seedEpisode(9, EpisodeDownloadEntity.STATE_COMPLETED).copy(
                m3u8Url = "https://cdn.example.com/done.m3u8",
                outputPath = vanished.absolutePath,
                outputUri = null,
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(9)),
            ),
        )

        repositoryWith(resolver).reEnqueue(episodeId(9))

        assertEquals(1, resolver.calls.size)
        val row = dao.getById(episodeId(9))
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
        assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)
        assertEquals(1, row?.retryCount)
        assertEquals(1, workInfos(9).size)
    }

    // ── Test G: resolution failure leaves a retryable state ──────────────────

    @Test
    fun resolution_failure_leaves_retryable_state_and_playUrl() = runBlocking {
        val dao = db.episodeDownloadDao()
        val failing = RecordStreamResolver {
            throw StreamResolutionException("Resolution blocked (test)")
        }
        dao.upsert(
            seedEpisode(10, EpisodeDownloadEntity.STATE_FAILED).copy(
                m3u8Url = "https://cdn.example.com/stale.m3u8",
                errorMessage = "older error",
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(10)),
            ),
        )

        repositoryWith(failing).reEnqueue(episodeId(10))

        // No download queued, honest failure, playUrl kept for the next Retry.
        val row = dao.getById(episodeId(10))
        assertEquals(EpisodeDownloadEntity.STATE_FAILED, row?.state)
        assertEquals("Resolution blocked (test)", row?.errorMessage)
        assertEquals(playUrl(10), row?.playUrl)
        assertNull("stale stream info must not survive as valid", row?.m3u8Url)
        assertNull(row?.userAgent)
        assertEquals(1, row?.retryCount)
        assertNoWorker(10)

        // The SAME persisted row succeeds through a later Retry (playUrl alive).
        val recovering = RecordStreamResolver { RetryTestStream.streamInfo() }
        repositoryWith(recovering).reEnqueue(episodeId(10))

        assertEquals(1, recovering.calls.size)
        assertEquals(playUrl(10), recovering.calls[0].playUrl)
        val recovered = dao.getById(episodeId(10))
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, recovered?.state)
        assertEquals(RetryTestStream.M3U8_URL, recovered?.m3u8Url)
        assertEquals(2, recovered?.retryCount)
        assertEquals(1, workInfos(10).size)
    }

    // ── Test H: cancellation during resolution ───────────────────────────────

    @Test
    fun cancel_during_resolution_discards_the_fresh_result() = runBlocking {
        val dao = db.episodeDownloadDao()
        val resolverStarted = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        val resolver = RecordStreamResolver {
            resolverStarted.complete(Unit)
            releaseResolver.await()
            RetryTestStream.streamInfo()
        }
        dao.upsert(
            seedEpisode(11, EpisodeDownloadEntity.STATE_FAILED).copy(
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(11)),
            ),
        )
        val repo = repositoryWith(resolver)

        val job = launch { repo.reEnqueue(episodeId(11)) }
        resolverStarted.await()
        assertEquals(
            "the row is RESOLVING while the resolution runs",
            EpisodeDownloadEntity.STATE_RESOLVING,
            dao.getById(episodeId(11))?.state,
        )

        // The Downloads-screen Cancel action lands DURING resolution.
        repo.cancel(episodeId(11))
        releaseResolver.complete(Unit)
        job.join()

        // Cancellation wins: no stream persisted as success, no work queued,
        // the session is CANCELLED — never stuck in RESOLVING.
        val row = dao.getById(episodeId(11))
        assertEquals(EpisodeDownloadEntity.STATE_CANCELLED, row?.state)
        assertNull(row?.m3u8Url)
        assertNull(row?.userAgent)
        assertEquals(playUrl(11), row?.playUrl)
        assertNoWorker(11)
    }

    @Test
    fun coroutine_cancellation_restores_the_original_terminal_state() = runBlocking {
        val dao = db.episodeDownloadDao()
        val resolverStarted = CompletableDeferred<Unit>()
        val neverEnds = CompletableDeferred<Unit>()
        val resolver = RecordStreamResolver {
            resolverStarted.complete(Unit)
            neverEnds.await() // suspends until the JOB is cancelled
            RetryTestStream.streamInfo()
        }
        dao.upsert(
            seedEpisode(12, EpisodeDownloadEntity.STATE_FAILED).copy(
                errorMessage = "original failure",
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(12)),
            ),
        )

        val job = launch { repositoryWith(resolver).reEnqueue(episodeId(12)) }
        resolverStarted.await()
        assertEquals(
            EpisodeDownloadEntity.STATE_RESOLVING,
            dao.getById(episodeId(12))?.state,
        )

        // Scope teardown (e.g. the ViewModel goes away) mid-resolution.
        job.cancelAndJoin()

        // Never a stuck RESOLVING row: the original terminal state is restored,
        // no stream/work is produced, and the row stays retryable.
        val row = dao.getById(episodeId(12))
        assertEquals(
            "cancellation must restore the pre-retry terminal state",
            EpisodeDownloadEntity.STATE_FAILED,
            row?.state,
        )
        assertNull("the stale reason died with the retry claim", row?.errorMessage)
        assertNull(row?.m3u8Url)
        assertEquals(1, row?.retryCount)
        assertEquals(playUrl(12), row?.playUrl)
        assertNoWorker(12)
    }

    // ── Section 8: retry from persisted state via a fresh repository ─────────

    @Test
    fun retry_works_from_a_fresh_repository_over_persisted_state_only() = runBlocking {
        // NOT an actual OS process death: this pins the
        // Section 8 contract — a retryable session persisted as FAILED with a
        // valid playUrl and NO usable StreamInfo is fully retried by a NEW
        // repository instance over Room state alone.
        val dao = db.episodeDownloadDao()
        dao.upsert(
            seedEpisode(13, EpisodeDownloadEntity.STATE_FAILED).copy(
                m3u8Url = null,
                userAgent = null,
                referer = null,
                errorMessage = "network down",
                retryCount = 4,
                completedSegments = 6,
                totalSegments = 12,
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(13)),
            ),
        )

        // Fresh lifecycle: nothing carried over from any previous instance.
        val freshResolver = RecordStreamResolver { RetryTestStream.streamInfo() }
        val freshRepository = DownloadRepository(
            context,
            dao,
            db.downloadSessionDao(),
            streamResolver = freshResolver,
        )
        freshRepository.reEnqueue(episodeId(13))

        assertEquals(1, freshResolver.calls.size)
        assertEquals(playUrl(13), freshResolver.calls[0].playUrl)
        val row = dao.getById(episodeId(13))
        assertEquals(EpisodeDownloadEntity.STATE_QUEUED, row?.state)
        assertEquals(RetryTestStream.M3U8_URL, row?.m3u8Url)
        assertEquals(playUrl(13), row?.playUrl)
        assertEquals(5, row?.retryCount)
        assertEquals(6, row?.completedSegments)
        assertEquals(12, row?.totalSegments)
        assertEquals(1, workInfos(13).size)
    }

    // ── Reviewer-driven guards ───────────────────────────────────────────────

    @Test
    fun retry_refuses_a_row_whose_key_does_not_match_its_columns() = runBlocking {
        // B-1: queueResolvedEpisode re-derives `<session>_<epStr>_<audio>`.
        // A hand-seeded/legacy row whose id does not match its own columns
        // must abort BEFORE any mutation — otherwise it strands itself in
        // RESOLVING and a phantom row appears under the derived key.
        val dao = db.episodeDownloadDao()
        val legacyId = "legacy-${sessionId}-ep15"
        dao.upsert(
            seedEpisode(15, EpisodeDownloadEntity.STATE_FAILED).copy(
                episodeId = legacyId,
                retryCount = 3,
                errorMessage = "legacy error",
                m3u8Url = "https://cdn.example.com/legacy.m3u8",
                segmentDir = Phase10TestSupport.segmentDirFor(context, legacyId),
            ),
        )
        val resolver = RecordStreamResolver { RetryTestStream.streamInfo() }

        repositoryWith(resolver).reEnqueue(legacyId)

        assertEquals("a non-conforming row must never trigger resolution", 0, resolver.calls.size)
        val row = dao.getById(legacyId)
        assertEquals(EpisodeDownloadEntity.STATE_FAILED, row?.state)
        assertEquals(3, row?.retryCount)
        assertEquals("legacy error", row?.errorMessage)
        assertEquals("https://cdn.example.com/legacy.m3u8", row?.m3u8Url)
        assertNull("no phantom row under the derived key", dao.getById(episodeId(15)))
        workManager.getWorkInfosForUniqueWork("episode_download_$legacyId").get().let { infos ->
            assertTrue("no work may be enqueued", infos.isEmpty())
        }
    }

    @Test
    fun retry_without_a_wired_resolver_mutates_nothing() = runBlocking {
        // B-2: production always wires the AppContainer resolver; a
        // host-constructed repository without one must be a logged no-op —
        // never the legacy broken path (clear + enqueue → "No stream info").
        val dao = db.episodeDownloadDao()
        dao.upsert(
            seedEpisode(16, EpisodeDownloadEntity.STATE_FAILED).copy(
                m3u8Url = "https://cdn.example.com/still-here.m3u8",
                userAgent = "StillHereUA",
                errorMessage = "old failure",
                retryCount = 2,
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(16)),
            ),
        )

        repositoryWith(null).reEnqueue(episodeId(16))

        val row = dao.getById(episodeId(16))
        assertEquals(EpisodeDownloadEntity.STATE_FAILED, row?.state)
        assertEquals("https://cdn.example.com/still-here.m3u8", row?.m3u8Url)
        assertEquals("StillHereUA", row?.userAgent)
        assertEquals("old failure", row?.errorMessage)
        assertEquals(2, row?.retryCount)
        assertEquals(playUrl(16), row?.playUrl)
        assertNoWorker(16)
    }

    @Test
    fun abort_restores_terminal_state_when_reconciler_flips_row_mid_resolution() = runBlocking {
        // S-5: the startup reconciler's RESOLVING → QUEUED + clearStreamInfo
        // (DownloadStateReconciler) may collide with an in-flight retry. The
        // retry must restore the retryable terminal state instead of
        // abandoning a "queued" row that will never run.
        val dao = db.episodeDownloadDao()
        val resolverStarted = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        val resolver = RecordStreamResolver {
            resolverStarted.complete(Unit)
            releaseResolver.await()
            RetryTestStream.streamInfo()
        }
        dao.upsert(
            seedEpisode(17, EpisodeDownloadEntity.STATE_FAILED).copy(
                errorMessage = "prior failure",
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(17)),
            ),
        )

        val job = launch { repositoryWith(resolver).reEnqueue(episodeId(17)) }
        resolverStarted.await()
        assertEquals(
            EpisodeDownloadEntity.STATE_RESOLVING,
            dao.getById(episodeId(17))?.state,
        )

        // Simulate exactly what DownloadStateReconciler does to a stranded
        // RESOLVING row at startup.
        dao.updateStateForResume(
            episodeId(17),
            EpisodeDownloadEntity.STATE_RESOLVING,
            EpisodeDownloadEntity.STATE_QUEUED,
        )
        dao.clearStreamInfo(episodeId(17))

        releaseResolver.complete(Unit)
        job.join()

        val row = dao.getById(episodeId(17))
        assertEquals(
            "the abort must restore the pre-retry terminal state",
            EpisodeDownloadEntity.STATE_FAILED,
            row?.state,
        )
        assertNull("the stale reason died with the retry claim", row?.errorMessage)
        assertNull(row?.m3u8Url)
        assertEquals(1, row?.retryCount)
        assertEquals(playUrl(17), row?.playUrl)
        assertNoWorker(17)
    }

    @Test
    fun resolve_complete_after_cancellation_never_queues_work() = runBlocking {
        // S-1: resolveComplete's QUEUED transition is CAS-guarded, so a Cancel
        // landing between resolution and the hand-off can no longer be
        // flipped back to QUEUED with a Worker appended after it.
        val dao = db.episodeDownloadDao()
        dao.upsert(
            seedEpisode(18, EpisodeDownloadEntity.STATE_CANCELLED).copy(
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId(18)),
            ),
        )

        repositoryWith(null).resolveComplete(
            episodeId = episodeId(18),
            m3u8Url = RetryTestStream.M3U8_URL,
            userAgent = RetryTestStream.USER_AGENT,
            referer = RetryTestStream.REFERER,
        )

        val row = dao.getById(episodeId(18))
        assertEquals(
            "a durable cancellation must never be resurrected to QUEUED",
            EpisodeDownloadEntity.STATE_CANCELLED,
            row?.state,
        )
        // Stream info may be persisted on the terminal row (harmless: no Worker
        // reads it, and the next retry claim clears it).
        assertNoWorker(18)
    }

    /** Test-double HlsClient: serves configured responses, counts every call. */
    private class CountingHlsClient(
        private val text: Map<String, String>,
        private val bytes: Map<String, ByteArray>,
    ) : HlsClient {
        private val counts =
            java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())

        fun count(url: String): Int = counts[url] ?: 0

        override suspend fun getText(
            url: String,
            context: HlsClient.RequestContext,
            timeoutMs: Long,
        ): HlsTextResult {
            counts[url] = (counts[url] ?: 0) + 1
            val body = text[url]
                ?: return HlsTextResult.NetworkError("No response configured", url)
            return HlsTextResult.Success(body)
        }

        override suspend fun getBytes(
            url: String,
            context: HlsClient.RequestContext,
            timeoutMs: Long,
        ): HlsBytesResult {
            counts[url] = (counts[url] ?: 0) + 1
            val body = bytes[url]
                ?: return HlsBytesResult.NetworkError("No response configured", url)
            return HlsBytesResult.Success(body)
        }
    }
}
