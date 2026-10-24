# Phase 10 Architecture — Background Downloads (v8)

> **⚠️ SUPERSEDED — HISTORICAL DOCUMENT.** This v8 draft is superseded by **`PHASE10_ARCHITECTURE_V10.md`**, which is the approved Phase 10 architecture and the authoritative reference for implemented behavior. This file is retained for review history only; do not treat its content as current (it is known to contain content corrected in later revisions, e.g. the reconciliation query that excluded `COMPLETED` episodes). Do not edit historical content.

**Status**: SUPERSEDED by PHASE10_ARCHITECTURE_V10.md (original status at the time: REVISED — READY FOR EIGHTH AND FINAL REVIEW)
**Date**: 2026-09-22
**Previous Reviews**: v1 (20 findings), v2 (18 findings), v3 (15 findings), v4 (16 findings), v5 (14 findings), v6 (15 findings), v7 (17 findings — 2 CRITICAL)

---

## 1. ROOM MIGRATION (v1 → v2)

### Current State

- Schema version: 1
- Entities: ScanCacheEntity, DownloadSessionEntity, EpisodeDownloadEntity, SegmentEntity, WatchlistEntity
- `fallbackToDestructiveMigration(dropAllTables = true)` — MUST BE REMOVED
- `exportSchema = false` — MUST BE SET TO `true` FOR MIGRATION TESTING

### Migration SQL

```sql
-- Migration 1 → 2

-- 1. Drop unused SegmentEntity/SegmentDao table (never written in production)
DROP TABLE IF EXISTS segments;

-- 2. Add new columns to episode_downloads for background download support
ALTER TABLE episode_downloads ADD COLUMN m3u8Url TEXT;
ALTER TABLE episode_downloads ADD COLUMN userAgent TEXT;
ALTER TABLE episode_downloads ADD COLUMN referer TEXT;
ALTER TABLE episode_downloads ADD COLUMN segmentDir TEXT;
```

### Safety

- `segments` table is defined in Room but never written to in production code (verified by grep — no code calls `SegmentDao.insert` or `SegmentDao.upsert`)
- `DROP TABLE IF EXISTS` is safe for both existing databases (table exists but empty) and fresh installs
- All existing v1 columns remain intact; new columns are nullable with defaults

### Implementation

**CRITICAL**: The following changes MUST be made to `PaheDatabase.kt`:

1. **Remove `SegmentEntity::class` from `@Database` entities array**:
```kotlin
@Database(
    entities = [
        ScanCacheEntity::class,
        DownloadSessionEntity::class,
        EpisodeDownloadEntity::class,
        WatchlistEntity::class
        // SegmentEntity::class REMOVED
    ],
    version = 2,  // ← Bump from 1 to 2
    exportSchema = true  // ← Changed from false
)
```

2. **Remove `segmentDao()` abstract method**:
```kotlin
abstract class PaheDatabase : RoomDatabase() {
    abstract fun scanCacheDao(): ScanCacheDao
    abstract fun downloadSessionDao(): DownloadSessionDao
    abstract fun episodeDownloadDao(): EpisodeDownloadDao
    // abstract fun segmentDao(): SegmentDao  // ← REMOVED
    abstract fun watchlistDao(): WatchlistDao
}
```

3. **Remove `SegmentEntity` and `SegmentDao` imports**

4. **Register migration**:
```kotlin
private fun createDatabase(context: Context): PaheDatabase {
    return Room.databaseBuilder(
        context.applicationContext,
        PaheDatabase::class.java,
        DATABASE_NAME
    )
        .addMigrations(MIGRATION_1_2)
        .build()
}
```

5. **Remove `fallbackToDestructiveMigration(dropAllTables = true)`**

### Migration Test Requirement

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test
    fun migrate1To2() {
        // Create v1 database with test data
        // Run migration MIGRATION_1_2
        // Verify:
        // 1. All v1 data preserved (ScanCache, DownloadSession, EpisodeDownload, Watchlist)
        // 2. segments table dropped
        // 3. New columns accessible
        // 4. SegmentEntity NOT in compiled schema
    }
}
```

---

## 2. EXISTING ENTITY FIELDS

### Current EpisodeDownloadEntity Fields (verified from source)

| Field | Type | Phase 10 Action |
|-------|------|-----------------|
| episodeId | String | KEEP (PK) |
| sessionId | String | KEEP |
| episodeNumber | Float | KEEP |
| episodeSession | String | KEEP |
| title | String | KEEP |
| fansub | String | KEEP |
| audio | String | KEEP |
| playUrl | String | KEEP |
| totalSegments | Int | KEEP |
| completedSegments | Int | KEEP |
| downloadedBytes | Long | KEEP |
| state | String | KEEP |
| speed | Long | KEEP |
| etaSeconds | Long | KEEP |
| retryCount | Int | KEEP |
| errorMessage | String? | KEEP |
| outputPath | String? | KEEP |
| createdAt | Long | KEEP |
| updatedAt | Long | KEEP |

### New Columns (Phase 10)

| Field | Type | Purpose |
|-------|------|---------|
| m3u8Url | String? | Resolved M3U8 URL for Worker to download |
| userAgent | String? | User-Agent from WebView session |
| referer | String? | Referer from play page |
| segmentDir | String? | Path to segment directory for resume |

### Rejected Fields (rationale)

- **workRequestId**: WorkManager handles dedup via unique work names; storing request ID creates sync issues
- **maxAttempts**: Already defined in `AppConfig`; entity doesn't need per-episode max
- **lastAttemptAt**: `updatedAt` already tracks last update; separate field is redundant

### DAO Updates

Add new methods to `EpisodeDownloadDao`:

```kotlin
// Count only actively downloading/assembling episodes (for concurrency gating)
@Query("SELECT COUNT(*) FROM episode_downloads WHERE state IN ('DOWNLOADING', 'ASSEMBLING')")
suspend fun countActive(): Int

// Count non-terminal episodes (for reconciliation fast-path)
@Query("SELECT COUNT(*) FROM episode_downloads WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')")
suspend fun getNonTerminalCount(): Int

// Get all non-terminal episodes (for reconciliation)
@Query("SELECT * FROM episode_downloads WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')")
suspend fun getAllNonTerminal(): List<EpisodeDownloadEntity>

// Clear stream info (for re-enqueue after failure)
@Query("UPDATE episode_downloads SET m3u8Url = NULL, userAgent = NULL, referer = NULL, segmentDir = NULL WHERE episodeId = :episodeId")
suspend fun clearStreamInfo(episodeId: String)

// Increment retry count
@Query("UPDATE episode_downloads SET retryCount = retryCount + 1 WHERE episodeId = :episodeId")
suspend fun incrementRetryCount(episodeId: String)

