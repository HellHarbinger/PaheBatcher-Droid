package com.pahebatcher.app.worker

import android.content.Context
import android.app.NotificationManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.data.download.DownloadConfig
import com.pahebatcher.app.data.export.MediaStoreExporter
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.support.Phase10TestSupport
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pause/resume run-ownership race fix — the deterministic, barrier-driven
 * worker-level proofs of the 2026-10-22 device failures (SPEC §15 row-state
 * lifecycle, DECISIONS pending).
 *
 * The defect: a pause cancels the unique work, but the cancelled run's
 * `EpisodeDownloader` drains its in-flight blocking OkHttp segment fetches
 * (DL-004) before `runWork`'s CancellationException handler fires the
 * `DOWNLOADING → CANCELLED` terminal write. If the user resumes within that
 * drain window, the NEW run re-claims the row as `DOWNLOADING`, and the OLD
 * run's state-only CAS then stole it — erasing the resumed run's state
 * (observed on device as the resumed run losing its ASSEMBLING claim and its
 * completion).
 *
 * The fix under test: every run-owned write is a single atomic UPDATE guarded
 * by BOTH the expected state AND the persisted run token
 * (`episode_downloads.downloadRunId`, stamped by the run's own entry claim).
 *
 * Determinism (no sleeps, no timing guesses): [RunOwnershipBarrierHlsSource]
 * blocks every segment fetch of Run A on a [CountDownLatch] the test releases
 * explicitly. The test waits until A's fetches are verifiably in flight
 * (a started-counter barrier), pauses, cancels A, resumes, lets Run B claim,
 * and only THEN opens the barrier — so A's drained cancellation write provably
 * fires inside Run B's ownership window every single run.
 *
 * Everything below the fake HLS source is REAL:
 * - real DownloadWorker instances (claim, progress, terminal writes)
 * - REAL Room database (PaheDatabase singleton — the device's migrated v5 DB)
 * - real SegmentFileStore writes and REAL FFmpeg assembly of on-device
 *   generated MPEG-TS segments
 * - Run B exports through the REAL [MediaStoreExporter]
 *
 * Test doubles live here only — never in production.
 */
@RunWith(AndroidJUnit4::class)
class RunOwnershipDeviceTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-run-ownership-$runStamp"
    private val episodeId = "ep-run-ownership-$runStamp"
    private val playlistUrl = "https://cdn.local.test/run-ownership/playlist.m3u8"
    private val baseUrl = "https://cdn.local.test/run-ownership/"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)
        File(Phase10TestSupport.segmentDirFor(context, episodeId)).deleteRecursively()
    }

    @After
    fun teardown() {
        // Remove any exported MediaStore file this test produced.
        val outputUri = runBlocking { db.episodeDownloadDao().getById(episodeId)?.outputUri }
        if (outputUri != null) {
            try {
                context.contentResolver.delete(Uri.parse(outputUri), null, null)
            } catch (_: Exception) {
                // best-effort cleanup
            }
        }
        runBlocking { db.episodeDownloadDao().delete(episodeId) }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(DownloadNotificationManager(context).terminalIdFor(episodeId))
        Phase10TestSupport.deleteSession(db, sessionId)
        File(Phase10TestSupport.segmentDirFor(context, episodeId)).deleteRecursively()
    }

    /** Generate one valid MPEG-TS segment on-device (proven FFmpegKit command). */
    private fun generateValidTs(out: File): Boolean {
        val cmd = "-y -f lavfi -i color=c=red:s=320x240:d=1:r=25 " +
            "-c:v libopenh264 -pix_fmt yuv420p " +
            "-f mpegts ${out.absolutePath}"
        val session = FFmpegKit.execute(cmd)
        val ok = ReturnCode.isSuccess(session.returnCode)
        if (!ok) {
            android.util.Log.e("RunOwnershipDeviceTest", "TS generation failed: ${session.output}")
        }
        return ok
    }

    /** QUEUED episode with a resolved stream — the production entry state. */
    private fun seed(state: String) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Run Ownership Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://animepahe.com/play/$sessionId/eps-$episodeId",
        state = state,
        retryCount = 0,
        errorMessage = null,
        createdAt = runStamp,
        updatedAt = runStamp,
        m3u8Url = playlistUrl,
        userAgent = "RunOwnershipUA",
        referer = "https://example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId),
    )

    private fun buildPlaylist() = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:1
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:1.0,
        seg0.ts
        #EXTINF:1.0,
        seg1.ts
        #EXTINF:1.0,
        seg2.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    /**
     * THE race, end to end on the real worker pipeline:
     *
     * Run A claims the row and blocks mid-download in the barrier; the user
     * pauses and cancels; the user resumes and Run B re-claims the row; the
     * barrier is then opened so Run A's drained cancellation cleanup fires its
     * `DOWNLOADING → CANCELLED` write inside Run B's ownership window.
     *
     * Must hold:
     * 1. the stale write is ATTEMPTED (recorded) and REFUSED (0 rows) — never
     *    a vacuous pass on an untouched row;
     * 2. Run B's row is never CANCELLED and keeps Run B's token;
     * 3. Run B still finishes the full pipeline: ASSEMBLING → COMPLETED with a
     *    real muxed MP4 and a REAL MediaStore export (outputUri).
     */
    @Test
    fun stale_run_a_cancellation_cannot_overwrite_run_b_and_run_b_finishes() = runBlocking {
        val dao = db.episodeDownloadDao()

        // ── 1. Real TS bytes on-device (B's mux is REAL FFmpeg) ──────────────
        val segmentBytes = generateSegmentBytes()

        // ── 2. Seed QUEUED + start Run A behind the barrier ──────────────────
        dao.upsert(seed(EpisodeDownloadEntity.STATE_QUEUED))
        val releaseA = CountDownLatch(1)
        val fetchesStarted = AtomicInteger(0)
        val recorder = OwnershipRecordingDao(dao)
        val workerA = buildWorker(
            dao = recorder,
            hlsClient = RunOwnershipBarrierHlsSource(
                playlistUrl, buildPlaylist(), segmentBytes, releaseA, fetchesStarted,
            ),
        )

        val outcomeA = AtomicReference<Any?>()
        val jobA = launch(Dispatchers.Default) {
            outcomeA.set(
                try {
                    workerA.doWork()
                } catch (t: Throwable) {
                    t
                },
            )
        }

        assertTrue(
            "Run A must reach DOWNLOADING",
            Phase10TestSupport.awaitUntil(15_000) {
                kotlinx.coroutines.runBlocking {
                    dao.getById(episodeId)?.state == EpisodeDownloadEntity.STATE_DOWNLOADING
                }
            },
        )
        val tokenA = dao.getById(episodeId)?.downloadRunId
        assertNotNull("Run A's claim must stamp its run token", tokenA)

        // The drain barrier: every segment fetch of Run A is verifiably in
        // flight — the exact moment a real pause+cancel lands mid-download.
        assertTrue(
            "Run A must really be mid-download with all segment fetches in flight",
            Phase10TestSupport.awaitUntil(15_000) { fetchesStarted.get() >= 3 },
        )

        // ── 3. Pause + cancel Run A, resume, start Run B ─────────────────────
        // The pause/resume writes below are byte-identical to the durable
        // statements DownloadRepository.pause()/resume() issue
        // (`updateStateForResume`, state-only CAS — user actions are newer than
        // any run and never carry a token). The resume's WorkManager enqueue is
        // replaced by starting Run B's worker directly below: Run B IS the new
        // run, and a real WorkManager enqueue would race an unrelated real
        // worker against this seam-driven scenario.
        assertEquals(
            "the pause must persist PAUSED exactly like DownloadRepository.pause()",
            1,
            dao.updateStateForResume(
                episodeId, EpisodeDownloadEntity.STATE_DOWNLOADING, EpisodeDownloadEntity.STATE_PAUSED,
            ),
        )
        jobA.cancel() // NOT join: Run A now drains in the barrier (DL-004)

        assertEquals(
            "the resume must persist QUEUED exactly like DownloadRepository.resume()",
            1,
            dao.updateStateForResume(
                episodeId, EpisodeDownloadEntity.STATE_PAUSED, EpisodeDownloadEntity.STATE_QUEUED,
            ),
        )

        // Run B: REAL DAO, barrier-free HLS source, REAL MediaStore export.
        val workerB = buildWorker(
            dao = dao,
            hlsClient = RunOwnershipBarrierHlsSource(playlistUrl, buildPlaylist(), segmentBytes),
            exporter = MediaStoreExporter(
                context.contentResolver, db.episodeDownloadDao(), db.downloadSessionDao(),
            ),
        )
        val futureB = workerB.startWork()

        // Wait on the TOKEN, not the state. Run B's DOWNLOADING window is
        // short with an in-process source — it can move through ASSEMBLING to
        // COMPLETED between two 250 ms polls (observed on device: ~44 ms) —
        // while the claim token is stamped the instant Run B claims and is
        // never cleared, so it persists across ASSEMBLING/COMPLETED. This
        // barrier therefore fires deterministically on Run B's claim and stays
        // true afterwards, and the token can only become non-tokenA through a
        // real re-claim by Run B.
        assertTrue(
            "Run B must re-claim the row with a fresh run token of its own",
            Phase10TestSupport.awaitUntil(15_000) {
                kotlinx.coroutines.runBlocking {
                    val row = dao.getById(episodeId)
                    row?.downloadRunId != null && row.downloadRunId != tokenA
                }
            },
        )
        val tokenB = dao.getById(episodeId)?.downloadRunId
        assertNotNull("Run B's claim must stamp its own run token", tokenB)
        assertNotEquals(
            "Run B must OWN the row with a fresh token — the claim overwrites Run A's",
            tokenA,
            tokenB,
        )

        // ── 4. Open the barrier: Run A's drained cancellation write fires NOW
        //        — inside Run B's ownership window (the 2026-10-22 race). ─────
        releaseA.countDown()
        jobA.join()

        assertTrue(
            "Run A's outcome must be a propagated CancellationException, got: ${outcomeA.get()}",
            outcomeA.get() is CancellationException,
        )

        // The stale write was ATTEMPTED by Run A and REFUSED by the atomic
        // state+ownership guard (anti-vacuous, reviewer m1).
        assertTrue(
            "Run A's stale DOWNLOADING→CANCELLED write must have been ATTEMPTED " +
                "and REFUSED (0 rows), got: ${recorder.writes}",
            recorder.writes.any {
                it.contains("DOWNLOADING->${EpisodeDownloadEntity.STATE_CANCELLED}") &&
                    it.contains("rows=0") &&
                    it.contains("runId=$tokenA")
            },
        )

        val midRow = dao.getById(episodeId)
        assertNotNull(midRow)
        assertTrue(
            "Run B's row must NEVER become CANCELLED by the stale write " +
                "(state=${midRow!!.state})",
            midRow.state != EpisodeDownloadEntity.STATE_CANCELLED,
        )
        assertEquals(
            "Run B must still own the row after the stale write lost",
            tokenB,
            midRow.downloadRunId,
        )

        // ── 5. Run B completes the whole pipeline: assembly + completion +
        //        REAL MediaStore export ────────────────────────────────────────
        val bResult = futureB.get(120, TimeUnit.SECONDS)
        assertEquals(
            "Run B must finish with a success Result",
            ListenableWorker.Result.success(),
            bResult,
        )

        val finalRow = dao.getById(episodeId)
        assertNotNull(finalRow)
        assertEquals(
            "Run B must end COMPLETED",
            EpisodeDownloadEntity.STATE_COMPLETED,
            finalRow!!.state,
        )
        assertEquals(
            "the completed row must still carry Run B's token",
            tokenB,
            finalRow.downloadRunId,
        )
        val outputPath = finalRow.outputPath
        assertTrue("Run B must record its outputPath", !outputPath.isNullOrBlank())

        // The REAL exporter's verified protocol is a MOVE: stream-copy → size
        // verify → persist outputUri → REMOVE the local duplicate
        // (MediaStoreExporter: never delete an UNVERIFIED local copy — a
        // verified one is deduplicated by design). The authoritative artifact
        // is therefore the MediaStore copy at outputUri: it must resolve, be
        // non-empty, and carry a real MP4 header — proving Run B's mux
        // produced genuine output that survived the stale run's drain and
        // reached the user's library.
        val uriString = finalRow.outputUri
        assertNotNull("Run B's export must have produced a MediaStore outputUri", uriString)
        val exportedStream = context.contentResolver.openInputStream(Uri.parse(uriString!!))
        assertNotNull("the exported MediaStore item must be openable", exportedStream)
        val header = ByteArray(8)
        exportedStream!!.use { stream ->
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            assertEquals("the exported copy must carry a full MP4 box header", header.size, read)
        }
        assertEquals("the exported copy must be a real MP4 (ftyp box)", "ftyp", String(header, 4, 4))
        assertTrue("a finished run reports no rate", finalRow.speed == 0L)
        assertTrue("a finished run reports no countdown", finalRow.etaSeconds == 0L)
    }

    /**
     * Control case for the guard: a genuine cancel of the CURRENT run — no
     * pause, no resume, no newer run — must still persist `CANCELLED` with the
     * owning run's token. The fix must never block real cancellation
     * semantics; only STALE runs lose.
     */
    @Test
    fun genuine_cancel_of_the_current_run_still_persists_cancelled() = runBlocking {
        val dao = db.episodeDownloadDao()

        val segmentBytes = generateSegmentBytes()
        dao.upsert(seed(EpisodeDownloadEntity.STATE_QUEUED))

        val release = CountDownLatch(1)
        val fetchesStarted = AtomicInteger(0)
        val recorder = OwnershipRecordingDao(dao)
        val worker = buildWorker(
            dao = recorder,
            hlsClient = RunOwnershipBarrierHlsSource(
                playlistUrl, buildPlaylist(), segmentBytes, release, fetchesStarted,
            ),
        )

        val outcome = AtomicReference<Any?>()
        val job = launch(Dispatchers.Default) {
            outcome.set(
                try {
                    worker.doWork()
                } catch (t: Throwable) {
                    t
                },
            )
        }

        assertTrue(
            "the run must reach DOWNLOADING",
            Phase10TestSupport.awaitUntil(15_000) {
                kotlinx.coroutines.runBlocking {
                    dao.getById(episodeId)?.state == EpisodeDownloadEntity.STATE_DOWNLOADING
                }
            },
        )
        val token = dao.getById(episodeId)?.downloadRunId
        assertNotNull("the claim must stamp the run token", token)
        assertTrue(
            "the run must really be mid-download with all fetches in flight",
            Phase10TestSupport.awaitUntil(15_000) { fetchesStarted.get() >= 3 },
        )

        // Cancel the CURRENT owner (no pause, no resume), then open the drain.
        job.cancel()
        release.countDown()
        job.join()

        assertTrue(
            "doWork must PROPAGATE CancellationException, got: ${outcome.get()}",
            outcome.get() is CancellationException,
        )

        val row = dao.getById(episodeId)
        assertNotNull(row)
        assertEquals(
            "a genuine cancel of the current run must persist CANCELLED",
            EpisodeDownloadEntity.STATE_CANCELLED,
            row!!.state,
        )
        assertEquals(
            "the cancellation write must have landed (rows=1) for the OWNING run, " +
                "got: ${recorder.writes}",
            true,
            recorder.writes.any {
                it.contains("DOWNLOADING->${EpisodeDownloadEntity.STATE_CANCELLED}") &&
                    it.contains("rows=1") &&
                    it.contains("runId=$token")
            },
        )
        assertTrue("a user-style cancel records no error", row.errorMessage == null)
        assertEquals(
            "the token is never cleared on terminal writes — it dies at the next claim",
            token,
            row.downloadRunId,
        )
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private var segmentBytesCache: Map<String, ByteArray> = emptyMap()

    /** Generate the 3 valid TS segments shared by both runs' HLS sources. */
    private fun generateSegmentBytes(): Map<String, ByteArray> {
        if (segmentBytesCache.isNotEmpty()) return segmentBytesCache
        val segmentUrls = (0 until 3).map { "${baseUrl}seg$it.ts" }
        val bytes = mutableMapOf<String, ByteArray>()
        segmentUrls.forEachIndexed { i, url ->
            val ts = File(context.cacheDir, "run_ownership_gen${i}_$runStamp.ts")
            try {
                assertTrue("TS generation $i must succeed", generateValidTs(ts))
                bytes[url] = ts.readBytes()
            } finally {
                ts.delete()
            }
        }
        segmentBytesCache = bytes
        return bytes
    }

    /** Real DownloadWorker with only the HLS source (and DAO/exporter) injected. */
    private fun buildWorker(
        dao: EpisodeDownloadDao,
        hlsClient: HlsClient,
        exporter: com.pahebatcher.app.data.export.EpisodeExporter? = null,
    ): DownloadWorker = TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
        .setInputData(workDataOf("episode_id" to episodeId))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = DownloadWorker(
                context = appContext,
                params = workerParameters,
                dao = dao,
                notificationManager = DownloadNotificationManager(appContext),
                hlsClient = hlsClient,
                keyCache = com.pahebatcher.app.data.hls.AesKeyCache(),
                config = DownloadConfig(),
                exporter = exporter,
            )
        })
        .build()
}

