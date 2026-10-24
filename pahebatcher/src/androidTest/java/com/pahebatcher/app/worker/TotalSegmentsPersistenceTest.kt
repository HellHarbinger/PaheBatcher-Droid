package com.pahebatcher.app.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.pahebatcher.app.data.download.DownloadConfig
import com.pahebatcher.app.data.hls.AesKeyCache
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import com.pahebatcher.app.domain.hls.HlsTextResult
import com.pahebatcher.app.notification.DownloadNotificationManager
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.ui.mapper.DownloadRowMapper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * §36-2 — totalSegments persistence boundary (PHASE11_ARCHITECTURE_V2 §16.2).
 *
 * Runs the REAL DownloadWorker (constructed through TestListenableWorkerBuilder
 * with an injected in-process HLS source — the only substituted layer) against a
 * real Room database and asserts the full six-assertion contract:
 *
 *  1. a manifest-derived totalSegments > 0 is READ BACK from Room
 *  2. the persisted completedSegments is READ BACK from Room
 *  3. the mapper computes the expected percentage from THOSE two persisted values
 *  4. totalSegments = 0 (unknown) is safely handled — no percentage, no crash
 *  5. the percentage is a pure function of the Room row (no fabricated denominator)
 *  6. the manifest-derived total REACHES PERSISTENCE — the Worker issues the
 *     one-time updateTotalSegments write after the HLS playlist parse
 *
 * This pins the §16.2 write path: manifest parse → DownloadWorker →
 * dao.updateTotalSegments (write-on-change), verified end to end. The HLS source
 * is a local fake (no CDN/Kwik/network is claimed — external blocker unchanged).
 */
