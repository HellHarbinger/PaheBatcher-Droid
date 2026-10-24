package com.pahebatcher.app.notification

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.data.config.AppConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §36-15 — Notification permission architecture (PHASE11_ARCHITECTURE_V2 §20,
 * F-11), against the REAL DataStore-backed [AppConfig] (flags are saved in
 * @Before and restored in @After so the device state is never left mutated):
 *
 *  - API 33+: grant/deny outcomes are recorded to the two DataStore flags and
 *    the asked-once latch prevents a second automatic request;
 *  - API 32: `recordRequestOutcome`/`shouldRequest` are strict no-ops (no
 *    runtime prompt exists below 33) and `isGranted` reports install-time truth;
 *  - channel reality (Phase 14): exactly the FOUR app channels exist —
 *    `download_progress` (LOW) plus `download_complete` / `download_failure` /
 *    `watchlist_new_episodes` (DEFAULT), PHASE14_PLAN §8 — created together by
 *    one idempotent `NotificationChannels.ensure()`;
 *  - posting (Phase 14): the terminal/watchlist post paths are safe whatever
 *    the platform's posting decision is.
 */
class NotificationPermissionTest {

    private lateinit var appConfig: AppConfig
    private lateinit var context: Context
    private var askedBefore = false
    private var grantedBefore = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        appConfig = (context.applicationContext as PaheBatcherApplication).appContainer.appConfig
        runBlocking {
            askedBefore =
                appConfig.getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, false).first()
            grantedBefore =
                appConfig.getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, false).first()
        }
    }

    @After
    fun tearDown() {
        // Never leave the real device's permission-latch state mutated.
        runBlocking {
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, askedBefore)
            appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, grantedBefore)
        }
    }

    private fun askedOnce(): Boolean = runBlocking {
        appConfig.getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, false).first()
    }

    private fun grantedFlag(): Boolean = runBlocking {
        appConfig.getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, false).first()
    }

    private suspend fun resetFlags() {
        appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, false)
        appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, false)
    }

    @Test
    fun api33GrantIsRecordedOnceAndLatched() = runBlocking {
        resetFlags()
        val repository = NotificationPermissionRepository(appConfig, context, sdkInt = 33)

        assertTrue(repository.runtimeRequestApplies())
        assertTrue("first trigger must be allowed", repository.shouldRequest())
        assertFalse(askedOnce())
        assertFalse(grantedFlag())

        // Grant outcome recorded: latch consumed + granted flag true.
        repository.recordRequestOutcome(granted = true)
        assertTrue(askedOnce())
        assertTrue(grantedFlag())
        assertFalse("asked-once latch blocks any second automatic request", repository.shouldRequest())
    }

    @Test
    fun api33DenyIsRecordedWithTheSameLatch() = runBlocking {
        resetFlags()
        val repository = NotificationPermissionRepository(appConfig, context, sdkInt = 33)

        repository.recordRequestOutcome(granted = false)
        assertTrue(askedOnce())
        assertFalse("deny outcome persisted", grantedFlag())
        assertFalse(repository.shouldRequest())
    }

    @Test
    fun api33RefreshGrantedFlagMirrorsTheRealPermissionState() = runBlocking {
        resetFlags()
        val repository = NotificationPermissionRepository(appConfig, context, sdkInt = 33)

        repository.refreshGrantedFlag()

        val actualGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        assertEquals(
            "GRANTED flag mirrors checkSelfPermission after refresh",
            actualGranted,
            grantedFlag(),
        )
    }

    @Test
    fun api32IsStrictlyANoOp() = runBlocking {
        resetFlags()
        val repository = NotificationPermissionRepository(appConfig, context, sdkInt = 32)

        assertFalse("no runtime prompt below API 33", repository.runtimeRequestApplies())
        assertFalse("shouldRequest never fires on API 32", repository.shouldRequest())
        assertTrue("install-time permission state is reported as granted", repository.isGranted())

        // Writes must be skipped entirely — both flags stay untouched.
        repository.recordRequestOutcome(granted = true)
        assertFalse("API 32 must never write the asked-once latch", askedOnce())
        assertFalse("API 32 must never write the granted flag", grantedFlag())

        // Opportunistic refresh is equally a no-op.
        repository.refreshGrantedFlag()
        assertFalse(askedOnce())
        assertFalse(grantedFlag())
    }

    @Test
    fun fourAppChannelsExistWithTheShippedImportance() {
        // Creating through the real manager is idempotent (§20 channel owner).
        DownloadNotificationManager(context).createNotificationChannel()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels = notificationManager.notificationChannels

        // 4 app channels + 1 media3-owned channel (created by DefaultMediaNotificationProvider
        // when a MediaSession is active). The media3 channel is not ours — it is created
        // and owned by media3's notification provider.
        val appChannelIds = setOf(
            DownloadNotificationManager.CHANNEL_ID_DOWNLOADS,
            NotificationChannels.DOWNLOAD_COMPLETE,
            NotificationChannels.DOWNLOAD_FAILURE,
            NotificationChannels.WATCHLIST_NEW_EPISODES,
        )
        val appChannels = channels.filter { it.id in appChannelIds }
        assertEquals(
            "exactly the FOUR Phase 14 app channels (PHASE14_PLAN §8)",
            4,
            appChannels.size,
        )
        val byId = appChannels.associateBy { it.id }
        assertTrue(
            "progress channel must still exist untouched",
            byId.containsKey(DownloadNotificationManager.CHANNEL_ID_DOWNLOADS),
        )
        assertEquals(
            "download progress stays LOW (silent) — an active download never alerts",
            NotificationManager.IMPORTANCE_LOW,
            byId.getValue(DownloadNotificationManager.CHANNEL_ID_DOWNLOADS).importance,
        )
        assertFalse(
            "progress notifications carry no badge",
            byId.getValue(DownloadNotificationManager.CHANNEL_ID_DOWNLOADS).canShowBadge(),
        )
        // The three Phase 14 event channels are DEFAULT (banner + sound).
        listOf(
            NotificationChannels.DOWNLOAD_COMPLETE,
            NotificationChannels.DOWNLOAD_FAILURE,
            NotificationChannels.WATCHLIST_NEW_EPISODES,
        ).forEach { id ->
            val channel = byId[id]
            assertNotNull("missing channel $id", channel)
            assertEquals("channel $id must be DEFAULT", NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
            assertFalse("channel $id must not badge the launcher", channel.canShowBadge())
        }
    }

    /**
     * The terminal/watchlist post paths must be
     * safe under whatever the platform's posting decision is.
     *
     * Two honest branches, both real assertions:
     *
     *  - **Grant denied** (POST_NOTIFICATIONS not held, so
     *    `areNotificationsEnabled() == false`): Android DROPS the post — the
     *    contract on our side is "does not throw" plus "leaves nothing
     *    active". We can only OBSERVE this state, never create it:
     *    Android force-stops a running process whose runtime permission is
     *    revoked (`ActivityManager: Killing ...: permissions revoked`), which
     *    would abort this whole instrumentation run — the same constraint
     *    `FirstEnqueuePromptTest` documents as "GRANT ONLY — never revoke".
     *  - **Grant allowed**: the same three production post paths must complete
     *    without throwing and actually become active, proving the path is not
     *    a no-op by accident. The posted notifications are cancelled in
     *    `finally` so the device is left clean.
     *
     * Both gates are checked because they are the two things the platform
     * consults, and only their conjunction is guaranteed to match
     * `NotificationManager.getActiveNotifications()` in both states.
     */
    @Test
    fun postPathsAreSafeWhateverThePlatformPostingDecision() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val manager = DownloadNotificationManager(context)
        manager.createNotificationChannel()

        val episodeId = "ep-permission-branch"
        val completionId = manager.terminalIdFor(episodeId)
        val failureId = manager.terminalIdFor("$episodeId-2")
        val watchlistId = WatchlistNotificationManager.WATCHLIST_NOTIFICATION_ID
        val posted = listOf(completionId, failureId, watchlistId)
        val permissionHeld =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val platformAllowsPosts =
            permissionHeld && notificationManager.areNotificationsEnabled()

        try {
            manager.updateNotification(
                completionId,
                manager.buildCompletionNotification(episodeId, "Permission Episode"),
            )
            manager.updateNotification(
                failureId,
                manager.buildFailureNotification("$episodeId-2", "Permission Episode 2"),
            )
            WatchlistNotificationManager(context).post(newEpisodes = 1, seriesCount = 1)

            if (platformAllowsPosts) {
                posted.forEach { id ->
                    assertTrue(
                        "an allowed post must become active (id=$id)",
                        notificationManager.activeNotifications.any { it.id == id },
                    )
                }
            } else {
                assertTrue(
                    "a platform-refused post must leave NOTHING active",
                    notificationManager.activeNotifications.none { it.id in posted },
                )
            }
        } finally {
            posted.forEach { notificationManager.cancel(it) }
        }
    }

    @Test
    fun manifestDeclaresPostNotificationsForTheApi33Request() {
        if (Build.VERSION.SDK_INT < 33) return
        // Positive proof of declaration: the permission appears in requested permissions.
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()
        assertTrue(
            "POST_NOTIFICATIONS must be declared in the manifest",
            declared.contains(Manifest.permission.POST_NOTIFICATIONS),
        )
        // And the runtime state is one of the two real outcomes (granted or
        // requestable — an undeclared permission would make §20 unimplementable).
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        assertTrue(
            "permission state must be a real grant outcome",
            granted == PackageManager.PERMISSION_GRANTED ||
                granted == PackageManager.PERMISSION_DENIED,
        )
    }
}