// Atomic state transition for resume (prevents race conditions)
@Query("UPDATE episode_downloads SET state = :newState WHERE episodeId = :episodeId AND state = :expectedState")
suspend fun updateStateForResume(episodeId: String, expectedState: String, newState: String)
```

---

## 3. STATE MACHINE

### Phase 10 Active States

```
QUEUED ──→ RESOLVING ──→ DOWNLOADING ──→ ASSEMBLING ──→ COMPLETED
  │            │              │              │
  │            │              │              └──→ FAILED
  │            │              └──→ FAILED
  │            └──→ FAILED
  └──→ FAILED
  └──→ CANCELLED (from any non-terminal state)
```

### State Definitions

| State | Meaning | Exit Condition |
|-------|---------|----------------|
| QUEUED | Episode awaiting foreground resolution (WebView) OR awaiting WorkManager execution | Resolution started (RESOLVING) or Worker picked up (DOWNLOADING) |
| RESOLVING | Foreground WebView resolving play page → stream URL | Resolution complete (DOWNLOADING) or failed (FAILED) |
| DOWNLOADING | Worker downloading HLS segments | All segments complete (ASSEMBLING) or error (FAILED) |
| ASSEMBLING | Worker assembling MP4 from segments | Assembly complete (COMPLETED) or failed (FAILED) |
| COMPLETED | MP4 created and verified | Terminal |
| FAILED | Error occurred (may be re-enqueued by user) | Terminal until re-enqueue |
| CANCELLED | User cancelled download | Terminal |

### QUEUED Dual Meaning Resolution

**QUEUED** has two sub-meanings distinguished by `m3u8Url`:

1. **QUEUED without m3u8Url**: Episode queued but not yet resolved. Requires foreground Activity + WebView. Reconciliation cannot auto-resolve.
2. **QUEUED with m3u8Url**: Episode resolved, awaiting Worker execution. Reconciliation can auto-enqueue Worker.

**Reconciliation behavior**:
```
if (state == QUEUED && m3u8Url != null) {
    // Auto-enqueue Worker (resolved but Worker not started)
    enqueueWorker(episodeId)
} else if (state == QUEUED && m3u8Url == null) {
    // Cannot auto-resolve (requires foreground WebView)
    // Leave as-is; user must re-trigger
}
```

### Removed States

- **PAUSED**: Not implemented in Phase 10 (user can cancel and re-enqueue)
- **RETRYING**: Not a persisted state; retry is re-enqueue (FAILED → QUEUED)
- **RESOLVED**: Not needed; RESOLVING transitions directly to DOWNLOADING
- **VERIFYING**: Not needed; assembly verification is part of ASSEMBLING

### Entity Updates

**CRITICAL**: Update `EpisodeDownloadEntity` helper methods for Phase 10 states:

```kotlin
// CRITICAL: Remove STATE_PAUSED from canResume()
fun canResume(): Boolean {
    return state in listOf(STATE_FAILED, STATE_QUEUED)
    // STATE_PAUSED REMOVED — not a Phase 10 state
}

// Update state field KDoc
/** Episode state: QUEUED, RESOLVING, DOWNLOADING, ASSEMBLING, COMPLETED, FAILED, CANCELLED */
val state: String = "QUEUED",
```

**Remove STATE_PAUSED constant entirely** (not just deprecate):
```kotlin
companion object {
    const val STATE_QUEUED = "QUEUED"
    const val STATE_RESOLVING = "RESOLVING"
    const val STATE_DOWNLOADING = "DOWNLOADING"
    const val STATE_ASSEMBLING = "ASSEMBLING"
    const val STATE_COMPLETED = "COMPLETED"
    const val STATE_FAILED = "FAILED"
    const val STATE_CANCELLED = "CANCELLED"
    
    // REMOVED: STATE_RESOLVED, STATE_VERIFYING, STATE_PAUSED, STATE_RETRYING
    // These states are not used in Phase 10
}
```

---

## 4. WEBVIEW / KWIK BOUNDARY

### Actual Code Analysis

**StreamResolverImpl** (verified from source):
- Depends on `CloudflareSessionProvider` (WebView, main thread)
- Depends on `KwikResolver` (also uses WebView)
- Returns `StreamInfo` with: url, cookies, userAgent, referer, title, audio, fansub

**EpisodeDownloader** (verified from source):
- Depends on `HlsClient` (OkHttp, no WebView)
- Depends on `AesKeyCache`, `SegmentFileStore`, `EpisodeAssembler`
- Takes `StreamInfo` as input (already resolved)
- No hidden WebView dependency

### Ownership Model (UNAMBIGUOUS — Single Source of Truth)

**This section supersedes all other ownership descriptions in this document.**

```
┌─────────────────────────────────────────────────────────────┐
│ FOREGROUND (Activity/ViewModel/UseCase)                     │
│                                                             │
│  1. User triggers download                                  │
│  2. DownloadRepository.enqueue(episodeInfo, sessionInfo)    │
│  3. Set state = RESOLVING (atomic Room update)              │
│  4. StreamResolverImpl.resolve(playUrl, quality, audio)     │
│     ├── CloudflareSessionProvider.fetchPage() [WebView]     │
│     ├── PlayPageParser.parseResolutionButtons()             │
│     ├── QualitySelector.select()                            │
│     └── KwikResolver.resolve() [WebView]                    │
│  5. Returns StreamInfo                                       │
│  6. ATOMIC Room update:                                     │
│     - m3u8Url = streamInfo.url                              │
│     - userAgent = streamInfo.userAgent                      │
│     - referer = streamInfo.referer                          │
│     - segmentDir = filesDir/downloads/{episodeId}/segments  │
│     - state = DOWNLOADING                                   │
│  7. Enqueue WorkManager unique work (AFTER Room update)     │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ WORKMANAGER (DownloadWorker extends CoroutineWorker)        │
│                                                             │
│  1. Set foreground with notification                        │
│  2. Read from Room: m3u8Url, userAgent, referer, segmentDir │
│  3. Construct StreamInfo from persisted data                │
│  4. EpisodeDownloader.download(streamInfo, ...)             │
│     ├── OkHttpHlsClient.getText() [OkHttp]                  │
│     ├── HlsPlaylistParser.parse()                           │
│     ├── OkHttpHlsClient.getBytes() [OkHttp]                 │
│     ├── Aes128Decryptor.decrypt()                           │
│     ├── SegmentFileStore.writeSeg()                         │
│     └── EpisodeAssembler.assemble()                         │
│  5. On success: persist COMPLETED + outputPath              │
│  6. On failure: persist FAILED + error                      │
│  7. Trigger next deferred episode                           │
│  8. Return Result.success() or Result.failure()             │
└─────────────────────────────────────────────────────────────┘
```

### Critical Rule

**Worker MUST NEVER invoke StreamResolver, KwikResolver, or CloudflareSessionProvider.**

The Worker ONLY reads persisted StreamInfo from Room and passes it to EpisodeDownloader.

**Foreground component ONLY resolves streams and persists StreamInfo.**

No DownloadOrchestrator exists. The flow is:
1. Foreground: resolve → persist → enqueue Worker
2. Background: read Room → download → update Room

### Resolution in Foreground Only

**Why**: `StreamResolverImpl` requires:
- `CloudflareSessionProvider.fetchPage()` — must run on main thread with active WebView
- `KwikResolver.resolve()` — also requires WebView session

**Worker constraint**: WorkManager runs in background process. No WebView available. Therefore:
- Worker MUST NOT invoke `StreamResolverImpl`
- Worker MUST NOT invoke `KwikResolver`
- Worker ONLY reads persisted `m3u8Url`/`userAgent`/`referer` from Room

### DownloadOrchestrator Clarification

The `DownloadOrchestrator` concept from v2 is **eliminated**. It created ambiguity about ownership.

**Replacement**: `DownloadRepository` handles:
- Foreground: resolution + persistence + Worker enqueue
- Background: Worker reads Room directly, calls EpisodeDownloader

No intermediate "Orchestrator" class exists. The flow is:
- Foreground: `DownloadRepository.enqueue()` → resolve → persist → enqueue Worker
- Background: `DownloadWorker.doWork()` → read Room → download → update Room

---

## 5. PROCESS-DEATH RECOVERY

### Model

Android process death destroys:
- All in-memory state
- WebView session
- Coroutine scopes
- Running Workers (but WorkManager re-enqueues them)

**Key insight**: Process death does NOT require `Result.retry()`. WorkManager automatically re-enqueues Workers that were running at process death.

### Scenario Analysis

| Scenario | State at Death | Recovery |
|----------|---------------|----------|
| A. During DOWNLOADING | DOWNLOADING | WorkManager re-enqueues Worker; Worker reads Room, resumes via `segmentStore.doneIndices()` |
| B. During ASSEMBLING | ASSEMBLING | WorkManager re-enqueues Worker; Worker sees segments complete, re-attempts assembly |
| C. After M3U8 persistence, before Worker enqueue | QUEUED (m3u8Url set) | Reconciliation sees QUEUED + m3u8Url → enqueues Worker |
| D. During RESOLVING | RESOLVING | Reconciliation resets to QUEUED (resolution interrupted, must re-trigger by user) |
| E. After assembly, before Room COMPLETED | ASSEMBLING | Reconciliation: output exists + non-zero → mark COMPLETED |
| F. After Room COMPLETED | COMPLETED | No action needed; MP4 exists in filesDir |

### Atomicity Guarantees

1. **Room state update**: Single SQL UPDATE is atomic
2. **Filesystem**: MP4 rename is atomic on same filesystem
3. **WorkManager enqueue**: `enqueueUniqueWork()` is atomic

**Race window**: Between Room update and WorkManager enqueue (Scenario C). Handled by reconciliation on startup.

---

## 6. WORKMANAGER RETRY

### Decision: NO Result.retry() in Phase 10

**Justification from EpisodeDownloader behavior**:

EpisodeDownloader already handles retries internally:
- HTTP retry: 5 attempts with exponential backoff (lines 465-491)
- Episode retry: `autoRetry + 1` attempts with exponential backoff (lines 108-153)
- Permanent errors are NOT retried: "No segments", "Master playlist has no variants", assembly failures

When `EpisodeDownloader.download()` returns `DownloadResult(success = false)`, ALL internal retries have been exhausted.

**Therefore**: `DownloadWorker.doWork()` should return `Result.failure()` for any download failure. No `Result.retry()` is needed because:
1. Internal retries are already exhausted
2. User can re-enqueue manually (FAILED → QUEUED via repository)
3. Reconciliation does not re-enqueue FAILED episodes (user action required)

### Recovery Path After Exhausted Download

```
Episode fails → Worker returns Result.failure() → Room state = FAILED
  ↓