@RunWith(AndroidJUnit4::class)
class TotalSegmentsPersistenceTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-totalseg-$runStamp"
    private val episodeId = "ep-totalseg-$runStamp"
    private val playlistUrl = "https://cdn.local.test/totalseg/playlist.m3u8"
    private val baseUrl = "https://cdn.local.test/totalseg/"

    /** Playlist resolves to this many segments — the manifest-derived denominator. */
    private val manifestSegmentCount = 4

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        runBlocking {
            db.episodeDownloadDao().delete(episodeId)
            db.downloadSessionDao().delete(sessionId)
        }
        File(Phase10TestSupport.segmentDirFor(context, episodeId)).deleteRecursively()
    }

    @Test
    fun manifestDerivedTotal_reachesRoom_persistenceBoundary_sixAssertions() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val now = System.currentTimeMillis()

            // Seed a QUEUED episode with persisted stream info (post-resolution shape)
            dao.upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = sessionId,
                    episodeNumber = 1.0f,
                    episodeSession = "eps-$episodeId",
                    title = "Total Segments Episode",
                    fansub = "TestFansub",
                    audio = "sub",
                    playUrl = "https://example.com/play",
                    // §16.1: column exists but starts at 0 (unknown) — the exact
                    // production precondition this write path exists to fix.
                    totalSegments = 0,
                    state = EpisodeDownloadEntity.STATE_QUEUED,
                    createdAt = now,
                    updatedAt = now,
                    m3u8Url = playlistUrl,
                    userAgent = "TotalSegTestAgent",
                    referer = "https://example.com",
                    segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId)
                )
            )

            // 3 of 4 segments are served; segment 3 → 404 (permanent, fast failure)
            val hls = LocalHlsSource(
                playlistUrl = playlistUrl,
                playlist = mediaPlaylist(),
                segments = mapOf(
                    "${baseUrl}seg0.ts" to "segment0data".toByteArray(),
                    "${baseUrl}seg1.ts" to "segment1data".toByteArray(),
                    "${baseUrl}seg2.ts" to "segment2data".toByteArray(),
                    // seg3.ts deliberately absent → HTTP 404
                )
            )

            // Real DownloadWorker — only the HLS source is injected
            val worker = TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
                .setInputData(workDataOf("episode_id" to episodeId))
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters
                    ): ListenableWorker = DownloadWorker(
                        context = appContext,
                        params = workerParameters,
                        dao = db.episodeDownloadDao(),
                        notificationManager = DownloadNotificationManager(appContext),
                        hlsClient = hls,
                        keyCache = AesKeyCache(),
                        config = DownloadConfig()
                    )
                })
                .build()

            // Run the worker to completion (segment 404 → permanent failure path)
            val result = worker.startWork().get(60, TimeUnit.SECONDS)
            assertNotNull("Worker must produce a result", result)

            // ── Read back the persisted row from Room ────────────────────────
            val row = dao.getById(episodeId)
            assertNotNull("Row must exist after worker run", row)

            // (1) + (6) manifest-derived total REACHES PERSISTENCE:
            //     totalSegments was 0 before the run and must now equal the
            //     segment count of the parsed manifest — proving the Worker
            //     issued the one-time updateTotalSegments write after HLS parse.
            assertEquals(
                "Manifest-derived total must be persisted (write-on-change reached Room)",
                manifestSegmentCount, row!!.totalSegments
            )
            assertTrue("Persisted denominator must be > 0 (known)", row.totalSegments > 0)

            // (2) persisted completedSegments read back from Room
            //     (3 segments were served successfully before seg3 404'd)
            assertEquals(
                "Persisted completedSegments must equal the 3 written segments",
                3, row.completedSegments
            )

            // (3) mapper computes the expected percentage from THOSE two
            //     persisted values (3/4 = 75%) — no other inputs involved
            val ui = DownloadRowMapper.map(row)
            assertEquals(4, ui.totalSegments)
            assertEquals(3, ui.completedSegments)
            assertEquals("Percentage must derive from the two persisted values", 75, ui.progressPercent)

            // (4) totalSegments = 0 (unknown) → safely handled: null, no crash
            val unknownTotal = row.copy(totalSegments = 0, completedSegments = 0)
            val unknownUi = DownloadRowMapper.map(unknownTotal)
            assertNull("totalSegments=0 must yield NO percentage", unknownUi.progressPercent)
            assertNull("totalSegments=0 must expose a null denominator", unknownUi.totalSegments)
            assertEquals(0, unknownUi.completedSegments)

            // (5) no fabricated denominator — percentage is a pure function of
            //     the Room row (same row → same result, untouched inputs)
            assertEquals(
                ui.progressPercent,
                DownloadRowMapper.progressPercentOrNull(row)
            )
            assertEquals(
                ui.progressPercent,
                DownloadRowMapper.progressPercentOrNull(row.copy())
            )
        }
    }

    @Test
    fun updateTotalSegments_writeOnChangeGuard_onlyWritesWhenValueDiffers() {
        runBlocking {
            val dao = db.episodeDownloadDao()
            val now = System.currentTimeMillis()
            dao.upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = sessionId,
                    episodeNumber = 2.0f,
                    episodeSession = "eps2-$episodeId",
                    title = "Write On Change Episode",
                    fansub = "TestFansub",
                    audio = "sub",
                    playUrl = "https://example.com/play",
                    totalSegments = 0,
                    state = EpisodeDownloadEntity.STATE_DOWNLOADING,
                    createdAt = now,
                    updatedAt = now
                )
            )

            // First write: 0 → 4 (the manifest-resolution write) — lands
            dao.updateTotalSegments(episodeId, 4)
            val afterFirst = dao.getById(episodeId)
            assertEquals(4, afterFirst!!.totalSegments)
            val updatedAtAfterFirst = afterFirst.updatedAt

            // Same value again: guarded no-op (write only on change, §16.2 rule 2)
            dao.updateTotalSegments(episodeId, 4)
            val afterSecond = dao.getById(episodeId)
            assertEquals(4, afterSecond!!.totalSegments)
            assertEquals(
                "updateTotalSegments must be a no-op when the value is unchanged",
                updatedAtAfterFirst, afterSecond.updatedAt
            )

            // A genuinely different re-resolution (retry re-parses a changed
            // playlist) may rewrite once more under the same rule
            dao.updateTotalSegments(episodeId, 5)
            assertEquals(5, dao.getById(episodeId)!!.totalSegments)
        }
    }

    private fun mediaPlaylist(): String = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:10.0,
        seg0.ts
        #EXTINF:10.0,
        seg1.ts
        #EXTINF:10.0,
        seg2.ts
        #EXTINF:10.0,
        seg3.ts
        #EXT-X-ENDLIST
    """.trimIndent()
}

/**
 * In-process HLS source: serves one fixed media playlist plus pre-provided
 * segment bytes; any URL not in [segments] returns HTTP 404 (permanent).
 * Test-only — never a production implementation (production uses OkHttpHlsClient).
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
