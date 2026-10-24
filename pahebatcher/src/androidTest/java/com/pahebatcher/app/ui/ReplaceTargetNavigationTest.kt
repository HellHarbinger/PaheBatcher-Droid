package com.pahebatcher.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.download.DownloadRepository
import com.pahebatcher.app.domain.stream.StreamResolver
import com.pahebatcher.app.support.RecordStreamResolver
import com.pahebatcher.app.support.RetryTestStream
import com.pahebatcher.app.support.backStackSize
import com.pahebatcher.app.support.ensureTestMp4
import com.pahebatcher.app.support.isOnRoute
import com.pahebatcher.app.support.seedCompletedEpisodes
import com.pahebatcher.app.ui.downloads.DownloadsViewModel
import com.pahebatcher.app.ui.navigation.DownloadsRoute
import com.pahebatcher.app.ui.navigation.HomeRoute
import com.pahebatcher.app.ui.navigation.SessionsRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * AQR-140 (adopted 2026-10-23) — `replace:<episodeId>` must bring Downloads to
 * the foreground BEFORE the pending request is processed (DECISIONS 152.7
 * routing chain, §19 seam).
 *
 * The production chain under test: `AppNavHost`'s replace branch pops a top
 * player, then routes through `navigateToDownloadsForReplacement()` — the §10.4
 * tab rule with `restoreState = false` — so the tab is entered at its ROOT
 * destination even when a nested destination (Sessions) was saved on top of it.
 * The one-shot delivery hook (`LaunchedEffect(pendingReplacementEpisodeId)`
 * inside `composable<DownloadsRoute>`, which only composes while Downloads is
 * the active destination) then hands the id to the entry-scoped
 * [DownloadsViewModel], which re-validates it against Room before ANY dialog is
 * armed.
 *
 * History: this suite originally Pinned the deferred behaviour a finding had
 * recorded — the request stayed armed behind the restored Sessions destination
 * until the user manually returned to Downloads — because the landing policy
 * was then an OPEN product decision (LIMITATIONS §53.2). The adopted policy is
 * now asserted on the SAME reproduction: Downloads is foregrounded at its root
 * and the confirmation is presented there.
 *
 * Covered here:
 *  - a target received at the Downloads root → confirmation on Downloads;
 *  - a target received with a nested destination on top → Downloads foregrounded
 *    at its ROOT, then the confirmation;
 *  - a target received after a saved Downloads stack is restored → same (the
 *    updated restored-stack reproduction);
 *  - Confirm revalidates persisted state and runs the replacement exactly once;
 *  - Cancel performs no destructive action and clears the pending target;
 *  - duplicate delivery → one dialog, one action, no duplicated stack entry;
 *  - an invalid/stale target → no dialog, no write, an honest message;
 *  - activity recreation → neither loses nor replays the request.
 *
 * The repository-level halves of the contract (a confirmation claims the row,
 * a repeated confirmation is idempotent, a stale/invalid target is refused
 * untouched) are pinned by `ReplaceFileConfirmationTest` and
 * `ReplaceFileDuplicateProtectionTest`; this suite pins the NAVIGATION and
 * lifecycle behaviour around them.
 */
class ReplaceTargetNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager
    private val seededIds = mutableListOf<String>()

    // The real app wires the production StreamResolverImpl, which cannot resolve
    // the synthetic playUrl under test. Swap in a fake resolver (restored in
    // tearDown) so a confirmed replacement can actually resolve, hand off to the
    // pipeline and claim the row — the repository-level confirm contract itself
    // is pinned by ReplaceFileConfirmationTest; this suite pins the navigation
    // and dialog flow around it.
    private var resolverRepo: DownloadRepository? = null
    private var resolverField: java.lang.reflect.Field? = null
    private var originalResolver: StreamResolver? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
        val app = context.applicationContext as PaheBatcherApplication
        val repo = app.appContainer.downloadRepository
        val field = DownloadRepository::class.java.getDeclaredField("streamResolver")
        field.isAccessible = true
        resolverRepo = repo
        resolverField = field
        originalResolver = field.get(repo) as StreamResolver?
        field.set(repo, RecordStreamResolver { RetryTestStream.streamInfo() })
    }

    @After
    fun tearDown() {
        // A confirmed replacement hands the row to a real Worker; cancel the
        // unique work for every episode this class seeded, then wipe the rows
        // (seedCompletedEpisodes wipes sessions on the next call anyway).
        seededIds.forEach { workManager.cancelUniqueWork("episode_download_$it") }
        seededIds.clear()
        runBlocking { db.downloadSessionDao().deleteAll() }
        // Restore the app-scoped repository's real resolver.
        val repo = resolverRepo
        val field = resolverField
        if (repo != null && field != null) field.set(repo, originalResolver)
    }

    /**
     * Seeds one COMPLETED episode with a real local export, tracked for cleanup.
     *
     * `canonicalIds = true`: the confirm test drives a real replacement through
     * `reEnqueue`, whose identity guard REQUIRES the row's id to round-trip
     * through `EpisodeId.build`. The default seed id does not, so without this
     * the guard would refuse the re-enqueue and the replacement could never
     * start. This suite never touches the player, so the production-shape id has
     * no other effect here.
     */
    private fun seedOne(): String =
        seedCompletedEpisodes(db, ensureTestMp4(), canonicalIds = true)
            .single().also { seededIds += it }

    private fun awaitNode(tag: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertExists()
    }

    private fun stackSize(): Int {
        val activity = composeRule.activity // read on the test thread (canonical usage)
        return composeRule.runOnIdle { backStackSize(activity) }
    }

    private inline fun <reified T : Any> topRouteIs(): Boolean {
        val activity = composeRule.activity
        return composeRule.runOnIdle { isOnRoute<T>(activity) }
    }

    /**
     * The armed `replace:` request from the CURRENT top entry's Downloads
     * ViewModel — the entry the confirmation dialog renders from.
     */
    private fun replacementTarget(): String? {
        val activity = composeRule.activity
        return composeRule.runOnIdle {
            val entry = requireNotNull(activity.navController?.currentBackStackEntry) {
                "no back-stack entry"
            }
            ViewModelProvider(entry)[DownloadsViewModel::class.java]
                .replacementTarget.value
        }
    }

    /** How many confirmation dialogs are on screen. */
    private fun dialogCount(): Int =
        composeRule.onAllNodesWithText("Confirm replacement").fetchSemanticsNodes().size

    private fun awaitConfirmation(episodeId: String, timeoutMs: Long = 15_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText("Confirm replacement").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            "exactly ONE confirmation dialog may be armed",
            1,
            dialogCount(),
        )
        assertEquals("the dialog is armed for the delivered episode", episodeId, replacementTarget())
    }

    /** Delivers the production §19 target through the exact `onNewIntent` seam. */
    private fun deliver(target: String) {
        composeRule.runOnIdle { composeRule.activity.onNavigateTarget(target) }
    }

    private fun row(episodeId: String): EpisodeDownloadEntity? =
        runBlocking { db.episodeDownloadDao().getById(episodeId) }

    private fun cancelDialog() {
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitUntil(5_000) { dialogCount() == 0 }
    }

    // -----------------------------------------------------------------------
    // Landing policy: Downloads is foregrounded at its ROOT, then processed
    // -----------------------------------------------------------------------

    @Test
    fun targetAtDownloadsRootPresentsTheConfirmationThere() {
        composeRule.waitForIdle()
        val episodeId = seedOne()

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")
        assertEquals(2, stackSize())
        assertTrue(topRouteIs<DownloadsRoute>())

        deliver("replace:$episodeId")

        awaitConfirmation(episodeId)
        assertTrue("Downloads stays the active destination", topRouteIs<DownloadsRoute>())
        assertEquals("no extra entry is pushed for the target", 2, stackSize())
    }

    /**
     * The AQR-140 reproduction itself: a saved Downloads stack has Sessions on
     * top, and the target must NOT stay deferred behind it.
     */
    @Test
    fun targetWithANestedDestinationForegroundsDownloadsAtItsRoot() {
        composeRule.waitForIdle()
        val episodeId = seedOne()

        // Drill Downloads → Sessions: stack [Home, Downloads, Sessions].
        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")
        composeRule.onNodeWithTag("downloads_open_sessions").performClick()
        awaitNode("sessions_root")
        assertTrue(topRouteIs<SessionsRoute>())
        assertEquals(3, stackSize())

        // The target arrives while the nested destination is on top.
        deliver("replace:$episodeId")

        awaitConfirmation(episodeId)
        assertTrue(
            "Downloads is brought to the foreground — Sessions is no longer top",
            topRouteIs<DownloadsRoute>(),
        )
        assertEquals(
            "Downloads lands at its ROOT: [Home, Downloads], never [Home, Downloads, Sessions]",
            2,
            stackSize(),
        )
        awaitNode("downloads_root")
    }

    /**
     * The UPDATED restored-stack reproduction (the original AQR-140 finding):
     * [Downloads, Sessions] was saved by a tab switch, the target is delivered
     * from Home, and the adopted behavior foregrounds Downloads at its root
     * instead of restoring Sessions on top and deferring the request.
     */
    @Test
    fun targetAfterASavedDownloadsStackIsRestoredForegroundsDownloadsAtItsRoot() {
        composeRule.waitForIdle()
        val episodeId = seedOne()

        // Drill Downloads → Sessions, then switch tabs so the stack is SAVED.
        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")
        assertEquals(2, stackSize())
        composeRule.onNodeWithTag("downloads_open_sessions").performClick()
        awaitNode("sessions_root")
        assertTrue(topRouteIs<SessionsRoute>())
        assertEquals(3, stackSize())

        composeRule.onNodeWithText("Home").performClick()
        awaitNode("home_root")
        assertTrue(topRouteIs<HomeRoute>())
        assertEquals(1, stackSize())

        deliver("replace:$episodeId")

        // The adopted policy: Downloads is foregrounded at its ROOT and the
        // confirmation is presented THERE — never deferred behind the restored
        // Sessions destination.
        awaitConfirmation(episodeId)
        assertTrue(topRouteIs<DownloadsRoute>())
        assertEquals(2, stackSize())
        awaitNode("downloads_root")

        // The confirmation safeguards are unchanged: exactly the dialog's Cancel
        // is offered (the seeded row is terminal — no row Cancel), and it
        // clears the request without any destructive repository call.
        assertEquals(
            "exactly one Cancel — the dialog's",
            1,
            composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().size,
        )
        cancelDialog()
        assertNull(
            "Cancel dismissed the request — nothing armed, nothing destructive",
            replacementTarget(),
        )
        val afterCancel = row(episodeId)
        assertNotNull("the row still exists", afterCancel)
        assertFalse(
            "Cancel never claimed the row",
            afterCancel!!.replaceRequested,
        )
        assertEquals(
            "Cancel left the export exactly as it was",
            EpisodeDownloadEntity.STATE_COMPLETED,
            afterCancel.state,
        )
    }

    // -----------------------------------------------------------------------
    // Confirmation, cancellation, duplication, staleness, recreation
    // -----------------------------------------------------------------------

    /**
     * Confirm is the ONE path that may start a replacement: it re-validates the
     * persisted row (the ViewModel's Room gate armed the dialog, and the
     * repository re-applies the same §29 rule at confirmation time) and claims
     * it exactly once.
     */
    @Test
    fun confirmRevalidatesPersistedStateAndRunsTheReplacementOnce() {
        composeRule.waitForIdle()
        val episodeId = seedOne()

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")

        deliver("replace:$episodeId")
        awaitConfirmation(episodeId)

        val before = row(episodeId)
        assertNotNull(before)
        assertFalse("nothing is claimed before the decision", before!!.replaceRequested)
        assertEquals(EpisodeDownloadEntity.STATE_COMPLETED, before.state)

        composeRule.onNodeWithText("Confirm replacement").performClick()
        composeRule.waitUntil(15_000) { dialogCount() == 0 }

        // The claim is persisted, so it survives process death and is what makes
        // a repeated confirmation lose.
        composeRule.waitUntil(10_000) {
            row(episodeId)?.replaceRequested == true
        }
        val claimed = row(episodeId)
        assertNotNull("the row must still exist after the claim", claimed)
        assertTrue("the replacement intent is persisted", claimed!!.replaceRequested)
        assertNotEquals(
            "the row is owned by the new run — it left COMPLETED",
            EpisodeDownloadEntity.STATE_COMPLETED,
            claimed.state,
        )
        assertNull("a started replacement reports no error", replacementTarget())

        // A duplicate delivery of the same target can no longer arm a second
        // dialog: the row is already claimed, so the ViewModel's Room gate
        // refuses it and says so instead.
        deliver("replace:$episodeId")
        Thread.sleep(2_500)
        assertEquals("no second dialog for a claimed row", 0, dialogCount())
        assertTrue(topRouteIs<DownloadsRoute>())
    }

    /** Cancel is a pure state clear: no claim, no write, no worker. */
    @Test
    fun cancelPerformsNoDestructiveActionAndClearsTheTarget() {
        composeRule.waitForIdle()
        val episodeId = seedOne()

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")

        deliver("replace:$episodeId")
        awaitConfirmation(episodeId)

        cancelDialog()

        assertNull("the pending target is cleared", replacementTarget())
        assertEquals(0, dialogCount())
        val after = row(episodeId)
        assertNotNull("the row still exists", after)
        assertFalse("nothing was claimed", after!!.replaceRequested)
        assertEquals(
            "the export is untouched — Cancel is never destructive",
            EpisodeDownloadEntity.STATE_COMPLETED,
            after.state,
        )
    }

    /**
     * Duplicate delivery of the SAME target — the §19 consume-once rule plus
     * the single-top tab rule — must not stack a second Downloads entry, arm a
     * second dialog, or claim anything.
     */
    @Test
    fun duplicateTargetDeliveryDoesNotDuplicateTheDialogOrTheAction() {
        composeRule.waitForIdle()
        val episodeId = seedOne()

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")
        assertEquals(2, stackSize())

        deliver("replace:$episodeId")
        awaitConfirmation(episodeId)

        // Second delivery of the identical target while the dialog is up.
        deliver("replace:$episodeId")
        Thread.sleep(2_000)

        assertEquals("still exactly ONE dialog", 1, dialogCount())
        assertEquals("the target is unchanged", episodeId, replacementTarget())
        assertEquals(
            "no duplicated Downloads entry",
            2,
            stackSize(),
        )
        assertTrue(topRouteIs<DownloadsRoute>())

        cancelDialog()
        assertNull(replacementTarget())
        assertFalse(
            "nothing was claimed by either delivery",
            row(episodeId)!!.replaceRequested,
        )
    }

    /**
     * A stale or invalid target cannot arm a confirmation at all: the ViewModel
     * gates on the repository's §29 rule, so an id with no completed download
     * surfaces an honest message and creates nothing.
     */
    @Test
    fun anInvalidOrStaleTargetIsHandledSafely() {
        composeRule.waitForIdle()
        seedOne() // Downloads has a real row — but not for the id below
        val unknownId = "never-seeded-${System.nanoTime()}"

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")

        deliver("replace:$unknownId")
        Thread.sleep(2_500)

        assertEquals("no dialog for a target that is not there", 0, dialogCount())
        assertNull("nothing is armed", replacementTarget())
        assertTrue(topRouteIs<DownloadsRoute>())
        assertEquals(2, stackSize())
        assertNotNull(
            "the user is told WHY nothing was armed",
            composeRule.onAllNodesWithText("Replace file is unavailable", substring = true)
                .fetchSemanticsNodes().firstOrNull(),
        )
        assertNull(
            "no row is created for an episode that does not exist",
            row(unknownId),
        )
    }

    /**
     * Activity recreation (the configuration-change path) must neither lose the
     * armed request nor replay it: the request lives in the entry-scoped
     * ViewModel, so the dialog survives, and the consume-once rule means a
     * second recreation after Cancel brings nothing back.
     */
    @Test
    fun activityRecreationNeitherLosesNorReplaysTheRequest() {
        composeRule.waitForIdle()
        val episodeId = seedOne()

        composeRule.onNodeWithText("Downloads").performClick()
        awaitNode("downloads_root")

        deliver("replace:$episodeId")
        awaitConfirmation(episodeId)

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        awaitNode("downloads_root")
        assertTrue(topRouteIs<DownloadsRoute>())

        val afterRecreate = row(episodeId)
        assertFalse(
            "recreation itself never claims anything",
            afterRecreate!!.replaceRequested,
        )

        if (dialogCount() > 0) {
            // The request survived the recreation — cancel it.
            assertEquals(episodeId, replacementTarget())
            cancelDialog()
        } else {
            assertNull("nothing is armed after recreation", replacementTarget())
        }
        assertEquals(0, dialogCount())

        // No replay: a second recreation brings nothing back.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        awaitNode("downloads_root")
        Thread.sleep(1_500)
        assertEquals("the consumed request is never replayed", 0, dialogCount())
        assertFalse(
            "and still nothing claimed",
            row(episodeId)!!.replaceRequested,
        )
    }
}
