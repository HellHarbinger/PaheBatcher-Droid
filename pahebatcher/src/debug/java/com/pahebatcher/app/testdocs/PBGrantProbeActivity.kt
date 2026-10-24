package com.pahebatcher.app.testdocs

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Phase 13 SAF test infrastructure — debug builds only.
 *
 * Trivial receiver activity used ONLY as the target of grant-carrying
 * intents in instrumentation tests (DECISIONS 92.3 test surface). It does
 * nothing but finish — unless launched with extra `keepAlive=true`, in which
 * case it stays alive for 20 seconds.
 *
 * Why keepAlive matters: intent-delivered URI grants are OWNED by the
 * receiving ActivityRecord; when that record is destroyed the grant is
 * revoked. The production flow takes the grant while the receiving activity
 * (the picker caller) is still alive. Tests reproduce that by keeping this
 * receiver alive until takePersistableUriPermission has run; the taken
 * grant is persisted (uid-scoped) and survives the later finish.
 */
class PBGrantProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No URI logging: grant-carrying intent data must never reach logcat
        // (AGENTS §10 — the probe is launched only from in-package callers,
        // see the debug manifest's exported="false").
        if (intent?.getBooleanExtra(EXTRA_KEEP_ALIVE, false) == true) {
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, KEEP_ALIVE_MS)
        } else {
            finish()
        }
    }

    companion object {
        const val EXTRA_KEEP_ALIVE = "keepAlive"
        const val KEEP_ALIVE_MS = 20_000L
    }
}
