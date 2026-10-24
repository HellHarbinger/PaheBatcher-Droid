package com.pahebatcher.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.PlayerDefaults
import androidx.media3.ui.compose.material3.R as Media3R
import androidx.compose.ui.layout.ContentScale
import com.pahebatcher.app.R
import com.pahebatcher.app.ui.navigation.PlayerMode
import java.util.Locale


/**
 * Phase 11 §23/§24 player route content.
 *
 * - The VM-owned single ExoPlayer is handed to the Media3 [Player] surface; no
 *   player is constructed here (§24.1 — composables never build players).
 * - [playerLifecycle] wiring implements §24.3/§24.4: the observer is tied to
 *   the nav entry's LifecycleOwner (LocalLifecycleOwner inside a NavHost
 *   destination IS the NavBackStackEntry); pause whenever it drops below
 *   RESUMED; a configuration-change detach lets the next attach resume only if
 *   playback was running; every other detach/return path never auto-resumes.
 * - Controls (M3): play/pause, next/previous (in-VM playlist, §24.2), seek
 *   slider, position/duration, retry-on-error, back — tap the surface toggles
 *   visibility (rememberSaveable so rotation keeps the choice, §24.4).
 *   SPEC §15 episode navigation applies to STREAMING too: for a STREAM entry
 *   the centre controls are ours (see [StreamEpisodeControls]) and switch the
 *   episode in place through [onNext]/[onPrevious]; DOWNLOAD keeps Media3's
 *   own playlist skip controls byte-for-byte.
 * - The episode title overlay and Back are ALWAYS visible (never hidden with
 *   the controls) so Back stays reachable and TalkBack always finds both (§21).
 * - Orientation is never locked; video is aspect-fit via ContentScale.Fit
 *   (§24.4). Errors show the real Media3 message plus a real retry — no fake
 *   states (AGENTS §5).
 */
