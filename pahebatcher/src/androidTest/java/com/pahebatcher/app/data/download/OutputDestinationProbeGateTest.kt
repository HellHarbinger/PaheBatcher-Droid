package com.pahebatcher.app.data.download

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.pahebatcher.app.data.export.EpisodeExporter
import com.pahebatcher.app.data.export.ExportOutcome
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.domain.download.EnqueueOutcome
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.download.ResolvedEpisodeSubmission
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §3.1 (DECISIONS 157): the submission gate is DESTINATION-aware, not only
 * row-aware.
 *
 * A re-download over an export that still survives in the destination — while the
 * row is gone (app-private state destroyed) or its output pointers are severed —
 * must be the SAME "already downloaded" no-op as a COMPLETED row with a present
 * output. Otherwise the re-download runs and the exporter adopts the surviving
 * file: wasted work whose only observable result already existed.
 *
 * These cases drive the gate with a FAKE [EpisodeExporter] injected as
 * `destinationProbe`, so the GATE's decision is asserted deterministically
 * without a real MediaStore file, and the exact arguments the gate passes to
 * the probe are pinned (a swapped title/audio/number would silently miss every
 * real destination and re-enable the duplicate-file defect while all fake-based
 * tests stayed green). The real MediaStore implementation of the probe
 * (`MediaStoreExporter.destinationOutput`) is exercised against a real provider
 * by `MediaStoreDestinationOutputTest`.
 */