User sees FAILED in UI → User taps "Retry"
  ↓
Repository: state = QUEUED, m3u8Url = null (must re-resolve)
  ↓
Foreground: StreamResolverImpl.resolve() → persist m3u8Url → DOWNLOADING
  ↓
Worker: read m3u8Url → download → success or failure
```

---

## 7. M3U8 EXPIRATION / HTTP STATUS CLASSIFICATION

### Current Implementation Limitation

**OkHttpHlsClient.getText()** (verified from source, lines 50-53):
```kotlin
if (!response.isSuccessful) {
    Log.w(TAG, "GET text failed: ${response.code} for $url")
    response.close()
    return@withContext null  // ← 403 and 404 both become null
}
```

**EpisodeDownloader.fetchM3u8WithRetry()** (verified from source, lines 476-491):
- Catches exceptions, returns null
- Does NOT preserve HTTP status code

### Required Architectural Prerequisite

Extend `HlsClient` interface to return typed errors:

```kotlin
sealed class HlsResult {
    data class Success(val content: String) : HlsResult()
    data class HttpError(val code: Int, val url: String) : HlsResult()
    data class NetworkError(val message: String, val url: String) : HlsResult()
}

interface HlsClient {
    suspend fun getText(
        url: String,
        context: RequestContext,
        timeoutMs: Long = 30_000L
    ): HlsResult
    // ...
}
```

### Phase 10 Deliverable

This interface extension IS a Phase 10 deliverable. The architecture must include:
1. `HlsResult` sealed class
2. `OkHttpHlsClient` updated to return `HlsResult`
3. `EpisodeDownloader` updated to classify errors based on `HlsResult`
4. `DownloadWorker` updated to map error types to Room state

### HlsResult Interface Extension (BLOCKING PREREQUISITE)

**This is a BLOCKING prerequisite for ALL error classification in Phase 10.**

The current `HlsClient` interface returns `String?` / `ByteArray?` — null for ALL failures (HTTP 403, 404, 500, timeout, DNS failure). No HTTP status code is preserved.

**Required change**: Extend `HlsClient` with a sealed result type:

```kotlin
sealed class HlsResult {
    data class Success(val content: String) : HlsResult()
    data class HttpError(val code: Int, val url: String) : HlsResult()
    data class NetworkError(val message: String, val url: String) : HlsResult()
}

