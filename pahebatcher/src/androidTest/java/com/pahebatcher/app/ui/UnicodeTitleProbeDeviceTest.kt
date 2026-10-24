package com.pahebatcher.app.ui

import android.util.Log
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate A Device Test 3 (investigation leg) — determines, through the
 * PRODUCTION flow only, whether the real site exposes ANY title in a
 * non-Latin script (CJK / Cyrillic / Arabic):
 *
 *  1. each candidate query is entered into the real search field; the first
 *     result row's text is read from the semantics tree;
 *  2. for every query that actually HIT (row carries the query text), the
 *     flow proceeds INTO the production Anime Details screen and the full
 *     title text rendered there (the session-title source) is scanned for
 *     non-Latin characters.
 *
 * The run always passes — its product is the `ADB_PROBE` log lines. The
 * Unicode device verification then either proceeds with a found query or is
 * reported NOT EXERCISED based on this evidence.
 */
@RunWith(AndroidJUnit4::class)
class UnicodeTitleProbeDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun firstRowText(): String? {
        val nodes = composeRule.onAllNodesWithTag("search_result_row")
            .fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        val text = nodes[0].config.getOrNull(SemanticsProperties.Text).orEmpty()
        return text.joinToString(" ") { it.text }
    }

    /** Bounded wait for the search response (rows either appear or settle). */
    private fun waitForSettle(timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            val count = composeRule.onAllNodesWithTag("search_result_row")
                .fetchSemanticsNodes().size
            if (count > 0 && count != last) return count
            last = count
            Thread.sleep(250)
        }
        return composeRule.onAllNodesWithTag("search_result_row")
            .fetchSemanticsNodes().size
    }

    /** Every text string rendered under the semantics tree [node]. */
    private fun collectTexts(node: SemanticsNode): List<String> {
        val own = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        return own + node.children.flatMap { collectTexts(it) }
    }

    /** Reads the details screen's rendered title text (session-title source). */
    private fun detailsText(): String {
        val roots = composeRule.onAllNodesWithTag("anime_details_root")
            .fetchSemanticsNodes()
        if (roots.isEmpty()) return ""
        return collectTexts(roots[0]).joinToString(" | ")
    }

    private fun hasNonLatin(text: String): Boolean = text.any { it.code > 127 }

    @Test
    fun probesProductionSearchForNonLatinTitles() {
        val candidates = listOf(
            "葬送フリーレン", // Frieren — CJK
            "鬼滅の刃", // Demon Slayer — CJK
            "進撃の巨人", // Attack on Titan — CJK
            "Титан", // Cyrillic
            "الشمس", // Arabic
        )
        var detailsHit = false
        candidates.forEach { query ->
            composeRule.onNodeWithTag("search_field").performClick()
            composeRule.onNodeWithTag("search_field").performTextClearance()
            composeRule.onNodeWithTag("search_field").performTextInput(query)
            composeRule.onNodeWithTag("search_field").performImeAction()
            val rows = waitForSettle(30_000)
            val text = firstRowText()
            val hit = text?.contains(query) == true
            Log.i(TAG, "ADB_PROBE query=[$query] rows=$rows first=[$text] rowHitQuery=$hit")
            // rows>0 IS a genuine match (an empty result set renders 0 rows —
            // observed for the Cyrillic/Arabic probes), so any non-empty
            // listing goes into details to inspect the session-title source.
            if (rows == 0) return@forEach

            // Production navigation into the details destination — the screen
            // whose title becomes the session title for Gate A's directory.
            composeRule.onAllNodesWithTag("search_result_row")[0].performClick()
            val deadline = System.currentTimeMillis() + 60_000
            var rendered = ""
            while (System.currentTimeMillis() < deadline) {
                rendered = detailsText()
                if (rendered.isNotEmpty() && !rendered.contains("Anime details")) break
                Thread.sleep(500)
            }
            val nonLatinTitle = hasNonLatin(rendered)
            if (nonLatinTitle) detailsHit = true
            Log.i(
                TAG,
                "ADB_PROBE_DETAILS query=[$query] nonLatin=$nonLatinTitle text=[$rendered]",
            )
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithTag("search_result_row")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        Log.i(TAG, "ADB_PROBE_SUMMARY anyNonLatinDetailsTitle=$detailsHit")
    }

    private companion object {
        const val TAG = "UnicodeTitleProbeDeviceTest"
    }
}
