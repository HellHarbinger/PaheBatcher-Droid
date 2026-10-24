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
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.util.StringUtils
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SPEC §25 device verification — downloaded bytes, speed and ETA through the
 * REAL production pipeline on a physical device:
 *
 * `AppConfig` → real `WorkManager` → real `DownloadWorker` → real
 * `EpisodeDownloader` over a real socket → real `ProgressTracker` → real Room
 * writes → the real `DownloadsScreen` composable.
 *
 * What this pins:
 *
 * 1. the byte total persisted at the end is EXACTLY what the segments wrote
 *    (`segmentBody.size * segmentCount` — no inflation from assembly, from
 *    partial `.tmp` writes or from a resume base that does not exist);
 * 2. a genuinely measured rate and ETA are persisted WHILE the row is
 *    DOWNLOADING (the loopback origin serves one segment every
 *    [SEGMENT_DELAY_MS] ms, so the run outlasts the 5 s progress throttle and
 *    the flushes are observable);
 * 3. the STALE rate seeded before the run is cleared by the claim CAS and is
 *    never presented as this run's measurement;
 * 4. the byte trace never regresses, and the terminal row reports rate 0;
 * 5. the dashboard really renders the byte total and the active rate, and the
 *    terminal row renders NO rate at all (stale-metric guard 2 of 2).
 *
 * The origin is an in-process loopback server: no external service, no CDN.
 */