@OptIn(markerClass = [UnstableApi::class, ExperimentalApi::class])
@Composable
fun PlayerScreen(
    uiState: PlayerUiState,
    playerLifecycle: PlayerLifecycle,
    onBack: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryPlayback: () -> Unit,
    /** SPEC §15 — STREAM "next episode" (no-op outside the STREAM path). */
    onNext: () -> Unit = {},
    /** SPEC §15 — STREAM "previous episode" (no-op outside the STREAM path). */
    onPrevious: () -> Unit = {},
    /**
     * SPEC §15 "subtitles where available" — index into
     * [PlayerUiState.subtitleOptions], or `-1` for Off.
     */
    onSelectSubtitle: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showControls by rememberSaveable { mutableStateOf(true) }
    // SPEC §15 "subtitles where available" — the subtitle panel is open state,
    // hoisted here (not inside the control) so an outside tap can close it.
    var subtitleMenuOpen by rememberSaveable { mutableStateOf(false) }
    // …and it may only exist while the media really publishes text tracks: a
    // source change that drops them closes the panel too, so it can never
    // linger open and pop back up over the next episode.
    LaunchedEffect(uiState.subtitleOptions.isEmpty()) {
        if (uiState.subtitleOptions.isEmpty()) subtitleMenuOpen = false
    }
    // Latest UI state for the measure-pass placement guard (remeasureOn): its
    // write lands during recomposition on every VM state change (the §23
    // position tick included), which is what makes the guard observable.
    val playerState = rememberUpdatedState(uiState)

    // §24.3/§24.4 lifecycle observer — keyed to the nav entry's owner. The
    // owner token is the LifecycleOwner itself: identity survives within an
    // entry and differs across a configuration change, which is exactly what
    // the VM state machine needs to tell rotation from background return.
    DisposableEffect(lifecycleOwner, playerLifecycle) {
        val lifecycle = lifecycleOwner.lifecycle
        val ownerToken: Any = lifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> playerLifecycle.onPausedBelowResumed(ownerToken)

                Lifecycle.Event.ON_RESUME -> {
                    // Order-independent: a same-owner resume clears the pending
                    // identity pause (§24.3); a rotation attach resolves its own
                    // pending config-change resume (§24.4).
                    playerLifecycle.onResumed(ownerToken)
                    playerLifecycle.onAttached(ownerToken)
                }

                Lifecycle.Event.ON_DESTROY ->
                    playerLifecycle.onDetached(
                        ownerToken,
                        activity?.isChangingConfigurations == true,
                    )

                else -> Unit
            }
        }
        // addObserver catch-up synchronously replays ON_RESUME when the entry is
        // already RESUMED, so a freshly-composed attach never misses the signal.
        lifecycle.addObserver(observer)
        onDispose {
            playerLifecycle.onDetached(
                ownerToken,
                activity?.isChangingConfigurations == true,
            )
            lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Placement guard: re-request this root's measure+layout on every VM
            // state change so children never stay silently unplaced (dead Back
            // button). Full explanation in [remeasureOn].
            .remeasureOn(playerState)
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                // §23 tap-to-toggle, with one refinement: when the subtitle
                // panel is open and a tap outside the control reaches this
                // handler, close the panel rather than hiding the controls —
                // one gesture, one job. Taps on the control and on the panel's
                // entries never get here: they compose above the media surface
                // and consume their own taps.
                if (subtitleMenuOpen) {
                    subtitleMenuOpen = false
                } else {
                    showControls = !showControls
                }
            }
            // §21.7: the video surface exposes the episode title instead of
            // being focusable-dead; the clickable node above makes it
            // TalkBack-activatable (double-tap toggles controls).
            .semantics { contentDescription = uiState.title ?: "Video player" }
            .testTag("player_root"),
    ) {
        // Always-visible episode title (never hidden with the controls).
        uiState.title?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 56.dp, end = 56.dp),
            )
        }

        // Always-visible Back (reachable even when controls are hidden, §23).
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        when {
            uiState.loadError != null -> LoadErrorContent(
                message = uiState.loadError.orEmpty(),
                onRetryLoad = onRetryLoad,
            )

            // Genuine prepare only (§23): spinner while loading or before the
            // VM-owned player exists — never a fake buffering indicator.
            uiState.player == null || uiState.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }

            else -> Player(
                player = uiState.player,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit, // aspect-fit letterbox, §24.4
                showControls = showControls,
                // §23 position readout + seek slider driven by the VM's ticking
                // state (real ExoPlayer reads) instead of Media3's own
                // ProgressStateJob-backed left slot/slider, which proved inert in
                // this app (both froze at init-time values while playback
                // advanced — instrumented). Duration (right slot),
                // centre/top controls and error overlay stay Media3 defaults.
                // UI-008: the slider's drag/hold behavior holds a local drag
                // value and commits the ONE seek at onValueChangeFinished — see
                // the progressSlider slot below.
                centerControls = { player, visible ->
                    // SPEC §15 (episode navigation / next / previous) has to hold
                    // while STREAMING as well. DOWNLOAD keeps Media3's default
                    // centre controls byte-for-byte — its sibling COMPLETED rows
                    // already are one ExoPlayer playlist (§24.2). A STREAM entry
                    // gets ours, because only the ViewModel knows which episode
                    // comes next: the list is read from the session, never guessed.
                    if (uiState.mode == PlayerMode.STREAM) {
                        StreamEpisodeControls(
                            player = player,
                            visible = visible,
                            hasPrevious = uiState.siblingIndex > 0,
                            hasNext = uiState.siblingIndex in 0 until uiState.siblingEpisodeIds.size - 1,
                            onPrevious = onPrevious,
                            onNext = onNext,
                        )
                    } else {
                        PlayerDefaults.CenterControls(player, visible)
                    }
                },
                bottomControls = { player, visible ->
                    PlayerDefaults.BottomControls(
                        player = player,
                        visible = visible,
                        left = {
                            Text(
                                text = formatPlaybackTime(uiState.positionMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        },
                        progressSlider = {
                            val durationMs = uiState.durationMs
                            val tickerFraction = if (durationMs > 0L) {
                                (uiState.positionMs.toFloat() / durationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            // UI-008 (fixed 2026-10-20) — drag/hold contract per
                            // DECISION 98's commit-on-finish slider posture and the
                            // inventory's recorded fix shape: while a drag gesture is
                            // active the slider displays this LOCALLY HELD value and
                            // the 500 ms ticker (which keeps advancing uiState
                            // underneath) is suppressed for the slider; the player is
                            // seeked exactly ONCE per gesture, at onValueChangeFinished
                            // (previously every drag movement issued a live seekTo and
                            // the ticker fought the drag). After the commit the ticker
                            // resumes driving the slider; the first post-release frame
                            // may show the pre-seek ticker value until the next tick
                            // lands the seeked position (bounded by one 500 ms tick).
                            // Interrupted gestures resolve at this SAME single finish
                            // point: a pointer-cancelled drag commits exactly once at
                            // the held value (device-pinned); a null player commits
                            // nothing, and the held value clears at finish or disposal.
                            var dragFraction by remember { mutableStateOf<Float?>(null) }
                            // Our slot's wrapper: the tag sits on this Box so the
                            // §21.5 test measures the control area this slot
                            // presents — the Box wraps Material3 Slider's FULL
                            // reported size, including the internal
                            // minimumInteractiveComponentSize() 48dp padding
                            // (the slider's inner semantics node reports content
                            // height only).
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("player_seek_slider"),
                            ) {
                                Slider(
                                    value = dragFraction ?: tickerFraction,
                                    onValueChange = { requested ->
                                        dragFraction = requested
                                    },
                                    onValueChangeFinished = {
                                        dragFraction?.let { committed ->
                                            player?.let { current ->
                                                // Duration is read from the player at
                                                // commit time (never a stale captured
                                                // value); C.TIME_UNSET/0 → no seek.
                                                val commitDurationMs = current.duration
                                                if (commitDurationMs > 0L) {
                                                    current.seekTo(
                                                        (committed * commitDurationMs).toLong(),
                                                    )
                                                }
                                            }
                                        }
                                        dragFraction = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                },
                errorOverlay = { player ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PlayerDefaults.ErrorOverlay(player)
                        if (uiState.playerError) {
                            TextButton(onClick = onRetryPlayback) { Text("Retry") }
                        }
                    }
                },
            )
        }

        // SPEC §15 "subtitles where available": rendered ONLY when the current
        // media really publishes a text track. No text track → no control, and
        // playback is untouched either way (AGENTS §5 — never a control that
        // pretends to do something).
        //
        // Composed LAST on purpose: the media surface in the `when` above is a
        // full-screen node with its own pointer handling, so a control placed
        // before it is not on the hit path at all — the tap is handled by that
        // surface's branch (and the root's tap-to-toggle) and never reaches the
        // control (instrumented on-device, not assumed). Last composed = drawn
        // on top = the tap lands on this control, whose own onClick consumes it
        // before the root's §23 toggle can also run.
        if (showControls && uiState.subtitleOptions.isNotEmpty() && uiState.loadError == null) {
            Column(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalAlignment = Alignment.End,
            ) {
                IconButton(
                    onClick = { subtitleMenuOpen = !subtitleMenuOpen },
                    modifier = Modifier.testTag("subtitle_control"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_subtitles),
                        contentDescription = "Subtitles",
                        tint = Color.White,
                    )
                }

                if (subtitleMenuOpen) {
                    SubtitlePanel(
                        options = uiState.subtitleOptions,
                        selectedIndex = uiState.selectedSubtitleIndex,
                        onSelect = { index ->
                            subtitleMenuOpen = false
                            onSelectSubtitle(index)
                        },
                    )
                }
            }
        }
    }
}

/**
 * SPEC §15 "subtitles where available" — the chooser: **Off** plus every text
 * track the current media publishes, anchored directly under the subtitle
 * control.
 *
 * This is an anchored panel inside the player's own semantics tree rather than
 * a `DropdownMenu` popup, for two concrete reasons: every entry is then
 * reachable by the same accessibility/UI queries as the rest of the player
 * controls (no second window to reach through, no popup outside the shared
 * semantics tree), and dismissal is deterministic — tapping the control again
 * or choosing an entry closes it, both handled by nodes composed above the
 * media surface. [PlayerScreen]'s root also closes it when an outside tap
 * reaches the root, instead of that tap also hiding the controls.
 *
 * Entries are rendered even when none is selected (a media whose only track the
 * viewer never chose, or a choice this media does not publish), so the viewer
 * can always act; the check mark is the sole selection marker.
 */
@Composable
private fun SubtitlePanel(
    options: List<SubtitleOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(240.dp)
            .padding(top = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column {
            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = 4.dp,
                ),
            )
            SubtitlePanelEntry(
                label = "Off",
                selected = selectedIndex == -1,
                onClick = { onSelect(-1) },
            )
            options.forEachIndexed { index, option ->
                SubtitlePanelEntry(
                    label = option.label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

/** One row of [SubtitlePanel]; the check mark carries `Subtitle selected`. */
@Composable
private fun SubtitlePanelEntry(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Subtitle selected",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * SPEC §15 — the centre controls of a STREAM entry.
 *
 * Everything except skip-previous/skip-next is Media3's OWN default centre row
 * ([PlayerDefaults.CenterControls]), so a stream looks, feels and announces
 * itself exactly like local playback: seek back, play/pause, seek forward and
 * their semantics are Media3's, untouched.
 *
 * Those two buttons are ours because they cannot be Media3's: Media3 derives a
 * skip button's enabled state from the player's media-item playlist, and a
 * stream entry has exactly ONE media item. Its neighbour lives in the session's
 * episode list and can only be reached by reconstructing and resolving it first
 * — an in-place source swap the ViewModel performs (§24.2: same player, same
 * route, never a stacked one). The icons and the content descriptions are
 * Media3's own resources, so the row stays visually and semantically identical
 * to the DOWNLOAD mode's row.
 *
 * A skip button is DISABLED rather than hidden when there is no neighbour — the
 * same presentation Media3 uses for a playlist that ends — so an unreadable
 * episode list can never be mistaken for a control that works (AGENTS §5).
 *
 * DOWNLOAD mode never reaches this composable: [PlayerScreen] keeps Media3's
 * default centre controls there, byte-for-byte.
 */
@OptIn(markerClass = [UnstableApi::class])
@Composable
private fun StreamEpisodeControls(
    player: androidx.media3.common.Player?,
    visible: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    PlayerDefaults.CenterControls(
        player = player,
        visible = visible,
        backSecondary = {
            EpisodeSkipButton(
                contentDescription = stringResource(Media3R.string.previous_button),
                painter = painterResource(Media3R.drawable.media3_icon_previous),
                enabled = hasPrevious,
                onClick = onPrevious,
            )
        },
        forwardSecondary = {
            EpisodeSkipButton(
                contentDescription = stringResource(Media3R.string.next_button),
                painter = painterResource(Media3R.drawable.media3_icon_next),
                enabled = hasNext,
                onClick = onNext,
            )
        },
    )
}

/**
 * The single difference from Media3's own skip button: `enabled` comes from the
 * ViewModel's sibling bounds instead of the player's one-item playlist. The
 * footprint (50 dp control, 24 dp icon, rounded secondaryContainer background)
 * reproduces Media3's medium control button so the row does not shift.
 */
@Composable
private fun EpisodeSkipButton(
    contentDescription: String,
    painter: Painter,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(50.dp)
            .background(
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                ButtonDefaults.shape,
            ),
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Full-screen playlist/seed load failure with a real retry (§23, AGENTS §5). */
@Composable
private fun LoadErrorContent(
    message: String,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetryLoad) { Text("Retry") }
    }
}

/**
 * Formats a media position/duration exactly like Media3's `Util.getStringForTime`
 * (zero-padded `MM:SS` under an hour, `H:MM:SS` above), so the VM-ticker-driven
 * position readout (§23) is byte-compatible with the duration label Media3 renders
 * in its own right slot — and with the instrumentation time parser.
 */
private fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Requests a fresh measure+layout pass of this root every time [state] changes.
 *
 * Compose can complete a placement pass that leaves this Box's children with
 * `isPlaced == false`. Such children stay composed and keep valid semantics and
 * bounds, but both the draw gate (`InnerNodeCoordinator.performDraw`) and the
 * hit-test gate (`InnerNodeCoordinator.hitTestChild`) skip unplaced nodes, so a
 * tap falls through to this root's controls toggle and the always-visible Back
 * button never receives it — the route then cannot be popped by touch
 * (observed in instrumentation as `poppingThePlayerEntryReleasesItsExoPlayer`
 * intermittently failing while the player itself was healthy).
 *
 * The unplaced child cannot repair itself: Compose drops layout requests from
 * nodes that are not placed (`MeasureAndLayoutDelegate.requestRelayout` —
 * "the node can't affect parent"), so recovery must be requested by this root,
 * which does stay placed. Reading [state] inside the measure block registers
 * the read with Compose's measure-pass snapshot observer, so every change of
 * the VM's player state (the §23 position tick, `POSITION_UI_TICK_MS`, while
 * the player runs) re-requests this root's measure+layout: the measure pass
 * rebuilds the Box's measure result from the real child list, and the following
 * placement pass re-runs `layoutChildren`, whose `onNodePlaced` →
 * `markNodeAndSubtreeAsPlaced` re-places every child. State-driven — no
 * polling, no sleeps, no retries — and the measured size passes through
 * unchanged.
 */
private fun Modifier.remeasureOn(state: State<PlayerUiState>): Modifier =
    this.layout { measurable, constraints ->
        // Deliberate read — this IS the guard (snapshot-observed in this pass);
        // the value itself does not influence the pass-through below.
        state.value.positionMs
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** Tail-recursively unwraps [ContextWrapper]s to the hosting [Activity], if any. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
