# PaheBatcher Android — Product & Engineering Specification

## Authority

This document defines the required behavior and engineering requirements for the Android application.

The original PaheBatcher repository is the **behavioral reference implementation**:

https://github.com/smolfiddle/pahebatcher

The Android application must reproduce the behavior of the reference implementation as closely as technically possible while replacing desktop-specific mechanisms with appropriate Android-native implementations.

## Critical source-of-truth rule

There are three distinct sources of truth:

1. **PaheBatcher repository**

   * Defines existing behavior.
   * Defines actual implemented functionality.
   * Defines existing algorithms and network flows.

2. **SPEC.md**

   * Defines the required Android product.
   * May intentionally add Android-specific functionality.
   * May require Android-native replacements for desktop dependencies.

3. **Android platform requirements**

   * Define constraints that may require implementation differences.

When these appear to conflict:

1. Inspect the actual Python implementation.
2. Determine the behavior currently implemented.
3. Determine whether SPEC.md intentionally changes that behavior.
4. Determine whether Android platform constraints require a different implementation.
5. Document the decision in `DECISIONS.md`.
6. Do not silently choose an implementation based on convenience.

Never invent behavior that is not supported by either the reference implementation, this specification, or a documented Android requirement.

---

## Platform and toolchain requirements

### Target platform

* **Android 17** (API level 37) is the current target platform.
* The application must compile against, target, and support Android 12 through Android 17.

### SDK configuration

| Setting | Value | Rationale |
|---------|-------|-----------|
| `compileSdk` | 37 | Compile against Android 17 APIs |
| `targetSdk` | 37 | Declare Android 17 compatibility to the platform |
| `minSdk` | 32 | Support Android 12 (API 32) as the minimum |

The supported Android range is **Android 12 (API 32) through Android 17 (API 37)**.

Do not lower `minSdk` below 32. Do not raise `minSdk` above 32 without explicit approval.

### JDK

* **JDK 25** is installed on the build environment and must be used.
* The Kotlin/JVM toolchain must be configured to target JDK 25.
* Do not downgrade the project to an older JDK merely for convenience.
* If a specific dependency or plugin does not yet support JDK 25, document the incompatibility and select the newest compatible version. Do not silently downgrade the JDK.

### Dependency version policy

The project must use **current stable versions** of:

* Android Gradle Plugin (AGP)
* Gradle
* Kotlin
* Jetpack Compose
* AndroidX libraries
* Material 3
* Android build tooling

Before introducing or upgrading any infrastructure dependency:

1. Verify the selected version supports the target JDK (25), Gradle, AGP, and Kotlin versions.
2. Verify the selected version supports `compileSdk = 37` and `targetSdk = 37`.
3. Verify the selected version is compatible with `minSdk = 32`.
4. Verify the selected version is the **latest stable release** of its line, not a numerically newer alpha/beta/RC that introduces incompatibilities.
5. Do not select incompatible "latest" versions merely because they are numerically newer.

### Adaptive/resizability

Android 17 introduces adaptive and resizability requirements.

These must be considered from the beginning of the UI implementation rather than retrofitted later.

All screen layouts must be responsive and handle:

* foldable devices
* large screens
* multi-window mode
* variable display densities

### Deprecated API policy

Avoid deprecated Android APIs and libraries when a maintained AndroidX/Jetpack replacement exists.

Document any use of deprecated APIs with the reason for the choice and the planned migration path.

---

## Feature provenance

Every major feature must be classified as one of:

* `PYTHON_PARITY` — directly reproduced from PaheBatcher.
* `PYTHON_BEHAVIOR_ANDROID_IMPLEMENTATION` — same behavior, different Android implementation.
* `ANDROID_EXTENSION` — functionality added specifically for Android.
* `ANDROID_REQUIRED_DIFFERENCE` — behavior differs because of Android platform constraints.
* `BLOCKED` — technically unresolved and explicitly documented.

Do not represent an Android extension as repository parity.

Do not represent an unresolved feature as complete.

---

## No premature UI implementation

The application must be implemented from the underlying domain outward.

Do not build a complete UI containing controls for functionality that does not yet exist.

A UI feature may be considered complete only when its underlying implementation is functional.