@RunWith(AndroidJUnit4::class)
class OutputDestinationProbeGateTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager

    private val sessionId = "sess-dest-probe"

    /** One recorded call into the destination probe. */
    private data class ProbeCall(
        val episodeNumber: Float,
        val title: String,
        val audio: String,
        val animeTitle: String?,
        val sessionId: String,
    )

    /**
     * A destination probe that always answers [resultUri] (null = no destination)
     * and records every call's arguments. [throwOnProbe] exercises the
     * "cannot be probed → admit" contract: a probe failure must never block a
     * legitimate submission (review finding 2).
     */
    private class FakeDestinationProbe(
        private val resultUri: Uri?,
        private val throwOnProbe: Boolean = false,
    ) : EpisodeExporter {
        val calls = mutableListOf<ProbeCall>()

        override suspend fun export(episode: EpisodeDownloadEntity): ExportOutcome =
            throw UnsupportedOperationException("the gate never exports")

        override suspend fun sweepStalePending(olderThanEpochMs: Long): Int = 0

        override suspend fun destinationOutput(
            episodeNumber: Float,
            title: String,
            audio: String,
            animeTitle: String?,
            sessionId: String,
        ): Uri? {
            calls += ProbeCall(episodeNumber, title, audio, animeTitle, sessionId)
            if (throwOnProbe) throw SecurityException("simulated provider failure")
            return resultUri
        }
    }

    private fun repositoryWith(probe: FakeDestinationProbe) = DownloadRepository(
        context,
        db.episodeDownloadDao(),
        db.downloadSessionDao(),
        destinationProbe = probe,
    )

    private fun submission(number: Int) = ResolvedEpisodeSubmission(
        animeSession = sessionId,
        animeTitle = "Destination Probe Anime",
        host = "animepahe.com",
        quality = 1080,
        episode = EpisodeInfo(
            number = number.toFloat(),
            session = "eps-dest-$number",
            title = "",
            fansub = "",
            audio = EpisodeInfo.AUDIO_JAPANESE,
            playUrl = "https://animepahe.com/play/$sessionId/eps-dest-$number",
        ),
        stream = StreamInfo(
            url = "https://cdn.example.invalid/$number.m3u8",
            userAgent = "FreshUA",
            referer = "https://kwik.cx/e/abc",
            title = "Destination Probe Anime Ep. $number :: animepahe",
        ),
    )

    /** The row id the gate itself will derive for this submission (shared [EpisodeId.build]). */
    private fun episodeId(number: Int): String =
        EpisodeId.build(sessionId, submission(number).episode)

    private fun workInfos(number: Int) =
        workManager.getWorkInfosForUniqueWork("episode_download_${episodeId(number)}").get()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        (1..3).forEach { workManager.cancelUniqueWork("episode_download_${episodeId(it)}") }
        runBlocking {
            db.episodeDownloadDao().delete(episodeId(1))
            db.episodeDownloadDao().delete(episodeId(2))
            db.episodeDownloadDao().delete(episodeId(3))
        }
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    @Test
    fun rowAbsent_butDestinationSurvives_isSuppressed_andNothingIsCreated() = runBlocking {
        val probe = FakeDestinationProbe(resultUri = Uri.parse("content://media/external/video/media/424242"))
        val repository = repositoryWith(probe)

        val outcome = repository.queueResolvedEpisode(submission(1))

        assertEquals(
            "a surviving destination with no row is the same no-op as a present output",
            EnqueueOutcome.AlreadyDownloaded,
            outcome,
        )
        assertTrue(
            "no row is created for a suppressed submission",
            db.episodeDownloadDao().getById(episodeId(1)) == null,
        )
        assertTrue(
            "and no worker is enqueued (finished historical entries for the " +
                "deterministic unique-work name are ignored; a live ENQUEUED/RUNNING " +
                "worker would still be waiting here)",
            workInfos(1).none { !it.state.isFinished },
        )
        // Review finding 7: pin the ARGUMENTS the gate passed, not just the answer.
        assertEquals("exactly one probe call", 1, probe.calls.size)
        val call = probe.calls.single()
        assertEquals("episode number as Float", 1f, call.episodeNumber)
        assertEquals(
            "the DOWNLOAD title (stream-title fallback), never the anime title",
            "Destination Probe Anime Ep. 1 :: animepahe",
            call.title,
        )
        assertEquals("audio discriminator", EpisodeInfo.AUDIO_JAPANESE, call.audio)
        assertEquals(
            "the session row's title (the directory identity the export writes to)",
            "Phase10 Test Session",
            call.animeTitle,
        )
        assertEquals("the episode's own session", sessionId, call.sessionId)
    }

    @Test
    fun completedRow_severedPointers_butDestinationSurvives_isSuppressed() = runBlocking {
        val probe = FakeDestinationProbe(resultUri = Uri.parse("content://media/external/video/media/434343"))
        val repository = repositoryWith(probe)
        // A COMPLETED row whose `outputUri` points at nothing (severed) and has no
        // local `outputPath` — so the ROW cannot prove an output, but the probe says
        // a destination file still lives.
        db.episodeDownloadDao().upsert(
            EpisodeDownloadEntity(
                episodeId = episodeId(2),
                sessionId = sessionId,
                episodeNumber = 2f,
                episodeSession = "eps-dest-2",
                title = "Destination Probe Episode 2",
                fansub = "TestFansub",
                audio = EpisodeInfo.AUDIO_JAPANESE,
                playUrl = "https://animepahe.com/play/$sessionId/eps-dest-2",
                state = EpisodeDownloadEntity.STATE_COMPLETED,
                outputUri = "content://media/external/video/media/999999999",
                outputPath = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )

        val outcome = repository.queueResolvedEpisode(submission(2))

        assertEquals(
            "a severed row over a surviving destination is still 'already downloaded'",
            EnqueueOutcome.AlreadyDownloaded,
            outcome,
        )
        assertTrue("and no worker is enqueued", workInfos(2).isEmpty())
        // Review finding 7: call site 2 probes with the EXISTING ROW's identity.
        assertEquals("exactly one probe call", 1, probe.calls.size)
        val call = probe.calls.single()
        assertEquals(2f, call.episodeNumber)
        assertEquals(
            "the persisted row's own title",
            "Destination Probe Episode 2",
            call.title,
        )
        assertEquals(EpisodeInfo.AUDIO_JAPANESE, call.audio)
        assertEquals(
            "session row title (fallback null is only used when the session row is gone)",
            "Phase10 Test Session",
            call.animeTitle,
        )
        assertEquals(sessionId, call.sessionId)
    }

    @Test
    fun rowAbsent_andDestinationGone_proceeds_withAFreshDownload() = runBlocking {
        val probe = FakeDestinationProbe(resultUri = null)
        val repository = repositoryWith(probe)

        val outcome = repository.queueResolvedEpisode(submission(3))

        // Review finding 8: assert the STRONGEST honest outcome, not merely
        // "not AlreadyDownloaded" — a hypothetical bug returning Refused (which
        // queues nothing) must not pass this test.
        assertEquals(
            "with no surviving destination the re-download is admitted",
            EnqueueOutcome.Accepted,
            outcome,
        )
        assertNotNull(
            "the admitted submission created its row",
            db.episodeDownloadDao().getById(episodeId(3)),
        )
        assertTrue(
            "and registered its worker",
            workInfos(3).isNotEmpty(),
        )
    }

    @Test
    fun throwingProbe_neverBlocks_aLegitimateSubmission() = runBlocking {
        // Review finding 2: the probe contract is "cannot be probed → admit".
        // An exotic provider failure must surface as a normal admission, never as
        // an exception escaping queueResolvedEpisode.
        val probe = FakeDestinationProbe(resultUri = null, throwOnProbe = true)
        val repository = repositoryWith(probe)

        val outcome = repository.queueResolvedEpisode(submission(1))

        assertEquals(
            "a probe that cannot answer admits, exactly like 'no destination'",
            EnqueueOutcome.Accepted,
            outcome,
        )
        assertNotNull(
            "the admitted submission created its row",
            db.episodeDownloadDao().getById(episodeId(1)),
        )
        assertTrue(
            "and registered its worker",
            workInfos(1).isNotEmpty(),
        )
    }
}
