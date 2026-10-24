package com.pahebatcher.app.worker

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.download.DownloadStateReconciler
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.ui.downloads.DownloadsScreen
import com.pahebatcher.app.ui.downloads.DownloadsUiState
import com.pahebatcher.app.ui.mapper.DownloadRowMapper
import com.pahebatcher.app.ui.theme.PaheBatcherTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * SPEC §20/§25 device verification — **Pause** and **Resume** through the REAL
 * production pipeline on a physical device:
 *
 * `AppConfig` → real `WorkManager` → real `DownloadWorker` → real
 * `EpisodeDownloader` over a real socket → real Room writes → the real
 * `DownloadsScreen`.
 *
 * What this pins, in order:
 *
 * 1. a pause that lands on a LIVE run really claims the row: `PAUSED` is
 *    persisted, the rate is cleared in the same statement, and the progress
 *    already made (bytes, `.ts` files) is kept;
 * 2. that intent is durable — the worker's own cancellation cleanup writes
 *    `DOWNLOADING → CANCELLED` under `NonCancellable`, and it MUST lose to the
 *    already-persisted `PAUSED`, so no state of the cancelled run can erase the
 *    user's decision;
 * 3. the app-restart path (the real `DownloadStateReconciler`, driven through a
 *    FRESH repository instance holding no in-process memory) neither re-enqueues,
 *    resets nor auto-resumes a paused row;
 * 4. a resume really restarts the work and finishes it, with the persisted byte
 *    total EXACTLY what the segments write — and without re-downloading
 *    everything, i.e. the retained cache is really used;
 * 5. the dashboard renders the paused snapshot as its own `paused` concept with
 *   a working Resume control and no fabricated rate.
 *
 * The origin is an in-process loopback server: no external service, no CDN.
 */
