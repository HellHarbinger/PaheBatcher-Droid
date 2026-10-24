package com.pahebatcher.app.data.download

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * SPEC §20/§25 — the **real** process-death scenario for a PAUSED download:
 * an external kill between a pause and a resume, executed on a physical
 * device through the REAL production pipeline (`DownloadRepository` → real
 * `WorkManager` → real `DownloadWorker` → real Room → the real
 * `DownloadStateReconciler`).
 *
 * This is NOT a simulated process death and NOT an in-process
 * `new repository → reconcile()` check — the state is persisted by one
 * process, that process is killed from OUTSIDE (`adb shell am force-stop`)
 * while the instrumentation is deliberately asleep, and the assertions run in
 * a SECOND, freshly started process. The two processes are proven distinct by
 * `Process.myPid()`.
 *
 * Protocol (adb-driven), mirroring `RealProcessDeathSeedTest`:
 *
 *  1. `am instrument -e class ...RealProcessDeathPausedDeviceTest
 *     -e leavePaused true`
 *     → generates real MPEG-TS bytes on-device (FFmpegKit), serves them from
 *       a loopback HLS origin on a FIXED port, seeds a QUEUED episode, starts
 *       the real worker, waits until ≥ 1 segment is really persisted, then
 *       PAUSES the run. It asserts the durable pause contract
 *       (`PAUSED` + `speed == 0` + `etaSeconds == 0` + retained `.ts` files),
 *       asserts the worker really stopped, records the baseline
 *       {pid, port, episodeId, segments, bytes} to
 *       `filesDir/pd_paused_phase1.txt`, and deliberately sleeps (grace
 *       window). The operator force-stops the package during that sleep —
 *       the instrumentation command failing is the expected signal of a REAL
 *       kill (LIMITATIONS §20: the kill must come from outside the app).
 *  2. `adb shell am start …` relaunches the app.
 *  3. `am instrument -e class ...RealProcessDeathPausedDeviceTest
 *     -e verifyPaused true`
 *     → rebinds the SAME origin port with the SAME persisted bytes, runs the
 *       real reconciler in a process that holds no in-memory state, and
 *       asserts that a PAUSED row survives a real kill untouched: still
 *       `PAUSED`, same retained segments, no auto-resume, no live work. It
 *       then resumes through a fresh repository and asserts the run finishes
 *       with EXACTLY the bytes the segments write and without re-downloading
 *       the retained ones.
 *
 * In a normal suite run (no `-e` args) the same seed → pause → reconcile →
 * resume → complete sequence executes in-process without a kill, and every
 * trace is cleaned up inside the test, so the suite stays green and no state
 * leaks into later tests. The executed protocol runs and their evidence are
 * recorded in LIMITATIONS / PARITY.md.
 */
