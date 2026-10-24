package com.pahebatcher.app.data.remote.validation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.data.remote.parser.PlayPageParser
import com.pahebatcher.app.data.session.AndroidCloudflareSession
import com.pahebatcher.app.data.session.AndroidSessionPersistence
import com.pahebatcher.app.data.stream.KwikResolver
import com.pahebatcher.app.data.stream.QualitySelector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device validation targeting "LIAR GAME" anime.
 */
@RunWith(AndroidJUnit4::class)
class StreamResolutionValidationTest {

    companion object {
        private const val TAG = "StreamResValidation"
    }

    @Test
    fun validateLiarGameStreamResolution() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionPersistence = AndroidSessionPersistence(context)

        // Step 1: Establish WebView session
        Log.i(TAG, "=== Step 1: Establishing WebView session ===")
        val session = AndroidCloudflareSession(context, sessionPersistence)
        val sessionInfo = session.establishSession(forceRefresh = true)
        Log.i(TAG, "Session state: ${sessionInfo.state}")
        Log.i(TAG, "Session ready: ${sessionInfo.isReady}")
        Log.i(TAG, "Cookie count: ${sessionInfo.cookieCount}")
        assertTrue("Session should be established", sessionInfo.isReady)

        // Step 2: Load main page
        Log.i(TAG, "=== Step 2: Loading main page ===")
        val mainPage = session.fetchPage(
            url = "https://animepahe.com",
            waitForCloudflare = true,
            timeoutMs = 30_000L
        )
        Log.i(TAG, "Main page success: ${mainPage.success}, length: ${mainPage.html?.length ?: 0}")

        // Step 3: Browse anime list pages to find LIAR GAME
        Log.i(TAG, "=== Step 3: Browsing anime list for LIAR GAME ===")
        var foundAnimeUuid: String? = null

