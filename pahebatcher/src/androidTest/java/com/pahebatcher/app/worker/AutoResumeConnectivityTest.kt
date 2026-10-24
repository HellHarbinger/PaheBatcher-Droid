package com.pahebatcher.app.worker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.hls.SegmentFileStore
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 14 auto-resume device verification (PHASE14_PLAN §17, §20 exit
 * criteria 3 and 6).
 *
 * Two rows, both driving the REAL application WorkManager, the REAL
 * DownloadWorker and the REAL Room database:
 *
 *  1. **Row "Retry bound" (device half)** — `firstTransportFailure_parks...
 *     an unreachable-host fixture makes the first run fail with a pure
 *     TRANSPORT error; with `auto_resume` ON the row must park back in QUEUED
 *     with its request ENQUEUED in backoff (never terminal FAILED).
 *
 *  2. **Row "Auto-resume connectivity interruption" (NEW)** —
 *     `severedTransport_midDownload...` runs a REAL download from an
 *     in-process loopback HLS fixture, severs the transport mid-download
 *     (the fixture refuses every further request after N segment responses),
 *     asserts the row parks QUEUED / request ENQUEUED with already-written
 *     segments intact, restores the transport, and asserts the episode then
 *     finishes **automatically** with **zero re-download** of the segments it
 *     had already fetched.
 *
 * The sever is performed at the fixture origin (the only transport an
 * instrumentation test can control without shell/root privileges on a
 * non-rooted device): every request the downloader makes during the severed
 * window fails at the socket level exactly like a dropped network, and the
 * production path under test — `OkHttpHlsClient` → `NetworkError` →
 * `DownloadErrorClass.TRANSPORT` → `shouldAutoResume` → CAS park →
 * `Result.retry()` → WorkManager backoff → re-run — is the unmodified
 * production path.
 */
@RunWith(AndroidJUnit4::class)
class AutoResumeConnectivityTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-autoresume-$runStamp"
    private val episodeId = "ep-autoresume-$runStamp"
    private val uniqueName = "episode_download_$episodeId"
    private val genDirPath = "autoresume_gen_$runStamp"

    private var fixture: LoopbackHlsFixture? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)

        // Drain OTHER sessions' non-terminal rows (same guard as
        // TriggerNextDeferredEpisodeTest): this test's every-completion trigger
        // fires after every run, and a stale QUEUED+m3u8 row from an earlier
        // suite would otherwise be enqueued by it.
        runBlocking {
            val dao = db.episodeDownloadDao()
            val leftovers = dao.getEpisodesForReconciliation().filter {
                it.sessionId != sessionId &&
                    it.state != EpisodeDownloadEntity.STATE_COMPLETED &&
                    it.state != EpisodeDownloadEntity.STATE_FAILED &&
                    it.state != EpisodeDownloadEntity.STATE_CANCELLED
            }
            for (leftover in leftovers) {
                dao.delete(leftover.episodeId)
            }
        }
    }

    @After
    fun teardown() {
        workManager.cancelUniqueWork(uniqueName)
        // Always restore the shipped default — the auto-resume variants flip it.
        runBlocking { AppConfig(context).updateAutoResume(false) }
        fixture?.stop()
        fixture = null

        // Remove the exported MediaStore output before the row is cascaded away.
        runBlocking {
            val row = db.episodeDownloadDao().getById(episodeId)
            val outputUri = row?.outputUri
            if (outputUri != null) {
                try {
                    context.contentResolver.delete(Uri.parse(outputUri), null, null)
                } catch (_: Exception) {
                    // Best effort — the row is gone either way.
                }
            }
        }
        Phase10TestSupport.deleteSession(db, sessionId)
        File(context.filesDir, "downloads/$episodeId").deleteRecursively()
        File(context.cacheDir, genDirPath).deleteRecursively()
    }

    /**
     * PHASE14_PLAN §17, "Retry bound" (device half): the FIRST transport
     * failure of a run parks the row instead of terminating it.
     *
     * `FAST_FAIL_URL` is an unreachable loopback endpoint, so the failure is a
     * real network error (`OkHttpHlsClient` → `NetworkError` →
     * `DownloadErrorClass.TRANSPORT`). With `auto_resume` ON the worker must
     * CAS the row DOWNLOADING → QUEUED and return `Result.retry()`, which
     * WorkManager reports as ENQUEUED (backoff) — not FAILED, not finished.
     */
    @Test
    fun firstTransportFailure_parksQueuedAndKeepsRequestEnqueued() {
        runBlocking {
            AppConfig(context).updateAutoResume(true)
            try {
                val dao = db.episodeDownloadDao()
                dao.upsert(
                    seededEpisode(
                        m3u8Url = Phase10TestSupport.FAST_FAIL_URL,
                        createdAt = System.currentTimeMillis()
                    )
                )

                repository.enqueueWorker(episodeId)

                val parked = Phase10TestSupport.awaitUntil(120_000) {
                    Phase10TestSupport.isParkedForAutoResume(db, workManager, episodeId)
                }
                val row = dao.getById(episodeId)
                val work = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                assertTrue(
                    "The first transport failure must park the row in QUEUED with its " +
                        "request ENQUEUED in backoff (row=${row?.state}, work=${work.map { it.state }})",
                    parked
                )
                assertEquals(
                    "A transport failure with auto-resume ON must never be terminal FAILED",
                    EpisodeDownloadEntity.STATE_QUEUED,
                    row?.state
                )
                val info = work.single()
                assertFalse("The parked request must still be pending", info.state.isFinished)
                // Review L-2: pin the FIRST attempt explicitly (as row 419's test
                // does), not just the bound — the plan's device contract is
                // "FIRST-retry parks QUEUED". The poll can only see this state
                // after exactly one executed run, but assert it anyway so a
                // future change that consumes attempts before parking fails here.
                assertEquals(
                    "The park must come from the first executed run",
                    1,
                    info.runAttemptCount
                )
                assertTrue(
                    "The parked request must still hold auto-resume budget " +
                        "(attempt ${info.runAttemptCount} of ${DownloadWorker.AUTO_RESUME_MAX_RETRIES})",
                    info.runAttemptCount < DownloadWorker.AUTO_RESUME_MAX_RETRIES
                )
            } finally {
                AppConfig(context).updateAutoResume(false)
            }
        }
    }

    /**
     * PHASE14_PLAN §17 row "Auto-resume connectivity interruption" / §20 exit
     * criterion 3 — the PARITY `:604` "To reach ✅" criterion.
     *
     * 1. a real download runs against the loopback fixture;
     * 2. after [LOOPBACK_SERVE_BUDGET] segment responses the fixture refuses
     *    every further request — the transport is severed mid-download;
     * 3. the row must park QUEUED (not FAILED) with its request ENQUEUED, and
     *    the already-written segments must be intact on disk;
     * 4. the transport is restored;
     * 5. without any further test action the episode must finish on its own
     *    (COMPLETED + work SUCCEEDED) having re-downloaded **nothing**.
     */
    @Test
    fun severedTransport_midDownloadParksThenAutoCompletesWithZeroRedownload() {
        val genDir = File(context.cacheDir, genDirPath).apply { mkdirs() }
        val segmentCount = LOOPBACK_SEGMENT_COUNT
        val segmentBodies = generateSegmentBodies(genDir)
        val playlist = buildPlaylist(segmentCount)
        val loopback = LoopbackHlsFixture(playlist, segmentBodies, LOOPBACK_SERVE_BUDGET)
        fixture = loopback
        loopback.start()

        runBlocking {
            AppConfig(context).updateAutoResume(true)
            try {
                val dao = db.episodeDownloadDao()
                val segmentDir = File(Phase10TestSupport.segmentDirFor(context, episodeId))
                val segmentStore = SegmentFileStore(segmentDir)

                dao.upsert(
                    seededEpisode(
                        m3u8Url = loopback.playlistUrl,
                        createdAt = System.currentTimeMillis()
                    )
                )

                repository.enqueueWorker(episodeId)

                // ── 3. park ────────────────────────────────────────────────
                val parked = Phase10TestSupport.awaitUntil(180_000) {
                    Phase10TestSupport.isParkedForAutoResume(db, workManager, episodeId)
                }
                val row = dao.getById(episodeId)
                val parkedWork = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                assertTrue(
                    "A severed transport mid-download must park the row in QUEUED with its " +
                        "request ENQUEUED (row=${row?.state}, work=${parkedWork.map { it.state }}, " +
                        "servedSegments=${loopback.servedSegmentResponses})",
                    parked
                )
                assertEquals(
                    "The park must not be terminal FAILED",
                    EpisodeDownloadEntity.STATE_QUEUED,
                    row?.state
                )
                assertFalse(
                    "The parked request must not be finished",
                    parkedWork.single().state.isFinished
                )
                assertEquals(
                    "The park must be produced by the FIRST retry (run 1 of the bound)",
                    1,
                    parkedWork.single().runAttemptCount
                )
                assertTrue(
                    "The transport must really have been severed mid-download " +
                        "(served=${loopback.servedSegmentResponses}, budget=$LOOPBACK_SERVE_BUDGET)",
                    loopback.servedSegmentResponses == LOOPBACK_SERVE_BUDGET
                )
                assertTrue(
                    "Segments downloaded before the sever must be persisted on disk",
                    segmentStore.doneIndices().size >= LOOPBACK_SERVE_BUDGET
                )
                assertTrue(
                    "The fixture must be refusing requests while severed",
                    loopback.isSevered
                )

                // ── 4. restore ─────────────────────────────────────────────
                loopback.restore()

                // ── 5. automatic completion, zero re-download ──────────────
                val completed = Phase10TestSupport.awaitUntil(300_000) {
                    rowIs(EpisodeDownloadEntity.STATE_COMPLETED)
                }
                // The Room row flips to COMPLETED a beat BEFORE WorkManager
                // finalizes the request (row 16:21:35.544 → export .642 → work
                // settles after that), so a single immediate read races that
                // window. Await the work's final state like every other wait
                // here; the assertions below are unchanged — a FAILED or
                // never-settling work still fails them honestly.
                val workSettled = Phase10TestSupport.awaitUntil(60_000) {
                    workManager.getWorkInfosForUniqueWork(uniqueName).get()
                        .singleOrNull()
                        ?.state
                        ?.isFinished == true
                }
                assertTrue(
                    "The parked request must reach a final state (row=" +
                        "${dao.getById(episodeId)?.state}, work=" +
                        workManager.getWorkInfosForUniqueWork(uniqueName)
                            .get().map { it.state } + ")",
                    workSettled,
                )
                val finalRow = dao.getById(episodeId)
                val finalWork = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                assertTrue(
                    "After the transport returns the episode must complete on its own " +
                        "(row=${finalRow?.state}, work=${finalWork.map { it.state }}, " +
                        "errorMessage=${finalRow?.errorMessage})",
                    completed
                )
                assertEquals(
                    "The parked request must succeed without any test intervention",
                    setOf(WorkInfo.State.SUCCEEDED),
                    finalWork.map { it.state }.toSet()
                )
                assertFalse(
                    "A completed episode must carry an output path",
                    finalRow?.outputPath.isNullOrEmpty()
                )

                // Zero re-download: every segment was served EXACTLY once across
                // the severed run and the resuming run.
                for (index in 0 until segmentCount) {
                    val count = loopback.serveCount("/seg$index.ts")
                    assertEquals(
                        "Segment seg$index.ts must not be downloaded twice",
                        1,
                        count
                    )
                }
                assertEquals(
                    "Every segment must have been downloaded exactly once",
                    segmentCount,
                    loopback.servedSegmentResponses
                )
            } finally {
                AppConfig(context).updateAutoResume(false)
            }
        }
    }

    /** True when this episode's Room row has reached [state]. */
    private fun rowIs(state: String): Boolean = runBlocking {
        db.episodeDownloadDao().getById(episodeId)?.state == state
    }

    /** QUEUED episode with a resolved stream, ready for the worker pipeline. */
    private fun seededEpisode(m3u8Url: String, createdAt: Long) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Auto Resume Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = EpisodeDownloadEntity.STATE_QUEUED,
        createdAt = createdAt,
        updatedAt = createdAt,
        m3u8Url = m3u8Url,
        userAgent = "AutoResumeTestAgent",
        referer = "https://example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId)
    )

    /** Valid MPEG-TS bytes generated on-device (the proven FFmpegKit recipe). */
    private fun generateTs(color: String, out: File): Boolean {
        val cmd = "-y -f lavfi -i color=c=$color:s=320x240:d=1:r=25 " +
            "-c:v libopenh264 -pix_fmt yuv420p " +
            "-f mpegts ${out.absolutePath}"
        return ReturnCode.isSuccess(FFmpegKit.execute(cmd).returnCode)
    }

    /** Two distinct valid MPEG-TS bodies, generated on-device (proven FFmpegKit recipe). */
    private fun generateSegmentBodies(genDir: File): List<ByteArray> {
        val first = File(genDir, "gen_a.ts")
        val second = File(genDir, "gen_b.ts")
        assertTrue("TS generation A must succeed", generateTs("red", first))
        assertTrue("TS generation B must succeed", generateTs("blue", second))
        val firstBytes = first.readBytes()
        val secondBytes = second.readBytes()
        assertTrue("Generated TS A must be non-empty", firstBytes.isNotEmpty())
        assertTrue("Generated TS B must be non-empty", secondBytes.isNotEmpty())
        return listOf(firstBytes, secondBytes)
    }

    /** Media playlist with relative segment URIs (same shape as FullDownloadFlowTest). */
    private fun buildPlaylist(segmentCount: Int): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:1")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        for (index in 0 until segmentCount) {
            appendLine("#EXTINF:1.0,")
            appendLine("seg$index.ts")
        }
        appendLine("#EXT-X-ENDLIST")
    }

    /**
     * In-process loopback HLS origin with a controllable transport.
     *
     * Behaviour:
     *  - phase SERVING: serves the playlist and the first
     *    [serveBudget] segment responses, then severs the transport;
     *  - phase SEVERED: accepts and closes every connection without a
     *    response, so every downloader request fails at the socket level;
     *  - phase RESTORED: serves everything again (no budget).
     *
     * Every response carries `Connection: close`, so OkHttp never reuses a
     * connection across the sever boundary. Successful responses are counted
     * only after their bytes are fully written — that count is the
     * zero-re-download evidence.
     */
    private class LoopbackHlsFixture(
        private val playlist: String,
        private val segmentBodies: List<ByteArray>,
        private val serveBudget: Int,
    ) {
        private enum class Phase { SERVING, SEVERED, RESTORED }

        private var server: ServerSocket? = null
        @Volatile private var running = true
        @Volatile private var phase = Phase.SERVING
        private val segmentAccepts = AtomicInteger(0)
        private val segmentServes = ConcurrentHashMap<String, Int>()

        val isSevered: Boolean get() = phase == Phase.SEVERED
        val servedSegmentResponses: Int get() = segmentServes.values.sum()

        val playlistUrl: String
            get() = "http://127.0.0.1:${server?.localPort ?: error("fixture not started")}/playlist.m3u8"

        fun serveCount(path: String): Int = segmentServes[path] ?: 0

        fun restore() {
            phase = Phase.RESTORED
        }

        fun stop() {
            running = false
            try {
                server?.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }

        fun start() {
            val socket = ServerSocket(0)
            server = socket
            Thread({
                while (running) {
                    val accepted = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread { handle(accepted) }.apply { isDaemon = true }.start()
                }
            }, "loopback-hls-fixture").apply {
                isDaemon = true
                start()
            }
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 10_000
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val requestLine = input.readLine() ?: return
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isEmpty()) break
                }

                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: return
                val isSegment = path.endsWith(".ts")

                when (phase) {
                    Phase.SEVERED -> return // severed: no response at all
                    Phase.SERVING -> {
                        if (isSegment) {
                            val accepted = segmentAccepts.incrementAndGet()
                            if (accepted > serveBudget) {
                                phase = Phase.SEVERED
                                return
                            }
                        }
                    }
                    Phase.RESTORED -> Unit // serve everything
                }

                val body: ByteArray
                val contentType: String
                when {
                    path.endsWith("playlist.m3u8") -> {
                        body = playlist.toByteArray(Charsets.UTF_8)
                        contentType = "application/vnd.apple.mpegurl"
                    }
                    isSegment -> {
                        val index = path.removeSuffix(".ts")
                            .substringAfterLast("seg")
                            .toIntOrNull() ?: return
                        body = segmentBodies[index % segmentBodies.size]
                        contentType = "video/mp2t"
                    }
                    else -> return // unknown path — no response
                }

                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val output = socket.getOutputStream()
                output.write(header.toByteArray(Charsets.US_ASCII))
                output.write(body)
                output.flush()

                if (isSegment) {
                    segmentServes.merge(path, 1) { old, new -> old + new }
                }
            } catch (_: Exception) {
                // Socket-level failure while severed is the expected transport error.
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Already closed.
                }
            }
        }
    }

    private companion object {
        /** Segments served before the transport is severed mid-download. */
        const val LOOPBACK_SERVE_BUDGET = 2

        /** Total segments in the fixture playlist. */
        const val LOOPBACK_SEGMENT_COUNT = 6
    }
}