Examples:

Search UI
→ real search implementation

Anime details
→ real metadata implementation

Episode list
→ real episode scanning

Download screen
→ real persistent download engine

Player
→ real Media3 playback

Sessions
→ real persistent session state

Settings
→ settings actually consumed by the relevant subsystem

---

## Phase gating

Each implementation phase is independently gated.

The agent must:

1. Implement the requested phase.
2. Build the project.
3. Run relevant tests.
4. Fix failures.
5. Verify the implementation.
6. Update project documentation.
7. Stop.

The agent must NOT automatically proceed to the next phase.

A phase is not complete because the code compiles.

A phase is complete only when its defined functionality and tests have been verified.

---

## No fake completion

Never satisfy an unfinished requirement with:

* fake data
* fake URLs
* fake progress
* mock production repositories
* TODO implementations
* placeholder service methods
* hard-coded sample responses
* fake Cloudflare sessions
* fake stream URLs
* dummy notifications
* non-functional settings
* UI-only functionality

If something cannot be implemented, report the blocker and document it.

Do not hide the blocker behind a UI.

## 1. Objective

Retrieve, inspect, understand, and test the **entire current PaheBatcher repository** before writing the Android application.

Build a **fully functional native Android application** that reproduces the behavior and functionality of the Python PaheBatcher project as closely as technically possible on Android.

This is **not** a request to create a UI mockup, proof of concept, WebView wrapper, or simplified anime browser.

The Android application must implement the actual PaheBatcher functionality:

* AnimePahe search
* Search result browsing
* Anime/series discovery
* Audio-variant (SUB/DUB and other) discovery
* Episode scanning
* Episode selection
* Downloading
* Batch downloading
* Parallel episode downloading
* Parallel HLS segment downloading
* HLS manifest parsing
* AES-128 HLS decryption where required
* Stream URL resolution
* Segment-level crash recovery
* Resume after process/app termination
* Atomic segment writes
* MP4 assembly/remuxing
* Streaming/playback
* Audio-variant (SUB/DUB and other) switching
* Episode navigation
* Download/session management
* Persistent configuration
* Scan-result caching
* Watchlist functionality if present in the current repository
* Latest-N episode selection
* Episode range selection
* Download progress
* Download speed
* ETA
* Segment progress
* Failure/retry handling
* Cache management
* Offline access to completed downloads
* Background downloads
* Notifications for download state changes

Do not remove functionality simply because the original application is a CLI.

Where the desktop implementation uses a desktop-specific dependency such as MPV, FFmpeg, Docker, or FlareSolverr, replace that dependency with the closest reliable Android-native implementation while preserving the behavior exposed to the user.

---

# 2. FIRST: ANALYZE THE REPOSITORY

Before implementing the Android application, inspect the repository completely.

Read and understand:

* README.md
* METHODOLOGY.md
* pyproject.toml
* pahebatcher.toml.example
* every file under `src/pahebatcher/`
* every file under `src/pahebatcher/extract/`
* every downloader/resolver/cache/store/session/watchlist implementation
* every test under `tests/`

Do not rely only on the README.

Create an internal feature/behavior matrix mapping:

Python implementation
→ Android implementation
→ Android-specific replacement
→ tests required

Do not expose this as a placeholder UI. Use it to drive implementation.

The Python implementation is the **reference behavior**.

Do not redesign the underlying behavior merely because a different implementation is easier.

---

# 3. TECHNOLOGY REQUIREMENTS

Build this as a real native Android application.

Preferred stack:

* Kotlin
* Jetpack Compose
* Material 3
* AndroidX
* Kotlin Coroutines
* Kotlin Flow
* Room for structured persistent state
* DataStore for preferences
* WorkManager for persistent background work
* Media3/ExoPlayer for playback
* Android DownloadManager only where it genuinely fits; otherwise implement a dedicated coroutine-based download engine
* OkHttp for HTTP networking where appropriate
* Android's Storage Access Framework / MediaStore for user-accessible downloads
* Foreground Service only when required for long-running active downloads
* WebView only for the browser/session functionality described below

Do not use Flutter, React Native, Cordova, or a WebView-only architecture.

The app must remain functional as a native Android application even if the WebView is never opened.

