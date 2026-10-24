package com.pahebatcher.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.data.local.entity.EpisodeDownloadEntity
import com.pahebatcher.app.support.Phase10TestSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 13 (DECISIONS 105) + Phase 14 (PHASE14_PLAN §9, Decision 3) — the
 * canonical network-constraint rule, verified on REAL WorkManager constraints
 * (observable `WorkInfo`, not mocks):
 *
 *  1. ON → the enqueue-time `NetworkType.UNMETERED` constraint is attached —
 *     asserted through the PRODUCTION wiring (AppContainer's `downloadRepository`
 *     reading the persisted DataStore value), not an injected fake;
 *  2. OFF (the default, 105.5) → NO constraint object at all — byte-for-byte
 *     today's behavior;
 *  3. toggling NEVER rewrites already-enqueued work (105.2): the queued request
 *     keeps `UNMETERED` after the setting flips, while the NEXT enqueue after a
 *     terminal state reads the CURRENT value (105.3/105.4);
 *  4. the reconciliation re-enqueue site reads the CURRENT setting fresh, the
 *     third of the three build sites (105.4).
 *
 * Phase 14 extends the matrix to two toggles (§9):
 *
 * | Wi-Fi-only | Auto-resume | Attached constraint |
 * |---|---|---|
 * | OFF | OFF | none |
 * | OFF | ON | `CONNECTED` |
 * | ON | OFF | `UNMETERED` |
 * | ON | ON | `UNMETERED` (Wi-Fi dominates) |
 *
 * (The deferred-episode trigger — site 2 — is covered end to end in
 * `TriggerNextDeferredEpisodeTest`, which drives the production WorkerFactory.)
 */
@RunWith(AndroidJUnit4::class)
class WifiOnlyConstraintTest {

    private lateinit var context: Context
    private lateinit var db: PaheDatabase
    private lateinit var workManager: WorkManager
    private lateinit var appConfig: AppConfig

