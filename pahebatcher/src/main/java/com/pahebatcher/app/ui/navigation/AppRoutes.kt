package com.pahebatcher.app.ui.navigation

import androidx.navigation.NavHostController
import kotlinx.serialization.Serializable

/**
 * Type-safe route definitions (PHASE11_ARCHITECTURE_V2 §10.3).
 *
 * ID-only arguments — screens re-read full state from Room/repositories; the
 * serializable route types make back-stack restoration lossless across process
 * death (§10.4).
 *
 * - [HomeRoute] / [DownloadsRoute]: top-level tabs (§10.1).
 * - [SettingsRoute]: pushed from the Home top app bar — never a bottom tab (§10.2).
 * - [PlayerRoute]: single player entry, `launchSingleTop` + replace-in-place (§24.2).
 *   The destination composable is registered with the player slice; defining the
 *   route here keeps the Phase 11 route surface in one place.
 * - [AnimeDetailsRoute]: pushed from a Home search row (11.5E-2). ID-only `session`
 *   argument like every other route — the destination re-reads title/episodes from
 *   [com.pahebatcher.app.data.repository.EpisodeRepository], so nothing derived is
 *   carried in the back stack (§10.3/§10.4 process-death restoration).
 */
@Serializable
data object HomeRoute

@Serializable
data object DownloadsRoute

@Serializable
data object SettingsRoute

/**
 * Phase 12A diagnostics Console — pushed from Settings only when `Show Console`
 * is ON (never a bottom tab, §10.2). Pure in-memory view: no arguments, no
 * persistence, back pops to Settings.
 */
@Serializable
data object ConsoleRoute

/**
 * Phase 12C watchlist — pushed from the Home top app bar (never a bottom tab,
 * §10.2). No arguments: the screen re-reads the `watchlist` rows from Room, so
 * nothing derived travels in the back stack (§10.3/§10.4).
 */
@Serializable
data object WatchlistRoute

/**
 * Phase 13 (DECISIONS 110) session manager — pushed from the Downloads top
 * app bar (never a bottom tab, §10.2). No arguments: the screen re-reads the
 * `download_sessions` + `episode_downloads` rows from Room, so nothing
 * derived travels in the back stack (§10.3/§10.4).
 */
@Serializable
data object SessionsRoute

@Serializable
data class PlayerRoute(val episodeId: String, val mode: PlayerMode = PlayerMode.DOWNLOAD)

/**
 * Which playback path a player entry takes.
 *
 * * [DOWNLOAD] — the pre-Phase 17 behaviour, unchanged: the entry seeds the
 *   local playlist from Room (`PlaybackSourceResolver` → MediaStore `content://`
 *   or the assembled MP4). Downloads `Play` and the in-app tests use it,
 *   explicitly or through the route default.
 * * [STREAM] — direct HLS streaming: the entry reconstructs the episode's
 *   `playUrl`, resolves it just-in-time and plays the resulting playlist.
 *
 * This value is the ONLY playback difference that travels in navigation next to
 * `episodeId`. No URL, no `StreamInfo`, no title, no headers — those are all
 * rebuilt in the player (§3.3 of the Phase 17 brief).
 */
@Serializable
enum class PlayerMode {
    DOWNLOAD,
    STREAM,
}

/**
 * The §19 `player:` target, parsed into its two validated parts (AQR-138).
 *
 * A notification tap is the one path that reaches the player from OUTSIDE the
 * app, and the episode id alone cannot say whether that tap represents a local
 * download or a direct HLS stream: both playbacks post the SAME media
 * notification through the SAME [androidx.media3.session.MediaSession]. Before
 * AQR-138 every target therefore landed on the route's implicit
 * [PlayerMode.DOWNLOAD] default, so a stream session's notification silently
 * re-opened its episode as a download.
 *
 * The target now carries the mode EXPLICITLY and the consumer validates it
 * before navigating:
 *
 * * `player:<episodeId>:stream` — [PlayerMode.STREAM];
 * * `player:<episodeId>:download` — [PlayerMode.DOWNLOAD];
 * * `player:<episodeId>` — the documented pre-AQR-138 form (DECISIONS 118.2),
 *   kept for compatibility and read as [PlayerMode.DOWNLOAD], which is what
 *   every producer of that form meant. No producer emits it any more, so an
 *   ambiguous STREAM notification can never arrive in this shape.
 *
 * Encoding is unambiguous because episode ids are `{session}_{epStr}_{audio}`
 * ([com.pahebatcher.app.domain.download.EpisodeId] — three `_`-separated
 * components, none of which may contain `:`), so the LAST `:` always separates
 * the id from the mode token.
 *
 * Anything that cannot be trusted — a blank id, or a mode token this build does
 * not know — parses to `null` and is CONSUMED without navigation (§19.1), never
 * guessed at.
 */