---

# 4. ANDROID VERSION SUPPORT

Target current Android APIs while maintaining compatibility with approximately the previous five years of commonly used Android devices.

Use sensible minimum SDK / target SDK values based on the current Android ecosystem.

Do not sacrifice modern Android background-execution requirements for legacy behavior.

Handle:

* Android 10+
* scoped storage
* notification permission
* foreground-service restrictions
* Doze mode
* app standby
* process death
* network changes
* Wi-Fi/mobile data changes
* battery restrictions
* device reboot where appropriate

The app must not assume that a background process can remain alive indefinitely.

---

# 5. DO NOT MAKE THE APP A WEBVIEW WRAPPER

The WebView is only a supporting component.

The main application must be native.

Do NOT implement the application as:

WebView → AnimePahe website → JavaScript UI

Instead:

Native UI
↓
Native domain/repository layer
↓
Native AnimePahe scanner/resolver
↓
Native HLS/download engine

Use WebView only where it is genuinely required for browser/session interaction.

---

# 6. CLOUDFLARE / WEBVIEW SESSION HANDLING

The original project uses Cloudflare-related infrastructure and supports `cf_clearance` cookies.

Implement Android-compatible session handling.

Create a dedicated WebView-based session/bootstrap component.

When necessary:

1. Load AnimePahe's base URL in an actual Android WebView.
2. Use the WebView's real Android User-Agent.
3. Allow the page to execute normally.
4. Wait for page completion/challenge completion.
5. Extract relevant cookies using Android's CookieManager.
6. Preserve the cookies securely.
7. Capture the exact User-Agent used by the WebView.
8. Use those cookies and the exact User-Agent consistently in subsequent HTTP requests when appropriate.
9. Detect expired/invalid Cloudflare cookies.
10. Prompt the user to refresh the WebView session instead of silently failing.

Do not hard-code a fake User-Agent.

Do not assume that copying a cookie alone is sufficient.

Do not expose cookies in logs.

Do not transmit cookies to any third-party service.

Use encrypted Android storage for sensitive session information where appropriate.

Important:

**Phase 5 Validated Architecture (Decision 26)**: For the current AnimePahe Cloudflare configuration, cookie transfer from WebView to the native HTTP client (OkHttp) does NOT bypass Cloudflare. Cloudflare binds `cf_clearance` cookies to the TLS fingerprint that solved the challenge, and OkHttp's TLS fingerprint differs from WebView's. All Cloudflare-protected AnimePahe requests (search, episode lists, play pages) must go through WebView. Do NOT build Cloudflare cookie synchronization infrastructure between WebView and OkHttp — it does not work.

The native HTTP client (OkHttp) can be used for non-Cloudflare endpoints (HLS segments, AES keys).

**Earlier requirement (superseded)**: The original specification called for cookie synchronization between WebView CookieManager and the native HTTP client. Phase 5 validation proved this does not work for Cloudflare-protected endpoints. The cookie synchronization diagram below is retained for historical reference only and must NOT be implemented as a Cloudflare bypass mechanism.

~~WebView CookieManager~~
~~↕~~
~~secure session store~~
~~↕~~
~~native HTTP client cookie/header provider~~

---

# 7. DO NOT PORT FLARESOLVERR LITERALLY

The desktop project uses a local FlareSolverr instance because it runs on a desktop/server environment.

Do NOT require Android users to install Docker.

Do NOT require users to run a separate local server.

Instead, determine what portions of the repository's resolution flow actually require FlareSolverr and reproduce those portions using Android-compatible mechanisms.

If a WebView session can satisfy the required browser/session state, use it.

If some operation genuinely cannot be reproduced inside Android, isolate that limitation behind an interface such as:

`CloudflareSessionProvider`

and make the implementation replaceable.

Do not fake successful resolution.

Do not return placeholder URLs.

Do not silently substitute unrelated sources.

---

# 8. REIMPLEMENT THE ACTUAL PAHEBATCHER PIPELINE

Preserve the logical architecture of the original project.

The intended flow is approximately:

Search
→ Anime selection
→ Anime metadata
→ SUB/DUB discovery
→ Episode scan
→ Episode selection
→ Play-page resolution
→ Stream/Kwik resolution
→ M3U8 resolution
→ HLS manifest parsing
→ segment download
→ segment verification
→ atomic segment commit
→ completion detection
→ MP4 assembly
→ completed file

The Android implementation should preserve these stages.

Use clear interfaces between each stage.

For example:

* AnimeRepository
* EpisodeRepository
* StreamResolver
* HlsManifestParser
* HlsSegmentDownloader
* SegmentStore
* EpisodeAssembler
* DownloadRepository
* SessionRepository
* WatchlistRepository
* PlaybackRepository
* CloudflareSessionProvider

Do not put all of this logic inside Activities or Composables.

---

# 9. HLS DOWNLOAD ENGINE

The HLS engine is one of the most important components.

Reproduce the behavior of the Python implementation.

Requirements:

* Parse master/media playlists.
* Select the requested quality.
* Select the requested audio language.
* Resolve relative URLs correctly.
* Handle encrypted HLS when present.
* Download segments concurrently.
* Respect configurable concurrency.
* Retry transient failures.
* Detect permanent HTTP failures.
* Validate downloaded data.
* Write segments to temporary files.
* Atomically rename completed temporary files.
* Never treat a partially downloaded segment as completed.
* Resume from completed segments after interruption.
* Avoid downloading already completed segments.
* Maintain accurate segment state.

The Android engine must survive:

* process death
* network interruption
* app backgrounding
* screen lock
* temporary CDN errors
* device restart where WorkManager permits continuation

---

# 10. CONCURRENCY

Preserve the conceptual distinction in PaheBatcher between:

A. number of episodes downloading concurrently

and

B. number of HLS segments downloading concurrently per episode.

Do not merge these into one arbitrary thread count.

Expose sensible Android-safe settings.

The original defaults are approximately:

* quality: 1080p
* audio: Japanese
* concurrent episodes: 2
* HLS workers: 24

Preserve these defaults unless Android resource constraints make them unsafe.

Implement adaptive/conservative behavior where necessary.

Do not blindly create hundreds of threads.

Use coroutine dispatchers, semaphores, bounded queues, and connection pooling.

---

# 11. TWO-STAGE DOWNLOAD PIPELINE

Preserve the original two-stage architecture:

Stage 1:
stream/episode resolution

Stage 2:
concurrent HLS downloading

Use a bounded queue.

Do not resolve hundreds of episodes in advance.

Respect the original `resolve_ahead` concept.

Make it configurable.

Avoid excessive AnimePahe/Cloudflare requests.

---

# 12. LOCAL CACHE

Local caching is a first-class feature.

Implement durable local storage.

Cache:

* anime metadata
* episode metadata
* scan results
* stream resolution metadata where safe
* download sessions
* segment state
* completed downloads
* watchlist data
* playback position
* application settings

Do NOT blindly cache expiring URLs indefinitely.

Separate:

persistent metadata

from

temporary/expiring network credentials and URLs.

Use TTLs where required.

The app must be able to restart and reconstruct the state of an interrupted download.

---

# 13. DOWNLOAD STORAGE

Use Android-compatible storage APIs.

Completed media should be accessible to the user.

Do not store everything in an inaccessible private directory.

Use MediaStore or another Android-appropriate mechanism where applicable.

Support selecting a download directory using the Storage Access Framework where appropriate.

Handle:

* insufficient storage
* permissions
* file collisions
* renaming
* deletion
* partially completed files
* orphaned segments
* corrupted files

Do not delete user media accidentally.

---

# 14. MP4 ASSEMBLY

The Python application uses FFmpeg for assembly.

Android cannot simply assume a system FFmpeg binary exists.

Choose an Android-compatible implementation.

Possible approaches may include:

* Media3/Android media APIs where technically sufficient
* an appropriately packaged FFmpeg implementation
* another reliable native muxing solution

Determine what the original FFmpeg invocation actually does before choosing.

Do not replace muxing with a fake file concatenation operation.

The resulting MP4 must be valid and playable.

Keep temporary `.ts` segments until the final output has been successfully verified unless the user has disabled temporary retention.

Only clean temporary data after successful completion.

---

# 15. STREAMING