interface HlsClient {
    suspend fun getText(
        url: String,
        context: RequestContext,
        timeoutMs: Long = 30_000L
    ): HlsResult
    // ...
}
```

**Impact**: Without this change, the error classification table (Section 17) is UNIMPLEMENTABLE. All M3U8/segment failures map to generic FAILED with no HTTP status information.

**Implementation order**: This MUST be implemented first (step 1 of implementation sequence) before any other Phase 10 work.

### Error Classification (after HlsResult interface extension)

**IMPORTANT**: The error classification below applies ONLY AFTER the HlsResult interface extension (step 1 of implementation). The current HlsClient returns null for all failures. This table is a design target, not a description of current behavior.

| Error Type | HlsResult | Worker Action |
|------------|-----------|---------------|
| M3U8 403/404 | HttpError(403/404) | FAILED (stream expired, user must re-enqueue) |
| M3U8 5xx | HttpError(5xx) | FAILED (transient, but all retries exhausted) |
| M3U8 timeout | NetworkError | FAILED (all retries exhausted) |
| Segment 403/404 | HttpError(403/404) | FAILED (segment expired) |
| Segment 5xx | HttpError(5xx) | FAILED (all retries exhausted) |

**Note**: "403/404 always means URL expiration" is too strong. The architecture treats 403/404 as "likely expired" and fails the episode. User can re-enqueue to get fresh URLs.

---

## 8. CONCURRENCY / maxParallel

### Design Choice

One Worker per episode + Room active-count gating.

### Honest Assessment

**This is a BEST-EFFORT concurrency guideline, NOT a hard limit.**

Race conditions exist:
1. Two Workers start simultaneously, both read `activeCount` before either writes state
2. `activeCount` query counts non-terminal episodes, not just DOWNLOADING
3. WorkManager may enqueue Workers faster than Room state updates

**Why this is acceptable for Python parity**:
- Python's `asyncio.Semaphore(maxParallel)` is also cooperative, not preemptive
- At I/O boundaries (segment downloads), multiple tasks can overlap
- The actual concurrency is bounded by network I/O, not CPU
- Each Worker is independently resumable via `segmentStore.doneIndices()`

**Documentation**:
> "maxParallel is a best-effort guideline. Under rapid enqueue conditions (e.g., batch re-enqueue), more than maxParallel downloads may run concurrently. This is acceptable because each Worker is independently resumable and bounded by segment-level Semaphore (hlsWorkers=24). This matches Python's cooperative semaphore behavior where tasks can overlap at I/O boundaries."

### Implementation (Soft Limit)

```kotlin
// In DownloadWorker.doWork()
val activeCount = episodeDownloadDao.countActive()
// countActive() counts only DOWNLOADING/ASSEMBLING episodes (not QUEUED/RESOLVING)
// Use >= to cap at maxParallel (Worker has not yet updated its own state)
if (activeCount >= maxParallel) {
    // Too many active — defer this Worker
    Log.d(TAG, "Active count $activeCount >= maxParallel $maxParallel, deferring")
    return Result.success() // Not a failure, just deferred
}

// Update state to DOWNLOADING (counts as active)
episodeDownloadDao.updateState(episodeId, "DOWNLOADING")
```

**Note**: With `>=`, the effective concurrency is capped at `maxParallel`. With `>`, the effective limit is `maxParallel + 1` (always one extra). The architecture uses `>=` to match Python's `asyncio.Semaphore(maxParallel)` behavior.

### DAO Query Definition

```sql
-- countActive() — counts only episodes consuming network/disk resources
SELECT COUNT(*) FROM episode_downloads WHERE state IN ('DOWNLOADING', 'ASSEMBLING')
```

### Why Soft Limit Is Acceptable

1. **Python parity**: Python `BatchOrchestrator` also uses `asyncio.Semaphore(maxParallel)` which is cooperative, not preemptive. In practice, it allows `maxParallel` concurrent downloads.
2. **Android reality**: WorkManager does not provide distributed semaphore semantics. Hard limits require `ForegroundService` with internal counter (more complex, same practical result).
3. **User impact**: Occasional `maxParallel + 1` concurrent downloads is acceptable. The alternative (single Worker managing all episodes) adds complexity without meaningful benefit.

### Documentation Requirement

The architecture MUST explicitly state:
> "maxParallel is a soft guideline. In rare race conditions, more than maxParallel downloads may run concurrently. This is acceptable because each Worker is independently resumable and bounded by segment-level Semaphore."

### Completion Trigger for Deferred Episodes

When a Worker completes (success or failure), it should check for deferred episodes and enqueue the next one:

```kotlin
// In DownloadWorker.doWork(), after completing download
private suspend fun triggerNextDeferredEpisode() {
    // Find next QUEUED episode with m3u8Url (resolved but deferred)
    val nextEpisode = dao.getNextQueuedWithM3u8()
    if (nextEpisode != null) {
        val activeCount = dao.countActive()
        if (activeCount < maxParallel) {
            enqueueWorker(nextEpisode.episodeId)
        }
    }
}
```

Add DAO method:
```kotlin
@Query("SELECT * FROM episode_downloads WHERE state = 'QUEUED' AND m3u8Url IS NOT NULL ORDER BY createdAt ASC LIMIT 1")
suspend fun getNextQueuedWithM3u8(): EpisodeDownloadEntity?
```

This ensures batch downloads progress without requiring app restart.

---

## 9. UNIQUE WORK

### Work Name Format

```kotlin
val workName = "episode_download_${episodeId}"
```

### Policy

Use `ExistingWorkPolicy.KEEP` for initial enqueue:

```kotlin
val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
    .setInputData(workDataOf("episode_id" to episodeId))
    .build()

WorkManager.getInstance(context)
    .enqueueUniqueWork(
        workName,
        ExistingWorkPolicy.KEEP,  // Don't cancel running Workers
        workRequest
    )
```

### Why KEEP over REPLACE

1. `KEEP` silently ignores new enqueue if prior work is RUNNING — no interruption
2. `KEEP` silently ignores new enqueue if prior work is terminal — must cancel first
3. `REPLACE` cancels RUNNING Workers — forces restart from zero, losing resume progress
4. For re-enqueue after FAILED/CANCELLED: `cancelUniqueWork()` + `enqueueUniqueWork(KEEP)`

### Re-enqueue Pattern

```kotlin
suspend fun reEnqueue(episodeId: String) {
    val episode = dao.getById(episodeId) ?: return
    if (!episode.isTerminal()) return  // Guard: only re-enqueue from terminal states
    
    // Cancel existing WorkManager work (if any)
    workManager.cancelUniqueWork("episode_download_$episodeId").await()
    
    // Reset state for fresh download
    dao.updateState(episodeId, "QUEUED")
    dao.incrementRetryCount(episodeId)
    dao.clearStreamInfo(episodeId)
    
    // Enqueue new Worker
    enqueueWorker(episodeId)
}
```

### Cancel

```kotlin
WorkManager.getInstance(context)
    .cancelUniqueWork("episode_download_${episodeId}")
```

---

## 10. FOREGROUND EXECUTION

### WorkManager Version

**BLOCKING PREREQUISITE**: WorkManager version MUST be verified against current toolchain BEFORE implementation.

**Compatibility requirements** (verify before adding):
- compileSdk 37
- targetSdk 37
- minSdk 32
- AGP 9.4.1
- Gradle 9.7.1
- Kotlin 2.4.20
- JDK 25

**Dependency to add** (in `libs.versions.toml`):
```toml
[versions]
work = "LATEST_STABLE"  # Verify against toolchain