data class PlayerTarget(
    val episodeId: String,
    val mode: PlayerMode,
) {

    /** The §19 `navigate_to` value this target is delivered as. */
    fun encode(): String = "$PREFIX$episodeId:${modeToken(mode)}"

    companion object {

        /** §19 target prefix this parser owns. */
        const val PREFIX = "player:"

        private const val MODE_DOWNLOAD = "download"
        private const val MODE_STREAM = "stream"

        /** Encode [episodeId] + [mode] as a validated §19 target. */
        fun encode(episodeId: String, mode: PlayerMode): String =
            PlayerTarget(episodeId, mode).encode()

        /**
         * Parse a raw §19 target into a validated [PlayerTarget], or `null` for
         * anything that must be consumed WITHOUT navigating:
         *
         * * not a [PREFIX] target at all;
         * * a blank episode id (`player:`, `player:   `, `player::stream`);
         * * an unsupported mode token (`player:<id>:loud`) — never silently
         *   downgraded to a mode the target did not name.
         */
        fun parse(raw: String): PlayerTarget? {
            if (!raw.startsWith(PREFIX)) return null
            val rest = raw.substring(PREFIX.length)
            val separator = rest.lastIndexOf(':')
            if (separator < 0) {
                // Pre-AQR-138 form `player:<episodeId>` — no mode segment.
                return if (rest.isBlank()) null else PlayerTarget(rest, PlayerMode.DOWNLOAD)
            }
            val episodeId = rest.substring(0, separator)
            if (episodeId.isBlank()) return null
            val mode = when (rest.substring(separator + 1)) {
                MODE_DOWNLOAD -> PlayerMode.DOWNLOAD
                MODE_STREAM -> PlayerMode.STREAM
                else -> return null
            }
            return PlayerTarget(episodeId, mode)
        }

        private fun modeToken(mode: PlayerMode): String = when (mode) {
            PlayerMode.DOWNLOAD -> MODE_DOWNLOAD
            PlayerMode.STREAM -> MODE_STREAM
        }
    }
}

/**
 * Anime details destination (11.5E-2) — ID-only `session` argument (§10.3): the
 * anime session UUID taken straight from the tapped search row.
 */
@Serializable
data class AnimeDetailsRoute(val session: String)

/**
 * §10.4 top-level tab navigation rule — used for every tab switch AND for the
 * notification deep-link target (§19.3), so all three paths obey identical
 * semantics. The overloads are typed to the two bottom-tab routes (AQR-144).
 *
 * - `popUpTo(HomeRoute) { saveState = true }` — leaving a tab saves its stack
 *   (including destinations pushed within it, e.g. Settings from Home).
 * - `launchSingleTop = true` — repeat taps never stack duplicate destinations.
 * - `restoreState = true` — returning to a tab restores where the user left off.
 *
 * Navigation uses the route OBJECT value (`navigate(route) { … }` /
 * `popUpTo(route) { … }`) — the 2.10.1 public type-safe API. Destinations are
 * registered type-safely via `composable<T>`; there is no reified
 * `navigate<T> { }` overload in this version (verified against the
 * navigation-runtime 2.10.1 artifact).
 */
fun NavHostController.navigateToTab(route: HomeRoute) = navigateToTabInternal(route)
fun NavHostController.navigateToTab(route: DownloadsRoute) = navigateToTabInternal(route)
private fun NavHostController.navigateToTabInternal(route: Any) = navigate(route) {
    popUpTo(HomeRoute) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * AQR-140 foreground-Downloads rule — used ONLY by the `replace:<episodeId>`
 * target, and byte-identical to [navigateToTabInternal] in every respect but
 * one: `restoreState = false`.
 *
 * `restoreState = true` (the normal tab switch) brings the tab back to wherever
 * the user left it, which for the Downloads tab can be a NESTED destination —
 * Sessions pushed over the list. A replacement target processed that way armed
 * its confirmation dialog behind a destination the user had to leave manually,
 * so the request stayed deferred indefinitely.
 *
 * With `restoreState = false` the popped-with-saveState stack is NOT restored:
 * the tab is entered at its ROOT destination, the confirmation dialog is
 * therefore presented on the visible Downloads screen, and `launchSingleTop`
 * keeps the already-at-root case a no-op (no duplicate entry). The saved nested
 * state is untouched — the next ordinary tab switch still restores it, so
 * multiple-back-stack behaviour for unrelated navigation is preserved.
 */
fun NavHostController.navigateToDownloadsForReplacement() = navigate(DownloadsRoute) {
    popUpTo(HomeRoute) { saveState = true }
    launchSingleTop = true
    restoreState = false
}

/**
 * §24.2 player entry navigation — the single entry point for `player(episodeId)`.
 *
 * - `popUpTo<PlayerRoute> { inclusive = false }`: replace-in-place semantics.
 *   When a player entry exists anywhere below the top, everything above it is
 *   popped (non-inclusive keeps the player entry itself), so at most ONE
 *   player entry ever exists. When no player entry exists, the pop is a
 *   verified graceful no-op in Navigation 2.10.1 (the destination simply isn't
 *   found; navigation proceeds) — the common "open player from Downloads" path.
 * - `launchSingleTop = true`: re-tapping Play for the same episode reuses the
 *   existing top player entry (no duplicate, §36-5 depth assertion).
 * - Different episodeId while a player entry exists: popUpTo removes any
 *   entries above the player (none, since the bar is hidden while the player
 *   is top) and singleTop + new args re-seeds the SAME entry's ViewModel via
 *   `LaunchedEffect(route.episodeId)` (§24.2 in-place episode switch on route
 *   change; next/prev INSIDE the player never routes through here).
 *
 * [mode] selects the playback path (local download vs direct HLS
 * streaming). It defaults to [PlayerMode.DOWNLOAD] for the in-app callers whose
 * semantics are already DOWNLOAD by definition (Downloads `Play`, tests); the
 * MediaSession notification tap passes the mode EXPLICITLY, taken from the
 * [PlayerTarget] the notification carried (AQR-138), so no notification ever
 * depends on this default. Nothing but `episodeId` + `mode` ever travels in the
 * route.
 */
fun NavHostController.navigateToPlayer(episodeId: String, mode: PlayerMode = PlayerMode.DOWNLOAD) {
    navigate(PlayerRoute(episodeId, mode)) {
        popUpTo<PlayerRoute> { inclusive = false }
        launchSingleTop = true
    }
}