/**
 * Recording seam for the run-owned writes of ONE worker run: it only RECORDS
 * the guarded statements (claim + state transitions) with their rows-affected
 * value and run token, delegating every write unchanged. It never alters any
 * state — the tests assert on the REAL Room row plus this record, so a stale
 * write is proven ATTEMPTED-and-refused rather than inferred from an untouched
 * row (reviewer m1).
 */
private class OwnershipRecordingDao(
    private val delegate: EpisodeDownloadDao,
) : EpisodeDownloadDao by delegate {

    val writes = CopyOnWriteArrayList<String>()

    override suspend fun claimEntryRunWithRunId(
        episodeId: String,
        expectedState: String,
        runId: String,
        timestamp: Long,
    ): Int {
        val rows = delegate.claimEntryRunWithRunId(episodeId, expectedState, runId, timestamp)
        writes.add("claim $expectedState->${EpisodeDownloadEntity.STATE_DOWNLOADING} rows=$rows runId=$runId")
        return rows
    }

    override suspend fun updateStateIfOwned(
        episodeId: String,
        expectedState: String,
        newState: String,
        runId: String,
        timestamp: Long,
    ): Int {
        val rows = delegate.updateStateIfOwned(episodeId, expectedState, newState, runId, timestamp)
        writes.add("updateStateIfOwned $expectedState->$newState rows=$rows runId=$runId")
        return rows
    }
}

