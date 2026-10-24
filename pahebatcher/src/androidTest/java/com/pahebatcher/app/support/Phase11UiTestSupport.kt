package com.pahebatcher.app.support

import android.app.Activity
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pahebatcher.app.MainActivity
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.data.local.entity.PlaybackPositionEntity
import com.pahebatcher.app.domain.download.EpisodeId
import com.pahebatcher.app.domain.model.EpisodeInfo
import com.pahebatcher.app.ui.navigation.PlayerRoute
import com.pahebatcher.app.ui.player.PlayerViewModel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Shared helpers for Phase 11 UI instrumentation tests (§36-3/4/5/10/13).
 *
 * All back-stack reads go through [backStackSize]/[isOnRoute] helpers that are
 * evaluated ON THE MAIN THREAD via [ActivityScenario.onActivity] or
 * [AndroidComposeTestRule.runOnIdle], so assertions observe a consistent
 * snapshot of the live NavController (the §36-4/§36-5 test seam on MainActivity).
 */

/** Active back-stack size (saved-but-popped tab state is NOT counted). */
fun backStackSize(activity: Activity): Int {
    val nav = (activity as? MainActivity)?.navController ?: return -1
    val entries = nav.currentBackStack.value
    // NavController keeps the navigation GRAPH itself at the bottom of its
    // back stack — the meaningful size counts navigable destinations only.
    val graphOffset = if (entries.firstOrNull()?.destination is androidx.navigation.NavGraph) 1 else 0
    return entries.size - graphOffset
}

/** Whether the active back stack's top destination is of type [T]. */
inline fun <reified T : Any> isOnRoute(activity: Activity): Boolean {
    val nav = (activity as? MainActivity)?.navController ?: return false
    return nav.currentBackStackEntry?.destination?.hasRoute<T>() == true
}

/** Current back-stack size from an ActivityScenario (main-thread read). */
fun ActivityScenario<*>.stackSize(): Int {
    var size = -1
    onActivity { size = backStackSize(it) }
    return size
}

/** Top-route check from an ActivityScenario (main-thread read). */
inline fun <reified T : Any> ActivityScenario<*>.onTopRoute(): Boolean {
    var onRoute = false
    onActivity { onRoute = isOnRoute<T>(it) }
    return onRoute
}

/**
 * §36-10: whether the IME (software keyboard) window is currently visible for
 * the activity's window — the normative "no IME over the next screen" signal.
 */
fun imeVisible(activity: Activity): Boolean {
    val insets = activity.window.decorView.rootWindowInsets ?: return false
    return insets.isVisible(WindowInsets.Type.ime())
}

// TEST-008: `ActivityScenario.isImeVisible()` was deleted here —
// zero callers, superseded by `isImeVisibleOnActivity()` (the main-thread-safe
// `runOnIdle` form NavigationTest uses) and the private `imeVisible(activity)`.

/**
 * IME visibility snapshot from an [AndroidComposeTestRule] (the rule exposing
 * the launched MainActivity) — evaluated on the main thread via [runOnIdle].
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.isImeVisibleOnActivity(): Boolean {
    var visible = false
    runOnIdle { visible = imeVisible(activity) }
    return visible
}

/**
 * Every visible text string in the MERGED semantics tree (flat across nodes
 * carrying multiple text lines). Used for the §36-3/§36-13 EXACT-composition
 * assertions: if an invented row or dead settings row existed, its text would
 * appear here.
 */
fun androidx.compose.ui.test.junit4.ComposeTestRule.allTexts(): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes()
        .flatMap { node ->
            val texts = node.config[SemanticsProperties.Text]
            texts.map { annotated -> annotated.text }
        }

/** Content descriptions ending in [suffix] (e.g. `" poster"`) across the tree. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.contentDescriptionsWithSuffix(
    suffix: String,
): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        .fetchSemanticsNodes()
        .flatMap { node ->
            val descriptions = node.config[SemanticsProperties.ContentDescription]
            descriptions.filter { it.endsWith(suffix) }
        }

/** Poll until [block] is true or [timeoutMs] elapses; returns the last evaluation. */
fun awaitCondition(timeoutMs: Long = 5_000, intervalMs: Long = 100, block: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (block()) return true
        Thread.sleep(intervalMs)
    }
    return block()
}

/**
 * [awaitCondition] variant that tolerates transient exceptions — used around
 * Activity recreation windows (§36-6 rotation) where `onActivity`/`runOnIdle`
 * legitimately throw while the scenario has no RESUMED instance yet.
 */
