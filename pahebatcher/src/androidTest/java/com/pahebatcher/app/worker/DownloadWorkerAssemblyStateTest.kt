package com.pahebatcher.app.worker

import android.content.Context
import android.app.NotificationManager
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
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.support.Phase10TestSupport
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Final-audit H4 (SPEC §6.13/§25 "remuxing", SPEC §15 row-state lifecycle):
 * `STATE_ASSEMBLING` must be a REAL persisted row state, written by the one
 * production writer (the `onAssemblyStart` hook inside `EpisodeDownloader`'s
 * download call) at the instant before FFmpeg starts muxing, and the run's
 * terminal write must then CAS from ASSEMBLING — not from the stale
 * DOWNLOADING claim.
 *
 * Everything below the fake HLS client is REAL:
 * - real DownloadWorker (entry claim, progress, terminal CAS, notifications)
 * - REAL Room database (PaheDatabase singleton) and REAL rows
 * - real SegmentFileStore in the production segment directory
 * - real FFmpeg assembly of VALID on-device-generated MPEG-TS segments
 *
 * The only seam is the DAO wrapper, which RECORDS the CAS attempts without
 * changing any write — the test proves the exact transition sequence
 * `QUEUED -> DOWNLOADING -> ASSEMBLING -> COMPLETED` against observable Room
 * state, never against an internal flag.
 *
 * Test doubles live here only — never in production.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWorkerAssemblyStateTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-assembly-state-$runStamp"
    private val episodeId = "ep-assembly-state-$runStamp"
    private val playlistUrl = "https://cdn.local.test/assembly-state/playlist.m3u8"
    private val baseUrl = "https://cdn.local.test/assembly-state/"

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
        File(Phase10TestSupport.segmentDirFor(context, episodeId)).deleteRecursively()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(DownloadNotificationManager(context).terminalIdFor(episodeId))
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    /** Generate one valid MPEG-TS segment on-device (proven FFmpegKit command). */
    private fun generateValidTs(out: File): Boolean {
        val cmd = "-y -f lavfi -i color=c=red:s=320x240:d=1:r=25 " +
            "-c:v libopenh264 -pix_fmt yuv420p " +
            "-f mpegts ${out.absolutePath}"
        val session = FFmpegKit.execute(cmd)
        val ok = ReturnCode.isSuccess(session.returnCode)
        if (!ok) {
            android.util.Log.e("AssemblyStateTest", "TS generation failed: ${session.output}")
        }
        return ok
    }

    private fun seed(state: String, m3u8Url: String?) = EpisodeDownloadEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        episodeNumber = 1.0f,
        episodeSession = "eps-$episodeId",
        title = "Assembly State Episode",
        fansub = "TestFansub",
        audio = "sub",
        playUrl = "https://animepahe.com/play/$sessionId/eps-$episodeId",
        state = state,
        retryCount = 0,
        errorMessage = null,
        createdAt = runStamp,
        updatedAt = runStamp,
        m3u8Url = m3u8Url,
        userAgent = "AssemblyStateUA",
        referer = "https://example.com",
        segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId),
    )

    @Test
    fun real_run_persists_assembling_before_mux_and_completes_from_it() = runBlocking {
        // ── 1. Valid TS bytes generated on-device (the mux is REAL) ──
        val segmentUrls = (0 until 3).map { "${baseUrl}seg$it.ts" }
        val segmentBytes = mutableMapOf<String, ByteArray>()
        segmentUrls.forEachIndexed { i, url ->
            val ts = File(context.cacheDir, "assembly_state_gen${i}_$runStamp.ts")
            try {
                assertTrue("TS generation $i must succeed", generateValidTs(ts))
                segmentBytes[url] = ts.readBytes()
            } finally {
                ts.delete()
            }
        }
        val playlist = """
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

        // ── 2. Seed a QUEUED row with the persisted stream info ──────
        val dao = db.episodeDownloadDao()
        dao.upsert(seed(EpisodeDownloadEntity.STATE_QUEUED, playlistUrl))

        // ── 3. Recording seam: every guarded CAS, in execution order ──
        val recorder = AssemblyRecordingDao(dao)

        // ── 4. Run the REAL Worker against the fake HLS source ───────
        val worker = TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
            .setInputData(workDataOf("episode_id" to episodeId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = DownloadWorker(
                    context = appContext,
                    params = workerParameters,
                    dao = recorder,
                    notificationManager = DownloadNotificationManager(appContext),
                    hlsClient = AssemblyLocalHlsSource(playlistUrl, playlist, segmentBytes),
                    keyCache = com.pahebatcher.app.data.hls.AesKeyCache(),
                    config = DownloadConfig(),
                )
            })
            .build()

        val result = worker.startWork().get(120, TimeUnit.SECONDS)
        assertEquals(
            "the full success path must produce a success Result",
            ListenableWorker.Result.success(),
            result,
        )

        // ── 5. The recorded transition sequence (H4 core assertion) ──
        val events = recorder.events.toList()
        val claimIndex = events.indexOf("CAS ${EpisodeDownloadEntity.STATE_QUEUED}->" +
            EpisodeDownloadEntity.STATE_DOWNLOADING)
        val assemblingIndex = events.indexOf("CAS ${EpisodeDownloadEntity.STATE_DOWNLOADING}->" +
            EpisodeDownloadEntity.STATE_ASSEMBLING)
        val completionIndex = events.indexOf(
            "COMPLETED expected=${EpisodeDownloadEntity.STATE_ASSEMBLING} rows=1",
        )

        assertTrue(
            "the entry claim (QUEUED -> DOWNLOADING) must be recorded, got: $events",
            claimIndex >= 0,
        )
        assertTrue(
            "H4: the run must persist DOWNLOADING -> ASSEMBLING exactly once, got: $events",
            events.count {
                it == "CAS ${EpisodeDownloadEntity.STATE_DOWNLOADING}->" +
                    EpisodeDownloadEntity.STATE_ASSEMBLING
            } == 1,
        )
        assertTrue(
            "H4: ASSEMBLING must be written AFTER the entry claim, got: $events",
            assemblingIndex > claimIndex,
        )
        assertTrue(
            "H4: completion must CAS from ASSEMBLING (the state this run persisted) " +
                "and the CAS must land, got: $events",
            completionIndex > assemblingIndex,
        )

        // ── 6. Observable Room state — the sequence really happened ──
        val after = dao.getById(episodeId)
        assertNotNull("row must exist", after)
        assertEquals(
            "the episode must end COMPLETED",
            EpisodeDownloadEntity.STATE_COMPLETED,
            after!!.state,
        )
        val outputPath = after.outputPath
        assertNotNull("outputPath must be recorded", outputPath)
        assertTrue("outputPath must not be blank", !outputPath.isNullOrBlank())

        val output = File(outputPath!!)
        assertTrue("the assembled MP4 must exist", output.exists())
        assertTrue("the assembled MP4 must be non-empty", output.length() > 0)
        val header = ByteArray(8)
        FileInputStream(output).use { it.read(header) }
        assertEquals("output must be a real MP4 (ftyp box)", "ftyp", String(header, 4, 4))

        // The mux really ran: no .ts artifacts may remain (cleanup executed).
        val children = File(Phase10TestSupport.segmentDirFor(context, episodeId)).listFiles()
        assertNotNull("segment dir must exist", children)
        assertTrue(
            "no .ts segments may remain after a successful mux",
            children!!.none { it.name.endsWith(".ts") },
        )

        // ── 7. Run ownership: every guarded write belonged to ONE run ──────
        // (pause/resume run-ownership fix) — the claim, the remuxing hook and
        // the completion must all carry the SAME non-blank token, and the row
        // must persist exactly that token.
        assertTrue(
            "at least the claim, the ASSEMBLING hook and the completion must " +
                "have been recorded, got: ${recorder.events}",
            recorder.runIds.size >= 3,
        )
        assertTrue(
            "every recorded run token must be a real non-blank value, got: ${recorder.runIds}",
            recorder.runIds.all { it.isNotBlank() },
        )
        assertEquals(
            "the claim, the remuxing hook and the completion must all carry the " +
                "SAME run token (one run owned the row end-to-end), got: ${recorder.runIds}",
            1,
            recorder.runIds.distinct().size,
        )
        assertEquals(
            "the persisted row must carry the run token that produced it",
            recorder.runIds.first(),
            after.downloadRunId,
        )
    }

    /**
     * Recording seam for H4: it only RECORDS the guarded CAS attempts and the
     * completion CAS (with the rows-affected value), delegating every write
     * unchanged. It never alters any state.
     *
     * Records through the run-owned statements (`claimEntryRunWithRunId`,
     * `updateStateIfOwned`, `updateCompletedIfOwned`) — the exact statements the
     * Worker's claim/remuxing-hook/completion issue since the pause/resume
     * run-ownership fix — and captures the run token each one carried, so the
     * test can prove all three writes belonged to ONE run.
     */
    private class AssemblyRecordingDao(
        private val delegate: EpisodeDownloadDao,
    ) : EpisodeDownloadDao by delegate {

        val events = CopyOnWriteArrayList<String>()
        val runIds = CopyOnWriteArrayList<String>()

        override suspend fun claimEntryRunWithRunId(
            episodeId: String,
            expectedState: String,
            runId: String,
            timestamp: Long,
        ): Int {
            val rows = delegate.claimEntryRunWithRunId(episodeId, expectedState, runId, timestamp)
            events.add("CAS $expectedState->${EpisodeDownloadEntity.STATE_DOWNLOADING}")
            runIds.add(runId)
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
            events.add("CAS $expectedState->$newState")
            runIds.add(runId)
            return rows
        }

        override suspend fun updateCompletedIfOwned(
            episodeId: String,
            expectedState: String,
            outputPath: String,
            runId: String,
            timestamp: Long,
        ): Int {
            val rows = delegate.updateCompletedIfOwned(episodeId, expectedState, outputPath, runId, timestamp)
            events.add("COMPLETED expected=$expectedState rows=$rows")
            runIds.add(runId)
            return rows
        }
    }
}

/**
 * In-process HLS source: serves one fixed playlist plus pre-generated segment
 * bytes. Mirrors the proven LocalHlsSource pattern from FullDownloadFlowTest,
 * scoped to this test (renamed to avoid clashing with the same helper in
 * TotalSegmentsPersistenceTest, same package). Test-only — never a production
 * implementation (production uses OkHttpHlsClient).
 */
private class AssemblyLocalHlsSource(
    private val playlistUrl: String,
    private val playlist: String,
    private val segments: Map<String, ByteArray>,
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
        return HlsBytesResult.Success(bytes)
    }
}