Replace desktop MPV with Android Media3/ExoPlayer.

Streaming must support:

* HLS playback
* requested quality
* requested audio language
* subtitles where available
* playback controls
* seek
* pause/resume
* episode navigation
* next episode
* previous episode
* replay
* episode selection
* playback position persistence

If the original stream flow allows SUB/DUB switching, reproduce it using Media3's track-selection facilities where possible.

Do not download an entire episode merely to implement streaming.

---

# 16. BACKGROUND DOWNLOADS

Downloads must continue when the user leaves the application.

Use:

* WorkManager for persistent scheduled/background orchestration
* foreground service for actively running long downloads where required by Android

The UI must observe persistent download state from the database rather than relying on an Activity staying alive.

If Android kills the process:

* persist progress
* restart/resume appropriately
* continue from completed HLS segments
* never restart the entire episode unnecessarily

Use network constraints intelligently.

Allow the user to configure whether downloads can use mobile data.

---

# 17. BATTERY OPTIMIZATION

Do not constantly poll.

Use:

* Kotlin Flow
* database observation
* WorkManager
* event-driven state updates

Avoid:

* infinite foreground loops
* aggressive polling
* unnecessary wake locks
* repeatedly resolving the same URLs
* keeping WebView alive unnecessarily

When no download/playback is active, the app should be essentially idle.

---

# 18. NOTIFICATIONS

Implement real Android notifications.

Notifications should reflect actual state.

Examples:

* Download started
* Episode downloading
* Download progress
* Download completed
* Download failed
* Download paused
* Session requires user action
* Cloudflare/session expired
* New watchlist episode detected

Do not create notification spam.

Use grouped notifications.

Allow the user to tap a notification and return to the relevant download/session/player screen.

---

# 19. WATCHLIST

If the current repository contains watchlist functionality, reproduce it.

Watchlist should support:

* adding/removing anime
* persistent storage
* checking for newly available episodes
* displaying unread/new episodes
* triggering background checks where Android permits
* configurable check frequency
* notification when a new episode is detected

Do not continuously run a background service.

Use WorkManager periodic work.

Respect Android's minimum periodic-work constraints.

---

# 20. SESSION MANAGER

Implement a native session manager equivalent to the Python application.

Display:

* anime title
* URL
* selected quality
* audio language
* episode count
* download progress
* completed episodes
* incomplete episodes
* cache size
* current state

Actions:

* Resume
* Pause
* Cancel
* Retry
* Delete session
* Delete temporary cache
* Clear all cache

Deleting a session must clearly distinguish between:

temporary download cache

and

completed user media.

Never delete completed user media without explicit confirmation.

---

# 21. SEARCH

Implement native search.

Search results should show the equivalent information available in the Python application, such as:

* title
* type
* year
* episode count
* score
* relevant variants

Allow:

* search
* result selection
* refresh
* retry
* cached results where valid

Do not scrape the UI visually.

Use the underlying API/HTTP/extraction logic from the repository where possible.

---

# 22. EPISODE SELECTION

Support all meaningful selection modes from the repository.

At minimum:

* all episodes
* individual episodes
* range: `1-12`
* comma-separated selections
* open-ended ranges
* latest N episodes

On Android, provide a better UI than the CLI but preserve the same semantics.

Example:

Episode 1–12
Episode 13
Episode 15
Latest 3

Provide a clear selection summary before downloading.

### Audio selection (per episode)

* The screen's audio option set is discovered from a bounded play-page probe
  that runs after the episode scan completes: the play page of the
  lowest-numbered episode of every merged session plus the highest-numbered
  episode of the opened session, duplicate targets removed — at most
  `merged sessions + 1` page requests per load, fetched through the existing
  session-aware page path, with no in-probe retries. The probe samples
  representative episodes, so an audio present only on unsampled play pages
  may be absent from the option set; probing never silently expands to
  per-episode fetching. Release-listing audio remains display/identity
  metadata and the option-set fallback when the probe yields nothing; on its
  own it never proves another audio unavailable for an episode. Availability
  of a requested audio is enforced when that audio is resolved. A completed
  probe result is reused for the life of the scan-cache entry; probing
  repeats only after the cache refreshes or a previous attempt did not fully
  succeed.