fun awaitConditionQuietly(
    timeoutMs: Long = 5_000,
    intervalMs: Long = 100,
    block: () -> Boolean,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            if (block()) return true
        } catch (_: Exception) {
            // transitional lifecycle window — retry
        }
        Thread.sleep(intervalMs)
    }
    return try {
        block()
    } catch (_: Exception) {
        false
    }
}

// ---------------------------------------------------------------------------
// Player instrumentation helpers (§36-6 player lifecycle, §36-5 player case)
// ---------------------------------------------------------------------------

/** Every content description in the MERGED semantics tree (flat). */
fun ComposeTestRule.allContentDescriptions(): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        .fetchSemanticsNodes()
        .flatMap { node -> node.config[SemanticsProperties.ContentDescription] }

private val TIME_PATTERN = Regex("""^(\d+):(\d{2})(?::(\d{2}))?$""")

/** Parses Media3 `Util.getStringForTime` labels ("M:SS" or "H:MM:SS") to seconds. */
fun parseTimeToSeconds(text: String): Double? {
    val match = TIME_PATTERN.matchEntire(text.trim()) ?: return null
    val first = match.groupValues[1].toDouble()
    val second = match.groupValues[2].toDouble()
    val third = match.groupValues[3]
    return if (third.isEmpty()) {
        first * 60 + second
    } else {
        first * 3600 + second * 60 + third.toDouble()
    }
}

/** All rendered player time labels (position + duration) in seconds. */
fun ComposeTestRule.playerTimeSeconds(): List<Double> =
    allTexts().mapNotNull { parseTimeToSeconds(it) }

// TEST-008: `playerPositionSeconds()` was deleted here — zero callers,
// superseded by the direct `playerTimeSeconds().minOrNull()` at the two call
// sites in PlayerLifecycleTest (the helper invited the wrong probe for a site).

/** Current media duration in seconds — the MAXIMUM rendered time. */
fun ComposeTestRule.playerDurationSeconds(): Double? = playerTimeSeconds().maxOrNull()

/**
 * §36-6 play/pause probe via the Media3 play/pause control semantics: its
 * content description is exactly "Pause" while playback is active and "Play"
 * while idle/paused (PlayerDefaults.PlayPauseButton contract, verified from
 * the media3-ui-compose-material3 1.11.1 sources).
 */
fun ComposeTestRule.isPlayerPlaying(): Boolean =
    allContentDescriptions().any { it == "Pause" }

/** Inverse probe: the Play control is exposed and no Pause control exists. */
fun ComposeTestRule.isPlayerPaused(): Boolean {
    val descriptions = allContentDescriptions()
    return descriptions.any { it == "Play" } && descriptions.none { it == "Pause" }
}

/**
 * Current values of every progress indicator's ProgressBarRangeInfo in the
 * tree (0..1) — the progress slider's rendered thumb fraction. Differential
 * diagnostic against the position TimeText: both are driven by separate
 * ProgressStateJob loops (TickCount vs TickInterval); comparing them
 * isolates whether Media3's progress-update machinery runs at all on this
 * screen. (Compose 1.12 renamed semantics RangeInfo → ProgressBarRangeInfo.)
 */
fun ComposeTestRule.sliderProgressValues(): List<Float> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .fetchSemanticsNodes()
        .mapNotNull { node -> node.config[SemanticsProperties.ProgressBarRangeInfo] }
        .map { info -> info.current }

/** Immutable snapshot of the entry-scoped ExoPlayer's live state (§36-6 probing). */
data class PlayerProbe(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val playbackState: Int,
    /** Buffered position — 0 with state=BUFFERING indicates read starvation. */
    val bufferedPositionMs: Long,
)