    private val runStamp = System.currentTimeMillis()
    private val sessionId = "sess-wifi-$runStamp"
    private val episodeId = "ep-wifi-$runStamp"
    private val uniqueName = "episode_download_$episodeId"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = PaheDatabase.getInstance(context)
        workManager = WorkManager.getInstance(context)
        appConfig = (context.applicationContext as PaheBatcherApplication).appContainer.appConfig
        Phase10TestSupport.grantNotificationPermission()
        Phase10TestSupport.insertSession(db, sessionId)
    }

    @After
    fun teardown() {
        runBlocking {
            // ALWAYS restore the default — the setting is process-wide
            // persistence and every other suite expects the shipped default.
            appConfig.updateWifiOnly(false)
        }
        workManager.cancelUniqueWork(uniqueName)
        Phase10TestSupport.deleteSession(db, sessionId)
        File(context.filesDir, "downloads/$episodeId").deleteRecursively()
    }

    /** QUEUED episode with a resolved hanging stream (stays unfinished → KEEP applies). */
    private suspend fun seedHangingEpisode() {
        val now = System.currentTimeMillis()
        db.episodeDownloadDao().upsert(
            EpisodeDownloadEntity(
                episodeId = episodeId,
                sessionId = sessionId,
                episodeNumber = 1.0f,
                episodeSession = "eps-$episodeId",
                title = "Wi-Fi Constraint Episode",
                fansub = "TestFansub",
                audio = "sub",
                playUrl = "https://example.com/play",
                state = EpisodeDownloadEntity.STATE_QUEUED,
                createdAt = now,
                updatedAt = now,
                m3u8Url = Phase10TestSupport.HANGING_URL,
                userAgent = "TestUA",
                referer = "https://example.com",
                segmentDir = Phase10TestSupport.segmentDirFor(context, episodeId),
            )
        )
    }

    private fun requiredNetworkTypeOf(name: String): NetworkType =
        workManager.getWorkInfosForUniqueWork(name).get()
            .single()
            .constraints
            .requiredNetworkType

    @Test
    fun productionWiring_attachesUnmetered_whenThePersistedSettingIsOn() {
        runBlocking {
            seedHangingEpisode()
            // The REAL container repository reading the REAL DataStore value —
            // exactly the production path a download enqueue takes.
            appConfig.updateWifiOnly(true)

            val container = (context.applicationContext as PaheBatcherApplication).appContainer
            container.downloadRepository.enqueueWorker(episodeId)

            assertEquals(
                "Wi-Fi-only ON must attach NetworkType.UNMETERED at enqueue time",
                NetworkType.UNMETERED,
                requiredNetworkTypeOf(uniqueName),
            )
        }
    }

    @Test
    fun defaultOff_attachesNoConstraintAtAll() {
        runBlocking {
            seedHangingEpisode()

            // Shipped default (105.5): updateWifiOnly was never called here.
            val container = (context.applicationContext as PaheBatcherApplication).appContainer
            container.downloadRepository.enqueueWorker(episodeId)

            assertEquals(
                "Wi-Fi-only OFF must behave exactly as before this phase (no constraint)",
                NetworkType.NOT_REQUIRED,
                requiredNetworkTypeOf(uniqueName),
            )
        }
    }

    @Test
    fun toggling_neverRewritesQueuedWork_butTheNextEnqueueReadsTheCurrentValue() {
        runBlocking {
            seedHangingEpisode()

            // Injected reader for the toggle sequence — same contract the
            // container provides (a fresh read per enqueue).
            var wifiOnly = false
            val repository = com.pahebatcher.app.data.download.DownloadRepository(
                context = context,
                dao = db.episodeDownloadDao(),
                sessionDao = db.downloadSessionDao(),
                wifiOnlyProvider = { wifiOnly },
            )

            wifiOnly = true
            repository.enqueueWorker(episodeId)
            assertEquals(
                "ON attaches UNMETERED",
                NetworkType.UNMETERED,
                requiredNetworkTypeOf(uniqueName),
            )

            // 105.2: the setting flips OFF while the request is queued/running —
            // KEEP hands back the SAME request; its constraints are never rewritten.
            wifiOnly = false
            repository.enqueueWorker(episodeId)
            assertEquals(
                "a queued request must keep the constraints it was built with",
                NetworkType.UNMETERED,
                requiredNetworkTypeOf(uniqueName),
            )

            // 105.3/105.4: once the chain is terminal, the NEXT enqueue builds a
            // NEW request that reads the CURRENT value (now OFF → unconstrained).
            workManager.cancelUniqueWork(uniqueName).result.get()
            val terminal = Phase10TestSupport.awaitUntil(30_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .all { it.state.isFinished }
            }
            assertTrue("the old chain must reach a terminal state", terminal)

            repository.enqueueWorker(episodeId)
            assertEquals(
                "the next enqueue must read the CURRENT (OFF) setting",
                NetworkType.NOT_REQUIRED,
                requiredNetworkTypeOf(uniqueName),
            )
        }
    }

    @Test
    fun reconciliationReenqueue_readsTheCurrentWifiOnlySetting() {
        runBlocking {
            seedHangingEpisode()

            val on = DownloadStateReconciler(
                episodeDao = db.episodeDownloadDao(),
                workManager = workManager,
                wifiOnlyProvider = { true },
            )
            on.reconcile()
            assertEquals(
                "the reconciliation site must attach UNMETERED when the setting is ON",
                NetworkType.UNMETERED,
                requiredNetworkTypeOf(uniqueName),
            )

            // Terminal, then reconcile again with the setting OFF — a NEW
            // request must reflect the CURRENT value (105.4).
            workManager.cancelUniqueWork(uniqueName).result.get()
            val terminal = Phase10TestSupport.awaitUntil(30_000) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .all { it.state.isFinished }
            }
            assertTrue(terminal)

            // Re-seed: cancelling a RUNNING worker persists its terminal state
            // (CANCELLED), and terminal rows are deliberately not re-enqueued —
            // the second run must see a queueable row, exactly like a fresh
            // reconciliation after a process restart.
            seedHangingEpisode()

            val off = DownloadStateReconciler(
                episodeDao = db.episodeDownloadDao(),
                workManager = workManager,
                wifiOnlyProvider = { false },
            )
            off.reconcile()
            assertEquals(
                "the reconciliation site must read the CURRENT (OFF) setting for a new request",
                NetworkType.NOT_REQUIRED,
                requiredNetworkTypeOf(uniqueName),
            )
        }
    }

    // ── Phase 14 (PHASE14_PLAN §9): the full 4-case matrix ──────────────────

    @Test
    fun autoResumeOn_wifiOnlyOff_attachesConnected() {
        runBlocking {
            seedHangingEpisode()

            // OFF/ON → CONNECTED: auto-resume ON, Wi-Fi-only OFF.
            val repository = com.pahebatcher.app.data.download.DownloadRepository(
                context = context,
                dao = db.episodeDownloadDao(),
                sessionDao = db.downloadSessionDao(),
                wifiOnlyProvider = { false },
                autoResumeProvider = { true },
            )
            repository.enqueueWorker(episodeId)

            assertEquals(
                "auto-resume ON with Wi-Fi-only OFF must attach NetworkType.CONNECTED",
                NetworkType.CONNECTED,
                requiredNetworkTypeOf(uniqueName),
            )
        }
    }

    @Test
    fun bothOn_attachesUnmetered_wifiOnlyDominates() {
        runBlocking {
            seedHangingEpisode()

            // ON/ON → UNMETERED: Wi-Fi-only dominates because UNMETERED subsumes CONNECTED.
            val repository = com.pahebatcher.app.data.download.DownloadRepository(
                context = context,
                dao = db.episodeDownloadDao(),
                sessionDao = db.downloadSessionDao(),
                wifiOnlyProvider = { true },
                autoResumeProvider = { true },
            )
            repository.enqueueWorker(episodeId)

            assertEquals(
                "both toggles ON must attach NetworkType.UNMETERED (Wi-Fi dominates)",
                NetworkType.UNMETERED,
                requiredNetworkTypeOf(uniqueName),
            )
        }
    }

    @Test
    fun autoResumeOn_wifiOnlyOff_reconciliationAttachesConnected() {
        runBlocking {
            seedHangingEpisode()

            // The reconciliation site must also apply the combined rule.
            val on = DownloadStateReconciler(
                episodeDao = db.episodeDownloadDao(),
                workManager = workManager,
                wifiOnlyProvider = { false },
                autoResumeProvider = { true },
            )
            on.reconcile()
            assertEquals(
                "reconciliation with auto-resume ON and Wi-Fi-only OFF must attach CONNECTED",
                NetworkType.CONNECTED,
                requiredNetworkTypeOf(uniqueName),
            )
        }
    }

    @Test
    fun bothOff_attachesNoConstraint() {
        runBlocking {
            seedHangingEpisode()

            // OFF/OFF → none: byte-for-byte today's behavior.
            val repository = com.pahebatcher.app.data.download.DownloadRepository(
                context = context,
                dao = db.episodeDownloadDao(),
                sessionDao = db.downloadSessionDao(),
                wifiOnlyProvider = { false },
                autoResumeProvider = { false },
            )
            repository.enqueueWorker(episodeId)

            assertEquals(
                "both toggles OFF must attach no constraint",
                NetworkType.NOT_REQUIRED,
                requiredNetworkTypeOf(uniqueName),
            )
        }
    }
}
