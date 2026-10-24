package com.pahebatcher.app.data.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.hls.FileNameGenerator
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.DownloadSessionEntity
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * §3.1 (DECISIONS 157): the REAL MediaStore destination probe
 * ([MediaStoreExporter.destinationOutput]) against a real provider.
 *
 * `OutputDestinationProbeGateTest` pins the GATE's decision with a fake probe.
 * This suite pins the one thing a fake cannot: that the production probe answers
 * a live `MediaStore` query the same way the export writes to it — matching the
 * audio-suffixed leaf name under the per-anime `Movies/<title>/` directory, and
 * returning the surviving row's URI so the reconciler can re-point a severed
 * `COMPLETED` row onto it.
 *
 * Covered here: exact-name visible row (present + URI), a MediaStore-uniquified
 * ` (n)` sibling (present — §30.2 tolerance), an `IS_PENDING=1` crash-residue
 * row (absent — a visible file, not residue), and a different audio variant
 * (absent — the audio suffix isolates identities).
 *
 * NOT covered here, honestly: a foreign-owned row cannot be simulated from inside
 * the app (MediaProvider stamps `OWNER_PACKAGE_NAME` to our own package on
 * insert; another package's file requires a second installer). The owner-scoped
 * filter it relies on is shared with the export's own stale-row lookup.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreDestinationOutputTest {

    private lateinit var context: Context
    private lateinit var database: PaheDatabase
    private lateinit var contentResolver: ContentResolver
    private lateinit var exporter: MediaStoreExporter

    private val token = "pbprobe${System.nanoTime() % 1_000_000_000L}"
    private val localFiles = mutableListOf<File>()

    private val session = DownloadSessionEntity(
        sessionId = "sess-$token",
        title = "Probe Anime $token",
        animeSession = "anime-$token",
        host = "animepahe.com",
        quality = 2,
        audioLang = "jpn",
        outputDir = "/downloads",
        cacheDir = "/cache",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        state = "QUEUED",
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = PaheDatabase.getInstance(context)
        contentResolver = context.contentResolver
        exporter = MediaStoreExporter(
            contentResolver,
            database.episodeDownloadDao(),
            database.downloadSessionDao(),
        )
        runBlocking { database.downloadSessionDao().insertIfAbsent(session) }
    }

    @After
    fun teardown() {
        // Episode rows go with the session (FK cascade); visible rows + local
        // fixtures are ours and removed by token/path.
        runBlocking { database.downloadSessionDao().delete(session.sessionId) }
        for (row in rowsInDir()) {
            if (row.displayName.contains(token)) {
                contentResolver.delete(row.uri, null, null)
            }
        }
        for (file in localFiles) {
            file.setReadable(true, false)
            file.delete()
        }
    }

    // ------------------------------------------------------------------
    // Fixtures (same shape as MediaStoreExportTest's proven helpers)
    // ------------------------------------------------------------------

    /** The production probe's own expected leaf name for episode 1, [audio]. */
    private fun expectedName(title: String, audio: String = "jpn") = ExportFileNames.exportedName(
        FileNameGenerator.formatEpisodePrefix(1.0f),
        title,
        audio,
    )

    private fun pattern(bytes: Int) = ByteArray(bytes) { (it % 251).toByte() }

    private fun localFile(tag: String, bytes: Int): File =
        File(context.filesDir, "pbprobe_${System.nanoTime()}_$tag.mp4")
            .also {
                it.writeBytes(pattern(bytes))
                localFiles += it
            }

    private fun completedEpisode(title: String, outputPath: String, audio: String = "jpn") =
        EpisodeDownloadEntity(
            episodeId = "pbprobe-ep-${System.nanoTime()}-$audio",
            sessionId = session.sessionId,
            episodeNumber = 1.0f,
            episodeSession = "es-$token",
            title = title,
            fansub = "ProbeFansub",
            audio = audio,
            playUrl = "https://animepahe.com/play/${session.sessionId}/es-$token",
            state = EpisodeDownloadEntity.STATE_COMPLETED,
            outputPath = outputPath,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

    /** Insert a row directly at [relativePath] with [name] and [bytes] of content. */
    private fun insertRow(name: String, bytes: ByteArray, pending: Boolean = false): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("fixture insert must succeed", uri)
        contentResolver.openOutputStream(uri!!, "w")!!.use { it.write(bytes) }
        return uri
    }

    private fun relativePath() = OutputPathNames.mediaStoreRelativePath(session.title, session.sessionId)

    private data class Row(val uri: Uri, val displayName: String)

    private fun rowsInDir(): List<Row> {
        val out = mutableListOf<Row>()
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(relativePath()),
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                out += Row(
                    Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getString(idIdx)),
                    c.getString(nameIdx),
                )
            }
        }
        return out
    }

    private fun probe(title: String, audio: String = "jpn") = runBlocking {
        exporter.destinationOutput(
            episodeNumber = 1.0f,
            title = title,
            audio = audio,
            animeTitle = null, // the exporter resolves the session-row title itself
            sessionId = session.sessionId,
        )
    }

    // ------------------------------------------------------------------
    // Cases
    // ------------------------------------------------------------------

    @Test
    fun noExport_yet_isAbsent() {
        val title = "Probe Absent $token"
        assertNull("with no row in the directory the probe finds nothing", probe(title))
    }

    @Test
    fun visibleExactNameExport_isPresent_andReturnsItsUri() {
        val title = "Probe Visible $token"
        val local = localFile("visible", 4_096)
        val episode = completedEpisode(title, local.absolutePath)

        val exportedUri = runBlocking {
            when (val result = exporter.export(episode)) {
                is ExportOutcome.Exported -> result.uri
                is ExportOutcome.AlreadyExported -> result.uri
                is ExportOutcome.Failed -> throw AssertionError("fixture export failed: ${result.reason}")
            }
        }

        val found = probe(title)
        assertNotNull("a completed export is a live destination", found)
        assertEquals(
            "the probe returns THAT row's URI (what the reconciler re-points onto)",
            exportedUri,
            found.toString(),
        )
    }

    @Test
    fun mediaStoreUniquifiedSibling_stillMatches() {
        // §30.2 tolerance: after a Replace file (old kept until verified) MediaStore
        // can hand the new row a ` (n)` display name — the suffix goes BEFORE the
        // extension (`stem (1).mp4`), per the production nameMatches regex.
        val title = "Probe Uniquified $token"
        val base = expectedName(title)
        val uri = insertRow(base.removeSuffix(".mp4") + " (1).mp4", pattern(2_048))

        val found = probe(title)
        assertNotNull("a uniquified exact-sibling name is the same destination", found)
        assertEquals("and it is that row", uri.toString(), found.toString())
    }

    @Test
    fun pendingRow_isCrashResidue_notALiveDestination() {
        val title = "Probe Pending $token"
        insertRow(expectedName(title), pattern(2_048), pending = true)

        assertNull(
            "an IS_PENDING row is invisible crash residue, never a live destination",
            probe(title),
        )
    }

    @Test
    fun differentAudioVariant_isADifferentDestination() {
        // An English export must never satisfy a Japanese probe — the audio suffix
        // is what keeps two variants of one episode from colliding.
        val title = "Probe CrossAudio $token"
        insertRow(expectedName(title, audio = "eng"), pattern(2_048))

        assertNull("a jpn probe never matches an eng export", probe(title, audio = "jpn"))
        assertNotNull("while the eng probe does", probe(title, audio = "eng"))
    }
}