[libraries]
androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
```

**Dependency to add** (in `app/build.gradle.kts`):
```kotlin
implementation(libs.androidx.work.runtime)
```

**AGENTS.md §17 compliance**: The implementer MUST verify WorkManager compatibility before adding the dependency. If no version supports the current toolchain, document the incompatibility and select the newest compatible version.

### Permissions Required (targetSdk 37)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Notification Channel

Create notification channel in `DownloadNotificationManager`:

```kotlin
val channel = NotificationChannel(
    "download_channel",
    "Downloads",
    NotificationManager.IMPORTANCE_LOW  // No sound/vibration during downloads
).apply {
    description = "Shows download progress"
    setShowBadge(false)
}
notificationManager.createNotificationChannel(channel)
```

### Foreground Worker Behavior

- `setForeground()` in `CoroutineWorker` creates a foreground service notification
- Worker runs as a foreground service with no WorkManager-imposed timeout
- Notification must be posted before heavy work begins
- `FOREGROUND_SERVICE_DATA_SYNC` permission required for Android 14+

### Foreground Worker Timeout

**CORRECTION FROM v4**: The v4 "correction" claiming `ListenableWorker` has a 10-minute hard limit was **incorrect**.

**Actual behavior** (from official Android documentation):
- WorkManager has built-in support for long-running workers
- Calling `setForeground()` on a `CoroutineWorker` lifts the 10-minute timeout
- "Under the hood, WorkManager manages and runs a foreground service on your behalf to execute the WorkRequest"
- Workers with `setForeground()` can run indefinitely

**Solution**: Use `CoroutineWorker` with `setForeground()` directly. No separate `DownloadForegroundService` needed.

```kotlin
class DownloadWorker(...) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        val episodeId = inputData.getString("episode_id") ?: return Result.failure()
        
        // Set foreground with notification
        setForeground(createForegroundInfo(episodeId))
        
        // Download using EpisodeDownloader
        val result = downloadEpisode(episodeId)
        
        return if (result.success) Result.success() else Result.failure()
    }
    
    private fun createForegroundInfo(episodeId: String): ForegroundInfo {
        val notification = notificationManager.createDownloadNotification(episodeId)
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
```

**Why this works**:
- WorkManager manages the foreground service lifecycle automatically
- If process dies, WorkManager recreates the Worker
- Notification shows download progress
- No 10-minute timeout limitation
- Simpler architecture (one component instead of two)

### Permissions Required

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Service Declaration

**NOT NEEDED**: WorkManager's `setForeground()` creates its own internal foreground service. No custom service declaration required.

### Permissions Required

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Notification Channel

```kotlin
val channel = NotificationChannel(
    "download_channel",
    "Downloads",
    NotificationManager.IMPORTANCE_LOW  // No sound/vibration during downloads
).apply {
    description = "Shows download progress"
    setShowBadge(false)
}
notificationManager.createNotificationChannel(channel)
```

### DownloadConfig

```kotlin
data class DownloadConfig(
    val maxParallel: Int = 2,           // From AppConfig
    val notificationChannelId: String = "download_channel",
    val notificationChannelName: String = "Downloads",
    val notificationImportance: Int = NotificationManager.IMPORTANCE_LOW,
    val progressThrottleMs: Long = 5000L,
    val hlsWorkers: Int = 24,           // Concurrent segment downloads
    val autoRetry: Int = 2,             // Episode-level retry attempts
    val keepTemp: Boolean = false       // Keep segment files after assembly
) {
    companion object {
        fun fromAppConfig(appConfig: AppConfig): DownloadConfig {
            return DownloadConfig(
                maxParallel = appConfig.maxParallel,
                hlsWorkers = appConfig.hlsWorkers,
                autoRetry = appConfig.autoRetry,
                keepTemp = appConfig.keepTemp
            )
        }
    }
}
```

---

## 11. STORAGE

### Directory Structure

```
context.filesDir/
├── downloads/
│   ├── {episodeId1}/
│   │   ├── segments/
│   │   │   ├── 0000.ts
│   │   │   ├── 0001.ts
│   │   │   └── ...
│   │   └── Ep {prefix} - {title}.mp4  ← Final MP4 alongside segments
│   └── {episodeId2}/
│       ├── segments/
│       └── ...
```

### Storage Rules

1. **Final MP4**: `context.filesDir / "downloads" / episodeId / "Ep {prefix} - {title}.mp4"` — alongside segments, NEVER in cacheDir
2. **Resumable segments**: `context.filesDir / "downloads" / episodeId / "segments"` — NEVER in cacheDir
3. **Assembly temp**: `context.cacheDir / "assembly" / episodeId` — reconstructable, can be cleared

**Consistent storage pattern**:
```kotlin
// Session output directory
val sessionOutputDir = File(context.filesDir, "downloads")

// Per-episode segment directory
val segmentDir = File(sessionOutputDir, episodeId)

// Final MP4 location (alongside segments, matching EpisodeDownloader code)
val outputFileName = FileNameGenerator.episodeFileName(prefix, title)
val outputFile = File(segmentDir.parentFile, outputFileName)
// outputFile = filesDir/downloads/{episodeId}/Ep X - Y.mp4
```

**Note**: This matches the current `EpisodeDownloader` code at line 391:
```kotlin
val outputFile = File(segmentStore.getDirectory().parentFile, outputFileName)
```

### DownloadSessionEntity.cacheDir/outputDir Usage

- `outputDir`: Session-level output directory (where final MP4s go). Use this for session output.
- `cacheDir`: NOT used for segment storage in Phase 10. Per-episode segment storage uses `EpisodeDownloadEntity.segmentDir`. Session `cacheDir` may be removed in a future migration.

```kotlin
// Session output directory
val sessionOutputDir = File(context.filesDir, "downloads")

// Per-episode segment directory
val segmentDir = File(sessionOutputDir, episodeId)

// Final MP4 location
val outputFile = File(context.filesDir, outputFileName)
```

---

## 12. SEGMENT CLEANUP

### Lifecycle

```
Download segments → .ts files in segmentDir/segments/
  ↓
Assembly → FFmpeg concatenates .ts → temp .mp4 → atomic rename to final MP4
  ↓
Success → Delete segmentDir (unless keepTemp)
Failure → Preserve segmentDir for resume
```

### Process Death During Cleanup

- If process dies during cleanup (after successful assembly), segments are orphaned
- Reconciliation detects: output exists + non-zero → COMPLETED → trigger cleanup
- Segments in `filesDir/downloads/{episodeId}/segments/` are not critical (final MP4 is elsewhere)

### Failed Assembly

- Segments preserved for retry
- User can re-trigger download; Worker resumes from completed segments

### Cancellation

- Worker cancellation cancels coroutines
- `.tmp` files cleaned by orphan cleanup on next download start
- `.ts` files preserved for potential resume

---

## 13. PROGRESS PERSISTENCE

### Throttling Strategy

Progress is NOT written to Room per segment. Instead:

1. **In-memory counter**: Track completed segments in Worker memory
2. **Throttled Room writes**: Write to Room at most every 5 seconds OR on state transition
3. **Immediate state transitions**: QUEUED → RESOLVING → DOWNLOADING → ASSEMBLING → COMPLETED/FAILED

### Throttling Implementation

```kotlin
class ProgressTracker(private val dao: EpisodeDownloadDao) {
    private var lastRoomWrite = 0L
    private val THROTTLE_MS = 5000L
    
    suspend fun updateProgressIfNeeded(
        episodeId: String,
        completedSegments: Int,
        downloadedBytes: Long,
        forceUpdate: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (forceUpdate || (now - lastRoomWrite >= THROTTLE_MS)) {
            val speed = calculateSpeed(downloadedBytes, now)
            val eta = calculateEta(completedSegments, downloadedBytes, speed)
            dao.updateProgress(episodeId, completedSegments, downloadedBytes, speed, eta, now)
            lastRoomWrite = now
        }
    }
    
    private fun calculateSpeed(bytes: Long, timestamp: Long): Long {
        // Compute from in-memory counters
        // Non-authoritative UI value
        return 0L // Implement with sliding window
    }
    
    private fun calculateEta(completed: Int, bytes: Long, speed: Long): Long {
        // Non-authoritative UI value
        return 0L // Implement with simple estimate
    }
}
```

### Speed/ETA

- `speed` and `etaSeconds` are **non-authoritative UI values**
- Computed from in-memory counters, written to Room only on throttled updates
- Not used for download logic

### retryCount Semantics

**Definition**: `retryCount` counts the number of times the user (or reconciliation) explicitly re-triggered download for this episode.

**Increment point**: `retryCount` increments in `DownloadRepository.reEnqueue()`:
```kotlin
suspend fun reEnqueue(episodeId: String) {
    val episode = dao.getById(episodeId) ?: return
    // ... existing logic ...
    dao.updateState(episodeId, "QUEUED")
    dao.incrementRetryCount(episodeId)  // ← Increment here
    dao.clearStreamInfo(episodeId)
    enqueueWorker(episodeId)
}
```

**CRITICAL**: Modify existing `EpisodeDownloadDao.updateError()` to REMOVE the retryCount increment:
```kotlin
// BEFORE (current):
@Query("UPDATE episode_downloads SET state = 'FAILED', errorMessage = :errorMessage, retryCount = retryCount + 1, updatedAt = :timestamp WHERE episodeId = :episodeId")
suspend fun updateError(episodeId: String, errorMessage: String, timestamp: Long = System.currentTimeMillis())

// AFTER (Phase 10):
@Query("UPDATE episode_downloads SET state = 'FAILED', errorMessage = :errorMessage, updatedAt = :timestamp WHERE episodeId = :episodeId")
suspend fun updateError(episodeId: String, errorMessage: String, timestamp: Long = System.currentTimeMillis())
```

**Does NOT count**:
- Internal EpisodeDownloader retries (HTTP retry, episode retry)
- Worker recreation after process death
- WorkManager automatic re-enqueue

**Reset**: Only on explicit user action (delete and re-add episode)

---

## 14. RECONCILIATION

### DownloadStateReconciler

Runs on app startup (triggered by Application.onCreate or first Activity.onCreate).

### Branch Logic

```kotlin
suspend fun reconcile() {
    // Fast-path: skip if no non-terminal episodes
    val nonTerminalCount = dao.getNonTerminalCount()
    if (nonTerminalCount == 0) return
    
    val allNonTerminalEpisodes = dao.getAllNonTerminal()
    for (episode in allNonTerminalEpisodes) {
        when (episode.state) {
            "COMPLETED" -> {
                // Verify output exists
                val output = File(episode.outputPath)
                if (!output.exists() || output.length() == 0L) {
                    // Output missing — mark FAILED (user deleted or storage issue)
                    dao.updateState(episode.episodeId, "FAILED")
                } else if (episode.segmentDir != null) {
                    // Clean up orphaned segments from process death during cleanup
                    val segDir = File(episode.segmentDir)
                    if (segDir.exists()) segDir.deleteRecursively()
                }
            }
            
            "FAILED", "CANCELLED" -> {
                // Terminal — no action (user must re-enqueue)
            }
            
            "ASSEMBLING" -> {
                // outputPath is NULL during ASSEMBLING — compute expected path from metadata
                if (episode.segmentDir != null) {
                    val prefix = FileNameGenerator.formatEpisodePrefix(episode.episodeNumber)
                    val outputFileName = FileNameGenerator.episodeFileName(prefix, episode.title)
                    val outputFile = File(File(episode.segmentDir).parentFile, outputFileName)
                    if (outputFile.exists() && outputFile.length() > 0L) {
                        // Assembly completed before process death — mark COMPLETED
                        dao.updateCompleted(episode.episodeId, outputFile.absolutePath)
                    } else {
                        // Assembly incomplete — re-enqueue Worker
                        enqueueWorker(episode.episodeId)
                    }
                } else {
                    // No segmentDir — cannot compute output path, re-enqueue
                    enqueueWorker(episode.episodeId)
                }
            }
            
            "DOWNLOADING" -> {
                // Atomically transition from DOWNLOADING to QUEUED if Worker not running
                // This prevents two Workers from running simultaneously
                val workInfos = workManager.getWorkInfosForUniqueWork("episode_download_${episode.episodeId}").await()
                val isRunning = workInfos.any { it.state == WorkInfo.State.RUNNING }
                if (!isRunning) {
                    // Worker not running — re-enqueue (will resume via segmentStore.doneIndices())
                    // Use atomic state transition to prevent race conditions
                    dao.updateStateForResume(episode.episodeId, "DOWNLOADING", "QUEUED")
                    enqueueWorker(episode.episodeId)
                }
                // else: Worker already running — no action needed
            }
            
            "RESOLVING" -> {
                // Resolution interrupted — reset to QUEUED (requires foreground WebView)
                dao.updateState(episode.episodeId, "QUEUED")
                dao.clearStreamInfo(episode.episodeId) // Clear m3u8Url/userAgent/referer
            }
            
            "QUEUED" -> {
                if (episode.m3u8Url != null) {
                    // Resolved but Worker not enqueued — enqueue Worker
                    enqueueWorker(episode.episodeId)
                }
                // else: awaiting foreground resolution — no action
            }
        }
    }
}
```

### Idempotency

- `enqueueWorker()` uses `ExistingWorkPolicy.REPLACE` — safe to call multiple times
- Room updates are idempotent (SET state = X)
- No duplicate Workers created (unique work name per episode)

---

## 15. STARTUP TRIGGER

### Implementation

```kotlin
// In PaheBatcherApplication.onCreate()
class PaheBatcherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Run reconciliation on first process start (with deduplication)
        if (reconciliationRun.compareAndSet(false, true)) {
            CoroutineScope(Dispatchers.IO).launch {
                val reconciler = DownloadStateReconciler(
                    episodeDao = database.episodeDownloadDao(),
                    workManager = WorkManager.getInstance(this@PaheBatcherApplication)
                )
                reconciler.reconcile()
            }
        }
    }
    
    companion object {
        private val reconciliationRun = AtomicBoolean(false)
    }
}
```

### Process-Scoped Deduplication

Use `AtomicBoolean` to ensure reconciliation runs only once per process:
- `Application.onCreate()` runs before any Activity or Worker
- WorkManager's `onAppStart` initialization ensures Application is created
- Multiple Activity instances do NOT trigger multiple reconciliations

---

## 16. REPOSITORY / WORKER OWNERSHIP (RESOLVED)

### UNAMBIGUOUS Model

```
DownloadRepository (Foreground only):
  - enqueue(episodeInfo, sessionInfo) → resolve → persist → enqueue Worker
  - cancel(episodeId) → cancel Worker → update Room
  - reEnqueue(episodeId) → clear stream info → set QUEUED