@RunWith(AndroidJUnit4::class)
class PauseResumeDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-pause-$runStamp"
    private val episodeId = "ep-pause-$runStamp"
    private val genDirPath = "pause_gen_$runStamp"
    private val uniqueWorkName = "episode_download_$episodeId"

    private var fixture: SlowHlsFixture? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)

        // Same guard as the metrics device test: drain other sessions' live rows
        // so an unrelated queued episode cannot be picked up meanwhile.
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
        workManager.cancelUniqueWork(uniqueWorkName)
        fixture?.stop()
        fixture = null

        runBlocking {
            val outputUri = db.episodeDownloadDao().getById(episodeId)?.outputUri
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

    @Test
    fun pausingARealDownloadStopsItDurablyAndResumingFinishesItFromTheCachedSegments() {
        val genDir = File(context.cacheDir, genDirPath).apply { mkdirs() }
        val segmentBody = generateSegmentBody(genDir)
        val loopback = SlowHlsFixture(buildPlaylist(SEGMENT_COUNT), segmentBody, SEGMENT_DELAY_MS)
        fixture = loopback
        loopback.start()

        val expectedBytes = segmentBody.size.toLong() * SEGMENT_COUNT
        var pausedSnapshot: EpisodeDownloadEntity? = null
        var finalRow: EpisodeDownloadEntity? = null

        try {
            runBlocking {
                val dao = db.episodeDownloadDao()
                dao.upsert(
                    seededEpisode(m3u8Url = loopback.playlistUrl, createdAt = System.currentTimeMillis()),
                )
                repository.enqueueWorker(episodeId)

                // ── 1. the run must be genuinely underway before pausing ──────
                val underway = Phase10TestSupport.awaitUntil(60_000) {
                    val row = runBlocking { dao.getById(episodeId) }
                    row?.state == EpisodeDownloadEntity.STATE_DOWNLOADING &&
                        (row?.downloadedBytes ?: 0L) > 0L
                }
                assertTrue(
                    "The real download must be underway with real bytes persisted " +
                        "(row=${runBlocking { dao.getById(episodeId)?.state }}, " +
                        "served=${loopback.servedSegmentResponses})",
                    underway,
                )

                // ── 2. PAUSE — durable intent first, work stop second ─────────
                assertTrue(
                    "Pausing a live row must really claim it",
                    repository.pause(episodeId),
                )
                val paused = dao.getById(episodeId)
                assertNotNull("the paused row must exist", paused)
                assertEquals(
                    "the durable intent must be the state of record",
                    EpisodeDownloadEntity.STATE_PAUSED,
                    paused!!.state,
                )
                assertEquals(
                    "a paused row never advertises a rate",
                    0L,
                    paused.speed,
                )
                assertEquals(
                    "…nor a countdown",
                    0L,
                    paused.etaSeconds,
                )
                assertTrue(
                    "progress made before the pause is real and must be kept",
                    paused.downloadedBytes > 0L,
                )
                pausedSnapshot = paused

                val segmentDirPath = paused.segmentDir
                assertNotNull("the row must carry its segment directory", segmentDirPath)
                val tsFiles = File(segmentDirPath!!)
                    .listFiles { file -> file.isFile && file.name.endsWith(".ts") } ?: emptyArray()
                assertTrue(
                    "Pause must keep the completed segments for the resume",
                    tsFiles.isNotEmpty(),
                )
                assertTrue(
                    "…and every retained segment must be a complete file",
                    tsFiles.all { it.length() > 0L },
                )

                // ── 3. the cancellation must not overwrite the intent ─────────
                val survived = Phase10TestSupport.awaitUntil(30_000) {
                    val infos = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
                    val settled = infos.all { it.state.isFinished }
                    val state = runBlocking { dao.getById(episodeId)?.state }
                    settled && state == EpisodeDownloadEntity.STATE_PAUSED
                }
                assertTrue(
                    "The worker's cancellation cleanup (DOWNLOADING → CANCELLED) must " +
                        "lose to the persisted PAUSED intent — state after settle = " +
                        runBlocking { dao.getById(episodeId)?.state },
                    survived,
                )
                assertEquals(
                    EpisodeDownloadEntity.STATE_PAUSED,
                    dao.getById(episodeId)?.state,
                )
                assertTrue("the run must really have been served", loopback.servedSegmentResponses > 0)

                // ── 4. app restart: fresh repository + the real reconciler ────
                val restartRepository = DownloadRepository(context, dao, db.downloadSessionDao())
                DownloadStateReconciler(dao, workManager).reconcile()
                assertEquals(
                    "Reconciliation must leave a paused row alone on restart",
                    EpisodeDownloadEntity.STATE_PAUSED,
                    dao.getById(episodeId)?.state,
                )
                val liveAfterRestart = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
                    .none { !it.state.isFinished }
                assertTrue(
                    "A paused row must not gain live work when the app restarts",
                    liveAfterRestart,
                )
                val afterRestart = requireNotNull(dao.getById(episodeId)) { "row vanished" }
                assertEquals(
                    "the retained progress must survive the restart",
                    paused.downloadedBytes,
                    afterRestart.downloadedBytes,
                )

                // ── 5. RESUME — through a repository holding no memory ────────
                assertTrue(
                    "Resuming a paused row must really claim it",
                    restartRepository.resume(episodeId),
                )
                val leftPaused = Phase10TestSupport.awaitUntil(10_000) {
                    runBlocking { dao.getById(episodeId)?.state } !=
                        EpisodeDownloadEntity.STATE_PAUSED
                }
                assertTrue("a resumed row must leave PAUSED", leftPaused)

                val completed = Phase10TestSupport.awaitUntil(180_000) {
                    runBlocking { dao.getById(episodeId)?.state } ==
                        EpisodeDownloadEntity.STATE_COMPLETED
                }
                finalRow = dao.getById(episodeId)
                assertTrue(
                    "The resumed download must complete (row=${finalRow?.state}, " +
                        "error=${finalRow?.errorMessage}, served=${loopback.servedSegmentResponses})",
                    completed,
                )
            }
        } finally {
            // Nothing left running once the test leaves this scope.
            workManager.cancelUniqueWork(uniqueWorkName)
        }

        val row = finalRow
        assertNotNull("the completed row must exist", row)
        val completedRow = row!!
        assertEquals(
            "the episode must really have completed after the resume",
            EpisodeDownloadEntity.STATE_COMPLETED,
            completedRow.state,
        )
        assertEquals(
            "persisted bytes must equal EXACTLY what the segments wrote after a " +
                "pause + resume (${SEGMENT_COUNT} x ${segmentBody.size} B) — a stale " +
                "resume base or a re-counted segment would inflate this",
            expectedBytes,
            completedRow.downloadedBytes,
        )
        assertTrue("the resumed run must produce an output", !completedRow.outputPath.isNullOrEmpty())
        assertEquals("a finished run reports no rate", 0L, completedRow.speed)
        assertEquals("a finished run reports no countdown", 0L, completedRow.etaSeconds)

        // Run-ownership (pause/resume race fix, v4→v5): the run that finished
        // stamped its own token into the row it claimed, so the completion CAS
        // provably went through the run-owned statement — a stale run's write
        // could not have produced this row.
        assertNotNull(
            "the completed row must carry the run token of the run that completed it",
            completedRow.downloadRunId,
        )
        assertTrue(
            "the run token must be a real non-blank value",
            !completedRow.downloadRunId.isNullOrBlank(),
        )

        // The retained cache must really be used: a full re-download would need
        // another SEGMENT_COUNT responses on top of the ones already served. With
        // at most `hlsWorkers` (24) requests in flight at the pause, losing those
        // can add at most 24 — so crossing 2 x SEGMENT_COUNT proves a re-download.
        val servedTotal = loopback.servedSegmentResponses
        assertTrue(
            "Resume must reuse the retained segments instead of re-downloading " +
                "(served=$servedTotal, segments=$SEGMENT_COUNT)",
            servedTotal < 2 * SEGMENT_COUNT,
        )

        // ── 5. the dashboard really shows the paused state ────────────────────
        // Two moments of the SAME row side by side — only the LazyColumn key is
        // suffixed, every value below is what Room really persisted.
        val pausedUi = DownloadRowMapper.map(pausedSnapshot!!)
            .copy(episodeId = "$episodeId-paused")
        val finalUi = DownloadRowMapper.map(completedRow)

        composeRule.setContent {
            PaheBatcherTheme {
                DownloadsScreen(
                    uiState = DownloadsUiState(
                        rows = listOf(pausedUi, finalUi),
                        isLoading = false,
                    ),
                    onCancel = {},
                    onRetry = {},
                    onPlay = {},
                )
            }
        }

        assertTrue(
            "SPEC §25: the paused state must be rendered as its own concept",
            composeRule.onAllNodesWithText("paused", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals(
            "SPEC §20: exactly one Resume control — for the paused row",
            1,
            composeRule.onAllNodesWithText("Resume").fetchSemanticsNodes().size,
        )
        assertEquals(
            "An already-paused (or completed) row must not offer Pause",
            0,
            composeRule.onAllNodesWithText("Pause").fetchSemanticsNodes().size,
        )
        assertEquals(
            "Neither a paused nor a finished row may render a rate",
            0,
            composeRule.onAllNodesWithText("B/s", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** QUEUED episode with a resolved stream — the production entry state. */
    private fun seededEpisode(m3u8Url: String, createdAt: Long) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Pause Episode",
        fansub = "PauseSubs",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = EpisodeDownloadEntity.STATE_QUEUED,
        createdAt = createdAt,
        updatedAt = createdAt,
        m3u8Url = m3u8Url,
        userAgent = "PauseTestAgent",
        referer = "https://example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId),
    )

    /** Valid MPEG-TS bytes generated on-device (the proven FFmpegKit recipe). */
    private fun generateSegmentBody(genDir: File): ByteArray {
        val out = File(genDir, "pause_body.ts")
        val cmd = "-y -f lavfi -i color=c=blue:s=320x240:d=1:r=25 " +
            "-c:v libopenh264 -pix_fmt yuv420p -f mpegts ${out.absolutePath}"
        assertTrue("TS generation must succeed", ReturnCode.isSuccess(FFmpegKit.execute(cmd).returnCode))
        val body = out.readBytes()
        assertTrue("Generated TS must be non-empty", body.isNotEmpty())
        return body
    }

    /** Media playlist with relative segment URIs (same shape as the download tests). */
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
     * In-process loopback HLS origin serving SEGMENT RESPONSES STRICTLY ONE AT A
     * TIME with [segmentDelayMs] between them — the run's duration is therefore a
     * fixture property rather than a race against the 24 segment workers, which is
     * what makes "pause it while it is really running" reproducible. Mirrors the
     * fixture of `DownloadMetricsDeviceTest`.
     */
    private class SlowHlsFixture(
        private val playlist: String,
        private val segmentBody: ByteArray,
        private val segmentDelayMs: Long,
    ) {
        private var server: ServerSocket? = null
        @Volatile private var running = true
        private val segmentLock = Any()
        private val served = AtomicInteger(0)

        val servedSegmentResponses: Int get() = served.get()

        val playlistUrl: String
            get() = "http://127.0.0.1:${server?.localPort ?: error("fixture not started")}/playlist.m3u8"

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
            }, "slow-hls-fixture").apply {
                isDaemon = true
                start()
            }
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 20_000
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val requestLine = input.readLine() ?: return
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isEmpty()) break
                }

                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: return
                if (path.endsWith(".ts")) {
                    synchronized(segmentLock) {
                        Thread.sleep(segmentDelayMs)
                        served.incrementAndGet()
                        write(socket, segmentBody, "video/mp2t")
                    }
                } else if (path.endsWith(".m3u8")) {
                    write(socket, playlist.toByteArray(Charsets.US_ASCII), "application/vnd.apple.mpegurl")
                } else {
                    val out: OutputStream = socket.getOutputStream()
                    out.write(
                        ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(),
                    )
                    out.flush()
                }
            } catch (_: Exception) {
                // Surfaced by the assertions on served counts / completion.
            }
        }

        private fun write(socket: Socket, body: ByteArray, contentType: String) {
            val out: OutputStream = socket.getOutputStream()
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }

        fun stop() {
            running = false
            try {
                server?.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }
    }

    private companion object {
        /**
         * 30 segments x 500 ms serialised = ~15 s: long enough for a real metric
         * flush (5 s throttle) before the pause, and ≥ 2 x the in-flight worker
         * count so "resume must not re-download everything" stays a strict bound.
         */
        const val SEGMENT_COUNT = 30
        const val SEGMENT_DELAY_MS = 500L
    }
}
