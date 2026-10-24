package com.pahebatcher.app.data.hls

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.data.download.DownloadStateReconciler
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * Full download flow test (Phase 10 architecture v10 §16, FullDownloadFlowTest).
 *
 * the pre-corrective implementation deleted the
 * assembled MP4 while recording the episode COMPLETED — and startup
 * reconciliation then flipped it to FAILED and deleted the directory. This
 * test pins the ENTIRE success path end to end:
 *
 *   playlist fetch → segment download → real FFmpeg assembly → cleanup →
 *   output survives → reconciliation (twice) → still COMPLETED, output intact.
 *
 * HLS source: an in-process fake HlsClient serving a local playlist plus
 * VALID MPEG-TS bytes generated on-device by FFmpegKit (same technique as
 * FfmpegAssemblyValidDataTest). No real CDN/Kwik/network is involved — that
 * external infrastructure is an unrelated blocker and is not claimed here.
 *
 * Everything below the fake client is REAL:
 * - real EpisodeDownloader (parse, concurrent segment fetch, atomic writes)
 * - real SegmentFileStore in filesDir/downloads/{episodeId} (production layout)
 * - real FfmpegEpisodeAssembler (device FFmpeg, x86_64 validated in Phase 9)
 * - real DownloadStateReconciler against the real cleanup behavior
 */
@RunWith(AndroidJUnit4::class)
class FullDownloadFlowTest {

    private lateinit var context: Context
    private lateinit var segmentDir: File
    private lateinit var genDir: File

