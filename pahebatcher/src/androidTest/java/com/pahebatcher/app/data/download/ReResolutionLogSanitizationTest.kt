package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.domain.stream.StreamResolutionException
import com.pahebatcher.app.logging.AppLog
import com.pahebatcher.app.logging.LogLevel
import com.pahebatcher.app.support.Phase10TestSupport
import com.pahebatcher.app.support.RecordStreamResolver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finding B of the final closure round (DECISIONS 138): the release-emitted
 * ERROR line in `DownloadRepository.reEnqueue`'s failure catch used to
 * interpolate `e.message` raw, so ANY exception from the resolution flow could
 * put a complete stream/Kwik URL — query included — onto Logcat.
 *
 * Drives the REAL failure path: the real `DownloadRepository` against the real
 * Room database, a resolver test double that fails exactly like production
 * does (input modelling only — the log line itself is production code, not a
 * test seam). The assertion runs on `AppLog.buffer`, which is fed by the same
 * call that feeds Logcat, so a regression that re-inlines `${e.message}` fails
 * here instead of leaking in production.
 *
 * AGENTS §10 / DECISIONS 136.
 */
@RunWith(AndroidJUnit4::class)
class ReResolutionLogSanitizationTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-logsan-$runStamp"
    private val episodeId = "${sessionId}_1_${EpisodeInfo.AUDIO_JAPANESE}"

    private var bufferingWasEnabled = false

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        Phase10TestSupport.insertSession(db, sessionId)
        bufferingWasEnabled = AppLog.isBufferingEnabled
        AppLog.setBufferingEnabled(true)
        AppLog.buffer.clear()
    }

    @After
    fun teardown() {
        AppLog.setBufferingEnabled(bufferingWasEnabled)
        AppLog.buffer.clear()
        File(context.filesDir, "downloads/$episodeId").deleteRecursively()
        Phase10TestSupport.deleteSession(db, sessionId)
    }

    @Test
    fun `the real re-resolution ERROR line never carries the Kwik URL query`() = runBlocking {
        val dao = db.episodeDownloadDao()
        val failing = RecordStreamResolver {
            throw StreamResolutionException(
                "Could not resolve Kwik URL: https://kwik.si/e/abc?token=SUPERSECRET&expires=1793529600",
            )
        }
        dao.upsert(
            EpisodeDownloadEntity(
                episodeId = episodeId,
                sessionId = sessionId,
                episodeNumber = 1f,
                episodeSession = "eps-logsan-1",
                title = "LogSan Episode 1",
                fansub = "LogSanFansub",
                audio = EpisodeInfo.AUDIO_JAPANESE,
                playUrl = "https://animepahe.com/play/$sessionId/eps-logsan-1",
                state = EpisodeDownloadEntity.STATE_FAILED,
                createdAt = runStamp,
                updatedAt = runStamp,
            ).copy(
                m3u8Url = "https://cdn.example.com/stale.m3u8?token=STALESECRET",
                errorMessage = "older error",
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId),
            ),
        )

        DownloadRepository(
            context,
            dao,
            db.downloadSessionDao(),
            streamResolver = failing,
        ).reEnqueue(episodeId)

        // The production failure behaviour itself is untouched by the fix.
        val row = dao.getById(episodeId)
        assertEquals(EpisodeDownloadEntity.STATE_FAILED, row?.state)
        assertEquals(1, row?.retryCount)

        // The observable artifact: the real ERROR line this catch emitted.
        val line = AppLog.buffer.entries.value.lastOrNull {
            it.level == LogLevel.ERROR && it.message.startsWith("Re-resolution failed for")
        }
        assertNotNull("the re-resolution failure must have logged at ERROR", line)
        val message = line!!.message

        // Useful diagnosis survives: WHICH Kwik page failed.
        assertTrue("host+path must survive for diagnosis: $message", message.contains("kwik.si/e/abc"))

        // …and nothing that authenticates does.
        assertFalse("no scheme-complete URL: $message", message.contains("https://"))
        assertFalse("no query marker: $message", message.contains("?"))
        assertFalse("no query parameter: $message", message.contains("token="))
        assertFalse("no token value: $message", message.contains("SUPERSECRET"))
        assertFalse("no expiry: $message", message.contains("expires"))
        assertFalse("no expiry value: $message", message.contains("1793529600"))

        // Nothing anywhere in the retained sink — any tag, message or rendered
        // throwable — may carry either secret from this episode's history.
        listOf("SUPERSECRET", "STALESECRET").forEach { secret ->
            val leaked = AppLog.buffer.entries.value.filter {
                it.message.contains(secret) || it.throwable.orEmpty().contains(secret)
            }
            assertTrue("$secret must never reach a log sink (leaked in $leaked)", leaked.isEmpty())
        }
    }
}