@RunWith(AndroidJUnit4::class)
class RealProcessDeathPausedDeviceTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager

    private var fixture: FixedPortHlsFixture? = null

    private val uniqueWorkName = "episode_download_$EPISODE_ID"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
    }

    @After
    fun teardown() {
        // Protocol phase 2 (and suite mode) leaves nothing behind. Protocol
        // phase 1 must NOT touch the seeded state — the kill is still ahead.
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("verifyPaused") == "true" || args.getString("leavePaused") != "true") {
            workManager.cancelUniqueWork(uniqueWorkName)
            fixture?.stop()
            fixture = null
            runBlocking {
                val outputUri = db.episodeDownloadDao().getById(EPISODE_ID)?.outputUri
                if (outputUri != null) {
                    try {
                        context.contentResolver.delete(Uri.parse(outputUri), null, null)
                    } catch (_: Exception) {
                        // Best effort — the row is gone either way.
                    }
                }
                db.episodeDownloadDao().delete(EPISODE_ID)
            }
            Phase10TestSupport.deleteSession(db, SESSION_ID)
            File(context.filesDir, RECORD_FILE).delete()
            File(context.filesDir, BODY_FILE).delete()
            File(context.filesDir, "downloads/$EPISODE_ID").deleteRecursively()
        }
    }

    @Test
    fun aPausedDownloadSurvivesARealExternalProcessDeathAndResumesToCompletion() {
        val args = InstrumentationRegistry.getArguments()
        val verifyPaused = args.getString("verifyPaused") == "true"

        if (verifyPaused) {
            verifyPausedAfterRealDeath()
            return
        }

        val leavePaused = args.getString("leavePaused") == "true"
        seedPauseAndRecordBaseline(leavePaused = leavePaused)

        if (!leavePaused) {
            // No kill requested (normal suite run): verify the paused state
            // end-to-end in this same process. The REAL cross-process kill is
            // the protocol mode above.
            verifyPausedAfterRealDeath(inProcess = true)
        }
    }

    // -----------------------------------------------------------------------
    // Phase 1 — seed, run, pause, record the pre-death baseline
    // -----------------------------------------------------------------------

    private fun seedPauseAndRecordBaseline(leavePaused: Boolean) {
        val dao = db.episodeDownloadDao()

        // Idempotent: wipe leftovers from a previous (possibly aborted) run —
        // but only ever from this process, never from the verification phase.
        workManager.cancelUniqueWork(uniqueWorkName).result.get()
        Phase10TestSupport.deleteSession(db, SESSION_ID)
        runBlocking {
            dao.delete(EPISODE_ID)
        }
        File(context.filesDir, RECORD_FILE).delete()
        File(context.filesDir, "downloads/$EPISODE_ID").deleteRecursively()
        Phase10TestSupport.insertSession(db, SESSION_ID)

        val genDir = File(context.filesDir, PD_GEN_DIR).apply { mkdirs() }
        val segmentBody = generateSegmentBody(genDir)
        // Persist the exact bytes so the post-death process can re-serve them
        // from a freshly bound socket — the download must resume against the
        // SAME media, not a re-generated lookalike.
        File(context.filesDir, BODY_FILE).writeBytes(segmentBody)

        val fixtureInstance = FixedPortHlsFixture(
            port = 0,
            playlist = buildPlaylist(SEGMENT_COUNT),
            segmentBody = segmentBody,
            segmentDelayMs = SEGMENT_DELAY_MS,
        )
        fixture = fixtureInstance
        fixtureInstance.start()

        val repository = DownloadRepository(context, dao, db.downloadSessionDao())

        runBlocking {
            val now = System.currentTimeMillis()
            dao.upsert(seededEpisode(m3u8Url = fixtureInstance.playlistUrl, createdAt = now))
            repository.enqueueWorker(EPISODE_ID)

            // ── 1. the run must be genuinely underway before the pause ──────
            val underway = Phase10TestSupport.awaitUntil(60_000) {
                val row = runBlocking { dao.getById(EPISODE_ID) }
                row?.state == EpisodeDownloadEntity.STATE_DOWNLOADING &&
                    (row?.downloadedBytes ?: 0L) > 0L
            }
            assertTrue(
                "The real download must be underway with real bytes persisted " +
                    "(row=${runBlocking { dao.getById(EPISODE_ID)?.state }}, " +
                    "served=${fixtureInstance.servedSegmentResponses})",
                underway,
            )

            // ── 2. PAUSE — durable intent first, work stop second ───────────
            assertTrue(
                "Pausing a live row must really claim it",
                repository.pause(EPISODE_ID),
            )
            val paused = dao.getById(EPISODE_ID)
            assertNotNull("the paused row must exist", paused)
            assertEquals(
                "the durable intent must be the state of record",
                EpisodeDownloadEntity.STATE_PAUSED,
                paused!!.state,
            )
            assertEquals("a paused row never advertises a rate", 0L, paused.speed)
            assertEquals("…nor a countdown", 0L, paused.etaSeconds)
            assertTrue(
                "progress made before the pause is real and must be kept",
                paused.downloadedBytes > 0L,
            )

            val segmentDirPath = paused.segmentDir
            assertNotNull("the row must carry its segment directory", segmentDirPath)
            val tsFiles = retainedSegments(segmentDirPath!!)
            assertTrue("Pause must keep completed segments for the resume", tsFiles.isNotEmpty())
            assertTrue(
                "…and every retained segment must be a complete file",
                tsFiles.all { it.length() > 0L },
            )

            // ── 3. the worker's cancellation cleanup must not erase it ──────
            val settled = Phase10TestSupport.awaitUntil(30_000) {
                val infos = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
                val allFinished = infos.all { it.state.isFinished }
                val state = runBlocking { dao.getById(EPISODE_ID)?.state }
                allFinished && state == EpisodeDownloadEntity.STATE_PAUSED
            }
            assertTrue(
                "The worker must really have stopped, and its cancellation " +
                    "cleanup (DOWNLOADING → CANCELLED) must lose to the persisted " +
                    "PAUSED intent — state after settle = " +
                    runBlocking { dao.getById(EPISODE_ID)?.state },
                settled,
            )
            assertEquals(
                EpisodeDownloadEntity.STATE_PAUSED,
                dao.getById(EPISODE_ID)?.state,
            )
            assertTrue(
                "the run must really have been served before the pause",
                fixtureInstance.servedSegmentResponses > 0,
            )

            // ── 4. record the pre-death baseline the phase-2 assertions use ──
            val segmentsAtPause = tsFiles.size
            val downloadedBytesAtPause = requireNotNull(dao.getById(EPISODE_ID)).downloadedBytes
            val record = mapOf(
                "pid" to Process.myPid().toString(),
                "episodeId" to EPISODE_ID,
                "sessionId" to SESSION_ID,
                "port" to fixtureInstance.boundPort.toString(),
                "segmentCount" to SEGMENT_COUNT.toString(),
                "segmentDelayMs" to SEGMENT_DELAY_MS.toString(),
                "segmentsAtPause" to segmentsAtPause.toString(),
                "downloadedBytesAtPause" to downloadedBytesAtPause.toString(),
                "servedTotalAtPause" to fixtureInstance.servedSegmentResponses.toString(),
                "bodyFile" to BODY_FILE,
            )
            File(context.filesDir, RECORD_FILE).writeText(
                record.entries.joinToString("\n") { "${it.key}=${it.value}" },
            )

            if (leavePaused) {
                // Protocol mode: leave the PAUSED row, the retained segments and
                // the origin body in place, then sleep — the operator force-stops
                // the package during this window. The instrumentation process is
                // about to die for real, so it must NOT clean anything up.
                Thread.sleep(GRACE_SLEEP_MS)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Phase 2 — the post-death verification (fresh process, no memory)
    // -----------------------------------------------------------------------

    private fun verifyPausedAfterRealDeath(inProcess: Boolean = false) {
        val dao = db.episodeDownloadDao()
        val record = readRecord()
        val bodyFile = File(context.filesDir, record.bodyFile)
        assertTrue(
            "the persisted segment body must survive the process death " +
                "(${bodyFile.absolutePath})",
            bodyFile.isFile && bodyFile.length() > 0L,
        )

        // In-process mode: the phase-1 origin is still bound to the recorded
        // port, so it must be stopped before phase 2 can rebind it — exactly
        // what a real kill does for free in protocol mode (the process that
        // owned the port is already gone).
        fixture?.stop()
        fixture = null

        // The verification must run in a DIFFERENT process than the one that
        // wrote the baseline — otherwise nothing was really killed.
        if (!inProcess) {
            assertEquals(
                "the paused baseline was written by pid ${record.pid}; the " +
                    "verification must run in a freshly started process",
                false,
                Process.myPid() == record.pid,
            )
        }

        val body = bodyFile.readBytes()
        val fixtureInstance = FixedPortHlsFixture(
            port = record.port,
            playlist = buildPlaylist(record.segmentCount),
            segmentBody = body,
            segmentDelayMs = record.segmentDelayMs,
        )
        fixture = fixtureInstance
        fixtureInstance.start()

        // A repository holding NO in-memory state + the real app-restart path.
        val restartRepository = DownloadRepository(context, dao, db.downloadSessionDao())

        runBlocking {
            // ── 1. reconciliation must leave the paused row completely alone ─
            DownloadStateReconciler(dao, workManager).reconcile()

            val afterKill = dao.getById(record.episodeId)
            assertNotNull("the paused row must survive the process death", afterKill)
            assertEquals(
                "SPEC §20: a real kill must NOT change a PAUSED row's state",
                EpisodeDownloadEntity.STATE_PAUSED,
                afterKill!!.state,
            )
            assertEquals(
                "…and must not touch its retained progress",
                record.downloadedBytesAtPause,
                afterKill.downloadedBytes,
            )
            assertEquals("…nor its cleared rate", 0L, afterKill.speed)
            assertEquals("…nor its cleared countdown", 0L, afterKill.etaSeconds)

            val liveAfterRelaunch = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
                .none { !it.state.isFinished }
            assertTrue(
                "Reconciliation must not resurrect work for a paused row " +
                    "(${workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()})",
                liveAfterRelaunch,
            )

            val retained = retainedSegments(
                requireNotNull(afterKill.segmentDir) { "row lost its segment dir" },
            )
            assertTrue(
                "the segments retained before the kill must still be on disk " +
                    "(recorded=${record.segmentsAtPause}, found=${retained.size})",
                retained.size >= record.segmentsAtPause,
            )
            assertTrue(
                "…and every one of them must still be a complete file",
                retained.all { it.length() > 0L },
            )
            assertTrue(
                "…with real bytes behind them",
                retained.sumOf { it.length() } > 0L,
            )

            // ── 2. RESUME through a repository holding no memory ─────────────
            assertTrue(
                "Resuming a paused row must really claim it",
                restartRepository.resume(EPISODE_ID),
            )
            val leftPaused = Phase10TestSupport.awaitUntil(10_000) {
                runBlocking { dao.getById(EPISODE_ID)?.state } !=
                    EpisodeDownloadEntity.STATE_PAUSED
            }
            assertTrue("a resumed row must leave PAUSED", leftPaused)

            val completed = Phase10TestSupport.awaitUntil(180_000) {
                runBlocking { dao.getById(EPISODE_ID)?.state } ==
                    EpisodeDownloadEntity.STATE_COMPLETED
            }
            val finalRow = dao.getById(EPISODE_ID)
            assertTrue(
                "The resumed download must complete after the real kill " +
                    "(row=${finalRow?.state}, error=${finalRow?.errorMessage}, " +
                    "served=${fixtureInstance.servedSegmentResponses})",
                completed,
            )

            // ── 3. the retained cache must really have been reused ────────────
            // A full re-download would need another `segmentCount` responses on
            // top of anything the killed run left in flight (at most the bounded
            // `hlsWorkers`), so crossing 2 x segmentCount proves a re-download.
            val servedTotal = fixtureInstance.servedSegmentResponses
            assertTrue(
                "Resume must reuse the retained segments instead of " +
                    "re-downloading (served=$servedTotal, segments=" +
                    "${record.segmentCount})",
                servedTotal < 2 * record.segmentCount,
            )

            // ── 4. completion must be exact ───────────────────────────────────
            val expectedBytes = body.size.toLong() * record.segmentCount
            assertEquals(
                "persisted bytes must equal EXACTLY what the segments wrote " +
                    "(${record.segmentCount} x ${body.size} B) — a stale resume " +
                    "base or a re-counted segment would inflate this",
                expectedBytes,
                finalRow!!.downloadedBytes,
            )
            assertEquals(
                EpisodeDownloadEntity.STATE_COMPLETED,
                finalRow.state,
            )
            assertTrue(
                "a finished run must produce an output",
                !finalRow.outputPath.isNullOrEmpty(),
            )
            assertEquals("a finished run reports no rate", 0L, finalRow.speed)
            assertEquals("a finished run reports no countdown", 0L, finalRow.etaSeconds)
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures / helpers
    // -----------------------------------------------------------------------

    /** QUEUED episode with a resolved stream — the production entry state. */
    private fun seededEpisode(m3u8Url: String, createdAt: Long) = EpisodeDownloadEntity(
        episodeId = EPISODE_ID,
        sessionId = SESSION_ID,
        episodeNumber = 1.0f,
        episodeSession = "eps-$EPISODE_ID",
        title = "Process Death Paused Episode",
        fansub = "PdSubs",
        audio = "sub",
        playUrl = "https://example.com/play",
        state = EpisodeDownloadEntity.STATE_QUEUED,
        createdAt = createdAt,
        updatedAt = createdAt,
        m3u8Url = m3u8Url,
        userAgent = "PdPausedTestAgent",
        referer = "https://example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, EPISODE_ID),
    )

    /** Completed, non-empty `.ts` files the downloader really wrote. */
    private fun retainedSegments(segmentDirPath: String): Array<File> =
        File(segmentDirPath)
            .listFiles { file -> file.isFile && file.name.endsWith(".ts") } ?: emptyArray()

    /** Valid MPEG-TS bytes generated on-device (the proven FFmpegKit recipe). */
    private fun generateSegmentBody(genDir: File): ByteArray {
        val out = File(genDir, "pd_body.ts")
        val cmd = "-y -f lavfi -i color=c=navy:s=320x240:d=1:r=25 " +
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

    private fun readRecord(): ProtocolRecord {
        val file = File(context.filesDir, RECORD_FILE)
        assertTrue(
            "the phase-1 protocol record must exist " +
                "($RECORD_FILE) — was phase 1 run with -e leavePaused true?",
            file.isFile,
        )
        val values = file.readText().lines()
            .filter { it.contains('=') }
            .associate { line ->
                val index = line.indexOf('=')
                line.substring(0, index) to line.substring(index + 1)
            }
        return ProtocolRecord(
            pid = values.getValue("pid").toInt(),
            episodeId = values.getValue("episodeId"),
            port = values.getValue("port").toInt(),
            segmentCount = values.getValue("segmentCount").toInt(),
            segmentDelayMs = values.getValue("segmentDelayMs").toLong(),
            segmentsAtPause = values.getValue("segmentsAtPause").toInt(),
            downloadedBytesAtPause = values.getValue("downloadedBytesAtPause").toLong(),
            bodyFile = values.getValue("bodyFile"),
        )
    }

    private data class ProtocolRecord(
        val pid: Int,
        val episodeId: String,
        val port: Int,
        val segmentCount: Int,
        val segmentDelayMs: Long,
        val segmentsAtPause: Int,
        val downloadedBytesAtPause: Long,
        val bodyFile: String,
    )

    /**
     * Loopback HLS origin bound to an EXACT port — phase 2 must rebind the port
     * phase 1 recorded, because the episode row's `m3u8Url` still points at it
     * after the process death. Serves segments strictly one at a time so the
     * run's duration is a fixture property rather than a race against the
     * bounded segment workers.
     */
    private class FixedPortHlsFixture(
        private val port: Int,
        private val playlist: String,
        private val segmentBody: ByteArray,
        private val segmentDelayMs: Long,
    ) {
        private var server: ServerSocket? = null
        @Volatile private var running = true
        private val segmentLock = Any()
        private val served = AtomicInteger(0)

        val servedSegmentResponses: Int get() = served.get()

        val boundPort: Int
            get() = server?.localPort ?: error("fixture not started")

        val playlistUrl: String
            get() = "http://127.0.0.1:$boundPort/playlist.m3u8"

        fun start() {
            // reuseAddress lets the post-death process rebind the exact port the
            // killed run used even while its sockets are still in TIME_WAIT —
            // the episode row's m3u8Url keeps pointing at that port.
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
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
            }, "pd-paused-hls-fixture").apply {
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
        const val EPISODE_ID = "ep-real-pd-paused"
        const val SESSION_ID = "sess-real-pd-paused"
        const val RECORD_FILE = "pd_paused_phase1.txt"
        const val BODY_FILE = "pd_paused_body.ts"
        const val PD_GEN_DIR = "pd_paused_gen"

        /**
         * 40 segments x 500 ms serialised = ~20 s: long enough for a real pause
         * window, and ≥ 2 x the bounded in-flight worker count so "resume must
         * not re-download everything" stays a strict bound.
         */
        const val SEGMENT_COUNT = 40
        const val SEGMENT_DELAY_MS = 500L

        /**
         * The operator's window to force-stop the package from outside. It is
         * deliberately generous: on a multi-user device the host cannot poll the
         * phase-1 record file (`run-as` resolves to the wrong user), so the kill
         * is issued at a fixed moment that must fall inside this window.
         */
        const val GRACE_SLEEP_MS = 60_000L
    }
}
