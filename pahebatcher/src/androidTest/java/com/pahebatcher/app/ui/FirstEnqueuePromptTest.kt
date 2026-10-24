package com.pahebatcher.app.ui

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.repository.AnimeRepository
import com.pahebatcher.app.data.repository.EpisodeRepository
import com.pahebatcher.app.data.repository.ScanCacheRepository
import com.pahebatcher.app.domain.download.EpisodeDownloadQueue
import com.pahebatcher.app.domain.model.AppContext
import com.pahebatcher.app.notification.NotificationPermissionRepository
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.FileInputStream

/**
 * The ONE-time `POST_NOTIFICATIONS` prompt issued
 * at the first enqueue.
 *
 * Testable surface (honest scope): an instrumentation test cannot interact with
 * the system permission dialog, so the contract is verified on the two real,
 * observable sides of it:
 *
 *  - the ViewModel's `AnimeDetailsViewModel.requestNotificationPermission`
 *    flag, which is exactly what the ActivityResult launcher in
 *    `AnimeDetailsScreen` keys its single launch on;
 *  - the persisted asked-once latch + real grant state in the REAL
 *    [NotificationPermissionRepository] over the REAL DataStore.
 *
 * ### Order constraint (real Android behavior, verified on this device)
 *
 * Android **force-stops a process whose runtime permissions are revoked while
 * it is running** (`ActivityManager: Killing ...: permissions revoked`), which
 * would kill this very instrumentation run mid-suite. GRANTING is safe.
 * Therefore this suite only ever moves the permission **denied → granted**,
 * never reverts it:
 *
 *  - `@FixMethodOrder(NAME_ASCENDING)` + the `a_`/`z_` name prefixes keep the
 *    DENIED-state tests first and the GRANTED-state test last;
 *  - the denied tests `Assume` a denied baseline (honest skip instead of a
 *    false failure if an earlier run left the permission granted);
 *  - `@After` restores only the DataStore flags — the permission itself is
 *    deliberately left as-is; the test harness reinstalls the app for every
 *    connected run, which resets the baseline.
 *
 * Both DataStore flags are saved in `@Before` / restored in `@After`, so no
 * other suite sees mutated latch state.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FirstEnqueuePromptTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var appConfig: AppConfig
    private lateinit var repository: NotificationPermissionRepository
    private var savedAskedOnce = false
    private var savedGrantedFlag = false

    @Before
    fun setup() {
        appConfig = AppConfig(context)
        repository = NotificationPermissionRepository(appConfig, context)
        Assume.assumeTrue(
            "the POST_NOTIFICATIONS runtime prompt does not exist below API 33",
            repository.runtimeRequestApplies(),
        )
        runBlocking {
            savedAskedOnce = appConfig
                .getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, false)
                .first()
            savedGrantedFlag = appConfig
                .getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, false)
                .first()
            // Pre-prompt state: latch fresh so the gate may offer the prompt.
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, false)
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, false)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, savedAskedOnce)
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, savedGrantedFlag)
        }
        // The permission is intentionally NOT restored: revoking it would
        // force-stop this running instrumentation process (see class KDoc).
    }

    // -----------------------------------------------------------------------
    // a_… — DECISIONS 95.A, single path: ask once, a denial never re-asks
    // (requires the fresh-install DENIED baseline; skips honestly otherwise)
    // -----------------------------------------------------------------------

    @Test
    fun a_denied_firstEnqueueOffersThePromptOnceAndADenialIsNeverReasked() {
        Assume.assumeFalse(
            "baseline is GRANTED and revoking would kill the running test " +
                "process — run from a fresh install",
            runBlocking { repository.isGranted() },
        )
        runBlocking {
            assertTrue(
                "the asked-once latch must be fresh before the first enqueue",
                repository.shouldRequest(),
            )
        }

        val vm = newViewModel()
        enqueueSingleEpisode(vm, number = 1f)

        // The first real enqueue must open the prompt gate (DECISIONS 95.A).
        awaitCondition("the prompt flag after the first enqueue") {
            vm.uiState.value.requestNotificationPermission
        }

        // The user answers DENY — the real launcher's only callback.
        vm.onNotificationPermissionResult(false)
        awaitCondition("the cleared prompt flag") {
            !vm.uiState.value.requestNotificationPermission
        }
        runBlocking {
            assertFalse(
                "a denial must consume the asked-once latch permanently",
                repository.shouldRequest(),
            )
        }

        // A SECOND real enqueue through the same gate must never re-ask.
        enqueueSingleEpisode(vm, number = 2f)
        // The gate runs asynchronously after the hand-off; give it time to
        // (wrongly) set the flag, then assert it never did.
        Thread.sleep(700L)
        assertFalse(
            "a denial must never be re-asked automatically (§20)",
            vm.uiState.value.requestNotificationPermission,
        )
    }

    // -----------------------------------------------------------------------
    // a_… — DECISIONS 95.A, the batch call site offers the same single prompt
    // -----------------------------------------------------------------------

    @Test
    fun a_denied_theBatchStartOffersTheSameOneTimePrompt() {
        Assume.assumeFalse(
            "baseline is GRANTED and revoking would kill the running test " +
                "process — run from a fresh install",
            runBlocking { repository.isGranted() },
        )
        runBlocking {
            assertTrue(
                "the setup requires a fresh latch with permission denied",
                repository.shouldRequest() && !repository.isGranted(),
            )
        }

        val queue = RecordingQueue()
        val vm = newViewModel(queue = queue)
        vm.load("sess-prompt-batch")
        awaitCondition("the scanned episodes") { vm.uiState.value.episodes.isNotEmpty() }

        vm.selectAllEpisodes()
        vm.startBatchDownload()

        // The batch's first real enqueue must open the same prompt gate.
        awaitCondition("the first batch submission") { queue.submissions.isNotEmpty() }
        awaitCondition("the prompt flag during the batch") {
            vm.uiState.value.requestNotificationPermission
        }

        vm.onNotificationPermissionResult(false)
        awaitCondition("the cleared prompt flag") {
            !vm.uiState.value.requestNotificationPermission
        }
        runBlocking {
            assertFalse(
                "the batch path consumes the same asked-once latch",
                repository.shouldRequest(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // a_… — DECISIONS 95.A, the in-flight window: an answer landing WHILE a
    // gate is suspended between its guard read and its arming must not re-arm
    // -----------------------------------------------------------------------

    @Test
    fun a_denied_anAnswerLandingDuringTheGateSuspensionNeverRearmsThePrompt() {
        Assume.assumeFalse(
            "baseline is GRANTED and revoking would kill the running test " +
                "process — run from a fresh install",
            runBlocking { repository.isGranted() },
        )
        runBlocking {
            assertTrue(
                "the setup requires a fresh asked-once latch",
                repository.shouldRequest(),
            )
        }

        // The gate suspends INSIDE shouldRequest() — exactly the window 95.A
        // names: the guard was already read as false when the suspension began.
        val latching = LatchingPromptRepository(appConfig, context)
        val vm = newViewModel(permissionRepository = latching)
        enqueueSingleEpisode(vm, number = 1f)
        awaitCondition("the gate suspended inside shouldRequest()") {
            latching.gateEntered.isCompleted
        }

        // The user answers while the gate is suspended — the real launcher's
        // only callback, arriving mid-check.
        vm.onNotificationPermissionResult(false)

        // Release the suspension: the gate resumes AFTER the answer landed.
        latching.resumeGate.complete(Unit)

        Thread.sleep(700L)
        assertFalse(
            "95.A: an answer landed mid-suspension — the resumed gate must " +
                "re-read the guard and never re-arm the prompt",
            vm.uiState.value.requestNotificationPermission,
        )
        awaitCondition("the answer consumed the asked-once latch") {
            runBlocking { !repository.shouldRequest() }
        }
    }

    /**
     * [NotificationPermissionRepository.shouldRequest] held on a latch — the
     * suspension point the 95.A in-flight window is about. Returns true
     * regardless of the real latch, so the ONLY thing that can suppress the
     * arming after the resume is the guard re-read itself (the contract
     * under test; the real latch truth is asserted through `repository`).
     */
    private class LatchingPromptRepository(
        appConfig: AppConfig,
        context: Context,
    ) : NotificationPermissionRepository(appConfig, context) {
        val gateEntered = CompletableDeferred<Unit>()
        val resumeGate = CompletableDeferred<Unit>()

        override suspend fun shouldRequest(): Boolean {
            gateEntered.complete(Unit)
            resumeGate.await()
            return true
        }
    }

    // -----------------------------------------------------------------------
    // z_… — DECISIONS 95.A, an already-granted permission never launches a
    // dialog (runs LAST: it is the only test allowed to change the permission,
    // and only in the safe direction)
    // -----------------------------------------------------------------------

    @Test
    fun z_granted_anAlreadyGrantedPermissionNeverPrompts() {
        ensureRealPermissionGranted()
        runBlocking {
            assertTrue(
                "the setup requires a GRANTED permission",
                repository.isGranted(),
            )
            assertTrue(
                "the latch is still fresh — only the grant may suppress the ask",
                repository.shouldRequest(),
            )
        }

        val vm = newViewModel()
        enqueueSingleEpisode(vm, number = 1f)

        Thread.sleep(700L)
        assertFalse(
            "no prompt may be offered when the app can already post",
            vm.uiState.value.requestNotificationPermission,
        )
    }

    // -----------------------------------------------------------------------
    // Fixtures — shared androidTest doubles (BatchDeviceFixtures.kt)
    // -----------------------------------------------------------------------

    private fun newViewModel(
        queue: EpisodeDownloadQueue = RecordingQueue(),
        permissionRepository: NotificationPermissionRepository = repository,
    ): AnimeDetailsViewModel = AnimeDetailsViewModel(
        episodeRepository = EpisodeRepository(
            animeRepository = AnimeRepository(ScriptedHttpClient()),
            scanCacheRepository = ScanCacheRepository(EmptyScanCacheDao()),
        ),
        streamResolver = ScriptedResolver().also { it.holdEp2 = false },
        downloadQueue = queue,
        configProvider = { AppContext.defaults() },
        // DECISIONS 95.A: the REAL permission-state repository over the REAL
        // DataStore — exactly what AppViewModelFactory injects in production
        // (AppContainer.notificationPermissionRepository). The gate-race test
        // overrides this with a suspension-controlling subclass.
        notificationPermissionRepository = permissionRepository,
    )

    /** load (first call only) → select (auto-resolves) → hand off to the queue. */
    private fun enqueueSingleEpisode(vm: AnimeDetailsViewModel, number: Float) {
        if (vm.uiState.value.episodes.isEmpty()) {
            vm.load("sess-prompt")
            awaitCondition("the scanned episodes") { vm.uiState.value.episodes.isNotEmpty() }
        }
        val episode = vm.uiState.value.episodes.first { it.number == number }
        vm.selectEpisode(episode)
        awaitCondition("the resolved stream for episode $number") {
            vm.uiState.value.resolvedStream != null
        }
        vm.downloadResolvedEpisode()
        awaitCondition("the queued state for episode $number") { vm.uiState.value.enqueued }
    }

    // -----------------------------------------------------------------------
    // Real permission state via the test harness shell (API 33+ only)
    // -----------------------------------------------------------------------

    /**
     * GRANT ONLY — never revoke. See the class KDoc: a revoke force-stops the
     * running app process (and with it this instrumentation run).
     */
    private fun ensureRealPermissionGranted() {
        if (Build.VERSION.SDK_INT < 33) return
        if (runBlocking { repository.isGranted() }) return
        // `pm` defaults to user 0, but on this multi-user device the app lives
        // under THIS process's user — without an explicit --user the command
        // fails with "package not found" and silently changes nothing.
        // (UserHandle.getIdentifier() is not public API; the public toString()
        // yields "UserHandle{10}". A format change makes the command fail loudly
        // via the stdout check below, never silently.)
        val selfHandle = Process.myUserHandle().toString()
        val userId = selfHandle.substringAfter('{').substringBefore('}')
        val command = "pm grant --user $userId " +
            "com.pahebatcher.app android.permission.POST_NOTIFICATIONS"
        try {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand(command)
            // Drain to EOF so the command really finishes before returning —
            // and keep the output: a failed pm command reports on stdout.
            val output = StringBuilder()
            FileInputStream(pfd.fileDescriptor).use { stream ->
                val buffer = ByteArray(1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    output.append(String(buffer, 0, read))
                }
            }
            pfd.close()
            val text = output.toString().trim()
            if (text.contains("Failure") || text.contains("Error")) {
                throw AssertionError("$command -> $text")
            }
        } catch (e: Exception) {
            throw AssertionError("shell command failed: $command", e)
        }
    }
}