* Audio values are open-set strings (e.g. `jpn`, `eng`, `kor`). Any value the
  source exposes must be selectable, persisted, routed, and displayed.
* Anime Details exposes a screen-level audio selector over the audio values the
  current title offers. The episode list shows one row per episode number for
  the selected audio context; variants in other audios are not separate
  top-level rows.
* When an episode offers multiple audio variants, the user may explicitly
  choose a different audio for that episode. The choice is recorded as an
  explicit per-episode override, and that episode's effective audio is its
  override when present, otherwise the current audio context — for
  single-episode resolution and for batch execution alike.
* Changing the audio context clears all explicit overrides and keeps every
  selected episode number selected in the new context — no number is ever
  deselected or reported as deselected for a context change, because
  release-listing audio cannot establish per-episode availability. The
  requested audio stays the user's intent; availability is checked when it
  is resolved.
* Batch selection executes as a mapping of episode number → requested audio,
  derived at execution time from the current audio context, the selected
  episode numbers, and any explicit per-episode audio overrides (an override
  takes precedence over the context).
* When a bulk action (select all / range / latest) applies, each matched
  episode number is assigned the current audio context. Bulk actions never
  create explicit per-episode audio overrides.
* The initial audio context is chosen deterministically: `jpn` when the
  discovered audio option set contains it, otherwise the first value of that
  set in deterministic discovery order. This rule establishes only the starting context —
  it never overrides the user's selected context during a bulk action — and it
  is a visible starting value, not a claim about "original" audio.
* The requested audio must never be silently substituted with a different
  audio at any stage (selection, resolution, streaming, download, retry,
  batch, watchlist). If the requested audio is unavailable, the operation
  produces an explicit failure or skip with a recorded reason.
* The application must not label any audio value as "original" unless the
  source itself provides that classification.

---

# 23. SETTINGS

Create a native settings screen.

Expose the functional configuration from PaheBatcher.

At minimum:

* Quality: 360p / 720p / 1080p
* Concurrent episodes
* HLS workers
* Resolve-ahead
* Cache TTL
* Output/storage location
* Keep temporary segments
* Wi-Fi-only downloads
* Notification settings
* Auto-resume downloads
* Theme

Audio is not a global setting. It is selected per episode from that episode's
available variants (see §22 EPISODE SELECTION). The Settings screen therefore
has no Preferred-audio row; the remaining settings are unchanged.

Persist using DataStore.

Settings must actually affect the underlying download engine.

Do not build settings that are visually present but non-functional.

---

# 24. UI/UX

Use modern Material 3.

The visual design should be:

* clean
* lightweight
* fast
* information-dense without being cluttered
* responsive
* accessible
* phone-first
* tablet-friendly where practical

Suggested screens:

1. Home
2. Search
3. Anime details
4. Episode list
5. Download queue
6. Active download
7. Downloads
8. Watchlist
9. Player
10. Sessions
11. Settings
12. Web/session authentication screen

Use proper loading, empty, error, retry, and offline states.

Never use placeholder cards or fake data.

Every visible control must be connected to real functionality.

---

# 25. DOWNLOAD DASHBOARD

The original terminal dashboard exposes detailed per-episode information.

Translate that into a mobile-friendly interface.

For every active episode show where possible:

* episode number
* title
* state
* percentage
* completed segments / total segments
* downloaded bytes
* speed
* ETA
* retry state
* quality
* audio
* errors

States should include equivalents of:

* resolving
* queued
* downloading
* remuxing
* completed
* failed
* paused

The UI must observe actual persisted state.

---

# 26. ERROR HANDLING

Do not hide exceptions.

Create meaningful error types.

Examples:

* NetworkUnavailable
* CloudflareSessionExpired
* AuthenticationRequired
* AnimeNotFound
* EpisodeNotFound
* StreamResolutionFailed
* ManifestParseFailed
* SegmentDownloadFailed
* DiskFull
* StoragePermissionDenied
* MuxingFailed
* UnsupportedStream
* ServerRateLimited

Display human-readable errors.

Also log enough diagnostic information for debugging, but NEVER log:

* cookies
* session tokens
* authorization headers
* private credentials

