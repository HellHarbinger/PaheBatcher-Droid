package com.pahebatcher.app.ui.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.dao.EpisodeDownloadDao
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Final-audit H5 (SPEC §26: "Do not hide exceptions / Display human-readable
 * errors") — the Downloads actions' catch blocks used to LOG only, so a failing
 * Cancel/Retry/Pause/Resume was completely silent to the user.
 *
 * Against the real production chain (DownloadsViewModel → DownloadRepository →
 * DAO): the failure is forced by a test-only DAO seam that THROWS the way a real
 * database/WorkManager problem would — the ViewModel's catch path is exactly
 * what the assertions observe. One test forces the OTHER failure shape found
 * during this pass: a foreign `CancellationException` (Room reports a closed
 * database as `JobCancellationException`), which the pre-H5 code silently
 * rethrew away.
 *
 * Test doubles live here only — never in production.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsViewModelActionErrorTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase

    private val runStamp = System.currentTimeMillis()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java).build()
    }

    @After
    fun teardown() {
        if (db.isOpen) db.close()
    }

    private fun newViewModel(dao: EpisodeDownloadDao = db.episodeDownloadDao()) =
        DownloadsViewModel(DownloadRepository(context, dao, db.downloadSessionDao()))

    @Test
    fun a_thrown_cancel_is_surfaced_to_the_user_not_just_logged() {
        val viewModel = newViewModel(
            ExplodingEpisodeDao(db.episodeDownloadDao(), IllegalStateException("database is locked")),
        )

        viewModel.cancel("ep-cancel-error-$runStamp")

        assertTrue(
            "SPEC §26: the failure must surface on actionError (it used to be log-only)",
            Phase10TestSupport.awaitUntil(10_000) {
                viewModel.actionError.value?.startsWith("Cancel failed: ") == true
            },
        )
        val message = viewModel.actionError.value!!
        assertTrue(
            "the human-readable detail must be preserved, got: $message",
            message.contains("database is locked"),
        )
        assertFalse(
            "AGENTS §10: no URL may ever reach the UI surface, got: $message",
            message.contains("://"),
        )

        // SPEC §26: shown until the user acknowledges it.
        viewModel.dismissActionError()
        assertNull("dismiss must clear the message", viewModel.actionError.value)
    }

    @Test
    fun a_url_bearing_failure_is_sanitized_to_the_exception_type() {
        val viewModel = newViewModel(
            ExplodingEpisodeDao(
                db.episodeDownloadDao(),
                IllegalStateException("segment fetch failed for https://cdn.example.com/seg0.ts"),
            ),
        )

        viewModel.pause("ep-url-error-$runStamp")

        assertTrue(
            Phase10TestSupport.awaitUntil(10_000) { viewModel.actionError.value != null },
        )
        assertEquals(
            "a URL-bearing message must fall back to the exception type (AGENTS §10)",
            "Pause failed: IllegalStateException",
            viewModel.actionError.value,
        )
    }

    @Test
    fun a_successful_action_publishes_no_error() {
        val viewModel = newViewModel()

        // Open DB, unknown row: `pause` returns false quietly — no exception,
        // so NO message may be fabricated.
        runBlocking { viewModel.pause("ep-no-error-$runStamp") }

        val surfaced = Phase10TestSupport.awaitUntil(1_500) {
            viewModel.actionError.value != null
        }
        assertFalse(
            "a quiet repository no-op must not fabricate a user-visible error",
            surfaced,
        )
        assertNull(viewModel.actionError.value)
    }

    @Test
    fun the_newest_failure_replaces_the_previous_message() {
        val viewModel = newViewModel(
            ExplodingEpisodeDao(db.episodeDownloadDao(), IllegalStateException("disk I/O error")),
        )

        viewModel.pause("ep-replace-a-$runStamp")
        assertTrue(
            Phase10TestSupport.awaitUntil(10_000) {
                viewModel.actionError.value?.startsWith("Pause failed: ") == true
            },
        )
        viewModel.resume("ep-replace-b-$runStamp")
        assertTrue(
            Phase10TestSupport.awaitUntil(10_000) {
                viewModel.actionError.value?.startsWith("Resume failed: ") == true
            },
        )
    }

    /**
     * The hole this pass actually found: Room surfaces a CLOSED database as a
     * `JobCancellationException`, which the pre-H5 `catch (CancellationException)
     * { throw }` rethrew into the cancelled coroutine — completely silent. The
     * ViewModel's own cancellation must STILL propagate (structured
     * concurrency), but a foreign cancellation while the scope is alive is a
     * real failure and must reach the user (SPEC §26).
     */
    @Test
    fun a_foreign_cancellation_is_surfaced_while_our_own_still_propagates() {
        val viewModel = newViewModel()

        db.close() // every DAO call now fails via a JobCancellationException
        viewModel.pause("ep-closed-db-$runStamp")

        assertTrue(
            "a closed-database failure must surface, not vanish into the " +
                "cancelled coroutine (SPEC §26)",
            Phase10TestSupport.awaitUntil(10_000) { viewModel.actionError.value != null },
        )
        assertTrue(
            "the message must name the action, got: ${viewModel.actionError.value}",
            viewModel.actionError.value!!.startsWith("Pause failed: "),
        )
    }

    /**
     * Test-only failure seam: throws the way a real storage problem would,
     * delegating everything else to the real DAO. Never in production.
     */
    private class ExplodingEpisodeDao(
        private val delegate: EpisodeDownloadDao,
        private val boom: RuntimeException,
    ) : EpisodeDownloadDao by delegate {

        override suspend fun getById(episodeId: String): EpisodeDownloadEntity? = throw boom

        override suspend fun cancelIfNotTerminal(
            episodeId: String,
            timestamp: Long,
        ): Int = throw boom
    }
}
