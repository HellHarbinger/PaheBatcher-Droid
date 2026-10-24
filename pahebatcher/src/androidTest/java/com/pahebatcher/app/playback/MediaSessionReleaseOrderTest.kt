package com.pahebatcher.app.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F-01 (PHASE14_PLAN §11) — the MediaSession release must be ORDERED before
 * PlayerViewModel.onCleared() releases the ExoPlayer.
 *
 * `onCleared()` runs on the main thread and calls, in this order:
 *
 *     mediaSessionHost.onPlayerReleased()
 *     exo.release()
 *
 * So the session release can only land after the player release if it is still
 * pending when `onPlayerReleased()` returns (the pre-fix `scope.launch`
 * behaviour). The contract under test is therefore behavioural, not a
 * "method A called before method B" spy check: **once `onPlayerReleased()`
 * returns, the session must already be released.**
 *
 * That is observed through media3 itself rather than field bookkeeping:
 * `MediaSession.release()` synchronously frees the session's id in media3's
 * static `SESSION_ID_TO_SESSION_MAP`, and the `MediaSession` constructor
 * rejects a duplicate id ("Session ID must be unique"). If the release were
 * still queued on another thread when the call returned, rebuilding the same
 * id would throw immediately — so the rebuild succeeding proves `release()`
 * already executed.
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionReleaseOrderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var host: MediaSessionHost
    private var rebuilt: MediaSession? = null
    private var playerReleased = false
    private var priorHolder: MediaSessionHost? = null

    /** Unique per run so a crashed run can never poison the static id map. */
    private val sessionId = "f01_release_order_${System.nanoTime()}"

    @Before
    fun setUp() {
        priorHolder = MediaSessionHostHolder.host
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build()
            session = MediaSession.Builder(context, player).setId(sessionId).build()
        }
        host = MediaSessionHost(context)
        host.player = player
        host.episodeId = "episode-f01"
        host.session = session
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            rebuilt?.release()
            rebuilt = null
            // Session.release() tolerates a second call (the id removal is a
            // no-op and impl.release() is guarded), so this is safe on both
            // the pass and the early-failure paths.
            runCatching { session.release() }
            if (!playerReleased) {
                player.release()
            }
        }
        MediaSessionHostHolder.host = priorHolder
    }

    @Test
    fun `onPlayerReleased completes the session release before it returns`() {
        // Same thread as PlayerViewModel.onCleared(): main.
        instrumentation.runOnMainSync { host.onPlayerReleased() }

        // (1) The host must be fully detached at return — nothing left queued.
        assertNull("the host must detach the session synchronously", host.session)
        assertNull("the host must detach the player reference synchronously", host.player)
        assertNull("the host must clear the episode id synchronously", host.episodeId)

        // (2) The release must have ACTUALLY executed, observed through media3:
        //     the static session-id registry is only freed by release(), so the
        //     same id can be rebuilt only if release() already ran.
        var thrown: Throwable? = null
        instrumentation.runOnMainSync {
            rebuilt = try {
                MediaSession.Builder(context, player).setId(sessionId).build()
            } catch (t: Throwable) {
                thrown = t
                null
            }
        }
        assertNotNull(
            "the session id must be free again — release() must have completed before " +
                "onPlayerReleased() returned, otherwise exo.release() would race it " +
                "(media3 said: ${thrown?.message})",
            rebuilt,
        )

        // (3) Only now does the production sequence proceed to the player
        //     teardown — guaranteed to happen AFTER the session release.
        instrumentation.runOnMainSync {
            player.release()
            playerReleased = true
        }
    }
}