Implement retry policies with exponential backoff and sensible limits.

---

# 27. OFFLINE MODE

The app should remain useful without network connectivity.

Offline functionality should include:

* viewing downloaded episodes
* viewing cached anime metadata
* viewing download history
* viewing sessions
* viewing watchlist
* viewing playback positions
* playing completed local files

Clearly indicate when network functionality is unavailable.

Never pretend that a network-dependent operation succeeded while offline.

---

# 28. STATE MODEL

Use a persistent state machine for downloads.

Do not infer download state solely from UI state.

Example:

QUEUED
→ RESOLVING
→ RESOLVED
→ DOWNLOADING
→ ASSEMBLING
→ VERIFYING
→ COMPLETED

Failure paths:

→ PAUSED
→ RETRYING
→ FAILED
→ CANCELLED

Persist enough information to resume safely.

---

# 29. TESTING REQUIREMENTS

Do not consider the application complete merely because it compiles.

Create automated tests for:

### Unit tests

* URL parsing
* anime parsing
* episode parsing
* search parsing
* HLS parsing
* quality selection
* audio selection
* AES key handling
* segment indexing
* retry logic
* cache TTL
* filename sanitization
* episode selection
* state transitions
* session persistence

### Integration tests

* repository → resolver
* resolver → HLS manifest
* manifest → segment downloader
* segment store → assembler
* download persistence → resume
* watchlist → episode detection

### Android tests

* process recreation
* rotation/configuration change
* background/foreground transition
* notification interaction
* storage handling
* WebView cookie extraction
* WebView/native cookie synchronization
* player lifecycle

### Download recovery tests

Explicitly test:

1. Download starts.
2. Several segments complete.
3. Process is killed.
4. App restarts.
5. Existing segments are detected.
6. Only missing segments are downloaded.
7. Episode completes.
8. Final MP4 is created.
9. Temporary data is cleaned up correctly.

This test is mandatory.

---

# 30. NETWORK TESTING

Do not test only against mocked HTTP responses.

Where practical, create a separate integration-test configuration capable of testing the real repository flow.

Do not hard-code live URLs that can expire.

Make network integration tests opt-in if necessary.

The application must fail gracefully when AnimePahe changes its HTML/API structure.

---

# 31. REPOSITORY PARITY

Before declaring the project finished, compare the Android implementation against the Python implementation feature by feature.

Produce a parity checklist internally.

There must be no accidental omissions such as:

* watchlist
* session resume
* cache TTL
* latest-N selection
* audio switching
* quality selection
* segment recovery
* output storage
* configuration persistence
* retry behavior

Do not claim 100% parity if an implementation detail cannot actually be reproduced on Android.

If something is technically impossible or requires a platform-specific alternative, document the exact difference.

---

# 32. NO PLACEHOLDERS

This requirement is strict.

Do NOT create:

* fake API responses
* mock anime data in production
* dummy download buttons
* fake progress
* fake player screens
* TODO implementations
* stub repositories
* empty service methods
* hard-coded sample episodes
* placeholder URLs
* fake notifications
* fake Cloudflare cookies
* fake stream URLs

Every production feature must connect to a real implementation.

If a feature cannot yet be implemented, do not hide it behind a fake UI. Fix the implementation or explicitly report the blocker.

---

# 33. BUILD QUALITY

The final project must:

* compile successfully
* have no unresolved references
* have no placeholder implementations
* have no unused fake code
* have no obvious memory leaks
* use lifecycle-aware components
* survive configuration changes
* survive process recreation
* follow modern Android permission rules
* follow scoped-storage requirements
* handle Android background restrictions
* have proper ProGuard/R8 configuration if needed
* have release-build configuration
* have debug logging disabled/restricted in release
* have no secrets embedded in source

Run:

* Gradle build
* unit tests
* instrumentation tests where available
* lint
* static analysis
* dependency checks

Fix errors rather than suppressing them.

---

# 34. PERFORMANCE TARGET

The application should run smoothly on Android devices from approximately the last five years.

Prioritize:

* low idle CPU usage
* low memory usage
* low battery consumption
* bounded concurrency
* connection reuse
* efficient disk I/O
* incremental database updates
* minimal recomposition in Compose
* no unnecessary polling
* no unnecessary WebView lifetime
* no huge in-memory segment buffers

