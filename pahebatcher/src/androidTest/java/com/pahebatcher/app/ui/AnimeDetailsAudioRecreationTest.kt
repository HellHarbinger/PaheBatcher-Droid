package com.pahebatcher.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Option C on DEVICE (SPEC §22 / DECISIONS 143, AGENTS §9) — a normal Activity
 * recreation must not disturb the Anime Details audio state: the screen-level
 * context, the explicit per-episode overrides and the selected numbers are
 * ViewModel-owned state (no SavedStateHandle), so the same instance carries
 * them across the configuration change exactly like Phase 13's batch counters
 * ([BatchRecreationTest]).
 *
 * The test additionally proves the RETAINED state still drives execution: the
 * batch started after recreation resolves every number through the derived
 * mapping frozen from the surviving context + override.
 */
@RunWith(AndroidJUnit4::class)
class AnimeDetailsAudioRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val resolver = ScriptedResolver().apply { holdEp2 = false }
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
    fun contextOverridesAndSelectionSurviveActivityRecreationAndStillDriveExecution() {
        lateinit var vm: AnimeDetailsViewModel

        composeRule.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity, factory)
                .get(AUDIO_KEY, AnimeDetailsViewModel::class.java)
            runBlocking { vm.load(SESSION) }
        }
        awaitCondition("the fixture scan produced the listing") {
            vm.uiState.value.episodes.size == 3
        }
        composeRule.activityRule.scenario.onActivity {
            // The deterministic initial context (jpn-if-any) on a device run…
            assertEquals("jpn", vm.uiState.value.selectedAudio)
            vm.setBatchMode(true)
            vm.selectAllEpisodes() // {1, 2} in the jpn context
            vm.setEpisodeAudio(1f, "eng") // …plus one EXPLICIT override
        }
        val before = vm.uiState.value
        assertEquals(setOf(1f, 2f), before.selectedNumbers)
        assertEquals(mapOf(1f to "eng"), before.explicitAudioOverrides)

        // --- THE configuration change ---
        composeRule.activityRule.scenario.recreate()

        composeRule.activityRule.scenario.onActivity { activity ->
            val retained = ViewModelProvider(activity, factory)
                .get(AUDIO_KEY, AnimeDetailsViewModel::class.java)
            assertSame("the ViewModel instance survives recreation", vm, retained)
        }

        val after = vm.uiState.value
        assertEquals(
            "the screen-level context is unchanged",
            before.selectedAudio,
            after.selectedAudio,
        )
        assertEquals(
            "explicit overrides are unchanged",
            before.explicitAudioOverrides,
            after.explicitAudioOverrides,
        )
        assertEquals(
            "the selection is unchanged",
            before.selectedNumbers,
            after.selectedNumbers,
        )
        assertTrue("batch mode itself is unchanged", after.batchMode)
        assertEquals(
            "recreation never fabricates or drops an option (DECISIONS 144)",
            before.audioOptions,
            after.audioOptions,
        )

        // --- the retained state still drives a REAL batch ---
        composeRule.activityRule.scenario.onActivity { vm.startBatchDownload() }
        awaitCondition("the batch finishes after recreation") {
            val s = vm.uiState.value
            !s.batchRunning && s.batchFinished
        }

        assertEquals("one submission per selected number", 2, queue.submissions.size)
        val ep1 = queue.submissions.first { it.episode.number == 1f }
        val ep2 = queue.submissions.first { it.episode.number == 2f }
        assertEquals(
            "the retained override still decides number 1",
            "eng",
            ep1.episode.audio,
        )
        assertEquals(
            "the retained context still decides number 2",
            "jpn",
            ep2.episode.audio,
        )
        assertEquals(
            "nothing failed on the way",
            0,
            vm.uiState.value.batchFailed,
        )
    }

    private companion object {
        const val AUDIO_KEY = "audio-recreation"
        const val SESSION = "sess-audio"
    }
}
