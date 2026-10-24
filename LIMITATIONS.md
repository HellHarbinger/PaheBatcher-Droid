# LIMITATIONS.md — Android Platform Limitations

## 1. Cloudflare Bypass

### Limitation
Python uses FlareSolverr (headless Chromium) for Cloudflare challenge solving. Android cannot run Docker or FlareSolverr.

### Affected Features
- Search queries
- Anime metadata fetching
- Play page resolution
- Kwik URL resolution

### Android Workaround
- Use Android WebView for Cloudflare challenge solving
- Extract cookies via CookieManager API
- Use WebView for ALL Cloudflare-protected AnimePahe requests
- OkHttp cannot bypass Cloudflare even with WebView cookies (validated)

### Limitations
- WebView session management is complex
- WebView lifetime must be managed (not always available)
- Cloudflare session expiry requires user notification and revalidation
- All AnimePahe API requests must go through WebView (not OkHttp)

### Risk Level
**HIGH** — If WebView cannot solve Cloudflare challenges, core functionality breaks

### Validation Status (Phase 5 Spike)
**Status**: VALIDATED on real Android 16 device
**Implementation**: WebViewSessionValidator.kt, OkHttpValidator.kt
**Validated Results**:
- ✅ WebView successfully loads AnimePahe and completes Cloudflare challenge
- ✅ Cookie extraction works (5 cookies including cf_clearance)
- ❌ Cookie transfer to OkHttp does NOT bypass Cloudflare (403 with `cf-mitigated: challenge`)
- ❌ OkHttp's TLS fingerprint is detected by Cloudflare
- **Conclusion**: WebView is the browser/session mechanism for all Cloudflare-protected operations

---

## 2. TLS Fingerprinting

### Limitation
Python uses curl_cffi with `impersonate="chrome120"` to bypass TLS fingerprint detection. Android's OkHttp cannot impersonate Chrome's TLS fingerprint.

### Affected Features
- Direct Kwik URL resolution (bypasses FlareSolverr)
- Any HTTP request requiring specific TLS fingerprint
- AnimePahe API requests (validated: Cloudflare detects OkHttp TLS)

### Android Workaround
- Rely on WebView for all Cloudflare-protected requests (validated)
- Use OkHttp only for non-Cloudflare endpoints (HLS segments, AES keys)
- May need WebView fallback for Kwik resolution if TLS-blocked

### Limitations
- OkHttp TLS fingerprint is detectable by Cloudflare (validated)
- Cookie transfer from WebView to OkHttp does not bypass TLS detection
- Performance impact of WebView for API requests
- Kwik resolution may require testing (not yet validated)

### Risk Level
**MEDIUM** — Confirmed 403 on AnimePahe; may affect other endpoints

### Validation Status (Phase 5 Spike)
**Status**: VALIDATED on real Android 16 device
**Implementation**: KwikValidator.kt created to test Kwik resolution
**Validated Findings**:
- ❌ OkHttp cannot impersonate Chrome TLS (confirmed by 403 response)
- ❌ Cloudflare detects OkHttp TLS fingerprint
- ❌ `cf_clearance` cookie is bound to the TLS session that solved the challenge
- ⚠️ Kwik resolution not yet tested with real URLs
- **Conclusion**: WebView required for all Cloudflare-protected AnimePahe requests

---

## 2B. Kwik Domain Infrastructure (Phase 6.1 Validation)

### Limitation
Kwik domains (kwik.cx, kwik.gg, kwik.si, etc.) are either blocked by Cloudflare WAF, down, or parked. This prevents M3U8 extraction even when the WebView successfully loads the Kwik page.

### Affected Features
- Kwik URL → M3U8 URL resolution (the core of Phase 6 stream resolution)
- Complete stream resolution flow (play page → resolution → M3U8 → StreamInfo)

### Validated Findings (Real Android 16 Device, Phase 6.1)
| Domain | Status | Details |
|--------|--------|---------|
| kwik.cx | BLOCKED | Cloudflare WAF: "Sorry, you have been blocked" |
| kwik.gg | DOWN | HTTP 521: Web server is down |
| kwik.si | PARKED | Domain for sale (parked page) |
| kwik.me | ERROR | "Content cannot be displayed" (DMNDT_3) |
| kwik.net | TIMEOUT | Page load timeout after 30s |
| kwik.in | ERROR | Same as kwik.me |
| kwik.cc | EMPTY | Redirects with tracking params, no content |

### Root Cause (corrected in Phase 11.5E-4)

Two conditions are required, and only one of them was ever under our control:

1. **A Referer must be sent.** Measured against the live Kwik page with the WebView's own mobile UA: **200** with `Referer: https://animepahe.com/`, **403** (Cloudflare WAF) without it, **403** with a Referer but no UA. Python sends it unconditionally (`kwik.py:_HEADERS["Referer"] = "https://animepahe.com/"`); Android never did — `fetchPage` had no header parameter at all. The same applies one hop later: the CDN playlist answers **200** only for the KWIK-PAGE referrer and **403** for the AnimePahe play-page referrer Android used to persist.
2. **A Chrome-class fingerprint must be used.** Plain (non-impersonated) TLS is refused *even with* the correct Referer.

The earlier statement in this section — "Python bypasses TLS fingerprint detection with `curl_cffi`; Android WebView's system TLS fingerprint is detected" — was **incomplete and therefore misleading**: a Chrome-class fingerprint alone still yields 403 without the Referer, and Android's WebView fingerprint is accepted once the Referer is present (measured 200 from a Chrome-class client; the on-device WebView result is recorded under *Implementation Status* below and is not assumed here). The actionable defect was ours: a missing request header, not an unfixable TLS property.

### Android Workaround (Phase 11.5E-4)