/**
 * Direct state of the top player entry's VM-owned ExoPlayer — the §36-6
 * authoritative progress signal, never derived from rendered text (the
 * on-screen TimeText is asserted separately for presence/liveness). Must be
 * read on the main thread (ExoPlayer is single-threaded); returns null when
 * the top entry is not the player route or its player does not exist yet.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
fun playerSnapshot(activity: Activity): PlayerProbe? {
    val nav = (activity as? MainActivity)?.navController ?: return null
    val entry = nav.currentBackStackEntry ?: return null
    if (!entry.destination.hasRoute<PlayerRoute>()) return null
    val vm = ViewModelProvider(entry)[PlayerViewModel::class.java]
    val player = vm.uiState.value.player ?: return null
    return PlayerProbe(
        positionMs = player.currentPosition,
        durationMs = player.duration,
        isPlaying = player.isPlaying,
        playWhenReady = player.playWhenReady,
        playbackState = player.playbackState,
        bufferedPositionMs = player.bufferedPosition,
    )
}

/**
 * Main-thread [playerProbe] for the compose rule; returns null (never throws)
 * during transitional lifecycle windows such as activity recreation (§36-6
 * rotation), so callers can poll with [awaitCondition].
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.playerProbe(): PlayerProbe? {
    var probe: PlayerProbe? = null
    try {
        runOnIdle { probe = playerSnapshot(activity) }
    } catch (_: Exception) {
        // transitional lifecycle window — the caller retries
        return null
    }
    return probe
}

/** Target application context for instrumentation-owned fixtures. */
fun playerTestContext() = InstrumentationRegistry.getInstrumentation().targetContext

@Volatile
private var cachedMediaPath: String? = null

/**
 * Real, genuinely playable 60 s MP4 (320x240, libopenh264) generated once via
 * FFmpegKit and cached in filesDir (§36-6 needs real local media — a fake file
 * would fail honestly at `ExoPlayer.prepare()`). Subsequent calls, including
 * later processes, reuse the cached file.
 *
 * `-g 25` forces a keyframe every second: without it libopenh264 emits a
 * single keyframe at t=0 for the whole 60 s (verified by pulling the file and
 * parsing `stss` → sync_count=1), which made any mid-file position restore
 * undecodable — the decoder receives only inter-frames with no reference and
 * never outputs, freezing ExoPlayer in BUFFERING at exactly the restored
 * position (observed at 8000 ms). Real downloaded episodes have regular
 * keyframes; the GOP flag makes the fixture match that reality.
 */
fun ensureTestMp4(): String {
    cachedMediaPath?.let { cached -> if (File(cached).exists()) return cached }
    val dir = File(playerTestContext().filesDir, "phase11_media").apply { mkdirs() }
    val output = File(dir, "player_media.mp4")
    if (!output.exists() || output.length() == 0L) {
        val command = "-y -f lavfi -i color=c=navy:s=320x240:d=60:r=25 " +
            "-c:v libopenh264 -g 25 -pix_fmt yuv420p -f mp4 ${output.absolutePath}"
        val session = FFmpegKit.execute(command)
        check(ReturnCode.isSuccess(session.returnCode)) {
            "test media generation failed: ${session.output}"
        }
    }
    cachedMediaPath = output.absolutePath
    return output.absolutePath
}

/**
 * Seeds [count] COMPLETED episodes (single session, run-unique ids) whose
 * `outputPath` is a PER-EPISODE COPY of the cached MP4 [mediaPath] —
 * invariant-safe against the startup DownloadStateReconciler (`outputUri`
 * NULL + existing non-empty output is a valid COMPLETED invariant;
 * `segmentDir` NULL skips segment cleanup).
 *
 * Slice 6 isolation (§30): production reconciliation now runs with the real
 * MediaStore exporter, so a process restart over previously seeded rows would
 * EXPORT each row's local file and then delete it (X6). Every episode
 * therefore gets its own copy in a fresh per-call directory: such an export
 * can never reach the shared cached MP4 every player test depends on, and
 * rows never share one file (removing one copy cannot invalidate a sibling
 * row). Stale directories from earlier seed calls are removed first — their
 * rows are wiped by the `deleteAll()` below.
 *
 * Wipes ALL sessions first so Downloads list composition is deterministic.
 * Returns the ids in episode order (1..count).
 *
 * @param canonicalIds when true, every seeded row's id is the PRODUCTION shape
 *   ([EpisodeId.build] = `{animeSession}_{epStr}_{audio}`) so it round-trips
 *   through `DownloadRepository.reEnqueue`'s identity guard. Only tests that
 *   actually re-enqueue or confirm a replacement over a seeded row (which
 *   reaches that guard) need this. The DEFAULT stays the historical
 *   `p11-ep-<n>-<i>` form, whose ids deliberately FAIL `EpisodeId.parse` — so
 *   STREAM-mode player loads over a seed fail locally (shape gate) and never
 *   enter the reconstruction ladder against a synthetic session.
 */