/**
 * In-process HLS source with an explicit drain barrier: when [release] is
 * provided, every segment fetch increments [started] and then BLOCKS on the
 * latch (a non-cooperative blocking wait — the exact shape of the real
 * blocking OkHttp segment fetches that created the DL-004 drain), returning
 * the segment bytes only after the test opens the barrier. With [release]
 * null the source serves instantly (Run B's view of the same content).
 * Test-only — never a production implementation.
 */
private class RunOwnershipBarrierHlsSource(
    private val playlistUrl: String,
    private val playlist: String,
    private val segments: Map<String, ByteArray>,
    private val release: CountDownLatch? = null,
    private val started: AtomicInteger? = null,
) : HlsClient {

    override suspend fun getText(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long,
    ): HlsTextResult {
        if (url == playlistUrl) return HlsTextResult.Success(playlist)
        return HlsTextResult.HttpError(404, url)
    }

    override suspend fun getBytes(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long,
    ): HlsBytesResult {
        val bytes = segments[url] ?: return HlsBytesResult.HttpError(404, url)
        val latch = release
        if (latch != null) {
            started?.incrementAndGet()
            // Failsafe bound only — the test's countDown() is the actual release.
            if (!latch.await(60, TimeUnit.SECONDS)) {
                return HlsBytesResult.HttpError(503, url)
            }
        }
        return HlsBytesResult.Success(bytes)
    }
}