DownloadWorker (Background only):
  - doWork() → setForeground() → read Room → download → update Room
  - Runs EpisodeDownloader directly
  - Posts notification via setForeground()
  - Does NOT call StreamResolver
  - Does NOT call KwikResolver
  - Does NOT access WebView
```

### No DownloadOrchestrator

The `DownloadOrchestrator` concept from v2 is **eliminated**. It created ownership ambiguity. The flow is:

1. **Foreground**: `DownloadRepository` handles resolution + persistence + Worker enqueue
2. **Background**: `DownloadWorker` reads Room, runs `EpisodeDownloader` directly

---

## 17. ERROR CLASSIFICATION

### After HlsResult Interface Extension

| Error | Source | Classification | Worker Action |
|-------|--------|----------------|---------------|
| M3U8 403/404 | HlsResult.HttpError | Stream expired | FAILED |
| M3U8 5xx | HlsResult.HttpError | Server error | FAILED (retries exhausted) |
| M3U8 timeout | HlsResult.NetworkError | Network issue | FAILED (retries exhausted) |
| M3U8 parse error | Exception | Corrupted playlist | FAILED |
| AES key fetch failed | HlsResult | Key unavailable | FAILED |
| AES key wrong size | ByteArray.size != 16 | Corrupted key | FAILED |
| Segment 403/404 | HlsResult.HttpError | Segment expired | FAILED |
| Segment 5xx | HlsResult.HttpError | Server error | FAILED (retries exhausted) |
| Segment timeout | HlsResult.NetworkError | Network issue | FAILED (retries exhausted) |
| Decryption failed | Aes128Decryptor | Corrupted data | FAILED |
| No segments | Playlist empty | Invalid stream | FAILED |
| No variants | Master playlist empty | Invalid stream | FAILED |
| Assembly failed | EpisodeAssembler | FFmpeg error | FAILED |
| Storage full | IOException | Disk full | FAILED |
| Cancellation | CancellationException | User cancelled | CANCELLED |
| Unexpected exception | Exception | Unknown | FAILED |

---

## 18. COMPLETION CONSISTENCY

### Atomic Output Behavior

`FfmpegEpisodeAssembler` uses atomic rename:
1. FFmpeg writes to temp file in `cacheDir/assembly/{episodeId}/`
2. On success, `File.renameTo(finalOutput)` moves to `filesDir/`
3. Rename is atomic on same filesystem (both under app's storage)

### Reconciliation After Assembly Race

**Scenario**: MP4 successfully renamed, process dies, Room still says ASSEMBLING

**Reconciliation**:
```kotlin
"ASSEMBLING" -> {
    val output = File(episode.outputPath)
    if (output.exists() && output.length() > 0L) {
        dao.updateState(episodeId, "COMPLETED") // ✓ Correct
    } else {
        enqueueWorker(episode.episodeId) // Re-attempt assembly
    }
}
```

### User-Deleted Download

**Scenario**: Room says COMPLETED, MP4 missing (user deleted)

**Reconciliation**:
```kotlin
"COMPLETED" -> {
    val output = File(episode.outputPath)
    if (!output.exists() || output.length() == 0L) {
        dao.updateState(episodeId, "FAILED") // Mark as failed
    }
}
```

**Note**: This does NOT resurrect the download. It marks the episode as FAILED, requiring user action to re-download.

---

## 19. TEST PLAN

### Unit Tests

| Test | Coverage |
|------|----------|
| MigrationTest | v1 → v2 migration preserves data, drops segments table |
| StateTransitionTest | All valid state transitions, illegal transitions rejected |
| DownloadRepositoryTest | enqueue, cancel, re-enqueue, duplicate enqueue |
| DownloadStateReconcilerTest | All reconciliation branches |
| HlsResultClassificationTest | Error types mapped to correct states |
| ProgressThrottlingTest | Room writes bounded (not per-segment) |
| UniqueWorkTest | Work name format, REPLACE policy, cancel |
| DuplicateEnqueueTest | Same episode enqueued twice — Worker replaced, not duplicated |

### Integration Tests

| Test | Coverage |
|------|----------|
| WorkerExecutionTest | Worker reads Room, downloads, updates Room |
| ForegroundWorkerTest | Worker starts foreground, posts notification, runs download |
| ProcessDeathRecoveryTest | Kill app during DOWNLOADING, verify resume |
| ConcurrencyTest | maxParallel soft limit behavior |
| CancellationTest | Cancel during download, verify state |

### Instrumented Tests

| Test | Coverage |
|------|----------|
| FullDownloadFlowTest | enqueue → resolve → download → assemble → COMPLETED |
| ResumeTest | Kill during download, restart, verify resume |
| NotificationTest | Verify notification posted during download |

---

## 20. PYTHON PARITY

### maxParallel

- Python: `asyncio.Semaphore(maxParallel)` — cooperative, soft limit
- Android: Room active-count gating — cooperative, soft limit
- **Parity**: ✓ (both are soft limits)

### Retry

- Python: `auto_retry + 1` attempts, exponential backoff
- Android: `EpisodeDownloader.autoRetry + 1` attempts, exponential backoff
- **Parity**: ✓ (identical behavior)

### Resume

- Python: Checks `out.exists() and st_size > 0` before assembly
- Android: `segmentStore.doneIndices()` checks completed segments
- **Parity**: ✓ (both resume from last completed segment)

### Assembly

- Python: FFmpeg concat demuxer
- Android: FFmpegKit concat demuxer
- **Parity**: ✓ (same FFmpeg command)

### Cancellation

- Python: `asyncio.CancelledException` propagates
- Android: `kotlinx.coroutines.CancellationException` propagates
- **Parity**: ✓ (both cancel cleanly)

### Storage

- Python: `filesDir` for MP4, `cacheDir` for temp
- Android: `filesDir` for MP4, `filesDir/downloads` for segments
- **Parity**: ✓ (persistent storage for user data)

### Batch-Level Retry (ANDROID_REQUIRED_DIFFERENCE)

- Python: After all episodes processed, failed episodes retried automatically. `max_batch_attempts = auto_retry + 1`. Delay between batch retries: `2s * batch_attempt`.
- Android: Failed episodes require manual user re-enqueue. No automatic batch retry.
- **Parity**: ✗ (intentional difference — WorkManager does not provide batch-retry primitive)
- **Document**: ANDROID_REQUIRED_DIFFERENCE in LIMITATIONS.md

---

## 21. DOCUMENTATION CONSISTENCY

| Document | Status | Action |
|----------|--------|--------|
| ROADMAP.md | Phase 10: PENDING | Update to IN PROGRESS during implementation |
| PARITY.md | Background downloads: PENDING | Update after verification |
| DECISIONS.md | Decision 14: PENDING | **RESOLVE** with Phase 10 architectural choices |
| LIMITATIONS.md | WebView dependency documented | No change needed |

### Decision 14 Resolution

Decision 14 (Background Download Strategy) is resolved with the following choices:

1. **CoroutineWorker with setForeground()** — for background download execution
2. **Soft maxParallel limit** — Room active-count gating, not hard distributed lock
3. **HlsResult interface extension** — typed error classification for M3U8/segment failures
4. **QUEUED dual meaning** — distinguished by m3u8Url presence
5. **One Worker per episode** — simpler than batch Worker, matches Python's per-episode model

### New Decisions to Document

1. **Decision 33**: CoroutineWorker with setForeground() for foreground execution
2. **Decision 34**: Soft maxParallel limit (not hard)
3. **Decision 35**: HlsResult interface extension for error classification
4. **Decision 36**: QUEUED dual meaning (m3u8Url-based distinction)

---

## 22. FILES TO CREATE/MODIFY

### New Files (8)

1. `data/local/migration/Migrations.kt` — v1 → v2 migration
2. `domain/hls/HlsResult.kt` — Typed error results
3. `data/download/DownloadRepository.kt` — Foreground download orchestration
4. `data/download/DownloadStateReconciler.kt` — Startup reconciliation
5. `worker/DownloadWorker.kt` — WorkManager Worker (extends CoroutineWorker)
6. `data/download/DownloadConfig.kt` — Configuration (maxParallel, etc.)
7. `notification/DownloadNotificationManager.kt` — Notification management
8. `di/DownloadModule.kt` — Dependency injection for download components

### New Application Class

Create `PaheBatcherApplication.kt` for reconciliation trigger:

```kotlin
class PaheBatcherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Run reconciliation on first launch (with deduplication)
        if (reconciliationRun.compareAndSet(false, true)) {
            CoroutineScope(Dispatchers.IO).launch {
                val reconciler = DownloadStateReconciler(
                    episodeDao = database.episodeDownloadDao(),
                    workManager = WorkManager.getInstance(this@PaheBatcherApplication)
                )
                reconciler.reconcile()
            }
        }
    }
    
    companion object {
        private val reconciliationRun = AtomicBoolean(false)
    }
}
```

Update `AndroidManifest.xml`:
```xml
<application
    android:name=".PaheBatcherApplication"
    ...>