fun seedCompletedEpisodes(
    db: PaheDatabase,
    mediaPath: String,
    title: String = "Phase11 Player Test",
    count: Int = 1,
    canonicalIds: Boolean = false,
): List<String> {
    val sessionId = "p11-${System.nanoTime()}"
    val base = System.nanoTime()
    val ids = (1..count).map { index ->
        if (canonicalIds) {
            // `index` here is already 1-based (1..count), matching the
            // `episodeNumber = (index + 1)` the entity loop below derives from
            // its 0-based `forEachIndexed` index — both are the episode number.
            EpisodeId.build(
                sessionId,
                EpisodeInfo(
                    number = index.toFloat(),
                    session = "es-$sessionId-$index",
                    title = title,
                    fansub = "test",
                    audio = "sub",
                    playUrl = "https://animepahe.local/play/$sessionId-$index",
                ),
            )
        } else {
            "p11-ep-$base-$index"
        }
    }
    val now = System.currentTimeMillis()
    playerTestContext().filesDir
        .listFiles { f -> f.isDirectory && f.name.startsWith("p11_seed_") }
        ?.forEach { it.deleteRecursively() }
    val seedDir = File(playerTestContext().filesDir, "p11_seed_$base").apply { mkdirs() }
    runBlocking {
        db.downloadSessionDao().deleteAll()
        Phase10TestSupport.insertSession(db, sessionId)
        ids.forEachIndexed { index, episodeId ->
            val copy = File(seedDir, "ep_${index + 1}.mp4")
            File(mediaPath).copyTo(copy, overwrite = true)
            db.episodeDownloadDao().upsert(
                EpisodeDownloadEntity(
                    episodeId = episodeId,
                    sessionId = sessionId,
                    episodeNumber = (index + 1).toFloat(),
                    episodeSession = "es-$episodeId",
                    title = title,
                    fansub = "test",
                    audio = "sub",
                    playUrl = "https://animepahe.local/play/$episodeId",
                    totalSegments = 10,
                    completedSegments = 10,
                    downloadedBytes = copy.length(),
                    state = EpisodeDownloadEntity.STATE_COMPLETED,
                    outputPath = copy.absolutePath,
                    // Newest-first row order: episode 1 lists first.
                    createdAt = now - index,
                    updatedAt = now - index,
                    segmentDir = null,
                ),
            )
        }
    }
    return ids
}

/** Persists a §29 playback-position row (cold-entry restore fixture). */
fun saveTestPlaybackPosition(
    db: PaheDatabase,
    episodeId: String,
    positionMs: Long,
    durationMs: Long,
) {
    runBlocking {
        db.playbackPositionDao().upsert(
            PlaybackPositionEntity(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}

/** The player route's episodeId while a player entry is top, else null. */
fun currentPlayerEpisodeId(activity: Activity): String? {
    val nav = (activity as? MainActivity)?.navController ?: return null
    val entry = nav.currentBackStackEntry ?: return null
    if (!entry.destination.hasRoute<PlayerRoute>()) return null
    return entry.toRoute<PlayerRoute>().episodeId
}

/** Main-thread snapshot of [currentPlayerEpisodeId] (null mid-recreation). */
fun ActivityScenario<*>.playerEpisodeId(): String? {
    var episodeId: String? = null
    try {
        onActivity { episodeId = currentPlayerEpisodeId(it) }
    } catch (_: Exception) {
        // transitional lifecycle window — the caller retries
    }
    return episodeId
}

/**
 * Identity hash of the current activity — a real system rotation replaces the
 * instance (the manifest declares no `android:configChanges`, so the system
 * recreates MainActivity with `isChangingConfigurations = true`, the genuine
 * §24.4 path). Returns -1 while the scenario is mid-recreation.
 */
fun ActivityScenario<*>.activityIdentity(): Int {
    var identity = -1
    try {
        onActivity { identity = System.identityHashCode(it) }
    } catch (_: Exception) {
        // transitional lifecycle window — the caller retries
    }
    return identity
}

private fun shellOutput(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command)
    val text = descriptor.use { pfd ->
        java.io.FileInputStream(pfd.fileDescriptor).use { stream ->
            stream.bufferedReader().readText()
        }
    }
    return text.trim()
}

/** Reads a `system` settings key (test-harness only, §36-6 rotation fixtures). */
fun readSystemSetting(name: String): String = shellOutput("settings get system $name")

/** Writes a `system` settings key — callers MUST restore the original value. */
fun writeSystemSetting(name: String, value: String) {
    shellOutput("settings put system $name $value")
}
