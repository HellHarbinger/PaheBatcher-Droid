package com.pahebatcher.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.support.allContentDescriptions
import com.pahebatcher.app.support.allTexts
import com.pahebatcher.app.support.awaitCondition
import com.pahebatcher.app.support.ensureTestMp4
import com.pahebatcher.app.support.seedCompletedEpisodes
import com.pahebatcher.app.support.sliderProgressValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §36-9 (touch-target + focus halves) and §21.5/§21.6/§21.7 against the REAL
 * MainActivity, real Room-seeded media, and the real Media3 player:
 *
 *  - §21.1 decorative: first-use Home exposes EXACTLY one content description
 *    ("Settings") — bottom-nav glyphs and the leading search glyph are null-CD
 *    ornaments that must never be announced;
 *  - §21.5 touch targets ≥48dp: icon buttons (gear, back), bottom-nav items,
 *    the §12.4 guidance button, the Settings radio/switch rows, the Downloads
 *    action button, and — the normative custom-slot case — the player's Back
 *    control, labeled player controls, and the custom seek Slider;
 *  - §21.6 player: the video surface exposes the episode title as its content
 *    description instead of being focusable-deck, and library-provided labeled
 *    controls keep their semantics;
 *  - §21.7 focus/navigation: dismissing a pushed route (Back from Settings)
 *    returns to the invoking screen with the invoking control reachable again.
 *
 * Dialog focus restoration (§36-9's "dialog dismissal returns focus" item) is
 * VACUOUS in Phase 11: grep over production sources finds ZERO
 * AlertDialog/Dialog call sites (the §20 permission rationale dialog is future
 * scope). The closest real contract — pushed-route back returning to the
 * invoking control — is asserted by
 * [backFromPushedRouteReturnsToTheInvokingScreenWithTheInvokerReachable].
 */
@RunWith(AndroidJUnit4::class)
class TouchTargetAndFocusTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var db: PaheDatabase
    private lateinit var mediaPath: String

    @Before
    fun setUp() {
        db = PaheDatabase.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        mediaPath = ensureTestMp4()
    }

    // -----------------------------------------------------------------------
    // §21.1 + §21.5 — chrome: descriptions, decorative none, 48dp targets
    // -----------------------------------------------------------------------

    @Test
    fun homeChromeExposesRealDescriptionsAnd48dpTargets() {
        composeRule.waitForIdle()

        // Decorative bottom-nav glyphs + leading search glyph carry NO
        // description → Home's CD set is exactly the icon-only ACTIONS' real
        // strings (gear + the Phase 12C watchlist icon).
        assertEquals(
            "Home first-use content descriptions (decorative icons must be absent)",
            listOf("Settings", "Watchlist"),
            composeRule.allContentDescriptions().sorted(),
        )

        assertMin48dp("Home Settings gear", composeRule.onNodeWithContentDescription("Settings"))
        assertMin48dp(
            "Home Watchlist icon",
            composeRule.onNodeWithContentDescription("Watchlist"),
        )
        assertMin48dp("bottom-nav Home item", composeRule.onNodeWithText("Home"))
        assertMin48dp(
            "bottom-nav Downloads item",
            composeRule.onNodeWithText("Downloads"),
        )
        assertMin48dp(
            "empty-downloads guidance button",
            composeRule.onNodeWithTag("empty_downloads_guidance"),
        )
    }

    @Test
    fun settingsControlsMeet48dpTouchTargets() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }

        assertMin48dp("Settings Back", composeRule.onNodeWithContentDescription("Back"))

        // Every interactive radio ROW is a real selectable target. Phase 12A
        // added the quality / max-parallel / auto-retry groups alongside the
        // theme modes, so Settings now exposes 15 such rows, not 3: the A21.5
        // contract is therefore measured over ALL of them, and the theme group
        // is additionally pinned to exactly its three mode labels.
        val radioRows = interactiveRoleNodes(Role.RadioButton)
        assertTrue(
            "Settings exposes interactive radio rows (found=${radioRows.size})",
            radioRows.size >= 3,
        )
        val themeModeLabels = setOf("System", "Dark", "Light")
        val themeRows = radioRows.filter { node ->
            node.subtreeTexts().any { it in themeModeLabels }
        }
        assertEquals(
            "theme group must expose exactly its three mode rows",
            3,
            themeRows.size,
        )
        // Settings scrolls (A13), so rows below the fold are clipped to a zero
        // touch bounds: scroll each row into view before measuring it.
        val radioMatcher = roleMatcher(Role.RadioButton)
        radioRows.indices.forEach { index ->
            val row = composeRule.onAllNodes(radioMatcher)[index]
            row.performScrollTo()
            composeRule.waitForIdle()
            val node = row.fetchSemanticsNode()
            assertNode48dp("radio row ${index + 1}", node.touchBoundsInRoot.width, node.touchBoundsInRoot.height)
        }

        // The dynamic-color row (Role.Switch) — our composed row is the target.
        // (Scroll it back into view first: the radio sweep above left the
        // content column scrolled to its bottom.)
        val switchMatcher = roleMatcher(Role.Switch)
        val switchRows = interactiveRoleNodes(Role.Switch)
        assertTrue(
            "dynamic-color switch row exists (nodes=${
                switchRows.map { "${it.boundsInRoot.width}x${it.boundsInRoot.height}" }
            })",
            switchRows.isNotEmpty(),
        )
        switchRows.indices.forEach { index ->
            val row = composeRule.onAllNodes(switchMatcher)[index]
            row.performScrollTo()
            composeRule.waitForIdle()
            val node = row.fetchSemanticsNode()
            assertNode48dp("switch target ${index + 1}", node.touchBoundsInRoot.width, node.touchBoundsInRoot.height)
        }
    }

    @Test
    fun downloadsActionButtonMeets48dpTouchTarget() {
        seedCompletedEpisodes(db, mediaPath)
        composeRule.onNodeWithText("Downloads").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Play").fetchSemanticsNodes().size == 1
        }

        // §21.5: the §17.1 action buttons are real touch targets too.
        assertMin48dp("Downloads Play button", composeRule.onNodeWithText("Play"))
    }

    // -----------------------------------------------------------------------
    // §21.5 + §21.6 — custom player slots + video-surface description
    // -----------------------------------------------------------------------

    @Test
    fun playerCustomControlSlotsMeet48dpAndSurfaceExposesTheEpisodeTitle() {
        seedCompletedEpisodes(db, mediaPath)
        composeRule.onNodeWithText("Downloads").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Play").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Play").performClick()

        assertTrue(
            "player route shows the episode title overlay",
            awaitCondition(15_000) {
                composeRule.allTexts().any { "Episode 1" in it }
            },
        )

        // Controls may auto-hide (Media3 default timeout); tap an empty part
        // of the video surface to re-show them and poll until Back + play/pause
        // + the custom seek slider are all present.
        var attempts = 0
        val controlsReady = awaitCondition(15_000, 250) {
            val cds = composeRule.allContentDescriptions()
            val sliderPresent = composeRule
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
            val ready = cds.any { it == "Back" } &&
                (cds.any { it == "Play" } || cds.any { it == "Pause" }) &&
                sliderPresent
            if (!ready && attempts % 12 == 0) {
                // Surface tap toggles control visibility (empty left-mid area,
                // away from the centred play/pause and the bottom slider).
                try {
                    composeRule.onNodeWithTag("player_root").performTouchInput {
                        click(Offset(x = 40f, y = height * 0.6f))
                    }
                } catch (_: AssertionError) {
                    // transitional frame — next poll retries
                }
            }
            attempts++
            ready
        }
        assertTrue(
            "player controls + seek slider present (attempts=$attempts, " +
                "cds=${composeRule.allContentDescriptions()}, " +
                "slider=${composeRule.sliderProgressValues()})",
            controlsReady,
        )

        // §21.6: the video surface exposes the EPISODE TITLE as its CD.
        val surface = composeRule.onNodeWithTag("player_root").fetchSemanticsNode()
        val surfaceCd = surface.config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(
            "video surface CD is the episode title (was '$surfaceCd')",
            "Episode 1" in surfaceCd,
        )
        assertNode48dp(
            "video surface",
            surface.touchBoundsInRoot.width,
            surface.touchBoundsInRoot.height,
        )

        // Every labeled click target on the player (surface, Back, play/pause)
        // must be ≥48dp (§21.5 + §21.6 library-control semantics preserved).
        val nodes = composeRule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        val labeledClickable = nodes.filter { node ->
            node.config.contains(SemanticsProperties.ContentDescription) &&
                node.config.contains(SemanticsActions.OnClick)
        }
        assertTrue(
            "labeled player controls exist (surface + Back + play/pause): " +
                "${labeledClickable.size}",
            labeledClickable.size >= 3,
        )
        labeledClickable.forEach { node ->
            val cd = node.config[SemanticsProperties.ContentDescription].joinToString()
            assertNode48dp("player control '$cd'", node.touchBoundsInRoot.width, node.touchBoundsInRoot.height)
        }

        // §21.6 + §21.5 — the custom seek slot. Size is asserted on OUR
        // wrapper Box's testTag: the Box wraps Material3 Slider's full
        // reported size, including its internal
        // minimumInteractiveComponentSize() 48dp padding — that is the control
        // area our slot presents (the slider's inner semantics node reports
        // content height only and exposes SetProgress rather than OnClick, so
        // its touch bounds never pad). The library-provided
        // semantics (ProgressBarRangeInfo + SetProgress) must remain intact
        // (§21.6).
        // useUnmergedTree: the wrapper Box's own node (exact control-area
        // bounds) — the merged tree may fold small single-tag nodes into a
        // mergeDescendants ancestor whose bounds would over-report the area.
        val taggedSeek = composeRule
            .onNodeWithTag("player_seek_slider", useUnmergedTree = true)
            .fetchSemanticsNode()
        val seekLayoutW = with(composeRule.density) { taggedSeek.boundsInRoot.width.toDp().value }.roundTo1()
        val seekLayoutH = with(composeRule.density) { taggedSeek.boundsInRoot.height.toDp().value }.roundTo1()
        val seekTouchW = with(composeRule.density) { taggedSeek.touchBoundsInRoot.width.toDp().value }.roundTo1()
        val seekTouchH = with(composeRule.density) { taggedSeek.touchBoundsInRoot.height.toDp().value }.roundTo1()
        assertTrue(
            "custom seek slider control area must be ≥48dp " +
                "(layout=${seekLayoutW}dp x ${seekLayoutH}dp, touch=${seekTouchW}dp x ${seekTouchH}dp)",
            seekLayoutW >= 47.9f && seekLayoutH >= 47.9f &&
                seekTouchW >= 47.9f && seekTouchH >= 47.9f,
        )

        val sliders = nodes.filter { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
        assertTrue(
            "custom seek slider present in player semantics",
            sliders.isNotEmpty(),
        )
        sliders.forEach { node ->
            assertTrue(
                "seek slider keeps the library SetProgress action (§21.6)",
                node.config.contains(SemanticsActions.SetProgress),
            )
        }
    }

    // -----------------------------------------------------------------------
    // §21.7 — pushed-route back returns to the invoking screen
    // -----------------------------------------------------------------------

    @Test
    fun backFromPushedRouteReturnsToTheInvokingScreenWithTheInvokerReachable() {
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings_root").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("home_root").fetchSemanticsNodes().isNotEmpty()
        }

        // The invoking control is reachable again — displayable, focusable
        // (clickable), and the exact Home composition is restored.
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertHasClickAction()
        composeRule.onNodeWithTag("home_root").assertHasClickAction()
        assertEquals(
            "Home restored with exactly its real descriptions after the pop",
            listOf("Settings", "Watchlist"),
            composeRule.allContentDescriptions().sorted(),
        )
        assertTrue(composeRule.allTexts().contains("PaheBatcher"))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Every [SemanticsProperties.Text] string inside this node's subtree.
     * Settings radio rows do not merge their labels, so the label lives in a
     * child node of the row that carries the [Role.RadioButton] semantics.
     */
    private fun SemanticsNode.subtreeTexts(): List<String> {
        val own =
            if (config.contains(SemanticsProperties.Text)) {
                config[SemanticsProperties.Text].map { it.text }
            } else {
                emptyList()
            }
        return own + children.flatMap { it.subtreeTexts() }
    }

    /** Matcher for interactive nodes carrying the given semantic [role] (click/select targets only). */
    private fun roleMatcher(role: Role) =
        SemanticsMatcher("role=$role") { node ->
            node.config.contains(SemanticsProperties.Role) &&
                node.config[SemanticsProperties.Role] == role &&
                (node.config.contains(SemanticsActions.OnClick) ||
                    node.config.contains(SemanticsProperties.Selected))
        }

    /** Interactive nodes carrying the given semantic [role] (click/select targets only). */
    private fun interactiveRoleNodes(role: Role) =
        composeRule
            .onAllNodes(roleMatcher(role))
            .fetchSemanticsNodes()

    /**
     * §21.5 measures TOUCH targets, so both helpers assert on
     * `SemanticsNode.touchBoundsInRoot` — the effective input area. For
     * Material3 components whose layout content is smaller than the minimum
     * (e.g. a 40dp IconButton), the framework's minimum-touch-target
     * expansion makes the touch bounds the normative 48dp interactive
     * surface; for custom composed rows touch bounds equal layout bounds.
     * (Plain `boundsInRoot` reads the inner clickable coordinator and would
     * under-report M3 components by design.)
     */
    private fun assertMin48dp(label: String, interaction: SemanticsNodeInteraction) {
        val node = interaction.fetchSemanticsNode()
        assertNode48dp(label, node.touchBoundsInRoot.width, node.touchBoundsInRoot.height)
    }

    private fun assertNode48dp(label: String, widthPx: Float, heightPx: Float) {
        val (w, h) = with(composeRule.density) {
            widthPx.toDp().value to heightPx.toDp().value
        }
        assertTrue(
            "$label touch target must be ≥48dp (was ${w.roundTo1()}dp x ${h.roundTo1()}dp)",
            w >= 47.9f && h >= 47.9f,
        )
    }

    private fun Float.roundTo1(): Float = (this * 10).toInt() / 10f
}