@RunWith(AndroidJUnit4::class)
class DownloadMetricsDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-metrics-$runStamp"
    private val episodeId = "ep-metrics-$runStamp"
    private val genDirPath = "metrics_gen_$runStamp"

    private var fixture: SerialHlsFixture? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        repository = DownloadRepository(context, db.episodeDownloadDao(), db.downloadSessionDao())
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)

        // Same guard as AutoResumeConnectivityTest: drain other sessions' live
        // rows so an unrelated queued episode cannot be picked up meanwhile.
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
        workManager.cancelUniqueWork("episode_download_$episodeId")
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
    fun aRealDownloadPersistsRealBytesAndARateOnlyWhileItIsDownloading() {
        val genDir = File(context.cacheDir, genDirPath).apply { mkdirs() }
        val segmentBody = generateSegmentBody(genDir)
        val loopback = SerialHlsFixture(
            playlist = buildPlaylist(SEGMENT_COUNT),
            segmentBody = segmentBody,
            segmentDelayMs = SEGMENT_DELAY_MS,
        )
        fixture = loopback
        loopback.start()

        // What the segments really write: every segment is served as exactly
        // [segmentBody] bytes, and nothing else (assembly, `.tmp`, export) may
        // contribute a single byte to the persisted total.
        val expectedBytes = segmentBody.size.toLong() * SEGMENT_COUNT

        val observations = CopyOnWriteArrayList<EpisodeDownloadEntity>()
        val observing = AtomicBoolean(true)
        val observer = Thread {
            while (observing.get()) {
                val row = runBlocking { db.episodeDownloadDao().getById(episodeId) }
                if (row != null) observations += row
                try {
                    Thread.sleep(OBSERVE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "download-metrics-observer"
            start()
        }

        var finalRow: EpisodeDownloadEntity? = null
        try {
            runBlocking {
                val dao = db.episodeDownloadDao()
                // A row carrying a rate from some earlier run — it must never be
                // presented as this run's measurement.
                dao.upsert(
                    seededEpisode(
                        m3u8Url = loopback.playlistUrl,
                        createdAt = System.currentTimeMillis(),
                    ),
                )

                repository.enqueueWorker(episodeId)

                val completed = Phase10TestSupport.awaitUntil(180_000) {
                    runBlocking { dao.getById(episodeId)?.state } ==
                        EpisodeDownloadEntity.STATE_COMPLETED
                }
                finalRow = dao.getById(episodeId)

                assertTrue(
                    "The real download must complete (row=${finalRow?.state}, " +
                        "error=${finalRow?.errorMessage}, served=${loopback.servedSegmentResponses})",
                    completed,
                )
            }
        } finally {
            observing.set(false)
            observer.join(2_000)
        }

        val row = finalRow
        assertNotNull("the completed row must exist", row)
        assertEquals(
            "the episode must really have completed",
            EpisodeDownloadEntity.STATE_COMPLETED,
            row!!.state,
        )

        // ── 1. exact byte accounting ────────────────────────────────────────
        assertEquals(
            "persisted bytes must equal EXACTLY what the segments wrote " +
                "(${SEGMENT_COUNT} x ${segmentBody.size} B)",
            expectedBytes,
            row.downloadedBytes,
        )
        assertTrue("the download must really have produced an output", !row.outputPath.isNullOrEmpty())
        assertEquals(
            "every segment must have been served exactly once",
            SEGMENT_COUNT,
            loopback.servedSegmentResponses,
        )

        // ── 2/3. rate and stale-rate guard ──────────────────────────────────
        val downloading = observations.filter { it.state == EpisodeDownloadEntity.STATE_DOWNLOADING }
        assertTrue(
            "the row must have been observed DOWNLOADING (observed states=${observations.map { it.state }.toSet()})",
            downloading.isNotEmpty(),
        )

        val firstDownloading = downloading.first()
        assertEquals(
            "claiming the row clears the inherited rate in the same statement " +
                "(no window where DOWNLOADING still carries the previous run's rate)",
            0L,
            firstDownloading.speed,
        )
        assertEquals(
            "claiming the row clears the inherited ETA too",
            0L,
            firstDownloading.etaSeconds,
        )
        assertTrue(
            "the seeded stale speed must never surface while DOWNLOADING",
            downloading.none { it.speed == STALE_SPEED },
        )
        assertTrue(
            "the seeded stale ETA must never surface while DOWNLOADING",
            downloading.none { it.etaSeconds == STALE_ETA },
        )

        val measured = downloading.filter { it.speed > 0 }
        assertTrue(
            "a genuinely measured rate must be persisted mid-download " +
                "(progress throttle is ${PROGRESS_THROTTLE_MS} ms, run lasts " +
                "${SEGMENT_COUNT * SEGMENT_DELAY_MS} ms)",
            measured.isNotEmpty(),
        )
        assertTrue(
            "a genuinely derived ETA must be persisted mid-download",
            downloading.any { it.etaSeconds > 0 },
        )
        assertTrue(
            "real bytes must be visible during the download",
            observations.any { it.downloadedBytes > 0 },
        )

        // ── 4. monotonic bytes, cleared terminal rate ───────────────────────
        val byteTrace = observations.map { it.downloadedBytes }
        assertTrue(
            "downloaded bytes must never regress (trace=$byteTrace)",
            byteTrace.zipWithNext().all { (before, after) -> after >= before },
        )
        assertEquals("a finished run reports no rate", 0L, row.speed)
        assertEquals("a finished run reports no countdown", 0L, row.etaSeconds)

        // ── 5. the dashboard really shows it ────────────────────────────────
        // Two moments of the SAME row are shown side by side, so the LazyColumn
        // key (`episodeId`) has to differ between them — only the key is
        // suffixed; every metric below is the value Room really persisted.
        val activeRow = DownloadRowMapper.map(measured.first())
            .copy(episodeId = "${episodeId}-midrun")
        val terminalRow = DownloadRowMapper.map(row)

        val speedText = DownloadRowMapper.formatSpeed(activeRow.speedBps)
        val etaText = DownloadRowMapper.formatEta(activeRow.etaSeconds)
        assertNotNull("the active row must have a rate to render", speedText)
        assertNotNull("the active row must have an ETA to render", etaText)

        composeRule.setContent {
            PaheBatcherTheme {
                DownloadsScreen(
                    uiState = DownloadsUiState(
                        rows = listOf(activeRow, terminalRow),
                        isLoading = false,
                    ),
                    onCancel = {},
                    onRetry = {},
                    onPlay = {},
                )
            }
        }

        val byteText = StringUtils.fmtBytes(expectedBytes)
        assertTrue(
            "the exact byte total must be on screen (wanted '$byteText')",
            composeRule.onAllNodesWithText(byteText, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the measured rate must be on screen (wanted '$speedText')",
            composeRule.onAllNodesWithText(speedText!!, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the derived ETA must be on screen (wanted '$etaText')",
            composeRule.onAllNodesWithText(etaText!!, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals(
            "of the two rows on screen, ONLY the downloading one may render a rate",
            1,
            composeRule.onAllNodesWithText("B/s", substring = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "a fabricated '0 B/s' must never reach the screen",
            0,
            composeRule.onAllNodesWithText("0 B/s", substring = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "a fabricated '0:00' countdown must never reach the screen",
            0,
            composeRule.onAllNodesWithText("0:00", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** QUEUED episode with a resolved stream and an inherited (stale) rate. */
    private fun seededEpisode(m3u8Url: String, createdAt: Long) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Metrics Episode",
        fansub = "MetricsSubs",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = EpisodeDownloadEntity.STATE_QUEUED,
        speed = STALE_SPEED,
        etaSeconds = STALE_ETA,
        createdAt = createdAt,
        updatedAt = createdAt,
        m3u8Url = m3u8Url,
        userAgent = "MetricsTestAgent",
        referer = "https://example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId),
    )

    /** Valid MPEG-TS bytes generated on-device (the proven FFmpegKit recipe). */
    private fun generateSegmentBody(genDir: File): ByteArray {
        val out = File(genDir, "metrics_body.ts")
        val cmd = "-y -f lavfi -i color=c=red:s=320x240:d=1:r=25 " +
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
     * In-process loopback HLS origin that serves SEGMENT RESPONSES STRICTLY ONE
     * AT A TIME with [segmentDelayMs] between them.
     *
     * Serialising the responses is what makes the run's duration a fixture
     * property (`SEGMENT_COUNT x SEGMENT_DELAY_MS`) instead of a race against the
     * 24 segment workers: the download therefore always outlives the 5 s
     * progress throttle, so real mid-run metric flushes are guaranteed to be
     * observable. Connections are still accepted concurrently (each runs in its
     * own thread); only the segment writes are serialised behind one lock.
     */
    private class SerialHlsFixture(
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
            }, "serial-hls-fixture").apply {
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
                val isSegment = path.endsWith(".ts")

                if (isSegment) {
                    // Serialised: the run lasts SEGMENT_COUNT * delay by design.
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
        /** 20 segments x 400 ms serialised = ~8 s — past the 5 s flush throttle. */
        const val SEGMENT_COUNT = 20
        const val SEGMENT_DELAY_MS = 400L
        const val OBSERVE_INTERVAL_MS = 100L

        /** Mirrors `DownloadConfig.progressThrottleMs` (not settable from tests). */
        const val PROGRESS_THROTTLE_MS = 5000L

        /** Rate inherited by the seeded row — must never be observed again. */
        const val STALE_SPEED = 999L
        const val STALE_ETA = 999L
    }
}