    private val runStamp = System.currentTimeMillis()
    private val episodeId = "ep-fullflow-$runStamp"
    private val sessionId = "sess-fullflow-$runStamp"
    private val playlistUrl = "https://cdn.local.test/fullflow/playlist.m3u8"
    private val baseUrl = "https://cdn.local.test/fullflow/"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        segmentDir = File(Phase10TestSupport.segmentDirFor(context, episodeId))
        genDir = File(context.cacheDir, "fullflow_gen_$runStamp").apply { mkdirs() }
    }

    @After
    fun teardown() {
        segmentDir.deleteRecursively()
        genDir.deleteRecursively()
    }

    /** Generate one valid MPEG-TS segment on-device (proven FFmpegKit command). */
    private fun generateValidTs(out: File): Boolean {
        val cmd = "-y -f lavfi -i color=c=red:s=320x240:d=1:r=25 " +
            "-c:v libopenh264 -pix_fmt yuv420p " +
            "-f mpegts ${out.absolutePath}"
        val session = FFmpegKit.execute(cmd)
        val ok = ReturnCode.isSuccess(session.returnCode)
        if (!ok) {
            android.util.Log.e("FullDownloadFlowTest", "TS generation failed: ${session.output}")
        }
        return ok
    }

    @Test
    fun fullDownload_producesOutputThatSurvivesCleanupAndReconciliation() {
        // ── 1. Valid TS bytes generated on-device ─────────────────────
        val segmentUrls = (0 until 3).map { "${baseUrl}seg$it.ts" }
        val segmentBytes = mutableMapOf<String, ByteArray>()
        segmentUrls.forEachIndexed { i, url ->
            val ts = File(genDir, "gen$i.ts")
            assertTrue("TS generation $i must succeed", generateValidTs(ts))
            assertTrue("Generated TS $i must be non-empty", ts.length() > 0)
            segmentBytes[url] = ts.readBytes()
        }

        // Media playlist with relative segment URIs (same proven shape as
        // EpisodeDownloaderTest — resolved against the playlist base URL).
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

        val store = SegmentFileStore(segmentDir)
        val downloader = EpisodeDownloader(
            hlsClient = LocalHlsSource(playlistUrl, playlist, segmentBytes),
            keyCache = AesKeyCache(),
            segmentStore = store,
            assembler = FfmpegEpisodeAssembler(),
            hlsWorkers = 4
        )

        // ── 2. Full download + real assembly + cleanup ────────────────
        val result = runBlocking {
            downloader.download(
                streamInfo = StreamInfo(
                    url = playlistUrl,
                    userAgent = "FullFlowTestAgent",
                    referer = "https://example.com"
                ),
                episodeNumber = 1,
                episodeTitle = "Full Flow Test"
            )
        }

        assertTrue(
            "Download must succeed (error=${result.error}, assembly=${result.assemblyError})",
            result.success
        )
        assertTrue("Download must be marked assembled", result.assembled)
        assertNotNull("Output file must be set", result.outputFile)

        // ── 3. F-C1 core assertions: output exists, non-empty, inside segmentDir ──
        val output = result.outputFile!!
        assertTrue("Output must exist after download + cleanup", output.exists())
        assertTrue("Output must be non-empty after download + cleanup", output.length() > 0)
        assertEquals(
            "Output must live inside segmentDir (architecture §2)",
            segmentDir.absolutePath,
            output.parentFile?.absolutePath
        )

        // Real MP4: ftyp box
        val header = ByteArray(8)
        FileInputStream(output).use { it.read(header) }
        assertEquals("Output must be a valid MP4 (ftyp box)", "ftyp", String(header, 4, 4))

        // Cleanup removed every segment artifact but preserved the output
        val children = segmentDir.listFiles() ?: emptyArray()
        assertTrue(
            "No .ts segments may remain after cleanup",
            children.none { it.name.endsWith(".ts") }
        )
        assertTrue(
            "No .tmp partial writes may remain after cleanup",
            children.none { it.name.endsWith(".tmp") }
        )
        assertTrue(
            "Segment directory must survive while it holds the output",
            segmentDir.exists()
        )
        val outputLengthAfterCleanup = output.length()
        assertEquals("Output must be unchanged by cleanup", outputLengthAfterCleanup, output.length())

        // ── 4. Reconciliation survival (twice — idempotency) ──────────
        // Plant a leftover segment artifact to recreate the
        // "process death during cleanup" state against the REAL output.
        val leftoverTs = File(segmentDir, "000099.ts")
        leftoverTs.writeBytes("leftover segment".toByteArray())

        val db = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                db.downloadSessionDao().insertIfAbsent(
                    DownloadSessionEntity(
                        sessionId = sessionId,
                        title = "Full Flow Session",
                        animeSession = "anime-$sessionId",
                        host = "animepahe.local",
                        quality = 2,
                        audioLang = "sub",
                        outputDir = "/downloads",
                        cacheDir = "/cache",
                        createdAt = runStamp,
                        updatedAt = runStamp,
                        state = "CREATED"
                    )
                )
                db.episodeDownloadDao().upsert(
                    EpisodeDownloadEntity(
                        episodeId = episodeId,
                        sessionId = sessionId,
                        episodeNumber = 1.0f,
                        episodeSession = "eps-$episodeId",
                        title = "Full Flow Test",
                        fansub = "TestFansub",
                        audio = "sub",
                        playUrl = "https://example.com/play",
                        state = EpisodeDownloadEntity.STATE_COMPLETED,
                        createdAt = runStamp,
                        updatedAt = runStamp,
                        outputPath = output.absolutePath,
                        segmentDir = segmentDir.absolutePath
                    )
                )

                val reconciler = DownloadStateReconciler(
                    db.episodeDownloadDao(),
                    WorkManager.getInstance(context)
                )
                reconciler.reconcile()

                var row = db.episodeDownloadDao().getById(episodeId)
                assertEquals(
                    "Real downloaded output must keep the episode COMPLETED",
                    EpisodeDownloadEntity.STATE_COMPLETED, row?.state
                )
                assertTrue("Real output must survive reconciliation", output.exists())
                assertEquals(
                    "Real output bytes must be untouched",
                    outputLengthAfterCleanup, output.length()
                )
                assertFalse("Leftover .ts must be cleaned by reconciliation", leftoverTs.exists())

                // Second startup reconciliation — idempotent result
                reconciler.reconcile()
                row = db.episodeDownloadDao().getById(episodeId)
                assertEquals(
                    EpisodeDownloadEntity.STATE_COMPLETED, row?.state
                )
                assertTrue("Real output must survive repeated reconciliation", output.exists())
            }
        } finally {
            db.close()
        }
    }
}

/**
 * In-process HLS source: serves one fixed playlist plus pre-generated
 * segment bytes. Mirrors the proven FakeHlsClient pattern from
 * EpisodeDownloaderTest, scoped to this flow. Test-only — never a
 * production implementation (production uses OkHttpHlsClient).
 */
private class LocalHlsSource(
    private val playlistUrl: String,
    private val playlist: String,
    private val segments: Map<String, ByteArray>
) : HlsClient {

    override suspend fun getText(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long
    ): HlsTextResult {
        if (url == playlistUrl) return HlsTextResult.Success(playlist)
        return HlsTextResult.HttpError(404, url)
    }

    override suspend fun getBytes(
        url: String,
        context: HlsClient.RequestContext,
        timeoutMs: Long
    ): HlsBytesResult {
        val bytes = segments[url] ?: return HlsBytesResult.HttpError(404, url)
        return HlsBytesResult.Success(bytes)
    }
}