Do not load an entire episode into RAM.

Stream/copy data incrementally.

---

# 35. SECURITY

Protect:

* cookies
* session data
* HTTP headers
* local metadata containing authentication state

Never log authentication cookies.

Use HTTPS.

Validate URLs.

Do not allow arbitrary downloaded content to execute as code.

Use Android's secure storage facilities where appropriate.

Do not send AnimePahe session data to your own server.

The app should not require an application backend merely to function.

---

# 36. ARCHITECTURE

Use a clean separation such as:

UI
↓
ViewModel
↓
Use Cases
↓
Repositories
↓
Network / Database / Download Engine / Player / WebView Session

Do not allow UI components to directly perform:

* HTTP requests
* file downloads
* HLS parsing
* database writes
* Cloudflare handling

Use dependency injection if useful, but do not introduce a framework merely for architectural fashion.

Keep the download engine independently testable.

---

# 37. IMPORTANT: DO NOT CHANGE THE SOURCE BEHAVIOR WITHOUT REASON

The objective is not to make an application that is merely "similar to PaheBatcher."

The objective is:

**PaheBatcher behavior + Android-native implementation + modern Android UX.**

If you think a behavior should be changed, first determine whether it is required by Android platform constraints.

Prefer compatibility over redesign.

---

# 38. IMPLEMENTATION ORDER

Work in this order:

### Phase 1

Repository analysis and architecture mapping.

### Phase 2

Create Android project and build infrastructure.

### Phase 3

Implement models, configuration, persistence and database.

### Phase 4

Implement AnimePahe search/scanning/parsing.

### Phase 5

Implement WebView/session/cookie handling.

### Phase 6

Implement stream resolution.

### Phase 7

Implement HLS parsing and download engine.

### Phase 8

Implement atomic segment cache and resume.

### Phase 9

Implement MP4 assembly.

### Phase 10

Implement background downloads.

### Phase 11

Implement Media3 playback.

### Phase 12

Implement sessions/watchlist.

### Phase 13

Implement complete Material 3 UI.

### Phase 14

Implement notifications and background checks.

### Phase 15

Run full test suite and fix all failures.

### Phase 16

Perform repository parity audit.

Do not build the entire UI first and leave the networking/download engine as placeholders.

---

# 39. FINAL ACCEPTANCE CRITERIA

Do not consider the application complete until all of the following are true:

* The app builds successfully.
* The app launches successfully.
* Search performs real searches.
* Anime selection works.
* Episode scanning works.
* Audio-variant detection works (SUB/DUB and any other audio value the source exposes).
* Quality selection works.
* Episode selection works.
* Individual downloads work.
* Batch downloads work.
* Multiple episodes can download concurrently.
* HLS segments download concurrently.
* Failed segments retry.
* Completed segments are never unnecessarily re-downloaded.
* Killing the application does not destroy download progress.
* Interrupted downloads resume correctly.
* MP4 assembly produces playable files.
* Completed files are accessible to the user.
* Streaming works.
* Playback position is retained.
* Episode navigation works.
* Audio-variant switching works where supported.
* Sessions can be resumed.
* Sessions can be deleted.
* Cache can be cleared.
* Configuration persists.
* Watchlist works if supported by the current repository.
* Background downloads work within Android's platform restrictions.
* Notifications reflect real download state.
* Offline playback works.
* No production screen contains fake data.
* No production feature is a placeholder.
* No authentication cookies are leaked into logs.
* The app remains usable after process recreation.
* Unit/integration tests pass.
* Android lint/static analysis passes.
* Release build succeeds.

---

# 40. MOST IMPORTANT INSTRUCTION

Do not stop at generating source code.

**Build, test, inspect the generated application, identify failures, fix them, rebuild, and repeat until the application is actually functional.**

Do not tell me that something "should work."

Verify it.

When you encounter a limitation caused by Android, replace the desktop-specific mechanism with a real Android implementation rather than removing the feature.

The finished result should feel like:

**PaheBatcher rebuilt specifically for Android**

—not:

**a website inside a WebView with a download button.**