- `CloudflareSessionProvider.fetchPage(url, ..., extraHeaders)` — caller-declared headers for that top-level navigation only.
- `KwikResolver` supplies `Referer: https://animepahe.com/` (Python's `_HEADERS` referer) for Kwik requests and for none of the AnimePahe requests.
- `StreamResolverImpl` persists the KWIK PAGE as `StreamInfo.referer`, so the playlist/segment/key requests carry the referrer that CDN requires.
- Header **names** are logged for evidence; header values, cookies and tokens never are (AGENTS §10).
- No TLS, hostname, certificate or cleartext bypass of any kind; no Cloudflare challenge is being evaded — this is the header Python already sends.

### Risk Level
**RESOLVED for the Kwik → M3U8 step (Phase 11.5E-4, verified on device)** — this section previously read HIGH/"cannot extract M3U8 URLs because the domains are blocked". That conclusion was wrong: the domains were reachable, and the missing Referer was the whole story.

### Implementation Status (Phase 11.5E-4, on-device evidence, production app)
- ✅ `KwikResolver` domain rotation works (rotation URL keeps `/e/{id}` and drops query/fragment — kwik.py:85-90).
- ✅ `fetchPage(..., extraHeaders = KWIK_REQUEST_HEADERS)` sends `Referer: https://animepahe.com/`; device log: `CFSession: Kwik request referrer present: true`.
- ✅ The **real** Kwik page is fetched instead of an error page: `Kwik page fetch success=true, htmlLen=22274`, `Cloudflare challenge completed (cycle 1)`.
- ✅ M3U8 extraction works: `M3U8 extraction result: https://vault-13.owocdn.top/…/uwu.m3u8` → `Stream resolved: quality=1080, audio=eng`.
- ✅ `StreamInfo.referer` = the Kwik page URL, so the CDN honours the playlist, the AES key (`AesKeyCache: Cached key: …/mon.key (16 bytes)`) and every segment — **146/146 segments downloaded**.
- ✅ MP4 assembly of those segments now succeeds (was failing for an unrelated, pre-existing reason — §26, **RESOLVED in Phase 11.5E-5**: 146/146 → `Assembly succeeded via concat demuxer` → real MP4 exported).

### Previous "Possible Resolutions" — withdrawn
Waiting for domain recovery, switching domains, proxy/VPN bypass and a FlareSolverr equivalent were all proposed while the diagnosis was wrong. **No bypass of any kind was implemented or is proposed** (AGENTS §11): the fix was the one header Python already sends.

---

## 3. FFmpeg Muxing

### Limitation
Python uses system FFmpeg binary via subprocess. Android cannot execute subprocess FFmpeg. TS→MP4 assembly requires container demuxing, AAC ADTS→ASC conversion, timestamp remapping, and faststart support.

### Affected Features
- TS segment concatenation into MP4
- faststart flag for streaming
- Pipe mode fallback

### Android Workaround
- **Selected & validated candidate**: `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`
- FFmpegKit provides the exact same FFmpeg binary, ensuring identical assembly behavior
- LGPL-3.0 licensing (no copyleft contamination)
- Handles all TS→MP4 remuxing, AAC ADTS→ASC conversion, faststart, and pipe fallback natively

### Verified Facts (2026-09-22)
- ✅ Dependency resolves from Maven Central
- ✅ Builds successfully with AGP 9.4.1, Gradle 9.7.1, JDK 25, compileSdk 37
- ✅ NDK r27c (27.2.12479018) installed and used
- ✅ Native libraries packaged for arm64-v8a AND x86_64 (NOT arm64-only as previously documented)
- ✅ Native library paths configured correctly on x86_64 emulator
- ✅ App launches without native loading errors on x86_64 emulator
- ✅ All 324 existing tests pass with FFmpegKit dependency added
- ⚠️ APK size increase: +66.5 MB (32.7 MB → 99.2 MB) — more than the ~30 MB estimate
- ❌ FFmpeg command execution not yet verified (requires Phase 9 implementation code)

### Remaining Limitations
- APK size increase is 66.5 MB (not ~30 MB as estimated)
- ~~arm64-v8a runtime validation deferred (no arm64 device currently connected — x86_64 validated)~~ **Superseded 2026-09-28 (reviewer M-2): arm64-v8a runtime validation was achieved in Phase 11.5E-5 on physical device `REDACTED-SERIAL` (arm64) — FFmpegKit load + execute + real 146-segment faststart MP4 assembly, plus the full instrumentation suite on that device (DECISIONS 73/74/75). The stated reason ("no arm64 device currently connected") was false: `REDACTED-SERIAL` **has been** the only connected device since 11.5E-4** *(date qualifier added round-5 finding 6: "has been" held only up to 2026-09-28 ~19:55 — the device has since gone offline and `adb devices` returns nothing)*

### Validation Status
**Status**: VALIDATED — Build Compatible, Runtime Compatible, Assembly Verified (x86_64 Phase 9; **arm64 Phase 11.5E-5**)
- NDK r27c: INSTALLED
- Build verification: PASSED
- Runtime verification: PASSED (x86_64 emulator, native library loading)
- FFmpeg execution: VERIFIED (x86_64 emulator, TS→MP4 assembly)
- Assembly behavior: VERIFIED (concat demuxer + pipe fallback, faststart, cleanup, skip logic)

### Risk Level
**LOW** - Dependency validated, build compatible, runtime loading confirmed, assembly behavior verified on x86_64 **and** arm64-v8a. ~~arm64-v8a runtime validation deferred to device availability~~ **Superseded 2026-09-28 (reviewer M-2): arm64-v8a validation was achieved in Phase 11.5E-5 on physical device `REDACTED-SERIAL`** - FFmpegKit load + execute + a real 146-segment faststart MP4 assembly, plus the full instrumentation suite on that device (DECISIONS 73/74/75) - which is exactly what this section's own "Validation Status" line and its struck-through "Remaining Limitations" line already record, so this Risk Level paragraph was the one place that still read as the pre-2026-09-28 state. The later date qualifier on that struck line ("the device has since gone offline") is likewise history rather than current state: every connected run from 2026-10-04 onward has found `REDACTED-SERIAL` online, including this pass.

---

## 4. Background Execution

### Limitation
Android restricts long-running background tasks. Python can run downloads indefinitely in the terminal.

### Affected Features
- Long episode downloads
- Batch downloads
- Watchlist background checks

### Android Workaround (Phase 10)
- **CoroutineWorker with setForeground()** — NOT a custom ForegroundService
- WorkManager manages the internal SystemForegroundService lifecycle
- Foreground notification displayed during download (VERIFIED on emulator-5554: SystemForegroundService `isForeground=true types=0x1`/dataSync; NotificationRecord with FOREGROUND_SERVICE flag; notification-shade screenshot)
- Process death recovery via Room database + WorkManager re-enqueue (VERIFIED on emulator-5554: seed Worker mid-download → process killed → relaunch → stranded episode recovered, Worker restarted — logcat evidence)
- Reconciliation on app startup handles orphaned/interrupted downloads (VERIFIED on emulator-5554 logcat)

### Limitations
- Foreground service requires persistent notification (IMPORTANCE_LOW)
- **POST_NOTIFICATIONS runtime permission (Android 13+)**: without the grant, the foreground service still runs but its progress notification may be hidden from the user
  - The permission is **declared in the manifest only**; the app does **not** yet issue the runtime prompt (no Activity-launched `RequestPermission` contract exists in any production path — the first-trigger requester belongs to the Phase 13 enqueue flow, per the Phase 11 §20 design). What Phase 11 **did** implement and verify (`NotificationPermissionRepository`, `NotificationPermissionTest`) is the tested permission-**state** architecture: one-time `askedOnce` latch + `granted` flag in DataStore, opportunistic re-grant refresh, API 32 no-op. Phase 10 runtime evidence was produced **with the permission explicitly granted by the tests** (`Phase10TestSupport.grantNotificationPermission()`). On a fresh install without a prior grant, the foreground service still runs but its progress notification may be hidden; do not claim notifications appear automatically on a fresh install [**SUPERSEDED 2026-10-22**: the runtime prompt now ships — Phase 13's enqueue-flow requester is implemented and production-wired: `AnimeDetailsViewModel.maybeRequestNotificationPermission()` (`AnimeDetailsViewModel.kt:1607`, invoked at `AnimeDetailsViewModel.kt:1549` and `:2554` after a real enqueue succeeded) drives the `ActivityResultContracts.RequestPermission()` contract registered at `AnimeDetailsScreen.kt:209`, and the Settings notification row offers a second request path (`SettingsScreen.kt:123`, `:631`). Phase ownership and the 2026-10-02 shipment are recorded in §34.5 below. The "declared in the manifest only / no production prompt" statement above is Phase 11–13 historical state; the fresh-install caveat remains true only until the user answers the one-time prompt.]
- **Android 15+ foreground-service time limits**: the `dataSync` service type has a system-imposed cumulative time limit (6 hours per 24h); downloads exceeding it are stopped and must be recovered by WorkManager + reconciliation on next launch
- Android 16+ job/runtime quota behavior applies
- Battery optimization may still affect long-running Workers
- Doze mode restricts network access (but foreground service mitigates)
- Worker execution window is extended for foreground Workers but NOT unlimited
- **`setForeground()` failure is swallowed** *(added 2026-09-28, reviewer L-8; scope corrected 2026-09-28 Phase 11.5F, reviewer M2)*: `DownloadWorker.executeClaimedWork()`'s foreground block wraps channel/notification creation and `setForeground(foregroundInfo)` in `catch (e: Exception) { Log.e(...); /* Continue anyway */ }`. If it throws (`MissingForegroundServiceTypeException`, `SecurityException`, an OEM quirk), the Worker continues as an ordinary background JobScheduler job, so the ~10-minute execution-timeout lift that the foreground service exists to provide is silently lost — a multi-minute 146-segment run can be stopped mid-flight and left `DOWNLOADING` until the next reconciliation. No metric or user-visible signal marks this downgrade. **This bullet's other claim is now fixed**: the same `catch` used to swallow `CancellationException` (part of how the §27 entry-transition window opened) — Phase 11.5F added an explicit `catch (e: CancellationException) { throw e }` arm ahead of it, and cancellation cleanup now persists `CANCELLED` under `NonCancellable` + rethrows (L-8, §27.3). The remaining behavior described above (silent NON-cancellation downgrade, continue-anyway) is deliberately left in place and stays **OPEN** — aborting a healthy download on a notification-channel quirk would be worse than continuing; closing it properly needs a user-visible warning signal (future notification/UI phase)
- **Verification scope**: Phase 10 runtime verification was performed on the x86_64 emulator (emulator-5554, API 36); **arm64-v8a real-device validation was subsequently performed in Phase 11.5E-5 on `REDACTED-SERIAL`** *(previous wording "arm64-v8a real-device validation is deferred (no arm64 device available)" corrected 2026-09-28, reviewer M-2 — the full instrumentation suite has also run on that arm64 device)*

### Android-Specific Differences
- **Batch retry**: Python automatically retries failed episodes in batch; Android requires manual user re-enqueue (ANDROID_REQUIRED_DIFFERENCE)
- **maxParallel**: Python uses asyncio.Semaphore (cooperative); Android uses Room active-count gating (best-effort soft limit, not identical synchronization semantics)
- **Foreground service type**: Requires `dataSync` type (FOREGROUND_SERVICE_DATA_SYNC permission) for Android 14+

### Risk Level
**MEDIUM** — Downloads may be interrupted by system, but recovery mechanisms in place

---

### Test-Environment FGS Quota Timeout - Observation Record (2026-10-23)

- Observed once during the run-ownership device round (DECISIONS 164): one `RealProcessDeathPausedDeviceTest` attempt ended with the system killing the `dataSync` foreground service work (the quota limit described above) - foreground stop, worker cancellation, and a genuine persisted CANCELLED. That matches this section's documented system behaviour rather than an application defect.
- The same test passed unchanged on immediate retry (no code change between attempts).
- Classification: environmental device-test flakiness under back-to-back foreground-work quota use; single occurrence; retry clean. No application change was made in response.

### Run-Ownership Guard Scope (DECISIONS 164)

- The atomic run-token guard covers run-owned STATE writes (claim, transition, pause, resume, completion, cancellation). The throttled progress flusher (`updateProgress`, `EpisodeDownloadDao.kt:140`) and `updateTotalSegments` (`EpisodeDownloadDao.kt:612`) are intentionally outside it: a stale run may still overwrite the row's progress/total FIELDS (monotonic counters and totals), but cannot overwrite state, ownership, output path, error text or completion. Accepted, fields-only residual - documented here rather than hidden.

---

## 5. Storage Access

### Limitation
Android 10+ enforces scoped storage. Python has unrestricted file system access.

### Affected Features
- Output directory selection
- Cache directory management
- Temporary segment storage
- Completed file access

### Android Workaround
- Use SAF (Storage Access Framework) for user-selected directories
- Use app-specific internal storage for cache
- Use MediaStore for scanning completed files

### Limitations
- SAF requires user interaction for directory selection
- Internal storage is not user-accessible
- MediaStore has write restrictions
- File paths change across Android versions

### Risk Level
**LOW** — Well-established Android patterns exist

### Phase 11 Architecture Note (Storage Strategy — IMPLEMENTED and verified in Phase 11)
**PHASE11_ARCHITECTURE_V2.md §27/§30** (Decision 45, as corrected by Decision 54; supersedes V1 §22) selected the storage design, and **Phase 11 implemented and verified it**: **completed MP4s export to `MediaStore.Video` under `Movies/PaheBatcher/` (outside `Android/data`, no runtime storage permission at minSdk 32)**, working data (segments/`.tmp`) stays app-private in `filesDir/downloads/{episodeId}/`; Room schema v2→v3 added `outputUri` (MigrationTest 5/5). `getExternalFilesDir()` was rejected (user-invisible on Android 11+); a SAF user-chosen directory remains deferred to the Settings phase (13). Verified by MediaStoreExportTest 8/8 on emulator-5554. The export design is **best-effort idempotent, not transactionally atomic** — see §18. *(F-1 attribution update, 2026-10-01: the SAF design is no longer undecided — DECISIONS 92 settles the hybrid pipeline (private staging + validated SAF tree else MediaStore default, byte-verified export before local delete, no SAF atomic-rename claim, collision reuse-by-size or uniquify); the **implementation** remains Phase 13, so this row correctly stays ⚠️ PARTIAL until it ships. Full residual list: §34.2.)* *(Shipped 2026-10-02: the SAF export target implemented in Phase 13 — PARITY's Output directory row is now ✅ and §34.2 carries the shipped notes; the 2026-10-01 wording above is preserved as written.)* *(Gate A 2026-10-08: new exports now land under `Movies/<sanitized-anime-title>/` (DECISIONS 141); the flat `Movies/PaheBatcher/` layout above is the pre-Gate-A Phase 11 record, retained only for legacy files that are intentionally never migrated — see §40.)*

---

## 6. Process Death

### Limitation
Android can kill app process at any time. Python process runs until explicitly stopped.

### Affected Features
- Download state persistence
- Resume after interruption
- Queue management
- Progress tracking

### Android Workaround
- Room database as single source of truth
- WorkManager for persistent task state
- Foreground service for active downloads
- Lifecycle-aware observation

### Limitations
- In-memory state is lost
- Coroutine scope is destroyed
- Pending operations may be lost
- User experience on restart

### Risk Level
**LOW** — Room + WorkManager handles this well

---

## 7. Memory Constraints

### Limitation
Python can load entire episodes into memory if needed. Android has strict memory limits.

### Affected Features
- Large segment downloads
- Concurrent segment processing
- AES decryption buffers
- M3U8 parsing

### Android Workaround
- Stream segments incrementally
- Use bounded buffers
- Limit concurrent operations
- Use Flow for reactive streams

### Limitations
- Cannot buffer entire episode
- Must process segments incrementally
- Memory pressure on low-end devices
- Garbage collection pauses

### Risk Level
**LOW** — Streaming patterns are well-established

---

## 8. Notification System

### Limitation
Python uses terminal output. Android requires notification system for background state.

### Affected Features
- Download progress
- Download completion
- Error reporting
- Session state

### Android Workaround
- NotificationManager for system notifications
- Foreground service notification for active downloads
- Grouped notifications for batch operations
- Deep links for notification actions

### Limitations
- Notification permission required (Android 13+) — **runtime prompt not yet issued by any production path** (Phase 11 implemented + tested the DataStore permission-state architecture in `NotificationPermissionRepository`; the Activity-launched request itself is deferred to the Phase 13 enqueue flow — see §4) [**SUPERSEDED 2026-10-22**: issued in production since Phase 13 shipped 2026-10-02 — see §34.5 and the supersession note in §4 above; the enqueue-gate requester is `AnimeDetailsViewModel.maybeRequestNotificationPermission()` (`AnimeDetailsViewModel.kt:1607`) behind the contract at `AnimeDetailsScreen.kt:209`, with a second request path on the Settings screen (`SettingsScreen.kt:123`, `:631`).]
- Users can disable notifications
- Notification channels required
- Battery optimization may delay notifications
- Completion/failure notification builders **were deleted as dead code in Phase 11.5 item (c)** (2026-09-28): `buildCompletionNotification` and `buildFailureNotification` had no production call site, and the app-owned `ic_stat_download_done` / `ic_stat_error` vectors were removed with them, so **neither symbol nor vector exists anywhere in `app/src` today** (re-verified: 0 matches for either name; the only surviving `ic_stat_*` file is `ic_stat_download.xml`). Only the foreground progress notification (`buildProgressNotification`, production-wired) is posted today. Phase 14 must re-create both builders and both vectors before it can wire them — see `ROADMAP.md:799-800`. *(Corrected in round 6, finding 3: this bullet previously said the builders "exist in `DownloadNotificationManager` but are not yet invoked by any production path", which was false from the moment 11.5 item (c) deleted them; `ROADMAP.md:908` carried the identical false claim and is corrected alongside it, while `ROADMAP.md:799-800` had already been right.)* **SUPERSEDED 2026-10-03 (Phase 14):** both builders
(`buildCompletionNotification` / `buildFailureNotification`) and both app-owned vectors
(`ic_stat_download_done` / `ic_stat_error`) were **re-created and production-wired in Phase 14**,
verified by `NotificationTest` (device) — the deleted-state record above is preserved as the
Phase 11.5/12/13 historical state.

### Risk Level
**LOW** — Standard Android pattern

---

## 9. WebView Limitations

### Limitation
Python uses FlareSolverr's headless Chromium. Android WebView is a different browser engine.

### Affected Features
- Cloudflare challenge solving
- JavaScript execution
- Cookie management
- Session persistence

### Android Workaround
- Use Android WebView with JavaScript enabled
- Extract cookies via CookieManager (in-memory only)
- Persist session METADATA (User-Agent, host, timestamp) — NOT raw cookie values
- Refresh sessions when expired

### Limitations
- WebView may not solve all challenges
- WebView version varies by device
- WebView has different JS engine than Chrome
- WebView lifetime is limited
- WebView does not expose HTTP status codes (always reports 0 or unknown)
- WebView renderer can crash (onRenderProcessGone handler recommended)
- extractPageContent must use suspendCancellableCoroutine (not CountDownLatch) to avoid main thread deadlock
- WebView destruction must be dispatched to main thread (must not be called from background threads)
- Session validity requires timestamp-based freshness check (cookie presence alone is insufficient — cookies expire after ~30 minutes but CookieManager does not purge them)

### Risk Level
**MEDIUM** — WebView compatibility varies

---

## 10. Concurrency Model

### Limitation
Python uses asyncio with single-threaded event loop. Android uses Kotlin coroutines with thread pools.

### Affected Features
- Concurrent segment downloads
- Parallel episode downloads
- Queue management
- Progress tracking

### Android Workaround
- Use Kotlin coroutines with structured concurrency
- Use Flow for reactive state
- Use Semaphore for bounded concurrency
- Use Channel for producer-consumer patterns

### Limitations
- Different concurrency primitives
- Thread safety concerns
- Dispatchers.IO for blocking operations
- Coroutine scope management

### Risk Level
**LOW** — Coroutines are well-suited for this

---

## 11. File System Differences

### Limitation
Python uses pathlib for cross-platform file operations. Android has different file system semantics.

### Affected Features
- Path construction
- File naming
- Directory creation
- File permissions

### Android Workaround
- Use Android File APIs
- Use DocumentFile for SAF
- Use Context.filesDir for internal storage
- Use Environment.getExternalStorageDirectory() for legacy

### Limitations
- Path separators differ (/ vs \)
- File name restrictions on Android
- Permission model differs
- Storage volume changes

### Risk Level
**LOW** — Standard Android file handling

---

## 12. Network Differences

### Limitation
Python assumes stable network connection. Android has flaky mobile networks.

### Affected Features
- Long-running downloads
- Connection pooling
- DNS resolution
- TLS handshake

### Android Workaround
- Use ConnectivityManager for network state
- Implement retry with exponential backoff
- Use OkHttp connection pool
- Handle network changes gracefully

### Limitations
- Network may change mid-download
- WiFi/Mobile data switching
- Airplane mode interruptions
- Data saver mode restrictions

### Risk Level
**LOW** — Standard Android networking

---

## 13. Test Environment: Package Vanishing After Instrumentation Runs

### Limitation
During Phase 10 gate runs on the emulator (emulator-5554), both installed packages (`com.pahebatcher.app` and `com.pahebatcher.app.test`) were repeatedly observed disappearing from the device around `connectedDebugAndroidTest` runs — observed 3 times across corrective cycles 2 and 3 (twice after a suite run completed, once before a suite run). Root cause is undetermined; no production code path performs uninstallation, and the disappearance does not corrupt a completed test run (results XML is produced from the completed run before disappearance).

**Trigger pinned in Phase 15 (2026-10-04, controlled before/after experiment):** with `com.pahebatcher.app` freshly installed (`pm path` non-empty), a single `connectedDebugAndroidTest` run (`ExampleInstrumentedTest`) was executed and `pm path` was empty immediately afterwards - the same result as after the full 413-test runs on the emulator and on `REDACTED-SERIAL`. The disappearance is therefore **deterministically associated with the connected test task itself**, not with chance or with any app behavior; the mechanism *inside* the toolchain remains undetermined, so this stays an open **test-environment** item (it is **not** a product defect and is never counted as one in a test result).

**Trigger sharpened in the audio-slice run (2026-10-08):** the disappearance follows the **Gradle connected task**, not instrumentation itself. After a manual `adb -s REDACTED-SERIAL install -r app-debug-androidTest.apk`, two sequential direct `adb shell am instrument` invocations (`GateASpecialTitleDeviceTest` → `GateASecondEpisodePlaybackDeviceTest`, ~2 minutes apart, with a `pm path com.pahebatcher.app` check in between) left the package **installed the whole time** - non-empty `pm path` between the runs and after the second one. The in-toolchain mechanism is still undetermined, but the boundary is now actionable and was used as such: suites driven by Gradle wipe both packages, direct `am instrument` does not - which is how the GateA order prerequisite (§41.5) was satisfied without a third Gradle run.

### Affected Features
- Full instrumentation suite runs (`connectedDebugAndroidTest`)
- Deployment verification (AGENTS.md §20)
- **Any manual device verification performed *after* a connected run**

### Android Workaround
- Before each instrumentation run, verify `pm list packages` shows both packages; if missing, reinstall both APKs with `adb -s <serial> install -r` before running the suite
- After a suite run, re-verify package presence; reinstall if vanished (per AGENTS.md §20)
- **Reinstall the debug APK before ANY manual verification that follows a connected run** — because the package (and therefore its data) is gone, the reinstalled app starts at defaults, so the settings required for the manual check must be re-applied first
- Never interpret package absence as an app regression without first re-checking installation state

### Risk Level
**LOW** — handled by reinstall; does not affect app behavior or the validity of completed test results. Cause remains under observation.

---

## 14. Application Scope: Production UI Entry Point (Phases 1–10; SINGLE-EPISODE ENTRY ADDED IN 11.5E-2/E-3/E-4)

### Limitation
Phases 1–10 implement and verify the underlying download/resolution/background-download
infrastructure with **no production UI entry point for search → resolve → download** —
that was deferred to Phase 13. **Phase 11.5 then delivered the single-episode entry**
(see *Current Fact*), so this section now describes the remaining scope gap, not an
entirely missing path.

### Current Fact
- **Phase 1–10 fact (historical)**: the only production screen was a static home screen
  (static "PaheBatcher" text; long-press opens the development ValidationScreen, see §16 —
  symbol reference corrected in Phase 11.5A: the former `MainActivity.HomeScreen` no longer exists;
  today the home composable is `ui/home/HomeScreen.kt`, hosted by `AppNavHost` inside the `MainActivity`
  activity host. Full screen-inventory reconciliation is deferred to D3 / 11.5E)
- **Phase 11.5E-2/E-3/E-4 update — the SINGLE-EPISODE entry point now EXISTS and was
  verified on device**: Home search → tap a result → Anime Details scan
  (`EpisodeRepository`) → tap an episode → `StreamResolverImpl` resolve →
  `AnimeDetailsViewModel.downloadResolvedEpisode` → `EpisodeDownloadQueue` →
  production `DownloadRepository` (session/episode/stream rows) → `DownloadWorker` →
  `EpisodeDownloader` → HLS → `.ts` files. Phase 11.5E-4 drove it end to end on a real
  network with the production APK (146/146 segments, resume verified).
- **Still true**: `range`/all-episode downloads, Settings-driven quality and queue
  redesign/retry (§24) remain unimplemented or unfinished. *(MP4 assembly of a real
  playlist — §26 — was resolved in Phase 11.5E-5 and no longer belongs on this list;
  §24 finding 1 — Downloads Retry re-resolution — was resolved in Phase 11.5E-7,
  findings 2–3 remain.)*
- `EpisodeRepository`, `ScanCacheRepository`, `StreamResolverImpl` now HAVE production
  callers (the Anime Details ViewModel, since 11.5E-2/E-3).
- This matches PHASE10_ARCHITECTURE_V10.md §20 scope guard ("No UI: DownloadViewModel and UI remain Phase 13") for Phases 1–10 and the Phase 11 Scope Note for Phases 11–12; the
  entry point above arrived with the Phase 11.5 slices instead.

### Affected Features
- Range/all-episode downloads (production entry point — Phase 13)
- Settings-driven quality selection (Phase 13)
- ~~Delivering a finished, playable file for a live stream (MP4 assembly — §26)~~ — **resolved in Phase 11.5E-5** (single-episode MP4 verified on device)
- Any user-facing description of the APK as a fully usable downloader

### Risk Level
**Informational** for the historical scope; the single-episode path is real and verified on device since Phase 11.5E-4, and since Phase 11.5E-5 it delivers a playable MP4 (§26 **RESOLVED**). Documentation and release notes must still **not** describe the APK as a fully usable downloader: queue/retry gaps remain (§24), and range/all downloads are unimplemented.

---

## 15. `STATE_ASSEMBLING` Never Written by Production Code (RESOLVED 2026-10-07 - production now writes it)

### Limitation
`EpisodeDownloadEntity.STATE_ASSEMBLING` exists in the persistence/state model, and `DownloadStateReconciler` contains a handler for it, but **no production code path writes this state** (verified by source grep of `app/src/main`: the constant definition and *readers* only — `DownloadStateReconciler` handler, `DownloadRepository` live-row/submission-guard branches, `DownloadRowMapper` concept + active-state list, `EpisodeDownloadDao.countActive()`; only tests construct entities in `ASSEMBLING`). **(RESOLVED 2026-10-07, final audit H4:** `DownloadWorker.executeClaimedWork` now CAS-flips DOWNLOADING -> ASSEMBLING through the `onAssemblyStart` hook of `EpisodeDownloader` before the assembler runs, so production writes this state; device-proven by `DownloadWorkerAssemblyStateTest` - exactly one DOWNLOADING -> ASSEMBLING transition and a COMPLETED row whose expected state is ASSEMBLING. The grep above describes the pre-2026-10-07 code. DECISIONS 139.)**

### Affected Features
- Process-death recovery **during assembly** — the architecture's ASSEMBLING recovery scenarios and the reconciler's `computeExpectedOutput()` path are reachable only when tests seed that state; production end-to-end ASSEMBLING recovery has **not** been verified under a natural assembly flow
- **Two production UI branches are consequently unreachable** *(added 2026-09-28, reviewer N-1)*: `DownloadsScreen.kt:223` (the assembling `LinearProgressIndicator`) and `DownloadRowMapper.kt:80,113` (`DownloadUserConcept.ASSEMBLING` → the active-state list) can never fire, because `DownloadWorker` writes `DOWNLOADING` at entry and never transitions to `ASSEMBLING` before `FfmpegEpisodeAssembler` runs. The user-visible consequence is that during a ~30 s FFmpeg run the row reads `downloading · 146/146`, never "assembling". Writing the state (small, testable) was deferred to Phase 11.5 item (c) *(RESOLVED 2026-10-07: production now writes ASSEMBLING before the FFmpeg run, so both branches fire in production - DECISIONS 139)*

### Status
Implementation/reconciliation limitation. The state and reconciler branch are intentionally retained (removing them would weaken the recovery model); documentation must not claim production end-to-end ASSEMBLING recovery was verified. **Never-written gap RESOLVED 2026-10-07 (final audit H4):** the state is now written by production and the transition is device-verified (`DownloadWorkerAssemblyStateTest`), so the reconciler ASSEMBLING branch is reachable in production; a natural process-death *during* the FFmpeg run remains unexercised on device (instrumentation constraint, §20), so recovery-under-death itself stays device-unverified. DECISIONS 139.

### Risk Level
**LOW** — assembly is short relative to segment download, and all other states (`QUEUED`, `DOWNLOADING`, `COMPLETED`, `FAILED`, `CANCELLED`) are production-exercised and test-verified.

---

## 16. Development Validation Screen Shipped in Release Source

### Limitation
`ui/validation/ValidationScreen.kt` is **development/diagnostic functionality** that lives in the `main/` source set (so the class is compiled into any APK built from `main/`), but it is a **debug-only surface**: both its entry point and its host are guarded by `BuildConfig.DEBUG` in `MainActivity.kt` — the Home long-press launcher lambda (`onOpenValidation`) and the `showValidation` overlay branch — with `buildConfig = true` in `app/build.gradle.kts`. **Release builds (`BuildConfig.DEBUG == false`) do not expose the validation entry point and cannot reach the screen** (Decision 51; Phase 11.5A reconciliation — Decision 70). In debug builds it is opened by a **long-press on the home screen**.

It contains development-only validation behavior, including:
- SSL error `proceed()` bypass (`WebViewSessionValidator.onReceivedSslError` → `handler?.proceed()`) — intentionally permitted **for diagnostic use inside this debug-gated screen only**
- `MIXED_CONTENT_ALWAYS_ALLOW` (same debug-only confinement)
- on-screen cookie diagnostics that expose/log cookie **names/keys only** — never raw cookie values
  - **Enforced 2026-09-28 (Phase 11.5G, scope item (a))**: `WebViewSessionValidator` actually wrote raw cookie-header values (`cookiesFromCom.take(50)` / `cookiesFromPw.take(50)`) into `diagnosticMessages`, which `ValidationScreen` renders verbatim at `ValidationScreen.kt:133` — contradicting this bullet and DECISIONS 70. Both lines now emit `parseCookies(...).keys` (names only). Production `AndroidCloudflareSession` was never affected.
- placeholder metrics/cards that are not real functionality (self-labeled "DO NOT use in production" in the composable's KDoc)

### Status
- It must **not** be treated as production functionality, and the debug validator's relaxed SSL/mixed-content behavior is **not suitable for release production use** — those relaxations exist solely for diagnostics behind the debug gate
- Production `AndroidCloudflareSession` does **not** use any of these debug-only relaxations: no SSL-error override, no mixed-content mode, no WebView debugging enablement
- It was **not** a Phase 10 failure — it predates Phase 10 and was never in Phase 10's scope
- **Gated, not removed**: the previously required release-readiness action ("gate or remove before external distribution") is **satisfied by the `BuildConfig.DEBUG` gate** (Decision 51); removal remains optional, not required (Decision 70)

### Risk Level
**LOW (release readiness)** — the debug validator's intentionally relaxed behavior is confined to a screen that release builds cannot reach (`BuildConfig.DEBUG` gate, Decision 51); no effect on current test results or gate status. Do not ship debug builds as production artifacts.

---

## 17. Poster Source Unverified (Phase 11 Architecture)

### Limitation
AnimePahe poster/image availability could **not be verified**: `AnimePaheParser.parseSearchResults` whitelists **no poster/image field**, the Python TUI renders **no poster column**, and the live API was network-blocked during archaeology, so whether poster URLs exist in the raw payload is **unverified (external)**.

### Affected Features
- Search/details poster artwork in the future UI — no verified source means no real poster data can be rendered

### Android Workaround
The Phase 11 architecture (PHASE11_ARCHITECTURE_V2.md §15, Decision 44) defines a **four-state poster contract** — real / loading / failed / vector-title-initial placeholder — and ships **placeholders only** until a poster field is verified in a real API response. Poster URLs are never invented, constructed, or pattern-guessed.

### Risk Level
**LOW (external)** — purely cosmetic; no engine/network impact. Resolution requires reaching the live API once to confirm payload fields.

---

## 18. No Cross-Store Atomicity Between MediaStore and Room (Phase 11 Architecture)

### Limitation
Android provides **no transaction spanning MediaStore and Room**: an exported video's visibility (MediaStore `IS_PENDING`) and the database record (`outputUri`) cannot be committed together. Perfect atomicity is **impossible** on the platform.

### Affected Features
- Completed-video export (PHASE11_ARCHITECTURE_V2.md §27/§30, Decision 54) — crash windows exist between the copy, the Room write, and the pending-flag clear

### Android Workaround
The design is **best-effort idempotent**: normative ordering (persist `outputUri` **before** clearing `IS_PENDING`, then delete local), deterministic duplicate-row lookup before any insert, and startup reconciliation via an injected `ContentResolver` (resume-if-verifiable, else sweep stale pending rows). A crash at any point leaves either an invisible pending file or a Room-known URI — never a visible file Room cannot identify (except the narrow documented rename-after-lost-URI edge, §30.2 of V2).

### Status (Phase 11 Slice 6 — implemented)
Export code now exists and is verified (`MediaStoreExportTest` 8/8 on emulator-5554,
including the process-kill/duplicate-prevention matrix of V2 §36-7). Room v3 migration
(`outputUri` + playback-position table) verified by `MigrationTest` 5/5.

### Related Export Caveats
- **Rename-after-lost-URI edge**: if a row's `outputUri` is lost (e.g., the MediaStore row
  was deleted by the user while Room still references it) reconciliation can only
  re-insert under a **system-assigned possibly-renamed display name** — byte-identical
  content, but the visible filename may differ from the original `Ep N - Title.mp4`.
  Narrow, documented (V2 §30.2); not a data-loss path.
- **Insufficient-space test substitute**: instrumentation cannot legitimately fill device
  storage, so the export tests **substitute `EACCES` for `ENOSPC`** to drive the
  "storage full → keep local file, no partial visible row" contract. The production
  branch keys off the real pre-copy space verification; only the trigger error is
  simulated (test-environment constraint, Decision 63). The production probe is Android's `File.usableSpace` (`StorageSpace.kt`), the platform stand-in for Python's statvfs — API 32+ exposes no unprivileged statvfs equivalent (DECISIONS 139); with the ENOSPC trigger simulatable only, the storage gate stays a documented non-blocking verification limitation (DECISIONS 140).

### Risk Level
**LOW** — recovery is deterministic and covered by the process-kill test matrix (V2 §36-7), which passes (MediaStoreExportTest 8/8).

---

## 19. Material 3 Touch Targets: 40 dp Inner Content vs 48 dp Reserved Minimum (Phase 11)

### Limitation
Material 3 widgets (IconButton, Slider) report **40 dp** layout bounds for their inner
interactive content; the 48 dp minimum exists only as M3's reserved outer region
(`minimumInteractiveComponentSize` + input-layer padding). The M3 Slider additionally
gates touch-bound padding behind `OnClick` semantics and applies an internal
`requiredSizeIn` that **blocks any upstream constraint fix**, so its inner semantics node
can never be padded from outside the library.

### Affected Features
- §21.5 compliance verification for custom/overridden control slots (player seek slider,
  Back button, icon actions)

### Android Workaround
- Assert §21.5 on **`SemanticsNode.touchBoundsInRoot`** (converted px→dp), never
  `boundsInRoot`, with `useUnmergedTree = true` for wrapped nodes (Decision 64)
- For the seek slider, production wraps the slot's M3 Slider in a
  `Box(Modifier.fillMaxWidth().testTag("player_seek_slider"))` — a layout-level ≥48 dp
  anchor for the control area the slot presents, with zero visual change — and tests
  assert layout+touch ≥48 dp on that tag plus survival of `ProgressBarRangeInfo`/
  `SetProgress` (Decision 64)

### Status
Verified: TouchTargetAndFocusTest 5/5 — all measured targets meet ≥48 dp under touch
bounds. The 40 dp inner-content figure is a Material 3 library characteristic, not a
defect in this app; the inner node itself is not padable from outside M3.

### Risk Level
**LOW** — real user touch targets are ≥48 dp (verified); only the inner-node measurement
value differs from the reserved minimum.

---

## 20. Instrumentation Cannot Force-Stop the App (Cold-Start Testing Constraint, Phase 11)

### Limitation
Compose/instrumentation tests run **in the app's own process**. `adb shell am force-stop
com.pahebatcher.app` would kill the instrumentation along with the app, so tests cannot
exercise a true process-kill + cold-start sequence from inside a run.

### Affected Features
- Verification of cold-start behaviors: first-frame theme (dark default / light-persisted
  settles to light), reconciliation-on-launch paths, and any "after process death"
  UI assertion that would require a force-stop inside a test method

### Android Workaround
- First-frame theme behavior is asserted via **screenshot pixel-luminance sampling** on a
  fresh activity launch (Decision 64), not via force-stop relaunch
- True process-death recovery for downloads is verified by the Phase 10 operator-driven
  protocol (seed → operator force-stop → relaunch → reconciler evidence in logcat), run
  **outside** an active instrumentation session
- Never `am force-stop` while instrumentation is running (would abort the run)

### Risk Level
**LOW** — behaviors are covered by the alternative techniques above; the constraint only
limits how "cold start" can be simulated inside a single test method.

---

## 21. Search IME Submit/Debounce Timing Race (Phase 11 Gate Failure) — RESOLVED

### Limitation *(historical — the failures below are recorded for the record; the item is closed, see Resolution)*
`SearchImeTest#imeSearchActionExecutesTheSameSubmissionPathImmediately` failed
**deterministically on the test emulator** on 2026-09-25 — 5 consecutive first-attempt
failures across the clean re-baseline, the fresh @tester gate, and post-verdict
re-verification (never observed green that day):
- 3× `AssertionError: IME submit must execute immediately (elapsed 364–379ms vs 350ms debounce)`
  (`SearchImeTest.kt:97`)
- 2× `AssertionError: pending debounce must be cancelled by the IME submit (one request total)
  expected:<1> but was:<2>` (`SearchImeTest.kt:103`)

A later variant hit the typed-query test: `typedQueryExecutesThroughTheDebounceWithoutAnySubmission`
measured `elapsed 344ms < 350ms` (`SearchImeTest.kt:195-199`). The IME Search action did not
observably beat the 350 ms typing debounce in the test environment.

### Root cause *(established by the bounded fix cycles — both were instrumentation races, not production defects)*
1. **IME path**: the harness's `performImeAction` dispatch latency (internal `runOnIdle` →
   semantics action on the main looper) can drift past 350 ms on a loaded emulator, letting
   the pending `delay(DEBOUNCE_MS)` job fire first (or both fire → request count 2).
2. **Typed-query path**: `typedAt` is captured after `performTextInput()` returns while the
   debounce coroutine can start a few ms earlier during text commit, so the measured elapsed
   time legitimately lands a few ms under the 350 ms boundary.

### Resolution
- **Phase 11G**: injectable `HomeViewModel` constructor debounce seam
  (`debounceMs: Long = DEBOUNCE_MS`, `delay(debounceMs)`) with the IME timing test injecting
  the test-only `TEST_DEBOUNCE_MS = 10_000L` (declared only in `SearchImeTest.kt`).
- **Phase 11I**: the typed-query timing test uses the same test-only seam and asserts its
  floor clear of the boundary; no 350 ms wall-clock boundary is asserted in emulator tests
  (the exact clock contract lives in `HomeViewModelTest`'s fake-clock tests).
- **Production unchanged**: `HomeViewModel.DEBOUNCE_MS = 350L`; the factory uses the
  production default (DECISIONS 68).
- **Verification**: typed-query test 10/10 consecutive first-attempt PASS; SearchImeTest 4/4;
  HomeViewModelTest 8/8; SearchImeTest 4/4 again inside the final Phase 11K gate.

### Affected Features
- Phase 11 §36-10 search IME gate item — **PASSED in the final gate** (`TESTER VERDICT: PASS`,
  Phase 11K); Phase 11 completion status: **COMPLETE**.

### Status
- **RESOLVED (2026-09-25)** — closed by the bounded test-only fixes of Phases 11G/11I and
  confirmed by the final independent Phase 11K gate (DECISIONS 68–69). The earlier
  "root cause NOT isolated" state recorded under Decision 67 is historical.
- Not attributable to the Decision 66 Player fix (the search route never composes
  `PlayerScreen`); the production submission path (`KeyboardActions(onSearch)` →
  `submitSearch` → cancel + `executeSearch`) was always correct by code reading.

### Risk Level
**CLOSED** — no user-visible defect was identified; worst case was a duplicate search
request or an IME submit behaving like a debounced submit in the test harness. The
production 350 ms debounce behavior was never altered.

---

## 22. Player Placement Guard Heal-Window Residual (Phase 11)

### Limitation
The Decision 66 `remeasureOn` guard on the Player root Box **repairs** an anomalous
zero-replacement placement pass (children left `isPlaced == false`, skipping draw and
hit-test) within ≤ one VM state tick (`POSITION_UI_TICK_MS = 500` ms while playing)
instead of **preventing** that pass; the exact trigger pass was never pinned. A tap
landing inside that ≤500 ms window could theoretically still miss the Back button (the
pre-fix failure mode). The guard is also inactive when `uiState` stops changing entirely
(e.g. playback ENDED) — by design, no polling.

### Status
Empirically 40+ consecutive green executions of
`PlayerLifecycleTest#poppingThePlayerEntryReleasesItsExoPlayer` after the fix,
including 3 first-attempt full-class runs inside the @tester gate, with no recurrence.
The residual is theoretical and unobserved post-fix.

### Risk Level
**LOW** — state-driven self-repair with no unbounded behavior; failure window is a
sub-second race on a self-healing invariant.

---

## 23. Resolved-Stream Success Path Not Exercisable End-to-End on Device (Phase 11.5E-3) — RESOLVED IN PHASE 11.5E-4

### Limitation (as it stood in Phase 11.5E-3)
Phase 11.5E-3 renders three real resolution outcomes (idle / resolving / resolved /
failed) backed by production `StreamResolverImpl`. On the attached Android 16 device
(`REDACTED-SERIAL`) every real resolution terminated in the **failed** branch: the play page was
fetched and parsed successfully, a genuine Kwik link was extracted, and `KwikResolver`
then exhausted its TLD rotation against Cloudflare WAF block pages
(`<title>Attention Required! | Cloudflare</title>`, "Sorry, you have been blocked"),
which is section **2B**. A second observed outcome was `No Kwik link found on episode
page` for a play page whose fetch returned no Kwik iframe. Neither outcome could produce
a resolved stream, so the **resolved** branch was never reached on real network.

### Status (Phase 11.5E-4 — on device, production app, real network)
The success path was reached, and it continued through the whole download pipeline:

| Step | Device evidence |
| --- | --- |
| Resolve requested | `StreamResolver: Resolving stream: https://animepahe.com/play/… (quality=1080, audio=eng)` |
| Play page | `CFSession: Captured HTML in onPageFinished: 27243 chars` |
| Kwik fetch | `KwikResolver: Fetching Kwik page: https://kwik.cx/e/IuUhxsI0LSgY` + `Kwik request referrer present: true` + `Kwik page fetch success=true, htmlLen=22274` + `Cloudflare challenge completed (cycle 1)` |
| M3U8 | `M3U8 extraction result: https://vault-13.owocdn.top/…/uwu.m3u8` |
| Resolved branch | `Stream resolved: quality=1080, audio=eng` → panel rendered the real URL, `eng · ABdex`, and the `Download` control |
| Hand-off | `DownloadRepository: Download session … created` → `Enqueued episode …_1_eng for resolution` → `Enqueued Worker for …_1_eng` → `Resolution complete …, Worker enqueued` |
| Worker → engine | `DownloadWorker: Starting download work for …_1_eng` → `EpisodeDownloader: Starting download: …/uwu.m3u8` |
| HLS | `AesKeyCache: Cached key: …/mon.key (16 bytes)` → `Playlist: 146 segments, 0 completed, 146 pending` |
| Segments | `SegmentFileStore: Wrote segment N` … → `Download complete: success=true, total=146, completed=146, failed=0`; on disk `000001.ts … 00146.ts`, `tmp=0` |
| Resume | second pass: `Playlist: 146 segments, 146 completed, 0 pending` → **zero** re-downloads |
| Persisted state | exactly one `download_sessions` row and one `episode_downloads` row, with `m3u8Url`, the device's real WebView `userAgent` and `referer = https://kwik.cx/e/IuUhxsI0LSgY` |

### Risk Level
**RESOLVED (Phase 11.5E-4)** — the failure that remained on this path (MP4 assembly, an
independent pre-existing defect documented in §26) was itself **resolved in Phase
11.5E-5**, so the single-episode pipeline now completes through a playable MP4. No production
code fabricated a resolved state, a URL, a segment or progress at any point.

---

## 24. Download Retry / Zombie Rows / Expired-Stream Recovery Are Not Wired (Found in Phase 11.5E-4; Finding 1 RESOLVED in Phase 11.5E-7)

### Limitation
Phase 11.5E-4 is the first phase that can actually create a download row from
production, which makes three pre-existing Phase 10 behaviors reachable. All three are
real and reproducible; none was fixed here because each is a **queue/retry redesign**
explicitly excluded from this slice. *(11.5E-7 update: finding 1 below is now
**RESOLVED**; findings 2 and 3 remain open — DECISIONS 75.)*

1. **Retry from the Downloads screen clears the stream info before it re-enqueues the
   worker.** `DownloadRepository.reEnqueue()` does `updateState(QUEUED)` +
   `incrementRetryCount` + `clearStreamInfo()` + `enqueueWorker()`, while
   `DownloadWorker` is forbidden to call `StreamResolver`/`KwikResolver` and treats a
   missing `m3u8Url` as `updateError("No stream info available")` → `Result.failure()`.
   The retry path therefore fails deterministically, with no network involved.
   Python's equivalent re-resolves on every attempt (`downloader.py:233-275` calls
   `extract_stream()` fresh).
   → **RESOLVED in Phase 11.5E-7** (DECISIONS 75). `reEnqueue()` now claims the
   terminal row atomically (`beginRetry`: clears the stale `m3u8Url`/`userAgent`/
   `referer`/`errorMessage`/`outputUri`, keeps `playUrl`, identity, `segmentDir`,
   progress and **keeps and increments `retryCount`** — *wording corrected 2026-09-28,
   reviewer L-4: `EpisodeDownloadDao.beginRetry` does `retryCount = retryCount + 1`*),
   refuses rows whose id does not match their own columns
   and rows with no wired resolver **before any mutation**, resolves a FRESH stream
   through the production `StreamResolver` (single-flight, foreground session),
   persists it via a CAS-guarded `resolveComplete`, and only then enqueues the Worker;
   an abort mid-resolution restores the original terminal state. The legacy
   `clearStreamInfo() + enqueueWorker()` path is gone, so `"No stream info available"`
   can no longer originate from Retry. Device-verified: CANCELLED `7/146` → Retry →
   logcat `Re-enqueued … (from CANCELLED) via fresh re-resolution` → `downloading` →
   `completed · 146/146 · 100% · retries: 1` + MP4 export, zero occurrences of the
   old error (`RetryReResolutionTest` 15/15 + `StateTransitionTest`/`DownloadRepositoryTest`
   additions).
2. **A row interrupted between `enqueue` and `resolveComplete` has no recovery
   control.** `DownloadStateReconciler` maps `RESOLVING → QUEUED` at startup and
   reports `QUEUED` with no `m3u8Url` as "awaiting foreground resolution — no action",
   while `DownloadRowMapper` offers only Cancel for both states — no Retry.
   *(Escape-route correction, 11.5E-6 — the previous "The only escape is Cancel →
   Retry" was inaccurate.)* Two escapes exist: **(a)** re-submitting the episode from
   Anime Details, where `queueResolvedEpisode → resolveComplete` repopulates the
   stream-info-less row — on-device verified in 11.5E-6
   (`QueueResolvedEpisodeGuardTest.resubmission_recovers_a_queued_row_without_stream_info`)
   and deliberately kept outside the 11.5E-6 suppression guard; or **(b)** Cancel →
   Retry from Downloads — **works since 11.5E-7**: Cancel claims the row and Retry
   now re-resolves a fresh stream end-to-end (finding 1 resolved), so a
   stream-info-less `QUEUED` row is recoverable as `Cancel → Retry` instead of
   dead-ending. **Slice B (DECISIONS 146) makes escape (a) honest:** that
   re-submission now returns `EnqueueOutcome.Accepted` — it really does start
   the download — instead of being indistinguishable from a refusal, so the
   Anime Details screen can no longer tell the user "already in the queue"
   about a row that nothing will ever run. Pinned by
   `EnqueueOutcomeMappingTest.a_queued_row_without_a_stream_is_accepted_because_this_call_makes_it_download`.
   The finding itself stays open: the recovery still requires the user to
   re-submit the episode by hand, and Downloads offers Cancel only for this
   state.
3. **An expired stream URL is terminal.** `EpisodeDownloader` classifies HTTP 403/404
   as permanent (Phase 10 arch v10 §3) and the ViewModel performs a single resolution
   attempt with no backoff, whereas Python retries resolution
   (`auto_retry + 1`, `1.5 * 2**r`, permanent errors limited to "No Kwik link"/"No
   anime UUID"). Stream URLs are expiring credentials (AGENTS §10), so an episode
   queued behind `maxParallel` can outlive its playlist URL.
   *(11.5E-7 note: each user Retry now performs exactly **one** fresh resolution
   attempt through the production resolver before the Worker is enqueued; Python's
   automatic `auto_retry` backoff re-resolution loop is still not ported — that
   remains this finding's §24.3 scope, deliberately deferred.)*

### Affected Features
- `DownloadsScreen` Retry (rows in `FAILED`/`CANCELLED`)
- Startup reconciliation of interrupted (`RESOLVING`/`QUEUED`) rows
- Any download whose playlist URL expires before it starts

### Android-Native Alternative (implemented in Phase 11.5E-7)
Re-resolution is only possible against the shared application-scoped WebView session (the
DownloadWorker must not create one; DECISIONS 91 lets the background check drive that same session), so the Android shape of Python's retry is: on Retry, mark the row
`RESOLVING` from its persisted `playUrl`, hand it back to the existing resolution path,
and only enqueue the `DownloadWorker` from `resolveComplete`. That reuses the exact
contract this phase wired (`enqueue → resolveComplete`) and adds no second engine.
**Implemented exactly as described in Phase 11.5E-7** (DECISIONS 75) — production
`AppContainer` wires the real `StreamResolver` into `DownloadRepository` lazily; no
second pipeline, no queue redesign.

### Status
Documented, not implemented in 11.5E-4; **re-audited in 11.5E-6**; **finding 1
RESOLVED in Phase 11.5E-7** — the retry path above, verified on JVM (`DownloadRepositoryTest`,
`StateTransitionTest`), on instrumentation (`RetryReResolutionTest` 15/15, `ResumeTest`,
`DuplicateEnqueueTest`) and on the physical device (`REDACTED-SERIAL`: CANCELLED `7/146 · 4%`
→ Retry → `Re-enqueued … via fresh re-resolution` → `completed · 146/146 · 100% ·
retries: 1`, zero `No stream info available` occurrences). Finding 2 keeps its
Downloads-screen control gap (a stream-less `QUEUED`/`RESOLVING` row still offers
Cancel only), but both of its escapes — details re-submission (11.5E-6, device-verified)
and `Cancel → Retry` (11.5E-7, device-verified) — now work. Finding 3 remains open with
§24.3 (single attempt per Retry, no backoff loop) — an intentional documented Python/Android difference, preserved deliberately and non-blocking for SPEC satisfaction (DECISIONS 140). Prior phases' verification for the
open findings was code inspection of `DownloadRepository.reEnqueue()`, `DownloadWorker`,
`DownloadStateReconciler`, `DownloadRowMapper` and `EpisodeDownloader`.

### Risk Level
**MEDIUM** — the primary single-episode happy path (details → resolve → queue →
worker → HLS) does not depend on any of the three; they surface on failure recovery.

### 11.5E-6 Re-Audit Additions (recorded, not fixed)

Additional findings from the 11.5E-6 three-audit reconciliation that do not fit the
three numbered findings above. All are deferred deliberately — each belongs to the
queue/retry/settings/UI slices this phase must not touch (DECISIONS 74).

| # | Finding | Evidence | Disposition |
|---|---------|----------|-------------|
| R1 | No per-host concurrency cap - RESOLVED 2026-10-13: Python shares one session with `TCPConnector(limit_per_host=24, limit=100)`; Android now builds ONE app-scoped host-keyed limiter shared by every Worker, so concurrent episodes share a single ceiling per host instead of stacking unbounded requests against it | `http.py:TCPConnector` vs `HlsHostConcurrencyLimiter.kt:69` (wired at `DownloadWorkerFactory.kt:76`, `AppContainer.kt:353`); recorded in PARITY §5.4 | RESOLVED 2026-10-13 - no longer deferred; a host-keyed Semaphore replaced the redesign this row previously deferred |
| R2 | Downloads-screen action failures are silent: `cancel()`/`retry()` catch every exception and log it — the user gets no error feedback when an action does nothing | `DownloadsViewModel.kt` `handleActionFailure` (pre-fix: `Log.e` only) | RESOLVED 2026-10-07 (final audit H5): `DownloadsViewModel.actionError` + a dismissible `DownloadsScreen` banner surface the failure (DECISIONS 139) |
| R3 | Startup reconciliation of an interrupted `RESOLVING` row unconditionally clears its persisted stream info, discarding a resolution that may have completed moments before the process died | `DownloadStateReconciler.kt:283-288` (`clearStreamInfo`) | **Mitigated by 11.5E-7** — the cleared stream is now recoverable end-to-end (Cancel → Retry re-resolves from the persisted `playUrl`; `abort_restores_terminal_state_when_reconciler_flips_row_mid_resolution` covers the collision), the reconciler's clearing behavior itself unchanged; not worth a redesign on its own |
| R4 | Deferred-episode refill only runs from a completing worker's `finally`; a failed trigger is swallowed (`Log.e`) and nothing else refills the queue, so under-maxParallel rows can idle until the next worker finishes | `DownloadWorker.kt:194-200,223-227` | Deferred (queue redesign) |
| R5 | Reboot continuation has never been exercised on a real device (WorkManager's default post-BOOT rescheduling is expected to cover it, but expectation ≠ verification) | — | Manual verification gate for a later phase (PARITY §9.4); classified a non-blocking verification limitation, not a SPEC blocker (DECISIONS 140) |
| R6 | A suppressed re-submission of a COMPLETED row still reports "In the download queue" on the details screen (the ViewModel reports hand-off success; the row actually stays completed) | `queueResolvedEpisode` lost-creation-claim branch; observed on device, 11.5E-6 | **RESOLVED 2026-10-12 (DECISIONS 148.7)** - that branch now derives its verdict from the row it re-reads (`outcomeOf(dao.getById(episodeId))`) instead of answering `AlreadyActive` unconditionally, so a completed row reports `AlreadyDownloaded` and a row that is gone reports `Refused`; pinned on device by `EnqueueOutcomeMappingTest.a_lost_creation_claim_against_a_completed_row_reports_that_row_state` and `EnqueueOutcomeMappingTest.a_submission_whose_row_vanished_twice_reports_what_is_actually_there` (13/0) |

---

## 25. WebView/Cloudflare Session Findings Deferred by Phase 11.5E-4

### Limitation
A read-only reviewer audit of this phase reported the following session-layer defects.
They are real, they are reproducible by inspection, and each one was classified as either
(a) a small fix that is **not parity** (Python has no equivalent behavior to match), or
(c) a design question. None of them blocks the single-episode happy path this phase
wires, and none was silently "fixed" in passing — they are recorded so the next slice
can pick them up deliberately.

| # | Finding | Evidence | Class |
|---|---------|----------|-------|
| W6 | `performPageFetch` never observes HTTP status: `success = htmlContent.isNotEmpty()`, `statusCode = 0`, no `onReceivedHttpError` override — a 403 WAF page is reported as success | `AndroidCloudflareSession` (page fetch result construction) | (a), not parity — would improve honesty of the evidence channel |
| W7 | The temporary capture `webViewClient` is restored on the normal paths but **not** in the `catch` path of `performPageFetch` — after one failed fetch the shared session WebView keeps the capture client | `AndroidCloudflareSession.performPageFetch` | (a), leak, not parity |
| W8 | Every Kwik fetch waits for Cloudflare on `animepahe.com`/`.pw` cookies even though the request is for another host, so each rotation attempt pays ≥5 s (≤15 s) | `CF_CHECK_DELAY_MS` / `MAX_CF_WAIT_CYCLES` loop | (c) — whether a Kwik fetch should wait at all is a decision |
| W9 | The HTML-capture path decodes the JSON callback with a naive sequential `replace("\\\\","\\")` decoder instead of the existing `decodeJsStringLiteral`, which can corrupt captured script text | `AndroidCloudflareSession` (capture decoding) | (a) — probe-demonstrated corruption; impact on live pages not observed (the captured Kwik page resolves via the JsPacker path) |
| W14 | Blank `userAgent`/`referer` would be sent as empty header values (OkHttp keeps empty-valued headers) if a row were ever persisted with an empty referer | `OkHttpHlsClient` request builder | (a) latent hazard; not observed — `resolveComplete` writes resolver-produced values |

> **Label note (2026-09-28, Phase 11.5F):** this table originally used the labels
> `F6/F7/F8/F9/F14`, which **collided** with the unrelated `F5`–`F8` register defined at
> `DECISIONS.md` (11.4 audit: assembly-retry / max_parallel / concurrency / AppConfig keys).
> The §25 rows are renamed `W6/W7/W8/W9/W14` ("W" = WebView/session audit of 11.5E-4);
> the DECISIONS/`PARITY.md:279` `F5`–`F8` labels are unchanged.
> `F1`–`F4` were never defined anywhere in the repo and are formally **retired**
> (DECISIONS 77) — no definitions exist to publish.

### Affected Features
- Evidence quality of on-device resolution diagnostics (W6)
- Long-lived WebView session correctness after a failed fetch (W7)
- Resolution latency across Kwik TLD rotation (W8)

### Android-Native Alternative (not yet implemented)
Record HTTP status in the page result (status codes are not secrets), restore the
WebView client in a `finally`, decode captured HTML with the JSON decoder that already
exists, and skip the Cloudflare wait for non-AnimePahe hosts.

### Status
Documented, not implemented in 11.5E-4. Verified by code inspection; W9 additionally by
a decoder round-trip probe (`\n` and `A` decodings differ from `json.loads`).

### Risk Level
**LOW-MEDIUM** — none of them fabricates data or blocks the wired pipeline.

---

| Category | Risk Level | Mitigation | Complexity |
|----------|------------|------------|------------|
| Cloudflare bypass | HIGH | WebView session | High |
| TLS fingerprinting | MEDIUM | WebView fallback | Medium |
| Kwik domain infrastructure | CLOSED | **RESOLVED** — the domains were reachable all along; the missing `Referer` header was the blocker (§2B, Decision 72) — verified on device in Phase 11.5E-4 | Low |
| FFmpeg muxing | LOW | FFmpegKit (build+load validated) | Medium |
| APK size impact | MEDIUM | +66.5 MB increase (FFmpegKit) | Low |
| Background execution | MEDIUM | Foreground Service | Medium |
| Storage access | LOW | SAF + MediaStore | Low |
| Process death | LOW | Room + WorkManager | Low |
| Memory constraints | LOW | Streaming patterns | Low |
| Notification system | LOW | Standard Android (state architecture tested in Phase 11; runtime prompt → Phase 13 — see §4, §8) | Low |
| WebView limitations | MEDIUM | CookieManager | Medium |
| Concurrency model | LOW | Kotlin coroutines | Low |
| File system | LOW | Android APIs | Low |
| Network differences | LOW | Retry + backoff | Low |
| Test environment (package vanishing) | LOW | Reinstall before/after suite runs | Low |
| No production UI entry point (Phases 1–10) | Informational | Deferred to Phase 13 (§14); **entry point since shipped — single-episode in Phase 11.5E-3/E-4, range/all-episodes in Phase 12C (PARITY §13)** | — |
| `STATE_ASSEMBLING` never written in production | LOW | Retained; **RESOLVED 2026-10-07** - production now writes ASSEMBLING (§15) | Low |
| Development ValidationScreen in `main/` source set (debug-only) | LOW (release readiness) | Gated by `BuildConfig.DEBUG` — release builds cannot reach it; debug validator stays diagnostic-only (§16, Decisions 51/70) | Low |
| Poster source unverified (external) | LOW | Four-state placeholder contract; never invent URLs (§17) | Low |
| No MediaStore↔Room cross-store atomicity | LOW | Idempotent export ordering + reconciliation — implemented & verified (§18) | Low |
| M3 40 dp inner content vs 48 dp reserved minimum | LOW | Touch-bounds assertions + seek-slider wrapper anchor (§19) | Low |
| Instrumentation cannot force-stop (cold-start testing) | LOW | Screenshot-luma + operator-driven process-death protocol (§20) | Low |
| Search IME submit/debounce timing race (Phase 11 gate failure) | CLOSED | **RESOLVED** — test-only debounce seam (Phases 11G/11I); production 350 ms unchanged; final Phase 11K gate PASS (§21, DECISIONS 68) | Low |
| Player placement guard heal-window residual | LOW | State-driven repair ≤500 ms; 40+ green executions post-fix (§22, Decision 66) | Low |
| Resolved-stream success path not exercisable end-to-end on device | CLOSED | **RESOLVED** — production app reached the resolved branch and the full pipeline on device in Phase 11.5E-4 (Kwik page fetched with Referer, m3u8 extracted, 146/146 segments); MP4 assembly then completed in Phase 11.5E-5 (§26, Decisions 71/72/73) | Low |
| Retry / zombie rows / expired-stream recovery not wired | MEDIUM | **Finding 1 RESOLVED (Phase 11.5E-7)** — Downloads Retry re-resolves a fresh stream end-to-end (device-verified, DECISIONS 75); zombie-row escapes now both work and expired-stream recovery (findings 2–3, R-list) stays documented (§24, Decisions 72/75) | Medium |
| WebView/Cloudflare session findings deferred | LOW | Wrong-host `waitForCloudflareCompletion` and 3 related findings deferred to a later session slice (§25) | Low |
| MP4 assembly fails on real playlists (segment index base mismatch) | CLOSED | **RESOLVED (Phase 11.5E-5)** — `AssemblyConfig.segmentIndices` boundary translation (playlist indices → assembly; file names untouched); production APK assembled 146/146 segments into a faststart MP4 with `avc1`+`mp4a` on device and exported it to MediaStore (§26, Decision 73) | Low |
| Unguarded Worker/Repository state writes outside the 11.5E-7 CAS discipline | CLOSED | **FIXED (Phase 11.5F, 2026-09-28)** — Worker entry CAS claim + every failure/completion/cancellation write guarded, `reEnqueue` reordered before any mutation, non-CE escapes persist a sanitized guarded FAILED; verified JVM 525/0/0 + instrumentation **224/1** (only the known `ForegroundWorkerTest` baseline), DECISIONS 77; the `setForeground` silent-downgrade half of §27.3 remains OPEN by design (§4). **Residual found and fixed by the 2026-10-07 final audit (M1):** `DownloadRepository.cancel()` claimed terminal rows outside the CAS discipline - now `cancelIfNotTerminal(episodeId, timestamp)` (DECISIONS 139) | Low |

---

## 26. MP4 Assembly Fails on Real Playlists — Segment File Index Base Mismatch (Found in Phase 11.5E-4 — RESOLVED in Phase 11.5E-5)

### Resolution (Phase 11.5E-5, verified on device)

Fixed **at the playlist↔assembly boundary** — option (b) below — while preserving the
Phase 8 segment identity:

- `EpisodeAssembler.AssemblyConfig` gained `segmentIndices: List<Int>` (the files FFmpeg
  must consume, in playlist order); `EpisodeDownloader` passes
  `playlist.segments.map { it.index }`.
- All three 0-based sites now consume that list: the pre-assembly missing check,
  `ConcatFileGenerator.generate(...)` (concat demuxer list), and the pipe-fallback
  concatenation loop. Persisted segment names were never touched —
  `000001.ts … 00146.ts` remain valid (Phase 8 `doneIndices()`/resume/duplicate
  prevention unchanged).
- A config-integrity guard rejects `segmentIndices.size != segmentCount`
  (`Assembly config mismatch: …`) before FFmpeg. Check order in `assemble()`:
  output-exists skip → config guard → missing check (`Missing N segments before
  assembly` error text unchanged) → ABI gate → concat/execute.
- Option (a) (switch files to enumerate position) was rejected: existing directories keyed
  `000001..00146` would then be read off-by-one into a silent corrupt resume.
- **No migration needed**: existing directories are already media-sequence keyed, so the
  translated indices match them exactly (reviewer F-MIG concern addressed).

Verification: JVM suites for media-sequence indices, playlist ordering, missing detection,
concat-list contents/order/quoting, empty list, config mismatch, output-exists skip and
output naming (**523/523** — *count corrected 2026-09-28, reviewer L-1: the previous
"522/522, baseline 510 + 12 new" omitted the deterministic cancellation test named in the
next clause; DECISIONS 73 records 510 → +12 → **523**, and DECISIONS 74/75 record the
later baseline of **525***), plus a deterministic cancellation test
at the download→assembly boundary (segments survive, `wasCancelled` reported). Device
instrumentation `FfmpegAssemblyValidDataTest#testMediaSequenceIndexedAssembly` (positional
config → honest `Missing 1 segments before assembly`; `[1,2,3]` → real FFmpeg faststart
MP4). **Production APK on device**: `Starting MP4 assembly for 146 segments` →
`Assembly succeeded via concat demuxer` → `Ep_001_-_Spy_x_Family_Ep._1_animepahe.mp4`
(146 705 229 B) → MediaStore `content://media/external/video/media/116` (`is_pending=0`,
1920×1080, 1458803 ms) → Downloads UI `completed · 146/146 · 100%`; MP4 parsed as
`ftyp` first, `moov@32` before `mdat@1124176` (faststart), tracks `avc1`+`mp4a`,
`mvhd` 1458.803 s. Recorded in DECISIONS 73.

### Limitation (historical — the state before 11.5E-5)
With the Kwik blocker removed, the production pipeline runs end-to-end on the device:
**146/146 segments** of a real AnimePahe/Kwik stream are fetched and written atomically
(`tmp=0`), the resume path skips them, and the Downloads row reads `146/146 segments · 100%`
— but every run then ends `FAILED` with
`Assembly failed: Missing 1 segments before assembly`.

Root cause, established against the Python reference:

| | Python | Android |
| --- | --- | --- |
| Segment file index | `downloader.py:77` — `[(i, s) for i, s in enumerate(segments) if i not in done_set]`, i.e. the **enumerate position** (0-based) | `HlsPlaylistParser` assigns `HlsSegment.index = seqNum` (`#EXT-X-MEDIA-SEQUENCE`), and `EpisodeDownloader` writes `segmentStore.writeSeg(segment.index, …)` |
| Store naming | `store.py:32` — `f"{idx:06d}.ts"` | `SegmentFileStore` — `"%06d.ts".format(idx)` (identical) |
| Pre-assembly check | `store.py:47` — `[i for i in range(n_segments) if not self.seg_path(i).exists()]` | `FfmpegEpisodeAssembler.kt:88` — `(0 until config.segmentCount).filter { !segmentStore.hasSeg(it) }` (identical) |

The store and the pre-check agree with each other and with Python (both 0-based). What
differs is that Android's file index is the **playlist's media sequence**, and the Kwik
CDN playlist starts at `#EXT-X-MEDIA-SEQUENCE:1`. So the directory holds
`000001.ts … 00146.ts` while the check looks for `000000.ts` → exactly **1** missing
segment → `Permanent error, not retrying`.

Device evidence (Phase 11.5E-4, production APK):
`Playlist: 146 segments, 0 completed, 146 pending` →
`Download complete: success=true, total=146, completed=146, failed=0` →
`Starting MP4 assembly for 146 segments` →
`E/FfmpegEpisodeAssembler: Cannot assemble: 1 segments missing` →
on disk `entries=146 ts=146 tmp=0` (`000001.ts … 00146.ts`).

Nothing is corrupted: all segment data is present and valid, and the failure is honest.

### Why it was NOT fixed in Phase 11.5E-4
- MP4/FFmpeg/`FfmpegEpisodeAssembler` is an **explicit exclusion** for this slice.
- The alternative fix — storing by enumerate position — changes Phase 8 segment identity
  (`doneIndices()`, resume, progress accounting), which this slice is explicitly required
  to **preserve**; existing segments named `000001…` would also need a migration story.
- The phase's deliverable (a resolved stream producing `.ts` segment files) is fully
  verified, so this is reported rather than guessed at.

### Affected features
- MP4 assembly for every real playlist whose `EXT-X-MEDIA-SEQUENCE != 0`
- `DownloadWorker` → `FAILED` terminal state after a 100% successful segment download
- Downstream: no output file, no MediaStore export, no playback for that episode

### Android-native alternative (for a later slice)
Pick one convention at the playlist↔store boundary and cover it with a device test:
(a) map playlist **position** → file index in `EpisodeDownloader` (Python-equivalent,
keeping `HlsSegment.index`/media sequence for log labels and any IV that derives from
it), or (b) teach `FfmpegEpisodeAssembler`/`ConcatFileGenerator` the playlist's base
index. Either way verify: fresh download, resume after process kill, and a 1-based
playlist from the real CDN.

### Risk Level
**MEDIUM** — download and resume work; the episode is not produced. Reproducible on
100% of real streams.

---

## 27. Unguarded Worker/Repository State Writes — Residual Outside the 11.5E-7 CAS Discipline (Found 2026-09-28 — 27.1/27.2 FIXED in Phase 11.5F, 27.3 re-scoped)

### Limitation
Phase 11.5E-7 made the **retry and reconciler** write paths CAS-guarded
(`EpisodeDownloadDao.updateStateForResume` / `beginRetry` / `updateErrorIfState`, so "a
cancellation is never converted into a failure"). Three write sites were **left outside
that discipline** and were found by the read-only reconciliation review of 2026-09-28.
They are documented here rather than silently fixed: each is a behavior change needing
its own gate and tests — *which is exactly what Phase 11.5F then supplied* (see Status).
*(This section's findings text below is retained as written on 2026-09-28 - the findings
describe the pre-fix code; line pointers displaced by later edits were re-pointed 2026-10-05.)*

> **SUPERSEDED 2026-10-13 (final verification gate) — PARTIALLY RESOLVED, NOT FULLY
> CLOSED.** The headline defect below, the **unguarded entry transition**, is **FIXED**:
> `DownloadWorker.kt:230-231` now says so in source — "11.5E item (e), reviewer M-5
> (LIMITATIONS 27.1): the entry transition is an atomic CAS CLAIM, not a blind
> `updateState(DOWNLOADING)`" — and `DownloadWorker.kt:51` states that entering the Worker
> is an atomic CAS claim (`claimEntryState`); both comments cite this very limitation. A
> grep of `DownloadWorker.kt` finds **no `updateState(..., STATE_DOWNLOADING)` call at
> all**, so the specific write this entry complains about no longer exists. The two line
> citations in the text below were **stale**: `DownloadWorker.kt:106` now resolves to a
> KDoc comment about auto-resume backoff and `:98` to a blank line (`countActive()`
> actually sits at `:223` and `:975`). **What was NOT done in this gate:** the
> escape-hatch sub-claims below — the `:135` `updateError` write, the `catch (e:
> Exception)` at `:122` swallowing `CancellationException`, the `finally` at `:73-81`
> writing no terminal state, the reconciler re-enqueue consequence, and the "same
> unguarded pattern" at `:249`/`:270`/`:275` — were **not re-audited**. They are retained
> as-recorded and are **not asserted either way** (their line pointers are as stale as
> `:106`/`:98` and were not re-pointed here). Read this entry as **partially resolved**:
> the fixed headline must not be used to claim the sub-claims are closed. The historical
> findings text below is preserved as written (one disclosed exception, 2026-10-19 Phase A2:
> the `:244-264` handler pointer (then `DownloadWorker.kt`'s) was re-aimed to `:285-301` when its old
> line went blank — the change is annotated in place; every other pointer here stays
> as-recorded).

**27.1 — `DownloadWorker` entry transition is unguarded (reviewer M-5).**
`DownloadWorker.kt:106` calls `dao.updateState(episodeId, STATE_DOWNLOADING)` with no
`expectedState` predicate, after the `countActive()` check at `:98`. Race: the user taps
Cancel → `DownloadRepository.cancel()` writes `CANCELLED` (`DownloadRepository.kt:856`)
→ a Worker already past the gate overwrites it with `DOWNLOADING`, and the subsequent
`cancelUniqueWork` only cancels the coroutine. In the common path the handler at
`DownloadWorker.kt:285-301` rewrites `CANCELLED` and it self-heals, but two escape
hatches do not: `:135` (`dao.updateError(... "No stream info available")`) and any Room
write throwing through `:122`'s `catch (e: Exception)` (which also swallows
`CancellationException` — see §4). *(the `:244-264` handler pointer was re-aimed to
`:285-301` on 2026-10-19, Phase A2, when the old line went blank — `:285-301` is the
NonCancellable cancellation-cleanup CAS that actually rewrites the row to `CANCELLED`;
the other line pointers in this paragraph remain as-recorded and stale per the
disclaimer above.)* If the exception escapes `runWork`, the `finally` at
`:73-81` runs and **no terminal state is written**, leaving a `DOWNLOADING` row with no
Worker — which the startup reconciler treats as stranded and **re-enqueues a download the
user cancelled**, the exact inverse of the 11.5E-7 invariant.
The same unguarded pattern is at `:249`, `:270`, `:275`.

**27.2 — `reEnqueue` mutates before its own identity check (reviewer M-6).**
`DownloadRepository.kt:735-739` executes `dao.updateError(episodeId, "Missing play page
URL")` (unguarded — SQL writes `state = 'FAILED'` with no predicate) **before** the
identity round-trip at `:514-526` and before the `beginRetry` claim at `:534`. This
contradicts the function's own KDoc contract #2 ("Identity must round-trip **BEFORE any
mutation**", `:439`) and `LIMITATIONS`' previous wording ("refuses rows … before any
mutation"). Because eligibility was decided on the snapshot read at `:481`, a concurrent
winner that moved the row out of its terminal state in that window can be forced to
`FAILED` while live — the defect class `updateErrorIfState` exists to prevent.

**27.3 — foreground degradation is silent (reviewer L-8).** Documented under §4
(*"setForeground() failure is swallowed"*). **Re-scoped in Phase 11.5F (reviewer M2)**:
the bullet contained TWO claims. The `CancellationException`-swallowing half is **FIXED**
— `DownloadWorker.executeClaimedWork()` rethrows CE before its generic handler and
`runWork()` persists `CANCELLED` under `NonCancellable` + rethrows (L-8; asserted by
`DownloadWorkerStateGuardTest#cancellation_during_foreground_setup_propagates_and_leaves_no_failed_row`
and `CancellationTest`). The remaining half — a NON-cancellation `setForeground` failure
(`MissingForegroundServiceTypeException`, `SecurityException`, an OEM quirk) downgrading
the run to a background-limited job with **no metric or user-visible signal** — stays
**OPEN** under §4; it is a deliberate continue-anyway design (aborting the download would
be worse), so closing it needs a user-visible warning, not a code guard.

### Affected Features
- Cancel correctness for in-flight rows (Cancel is the only control offered for
  `QUEUED`/`RESOLVING`/`DOWNLOADING`/`ASSEMBLING` rows — `DownloadRowMapper.kt:152,164`)
- The universality of the 11.5E-7 guarantee at `DownloadRepository.kt:659-667`, which
  currently reads broader than the system provides
- Retry of a hand-seeded/legacy row with a blank `playUrl`

### Status
**FIXED in Phase 11.5F (2026-09-28, scope item (e)) — 27.1 and 27.2 closed with code,
tests and device verification; 27.3 re-scoped above (its silent-downgrade half stays open
under §4).** What was implemented (all four candidate fixes above, plus the sites the
review found after them):

- **27.1** — Worker entry is now an atomic CAS claim: `claimEntryState()` accepts
  `{QUEUED, DOWNLOADING, ASSEMBLING}` and flips each with
  `updateStateForResume(expectedState → DOWNLOADING)`; a loser returns `Result.success()`
  with **zero** row mutation. The four Worker `updateError(...)` sites and the
  `wasCancelled → CANCELLED` write now go through `updateErrorIfState(DOWNLOADING)` /
  `updateStateForResume(DOWNLOADING → CANCELLED)`; the completion write is
  `updateCompletedIfState(DOWNLOADING → COMPLETED)` (export skipped on a lost claim); the
  reconciler's "file missing" demotion is `updateErrorIfState(COMPLETED, …)`; and a
  non-CE exception escaping `runWork()` is caught by a generic handler that persists a
  **sanitized** `updateErrorIfState(DOWNLOADING, "Unexpected worker error (…)")` instead
  of stranding the row.
- **27.2** — `reEnqueue()` reordered: session lookup → identity round-trip → `priorState`
  → CAS-guarded blank-`playUrl` write (`updateErrorIfState(expectedState = priorState)`)
  → `beginRetry`. No mutation happens before both round-trips succeed.
- **L-8 (CE half of 27.1/27.3)** — `CancellationException` propagates: terminal cleanup
  under `NonCancellable` + rethrow; never converted into an ordinary failure Result.

Verification: JVM **525/0/0**; full instrumentation on device `REDACTED-SERIAL` **224 tests /
1 failure** (the pre-existing `ForegroundWorkerTest` notification baseline only) with
`DownloadWorkerStateGuardTest` 5/5 and `ReEnqueueIdentityGuardTest` 3/3 green (the new
cases: non-vacuous stale-Worker claim proof, the `DOWNLOADING` process-death entry, and
the concurrent-winner CAS refusal); lint **0 errors / 24 warnings**. Evidence and the
claim-state-set decision: **DECISIONS 77**. *(2026-10-07 final audit: one more write outside this discipline was found - the blind `cancel()` claim - and closed with `cancelIfNotTerminal` (DECISIONS 139).)*

### Risk Level
**LOW** — LOW probability in normal production flow (blank `playUrl` ≈ hand-seeded rows;
the Cancel race needs sub-millisecond timing); every path above is now CAS-guarded and
covered by a behavioral test. Residual: the §27.3 silent `setForeground` downgrade (§4)
remains open by design, and `EpisodeDownloader`'s CE→`wasCancelled` conversion stays as
the pinned contract the Worker's guarded cancellation branch builds on.

---

## 28. MP4 Video-Timestamp Seam Structure Differs From the Python Reference Corpus (Found 2026-09-28; ATTRIBUTED in Phase 11.5G — created by the Android assembly step — **RESOLVED in Phase 11.5H, 2026-09-29**)

### Limitation
Read-only ffprobe analysis (Phase 11.5F, WSL ffprobe 8.0.1; raw outputs in
`.investigation/media-11_5/analysis*.txt`) of the two Android-assembled production MP4s
on `REDACTED-SERIAL` (MediaStore ids 194 = Spy Classroom Ep 1, 1626.775 s and 195 = One Piece
Ep 1, 1509.347166 s; both pulled byte-exact) versus the Python reference corpus
(`<python-ref-3.6.0>\downloads\`, 170 files) found:

| Property | Android id194 | Android id195 | Python reference (Iruma Ep 1 / Black Torch Ep 1 samples) |
|---|---|---|---|
| video frames | 38,761 | 35,965 | 35,965 / 34,166 |
| frames ÷ duration | **1.00614 × nominal 23.976** (mean 0.0419643 s/frame) | **1.00607 × nominal** (mean 0.0419616 s/frame) | **exactly 1.000000** (mean 0.0417083 s/frame) |
| seam discontinuities (sorted PTS deltas off the nominal grid) | 161 | 151 | **0** (all deltas are the 1-tick nominal pair 0.04171/0.04170) |
| nominal-grid "holes" (slots spanned − frames) | 238 | 218 | **0** |
| max/mean grid offset (in frames) | 0.499 / 0.269 | 0.493 / 0.250 | 0.010 / 0.010 (timescale rounding only) |

In plain terms: ~99.6 % of Android video timestamps sit exactly on the 24000/1001 grid,
but at ~160 evenly spaced points (≈ one per HLS segment) there is a non-integer jump of
~2–3 frame periods, so the video timeline is stretched by ≈ 0.6 % (≈ 10 s over 25 min)
relative to its frame count. **All frames are present** (decode-based `nb_read_frames`
equals header `nb_frames`, full decode exits 0 — `.investigation/media-11_5/analysis2.txt` §F,
`decode_exit=0` for id194/id195) and playback is confirmed (VLC, see
PARITY §6.2) — the difference is timing structure, not lost picture data.

### Attribution status: ATTRIBUTED (Phase 11.5G, 2026-09-28)

**(1) The "no title-matched pair exists" premise is retired.** A **genuine matched pair**
was produced — same title, same episode, same fansub/quality, *not* duration-nearest:
`The World's Strongest Rearguard` Ep. 1. Android side = `Movies/PaheBatcher` MediaStore
**id 220**, pulled byte-exact to `.investigation/media-final/android_rearguard_ep1.mp4`
(112,170,554 B). Python side = `<python-ref-3.6.0>\downloads\The_Worlds_Strongest_Rearguard\
Ep_001_-_The_Worlds_Strongest_Rearguard_Ep._1_animepahe.mp4` (mtime 2026-09-25 19:36:57).
`count_gaps.py` on the pair:

| | video holes | audio holes | video excess | audio excess |
|---|---|---|---|---|
| Android (id 220) | **142** | **142** | 764,788 ticks / **8.4976 s** | 374,977 ticks / **8.5029 s** |
| Python reference | **0** | **0** | — | — |

**(2) Reconstruction controls isolate the mechanism to the *assembly step*, not the media**
(read-only WSL FFmpeg **8.0.1**; **scripts** live in `.investigation/media-final/`, **outputs** in
`.investigation/media-final/recon/` — path corrected after review; the full re-measurement is
persisted in `recon/count_gaps_all.txt`, so a later reviewer reads the evidence instead of
reproducing it):

| control artifact | produced by | video samples / audio samples | video holes / audio holes | excess (video / audio) |
|---|---|---|---|---|
| `control.mp4` | `recon.sh` step1 (mp4 → `src.ts`) + **`recon2.sh`** single-file concat — whole `src.ts` as **one** input, identical argv | 34046 / 30582 | **0 / 0** | 0 / 0 |
| `reassembled.mp4` | `recon.sh` step2 `-f segment -segment_time 10 -reset_timestamps 0` + step3 concat | 34046 / 30582 | **141 / 141** | **8.4206 s / 8.4164 s** |
| `bytesplit.mp4` | `recon3.py` **pure 188-byte packet-count split**, **not** PAT-aligned | 34046 / **30522** | **141 / 141** | 46.2513 s / 49.0471 s |
| `bs2.mp4` | `recon5.py` **PAT-aligned** (PID 0 + PUSI) byte split + `recon6.sh` concat | **34046 / 30582 (exact)** | **141 / 141** | 40.1884 s / 40.1978 s |
| **production** | `FfmpegEpisodeAssembler` + `concat.txt`, FFmpeg **n8.1.2** (logcat:8182 — "for 143 segments") | 34046 / 30582 | **142 / 142** | 8.4976 s / 8.5029 s |

*Corrections applied after the background review (reviewer M-1):* the first published version of
this table **misidentified two of its four artifacts** and attached the `8.42 s` figure to the
wrong file. `bytesplit.mp4` is a plain packet-count split, **not** PAT-aligned, and does **not**
preserve the sample counts — it drops **60 audio samples** (30522 vs 30582); `bs2.mp4` is the
PAT-aligned arm; `reassembled.mp4`, not `bs2.mp4`, is the `-f segment` re-cut and is the file that
measures **8.42 s** — `bs2.mp4` measures **40.19 s**, five times larger. Every figure above was
re-derived from the persisted artifact in that correction round.

**The decisive arm is `bs2.mp4`**: a byte-split of `src.ts` whose video *and* audio sample counts
are **exactly** those of the single-file input — its payload is provably contiguous and lossless —
yet it still yields **N−1 = 141 holes**. The discontinuities are therefore **created by FFmpeg's
multi-file MPEG-TS concat path**, not present in the payload. The `-f segment` arm
(`reassembled.mp4`) independently reproduces production's magnitude (8.42 s vs 8.50 s).
Segment-boundary continuity, persisted in `recon/boundary_continuity.txt` over all 141 adjacent
pairs: mean **−0.0597 s**, range **−0.0833 … −0.0382** — every boundary is a small *overlap*, not a
60 ms shortfall (the earlier "≈ −0.05 s" understated it; **−0.060 s mean** is the measured value),
so the segments themselves are not short either.

**(3) Both sides still run the identical command** (`store.py:56-59` ==
`FfmpegEpisodeAssembler`'s `ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -f mp4
-movflags +faststart`; Python `concat.txt` is one `file '<seg>'` line per segment), and
both output the same box layout (`ftyp → moov → free → mdat`).

**(4) The one remaining discriminator is the FFmpeg build, and it is unrecorded on the
Python side.** Android's runtime FFmpeg is **`n8.1.2`** (`.investigation/media-final/
logcat_device_run.txt:8189`, full `configuration:` at line 8193). The Python repository
pins **no FFmpeg version anywhere** (grep over `*.{py,toml,md,txt,cfg}` in
`<python-ref-3.6.0>` → 0 matches; `store.py` invokes the bare `ffmpeg` argv), and no
binary ships in the repo — so the version that produced the 0-hole reference files cannot
be recovered. **Honest limit of the claim:** the seams are proven to be *created by our
assembly step on our FFmpeg build*; that Python's *method* would behave differently on
`n8.1.2` is **not** claimed, because Python's build is unknown.

### Affected Features
- Perceptual smoothness at segment boundaries (worst case ≈ 100 ms hold every ~10 s) and
  A/V-relative timing of Android-assembled files. Originally scoped to "these two sources
  only" — the reconstruction controls show the mechanism depends on **input file count**,
  not on the title, so it applies to every Android-assembled MP4.

### Status — ~~deliberately NOT fixed (scope)~~ **RESOLVED in Phase 11.5H (DECISIONS 80)**

The remedy this section reserved as a "bounded, separately gated follow-up" was executed in
Phase 11.5H: `FfmpegEpisodeAssembler.assemble()` now runs the **single contiguous MPEG-TS
input first** and demotes the concat demuxer to fallback — the same shape as the `control.mp4`
arm above, which is the arm that measured **0 holes**.

Both conditions this section attached to the remedy were satisfied without touching an assertion:
the argv-contract tests still pass **unmodified** because the method names (`executePipeFallback`,
`concat`) and both argv shapes were deliberately kept (DECISIONS 80), and the full device cycle
**was** re-run — a fresh 143-segment download → assembly → MediaStore export (id 246) → in-app
playback.

Re-measured on the *same* title-matched episode after the change
(`.investigation/media-final/gaps_all.txt`, `comparison_report.txt`):

| | video holes | audio holes | video `total_ticks` | audio `total_ticks` | container duration |
|---|---|---|---|---|---|
| Android-BEFORE | 142 | 142 | 128,564,999 | 63,006,703 | — |
| **Android-FINAL** | **0** | **0** | **127,800,172** | **62,631,937** | **1420.225329 s** |
| Python reference | 0 | 0 | **127,800,172** | **62,631,937** | **1420.225329 s** |

Video `stts` is now byte-identical to Python (17,024 entries / 127,800,172 ticks) and the
per-track elementary streams are byte-identical to Python in every output
(`track_payload_report.txt`), which is what proves the reorder changed *timing structure only*.
The residual difference from Python is documented in **§30**.

The FFmpeg-version discriminator in **(4)** above is unaffected and still stands: Android's build
is `n8.1.2`, Python's is unrecoverable — the claim remains "our assembly step created the seams
and removing the multi-file concat removes them", not "Python's build would have behaved
differently".

### Risk Level (updated)
**LOW / RESOLVED** — full decode exits **0** (`.investigation/media-final/decode_full.txt`, both files —
*citation corrected after review*), seek clean, third-party playback confirmed on the device
(VLC renders the file: `.investigation/media-final/screen6_vlc_controls.png`), and §29
shows the Media3 failure is **independent** of this structure (it fails at codec-config stage —
`:16110 We have a failed config` at .292 immediately precedes `:16114 aacDecoder_ConfigRaw
decoderErr = 0x2003` at .294, before any timestamp is read, so the "~52 ms" figure first written
here — which was anchored on the *video* codec — has been dropped — reviewer m-3), and the 0-hole
Python file fails identically).

---

## 29. Media3 Refuses Assembled MP4s Whose Source Rendition Is AAC-Main (`mp4a.40.1`) — Root Cause CLASS C (Found and physically verified 2026-09-28, Phase 11.5G; **RESOLVED in Phase 11.5H, 2026-09-29**)

### Symptom (physically reproduced on hardware, twice)
On device `REDACTED-SERIAL` (Android 16, arm64-v8a, user 10), opening the in-app player for a
**completed, successfully assembled and MediaStore-exported** episode
(`content://media/external/video/media/220`) fails immediately with Media3's
"Failed to decode media samples" overlay and a Retry control. Full Logcat:
`.investigation/media-final/logcat_device_run.txt` (**78,399,694 B**, 255,063 lines (254,931
non-blank), UTF-16LE,
SHA-256 `8AF292E0DA62F3D6572D875E0F10A1C00DDC467F390A1AADF097FC02F12814D0` — *size corrected
after background review, round-2 finding 2: the "23.7 MB" first written here was already false
when published, because the `adb logcat -v time` capture had been left running and appending all
session; it has since been stopped and the file frozen, and the same wrong figure in DECISIONS 78
and ROADMAP item (b) was corrected in the same pass*), screenshots
`screen4_player_t6.png` (failure) vs `screen5_vlc.png` / `screen6_vlc_controls.png`
(control).

Exact device evidence:
```
E/C2SoftAacDec(2482): aacDecoder_ConfigRaw decoderErr = 0x2003
ExoPlaybackException: MediaCodecAudioRenderer error ... mp4a.40.1, 65202 ... [1,-1,22050]
MediaCodec: Codec reported err 0xe / 14
```
The `0x2003` arises from `aacDecoder_ConfigRaw` at **config parse time, before a single media
sample or timestamp is read** — `:16110 E/Codec2-ComponentInterface: We have a failed config`
(16:42:17.**292**) immediately precedes `:16114 E/C2SoftAacDec: aacDecoder_ConfigRaw decoderErr =
0x2003` (.**294**). *(The "~52 ms after codec creation" wording first written here was anchored on
the wrong codec — 52 ms is the gap from the **video** adapter `:15773` to the error; measured from
the **audio** adapter `:16010` it is 20 ms, from `allocate(c2.android.aac.decoder)` `:16011` it is
19 ms. The number has been dropped in favour of the reproducible line sequence — reviewer m-3.)*

### Root-cause classification axis (restated — this axis is *not* defined anywhere else in this repo)
| Class | Definition | Result |
|---|---|---|
| **A** | **Our file / our pipeline is defective** — the assembled output is wrong | **RULED OUT** — VLC on *the same device* renders the file (frame + subtitle visible, `screen6_vlc_controls.png`), and a full `ffmpeg` decode (`ffmpeg -v error -nostdin -i <file> -f null -`, no `-t`/`-frames:v` limit) exits **`exit=0`** for **both** the Android file and the Python reference, persisted in **`decode_full.txt`** *(citation corrected after review: the original pointed at `decode_compare.txt`, which contains **no** exit code and cannot record one — its script decodes only 5 s of audio and 1 video frame and pipes stderr through `grep` then `head`, so `$?` would be grep's. The claim was true but the cited artifact did not carry it.)* |
| **B** | **The source content is defective** — it would fail everywhere, in any player | **RULED OUT** — same two controls |
| **C** | **Android platform / media-decoder limitation** — the device's software decoder refuses a stream other players accept | **ASSIGNED** |
| **D** | **Undetermined** — evidence insufficient to choose | **RULED OUT** — the evidence above is conclusive |

### Why C, specifically
- The container's `esds` → AudioSpecificConfig is **byte-identical** in the Android file
  and in the Python reference file — specifically the common span `05 80 80 80 02 0b 88`
  (`esds_hex.txt`), which is the esds descriptor tag `05` + 4-byte length `80 80 80 02` wrapping
  the **2-byte AudioSpecificConfig `0b 88`** = AOT **1 (AAC Main)**, **22050 Hz**, **mono**.
  *(Correction, reviewer m-4: an
  earlier version of this bullet quoted `... fa 00 05 80 80 80 02 | 0b 88 | 06 ...` as
  byte-identical, but **`fa 00` occurs only in the Python file** — Android has `f8 83` there, and
  the `fe b2`/`fe fb` prefix differs too. Only `05 80 80 80 02 0b 88` is common; of that span only
  `0b 88` is the AudioSpecificConfig proper (`05` is the descriptor tag, `80 80 80 02` its
  4-byte length) — and that ASC is what the decoder parses, so the conclusion holds on the
  corrected span.)*
- **The two retained artifacts describe this same audio in two different ways; both readings are
  recorded as measured and neither artifact is altered.** `esds_hex.txt` gives the *declared*
  AudioSpecificConfig `0b 88` = AOT 1 (AAC Main), 22050 Hz, mono, and the device's own Media3
  failure line reports `mp4a.40.1 ... [1, -1, 22050]` (`:16121`). `decode_compare.txt` gives the
  *decoded / interpreted* side, under its own `--- ffprobe stream profile/rate ---` heading -
  `profile=HE-AAC` and `sample_rate=44100` for both files, at lines 6-7 and 25-26. **No retained
  artifact states why the two readings differ, so no mechanism is asserted here**: both
  `decode_compare.txt` and `decode_full.txt` print ffmpeg's parametric-stereo advisory for both
  files - "Parametric Stereo signaled to be not-present but was found in the bitstream" - but that
  advisory is recorded, not interpreted, and nothing is inferred from it. Class **C** does not rest
  on either reading - it rests on the declared config failing to parse at `:16114`
  (`aacDecoder_ConfigRaw decoderErr = 0x2003`, preceded by `We have a failed config` at `:16110`)
  and on `Decoder failed: c2.android.aac.decoder` at `:16131`; Media3 does not decode this stream
  successfully.
- Android's esds bitrate metadata reads maxBitrate 65202 / avgBitrate 63619 and Python's
  65275 / 64000 (both matching their ffprobe readings) — no decoder inspects these.
- Therefore **the Python reference file carries the same `0b 88` and would fail
  identically in Media3** — the failure is not something our pipeline wrote.
- `C2SoftAacDec` is Android's *software* AAC decoder; it rejects the AAC-Main config from
  its own config parser. This is a property of the platform decoder, not of the file.

**Consequence:** in-app (Media3) playback of an assembled episode fails whenever the
source rendition for that episode is AAC-Main. Whether any given file is affected can be
read from its AudioSpecificConfig; only the matched-pair title was tested here, so
**no claim is made about id194/id195 or any other file.**

### Explicitly NOT attempted (mandate)
No codec switch, no re-encode/transcode, no faststart/remux, no `RenderersFactory`
extension-renderer change, no Media3 redesign, and no unsupported playback workaround.
AVPlayer/external-intent hand-off and any decoder workaround remain **out of scope**.

### Player-code audit: NO in-scope defect found → no player code was changed
`PlayerViewModel.onPlayerError` sets `playerError = true, loading = false`;
`PlayerScreen` renders `PlayerDefaults.ErrorOverlay(player)` plus a Retry button;
`retryPlayback()` clears the flag and calls `exo.prepare()` (a genuine re-prepare).
Media3 itself logs the full `PlaybackException` to Logcat, so a support engineer does get
the diagnostic without additional app logging. **Adding a redundant `Log.e` was considered
and rejected** — it would be a change invented to have something to change, not a defect
the investigation proved.

### Risk Level
**MEDIUM** — a user-facing failure of the app's own player for affected files, with a
fully established cause outside our code and no permitted remedy in this phase.

### Resolution — Phase 11.5H (2026-09-29), DECISIONS 81

The classification above stands and was the input to the fix; what was missing was a permitted
remedy, and Phase 11.5H supplied it without re-encoding, without a codec switch and without any
Media3 change.

**The class-C cause was narrowed to a single mislabelled byte of container metadata.** Before any
code was written: (1) an ADTS scan of the source TS found `profile = 0` (AAC Main) on **10,663**
frames (`recon/src.ts`), so the declaration originates upstream, not in our pipeline; (2) every
frame carries SBR **and** PS, and HE-AAC/HE-AACv2 are defined only over an AAC-LC core; (3) a
full-episode reference decode with the ASC corrected to AAC-LC passes `ffmpeg -v warning -xerror`
with **zero** warnings, while the AAC-Main declaration emits **30,581** PS advisories and decodes
mono instead of stereo; (4) the device exposes exactly **one** AAC decoder
(`c2.android.aac.decoder`, FDK — no hardware AAC), and it is that decoder's config parser that
returns `0x2003`.

**Fix:** `AudioSpecificConfigNormalizer`, called from both success branches of
`FfmpegEpisodeAssembler.assemble()` after `validateOutput` and before `atomicRename`, rewrites
exactly one byte (`0b 88` → `13 88`, AOT 1 → 2) when and only when `objectTypeIndication == 0x40`
and `audioObjectType == 1`. It never fails assembly.

**Verified after the fix — same episode, produced by the app itself:**

| stage | BEFORE | AFTER |
|---|---|---|
| assembly log | `Assembly succeeded via concat demuxer` | `Assembly succeeded via single contiguous MPEG-TS input` + `I/AscNormalizer: Corrected AudioSpecificConfig at offset 743396` |
| in-app playback | "Failed to decode media samples", position stuck; `aacDecoder_ConfigRaw decoderErr = 0x2003`, `Codec reported err 0xe/14`, `Decoder failed: c2.android.aac.decoder` | **plays** — screenshots `media3_playback_00m18s.png` (00:18) and `media3_playback_00m54s.png` (00:54), duration OSD **23:40** |
| decoder output format | n/a (config rejected) | `I/C2SoftAacDec: Reconfiguring decoder: 0->44100 Hz, 0->2 channels` |
| error lines in logcat during playback | 3 (`0x2003`, `err 0xe`, `Decoder failed`) | **0** |

Artifacts: `.investigation/media-final/logcat_before_fix_playback.txt` (3 failure lines) vs
`logcat_fix_run.txt` (fix + playback), `final_ffmpeg_decode.txt` (STRICT_OK), `byte_report.txt`
(`esds` `0b88` → `1388`), `comparison_report.{txt,json}`, `gaps_all.txt`,
`track_payload_report.txt`, `asc_profile_comparison.txt`, `vlc_final_playback.log`.

**Deliberate divergence from Python:** Python still propagates `0b 88`, so the Python reference
file remains Media3-unplayable — that is now recorded as **§30** rather than claimed as parity.
VLC/FFmpeg continue to play both files identically (FAAD reports `AAC SBR+PS (channels: 2,
samplerate: 44100)` for each), which is exactly why they could not be used to certify the
declaration.

**No player code was changed** — the §29 player-code audit conclusion is unchanged.

---

## 30. Residual Android-vs-Python Differences and Accepted Costs After Phase 11.5H (2026-09-29)

Everything below is a *measured* remainder after §28 and §29 were resolved. Nothing here is
claimed as parity, and nothing here is a defect that was left unfixed — each item is either a
deliberate divergence with a reason, a byte-level artefact of an otherwise identical timeline, or
a cost paid for the fix.

Comparison set (all four files are retained under `.investigation/media-final/`):

| label | file | bytes | md5 |
|---|---|---|---|
| Python | `<python-ref-3.6.0>\downloads\The_Worlds_Strongest_Rearguard\Ep_001_…animepahe.mp4` | 112,134,041 | `ea217fe321630aaf8e34f88f1f63762f` |
| Android-BEFORE | `android_rearguard_ep1.mp4` | 112,170,554 | `086f27232694a0dbff45f7f529e29c01` |
| Android-AFTER (order fix only) | `android_rearguard_ep1_after.mp4` | 112,134,018 | `5e2287e4af54f10610c259a82bf7190e` |
| Android-FINAL (both fixes) | `android_rearguard_ep1_final.mp4` | 112,134,018 | `560dd43b8123325a71de1d196f150f87` |

### 30.1 The `esds` AudioSpecificConfig differs by exactly one byte (deliberate, DECISIONS 81)

> **SUPERSEDED by Python 3.6.1 (Phase 12C re-audit, 2026-09-30).** The reference now performs the
> same correction itself — `store.py:fix_aac_esds()` runs after **both** assembly paths
> (`store.py:196,200`) and rewrites AOT 1 → 2 as metadata only. The Phase 12C comparison of two
> current-Python outputs measured **identical ASC bytes `13 90` and identical elementary-stream
> md5s on both sides**, so this item is no longer an Android-vs-Python difference (PARITY §6.2,
> DECISIONS 81 note). The text below is retained as written for the 11.5H-era measurement.

Python and Android-FINAL describe the same `mdat` with `0b 88` (AOT 1, AAC Main) and `13 88`
(AOT 2, AAC-LC) respectively. Android needs the correction because the platform's only AAC
decoder rejects AOT 1 (§29); Python does not make it, so the two containers are no longer
byte-identical in `moov`. This is **not** parity and is **not** reported as such.

A knock-on, both measured and neither artifact altered: ffprobe reports the *same bytes* as
`HE-AAC, 44100, 1 ch, mono` **plus** a `Parametric Stereo signaled to be not-present but was found
in the bitstream` warning for Python/BEFORE/AFTER, and as `HE-AACv2, 44100, 2 ch, stereo` with no
warning for FINAL (`asc_profile_comparison.txt`). VLC's FAAD reports `AAC SBR+PS (channels: 2,
samplerate: 44100)` for **both**, so the two readings describe the same payload. The stereo
reading is the correct one: the bitstream carries PS and HE-AACv2 is LC+SBR+PS, and Android's own
decoder outputs 44100 Hz / 2 channels after the correction.

### 30.2 Audio `stts` run grouping differs (byte-level only, timeline identical)
FINAL/AFTER group the audio track's 30,582 samples into **11,228** `stts` entries; Python groups
them into **11,231**. `total_ticks` is **62,631,937 on both**, so duration, drift and every sample
timestamp are identical — only how many runs it took to express them differs, which is what makes
the file **23 bytes** smaller. Video `stts` is byte-identical (17,024 entries / 127,800,172 ticks)
and so is the video `stsd`.

> **Phase 12C re-measured the same class of difference on a current-Python pair** (One Piece Ep 1,
> §33.5) and it appears in the *other* track plus one container byte: video `stts` carries **4
> fewer run entries** on Android (−32 B) while the audio `stts` is now identical, and `udta/meta`
> is 1 B larger — net **−31 bytes**, again with every `total_ticks`, frame count and duration equal
> and both files' elementary streams md5-identical. Which track groups differently is therefore an
> artefact of how FFmpeg compresses the runs of a given input, not a divergence in what was
> written; the residual is the same *kind* of thing this section has always described.

### 30.3 The single-input primary path costs a transient temp file
`executePipeFallback()` writes `_pipe_input.tmp` — roughly the sum of the segment bytes — before
running FFmpeg, because FFmpegKit's API does not expose stdin piping. The concat path read the
segments directly. This is an accepted, documented disk cost of the fix (the pipe/temp-file
adaptation itself is DECISIONS 79); it is transient (removed in the `finally` block) and does not
affect output.

### 30.4 The Python FFmpeg build identity is still unrecovered
Carried forward unchanged from §28(4): Android runs `n8.1.2`, the Python repo pins no FFmpeg
version. The residual claims are therefore about *our* assembly step on *our* build.

### 30.5 `mdat` interleaving was different in BEFORE and is now identical
`mdat` payload md5 is `6bf15684dfad877d566e5c096d2676a6` (111,055,878 B) for Python, AFTER and
FINAL alike; BEFORE was `0abc32d873979d475c37e8f48869071a` at the *same size*. Per-track
elementary-stream md5s are identical across all four files
(`40a5738bd33399a97d0b620f6e6de550` video / `8da0e8b9144abcb3d5d00370336b1673` audio —
`track_payload_report.txt`), so the BEFORE mismatch was audio/video **sample interleaving order**
under the stretched timeline, never a content difference. No payload was rewritten by either fix.

### Risk Level
**LOW** — every item above is measured, disclosed and bounded; none affects playback, and
§28/§29 (the two user-visible symptoms) are RESOLVED.

---

## 31. Phase 12A — Deferred Settings, Console Bounds, and Instrumentation Attempt History (2026-09-29)

### 31.1 Settings that exist in Python but have no Android settings row (deliberate deferrals)

> **SUPERSEDED in part by Phase 12C (2026-09-30):** `resolve_ahead` and `audio_lang` are **no
> longer deferrals** — Phase 12C gave both a real consumer and a Settings row, then the Phase 12C
> re-audit reversed this section's "KEEP DEFERRED" outcome for those two keys (§33.1, DECISIONS
> 90). The current, authoritative deferral list is the two rows left marked below plus PARITY §9.5.

A setting is only listed in the Settings screen when **production code actually consumes it**.
Four Python config keys were therefore intentionally absent from the surface rather than shown as
non-functional rows (two of the four have since been wired — see the note above):

| Python key | Why it is not a Settings row on Android | Where it is / will be handled |
|---|---|---|
| ~~`resolve_ahead`~~ **(wired in 12C)** | *(superseded)* The original rationale — no resolver-prefetch pipeline whose depth the key could widen, so a slider would be decorative — was **re-evaluated in Phase 12C**: Python's own consumption point is not a prefetch pipeline either, it is the staging-queue capacity `resolve_ahead + max_parallel` (`downloader.py:205-208`). Android now enforces the same quantity through `ResolveAheadGate` + `EpisodeDownloadDao.countResolvedPending()`. *(The still-earlier wording here cited a `Semaphore`/LIMITATIONS §4 as a resolver bound — that semaphore is the **segment-download** concurrency cap, not a resolver cap, and the claim was false; corrected in Phase 12B, DECISIONS 88/84.)* | **Settings row**: 0/1/2/4/8/Unlimited(999), default 999; consumed at batch/watchlist resolution time (§33.1, §33.3) |
| `cookie_string` | Session material is owned by the WebView session abstraction (Decision 26). Hand-editing a raw cookie string in a text field would bypass that abstraction and invite users to paste secrets into UI state. | Settings screen exposes only "Clear WebView session". |
| `output_dir` | Directory selection must go through the Storage Access Framework (scoped storage, minSdk 32). SAF is Phase 13. | PARITY §9.5 / §13 scope note. |
| ~~`audio_lang`~~ **(wired in 12C)** | *(superseded)* The original rationale — the per-variant row tap *is* the language selection — still holds for single-episode taps, but it did not cover batch and watchlist selection, where Python reads a preference (`downloader.py:215`, `watchlist.py:1002`). Phase 12C wired both and the key now has exactly that consumer. | **Settings row**: `Sub (jpn)` / `Dub (eng)`, default `jpn`; consumed once per batch and per entry (§33.1) |

No fake or no-op row exists for any of these. The rows that *are* present
(`quality`, `max_parallel`, `hls_workers`, `auto_retry`, `keep_temp`, `cache_ttl`,
`audio_lang`, `resolve_ahead`, plus
re-authentication / console / cache-clear actions) are each read by production code — see
PARITY §9.5.

There is also **no CLI on Android**, so Python's `cli_set` has no equivalent: configuration is
read from DataStore through `AppConfigDownloadConfigSource`, and every persisted value is
consumed inside the same process run (`DownloadWorker` reads the config **once per run** and
keeps the last good value if a read fails).

### 31.2 In-app Console is bounded by design

The optional Console (`Show Console`, **default OFF**) is a logcat mirror with a bounded
in-memory ring buffer (`LogBuffer`: **500 entries**, each entry truncated to **4000 chars per
message and 4000 chars per rendered throwable** — there is **no aggregate byte cap**; the earlier
"capped by byte count and line count" wording overstated it and was corrected in Phase 12B).
It is:

* **never** written to Room or to disk, so log retention cannot grow the database or storage;
* fed only through the `AppLog` abstraction, which is the single production file that touches
  `android.util.Log`;
* **not** a dependency of download execution — downloads run identically with the buffer
  disabled;
* **gated by the persisted `Show Console` key since Phase 12B**: while it is OFF, new lines are
  not retained and turning it OFF clears what was retained; Logcat is never affected.

**Corrected in Phase 12B (reviewer H2):** `AppLog` performs **no content filtering** — its own
KDoc says so explicitly. Cookie/`Authorization`/token/credential safety is a documented
**call-site obligation** (cookie names only, no tokens or authorization headers, no
session-bearing page URLs), not code that strips secrets before they reach Logcat or the buffer.

Residual risk: log content produced by third-party libraries still reaches logcat on device and
is outside `AppLog`'s control; the in-app buffer only reflects what the app itself logs. Because
there is no content filter, a future call site that logs a secret would emit it to both sinks —
mitigated only by review of call sites, not by machinery.

### 31.3 `SettingsPersistenceTest` attempt history (environmental, disclosed)

`SettingsPersistenceTest` (4 cases) is an on-device UI test and required **4 attempts** in this
phase; the history is disclosed rather than hidden:

| Attempt | Result | Cause |
|---|---|---|
| 1–2 | FAIL | `ComposeTimeoutException` — the test scrolled rows with a raw swipe computed from display height instead of `performScrollTo()`, which is layout-independent. |
| 3 | FAIL | **Environmental**: device was dozing (`mWakefulness=Dozing`, no compose hierarchy), and an *external* actor ran `adb cmd package uninstall com.pahebatcher.app` / `com.pahebatcher.app.test` mid-run at 23:13:06. |
| 4 | PASS 4/4 | Screen woken (`KEYCODE_WAKEUP`, `wm dismiss-keyguard`), app reinstalled, test rewritten to use `performScrollTo()` + `assertIsDisplayed()` (review item M3/C2). |

Passing attempt 4 is the only basis for the "SettingsPersistenceTest 4/4" claim elsewhere in
these documents.

### Risk Level
**LOW** — every deferral above is a *missing* surface, not a broken one; nothing in Phase 12A
reports success it did not measure.

---

## 32. Phase 12B — Retention Gate, Deferred-Keys Re-audit, Device Events (2026-09-30)

### 32.1 The four deferred Python config keys remain deferred (re-audited, KEEP DEFERRED)

Re-checked against the actual Python sources this phase rather than carried over on trust
(DECISIONS 88):

> **SUPERSEDED by Phase 12C (2026-09-30):** this table's 12B conclusion — all four keys KEEP
> DEFERRED — was reversed for `audio_lang` and `resolve_ahead`. Both now have a production
> consumer and a Settings row (§33.1, §31.1 note, PARITY §9.5); `output_dir` and `cookie_string`
> remain deferred. The "zero production callers" claim below was true when written and is no
> longer true (`AnimeInfo.getVariant` now has the batch and watchlist callers it was missing).

| Python key | Missing capability that keeps it deferred |
|---|---|
| ~~`audio_lang`~~ **(wired in 12C)** | *(superseded)* Python reads it (`downloader.py:215`, `stream.py:243`, `watchlist.py:1002`) but the only *scan* ingress, `scanner.scan(prefer_audio=…)`, never uses the parameter (`extract/scanner.py:202`). Android then had **no by-number download, no "download all" and no watchlist auto-download** path that could carry a language preference — the episode-row tap passed its own `episode.audio` and `AnimeInfo.getVariant` had zero production callers. **Phase 12C added exactly those paths** (batch by number + watchlist auto-download), which is what made a row legitimate. |
| `output_dir` | Needs SAF directory picking (scoped storage, minSdk 32) — Phase 13. |
| ~~`resolve_ahead`~~ **(wired in 12C)** | *(superseded)* The stated gap — "no resolver-prefetch architecture to widen" — was the wrong test: Python's own use of the key is a **staging-queue capacity**, not a prefetch pipeline (`downloader.py:205-208`). Android now enforces the same quantity through `ResolveAheadGate` + `countResolvedPending()` (§33.1, §33.3). |
| `cookie_string` | The WebView session owns cookies (Decision 26); a raw text field would bypass it. |

### 32.2 Console retention gate — what it does and what is not proven

`Show Console` (default OFF) now gates **retention**: `PaheBatcherApplication` collects the
persisted key and calls `AppLog.setBufferingEnabled`, which — under a single `gateLock`, atomic
with every append — either lets lines through to `LogBuffer` or refuses them **and clears what
was retained**. Logcat output is unconditional in the debug variant; in a release build DEBUG/INFO are dropped before either sink (DECISIONS 131, §38.8).

**Superseded 2026-10-05 (final SPEC gap closure, DECISIONS 131, §38.8):** what a test now pins is the
opposite for diagnostic levels in a release build — `AppLogDiagnosticsGateTest` 7/7 shows DEBUG/INFO
reaching neither sink while WARN/ERROR always pass (debug variant unchanged). Still unpinned: that log
*content* is filtered (there is none, §31.2) and third-party library logging, which bypasses `AppLog`.

### 32.3 Device events during Phase 12B (disclosed)

* 09:32:01 — an **external actor** uninstalled `com.pahebatcher.app` from device Settings,
  before this session issued any device command.
* 10:03:58 — Gradle `connectedDebugAndroidTest` reinstalled the app
  (`installer_clear_app_data_caller`); the AGP teardown removed the `.test` package at 10:09:51,
  10:11:16 and 10:11:47, and again after the final 11:28 run — so the closing AGENTS §20
  `install -r` (11:30:04) landed as a fresh install (`firstInstallTime == lastUpdateTime`, no
  `files/` directory yet).
* This session ran **no** `pm uninstall` and **no** `pm clear`; its only mutating device commands
  were `install -r` and a temporary `stay_on_while_plugged_in=3` for the run window (deleted
  afterwards). Everything else was `adb devices`, `KEYCODE_WAKEUP`/`dismiss-keyguard` and
  read-only inspection.

### 32.4 Attempt history and remaining baseline

| Operation | Attempts | Outcome |
|---|---|---|
| `SettingsPersistenceTest` | 1 FAIL (dozing device), 2 PASS | Environmental; woken before attempt 2. |
| `TouchTargetAndFocusTest` | Full run FAIL (`15 != 3`), then 3 corrective iterations (row-count contract → scroll radio rows → scroll switch rows) | Final full run 5/5. The gap was **Phase 12A's** new radio groups outdating a Phase 11 test that 12A's gate never ran; the rewrite measures more rows than before, not fewer. |
| Full `connectedDebugAndroidTest` | run 1: 243/2, final: **243/1** | The 1 remaining failure is the long-documented `ForegroundWorkerTest` notification baseline (§31, DECISIONS 87 era) — reproduced identically, untouched by this phase. |

### Risk Level
**LOW** — the phase added no new production surface beyond the retention gate, corrected two
false documentation claims rather than leaving them, and every change is covered by a test that
existed before or was added with it.

---

## 33. Phase 12C — Blocked background checks, newly-consumed keys, media residual, attempt history (2026-09-30)

### 33.1 Two previously-deferred config keys now have real consumers; two stay deferred

Phase 12B recorded all four keys as KEEP DEFERRED. Phase 12C was explicitly required to
**re-evaluate** them and either wire them for real or report a blocker — a row that only persists
a value is forbidden (AGENTS §5):

| Python key | 12B verdict | 12C verdict | Where it is now consumed |
|---|---|---|---|
| `audio_lang` | deferred (no consumer) | **CONSUMED** | `AnimeDetailsViewModel.configuredAudioLang()` → read **once per batch** → `AnimeInfo.getVariant(number, audioLang) ?: getAllVariants(number).first()` (`downloader.py:215` parity); `WatchlistChecker` uses the **per-entry** `entry.audioLang` (`watchlist.py:1002` parity). Row: `Sub (jpn)` / `Dub (eng)`. *(`scanner.scan(prefer_audio=…)` — the argument this section cited as "the only ingress" — is a **dead parameter in Python itself**: `extract/scanner.py:202` declares it and its body never reads it; Android's `EpisodeRepository.scan` has no such parameter, so both sides agree in effect. The real ingress is per-number variant selection, which 12C wired.)* |
| `resolve_ahead` | deferred (no prefetch architecture) | **CONSUMED** | `configuredResolveAhead()` → `ResolveAheadGate.awaitCapacity(queue, resolve_ahead + max_parallel)` before each batch/watchlist resolution, measured by `EpisodeDownloadDao.countResolvedPending()` (`downloader.py:205-208` parity). Row: 0/1/2/4/8/Unlimited(999) |
| `cookie_string` | deferred | deferred (unchanged) | WebView session owns cookies (DECISIONS 26); a text field would invite pasting secrets into UI state |
| `output_dir` | deferred | deferred (unchanged) | Needs SAF directory picking (scoped storage, minSdk 32) — Phase 13 |

Nothing else changed in the settings surface; no row was added whose value production code does
not read.

### 33.2 RESOLVED in Phase 12D — the scheduled/background check runs over the application-scoped WebView transport (this section reported it **BLOCKED** before)

**Affected functionality:** periodic watchlist checking with the app closed/backgrounded — i.e.
Phase 12's exit criterion *"Background checks work"* and the corresponding SPEC behavior.

**The earlier verdict, kept for the record and superseded by Phase 12D:** the heading above read
**BLOCKER — a scheduled/background watchlist check cannot be implemented on the current HTTP
stack**, and argued three points: (1) `cf_clearance` is issued to the **WebView's** TLS/client-hint
stack, so a plain `OkHttp` request in a Worker cannot reuse it; (2) `evaluateJavascript` must run on
the main thread **and needs a live WebView attached to a resumed Activity**; (3) a Worker has no
Activity/window context, so a WebView created inside a background job would be an AGENTS §13
WebView-lifetime violation. Points (1) and (3) argue against building a *second* browser, which was
never the plan: Phase 10 already gave the app **one application-scoped, never-window-attached
WebView** — `AndroidCloudflareSession` builds `WebView(applicationContext)` on the main looper and
`performPageFetch` swaps its capture `WebViewClient` on that instance. **Point (2) was false**, and
Phase 12D demonstrated it: no resumed Activity participates anywhere in that path. The "no
Worker→WebView" wording was a Phase 10 *project rule* about `DownloadWorker`'s persisted handoff,
not a technical property of the transport, and 12D narrows it explicitly (DECISIONS 91) rather than
quietly stepping over it.

**What Phase 12D shipped (WorkManager only — no AlarmManager, no timer, no foreground service, no
daemon, no second WebView and no second check algorithm):**

| Piece | Where | Behavior |
|---|---|---|
| Worker | `worker/WatchlistCheckWorker.kt` | `CoroutineWorker` orchestration over the existing `WatchlistChecker.checkAll()`. `success` when the run finishes (per-entry failures are isolated and persisted by the checker; the next period retries them), `retry` only when an exception escapes `checkAll()` (a Room/settings infrastructure fault), `failure` never (a terminal failure would strand a schedule no user can restart), `CancellationException` rethrown |
| Scheduler | `worker/WatchlistCheckScheduler.kt` | ONE unique periodic work `watchlist_periodic_check`, 15-minute interval (WorkManager's minimum), `ExistingPeriodicWorkPolicy.KEEP`, `NetworkType.CONNECTED`, registered from `PaheBatcherApplication.onCreate` — process lifecycle only, never an Activity, a screen or a button |
| Composition | `worker/DownloadWorkerFactory.kt` | builds the Worker from the single `AppContainer.watchlistChecker`; a composition that forgot that checker fails loudly instead of silently never checking |
| Page serialization | `AppContainer.webViewPageLock` | one shared `Mutex` held by `AndroidWebViewRequestBridge.fetch` **and** `StreamResolverImpl.resolve` (the 11.5E-7 single-flight promoted to the HTTP entry point), because a second caller now reaches the same WebView unattended |

**Verification (observed on the single enumerated device `REDACTED-SERIAL`, persisted in
`.investigation/phase12d/device_12d_verify.txt`):** cold start logged
`Watchlist check schedule: … count=1 state=[ENQUEUED] tags=[…WatchlistCheckWorker]` and ran the
first check immediately; after `am kill` + restart the identical line was logged again with
**`count=1`** (KEEP did not duplicate) while `dumpsys jobscheduler` still showed exactly one
`androidx.work.systemjobscheduler:u10a521/…` job. A run was then captured with the launcher resumed
(`topResumedActivity` = launcher, `mLastPausedActivity` = `com.pahebatcher.app/.MainActivity`): three
real `animepahe.com` requests issued by `WebViewHttpClient`, `Background check finished: 1 entry …`,
and WorkManager recording `Worker result SUCCESS … tags={ …WatchlistCheckWorker }`. Returning to the
foreground showed `Checked just now` on that row, i.e. the run's `last_checked` survived in Room.
JVM + instrumentation: `WatchlistCheckSchedulerTest` 4, `WatchlistCheckWorkerTest` 7 and
`WebViewPageSerializationTest` 3 — each of the last three fails when the page lock is removed.

**Residual limits (stated, not hidden):**

* The transport is **one** shared WebView, so a background check and a foreground request are
  serialized instead of colliding: each waits for the other's page load. That is a latency cost
  (AGENTS §12 bounded concurrency), not a correctness one — without the lock the two callers would
  cross-capture pages, which is why 11.5E-7 serialized resolutions in the first place.
* Periodic work is inexact (system-batched, deferred under Doze): the schedule gives a check *at
  most* roughly every 15 minutes, never an exact wall-clock tick, and no exact-fire claim is made.
* **An interval setting now exists (Phase 14, 2026-10-03 — supersedes the previous "no user-facing
  interval setting" bullet):** `watchlist_check_interval_minutes` offers 15 / 30 / 60 / 240 / 720 minutes
  (all >= WorkManager's minimum, never invented shorter), applied to the one periodic request via `ExistingPeriodicWorkPolicy.UPDATE`.
* Three firings were observed, two of them entirely on their own: the cold-start run at 19:53:31
  and a later run at 20:31:22, each logged as `Starting work for …WatchlistCheckWorker` →
  `Worker result SUCCESS` with `topResumedActivity` = the launcher and no PaheBatcher Activity
  resumed (`.investigation/phase12d/device_12d_verify.txt`). The run with a non-empty watchlist —
  the one that issued three real `animepahe.com` requests — was triggered with `adb shell
  cmd jobscheduler run -f` against the *existing* scheduled job after this device (Nothing OS) had
  let that job's deadline pass while in state `READY`; after it the job was re-registered `+12m50s`
  out, i.e. the next period was scheduled, not created. Observed spacing was ~18 minutes against a
  15-minute request (system dispatch + flex), which is why no exact tick is claimed.

**Exit-criterion impact:** *Background checks work* is now **✅ verified** by the observation above,
so Phase 12's five exit criteria are met: sessions persist (the 12A/12B Room-backed suites),
watchlist works (the 12C checker suites), new episodes are detected, dead links are recovered on the
automatic path (manual relink control remains PARTIAL — PARITY §12.3), and background checks (12D). *(Update 2026-10-02: the manual relink control shipped with Phase 13 — PARITY §12.3 now reads ✅.)*
Phase 12 deliverables outside the exit criteria (session/watchlist data foundation, settings +
console polish, batch, config parity, media parity) are likewise implemented and verified.

### 33.3 `resolve_ahead` bounds *staging*, it does not create a background prefetch

The key is consumed, but the mechanism differs from Python's in one respect that must not be
overstated: Python's single asyncio resolver fills `download_queue(maxsize=resolve_ahead +
max_parallel)` inside one long-lived process, so "ahead of time" means *background*. On Android,
resolution still happens **against the shared application-scoped WebView rather than a plain HTTP
client** — the same transport whether or not the screen is up (DECISIONS 91) — so for a *batch*
started from the details screen the gate bounds *how far ahead of download capacity the batch
resolves while the screen is up*. The bound is real
(lowering it throttles a batch, and when the staging depth never drops below the bound the wait
expires into an honest error after a 30-minute cap) — it just does not buy background prefetching,
and a background `WatchlistCheckWorker` run stages through the same gate with the same bound.
Preserved invariants: no
Worker→Activity, deterministic persisted handoff, unique `DownloadWorker` work, process-death
safety. (The former "no Worker→WebView" item is superseded in 12D and restated narrowly at
DECISIONS 91: only the watchlist check Worker may drive the application-scoped WebView transport.)

### 33.4 Manual relink has no user-facing control *(superseded 2026-10-02 — control shipped with Phase 13: see §34.7)*

`WatchlistRepository.relink` exists, is used by the automatic dead-link path and is tested, but
there is no relink button and no CLI on Android (`watchlist.py:cli_relink` has no equivalent).
The automatic path covers the same cases; the manual affordance belongs to the full Watchlist UI
(Phase 13 — its complete interaction contract was fixed 2026-10-01 by DECISIONS 108, including
the deliberate divergences: no global relink-all mode and no `new_url` parameter). Reported as
PARTIAL in PARITY §12.3 rather than as parity. *(Superseded 2026-10-02: Phase 13 shipped the
per-row Relink control under the DECISIONS 108 contract — `WatchlistScreenTest` 11/11, JVM
`WatchlistRelinkTest` 7/7; PARITY §12.3 now reads ✅; the relink-all and `new_url` divergences
still stand.)*

### 33.5 Media residual after the Phase 12C comparison (measured, byte-level only)

Android batch output vs current Python output for the **same episode** (One Piece Ep 1, 1080/jpn) —
`.investigation/media-final/phase12c_compare.txt`:

* **Identical:** video elementary-stream md5 `9789e8ba011df4ac131f3b3a4e4307b1`, audio
  elementary-stream md5 `5bcf00ef483e14b85e1a84b80acc9fa1`, duration `1500.240476 s`
  (video `1500.041700`), frames `35965`/`32305`, 1920×1080, h264 High@3.1, 24000/1001,
  HE-AAC 44.1 kHz stereo, `mdat` size `236,760,874` on both, identical ASC `13 90`,
  box order `ftyp(32) → moov → free(8) → mdat`, strict `ffmpeg -xerror` decode OK on both.
* **Differs:** size `237,895,908` (Android) vs `237,895,939` (Python) — **31 bytes**, located by
  `.investigation/media-final/moov_diff.py`: video `stts` has **4 fewer run entries** (32 B) and
  `udta/meta` is **1 byte larger**. Same class as §30.2 (how many runs it took to express the same
  sample timestamps, plus a container-metadata byte): every sample count, tick total and duration
  matches, so the timeline is identical.

Not claimed: byte-identical files. Claimed: identical codec payload, identical timeline,
identical container shape, and playback of the Android file **confirmed on the device**
(`phase12c_playback_*.png` — VLC renders the file, frames advance across screenshots).

### 33.6 Device events and attempt history (disclosed)

* **AGP connected-test teardown uninstalls BOTH APKs after every run** (`deletePackageX` for
  `com.pahebatcher.app` and `com.pahebatcher.app.test`, visible in `-b system` logcat at 14:13:18,
  14:14:16 and 14:24:32). Consequence: the AGENTS §20 `install -r` must be the **last** device
  step, and app data (Room) is wiped by each test run — which is why the batch test's exported
  MediaStore file, not a database row, is the evidence for playback. This session ran **no**
  `pm uninstall` and **no** `pm clear`.
* `BatchDownloadDeviceTest` attempt history: run 1 **FAIL** (120 s listing wait) — two real bugs
  in the *test*, both fixed after diagnosis: (a) the exact text `"One Piece"` matched the search
  **field** before the result row, so `[0].performClick()` never navigated (fixed by adding a
  `search_result_row` test tag in production); (b) One Piece's real scan takes **256 s** on this
  device (measured manually), so the listing wait is now 600 s. Run 2 **FAIL** at the final
  assertion — `onAllNodesWithText("Done: queued 1")` used exact matching against a summary line
  that continues with `· skipped … · failed …` (fixed with `substring = true`). Run 3 **PASS**
  (5 m 52 s end to end).
* `WatchlistScreenTest` attempt history: run 1 **FAIL** — all 7 cases reported "No compose
  hierarchies" because the device screen was locked (`mWakefulness=Dozing`-equivalent, the
  activity went RESUMED→STOPPED immediately); environmental, woken with `KEYCODE_WAKEUP` +
  `wm dismiss-keyguard`. Run 2: 6/7 — the add-field case failed because the test fed the field a
  value that never changed while production round-trips it through state (test fixed to mirror the
  ViewModel); run 3: **7/7**.
* `AnimeDetailsBatchScreenTest` attempt history: run 1 **10/11** — the finished-batch case
  asserted a disabled `Download` button while its own fixture had no selection (test fixture fixed
  to keep the selection, which is the behavior under test); run 2: **11/11**.
* MediaStore retained every export across the teardowns, so the same display name was reused with
  a `(2)` suffix; the compared file is MediaStore id **314**, `_size = 237,895,908` — the exact
  byte count pulled over `adb exec-out content read --uri … --user 10`.

### 33.7 Verification summary (Phase 12C)

| Gate | Result |
|---|---|
| `:app:testDebugUnitTest` | **612 tests / 0 failures / 0 errors / 0 skipped** (56 suites; +34 new 12C tests: `ResolveAheadGateTest` 3, `StreamResolutionRetryTest` 5, `AnimeDetailsViewModelBatchTest` 10, `WatchlistCheckerTest` 16 — the +4 are the review round's regression tests for H-1, M-5, L-3 and L-4) |
| `:app:lintDebug` | **0 errors / 25 warnings** — every warning is in a file Phase 12C did not touch (verified by listing all 25 with their file paths), and **11 of the 25 are version-availability notices** (`GradleDependency` ×8, `NewerVersionAvailable` ×2, `AndroidGradlePluginVersion` ×1) that depend on the dependency metadata lint resolves rather than on this code — which is why this count is not stable across runs and differs from the long-standing 24-warning baseline. The remaining 14 are code warnings all in untouched files. **No warning was added by 12C code, and no warning was suppressed** (AGENTS §7) |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, APK 115,280,968 B, SHA-256 `2557A5B941EBBFC20EFCE9ACB5DF5EF408E861ED2D98B4BD1298A0D8FC3178C6` (rebuilt after the review round's code fixes) |
| New instrumentation | `ResolvedPendingCountTest` (real Room/SQL) PASS, `WatchlistScreenTest` **7/7** PASS, `AnimeDetailsBatchScreenTest` **11/11** PASS, `BatchDownloadDeviceTest` PASS (full production batch: scan → resolve `…_1_jpn` → 152 segments → assembly → MediaStore export `Ep_001_-_One_Piece_Ep._1_animepahe (2).mp4`, 237,895,908 B) |
| Full `connectedDebugAndroidTest` (`REDACTED-SERIAL`) | **264 tests / 1 failure** — only the long-documented `ForegroundWorkerTest` notification baseline; **re-run after the review round's code fixes** (`.investigation/media-final/connected_12c_review.log`) with the identical result, all four 12C suites green in the same run |
| Deployment evidence | `.investigation/media-final/adb_devices_12c.txt` — the AGENTS §20 target list at deploy time (one `device`-state device: `REDACTED-SERIAL`, REDACTED-MODEL); re-verified before the final `adb -s REDACTED-SERIAL install -r` |
| Media comparison | `.investigation/media-final/phase12c_compare.txt` — identical ES md5s, durations, frame counts, resolution, codecs, audio properties, `mdat` size and ASC bytes; 31-byte container-metadata delta explained in §33.5 |
| Device playback | `phase12c_playback_a/b/osd.png` — the batch-produced file renders on `REDACTED-SERIAL` and frames advance |

### 33.8 Phase 12C read-only review round — findings and dispositions

One read-only reviewer ran against Phase 12C (its 12C budget is spent). It independently re-ran the
JVM suite, the device suite, lint and the APK build before reporting. Every finding was re-checked
against the code and the Python reference rather than taken on trust:

| # | Finding | Disposition |
|---|---|---|
| H-1 | The handled-skip wrote an in-flight row into the deleted-skip history, so a later `FAILED`/`CANCELLED` retry would look deliberately deleted | **Code fixed**: new `EpisodeDownloadQueue.isEpisodeOutputPresent` (row `COMPLETED` **and** output present = Python's `tmp_orch._find_existing`, `watchlist.py:1015-1019`); backfill now uses it. JVM: `an in-flight download is skipped but never enters the deleted-skip history`; PARITY §12.2 |
| M-1 | `recoverTitle` preferred the scan cache over the stored title (reference does the opposite) | **Code fixed**: stored title wins, cache consulted only for a 404-style title (`watchlist.py:68-72` / `:815-822`). The cache test's fixture was corrected to a 404 title — under the reference a *session-id* title is unrecoverable, so the old fixture asserted a behaviour Python does not have |
| M-2 | KDoc claimed every snapshotted pref was consumed per entry | **Doc fixed** (`WatchlistViewModel` KDoc table, ROADMAP:667, DECISIONS) + real divergence recorded below |
| M-3 | "per-entry results written to Room" was false | **Doc fixed** (PARITY §12.4, DECISIONS, §33.2): `last_checked` is persisted; the per-entry *result* is session state, exactly as the reference persists only `last_checked` |
| M-4 | PARITY §5.1 stated the gate condition backwards | **Doc fixed**: the gate waits while `resolvedAheadCount() >= resolve_ahead + max_parallel` |
| M-5 | An expired capacity wait was reported as `NEW_EPISODES` (healthy) | **Code fixed**: status is `FAILED`; JVM: `an exhausted capacity bound is reported as FAILED, never as a healthy status` |
| M-6 / L-5 | Claimed PARITY §12.2 marked folder reset ⚠ because Android "prunes to what is on disk" | **Not reproducible**: `grep removeIf` finds no per-number pruning anywhere in `app/src/main` — `WatchlistChecker.kt:226-227` clears the whole entry history, the same rule and granularity as `watchlist.py:987-996`. The row was corrected to say so explicitly (citation widened from `:989-990` to `:987-996`) |
| M-7 | §33.3 implied the 30-minute cap always elapses | **Doc fixed**: the cap only elapses while the staging depth never drops below the bound |
| L-1 | The batch's capacity gate ran *before* the skip/variant checks, and an expiry dropped the un-attempted remainder from the counters | **Code fixed**: ordering now matches the reference (`_find_existing` before any capacity wait, `watchlist.py:1000-1026`), and the expiry publishes `queued/skipped/failed + unattempted` and says how many were never attempted |
| L-2 | Dead local `failures` map in `runBatch` | **Removed** |
| L-3 | Retry KDoc claimed a second permanent marker and a backoff the code did not implement | **Code + doc fixed**: `"No anime UUID"` is now a real permanent marker (`AnimePaheParser:67` raises it) with a JVM test; KDoc now records that Python's per-attempt `asyncio.wait_for(..., timeout=120.0)` (`downloader.py:237-243`) has **no** Android equivalent and that Python's timeout branch backs off `2 * 2**attempt` while Android always uses `1.5 * 2**attempt` |
| L-4 | No test for `FAILED ("Search unreachable")` | **JVM test added**: `an unreachable search is reported as FAILED, never as no session found` |
| L-6 | The episode row persists the *requested* audio, Python stores the *resolved* one | **Documented** — bounded by the row's identity key (below + PARITY §5.1) |
| L-7 | PARITY §12.2 conflated scan failure with `FAILED ("Search unreachable")` | **Doc fixed**: the row is split into *Scan failure* (`FAILED` with the real exception message) and *Unreachable search during recovery* |
| L-8 | Stale comment in `TouchTargetAndFocusTest` | **Fixed** |
| L-9 | `Stop batch` semantics undocumented | **Documented** (PARITY §12.5) |
| L-10 | The device serial was not corroborated by an archived artifact | **Fixed**: `.investigation/media-final/adb_devices_12c.txt` |

**Three bounded divergences recorded by the round (behaviour that is honest but not identical):**

1. **Audio label on the persisted episode row (⚠ display-only).** `queueResolvedEpisode` stores the
   *requested* variant's `audio`, while `downloader.py:277` overwrites `ep.audio` with the
   *resolved* one. Android cannot do the same: `audio` is part of `episodeId`
   (`{animeSession}_{epStr}_{audio}`) and `reEnqueue` re-derives that key from the row's own
   columns, refusing a retry that does not round-trip (`DownloadRepository.kt:929-945`). The
   resolved audio is still surfaced where it is known — `StreamResolverImpl` computes it and the
   confirmation dialog renders `stream.audio` — and the downloaded bytes are identical either way.
2. **`Stop batch` (Android-only affordance).** Python's batch has no in-flight stop control
   (Ctrl-C ends the process). Cancelling on Android stops the submit loop only; rows already handed
   to WorkManager keep running and are not counted as failed or skipped (PARITY §12.5).
3. **Per-entry snapshot keys not yet consumed (⚠ real gap, not cosmetic).** `wl add` snapshots
   `quality`, `audioLang`, `maxParallel`, `autoRetry`, `outputDir`, `hlsWorkers` and `keepTemp`
   (`WatchlistViewModel`, mirroring `watchlist.py:630-665`), but only the first four are read by
   `WatchlistChecker`. The reference builds `ctx_for_check` from **all** of them —
   `output_dir` for the series folder (`watchlist.py:971`) and `hls_workers`/`keep_temp` for the
   orchestrator (`:978-979`) — whereas Android's download path reads the global
   `AppConfig` (`DownloadWorker` logs `Run config: … hlsWorkers=… keepTemp=…`) and the global
   download root. Consequence: changing those three values *after* adding an entry has no effect on
   that entry's auto-download, while in the reference it would. `resolveAhead` is global on both
   sides (`watchlist.py:982`), so it is deliberately not snapshotted.

### 33.9 Verification summary (Phase 12D)

| Gate | Result |
|---|---|
| `:app:testDebugUnitTest` | **619 tests / 0 failures / 0 errors / 0 skipped** (58 suites; +7 for 12D: `WatchlistCheckSchedulerTest` 4, `WebViewPageSerializationTest` 3 — each serialization case fails when the page lock is removed, mutation-checked and then reverted) |
| `:app:lintDebug` | **0 errors / 25 warnings** — unchanged 12C baseline, nothing suppressed. The one restricted API the new scheduler could have tripped (`WorkSpec`) is not touched: `WatchlistCheckScheduler.REQUIRED_NETWORK_TYPE` carries the constraint instead of reading the request's `workSpec` |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, APK 115,514,772 B, SHA-256 `12D201B74A4A911C0F80ACD9D30BE020C55755375B3779FAF34099A533E0CDBA` |
| New instrumentation | `WatchlistCheckWorkerTest` **7/7** — the production factory builds the Worker, an unknown class still returns `null` while `DownloadWorker` still builds, a composition missing the checker fails loudly, a background check persists `last_checked` on a real Room row, an empty watchlist is a successful no-op that issues no request, a watchlist read failure becomes `Result.retry`, and scheduling twice leaves exactly one periodic request |
| Full `connectedDebugAndroidTest` (`REDACTED-SERIAL`) | **271 tests / 1 failure** — only the long-documented `ForegroundWorkerTest` notification baseline. `TriggerNextDeferredEpisodeTest` needed a **test** fix, not a production one: the new permanent periodic work made its global "no unfinished work" precondition false, so setup now cancels the unique work and teardown re-schedules it — no assertion was weakened and no test was removed |
| Deployment (AGENTS §20) | one `device`-state target `REDACTED-SERIAL` → `adb -s REDACTED-SERIAL install -r` **Success** → `pm path com.pahebatcher.app` verified. The install followed AGP's connected-task teardown of both APKs, so app-private data started clean (shared-storage exports untouched) |
| Background check on device | `.investigation/phase12d/device_12d_verify.txt` — schedule idempotence (`count=1` after cold start **and** after `am kill`), exactly one JobScheduler job, three background runs (one forced with a real entry, two unforced), `Checked just now` read back from Room |
| Doc citation checker | **PASS** (`scripts/check-doc-citations.ps1`, exit 0), re-run after every documentation edit above |

### Risk Level
**LOW** — no Phase 12 exit criterion is blocked: the one that was, background checks, shipped in
Phase 12D over the application-scoped WebView transport (DECISIONS 91, §33.2) and is device-verified.
Everything in the phase is implemented, tested and device-verified, with no fake scheduling, no
suppressed warnings and no unverified claim. Carried-forward documented items, none of them exit
criteria: §33.3 (staging bound, not background prefetch), §33.4 (manual relink control, Phase 13),
the three bounded divergences in §33.8, and the long-standing `ForegroundWorkerTest` baseline. *(Update 2026-10-02: §33.4's manual relink control shipped with Phase 13 — the other carried items remain.)*

---

## 34. Phase 13 architecture reviews — settled designs and residual limitations (2026-10-01: first review + final pre-approval pass; **implementation completed 2026-10-02 — the sections below carry in-place shipped/verified annotations, §34.10 holds the implementation close-out residuals**)

Documentation-only reconciliation of findings F-1…F-11 from the first Phase 13 architecture review
(verdict CHANGES REQUIRED), followed by the same-day documentation-only remediation of findings
N-1…N-10 from the final pre-approval review (DECISIONS 103-112). **No production code, tests, UI or
Phase 14 work was written or changed by this section.** The decisions live in DECISIONS 92-112, the
implementation inventory and definition of done live in ROADMAP Phase 13, and §34.1-§34.9 below
record the limitations and divergences that remain *after* those decisions (§34.2 and §34.3 carry
explicit corrections of claims the final review found false), so the final Phase 13 architecture
review can verify them against the plan instead of rediscovering them.

### 34.1 Batch submission is UI-scoped: navigation-away and process death drop the un-submitted tail (F-2, DECISIONS 93 — Option B)

- **Limitation.** The batch loop runs in `viewModelScope` (`AnimeDetailsViewModel.kt:695`). It
  survives configuration changes and tab switches, but back-navigation while `batchRunning` will run
  `cancelBatch()` (`AnimeDetailsViewModel.kt:641`) behind a confirmation warning, and process death
  kills the loop outright. Episodes already handed to WorkManager keep running; episode numbers not
  yet submitted are lost.
- **Why accepted.** SPEC §16 protects *downloads* — those are WorkManager-backed and unaffected.
  No SPEC clause requires the *submission loop* to survive process death, and the Python batch is
  likewise bounded by its interactive process. Option A (persistent submission journal) was rejected
  as a new subsystem duplicating the existing idempotency guards.
- **Recovery.** Re-running the same selection is idempotent — skip-if-handled before resolve,
  queue submission guard, unique-work `KEEP` — so a partial run can be repeated without duplicate
  work. The confirmation warning and the six lifecycle tests are Phase 13 work (DECISIONS 93.3,
  ROADMAP Phase 13), not yet implemented as of 2026-10-01 — **both shipped 2026-10-02**: the
  back-navigation warning runs through both back paths (`AnimeDetailsScreen.kt:125-128`), and the
  six tests pass (JVM `AnimeDetailsBatchLeaveTest` 8/8 covering T1 variants + T4/T5/T6, device
  `AnimeDetailsLeaveBatchScreenTest` 8/8 for the T1 paths, `BatchRecreationTest` 1/1 for T2,
  `BatchPersistenceTest` 1/1 for T3 process-death persistence + T5 rerun-enqueues-nothing-twice).

### 34.2 SAF export: what is and is not guaranteed (F-1, DECISIONS 92 — settled 2026-10-01; implemented and device-verified 2026-10-02)

- **No SAF atomicity claim.** `DocumentsContract.renameDocument` is provider-dependent, so export
  writes an app-owned `<final-name>.pb.part` child and renames it to the final name when the provider
  supports rename (naming/ownership/cleanup/crash-matrix/collision-normalization contract: DECISIONS
  104). *(Corrected 2026-10-01, final review N-2: the previous wording here — "renames only when the
  provider reports rename support; otherwise it writes directly and verifies the byte count after the
  copy. Interrupted exports are detected by byte-count mismatch and re-exported" — is superseded.
  There is no portable pre-flight rename-capability check; capability is discovered at export time,
  and a rename-less provider falls back to the MediaStore default with the tree marked unsupported
  (`output_tree_rename_ok`) rather than leaving a partial file under its final name. An interrupted
  export is finalized when its byte count matches the surviving local source or discarded and
  re-copied — never "re-exported".)* **Residual:** a crash during the last export into a tree can
  leave one `*.pb.part` until the next export start (cleanup triggers are export start and the
  export's own failure path only — no startup tree walk, AGENTS §10). Local assembly stays atomic
  (same-directory rename), which is a property of app-private staging only.
- **Local copy deletion.** The private MP4 is deleted only after byte-verified export (the X6 rule,
  `MediaStoreExporter.kt:49`); until Phase 13 ships, MediaStore remains the only export target.
  *(Superseded 2026-10-02: Phase 13 shipped the SAF export target — the same byte-verified-before-
  delete rule runs in `SafExporter.kt:209-216` before the X6 local delete; MediaStore remains the
  default/fallback target.)*
- **Revocation / user-deleted export (corrected 2026-10-01, final review N-1 → DECISIONS 103).**
  A revoked/unreachable tree falls back to the MediaStore default for **future exports** with an
  explicit "output folder inaccessible" state; an already-exported child URI that no longer resolves
  (user deleted the file, tree moved/deleted, persisted permission invalid, provider
  `SecurityException`) is an invalid §29 row — reconciliation demotes it to FAILED "file missing"
  (`DownloadStateReconciler.kt:182-197`, `:300-310`, `:319-340`). It never silently completes as a
  successful download, never auto-re-downloads, and cannot be "re-exported" — X6 already removed the
  private source (`MediaStoreExporter.kt:49`). Playback tells the truth:
  `PlaybackSourceResolver.kt:22-24` returns the present `outputUri` without probing resolvability,
  so an inaccessible URI fails at open with no `outputPath` fallback (none exists post-X6); the
  recovery path is explicit Retry, which re-enters the normal download pipeline and exports to the
  then-current destination. **Re-grant semantics = Option B:** granting or switching SAF trees
  affects future exports only and never resurrects FAILED rows. *(The previous wording here claimed
  the child URI "degrades through `PlaybackSourceResolver.kt:22` (bare `outputPath`, else `null`)" —
  false against the resolver and X6.)* A persisted `takePersistableUriPermission` survives restart
  but never re-materializes a moved or deleted document.
- **Transient cost.** Private-then-export holds two copies of one MP4 (staging + destination) until
  the X6 delete — the price of never risking user media on an interrupted export.
- **Collisions/deletion.** Existing user files in a SAF tree are never overwritten or deleted:
  reuse on byte-size match, else a uniquified `name (n)` child. Session deletion, clear-cache,
  cancel and retry touch only Room rows and app-private directories — never exported media.
- **Verified in Phase 13 (2026-10-02)** — was "Unverified until Phase 13": SAF-child-URI playback
  and every SAF path end-to-end are now covered by the DECISIONS 92.8 test list —
  `SafPlaybackDeviceTest` 2/2 (real SAF child plays on `REDACTED-SERIAL`), `SafExporterTest` 9/9,
  `SettingsOutputLocationTest` 7/7, `OutputTreeRepositoryTest` 8/8, `ExportDestinationRouterTest`
  5/5, `DownloadStateReconcilerTest` 16/16 (revocation demotion, no resurrection),
  `SessionActionsTest` 15/15 (session deletion never reaches exported media).

### 34.3 Sessions actions: Pause/Resume offered (2026-10-05 - DECISIONS 129 supersedes DECISIONS 94.2's "Pause intentionally unsupported"); Clear-all-cache keeps session records (F-3, DECISIONS 94 - screen shipped 2026-10-02: SessionsScreenTest 19/19 incl. pauseAndResumeAllAreBoundToTheCallersLambda, SessionsNavigationTest 2/2, SessionActionsTest 18/18 incl. pauseSession_targets_only_pausable_rows)

- **Pause/Resume is now offered** *(superseded 2026-10-05, final SPEC gap closure - DECISIONS 129)*:
  the episode state machine carries a durable `STATE_PAUSED`, written by `DownloadRepository.pause()`
  (`DownloadRepository.kt:552`) **before** the unique work is cancelled and claimed back to `QUEUED` by
  `DownloadRepository.resume()` (`DownloadRepository.kt:570`); WorkManager still has no pause primitive,
  so pause is cooperative and `ASSEMBLING` cannot be paused (LIMITATIONS §38.4). The Python reference
  exposes no Pause action - its "Paused" is a display literal (`sessions.py:75`) - and `SessionRepository.setState` (`SessionRepository.kt:101`) is unchanged: the new PAUSED is episode-level, never a session state.
- **Clear all cache is temp-only** — Room session/episode rows are retained — diverging from
  Python's confirmed `shutil.rmtree(cache_dir)`, which also destroys session metadata
  (`sessions.py:94`), because Android's authoritative session state is Room (SPEC §28).
- **Delete temporary cache is disabled while a session has non-terminal episodes** (deleting
  segments mid-flight would silently break resume).
- **Delete session and Clear all cache are terminal-only too (2026-10-01, final review N-4 →
  DECISIONS 106):** Delete session is enabled only when every episode row of that session is
  terminal (otherwise the action is disabled and Cancel-first is the prerequisite); Clear all cache
  is enabled only while **no** episode row anywhere is non-terminal — no cache clearing while
  Workers are writing. Cancellation-first deletion was rejected: `DownloadRepository.cancel`
  (`DownloadRepository.kt:856-866`) cancels the unique work asynchronously, so a cancel-then-delete
  flow could race a final segment write against directory deletion. Removal order is filesystem
  first, then the Room row (cascade), so a crash mid-delete is safely repeatable.
- **Cache size** counts only temporary artifacts — `*.ts`, `*.tmp` and `concat.txt` under the
  episodes' `segmentDir`s (exactly the `SegmentFileStore.kt:168` cleanup set) — with `*.mp4`
  **excluded**, because Android stages the assembled MP4 inside `segmentDir` until X6 while Python's
  MP4s live in `output_dir` outside the walked cache folder (`downloader.py:54`). MediaStore/SAF
  media and `*.pb.part` files in the user tree are never queried; no schema change is needed
  (DECISIONS 107). *(Corrected 2026-10-01, final review N-5: the previous "mirroring
  `sessions.py:34`" claim was imprecise — parity is in meaning (temporary cache only, media
  excluded), reached structurally in Python and by explicit `*.mp4` exclusion on Android; the file
  sets differ trivially because Python's walk includes its `session.json`.)*

### 34.4 Verification attribution — four distinct claims (F-10, DECISIONS 101)

1. *Emulator gate* (`emulator-5554`, x86_64/API 36) — the Phase 9/10/11 verification platform.
2. *Physical device* (`REDACTED-SERIAL`, REDACTED-MODEL, arm64) — `TEST-REDACTED-MODEL - 16.xml` (2026-09-30): 271 tests /
   1 documented `ForegroundWorkerTest` baseline, with `PlayerLifecycleTest` 8/8 including
   `completedEpisodePlaysRealLocalMp4EndToEnd` (`PlayerLifecycleTest.kt:91`).
3. *Playback source kind* — bare-path local MP4 (`outputPath`, `outputUri = null`) is verified on
   device; MediaStore `content://` playback is verified through the Phase 11 export evidence chain;
   SAF-child-URI playback is **not** verified (Phase 13, DECISIONS 92.8). *(Superseded 2026-10-02: verified on `REDACTED-SERIAL` — `SafPlaybackDeviceTest` 2/2; the bare-path and MediaStore claims above remain as stated.)*
4. *Build/lint results* are never offered as runtime verification.

PARITY's Phase 11 playback bullet keeps its historical emulator wording and now carries the device
annotation. Future claims must name platform + source kind + artifact.

### 34.5 Phase ownership ledger (F-4/F-5/F-9, DECISIONS 95/96/100)

- **Phase 13 owns:** the `POST_NOTIFICATIONS` runtime permission prompt at enqueue (contract
  documented at `NotificationPermissionRepository.kt:25`), the notification permission-status row,
  the output-location row + SAF export target, the Wi-Fi-only row + `NetworkType.UNMETERED`
  constraint at the three download enqueue sites (`DownloadRepository.kt:1191`,
  `DownloadWorker.kt:994`, `DownloadStateReconciler.kt:388`) — enqueue-time only: existing
  queued/running WorkRequests keep their constraints and are never rewritten (DECISIONS 105) — the
  Sessions screen, manual relink,
  batch lifecycle UX + its six tests, offline indication, and the cross-cutting Material 3 /
  accessibility / error-state / `@Immutable` completion. **All shipped 2026-10-02 — evidence in
  the ROADMAP Phase 13 exit criteria.**
- **Phase 14 owns — all five items SHIPPED 2026-10-03:** completion/failure/watchlist notifications, channel polish +
  per-type channel preference rows, MediaSession, auto-resume downloads, and the watchlist check-frequency setting (evidence: ROADMAP Phase 14 exit criteria).
- **Intentional divergences:** settings wizard, `cli_set`, `cookie_string`. **Intentional N/A:** the
  SPEC §24 suggested screen #12 "Web/session authentication screen" (DECISIONS 102).
- **Already shipped, no longer pending:** range/all-episodes entry and Settings-driven quality
  (Phase 12C), engine settings (Phase 12A), theme/dynamic color (Phase 11).

### 34.6 Remaining postures: offline scope (F-6), Compose stability (F-7), English-only UI (F-8)

- **Offline:** the future `NetworkMonitor` reports connectivity only — it never pauses or resumes
  downloads (auto-resume shipped in Phase 14, 2026-10-03); `UNKNOWN` before the first callback is treated as online so
  no false offline banner can show (DECISIONS 97). **Shipped 2026-10-02:** `NetworkMonitor` +
  per-surface `OfflineBanner` implemented — JVM `NetworkMonitorOfflineTest` 6/6
  (UNKNOWN/ONLINE/OFFLINE/restore) + device `OfflineBannerTest` 8/8; the banner is its own merged
  semantics node with a polite live region (`OfflineBanner.kt:34-41`) so TalkBack announces only
  the offline status, not the whole screen.
- **Compose:** `@Immutable` is added only where construction sites were verified; no Paging
  dependency by decision (revisit only on a measured jank regression); sliders commit their
  DataStore write on gesture finish; the console stays bounded at 500 entries / 4000 chars (DECISIONS
  98).
- **Localization:** production is intentionally English-only; if a locale is ever added it must ship
  complete, never partial (DECISIONS 99).

### 34.7 Manual relink interaction contract (N-6, DECISIONS 108 — control shipped 2026-10-02: WatchlistScreenTest 11/11 incl. relink cases, JVM WatchlistRelinkTest 7/7, Phase13ResponsiveTest 12/12)

- **Exposure rule:** the per-row Relink action is available on **every** watchlist entry, because
  `WatchlistEntity` persists no per-entry status (only `lastCheckedAt`,
  `WatchlistEntity.kt:14-56`) — gating on "dead" status would require a schema change, rejected
  (AGENTS §6). Honest outcome reporting makes the over-exposure harmless.
- **Flow:** tap → the automatic path's own candidate search (`WatchlistChecker.kt:600`, shared,
  never a second search implementation) → a selectable 0/1/n-candidate dialog (0 → honest "no
  session found", entry unchanged; 1 → pre-selected but still requires Confirm; n → Confirm disabled
  until one is chosen) → `WatchlistRepository.relink(entry, candidate, host)`
  (`WatchlistRepository.kt:113`) → the standard check re-runs on the re-keyed entry (post-relink
  rescan, `WatchlistChecker.kt:213`) → migrated-but-still-dead keeps the new link and reports the
  error (`WatchlistChecker.kt:214-216`).
- **Divergences:** Python's global relink-all mode and the `wl relink <id> <new-url>` fallback are
  not ported; zero-candidate/unknown-title recovery stays the remove-and-re-add-by-URL flow (shipped
  in 12C, PARITY §12.5).

### 34.8 Sessions screen entry point and display state (N-7/N-9, DECISIONS 109/110)

- **Entry point:** a top-bar action on the Downloads screen navigating to `SessionsRoute` with
  `launchSingleTop` — the existing Home top-bar pattern (`HomeScreen.kt:99`/`:105`,
  `AppNavHost.kt:156-174`); the Downloads `TopAppBar` currently carries only its title
  (`DownloadsScreen.kt:62`), and the documented two-tab bottom bar is untouched (PARITY §12.5).
  Standard back pop; the existing type-safe route restoration applies; Resume navigates to
  `AnimeDetailsRoute(session)` per DECISIONS 94; no deep links (none exist for any destination).
- **Display state:** an exhaustive first-match-wins precedence over the existing five persisted
  states — no episode rows → CREATED; any non-terminal row → DOWNLOADING (covers CANCELLED+active);
  all COMPLETED → COMPLETED; all terminal with ≥1 FAILED → FAILED; all terminal, 0 FAILED, not all
  COMPLETED (all CANCELLED, or COMPLETED+CANCELLED) → FAILED meaning "terminal and not fully
  complete", with row-level CANCELLED remaining the truthful detail; PAUSED never derived
  (DECISIONS 109). No `CANCELLED` column, no schema change, `SessionRepository.setState` gains no
  production caller.
- **Limitation:** the five-state vocabulary cannot say "stopped by the user" at the session level —
  that truth lives in the row states, which the screen displays verbatim. **(Shipped 2026-10-02:
  entry point and precedence state implemented — SessionsScreenTest 18/18, SessionsNavigationTest
  2/2, SessionActionsTest 15/15; the five-state limitation above still stands.)**

### 34.9 Responsive / large-screen posture (N-10, DECISIONS 111)

- **Verified today:** responsive Scaffold considered at initial layout (ROADMAP:48) and player
  multi-window/orientation behavior (Decision 53, `PlayerLifecycleTest`). **Not verified today:**
  systematic large-screen/foldable/split-screen behavior of any existing screen — no claim is made.
- **Chosen posture — Option A:** Phase 13 makes responsive verification of **its new surfaces**
  (Sessions, dialogs, offline banners, settings additions, manual relink, batch confirmation,
  output-location UI) a Phase 13 exit criterion: compact + expanded width (≥600 dp), split-screen
  resize, and default + largest font scale, recorded as evidence alongside the accessibility tests
  (ROADMAP Phase 13 exit criteria).
- **Why acceptable:** SPEC.md:92-103 demands the consideration from the beginning, not a
  pre-existing verification; layouts use fill/weight constraints rather than hard-coded phone
  widths, so the criterion is a verify-and-correct pass rather than a retrofit redesign. The
  pre-existing screens' phone-first-only posture is stated here rather than papered over. **Shipped
  2026-10-02: the Option-A criterion ran and passed — Phase13ResponsiveTest 12/12
  (compact/expanded/split-screen/font-scale on the new surfaces), TouchTargetAndFocusTest 5/5,
  AccessibilitySemanticsTest 6/6, SettingsContentTest 3/3, OfflineBannerTest 8/8,
  WatchlistScreenTest 11/11; §34.10 below records the one width-axis boundary and the harness
  preconditions.**

### 34.10 Phase 13 implementation close-out — residual notes and test-harness preconditions (2026-10-02)

Annotations for the implementation pass (four slices; JVM 692/0/0, device 383/0/0/0 on `REDACTED-SERIAL`,
lint 0 errors / 32 warnings, `@reviewer` APPROVED, `@tester` PASS). Nothing below is a behavioral
defect in the shipped code; each entry is either a deferred low-risk follow-up, an Android platform
constraint, or a test-harness precondition the suites depend on.

- **LOW, deferred with reviewer sign-off — Settings `renameOk` projection race (N-1).** The
  projection collector (`SettingsViewModel.kt:186-193`) and the resume-time `refreshOutputLocation`
  assignment (`SettingsViewModel.kt:322-327`) can interleave: a flag flip landing while a
  `validate()` is in flight is clobbered by the stale `Valid` assignment carrying the old
  `renameOk` until the next resume or DataStore write. The export path is unaffected because it
  re-reads the flag fresh (`OutputTreeRepository.kt:95`, `ExportDestinationRouter.kt:41-42`), so
  exports always see the real capability; only the Settings row description can lag briefly, and it
  self-heals on the next validation cycle. Renaming the `renameOk` writer (N-1's suggested fix) is
  deferred to the next iteration.
- **Android platform constraint — revoking a runtime permission force-stops the app.** Revocation
  while the process runs triggers an `ActivityManager` force-stop, so the permission tests are
  ordered denied → granted only and never restore the permission by revoking it
  (`FirstEnqueuePromptTest.kt:47-59`). This is an OS behavior, not an app defect.
- **Android platform constraint — dialog window width.** A Compose `Dialog` is its own system
  window; its width follows the host window at the platform level and cannot be driven from inside
  a parent layout, so `Phase13ResponsiveTest` verifies the font-scale axis and constraint-driven
  content of the dialogs (`Phase13ResponsiveTest.kt:54-58`) while width behavior is the platform's.
- **Test-harness precondition — display rotation must stay locked.** The test device (`REDACTED-SERIAL`)
  had `accelerometer_rotation` enabled, and a sensor-driven flip to landscape mid-run changed the
  metrics for screens that compose their lists from width constraints, failing four layout-sensitive
  UI tests across the affected runs while the rest passed. Connected runs now set
  `settings put system accelerometer_rotation 0` and `settings put system user_rotation 0` before
  starting; with the lock, all 383 tests pass. The tests themselves are unchanged — this is device
  setup, recorded so a future run with rotation unlocked is not misread as an app regression.
- **Test-harness precondition — first-enqueue prompt latch in the batch suite.** Because the device
  screen times out during the minutes-long download, a real one-time prompt would launch deferred on
  the first frame after completion and pause MainActivity mid-assertion; `BatchDownloadDeviceTest`
  pre-sets the asked-once latch to the realistic "user already answered" state in `setUp`
  (`BatchDownloadDeviceTest.kt:83-93`) with every batch assertion unchanged.
- **Documentation accuracy — SAF playback fixture (N-2).** `SafPlaybackDeviceTest` verifies the
  post-export playback path by planting a real child document in a provider tree and seeding the
  completed episode row with that `outputUri` (fixture-level seed, not a full export round-trip);
  claims must describe it that way. The export round-trip itself is covered by `SafExporterTest`
  and `SettingsOutputLocationTest`.
- **Documentation accuracy — Home destination line (N-3).** The Home screen's static line said
  `Videos are saved to Movies/PaheBatcher/` unconditionally (`HomeScreen.kt:149-156` today); with
  a Valid rename-capable SAF tree selected, exports go to the tree instead — which the line still
  does not say. Gate A 2026-10-08 reworded the path half to `Movies/<anime title>/` (DECISIONS 141,
  §40); the SAF-tree half stays deferred (Settings states: `SettingsScreen.kt:733-746`).
- **Known lint hint (not a warning, not new).** The single lint hint of this phase's run is
  `AutoboxingStateCreation` on the slider-gesture holder (`SettingsScreen.kt:702`), pre-existing
  from Decision 98's slider persistence pattern; warnings stayed at the 32-line baseline.

---


### 34.11 Phase 14 close-out - accepted limitations and implementation deviations (2026-10-03)

- **P14-L1 - notification ID namespace:** one int space for all notification IDs. media3's fixed positive
  notification ID can theoretically collide with an episode progress hash (negligible probability while a media
  session is active) and watchlist `-9001` can theoretically collide with a terminal ID - replacement-only impact,
  self-healing on the next post, no data impact; PendingIntent request codes share the same int space
  (progress/terminal `episodeId.hashCode()`, watchlist `-9001`, player `-9002`). Accepted: removing it would mean
  mutating media3's provider or the established `notificationIdFor` contract for negligible gain.
- **P14-L2 - enqueue-time constraint semantics:** toggling auto-resume (or Wi-Fi-only) never rewrites
  already-enqueued requests (`WifiOnlyConstraint.kt` contract, DECISIONS 105); the connectivity gate applies to
  requests enqueued after the toggle.
- **P14-L3 - attempt-budget reset:** a reconciler-built fresh request resets `runAttemptCount`; reachable only
  when the previous request vanished (never caused by transport failures), bounded per request lifetime by the
  same 8-run rule (device-verified by `ProcessDeathRecoveryTest.rescheduledRequest_runAttemptCount_...`, PHASE14_PLAN
  row 421).
- **P14-L4 - channel stickiness:** the notification preference rows gate *posting*, not sound/importance;
  importance is fixed at channel creation and changeable only in system settings (the existing "Open settings"
  affordance).
- **Implementation deviation (row 419, connectivity-severing test):** the sever is applied **at the fixture
  origin** (a loopback HTTP server the test controls) rather than at the device radio - instrumentation on this
  API 36 device has no shell/root path to toggle connectivity. This required a **debug-scoped**
  `network_security_config.xml` allowing cleartext to `127.0.0.1`/`localhost` only; release manifests never
  reference it and the production network path is unmodified. The test still exercises a REAL mid-download
  transport failure, REAL park, REAL backoff and REAL automatic completion with zero re-download.
- **Implementation deviation (row 421, process-death attempt-count):** the instrumentation suite executes in the
  app's own process, so a true `am force-stop` cannot be staged without aborting the run (section 20 above).
  The test therefore uses a REAL WorkManager request with real execution, real transport failure, real
  `Result.retry()` park, real WorkManager reschedule with advanced `runAttemptCount` and real reconciler
  recreation, while only the kill step uses the class's documented simulated-death technique. The in-suite row
  therefore asserts only the *persistence* of the count across that simulated reschedule; the death-interrupted
  increment itself is now asserted by the EXTENDED 11.5E-6 operator protocol (reviewer round-2 finding H-1):
  the seed step records a pre-death baseline (`runAttemptCount == 1`, PID, request id) while the worker runs,
  the operator `am force-stop`s the package from outside the instrumentation (the section-20 operator path —
  only in-process staging is prohibited there), relaunches via `am start`, and
  `RealProcessDeathCleanupTest -e verifyAttemptCount true` asserts a different PID, the SAME request re-run
  with an advanced `runAttemptCount` inside the bound. Executed 2026-10-04 on device REDACTED-SERIAL: seed PID 18134
  force-stopped mid-run, relaunch (PID 18195) logged 01:27:41, verify PID 18271, request
  `1465b048-d249-4716-b447-1eef55f21cfd` re-run observed with advanced count — `OK (1 test)`
  (TestRunner/logcat 01:27:33–01:28:03). Re-run with a persisted evidence capture 2026-10-04 13:42 (reviewer
  round-3 finding NEW-3): the same protocol executed green again — seed death signal
  `INSTRUMENTATION_RESULT: shortMsg=Process crashed.`, pre-kill PID 32223, `pidof` empty after `am force-stop`,
  relaunch PID 32309, cleanup `-e verifyAttemptCount true` → `OK (1 test)` — with the full timeline, both
  instrument outputs and a 1956-line logcat (`Starting download work for ep-real-pd` at 13:42:02 pre-kill and
  13:42:32 post-reschedule) persisted under `.investigation/phase14/`.
- **Unchanged open gate:** reboot continuation remains manual (section 24, R5) - Phase 14 claims
  connectivity-resume only, never reboot-verified continuation.

---

## 35. Phase 15 final verification — environment, external-service and test-suite findings (2026-10-04)

**Scope.** Everything below is a **test-environment, external-service or test-suite** item raised by the Phase 15 final
verification gate. **None of it is a product defect**, none is counted against product behavior, and none of it is used
to rewrite a test result. Full-suite numbers are recorded as reported — emulator **413 tests / 7 failures / 0 errors /
0 skipped**, physical `REDACTED-SERIAL` **413 / 1 / 0 / 0** — and isolated re-runs are recorded **separately**:

| Isolated re-run | Result |
|-----------------|--------|
| `CancellationTest` | 2 tests / 0 failures |
| `MediaSessionLifecycleTest` | 1 test / 0 failures |
| `SettingsOutputLocationTest` | 7 tests / 0 failures |
| `ExampleInstrumentedTest` | 1 test / 0 failures |

### 35.1 Emulator AnimePahe search blocked by an external Cloudflare challenge — EXTERNAL SERVICE

The emulator's search/details/episode-list requests returned a constant 260-character Cloudflare challenge body, which
`AnimeRepository.searchWithOutcome` classifies as `Challenge` and renders as "No results found.". The **same build** on
the physical device returned a 2606-character JSON body with 8 results and full functionality. This is an external
service refusing the emulator's network path, **not a search regression**. Unresolved and not fixable from the app.

### 35.2 `connectedDebugAndroidTest` uninstalls the app under test — TEST ENVIRONMENT

See §13, where the Phase 15 before/after experiment pinned the trigger to the connected test task itself. Practical
consequence: **reinstall the debug APK before any manual verification that follows a connected run**, and expect
default settings afterwards because the package data went with the package.

### 35.3 Emulator full-run failures (7) — classified

| Test(s) | Count | Classification | Isolated / cross-device evidence |
|---------|-------|----------------|----------------------------------|
| `FirstEnqueuePromptTest` | 3 | **TEST / ENVIRONMENTAL** — its baseline assumes a GRANTED `POST_NOTIFICATIONS` state, which a non-fresh install does not provide (`pm revoke … android.permission.POST_NOTIFICATIONS` restores the precondition) | Precondition-driven, not a product failure |
| `BatchDownloadDeviceTest` | 1 | **EXTERNAL SERVICE** — Cloudflare, same root cause as §35.1 | Passes when the network path is reachable (previously green on `REDACTED-SERIAL`) |
| `MediaSessionLifecycleTest` | 1 | **Interference** | 1/0 isolated; green in the hardware full run |
| `SettingsOutputLocationTest` | 7 | **Interference** | 7/0 isolated; green in the hardware full run |
| `WatchlistCheckWorkerTest` | 1 | **TEST isolation gap** — `setUp` does not reset `notify_watchlist`, so an earlier test's value leaks in | Green in the hardware full run |

### 35.4 Physical-device full-run failure (1) — classified

`CancellationTest` failed once inside the hardware full run (its 20-second wait for the `RUNNING` state under full-suite
load) and passed **2/0** in isolation. The same timing flake is long documented (Phase 11.5E-7 roadmap row, "focused
re-run 1/0"). **Test-side nondeterminism; no product instability observed.**

### 35.5 No live dual-audio upstream title for a positive variant-selection sample

Among the titles checked on the device (*Tomb Raider King*, *Kurozuka*, *…Slimes S2*) none exposes two audio variants
for the same episode, so a positive live sample of the Sub/Dub path could not be staged. The code path is covered by
JVM `dualAudioPage` fixtures; the single-variant behavior itself was verified live (`audio=eng` under both preferences).

**Re-checked 2026-10-08 (audio-slice gate):** *Tomb Raider King* again showed 12 releases across 12 episodes
(`eng` eps 1–11, `jpn` ep 12) — per-episode variance ACROSS numbers, still zero titles with two variants for the
SAME number. The one behavior this keeps from live exercise is the same-episode variant *switch* (tapping a different
variant of a selected number in batch mode); everything around it is live (options display with zero play-page
fetches, exact request → exact resolved audio in logcat, bulk default, chosen-row deselect) or test-covered
(`AnimeDetailsBatchScreenTest` exact-pair contract 15/15 on device, `StreamResolutionValidationTest` exercising a
genuine decline against a real page when the requested audio is absent). *(The "options display with zero play-page fetches" clause is **Superseded in part by Decision 144**: the screen's option set is now discovered from a bounded play-page probe (at most merged sessions + 1 page requests per load, cached in the scan-cache row), so listing-only option display with zero play-page fetches no longer describes the shipped behavior. The still-missing positive live same-episode variant sample and Decision 144's representative-sampling limitation stand.)*

### 35.6 Two settings consumers verified by test only, not by a live log line

`cache_ttl` (Scan cache TTL) and `resolve_ahead` (Resolve ahead) have **no production log line**, so their consumers
were verified with passing production-path tests instead. They are recorded as **TEST-verified** in `PARITY.md` §9.6 and
are deliberately **not** upgraded to live-verified.

### 35.7 Deferred test hardening (non-blocking, no phase opened for it)

1. Reset `notify_watchlist` in `WatchlistCheckWorkerTest.setUp`.
2. Reduce cross-run interference affecting `MediaSessionLifecycleTest` / `SettingsOutputLocationTest`.
3. Make the fresh-install precondition explicit for `FirstEnqueuePromptTest`.

These are the three residuals behind the Phase 15 "No flaky tests" exit criterion, which is recorded in
`ROADMAP.md` as **met with documented residuals - not a claim of zero nondeterminism**. Product-side flakiness: none
observed.

**Status update (2026-10-08, audio-slice run):** items 1–3 remain open and materialized as predicted. The full
483-test run produced 3 `FirstEnqueuePromptTest` precondition assumption-violations (item 3's exact scenario —
re-run isolated on a fresh install: 4/4 green), plus timing-class failures in `CancellationTest` (isolated 2/2) and
`MediaSessionLifecycleTest` (isolated 1/1, item 2's interference class), plus a row-COMPLETED → export →
work-SUCCEEDED race in `AutoResumeConnectivityTest` whose one racy WorkInfo read was root-caused from logcat
timestamps and bounded with a 60 s await (the assertions themselves are unchanged; isolated 2/2). Every failure was
classified **not product**, and none of the three hardening items was implemented — still no phase opened for it.

---

## 36. Streaming / direct HLS playback is not implemented (found in Phase 16, 2026-10-05) — **RESOLVED in Phase 17 (2026-10-05)**

> ### ✅ RESOLVED — Phase 17 (2026-10-05)
> The limitation below was real when written and is now **closed**: Phase 17 added `androidx.media3:media3-exoplayer-hls`
> and `androidx.media3:media3-datasource-okhttp` **1.11.1** (through the existing `media3` version key, no other
> toolchain change) and built the direct playback path — `PlayerMode.STREAM` → `StreamEpisodeReconstructor`
> (Room row → unexpired scan cache → network scan) → just-in-time `StreamResolver.resolve()` → `HlsMediaSource` over
> OkHttp carrying `StreamInfo.toHeaders()` → per-stream `setMediaSource(...)` on the one existing ExoPlayer.
>
> **Verification (physical `REDACTED-SERIAL`, real network, real Cloudflare session, no fixture)**: `resolve(quality=1080,
> audio=eng)` → `hostPath=vault-13.owocdn.top/stream/…/uwu.m3u8`, `referer=kwik.cx/e/xChstKfzzw4i` → Media3
> `STATE_READY`, `durationMs=6601462` parsed from the real media playlist → playhead `0 → 2919 ms` over 3 s →
> `playerError=null` (`Phase17DeviceStreamingVerificationTest`). Headers proven **on the socket** for the playlist, the
> AES-128 key and the segment through the real playback stack (`StreamHlsHeaderTest`, emulator + physical).
> JVM `testDebugUnitTest` **766/0/0/0**, `lintDebug` **0 errors**, both APKs built.
>
> The rows below are retained as the record of what was found and corrected. **The "Android-Native Alternative
> (not implemented, no phase opened)" section is superseded — it is now implemented.** The genuinely remaining
> streaming-related limitations are in **§37**.

### Limitation (as found in Phase 16)
`SPEC.md` §15 (STREAMING) requires Media3 to replace desktop MPV for **HLS playback**, and `SPEC.md` §39 lists
"*Streaming works.*" among the final acceptance criteria. Python supports it directly — `--stream/-s`
(`main.py:76`), `run_stream(...)` (`main.py:404-405`, `stream.py`) — MPV plays the resolved HLS playlist without
downloading the episode. **The Android app has no equivalent path.**

### Evidence (source inspection, 2026-10-05)
- `PlaybackSourceResolver.sourceFor()` returns `outputUri` (MediaStore `content://`) ?: `outputPath` (the assembled
  MP4) ?: `null` — its KDoc states "*The player plays local media only*". It never returns a network or `.m3u8` URI.
- `PlayerViewModel.load()` builds its playlist from `DownloadRepository.getCompletedPlaylist(sessionId)` and seeds the
  route from `PlayerRoute(episodeId)`; the only UI entry to the player is the **Play** button on a completed Downloads
  row.
- **No `HlsMediaSource` and no other network media source exists anywhere in `app/src/main`** (0 matches), and
  `gradle/libs.versions.toml` declares only `media3-exoplayer`, `media3-ui-compose-material3` and `media3-session` —
  **`media3-exoplayer-hls` is not a declared dependency at all**, contrary to what several documents claimed.
- Consequence: a resolved stream can only be *downloaded*, then *played* from disk.

### What this is NOT
- **Not** a test-environment or external-service issue: the previously recorded reason for the open Phase 11
  "HLS playback works" item — *kwik.cx Cloudflare WAF* — was **RESOLVED in Phase 11.5E-4** (§2B), so that blocker no
  longer explains anything.
- **Not** a deliberate, recorded deferral: no `DECISIONS.md` entry decides against streaming.
- **Not** faked anywhere: nothing in the UI or the logs pretends to stream, and no placeholder path exists
  (AGENTS §5 honoured).

### Documentation corrected in Phase 16
- `PARITY.md` §11.1 `HLS playback` row: `ExoPlayer / 🔄 Different API` → **❌ NOT IMPLEMENTED**, with the source
  evidence above.
- `PARITY.md` Phase 11 Completion Status, *HLS playback* bullet: "not exercised against live streams — `media3-exoplayer-hls` pinned 1.11.1" → corrected
  (module absent; the gap is implementation, not verification scope).
- `ROADMAP.md` Phase 11 criteria (the `[~]`/struck-through *HLS playback* lines): stale "module pinned / EXTERNALLY
  BLOCKED" reason annotated with a Phase 16 note; the criterion stays **not claimed**.
- The other architecture records (`PHASE11_ARCHITECTURE_V1/V2`) keep their original wording as historical documents.

### Affected Features
- SPEC §15 STREAMING (HLS playback; also the section's "Do not download an entire episode merely to implement
  streaming" rule)
- SPEC §39 final acceptance criterion "*Streaming works.*"
- PARITY §11.1 (`HLS playback` row)

### Android-Native Alternative — **implemented in Phase 17**
Add `androidx.media3:media3-exoplayer-hls` and feed the resolver's `.m3u8` URL to `MediaItem.fromUri(...)` through a
`DataSource.Factory` that reuses the app's existing session headers (`Referer`, cookies) — the same transport the
downloader already uses for segments and keys. Phase 16 was documentation-only and was explicitly forbidden from
starting another phase; **Phase 17 built exactly this** (`HlsPlaybackSourceFactory` + `PlayerMode.STREAM`), plus the
reconstruction ladder, just-in-time resolution and bounded user-driven retry the brief required.

### Status
**RESOLVED (Phase 17, 2026-10-05)** — feature implemented and device-verified; see the resolution box at the top of
this section. The Phase 16 verdict `PROJECT NOT CLOSED — DOCUMENTATION EXIT CRITERIA UNMET` was evaluated against
Phase 16's documentation-only deliverables and stands for that phase; the underlying product gap is now closed.

### Risk Level
**CLOSED** — was MEDIUM. The core search → scan → resolve → download → play use case was always unaffected; the
watch-now-without-downloading mode now exists and the specification/build gap for streaming is closed.

---

## 37. Remaining streaming limitations after Phase 17 (2026-10-05)

### Limitation
Phase 17 closed the streaming gap (§36). These are the **genuine, still-open** streaming-related limitations — none of
them is a fake or an unverified claim; each is stated so the phase's boundaries stay visible.

1. **Position retention across a resolve → source swap is proven only at policy level.**
   A STREAM entry that already has a saved position seeds `startPositionMs` in its `MediaItem`; a retry that re-resolves
   creates a fresh `MediaItem` and re-seeds the same saved position. The *policy* is asserted by JVM tests
   (`StreamRetryPolicyTest`), and the physical run did confirm playback starts at the requested position, but a full
   end-to-end observation (seek mid-episode → network error → user retry → playhead restored) was not exercised on
   device. **Not claimed as device-verified.**

2. **One entry point, and now real next/previous navigation** *(superseded in part by the final SPEC
   gap closure — §38.1–§38.3)*: streaming still starts from Anime Details for a single episode, and
   there is still no multi-episode stream playlist and no `PlaybackEntry` hierarchy — but "stream
   next" / "stream previous" now exist (DECISIONS 130): the player switches to the session's
   neighbouring episode **in place**, bounded by a sibling list read once per entry, with both skip
   controls disabled rather than fake when that list cannot be read (AGENTS §5). Absent on purpose,
   with no placeholder control anywhere: a queue of episodes to stream and in-player quality/audio
   switching (§37.3). Subtitles were on that list too until 2026-10-06 — the in-playback text-track
   control shipped that day (§37.4, DECISIONS 133), so subtitles no longer belong to this clause. The
   local-MP4 playlist behaviour is untouched.

3. **No in-player quality or audio switching while streaming.**
   A stream resolves once at start with `configuredQuality()` + `episode.audio`. Changing quality or audio mid-playback
   requires leaving the player and streaming the episode again (or downloading it). This is the pre-existing §11.1
   platform difference — Python rebuilds the playlist inside MPV; Android resolves before playback. It was out of scope
   for Phase 17 (TrackSelector/audio switching).

4. **Subtitles are in-playback text-track selection only.**
   The control ships, but only while the current media declares at least one *named* text rendition (manifest `NAME` else
   `LANGUAGE`): a media that declares none publishes no button at all — deliberate, so nothing pretends (AGENTS §5). What is
   deliberately NOT built: subtitle download, persistence of the choice across entries or app restarts, translation, and any
   custom rendering; the choice is held for the current entry and re-applied when a source swap changes the tracks. The device
   evidence (`PlayerSubtitleControlTest` 8/8, `SubtitleFixturePlaybackDeviceTest` 2/2, both on `REDACTED-SERIAL`) uses a **local
   fixture rendition**; no real-CDN title carrying a soft-subtitle rendition was exercised, so upstream subtitle availability
   stays unclaimed. This supersedes the previous wording of this item, which recorded subtitles as absent and `SPEC` §15
   "subtitles where available" as UNVERIFIED (DECISIONS 133).

5. **Emulator cannot verify streaming.**
   The emulator build is EXTERNAL-blocked by an AnimePahe Cloudflare challenge (LIMITATIONS §35.1), so streaming is
   verified on physical devices only. The two focused instrumentation suites were additionally run against a
   loopback HLS server (`RecordingHlsServer`, bound to `127.0.0.1`) so the header and lifecycle evidence does not
   depend on upstream reachability.

6. **`StreamInfo.cookies` is always empty on this path.**
   The resolver returns UA + Referer, which is what the CDN requires today (verified on the wire in Phase 17). If a
   future CDN starts demanding the session cookie for playlist/segment/key requests, streaming will break the same way
   the download path would — the fix is on the shared resolution path, not in the player. Nothing is hard-coded and no
   cookie is invented (AGENTS §11 honoured).

7. **Stream URLs are short-lived by nature.**
   A resolved `playUrl` expires; Phase 17 therefore resolves at stream start and never persists an expiring URL
   beyond the reconstruction ladder (Room row / TTL-bound scan cache). Logcat prints `host + path` only — no full
   stream URL, cookie, User-Agent or Referer is logged. Since 2026-10-06 that rule is also enforced *mechanically*
   for the HTTP layer: every URL embedded in a `WARN`/`ERROR` message in `OkHttpHlsClient` and `KwikResolver` is
   rewritten by the shared `LogUrlSanitizer` (host[:port] + a bounded path; query, fragment, credentials, tokens and
   cookies are cut before the message is built), so an exception message carrying a complete request URL cannot leak
   its query string. Network, resolver and Cloudflare behaviour are unchanged by this (DECISIONS 136). When the
   ladder runs dry the UI shows
   *"This stream session has expired. Reopen the episode list and try again."* — deliberately **not**
   *"This episode is no longer available."*, because expiry ≠ absence.

### Affected Features
- SPEC §15 STREAMING (the "single episode at a time" aspect; the former "no-subtitles" aspect was retired when the text-track control shipped 2026-10-06 — §37.4)
- SPEC §39 "*Streaming works.*" — **MET** for playback itself; the limitations above are quality-of-playback and scope items, not a failure of that criterion

### Risk Level
**LOW** — every item above is either an explicit scope exclusion (documented in the brief and PARITY §11.1) or a
platform difference already recorded. No streaming behaviour is faked, and the download/local-MP4 path is unaffected.

---

## 38. Final SPEC gap closure — what is still genuinely open (2026-10-05)

### Limitation

The five remaining mandatory SPEC gaps (release logging gate, latest-N selection, download bytes/speed/ETA,
pause/resume, streaming episode navigation) are closed and device-verified. Everything below is what stays open
**after** them — a real boundary, not a restatement of what shipped, and none of it is hidden behind a placeholder
control (AGENTS §5). Where an older section of this document still reads as if the gap were open (§31.2's and §32.2's
logging wording, §37.2's navigation wording), the supersession note sits with that statement.

1. **The streaming sibling list is read once, per entry.** `refreshStreamSiblings(...)` skips the read when a list
   is already present, so a session whose episode list changes *while* an entry is open keeps the list it started
   with until the player is reopened (DECISIONS 130). This is deliberate — one scan per entry rather than one per
   switch (AGENTS §10) — and it can only ever make navigation smaller, never invent a neighbour.

2. **An episode switch re-resolves; it is not a seamless playlist handoff.** Each `playNextEpisode()`/
   `playPreviousEpisode()` runs the full just-in-time resolve and swaps the source on the same player, so the viewer
   sees the loading state between episodes and the resolver is called once per switch (device-verified in
   `StreamPlaybackLifecycleTest`). There is no pre-buffered next episode and no multi-item stream playlist; Python
   hands MPV a finished multi-episode playlist instead.

3. **The MediaSession/notification tap target still names the episode the route was opened with.** `host.episodeId`
   is not rewritten by an in-place switch — the same behaviour the DOWNLOAD next/previous controls have always had —
   so tapping the player notification after navigating returns to the seed episode, not the one now playing. Media
   session lifecycle was outside this closure's boundary and was deliberately not touched; correcting it needs its
   own decision.

4. **Pause is cooperative, and an assembly cannot be paused at all.** `pause(...)` persists the `PAUSED` intent
   first and only then stops the work, so a segment already in flight finishes its atomic write and the row is
   durable before anything is interrupted; `ASSEMBLING` is excluded from `PAUSABLE_STATES` by design, because
   interrupting the mux would risk the output file. Neither is a defect — but pause is therefore not instantaneous,
   and a running mux runs to completion (DECISIONS 129).

5. **A paused download never resumes by itself, and there is still no "Download paused" notification.**
   `DownloadStateReconciler` treats `PAUSED` as deliberate user intent — never restarted, reset or auto-resumed on
   startup, process death or reboot — so resuming after any of those is a user action. The notification scope ruling
   of DECISIONS 119 stands: the state exists now, the notification deliberately does not (PARITY §9.4 row).

6. **Speed and ETA are derived values, shown only while a row is really downloading, and absent from the
   notification.** They come from a bounded 10-second window over persisted averages, so a stall reads as `0`, an
   unknown total yields no ETA, and a queued, paused or terminal row renders neither (DECISIONS 128). The progress
   notification keeps showing percentage only (`"$episodeTitle - $progress%"`), and nothing outside `AppLog`-style
   app telemetry is measured — there is no aggregate throughput figure across concurrent episodes.

7. **latest-N is a count over episode numbers, clamped to what exists.** It collapses SUB/DUB variants to one
   number, rejects a count below 1 instead of inventing episode 1, and cannot express Python's CLI-style selection
   prompts — there is no wizard flow on Android (the intentional divergence PARITY §13.2 records separately).

8. **The release logging gate is a level gate inside `AppLog`, and only that.** It does not strip third-party
   library logs, release minification is off so there is no R8 log stripping to fall back on, there is still no
   content filter (§31.2). **Updated 2026-10-06:** the gate is now asserted in *both* build variants on the JVM —
   `AppLogReleaseVariantTest` 3/3 plus the variant-aware `AppLogDiagnosticsGateTest` 7/7 and `LogBufferTest` (the
   release unit-test task was enabled with `android.onlyEnableUnitTestForTheTestedBuildType=false`, DECISIONS 132)
   — so `testReleaseUnitTest` runs against the compiled **release** `BuildConfig` default instead of only reading
   it in source. What still has **no** evidence is the release **APK at runtime**: as of 2026-10-24
   `app/build.gradle.kts` has an env-gated, fail-closed `signingConfigs` (PB_* variables,
   signing.env.example), so installing a release build now means the maintainer setting those variables locally - still not executed here. The three tiers are therefore: source ✅,
   release-JVM ✅, release-APK-runtime ❌ never executed (§39.1). The debug variant is unchanged, which is what
   every device run above executes. Classified a non-blocking verification limitation: the SPEC requires a successful release build and the release logging gate, not a signed runtime-verified artifact (DECISIONS 140).

9. **SPEC §15 "replay" and "episode selection" stay the different-API rows they were.** Nothing in this closure
   changed them: seeking to the start goes through the existing seek control, and episode selection is the Anime
   Details list plus the next/previous controls of §38.1–§38.3 — there is no in-player episode picker. PARITY
   `PARITY.md:699` and `PARITY.md:700` keep their 🔄 status, unchanged and not upgraded.

### Affected Features
- SPEC §15 STREAMING (episode navigation, replay, episode selection)
- SPEC §20 SESSIONS (Pause / Resume actions)
- SPEC §22 EPISODE SELECTION (latest N episodes)
- SPEC §25 DOWNLOAD STATE (downloaded bytes / speed / ETA, `paused` state)
- SPEC §33 release-build configuration (debug logging restricted in release)

### Risk Level
**LOW** — every item above is a stated boundary with its own evidence trail; none of them fakes state, none of them
blocks the search → scan → resolve → download → play use case, and the download, player and settings paths outside
these boundaries are untouched by the closure.

---

## 39. Final closure round (2026-10-06) — the six items and their real boundaries

### Limitation

This round closed six remaining items: the subtitle control, release-variant logging verification, the **real**
process-death proof for a PAUSED download, real-CDN episode navigation, the `PARITY.md` contradiction and its
citation checker, and log-URL sanitization. Everything below is what those six items deliberately did **not**
settle — a boundary, not a restatement of what shipped (AGENTS §5). Where an older section still reads as if one of
these were open (§37.4's former subtitle wording, §38.8's former "no release-variant test" wording), the
supersession sits with that statement.

1. **The release log gate is never exercised on a release APK at runtime.** *(2026-10-24 update: `app/build.gradle.kts` now carries an
   env-gated, fail-closed `signingConfigs.release` (PB_* variables, `signing.env.example`); the unsigned-by-default per-ABI
   `app-<abi>-release-unsigned.apk`s still cannot be installed, and the signed runtime run stays unexecuted - pending owner authorization.)*
   So the evidence for DECISIONS 131/132 is tiered and must never be quoted as one number: **source ✅**
   (the gate is read in `AppLog.kt`), **release-JVM ✅** (`testReleaseUnitTest` runs `AppLogReleaseVariantTest` 3/3 +
   the variant-aware gate tests against the compiled release `BuildConfig`), **release-APK-runtime ❌ never run**
   (DECISIONS 132).

2. **Subtitles are an in-playback text-track control, and their device evidence is a fixture rendition.**
   Nothing was added for subtitle *acquisition*: no subtitle download, no persistence of the choice across entries or
   restarts, no translation, no custom rendering (§37.4). `SubtitleFixturePlaybackDeviceTest` plays a locally served
   HLS playlist that declares a named text rendition; **no real-CDN title carrying a soft-subtitle rendition was
   exercised**, because no such title was staged this round. Upstream subtitle availability is therefore unclaimed
   — the claim is exactly "when the media declares a subtitle, the viewer can turn it on and off" (DECISIONS 133).

3. **A text rendition the media never named produces no control at all.** MPEG-TS decoders expose an unlabelled,
   language-less `application/cea-608` placeholder for every stream, subtitle-bearing or not; offering it would be a
   button that pretends to work (AGENTS §5). The rule is therefore "offer only what the media named" — so a stream
   that *has* CEA-608 captions but declares no `NAME`/`LANGUAGE` for them shows no subtitle button. That is the
   chosen trade-off, not a detection failure (DECISIONS 133).

4. **Both new device protocols are operator-driven and cannot be automated end to end.** The real kill for the
   PAUSED row needs an external `am force-stop` while the instrumentation sleeps (a 60-second grace window), and the
   host cannot poll this device's user-10 data directory (`run-as` resolves to the empty user-0 directory), so the
   two phases are driven by the operator exactly as `RealProcessDeathPausedDeviceTest`'s KDoc records. The real-CDN
   navigation run needs a backend that exposes **at least two navigable episodes** for one title; if upstream stops
   doing that, STEP 2 fails loudly with the per-title exposure instead of skipping the scenario (DECISIONS 134, 135). The operator-run transcript for the PAUSED kill is no longer ephemeral: the 2026-10-07 re-execution is archived under .investigation/phase17p7/ with integrity hashes (DECISIONS 138) - that fixes reproducibility of the RECORD, not of the protocol, which still needs the operator to fire the kill.

5. **Log-URL sanitization covers the two files this round was scoped to, and is not a content filter.** Only the
   URL-bearing `WARN`/`ERROR` lines in `OkHttpHlsClient` and `KwikResolver` go through `LogUrlSanitizer`; every other
   call site still relies on the call-site rule of Decision 86 item 4 (`DECISIONS.md:3666`), which stands unchanged.
   The sanitizer changes what is *printed*, never what is requested: no network, resolver, URL-construction or
   Cloudflare behaviour moved (DECISIONS 136). SUPERSEDED IN PART 2026-10-07 (DECISIONS 138): the two-file scope above is Phase 17.6 history. The closing round extended the same LogUrlSanitizer to HlsPlaylistParser (URI-resolution WARN), StreamResolverImpl (throw site), DownloadRepository (re-resolution ERROR) and KwikResolver (exception message), and centralized exception rendering in AppLog.renderThrowable, so every identified release-reachable WARN/ERROR path is sanitized. Still true and unchanged: it is not a content filter - DEBUG-only lines, third-party library logs and non-URL call-site messages are untouched, and no universal claim about every URL in the application is made.

6. **The MediaSession episode id still names the route seed after an episode switch** — unchanged by this round and
   still recorded at §38.3; the navigation evidence asserts the coherence that does hold (the session serves the one
   player through both switches) and deliberately does not assert the seed id as current behaviour (DECISIONS 135).

### Affected Features
- SPEC §15 STREAMING (subtitles where available; episode navigation evidence)
- SPEC §20 SESSIONS (a PAUSED row across a real external process kill)
- SPEC §33 release-build configuration (debug logging restricted in release — evidence tiering)

### Risk Level
**LOW** — all six boundaries are stated with their evidence and none of them substitutes a fixture, a source read or
a JVM assertion for a runtime claim it cannot support; the search, download, player and settings paths outside them
are untouched.

---

## 40. Gate A pre-release export-directory closure (2026-10-08) — what the Unicode device scenario did not exercise

### Limitation

Gate A (pre-release bounded fix, DECISIONS 141) restructured NEW exports to a per-anime directory — `Movies/<sanitized-anime-title>/` via MediaStore `RELATIVE_PATH`, or `<selected-tree>/<sanitized-anime-title>/` beneath a chosen SAF tree — leaving legacy flat files untouched. Every statement below is limited to what was actually exercised.

1. **Unicode title export was NOT exercised on the physical device.** The production probe (`UnicodeTitleProbeDeviceTest`) sent `葬送フリーレン`, `鬼滅の刃`, `進撃の巨人`, `Титан` and `الشمس` through the real search → details flow; the details-title path never surfaced a non-Latin session title (probe summary `anyNonLatinDetailsTitle=false`), so production code never created a Unicode-named directory. This is a verification limitation, not an implementation failure: Unicode sanitizer behavior is covered on the JVM (`StringUtilsTest` 23/23 and `OutputPathNamesTest` 17/17 include CJK, Arabic and accented titles), and the final sanitizer ran on device for the exercised titles.
2. **Physically verified on `REDACTED-SERIAL` — do not list these as unverified:** ordinary title → `Movies/One_Piece/` (full production batch flow, `is_pending = 0`, playback verified); special-character title — `The World's Strongest Rearguard` → `Movies/The_Worlds_Strongest_Rearguard/` (matches the Python sanitizer for this title, exported and played); legacy compatibility — a real pre-Gate-A artifact at `Movies/PaheBatcher/Ep_001_-_Solo_Leveling_Ep._1_animepahe.mp4` remained in place (bytes, Room `outputUri` and `is_pending = 0` untouched) and played through production; batch — two episodes of one anime exported concurrently into the same directory with distinct filenames, both played.
3. **Android runtime regex detail (RESOLVED during the gate — not a remaining limitation).** The first implementation relied on `Pattern.UNICODE_CHARACTER_CLASS`, which Android's `java.util.regex.Pattern` rejects at runtime with `IllegalArgumentException`; this was discovered by device verification, replaced by explicit Unicode categories (`\p{L}`, `\p{N}`) in `Regex("[^\p{L}\p{N}_\s\-.]")`, then re-verified on device and covered by JVM tests. The final implementation does not use the rejected flag, and the rejected approach is not the current state.
4. **Legacy retention is intentional.** No legacy file was migrated, renamed, deleted or rewritten (DECISIONS 141, PARITY §10.1); §18's best-effort cross-store atomicity boundary is unchanged by Gate A.

Everything else recorded here (Kwik/WAF, release-runtime tiering, reboot/process-death, FFmpeg, §34 residuals, test-environment quirks) pre-dates Gate A and is untouched by it.

### Affected Features
- SPEC §13 / PARITY §10.1 per-anime export directory — Unicode-title device scenario only
- Physical-device verification coverage (four of five Gate A scenarios passed; one unexercised)

### Risk Level
**LOW** — sanitizer and directory hardening are JVM-verified including Unicode cases, the four remaining scenarios passed on the physical device, and the unexercised scenario depends on site-provided titles rather than on Android behavior.

---

## 41. Audio Source / Per-Episode Audio slice — verification environment and residuals (2026-10-08)

The slice itself closed its gate (JVM 941/0 both variants, lint baseline, both assembles, §20 deploy, 13-scenario
physical-device gate **11 PASS / 2 NOT EXERCISED / 0 FAIL** — DECISIONS 142). What follows is everything the gate
could NOT exercise live, plus the run-environment findings — none of it is a product defect.

### 41.1 Legacy watchlist concrete-audio skip — NOT EXERCISED on device

The watchlist Add flow always writes `audioLang = "any"`, and no UI exists to create an entry with a concrete
`jpn`/`eng` value, so the strict concrete-exact-match-or-skip path had no live trigger. It is test-covered instead
(JVM `WatchlistCheckerTest`; device `WatchlistCheckWorkerTest`, green in the full 2026-10-08 run). The gate table
records this scenario as **NOT EXERCISED**, never as PASS.

### 41.2 Local-playlist seed-audio isolation — no mixed-audio completed set upstream

`PlaybackPlaylist.completedPlayable(rows, seedAudio)` filters a local playlist to the seed's audio so next/previous
never cross audio, but both completed device episodes (GateA's two Rearguard downloads) share one audio, so no live
playlist could ever reach the boundary where filtering matters. The local playlist itself was exercised live (played,
Next/Previous within the seed); the filtering has mixed-fixture JVM coverage (`PlaybackPlaylistTest`). Also recorded
as **NOT EXERCISED** in the gate table.

### 41.3 Stale pre-slice comments left in place (documentation drift, not behavior)

`DownloadConfig.kt:73-75` still describes `audio_lang` as "consumed by variant SELECTION at resolution time
(`AnimeDetailsViewModel.configuredAudioLang`, Phase 12C)", and `DownloadConfigSource.kt:28` still lists `audio_lang`
among the keys with "its own real consumer at resolution/scan time". Both config paths were removed by the slice and
a grep audit confirmed **zero live references** to the removed APIs anywhere under `app/src`; only these two comments
are stale. They were deliberately not edited because neither file is inside the approved 21-file production boundary
for this slice. Cited here for a future documentation/code-sync pass.

### 41.4 No JVM unit test drives `PlayerViewModel.loadLocalPlaylist`'s seed argument directly

The seed-audio wiring into the local playlist loader lives in `PlayerViewModel` (Android runtime scaffolding
required); its filtering logic is pure and covered by `PlaybackPlaylistTest`, and the local playlist was exercised on
device, but no unit test calls `loadLocalPlaylist` itself with a seed. Coverage gap recorded rather than papered over
with a mock-heavy test built outside the slice's testing scope.

### 41.5 The multi-class filter honors only the FIRST class (the GateA prerequisite is gone)

Toolchain quirk, still live: `-Pandroid.testInstrumentationRunnerArguments.class=A,B` honors only
the **first** class in this Gradle/AGP combination — pair classes via direct
`adb shell am instrument`, not via the property. It is also why subset runs are driven one class
at a time.

**The section this replaced is closed (Slice B closure, 2026-10-11).** It recorded a
`GateASecondEpisodePlaybackDeviceTest` prerequisite: that class needed the Rearguard session
`GateASpecialTitleDeviceTest` creates *in the same install session* (§13 wipes both packages
after Gradle connected runs), so a standalone run failed its precondition by construction — and
no ordering this toolchain offers could satisfy it, because a full run is alphabetical and
`GateASecond…` starts before `GateASp…`. The workaround was to install the androidTest APK
manually and invoke the two classes as consecutive `am instrument` calls.

That class has been **deleted**. Its purpose — proving both batch exports play through the
production player — now lives inside `GateASpecialTitleDeviceTest`, in the same method that
produced those exports, looping over every export with an exact-count pin. The precondition no
longer exists, so nothing pairs the classes and the manual two-step is unnecessary. Verified
1/1 standalone and in the full run (`ADB_PLAYBACK count=2 of 2`); see DECISIONS 146.10.

### 41.6 The physical device was in LANDSCAPE during the full run — rotation now locked

Two `AnimeDetailsBatchScreenTest` failures in the full run were traced to the device reporting landscape (Compose
composed only one batch row); production highlight semantics were correct on device once exercised in portrait
(15/15). The device `REDACTED-SERIAL` is now deliberately locked to portrait (`settings put system accelerometer_rotation 0`
+ `user_rotation 0`) as a documented test-environment baseline for deterministic UI dumps; `PlayerLifecycleTest`
already saves/restores rotation around its own runs, so the lock does not fight it.

### 41.7 `adb shell run-as` targets user 0 on this multi-user device

The app runs under user 10; `run-as com.pahebatcher.app` resolves to `/data/user/0/com.pahebatcher.app` (created but
never run — empty apart from `cache`/`code_cache`) and exposes no `--user` flag, so the Room database is unreachable
for inspection through `run-as` (`run-as … strings databases/pahebatcher.db` → `No such file or directory`; the
device has no `sqlite3` either). Gate evidence that needed persistence therefore used UI dumps across a force-stop
and relaunch, plus logcat, instead of direct database reads.

### Risk Level
**LOW** — every item is environment, coverage disclosure or comment drift; each carries its test-covered substitute,
and none was counted as a PASS it did not earn.

---

## 42. Option C Audio Selector UX slice — verification environment and residuals (2026-10-09)

The slice closed its gate (JVM **966/0** in both variants, lint 0 errors, both assembles, §20 deploy to `REDACTED-SERIAL`,
full device suite **484 tests / 3 failures / 0 errors with zero in-scope failures** — DECISIONS 143). What follows is
everything the gate could NOT exercise live, plus the run-environment findings — none of it is a product defect.

### 42.1 Real-site dual-audio end-to-end — NOT EXERCISED in this slice (live backend not serving reliably during the run window)

Every dual-audio behavior of this slice was verified on the physical device through the fixture-backed PRODUCTION
stacks — production `AnimeDetailsViewModel`, production `AnimeDetailsScreen`, production derivation functions, with
only the `HttpClient`/`StreamResolver`/`EpisodeDownloadQueue` edges faked (AGENTS §5): `AnimeDetailsAudioScreenTest`
9/9 (selector options/current-marking, one-row-per-number in jpn and eng contexts, override control, tap
consumption, batch-running inertness, effective-audio display, notice rendering) and `AnimeDetailsAudioRecreationTest`
1/1 (context/overrides/selection across Activity recreation, then a real batch driven by the retained state).
A REAL live dual-audio title flow was **not** exercised in this slice: the live backend did not serve the app's
requests reliably during the run window (first full run: 10 live suites failed including the real-search step of
`BatchDownloadDeviceTest`; second run, byte-identical sources: 5 of those went green while the Phase 17 sibling
stream hit a mid-stream `Source error`). The equivalent live dual-audio evidence exists from the audio-slice gate
earlier the same day (Tomb Raider King rows `eng`×11 + `jpn`, resolver logcat `request audio=eng → resolved
audio=eng` — LIMITATIONS §41). This scenario is recorded as **NOT EXERCISED**, never as PASS.

### 42.2 The full-suite environment findings — root-caused run by run, none in-scope

The first full device run (493 tests, 15 failures) decomposed as: **2 of this slice's own test defects**
(`anime_details_episode_audio` is a plain Text inside the selectable row, so the merged-tree lookup failed — fixed
with `useUnmergedTree` and re-verified 9/9 isolated), **3 `FirstEnqueuePromptTest.a_denied_*` documented
precondition assumption-violations** (the device's notification permission is already GRANTED from a previous run;
the tests themselves say "run from a fresh install" — §35.7-3), and **10 live/timing suites**. The second full run
(484 tests, 3 failures — same sources, none of the slice's files touched in between) kept only:
`GateASecondEpisodePlaybackDeviceTest` — its own §41.5 cross-class precondition ("the Rearguard batch session must
exist (run GateASpecialTitleDeviceTest first)"; the session is wiped by the connected-task teardown), failing fast
in 0.6 s; `LegacyFlatPlaybackDeviceTest` — its own precondition guard ("this device must hold at least one real
legacy flat export (got 0)"), failing fast in 0.6 s; and `Phase17StreamingSiblingNavigationDeviceTest` — real-CDN
delivery: the seed stream reached READY, then the next-episode switch hit Media3 `state=BUFFERING, error=Source
error` after ~3 minutes of live traffic (the same test was green in the morning gate with identical code). The
10→3 flip across two runs of identical sources is the evidence for the environment classification.

**Reclassified at the Slice B closure (2026-10-11).** Two of those three were not environment —
they were in-scope test defects, so the "none in-scope" framing of this section was wrong about
them, and both were closed rather than tolerated:

* `GateASecondEpisodePlaybackDeviceTest` — a cross-class precondition no ordering this toolchain
  offers could satisfy (§41.5: alphabetical ordering, a filter honouring only the first class,
  and §13's package wipe). The class is **deleted**; its playback assertion now runs inside
  `GateASpecialTitleDeviceTest`, in the same method that produces the exports, and pins an exact
  export count.
* `LegacyFlatPlaybackDeviceTest` — demanded "a real pre-Gate-A artifact", which production can no
  longer write at `Movies/PaheBatcher/` (this section's own "zero exports on the device" was the
  symptom, not the cause). The contract is now path-based and the test seeds its own >1 MB
  fixture when the device holds none.

The third, `Phase17StreamingSiblingNavigationDeviceTest`'s real-CDN mid-stream `Source error`,
remains environmental: it depends on live third-party delivery and flipped green/red across runs
of byte-identical sources.

**Evidence:** the first full run after the closure is **534 tests / 0 failures / 0 errors /
0 skipped across 95 classes** — both of the above included, and with the Phase 17 sibling suite
and `FirstEnqueuePromptTest` green in that same run. See DECISIONS 146.10.

### 42.3 Test-technique note: row-internal tags resolve only in the unmerged tree

`anime_details_episode_audio` sits on a plain `Text` inside the `selectable` episode row; the row's own `testTag`
wins the semantics merge, so a default merged-tree `onNodeWithTag` for the child tag fails even though the node is
on screen. The fixed tests look the tag up with `useUnmergedTree = true` (and text-based queries work in the merged
tree either way). Recorded so future suites do not rediscover it; interactive children (buttons, chips, menu items)
and container tags are unaffected, which is why every other tag assertion passed.

### Risk Level
**LOW** — one NOT-EXERCISED live scenario (with its test-covered substitute and the morning gate's live evidence),
run-environment findings with per-test root causes, and one test-technique note; no product defect and no PASS was
claimed without evidence.

---

## Recommendations

1. **Phase 5 Architecture**: VALIDATED — WebView is the browser/session mechanism for all Cloudflare-protected AnimePahe requests
2. **OkHttp Usage**: Use OkHttp only for non-Cloudflare endpoints (HLS segments, AES keys, CDN requests)
3. **Session Management**: Session validity checks cookie presence AND timestamp freshness (20-minute threshold)
4. **Kwik Resolution**: SOLVED (Phase 11.5E-4) — send `Referer: https://animepahe.com/` on the Kwik page navigation and use the Kwik PAGE as the referrer for CDN playlist/segment/key requests; verified on device (see Section 2B)
5. **FFmpeg Priority**: Install NDK r27c and validate FFmpegKit dependency before Phase 9 implementation
6. **Background Downloads**: IMPLEMENTED (Phase 10) — CoroutineWorker with setForeground(), WorkManager 2.11.2, reconciliation on startup
7. **CloudflareDetector**: Ready for native HTTP integration in Phase 6 (Decision 28) — already integrated
8. **WebView Thread Safety**: All WebView creation/destruction must happen on the main thread
9. **Suspend Functions**: Use suspendCancellableCoroutine for WebView callbacks, never blocking primitives on the main thread
10. **Phase 8 Completion**: Wire cleanupOrphans() into production download flow and add crash recovery tests

---

## Testing Strategy

1. **Cloudflare bypass**: Test with real AnimePahe URLs on multiple devices
2. **TLS fingerprinting**: Test direct requests vs WebView fallback
3. **FFmpeg muxing**: Test assembly on various Android versions
4. **Background execution**: Test with battery optimization enabled
5. **Storage access**: Test on Android 10, 11, 12, 13, 14
6. **Process death**: Test kill and resume scenarios
7. **Memory**: Test on devices with 2GB, 4GB, 8GB RAM
8. **Network**: Test with WiFi, mobile data, airplane mode transitions

## 43. Audio option discovery slice (DECISIONS 144) — sampling bounds and verification environment (2026-10-10)

This section records only what the 2026-10-10 implementation slice could and could not establish. It does NOT close, restate or supersede the earlier audio limitations (§35.5, §41, §42); it adds the new, probe-specific ones.

### 43.1 Representative sampling is NOT a per-episode audio inventory — UNRESOLVED BY DESIGN

The option set is the union of the `data-audio` strings found on at most `merged sessions + 1` sampled play pages: the lowest-numbered row of every merged session plus the highest-numbered row of the opened session, deduplicated (`EpisodeRepository.audioProbeTargets`, `SPEC.md:876-890`). Consequences, all accepted and none hidden:

- An audio that exists only on an UNSAMPLED play page may be absent from the selector. The user cannot select what the probe did not see, and nothing in the UI claims the set is exhaustive.
- Probing never silently expands to one request per episode. Escalating the bound needs a new approval (DECISIONS 144.3); a title with many merged sessions costs one extra page request per session, plus at most one for the opened session's maximum.
- A partial pass (`PARTIAL`) unions only the pages that answered; a total failure (`FAILED`) and a completed-but-empty pass (`OK_EMPTY`) both fall back to release-listing audio. Neither is dressed up as a successful discovery.
- Release-listing audio stays display/identity metadata and the fallback source only. On its own it never proves an audio unavailable for an episode; that is decided at resolve.

### 43.2 Probe state expires with the scan-cache row that carries it

`audio_probe` and `probe_targets` live inside the EXISTING `scan_cache` payload (`{episodes, probe_targets, audio_probe}`, with the legacy top-level array still readable). There is no new table, no Room migration and no independent probe TTL:

- `saveAudioProbe` rewrites only the probe member and copies `cachedAt` / `cacheTtlMinutes` verbatim, so a probe NEVER extends scan freshness (and never shortens it).
- When the scan-cache row expires or is cleared, the probe result goes with it and the next load re-probes. A cache-disabled configuration therefore re-probes on every load.
- `PARTIAL` / `FAILED` are not completed states and are re-attempted at most once per user-driven load; a completed `OK_VALUES` / `OK_EMPTY` costs zero requests for the life of the row. A row carrying no targets (a legacy payload, or a scan whose rows have no `play_url`) is never probed and reports `NOT_PROBED`.

### 43.3 Never persisted: page HTML, stream URLs, cookies

The probe parses each page in memory and stores only the audio strings it extracted. Play-page HTML, KWIK/`m3u8` URLs and any cookie or header material are dropped with the page; the only URLs retained are the `play_url` values the scan already stored, plus the probe's own target list derived from them. Logs record request counts, state and value counts only (`AudioOptionProbe`, `AudioOptionRepository.kt`).

### 43.4 Verification environment for this slice — no live probe; device instrumentation added later the same day

- **Live AnimePahe is unreachable from the build environment** (hard IP-level Cloudflare 403, LIMITATIONS §1/§35.1), so NO probe run against the real backend is claimed. Every probe, union, state and cache assertion in this slice runs against fixture play pages through the REAL `PlayPageParser`, `AudioOptionRepository` and `ScanCacheRepository` — and the bounded probe still has never been exercised live even after the device run below (fixture-based probe tests are not live evidence).
- **Initial close of this slice: no ADB device** (`adb devices` returned an empty list twice on 2026-10-10), so no instrumentation suite ran and no APK was installed; the androidTest sources were only compiled (`compileDebugAndroidTestKotlin` SUCCESS). That status was true for its run window and is superseded by the next bullet.
- **Later the same day a device WAS attached** (`REDACTED-SERIAL`, state `device`) and the instrumentation suite ran for real. The first run found a genuine in-scope defect: `AnimeDetailsAudioScreenTest` failed 4/9 because `EpisodeRow` gated the per-episode override control on the screen-level `audioOptions.size > 1` instead of the SPEC §22 `getAllVariants(N)` > 1 rule (root cause, fix and re-verification in the DECISIONS 144 device addendum). After the fix: `AnimeDetailsAudioScreenTest` 9/9, `AnimeDetailsAudioRecreationTest` 1/1, `AnimeDetailsBatchScreenTest` 14/14, `AnimeDetailsScreenTest` 18/18, `AnimeDetailsLeaveBatchScreenTest` 8/8, `AnimeDetailsDestinationTest` 1/1; the FULL `connectedDebugAndroidTest` was **493 tests / 2 failures / 0 errors**, the 2 being the pre-existing §42.2 device-state/ordering preconditions (`GateASecondEpisodePlaybackDeviceTest`, `LegacyFlatPlaybackDeviceTest`) with zero in-scope failures. AGENTS §20 install performed and the package verified (`com.pahebatcher.app`, `versionName=0.1.0`). The live-network suites inside that run (real search/scan/resolve/download, Kwik, production Unicode search) passed from the device — but none of them exercises the bounded audio probe, so the probe stays test-only.
- Consequence: the device-level Option C evidence recorded in §42 (previous behavior) is superseded FOR THE RENDER CONTRACT by the 2026-10-10 run above — one row per NUMBER in every context and the override control's `getAllVariants(N)` gate are device-verified on the current code, and §42's recreation/selector evidence still stands. STILL NOT VERIFIED anywhere: a live probe of the audio option set (§43.1's sampling caveat is unaffected by either run).

### 43.5 What this slice did NOT change

Search, Cloudflare/WebView session handling, KWIK resolution, HLS parsing, the download engine, segment store, MP4 assembly, notifications, WorkManager scheduling and playback are untouched. No new HTTP stack, browser automation, runtime, cache subsystem, service or dependency was introduced; the probe reuses the existing session-aware page path and the existing scan-cache row.


---

## 44. Slice A per-audio export identity (DECISIONS 145) - what stays conditional (2026-10-11)

Records the genuine residuals of the export-identity slice. Scenario coverage and commands are in DECISIONS 145 and the ROADMAP Slice A section.

### 44.1 Legacy alias adoption is gated on a REFERENCE, not on ownership

The guarded legacy alias refuses a pre-Slice-A unsuffixed file only when ANOTHER Room row already references that URI, or when the byte count differs. It does NOT establish which audio variant produced the file: an unreferenced legacy export whose size happens to equal the local source can be adopted by the WRONG variant. Nothing can detect that case - neither MediaStore nor SAF stores audio provenance on a file, and the file name carries no audio token by construction. The consequence is bounded (the adopting variant shows bytes that belong to the other variant, while the true owner either writes its own suffixed copy from its surviving local source or hits the same ambiguity in reverse), but it is a real blind spot and is tested only on the refusal side: `legacyAliasRefused_whenAnotherAudioVariantReferencesTheUri`, `legacyRootFile_referencedByAnotherAudioVariant_isNotAdopted`.

### 44.2 Reuse is verified by SIZE only, never by bytes

Every reuse and adoption path (`MediaStoreExporter` lookup and alias pass, `SafExporter` reuse lookup, `SafExporter.finalizePart`) compares the stored length of the candidate against the local source's length and stops there. Content is never hashed or byte-compared, because that would read the whole exported video (plus the local copy) for every candidate. The documents therefore say `size-verified` and never claim byte-identical reuse. A same-length-but-different-content file would be indistinguishable from a good one; in practice it cannot arise, because the copy is written in a single bounded pass and its byte count is verified before `IS_PENDING` is cleared.

### 44.3 The two exporters keep DIFFERENT replacement policies, on purpose

MediaStore replaces an exact-name size-mismatched row (the §36-7 one-row invariant, Decision 54): the stale row of THIS variant is deleted and re-created. SAF never overwrites, never deletes and never renames an existing final child - it reuses by size and otherwise uniquifies to the first-free `name (n)` child (Decisions 92.5/104.6). Slice A deliberately did NOT harmonize them, so the same logical situation yields "replaced" on MediaStore and "added alongside" on SAF. This is a divergence between two Android destinations, not a divergence from the Python reference.

### 44.4 A second reference to one URI now duplicates instead of sharing

Before this slice two rows could end up pointing at one output URI, and an exporter could adopt a file another row already owned - so either row's cleanup could destroy the other row's output. The `otherReferenceAudios` guard makes the second row take a FRESH copy instead, which means identical content can now exist twice under two names. That duplication is intentional: it is the price of never letting one row's deletion take away another row's file. Cost: one scan of the small `episode_downloads` table per size-matched candidate - no index, no column, no schema change, and therefore no Room migration.

### 44.5 Bounds of this slice's verification

Every required scenario ran on device `REDACTED-SERIAL` through the REAL MediaStore, the REAL test DocumentsProvider, the REAL Room database and the production `DownloadStateReconciler` - both export orders, same-audio re-export, size-mismatch isolation, crash-residue recovery, legacy file never deleted, alias refusal on a foreign reference, alias size rule, reconciler cross-audio safety, SAF stale-part cross-audio, SAF never-overwrite, SAF dual-audio coexistence, and the JVM naming determinism/sanitization suite. NOT verified here: a live AnimePahe download-then-export sequence (the backend is unreachable from this environment - §43.4), a storage provider that reports sizes inconsistently, and concurrent exports from two processes against the same MediaStore row (exports are serialized by `ExportOperationLock` within the process, which is the only lock Android gives an app).


---

## 45. Slice B truthful duplicate-download outcomes (DECISIONS 146) - what stays conditional (2026-10-11)

Records the genuine residuals of the duplicate-outcome slice. The outcome model, the test inventory and the verification commands are in DECISIONS 146.

### 45.1 RESOLVED at the closure: a refused hand-off now has its own verdict

**Closed 2026-10-11, review finding F1: `EnqueueOutcome` has six values.**

This section used to record a deliberate imprecision, on the stated grounds that a sixth value
"could only ever be produced by a race, and a caller could never distinguish it from an ordinary
already-covered episode". The first half was always true; the second was wrong, and
`EnqueueOutcomeMappingTest.ClaimThenCancelDao` proved it by producing that window
DETERMINISTICALLY instead of waiting for a race.

The H-2 path - the `resolveComplete` CAS lost to a concurrent Cancel/Pause/reconciler move, so
no Worker exists - derives its answer from the persisted row. A row moved to `FAILED` or
`CANCELLED` in that window used to report `AlreadyActive`, whose contract promises a row "the
pipeline owns": a cancelled episode was announced as "Already in the download queue" while
holding no Worker at all. It now reports **`EnqueueOutcome.Refused`**, which says only what is
true - nothing was queued, and nobody owns the episode - and is distinguishable from an ordinary
already-covered episode precisely because the row itself said `FAILED`/`CANCELLED`, or is gone.

**Unchanged:** the property this slice exists for still holds, and is stronger - that path can
never report a fresh enqueue, and now never claims coverage it does not provide. The Downloads
dashboard still reads Room directly, so the persisted state is always what the user sees there.
DECISIONS 146.3 records why the rejection was originally made and why it no longer stands.

No residual imprecision remains in this path.

### 45.2 A verdict describes the row at the instant it was read

Two submissions of one episode are serialized only as far as Room's primary-key claim goes: exactly one creates the row, and every loser re-classifies from the row as it exists at that moment. Android gives an application no stronger cross-process primitive than that claim plus the per-episode unique Work name (which is what actually prevents a second Worker). A loser can therefore report `AlreadyActive` for a row that a moment later becomes `FAILED`; that is a statement about a different instant, not a false claim about work it started.

### 45.3 The audio label is only as good as the persisted `audio` column

`EpisodeDownloadEntity.audio` has been written by production since the audio slice, so every row created since then carries it. A legacy row with a blank `audio` shows no label at all, because the mapper never invents one. Nothing here re-probes the play page for the audio of an already-downloaded episode: that would cost a WebView round-trip to display a string the database already holds - or, in the blank case, a string the database cannot hold.

### 45.4 The shared output rule inherits the reconciler's behavior exactly, blind spot included

`EpisodeOutputVerifier` is the rule `DownloadStateReconciler` already applied, so this slice changed what the SUBMISSION path believes, not what the app believes about a finished download. A row whose `outputUri` names a file that has since been moved or deleted is still reported missing at the next launch exactly as before, and nothing is deleted or demoted at submission time beyond the re-queue the user asked for.

### 45.5 Bounds of this slice's verification

Every mapping in the outcome table, the concurrent-creation claim, the refused hand-off, both `insertIfAbsent` safety properties, the resolver-verified and the unverifiable output cases, the watchlist already-handled reclassification, the audio-label mapping and its Compose rendering were exercised on device `REDACTED-SERIAL` against the REAL Room singleton, the REAL application WorkManager, the REAL `ContentResolver` and the REAL MediaStore, plus the full JVM suite.

**Closed at the Slice B closure (2026-10-11).** The two section 42.2 preconditions this section previously listed under NOT verified were test defects, not environment, and both are gone: `GateASecondEpisodePlaybackDeviceTest` was deleted with its playback leg folded into `GateASpecialTitleDeviceTest`, and `LegacyFlatPlaybackDeviceTest` now seeds its own path-based fixture (DECISIONS 146.10). Full instrumentation since then is **534 tests / 0 failures / 0 errors / 0 skipped across 95 classes**, so no part of this slice is excluded from a full run any more.

**STILL NOT verified:** a live AnimePahe submission of two audio variants of one episode in parallel - the backend is unreachable from this environment (section 43.4). That gap is unchanged by this slice and was not made worse by it.

**Bounded by scope rather than by reachability:** `Refused` is verified against a deterministically cancelled/failed/deleted row (`ClaimThenCancelDao`, plus the F2/F7 concurrency tests), not against every interleaving of concurrent Cancel and retry a real device could produce. The tests pin the invariant that matters - a refused hand-off never reports a fresh enqueue, and never claims coverage it does not provide - rather than enumerating interleavings Android gives an application no primitive to observe.

## 46. Slice C per-episode failure reasons (DECISIONS 147) - what stays conditional (2026-10-12)

### 46.1 R1 means resolution-stage failures do NOT survive the process

`AnimeDetailsUiState.batchFailures` and `BatchEpisodeFailure` are **UI state**. The
room database stays at **v3 with no migration**, because the alternative (R2, a persisted
FAILED row) would have required fabricating `episodeSession` and `playUrl` for an episode
that does not exist in the listing (DECISIONS 147.2).

Consequences:

- A resolution-stage failure (`EPISODE_NOT_IN_LISTING`, `AUDIO_UNAVAILABLE`,
  `STREAM_RESOLUTION_FAILED`) has **no download row**, so there is nothing to recover
  from on restart. **No process-death recovery is claimed or possible for these.**
- `batchFailures` is dropped on Activity recreation, configuration change, process death
  and app restart. The `passFailures` keys that drive retry passes live in the batch
  coroutine and are dropped with it.
- `Room migration` tests, `DownloadStateReconciler` and the terminal CAS are untouched by
  this slice - they never see these failures because no row is ever created.

By contrast, a **download-stage** failure (`SEGMENT_DOWNLOAD_FAILED`, `STREAM_EXPIRED`,
`NO_CONTENT`, `DISK_FULL`, `ASSEMBLY_FAILED`, `IN_RUN_RETRYABLE`) already has a real row
whose persisted `errorMessage` and state drive recovery; Slice C only adds the CATEGORY
shown alongside it.

### 46.2 The Downloads-row Retry (`reEnqueue`) failure stays un-notified and unclassified

`DownloadRepository.reEnqueue` (`DownloadRepository.kt:990`) performs a FRESH
re-resolution when the user taps Retry on a Downloads row. If that resolution fails, the
error IS persisted honestly and CAS-guarded at
`DownloadRepository.kt:1138-1142` (`updateErrorIfState`, expected `RESOLVING`), so the
row transitions to FAILED with an `errorMessage` the UI renders.

But:

- **`DownloadRepository` contains zero notification calls**, so this path posts no
  notification at all - neither the per-episode failure notification nor the batch
  summary. Slice C's notification policy covers only `AnimeDetailsViewModel`
  (`publishBatchDone`) and `DownloadWorker.notifyTerminal`.
- The failure is **not** passed through `FailureCategory`, so no category label is
  attached to it anywhere.

This is unchanged behavior - Slice C did not widen the notification policy to a third
origin because the approved scope was explicitly R1 + W1 + the typed seam + the
conditioned batch policy. Extending it here would have been a scope violation, not an
oversight. Fixing it would mean routing `reEnqueue`'s outcome through a notifier, which
is a separate decision.

### 46.3 After a cancel, the aggregate count can exceed the rendered list

`cancelBatch()` sets `batchFinished = true` and **clears `batchFailures`** (a cancelled
run makes no per-episode claim - no retry pass completed, so there is no honest list of
"which failed"), but it **deliberately leaves `batchFailed` as-is**.

`batchFailed` is updated by `publishBatchProgress` from the pass that was in flight when
the run was cut short, so after a Cancel the summary line may read e.g.
`failed 1` while the per-episode list below it is empty.

Clearing `batchFailed` too would have changed pre-existing counter behavior that other
tests and the Slice B contract depend on, and it was outside the approved scope. The list
therefore makes a STRICTLY WEAKER claim than the count: it never asserts a per-episode
failure that the count does not, but it can omit one after a cancellation.

Selection changes (`setSelectedAudio`, `setEpisodeAudio`, `setBatchMode`,
`toggleBatchEpisode`, `selectAllEpisodes`, `clearBatchSelection`) likewise leave BOTH
untouched - clearing only the list there would let the two disagree in the other
direction.

### 46.4 Notification posting is best-effort with no permission post-gate

There is **no `POST_NOTIFICATIONS` post-gate**: `postBatchFailureNotification` and
`notifyTerminal` post directly inside a try/catch. On a device where the permission is
denied the platform drops the post silently - the attempt does not throw and does not
corrupt the channel set (pinned by
`BatchFailureNotificationTest.post_paths_never_throw_whatever_the_platform_posting_decision`).

So a user who denied notifications will still see the failure in the app (the rendered
list and the Downloads rows) but will receive no notification. Adding a gate was
explicitly rejected during review: it would change existing behavior outside the
approved scope, and `notify_failure` already provides the user-facing opt-out.

Related: **five** channels exist on a device where a MediaSession has been active -
the four app channels plus the media3-owned one created by
`DefaultMediaNotificationProvider`. That channel is not ours and Slice C adds none; the
app-owned count stays at four, which is what
`fourAppChannelsExistWithTheShippedImportance` asserts.

### 46.5 Verification environment for this slice

- **Full instrumentation: 545 tests / 1 failure - and that test remains FAILING.** The
  failure is `Phase17StreamingSiblingNavigationDeviceTest` asserting Media3 reaches READY
  on a real CDN stream.

  **Corrected attribution (2026-10-12).** This entry previously blamed "LIMITATIONS 43.4 -
  live AnimePahe unreachable from the build environment (hard IP-level Cloudflare 403)".
  That attribution was **wrong**, and a read-only investigation disproved it from the run's
  own logcat: the backend **was reachable** (eight titles scanned, each with a complete
  episode listing), the Cloudflare session **was passed**, the play page and the Kwik page
  were fetched (`htmlLen=22291`) and the M3U8 **was extracted**. No HTTP 403, 404, 410 or
  5xx, no timeout, no DNS failure and no Cloudflare challenge appears anywhere in the
  failure. 43.4 concerns probes that never got past the backend; this test got all the way
  to the media loader, so citing it here was a category error.

  **What the evidence actually shows** is a Media3 malformed-media failure during HLS
  media loading from the resolved CDN host `vault-13.owocdn.top`:

  `ParserException: Loading finished before preparation is complete.
  {contentIsMalformed=true, dataType=1}` at
  `HlsSampleStreamWrapper.maybeThrowPrepareError` (surfaced as `STATE_IDLE` +
  `error="Source error"`, `duration=1450040` - so the manifest had already parsed).

  The **stream-resolution stage SUCCEEDED** and the preceding episode reached
  `STATE_READY`; only the next-episode switch failed. That is the same shape as the
  project's pre-existing, correctly-reclassified account in **42.2** ("real-CDN mid-stream
  `Source error` ... depends on live third-party delivery"), which this entry should have
  cited instead of 43.4.

  **Reproduced in 3 of 3 isolated runs** on the same device, all identical: same episode,
  same media id, `duration=1450040`, same exception, same CDN host, while the sibling
  episode's stream played READY in each run.

  **Underlying cause NOT verified, and deliberately not claimed.** The raw media bytes
  were never captured, so whether that CDN object is permanently malformed or only
  transiently affected is unknown. It is recorded as an observed Media3 parsing failure
  only - not as a permanent defect and not as permanently fixed.

  **This is not a Slice C notification or failure-attribution regression.** Every file on
  the playback path (`PlayerViewModel`, `HlsPlaybackSourceFactory`, `MediaSessionHost`,
  `StreamEpisodeReconstructor`, `AudioSelector`) predates this slice by 4-7 days, and the
  only Slice C code reachable from the test - the resolver's additive `code` parameters -
  never executed, because resolution succeeded. Slice C's own suites stayed green in the
  same run (`BatchFailureNotificationTest` 11/11) and in isolation.

  **Diagnostics deferred:** `awaitHealthy` reports only `playerError.message`
  ("Source error"), so triage required logcat to reach the cause chain. Surfacing the
  complete exception cause chain is deferred to the next slice of work.
- **`CancellationTest` is timing-flaky, not a regression.** It failed in one full run and
  passed in the previous full run, the confirmatory full run, and 2/2 in isolation - all
  on a **byte-identical `app-debug.apk`** (built 11:52:14, before both runs). Identical
  bytecode cannot be a regression. Recorded as a failure in the run that failed, per the
  convention that full-suite failures are never rewritten as zero-failure runs.
- One of this slice's OWN assertions was initially wrong (it counted the TOTAL channel
  count and so tripped over the media3-owned channel once a player test had run). The
  cause was confirmed from `dumpsys` before any change and the assertion was corrected to
  count only app-owned channels. **No production code was modified to make it pass.**

## 47. Final verification gate (2026-10-13) — deviations, unverified paths and open product decisions

This section records what the final gate of 2026-10-13 actually established, in the three
distinct categories the project convention requires: deviations that are deliberate,
paths that were NOT device-verified, and decisions that belong to the user rather than to
the implementer. Nothing below is claimed as full parity; unit tests, instrumentation
tests and physical-device observation are kept strictly separate throughout.

### 47.1 AES empty-ciphertext divergence is a KNOWN INTENTIONAL DEVIATION (not a defect)

The reference implementation at `<python-ref-3.7.0>`, `downloader.py:101-103`, performs
a raw AES-CBC `cipher.decrypt(raw)` with **no unpadding**; for an EMPTY ciphertext it
returns **0 bytes of output**, which the reference treats as success. Android instead
returns **null** for an empty ciphertext: the guard is at `Aes128Decryptor.kt:68-71`, with
the rationale at `Aes128Decryptor.kt:48-50` — a zero-byte segment is corruption rather
than a padding problem, and AGENTS 12 forbids treating incomplete segments as complete.

This is a **known, intentional, directionally-safe divergence**: Android is the stricter
of the two, and it has **not** been equalized to Python. It is asserted explicitly by the
test `empty ciphertext divergence is explicitly documented not silently equalized` in
`AesPythonParityTest.kt`, so any future silent equalization fails loudly rather than
drifting in unnoticed. **Every other vector agrees byte-for-byte**; this single case is
the only one where the two sides deliberately return different results.

### 47.2 No production affordance exists for "valid old export + intentional new download"

A COMPLETED episode with a valid output is **always** suppressed: `DownloadRepository.kt:555`
(`suppressedOutcome`), `DownloadRepository.kt:567` (`outputPresent`), with the rule itself
at `EpisodeOutputVerifier.kt:61`, and the Downloads action matrix offers no Retry action
for COMPLETED rows. There is therefore no supported way for a user to say "my old export
is valid, but I want to download this again anyway".

Where the only evidence of the old download is an **app-private local file** (a
pre-export COMPLETED row), the file is not reachable from a file manager at all, so the
notice text "Already downloaded — delete the existing file to download it again"
(`AnimeDetailsViewModel.kt:511`) is **unactionable for that case** — the instruction
cannot be carried out from anywhere in the product.

This is recorded as a **PRODUCT DECISION AWAITING THE USER, not a defect to be fixed
unilaterally**: `PHASE11_ARCHITECTURE_V2.md:653` deferred replacing a completed export
to a Phase 13 "deliberate UX" that was never built, and `SPEC.md:819` requires explicit
confirmation before deleting completed user media. No force-redownload or replacement
policy is authorized by SPEC or by DECISIONS, so **none was implemented**. Any such
affordance needs an explicit product decision first.

### 47.3 Session-refresh FAILURE branch is not device-verified

The quiet-attempt-first state machine lives in `SessionRefreshViewModel.kt`: the latch at
`:86`, the in-flight guard at `:96`, `:126-127`, the quiet success path at `:139` (which
produces **NO popup**), the failure handling at `:141-142` / `:147` / `:151` (failure →
latch + prompt), and `:188` where the latch is cleared **only on success**.

- **Unit tests only** for the failure branch: `SessionRefreshViewModelTest` **12/12**.
- The quiet **SUCCESS** path **WAS observed on the physical device**: a genuine HTTP 403
  followed by "Session recovered by the background refresh", with no popup.
- The quiet **FAILURE** path (a popup raised because the quiet attempt failed) is now
  **observed on the physical device** (2026-10-23, AQR-138/140 remediation): the
  signal-driven `SessionRefreshPromptDialogTest` PASSES 2/2 on device `REDACTED-SERIAL`
  (isolated run 1/1 in 1.4 s, down from a 15 s timeout).
- **How the prompt became deterministic (2026-10-23):** the former NOT-RUN status was a
  premise defect, not a production defect. Under the quiet-first state machine the FIRST
  signal triggers a real `forceRefreshWebViewSession()`; on a healthy networked session
  that attempt SUCCEEDS, and a successful quiet attempt **correctly raises no prompt**
  (SPEC §6.10) — so the old test (signal → prompt "without touching the network") could
  never see the dialog. The fix injects a test-double `CloudflareSessionProvider` via
  reflection on `AppContainer.cloudflareSession$delegate` (the accepted seam-swap pattern),
  so the quiet attempt fails deterministically and the REAL signal bus → ViewModel →
  AlertDialog → `Not now` dismissal all execute. The double is restored in `@After` (the
  container is process-wide). ONLY the refresh outcome is stubbed; WHEN to prompt remains
  pinned by the JVM `SessionRefreshViewModelTest` (14/14). The test's KDoc documents the
  controlled outcome.
- **Not weakened, not a timeout raise:** no assertion was removed and the 15-second
  `waitUntil` bound was kept; only the (previously network-dependent) quiet-attempt
  outcome is now controlled. The no-prompt pre-condition (healthy startup shows no
  "Refresh now") still runs against the real session first.

### 47.4 Notification audio-language labels are not device-observed

The label path is `EpisodeDownloadEntity.kt:59-60` (`audio`) → `DownloadWorker.kt:427`,
`:546`, `:945`, `:951` → `DownloadNotificationManager.kt:116`, `:130`, `:140`, `:87`,
with the fallback at `DownloadRowMapper.kt:165-170`.

It is proven by **JVM unit tests only** — `DownloadNotificationAudioLabelTest` **11/11**
and `DownloadNotificationTextTest` **8/8**, which assert the exact output text of the
pure helpers. **NOT proven:** that a notification actually posted by a running Worker on
a physical device displays the label, nor that channel / grouping / replacement
behaviour is correct. A live download was **deliberately not initiated** merely to watch
a text-formatting change, so no device-observed notification claim is made here.

### 47.5 Notification worker wiring is unasserted, and the fallback's open set is not enforced at the boundary

- **No test asserts** that `DownloadWorker.kt:427`, `:546`, `:945`, `:951` actually
  forward `episode.audio`, nor that `notifyTerminal` forwards the typed
  `FailureCategory`; that link is established by **reading the source only**.
- `DownloadRowMapper.kt:165-170` uppercases ANY non-jpn/eng non-blank value. Production
  values come from the source listing's `audio` field (`AnimePaheParser.kt:130`), and the
  `^[a-z0-9]{1,16}$` shape check exists only in `EpisodeId.parse` — **not** in
  `EpisodeId.build`, nor at enqueue. An unexpected hostile value could therefore be
  echoed (uppercased) into notification text. This is **theoretical and unobserved**, and
  it cannot carry a cookie, token or session value, but it means "only a language name or
  code ever reaches this line" is an **assumption rather than an enforced invariant**.

### 47.6 Verification environment for this gate

- **Full JVM suite: 105 suites / 1,141 tests / 0 failures / 0 errors / 0 skipped.**
  `lintDebug` **38 warnings, 0 errors**. `assembleDebug` **BUILD SUCCESSFUL**; APK
  **119,242,126 bytes**, SHA-256
  `6411B70BE9FEA6E2010808A89285BC5678699CA1BA9B934E78117CAEB95B2267`.
- **Python reference RUNTIME parity** (not merely source-level) was established by
  actually executing the WSL venv `<python-ref-3.7.0>/venv/bin/python3` = **Python
  3.14.4** with **pycryptodomex 3.24.0**; fixtures
  `app/src/test/resources/aes_parity_reference.tsv` (3,797 bytes, SHA-256
  `E4E44BAB…D45F`) and `.json` (SHA-256 `5A292D73…5812`) were re-executed byte-for-byte.
  This **supersedes** the earlier claim in DECISIONS 150.11 that no Python interpreter was
  available; see **DECISIONS 151** for the authoritative record.
- **Device:** exactly one device, serial `REDACTED-SERIAL` (model `REDACTED-MODEL`), Android user 10
  "Android Dev". `firstInstallTime` **2026-10-12 11:56:22** and `lastUpdateTime`
  **2026-10-13 10:38:35**, plus App Data Size **4,034,385,636 bytes**, were **UNCHANGED**
  before and after — no uninstall, no data clear. A real search returned **5 genuine live
  results**. All four exports remained present with unchanged sizes and `is_pending=0`.
  Instrumentation was run via an **explicit single-class filter** against the explicit
  serial and `--user 10`, never the broad `connectedDebugAndroidTest`:
  `AnimeDetailsScreenTest` **OK (34 tests)** and
  `SessionRefreshPromptDialogTest#theProductionFactoryBuildsThePromptViewModel` **OK
  (1 test)**.


## 48. Replace file (DECISIONS 152) - what stays conditional (2026-10-13)

- **The notification action was never tapped on the device.** The `Replace file` action, its per-episode request code, its immutable PendingIntent and the `(replacement failed, existing file kept)` suffix are asserted by unit and instrumentation tests, and the identical dialog was opened from the in-app row affordance on serial `REDACTED-SERIAL`. What was NOT observed is a tap on the action inside a real system notification, through `MainActivity`'s `replace:` route. Unverified path, stated as such.
- **No real export was ever replaced.** Cases 15 and 16 run the real `DownloadWorker` against real filesystem bytes - a staged MP4 committed onto an old export path, and a 404 that leaves the old export byte-identical - but both operate on files the test created. Running a genuine replacement over one of the four existing exports would deliberately destroy user media, which is forbidden here, so the production end-to-end path over a real completed export is NOT device-verified.
- **Atomicity is bounded by what the tests assert.** The claims are a same-volume atomic rename of the staged file onto the canonical path and compare-and-set row writes. No atomicity is claimed across the export pass as a whole: a crash between the commit write and the retirement leaves `replacePriorUri` set with the new export already referenced, which is the safe direction - the old file is simply not yet retired, and a later export pass retries it rather than losing track.
- **MediaStore name uniquification after retirement.** When the prior export is a MediaStore item and the replacement is written under the same display name, the retirement of the old row can leave the new item holding a uniquified ` (1)` form. No post-retire rename is attempted, because renaming a visible user file after the fact would reintroduce exactly the delete-before-verify hazard this feature exists to avoid. Cosmetic only: the file content, the recorded `outputPath`/`outputUri` and playback are unaffected.
- **A NULL `replacePriorUri` is not distinguishable from "no prior" by reading the column alone.** For an app-private prior the column is legitimately NULL because the prior is the file at `outputPath` itself and the commit renames over it. Code that inspects the column must therefore be read together with `replaceRequested`; `clearReplacementIntent` is guarded on that flag precisely so a NULL prior on a COMPLETED row is never mistaken for an unset retirement.
- **The two exporters still retire differently, on purpose.** `MediaStoreExporter` deletes the prior row through `ContentResolver`, while `SafExporter` deletes the prior document through `DocumentsContract`; the shared `PriorOutputRetirement` refuses the delete when the outcome URI equals the prior or when another episode row still points at it. The divergence between the two destinations is the pre-existing one and was deliberately not harmonized by this feature.

## 49. App-page paging (DECISIONS 153) - what stays conditional (2026-10-17)

- **An unknown total is rendered, not resolved.** The pager states `total unknown` and derives its
  page COUNT from `per_page x last_page` - a bound the source itself stated, not a total. That bound
  can exceed the real ordinal count, so the LAST app page may legitimately hold fewer rows than its
  size. Nothing is invented, and a real `0` stays distinguishable from unknown.
- **Opening a long title costs more than opening a short one.** A 250-episode title reads 4 of its 9
  windows on open (app page 1) plus the one bounded probe window, where the previous slice read 1.
  This is inherent to showing 100 episodes on the first page: the rows have to come from somewhere.
  It is still never the whole series, and the probe's `last_page` window remains bounded.
- **The page cache is bounded at 16 windows, and that is a real cost on a wide title.** Walking all
  six app pages of a 520-episode title opens 18 windows, so the first ones are dropped; returning to
  them costs one request each. The eviction is honest (an evicted window is re-fetched for real and
  never reported as present), but it is not free.
- **A SELECTION batch can name episodes the bounded cache has since dropped.** `select all` over a
  250-episode title selects 100 numbers; if a later range request evicts the windows carrying some
  of them, those numbers resolve as `EPISODE_NOT_IN_LISTING` with the per-episode reason the existing
  batch machinery already reports. A PAGE batch does not have this problem: it merges its own source
  rows into the listing it resolves against (DECISIONS 153.5).
- **No device verification in this pass.** Everything above was verified host-side by the JVM suite
  (108 suites / 1,178 tests / 0 failures). Nothing on this page was observed on `REDACTED-SERIAL`; the
  device rows in PARITY stay negative for that reason.
  *(Superseded 2026-10-17 by DECISIONS 154: the pager, page navigation, the ordinal range and the
  already-downloaded skip were all device-verified on `REDACTED-SERIAL` (user 10, real service), so the
  PARITY device rows for those are now positive. Two things stay host-side only: the page-batch
  queue CONTENTS - its label `Download this page (100 episodes)` was read on device but executing it
  would start ~100 real downloads - and multi-audio identity, which One Piece on this instance does
  not offer (only `jpn`).)*
- **The ordinal range is positional, not by-episode-number, and needs a known total.** *(Superseded
  2026-10-17 by DECISIONS 155: the older expression field `Range e.g. 1-12` was REMOVED as a
  confusing duplicate; `From`/`To`/`Range` is now the single range control.)* It selects by the API's
  stable `episode_asc` ORDERING (position), not by episode number, so on a specials title where
  positions and numbers diverge (e.g. `1.5`, `2.5`) a `From`/`To` of `1`–`2` means the first two
  positions, not the episodes numbered 1 and 2 — an open-ended or by-number reading (`13-`) is not
  supported. When the listing exposes no total, the control falls back to the loaded-catalogue
  NUMBER range (`selectEpisodeRange`, honest about unmatched ranges), so it cannot fetch unloaded
  windows in that branch. These are the honest boundaries of the positional selector, not silent
  failures.
- **`Latest N` on a PAGED title selects the newest among LOADED rows, not the whole catalogue.**
  *(Found during the DECISIONS 156 review; deliberately NOT changed there.)* `selectLatestEpisodes(N)`
  picks the N highest episode NUMBERS the current listing carries. For a ≤100-episode title (one app
  page) that is the catalogue's newest N, matching the intent. For a >100-episode title the listing is
  windowed, so on app page 1 it is the newest N among the LOADED window — not the series' newest N.
  Correcting it to reach the whole catalogue is a paging/range-architecture change (it would need to
  resolve the tail windows independently of the loaded page), which is out of scope for the bounded UI
  cycle and is recorded here so the behaviour is not mistaken for full-catalogue `Latest`.
- **The responsive pager's wide-window branch is not Compose-testable on the ~461dp test device.**
  `Go to page` visibility now derives from the number strip's measured width (DECISIONS 156.1). On
  `REDACTED-SERIAL` (~461dp window, ~271dp numbers slot between `Prev`/`Next`) the slot only ever fits ~4
  real buttons, so the budget never grows past the `PAGE_NUMBER_BUDGET = 4` floor for any page count
  the device can show — visibility there is page-count-driven (`≤4` hides, `≥5` shows), identical to
  the prior fixed behaviour. The "wide window fits every page → hide" transition needs a wider window
  (tablet/landscape) than the Compose test can model (a `Box` width override can only go NARROWER than
  the device), so that growth is covered by the JVM `AnimeDetailsPageBarBudgetTest` (a deterministic
  function of width/font-scale), not by Compose. The Compose suites cover the all-fit hide (4 pages),
  the ellipsis show (12 pages), constrained-width validation, double-font-scale accessibility, and a
  state-driven width change that keeps validation working.

## 50. Prompt 3 final release gate (DECISIONS 157) — what stays conditional (2026-10-19)

- **The destination probe covers the MediaStore route only; an active SAF tree is never probed.**
  `ExportDestinationRouter.destinationOutput` returns `null` while a configured SAF tree is
  `Valid` + `rename_ok=true` (walking a user-owned SAF tree is outside this gate). Effect: over a
  surviving SAF export with no DB row, a re-download is ADMITTED exactly as before this gate — the
  SAF exporter then finds the existing document and takes its `AlreadyExported` outcome.
  Conservative divergence: never a false suppression, never a false rejection (DECISIONS 157.1).
- **The probe does not cover legacy flat-name exports.** The gate probes the per-anime Gate A
  directory (`Movies/<session title>/`); an export still living under the legacy flat
  `Movies/PaheBatcher/` name is not recognized as a surviving destination, so a re-download over it
  runs and the exporter adopts the legacy file — the same end state as before this gate, never a
  false suppression (review finding 10, DECISIONS 157.5).
- **MediaStore ` (1)` uniquified replace-name drift is accepted (§30.2-tolerated).** A Replace file
  keeps the old row until the new one is verified, so MediaStore can hand the new row a ` (n)`
  display name; the probe's `nameMatches` treats `stem (n).mp4` as the same destination, and the
  DB row may hold a display name that no longer equals the file's actual name. Display-only drift;
  identity checks (audio suffix, session directory) are unaffected.
- **Uninstall-orphaned exports are invisible to owner-scoped queries — a re-download then creates a
  uniquified second file.** A package uninstall clears MediaStore `OWNER_PACKAGE_NAME` (observed on
  this device after the pre-gate reinstall); after reinstall the probe and the exporter's stale-row
  lookups (owner-scoped by design, shared with the export pipeline) cannot see those rows. The
  foreign-owned file must never suppress a download, so the re-download proceeds and MediaStore
  uniquifies (` (1)`). Deliberate: suppressing on files we can no longer attribute would risk
  suppressing on other apps' files (DECISIONS 157.6d).
- **`MediaStoreExportTest` legacy-name fixture/timing flake (pre-existing).** One instrumented batch
  (2026-10-18) failed a legacy-name case correlated with back-to-back suite residue; the class ran
  clean 23/23 on the closure re-run (2026-10-18 and 2026-10-19). The pre-existing legacy code path
  was not modified in this cycle; the flake is recorded so a future failure is not mistaken for a
  Prompt 3 regression.
- **`SessionRefreshPromptDialogTest` signal case — RESOLVED 2026-10-23 (AQR-138/140).**
  Formerly a pre-existing design/live incompatibility: a quiet background refresh SUCCESS
  suppresses the prompt BY DESIGN, so the signal case's `waitUntil` timed out and it was
  classified NOT RUN at prior gates (running it unchanged would have weakened it — the old
  premise was "signal → prompt without touching the network"). It now RUNS and PASSES 2/2
  on device `REDACTED-SERIAL` WITHOUT weakening: the quiet-attempt OUTCOME (the only
  network-dependent part) is made deterministic by a test-double
  `CloudflareSessionProvider` injected via reflection on
  `AppContainer.cloudflareSession$delegate` and restored in `@After`; the real signal bus,
  ViewModel, AlertDialog surface and `Not now` dismissal all execute, no assertion was
  removed, and the 15-second `waitUntil` bound is unchanged. WHEN to prompt is still
  pinned by the JVM `SessionRefreshViewModelTest` (DECISIONS 157.3); the latch logic is
  untouched production behaviour (SPEC §6.10).
- **Layer-B coexistence sweep deliberately deferred.** Automatic de-duplication of already-created
  duplicate exports at reconcile time was considered in DECISIONS 157.1 and not implemented:
  automatically deleting user-visible files is riskier than the honest admission-gate rejection that
  now prevents NEW duplicates.

## 51. PB-004 media-session FGS promotion window - RESOLVED 2026-10-21 (live-verified 2026-10-20)

- **[pre-fix record - rewritten 2026-10-21 per AQR-105: these bullets were present-tense prose
  under a RESOLVED heading; `MediaSessionHost.kt:107` names the PRE-FIX line, and the current
  plain-`startService` call is `MediaSessionHost.kt:144`] The session path had no guaranteed
  `startForeground()` inside the platform window.**
  `MediaSessionHost.startService` (pre-fix `MediaSessionHost.kt:107`) issued `startForegroundService()`,
  after which Android REQUIRES a `startForeground()` transition within the system window
  (documented as ~5 s) or it throws
  `ForegroundServiceDidNotStartInTimeException` at the next check, killing the app. The A2.1 guard
  covered only the two non-session exits - `PaheMediaSessionService.exitWithoutSession()`
  (placeholder notification id `-9004` on the LOW-importance downloads channel, then
  `STOP_FOREGROUND_REMOVE` + `stopSelf`) in the no-player and build-failure branches. The
  SESSION branch relied on media3 promoting the service itself, which happens only once a media
  notification exists.
- **[pre-fix record] Live device evidence (serial REDACTED-SERIAL, 2026-10-20, `MediaSessionServiceForegroundExitTest`):**
  case 1 (no-player start) PASSED the real window - the guarded exit was live-verified; case 2 (a
  real `ExoPlayer` + session, empty playlist, notification never posted) FAILED with the real
  system exception originating at the pre-fix `MediaSessionHost.kt:107` via `onPlayerCreated`
  (pre-fix revision line 55, now `MediaSessionHost.kt:73`). Result XML retained at
  `app/build/outputs/androidTest-results/connected/archive/CLOSURE-2026-10-20-MediaSessionServiceForegroundExitTest.xml`.
- **[pre-fix record] Why it was not fixed at that time:** closing the window was a production
  service change (for example a guaranteed guarded placeholder on the session path until media3
  posts its notification, released when the real notification appears). Deferred pending separate
  authorization - inventory PB-004 was `DEFERRED - USER DECISION REQUIRED` (DECISIONS 161.2); the
  related latent staging-delete edge AQR-069 is likewise recorded, not implemented.
- **[pre-fix record] Practical impact at that time:** in production the window is normally
  milliseconds (playback is started right after `onPlayerCreated`), so the failure reproduced only
  when the player was created and playback never began (no media item set, or playback not
  started). That edge was a possible app-kill - it was NOT observed on the normal play path.
- **RESOLUTION 2026-10-21 (authorized PB-004 round): the session-path window no longer exists.**
  `MediaSessionHost.startService` now issues a plain `Context.startService()`, so the platform
  FGS contract is never created at player creation; media3 1.11.1 owns promotion end-to-end and
  re-issues its own foreground-start self-intent when user-engaged playback makes the media
  notification appear (traced from the official androidx/media tag `1.11.1` sources; empty
  timeline never posts a notification, so the pre-fix contract was unsatisfiable). The live
  failure above is retained as the pre-fix record
  (`archive/CLOSURE-2026-10-20-MediaSessionServiceForegroundExitTest.xml`, 2 tests F=1);
  post-fix device evidence on REDACTED-SERIAL:
  `archive/PB004-2026-10-20-MediaSessionServiceForegroundExitTest.xml` 3/0/0 plus the final
  chain `archive/FINAL-2026-10-21-*` (14 classes, 55 tests, 0F/0E). Inventory PB-004 =
  `FIXED — VERIFIED`; the bullets above are the historical pre-fix record (DECISIONS 162).

## 52. Residual edges from the PB-004/UI-008 round (2026-10-21)

- **Background-refused plain start degrades silently (PB-004 review AQR-075).** If a background
  context ever called `MediaSessionHost.startService`, the plain `startService()` could be
  refused under Android's background-start limits and the existing catch would only log - no
  foreground service, no user-visible error. Unreachable today: both call sites
  (`PlayerViewModel.ensurePlayer`) are user-initiated from the foreground. Recorded so any
  future background call site re-examines this edge before reusing the helper.
- **Seek-slider hardware keyboard / D-pad adjustment is unverified (UI-008 review AQR-078).**
  The commit-on-finish contract is device-verified for finger gestures and for the accessibility
  `SetProgress` route (`PlayerSeekSliderDragTest`, 3/0/0), but Compose instrumentation exposes
  no key-event injection for this node and material3 1.4.0 sources are not in the local Gradle
  cache - so no keyboard/D-pad behavior claim is made. Related accepted behavior: the slider may
  show the pre-seek ticker value for up to one 500 ms tick after release before the ticker
  catches up with the seeked position (AQR-083) - bounded and documented in the slider slot
  KDoc, an inherent property of the commit-on-finish contract, not an open defect.

## 53. Navigation remediation residuals (2026-10-23) - two product decisions, BOTH RESOLVED 2026-10-23

The bounded AppNavHost remediation round (AQR-138..145) fixed every safe defect in
place: the same-episode no-op (no pop, no re-seed, mode untouched), the blank-id
discard (DECISIONS 118.2 default routing), typed tab-switch overloads, and the
import/pointer cleanups. Two questions had no product ruling anywhere in the
repository and were deliberately not decided by that round. Both were adopted and
implemented on 2026-10-23; the original findings are retained below as the audit
record, with each resolution stated first.

### 53.1 AQR-138 - notification-tap playback mode

**RESOLVED 2026-10-23 (adopted): a player notification target carries an EXPLICIT
playback mode.**

- **Format:** `player:<episodeId>:<mode>`, with `mode` one of `download` / `stream`,
  produced and consumed through the one typed contract
  `com.pahebatcher.app.ui.navigation.PlayerTarget` (`encode` / `parse` beside
  `PlayerMode`). Episode ids are `{session}_{epStr}_{audio}`
  (`EpisodeId`, no `:` in any component), so the LAST `:` always separates the id
  from the mode token.
- **Producer:** the media-session path - `PlayerViewModel.ensurePlayer` records the
  route seed's mode on `MediaSessionHost.onPlayerCreated(player, episodeId, mode)`,
  `PaheMediaSessionService.ensureSession` reads it back, and
  `MainActivity.createSessionActivityIntent(context, episodeId, mode)` always emits
  an explicit mode. No download-notification path opens the player
  (`DownloadNotificationManager`'s intents target `downloads` / `replace:` only), so
  the media session was the only producer to change.
- **Consumer:** `AppNavHost` parses the target and calls
  `navigateToPlayer(episodeId, playerTarget.mode)`, so a COLD-START tap opens the
  target's mode rather than the route's `PlayerMode.DOWNLOAD` default, and a
  different-episode tap replaces in the mode the target names. A same-episode tap is
  still a no-op keyed on the episode id alone, so the running player's mode, entry,
  ViewModel, ExoPlayer and MediaSession are never touched. Blank ids and unknown
  mode tokens parse to `null` and are consumed without navigation (§19.1).
- **Compatibility:** the pre-AQR-138 bare `player:<episodeId>` form is retained and
  reads as `PlayerMode.DOWNLOAD` (DECISIONS 118.2); production no longer EMITS it on
  the media-session path, so an ambiguous stream notification cannot arrive in that
  shape.
- **Verification:** `PlayerTargetTest` (12 pure-JVM tests, executed) plus
  `IntentNavigationTest` cold-start / cross-episode / same-episode-mode /
  invalid-target cases (instrumentation compiled; device execution pending for this
  round - see the verification report).
- **Residual (PRE-EXISTING, unchanged by this round):** the session activity target
  names the episode + mode recorded when the ExoPlayer was CREATED.
  `MediaSessionHost.onPlayerCreated` is the only writer of `episodeId`/`mode`, and
  `ensureSession` returns early once a session exists, so a route re-seed of the SAME
  player instance in place (§24.2 episode/mode switch) is not re-issued to the
  session activity - the tap then opens the creation-time episode/mode. This mismatch
  predates AQR-138 (the episode id alone was already fixed at creation); closing it
  requires rebuilding the session activity, i.e. a MediaSession lifecycle change that
  this round was explicitly scoped not to make.

- **Original finding (retained, superseded by the resolution above):**

  - **Guaranteed then:** while a player entry for the TAPPED episode was already top,
    `player:<episodeId>` was a no-op - the route kept its `PlayerMode`, the entry's
    ViewModel and its ExoPlayer were untouched, and a live STREAM session's tap could
    never be flipped to the `PlayerMode.DOWNLOAD` route default. Regression tests:
    `IntentNavigationTest.sameEpisodeStreamPlayerTargetPreservesStreamMode` and
    `IntentNavigationTest.sameEpisodePlayerTargetWhilePlayerIsTopIsANoOp`.
  - **Not established then:** for a COLD-START tap, or a tap naming a DIFFERENT
    episode while a stream session was (or was last) active, no repository document
    ruled which mode the player should open. SPEC 18 said only "return to the
    relevant ... player screen"; DECISIONS 118 and PHASE14_PLAN section 7 predate
    PlayerMode; the PlayerMode KDoc merely DESCRIBED the current wiring (route
    default = DOWNLOAD). The target string `player:<episodeId>` carried no mode.
  - **Then-open question:** what mode SHOULD a cold-start or different-episode tap of
    a stream session's notification open? (a) STREAM - requires a mode signal;
    (b) keep the current DOWNLOAD route default; (c) another ruling. Behavior stayed
    (b) plus the active-player no-op until the adoption above chose (a) via an
    explicit mode signal in the target.

### 53.2 AQR-140 - a `replace:` request can be deferred by a restored in-tab stack

**RESOLVED 2026-10-23 (adopted: the earlier "force Downloads to the top" option) -
`replace:<episodeId>` foregrounds Downloads at its ROOT, then processes the request.**

- **Implementation:** the replace branch routes through
  `NavHostController.navigateToDownloadsForReplacement()`, which is the §10.4 tab rule
  with `restoreState = false` and nothing else changed (`popUpTo(HomeRoute) {
  saveState = true }` + `launchSingleTop`), so a saved nested stack is NOT restored
  and the tab is entered at its root destination. Delivery is unchanged: the one-shot
  `LaunchedEffect(pendingReplacementEpisodeId)` lives inside
  `composable<DownloadsRoute>`, which only composes while Downloads is the active
  destination, so the request reaches the ViewModel after the tab is foregrounded and
  the confirmation is presented on the visible Downloads screen.
- **Multi-back-stack preserved:** the pop still uses `saveState = true`, so the
  user's saved in-tab position is retained for ordinary tab switches; nothing about
  Home's or any other tab's navigation changed.
- **Safeguards unchanged:** confirmation is still required; the ViewModel gate still
  re-validates the id against Room before any dialog is armed (DECISIONS 152) and the
  repository re-applies the same §29 rule at confirmation time; Cancel and back remain
  a pure state clear with no repository call; a stale/invalid target produces an
  honest message and no dialog and no write; duplicate delivery stays single-slot
  (one target, one dialog, one stack entry) and a second CONFIRM cannot run because
  the repository's persisted `replaceRequested` claim makes a repeat lose.
- **Verification:** `ReplaceTargetNavigationTest` - the restored-stack reproduction
  was UPDATED (not deleted) to assert the adopted foreground-then-confirm behavior,
  and the class was extended with root / nested / confirm-once / cancel /
  duplicate / invalid-target / recreation cases (instrumentation compiled; device
  execution pending for this round).

- **Original finding (retained, superseded by the resolution above):**

  - **Mechanism (pinned by the then-named
    `ReplaceTargetNavigationTest.replaceTargetOnRestoredStackDefersTheRequestUntilDownloadsIsTopAgain`):**
    the replace target routed through the §10.4 tab rule, so a restored Downloads tab
    whose saved stack had another destination pushed (e.g. Session Manager) came back
    with THAT destination on top; the consume-once arming effect lives in the
    Downloads destination and did not run until the user returned to Downloads. The
    confirmation dialog therefore appeared at that later moment, not at tap time.
  - **Safety then (unchanged today):** nothing destructive ever happened without the
    dialog; the request was re-validated against Room by the ViewModel gate
    (DECISIONS 152), Cancel and back cleared it with no repository call, the hoisted
    request and the dialog target are single-slot, and the target string is consumed
    after its first processing.
  - **Alternative A - discard a deferred request:** clear the hoisted request when
    the restored stack does not land Downloads on top. User-visible: the tap never
    produces a later (possibly surprising) dialog; while the user sits in Session
    Manager the action appears to do nothing, and a fresh tap is required.
  - **Alternative B - force Downloads to the top:** pop the restored destinations
    above Downloads before arming. User-visible: the dialog always appears promptly at
    tap time, but the user's saved in-tab position (the open Session Manager, etc.) is
    no longer restored for that entry.
  - **Then-current behavior - defer:** the saved stack was restored, the request
    waited, and the dialog appeared when the user returned to Downloads - tab position
    preserved, prompt timing disconnected from the tap. **Adopted alternative B
    supersedes the defer behavior; the test above was updated accordingly.**