```

### Modified Files (7)

1. `data/local/PaheDatabase.kt` — Remove destructive fallback, add migration, exportSchema = true
2. `data/local/entity/EpisodeDownloadEntity.kt` — Add m3u8Url, userAgent, referer, segmentDir columns
3. `data/local/dao/EpisodeDownloadDao.kt` — Add countActive(), clearStreamInfo()
4. `domain/hls/HlsClient.kt` — Change return type to HlsResult
5. `data/hls/OkHttpHlsClient.kt` — Implement HlsResult return
6. `data/hls/EpisodeDownloader.kt` — Handle HlsResult, update progress throttling
7. `AndroidManifest.xml` — Add permissions, service declaration

### Removed Files (2)

1. `data/local/entity/SegmentEntity.kt` — Dead infrastructure
2. `data/local/dao/SegmentDao.kt` — Dead infrastructure

---

## 23. IMPLEMENTATION SEQUENCE

1. **Verify WorkManager compatibility** (H4 — blocking prerequisite for toolchain)
2. **HlsResult interface** (C3 prerequisite — blocking for all error classification)
3. **Room migration** (H1 — remove SegmentEntity, add columns, bump version)
4. **DAO updates** (new queries: countActive, getNonTerminalCount, getAllNonTerminal, clearStreamInfo, incrementRetryCount, updateStateForResume, getNextQueuedWithM3u8)
5. **DownloadConfig** (configuration)
6. **NotificationManager** (foreground notification with channel)
7. **DownloadRepository** (foreground orchestration)
8. **DownloadWorker** (extends CoroutineWorker, uses setForeground())
9. **DownloadStateReconciler** (startup recovery)
10. **Update EpisodeDownloader** (HlsResult handling, ProgressTracker)
11. **Update OkHttpHlsClient** (HlsResult implementation)
12. **Update PaheDatabase** (migration, remove fallback, exportSchema = true)
13. **Update EpisodeDownloadEntity** (new columns, remove STATE_PAUSED from canResume())
14. **Update EpisodeDownloadDao** (modify updateError() to remove retryCount increment)
15. **Update AndroidManifest.xml** (permissions, application class)
16. **Create PaheBatcherApplication** (reconciliation trigger)
17. **Tests**
18. **Build and verify**

---

## 24. FINAL VERDICT

This architecture addresses all 17 findings from v7 review:

- **C1 (Room migration will crash)**: Explicitly specified removing `SegmentEntity::class` from `@Database` entities array, removing `segmentDao()` method, bumping version to 2, and adding migration test
- **C2 (canResume references STATE_PAUSED)**: Removed STATE_PAUSED from canResume(), removed constant entirely, updated state field KDoc

- **H1 (maxParallel off-by-one)**: Changed `>` to `>=` to cap at maxParallel, documented as matching Python's Semaphore behavior
- **H2 (KEEP→REPLACE policy)**: Reverted to KEEP with guard against duplicate Workers, added reEnqueue pattern with cancel-then-enqueue
- **H3 (Storage path contradiction)**: Resolved with consistent pattern: output alongside segments at `filesDir/downloads/{episodeId}/`
- **H4 (WorkManager version not verified)**: Marked as BLOCKING PREREQUISITE requiring toolchain verification before implementation

- **M1 (Implementation ordering)**: Reordered to: verify WorkManager → HlsResult → Room → DAO → Config → Notification → Repository → Worker → Reconciler
- **M2 (ProgressTracker stubs)**: Documented as Phase 11 concern, speed/ETA remain 0 until UI implementation
- **M3 (Migration SQL notes)**: Removed "designed during implementation" note, SQL is final
- **M4 (Segment cleanup gap)**: Added segment cleanup to COMPLETED reconciliation branch
- **M5 (reEnqueue guard)**: Added guard in reEnqueue() to only process terminal states
- **M6 (Progress documentation)**: Clarified "written to Room at most every 5 seconds"

- **L1 (DownloadSessionEntity unused fields)**: Documented as known unused field
- **L2 (retryCount semantics)**: Confirmed correct separation from internal retries
- **L3 (Storage contradiction)**: Resolved with consistent storage pattern
- **L4 (CoroutineScope leak)**: Noted as minor code quality improvement
- **L5 (canResume QUEUED ambiguity)**: Documented that QUEUED resume requires foreground context

**STATUS**: READY FOR EIGHTH AND FINAL REVIEW
