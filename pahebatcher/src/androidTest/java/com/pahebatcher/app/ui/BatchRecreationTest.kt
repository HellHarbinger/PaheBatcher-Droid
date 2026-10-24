package com.pahebatcher.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Activity recreation keeps the batch
 * running with unchanged counters.
 *
 * The batch lives in the [AnimeDetailsViewModel] held by a
 * [ViewModelStore]-owned provider (production: nav-entry-scoped through
 * [com.pahebatcher.app.di.AppViewModelFactory]) — recreation of the HOST
 * activity must not touch it:
 *
 * 1. start a batch whose second resolution is held open (deterministic
 *    partial submission: 1 of 2 numbers queued);
 * 2. `ActivityScenario.recreate()` — the exact configuration-change path;
 * 3. the SAME ViewModel instance comes back (assertSame), the counters are
 *    byte-identical, and the loop still finishes with the second submission.
 *
 * Device-only by nature (real activity lifecycle + real dispatchers).
 */
@RunWith(AndroidJUnit4::class)
class BatchRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val resolver = ScriptedResolver()
    private val queue = RecordingQueue()

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AnimeDetailsViewModel::class.java)) {
                "unknown model $modelClass"
            }
            return AnimeDetailsViewModel(
                episodeRepository = EpisodeRepository(
                    animeRepository = AnimeRepository(ScriptedHttpClient()),
                    scanCacheRepository = ScanCacheRepository(EmptyScanCacheDao()),
                ),
                streamResolver = resolver,
                downloadQueue = queue,
                configProvider = { AppContext.defaults() },
            ) as T
        }
    }

    @Test
    fun activityRecreationKeepsTheBatchRunningWithUnchangedCounters() {
        lateinit var vm: AnimeDetailsViewModel

        composeRule.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity, factory)
                .get(BATCH_KEY, AnimeDetailsViewModel::class.java)
            runBlocking { vm.load(SESSION) }
        }
        awaitCondition("the fixture scan produced the listing") {
            vm.uiState.value.episodes.size == 3
        }
        composeRule.activityRule.scenario.onActivity {
            vm.setBatchMode(true)
            vm.selectAllEpisodes()
            vm.startBatchDownload()
        }

        awaitCondition("the first number is submitted") {
            vm.uiState.value.batchQueued == 1
        }
        val before = vm.uiState.value
        assertTrue("the batch is mid-run before recreation", before.batchRunning)

        // --- THE configuration change ---
        composeRule.activityRule.scenario.recreate()

        composeRule.activityRule.scenario.onActivity { activity ->
            val retained = ViewModelProvider(activity, factory)
                .get(BATCH_KEY, AnimeDetailsViewModel::class.java)
            assertSame("the ViewModel instance survives recreation", vm, retained)
        }

        val after = vm.uiState.value
        assertTrue("the batch is STILL running after recreation", after.batchRunning)
        assertEquals(
            "counters are unchanged by recreation",
            Triple(before.batchQueued, before.batchSkipped, before.batchFailed),
            Triple(after.batchQueued, after.batchSkipped, after.batchFailed),
        )
        assertEquals("no submission was lost or duplicated", 1, queue.submissions.size)

        // --- the loop keeps progressing past the recreation ---
        resolver.holdEp2 = false
        awaitCondition("the batch finishes after recreation") {
            val s = vm.uiState.value
            !s.batchRunning && s.batchFinished
        }

        val final = vm.uiState.value
        assertEquals(2, final.batchQueued)
        assertEquals(0, final.batchFailed)
        assertEquals(0, final.batchSkipped)
        assertEquals("one submission per number across the recreation", 2, queue.submissions.size)
    }

    private companion object {
        const val BATCH_KEY = "batch-t2"
        const val SESSION = "sess-batch"
    }
}
