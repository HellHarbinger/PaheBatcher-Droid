package com.pahebatcher.app.notification

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pahebatcher.app.data.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible

/**
 * Phase 11 §20 notification-permission architecture.
 *
 * Exactly two persistent flags live in the app-config store:
 *
 *  - [AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE]: the first-trigger signal. It stays false
 *    until the app has actually issued the one-time runtime request on Android 13+; after the
 *    request is made it is permanently true so no other screen ever re-triggers it automatically.
 *  - [AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED]: the last observed outcome. PB-007
 *    (DECISIONS 159): in production this flag is WRITE-ONLY today — a later re-grant in
 *    Settings is picked up by [isGranted] asking the platform directly, NOT by re-reading
 *    the key, and no production code reads the key back (only instrumented tests do). The
 *    original wording implied a write+read symmetry ("always refreshed opportunistically")
 *    that does not exist; A2.1 corrected the phrase here rather than deleting the key
 *    (deletion declined — Decision 8 of A2.1 keeps [AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED]), so this note records the current truth rather than a planned state.
 *
 * On API <= 32 there is no runtime prompt at all: `shouldRequest()` is false, `isGranted()` reports
 * the install-time permission state, and `recordRequestOutcome()` is a no-op. The first-trigger
 * requester itself (an Activity-launched `RequestPermission` contract issued at enqueue time)
 * belongs to the enqueue flow of Phase 13; this repository is the tested permission-state
 * architecture this phase mandates (§20, §36-15).
 *
 * `open` (and [shouldRequest] `open`) is a deliberate test seam: the DECISIONS 95.A in-flight
 * window — an answer landing while a gate is suspended INSIDE `shouldRequest()` — can only be
 * reproduced by a test subclass that controls that suspension point. No production subclass
 * exists (AGENTS §5: mocks live in tests only).
 */
open class NotificationPermissionRepository(
    private val appConfig: AppConfig,
    private val appContext: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    /** Whether the Android 13+ POST_NOTIFICATIONS runtime prompt applies to this device. */
    fun runtimeRequestApplies(): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /** True only for the single, one-time first trigger on Android 13+ (§20 "asked once"). */
    open suspend fun shouldRequest(): Boolean {
        if (!runtimeRequestApplies()) return false
        return !appConfig.getBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, false).first()
    }

    /** Current notification-posting grant state (always true on API <= 32). */
    suspend fun isGranted(): Boolean {
        if (!runtimeRequestApplies()) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Persist the outcome of the one-time request: the asked-once latch is consumed on the first
     * call and the granted flag mirrors the request result. Never writes on API <= 32.
     */
    suspend fun recordRequestOutcome(granted: Boolean) {
        if (!runtimeRequestApplies()) return
        appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_ASKED_ONCE, true)
        appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, granted)
    }

    /** Opportunistic refresh of the granted flag when the app has permission to check it. */
    suspend fun refreshGrantedFlag() {
        if (!runtimeRequestApplies()) return
        val granted = runInterruptible(Dispatchers.IO) {
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        appConfig.setBoolean(AppConfig.KEY_NOTIFICATION_PERMISSION_GRANTED, granted)
    }
}