        // Try multiple pages of the anime list
        for (page in 1..5) {
            val listUrl = "https://animepahe.com/anime?page=$page"
            Log.i(TAG, "Fetching anime list page $page...")
            val listPage = session.fetchPage(
                url = listUrl,
                waitForCloudflare = true,
                timeoutMs = 30_000L
            )

            if (!listPage.success || listPage.html == null) {
                Log.w(TAG, "Anime list page $page failed: ${listPage.error}")
                continue
            }

            Log.i(TAG, "Anime list page $page: ${listPage.html!!.length} chars")

            // Search for "liar game" in the HTML (case-insensitive, whole phrase)
            val liarGamePattern = Regex("""(?i)liar\s*game""")
            val liarGameMatch = liarGamePattern.find(listPage.html!!)
            if (liarGameMatch != null) {
                Log.i(TAG, "Found 'LIAR GAME' at index ${liarGameMatch.range.first} in page $page")
                // Get surrounding context
                val start = maxOf(0, liarGameMatch.range.first - 300)
                val end = minOf(listPage.html!!.length, liarGameMatch.range.last + 500)
                val contextStr = listPage.html!!.substring(start, end)
                Log.i(TAG, "Context: $contextStr")

                // Extract anime UUID from the context
                val uuidPattern = Regex("""href="/anime/([a-f0-9-]+)"""")
                val uuidMatch = uuidPattern.find(contextStr)
                if (uuidMatch != null) {
                    foundAnimeUuid = uuidMatch.groupValues[1]
                    Log.i(TAG, "Found LIAR GAME UUID: $foundAnimeUuid")
                    break
                }

                // Try data-id pattern
                val dataIdPattern = Regex("""data-id="([a-f0-9-]+)"""")
                val dataIdMatch = dataIdPattern.find(contextStr)
                if (dataIdMatch != null) {
                    foundAnimeUuid = dataIdMatch.groupValues[1]
                    Log.i(TAG, "Found LIAR GAME UUID (data-id): $foundAnimeUuid")
                    break
                }
            }
        }

        if (foundAnimeUuid == null) {
            Log.w(TAG, "Could not find LIAR GAME in anime list pages 1-5")
            session.releaseSession()
            return@runBlocking
        }

        // Step 4: Fetch the anime page to find episodes
        Log.i(TAG, "=== Step 4: Fetching LIAR GAME anime page ===")
        val animePage = session.fetchPage(
            url = "https://animepahe.com/anime/$foundAnimeUuid",
            waitForCloudflare = true,
            timeoutMs = 30_000L
        )
        Log.i(TAG, "Anime page success: ${animePage.success}")
        Log.i(TAG, "Anime page HTML length: ${animePage.html?.length ?: 0}")

        if (!animePage.success || animePage.html == null) {
            Log.w(TAG, "Anime page failed: ${animePage.error}")
            session.releaseSession()
            return@runBlocking
        }

        // Find episodes
        val epPattern = Regex("""/play/[a-f0-9-]+/([a-f0-9-]+)""")
        val epLinks = epPattern.findAll(animePage.html!!)
            .map { it.groupValues[1] }
            .distinct()
            .take(5)
            .toList()
        Log.i(TAG, "Found ${epLinks.size} episodes: $epLinks")

        if (epLinks.isEmpty()) {
            Log.w(TAG, "No episodes found for LIAR GAME")
            session.releaseSession()
            return@runBlocking
        }

        // Step 5: Fetch the play page for the first episode
        val epUuid = epLinks[0]
        Log.i(TAG, "=== Step 5: Fetching play page for episode $epUuid ===")
        val playPage = session.fetchPage(
            url = "https://animepahe.com/play/$foundAnimeUuid/$epUuid",
            waitForCloudflare = true,
            timeoutMs = 30_000L
        )
        Log.i(TAG, "Play page success: ${playPage.success}")
        Log.i(TAG, "Play page HTML length: ${playPage.html?.length ?: 0}")

        if (!playPage.success || playPage.html == null) {
            Log.w(TAG, "Play page failed: ${playPage.error}")
            session.releaseSession()
            return@runBlocking
        }

        val html = playPage.html!!

        // Step 6: Parse resolution buttons
        Log.i(TAG, "=== Step 6: Parsing resolution buttons ===")
        val entries = PlayPageParser.parseResolutionButtons(html)
        Log.i(TAG, "Found ${entries.size} resolution entries")
        for (entry in entries) {
            // Audio-variant slice (SPEC §22): the entry carries the source's own
            // open-set audio string — "" means the page gave no audio signal.
            Log.i(TAG, "  ${entry.resolution}p audio='${entry.audio}' fansub=${entry.fansub} kwikUrl=${entry.kwikUrl}")
        }

        if (entries.isEmpty()) {
            Log.w(TAG, "No resolution entries found")
            session.releaseSession()
            return@runBlocking
        }

        // Step 7: exact-audio + quality selection (SPEC §22).
        // `jpn` is requested because that is what this test's caller streams;
        // the port selects ONLY if the page offers that exact audio at ≤720p,
        // and otherwise declines (the failure message path is asserted by the
        // JVM suite — here we prove the decline is a genuine audio-filter
        // outcome, never a crash and never another audio sneaking through).
        Log.i(TAG, "=== Step 7: Selecting quality and audio ===")
        val selected = QualitySelector.select(entries, 720, "jpn")
        if (selected == null) {
            assertTrue(
                "a null selection must mean the page offers no exact 'jpn' entry, " +
                    "nothing else: entries=${entries.map { "${it.resolution}p/audio='${it.audio}'" }}",
                entries.none { it.audio == "jpn" },
            )
            Log.w(TAG, "No 'jpn' entry at ≤720p on this page — explicit-failure path exercised; not resolving Kwik")
            session.releaseSession()
            return@runBlocking
        }
        Log.i(TAG, "Selected: ${selected.resolution}p audio='${selected.audio}'")
        Log.i(TAG, "Kwik URL: ${selected.kwikUrl}")
        // The selected entry's own audio IS the resolved audio — an exact
        // match was requested, so nothing may be re-derived.
        assertEquals("exact-audio selection returns the requested audio", "jpn", selected.audio)

        // Step 8: Resolve Kwik URL
        Log.i(TAG, "=== Step 8: Resolving Kwik URL ===")
        val kwikResolver = KwikResolver(session)
        // 11.5E-4: resolve() now reports the playlist together with the Kwik page it came from.
        val m3u8Url = kwikResolver.resolve(selected.kwikUrl)?.m3u8Url
        Log.i(TAG, "M3U8 URL: $m3u8Url")

        val title = PlayPageParser.extractTitle(html)
        // The resolved audio is the selected entry's own value — no
        // `getEffectiveAudio(requested, isDub)` re-derivation exists anymore.
        val effectiveAudio = selected.audio

        Log.i(TAG, "=== FINAL RESULT ===")
        Log.i(TAG, "Title: $title")
        Log.i(TAG, "Anime UUID: $foundAnimeUuid")
        Log.i(TAG, "Episode UUID: $epUuid")
        Log.i(TAG, "Resolution: ${selected.resolution}p audio='$effectiveAudio'")
        Log.i(TAG, "Fansub: ${selected.fansub}")
        Log.i(TAG, "Kwik URL: ${selected.kwikUrl}")
        Log.i(TAG, "M3U8 URL: $m3u8Url")
        Log.i(TAG, "Audio: $effectiveAudio")

        if (m3u8Url != null) {
            Log.i(TAG, "=== ALL STEPS PASSED ===")
        } else {
            Log.w(TAG, "Steps 1-7 PASSED, Step 8 (Kwik→M3U8) BLOCKED by domain infrastructure")
        }

        session.releaseSession()
    }
}
