# Phase 10 Architecture — Background Downloads (v9)

> **⚠️ SUPERSEDED — HISTORICAL DOCUMENT.** This v9 draft is superseded by **`PHASE10_ARCHITECTURE_V10.md`**, which is the approved Phase 10 architecture and the authoritative reference for implemented behavior. This file is retained for review history only; do not treat its content as current. Do not edit historical content.

**Status**: SUPERSEDED by PHASE10_ARCHITECTURE_V10.md (original status at the time: REVISED — READY FOR FINAL REVIEW)
**Date**: 2026-09-22
**Previous Reviews**: v1-v8 (8 architecture reviews)

---

## 1. ROOM MIGRATION (v1 → v2)

### Current State

- Schema version: 1
- Entities: ScanCacheEntity, DownloadSessionEntity, EpisodeDownloadEntity, SegmentEntity, WatchlistEntity
- `fallbackToDestructiveMigration(dropAllTables = true)` — MUST BE REMOVED
- `exportSchema = false` — MUST BE SET TO `true` FOR MIGRATION TESTING

### Migration SQL (Final)

```sql
-- Migration 1 → 2
-- Executed ONLY against existing v1 databases

-- 1. Drop unused SegmentEntity/SegmentDao table (never written in production)
DROP TABLE IF EXISTS segments;

-- 2. Add new columns to episode_downloads for background download support
ALTER TABLE episode_downloads ADD COLUMN m3u8Url TEXT;
ALTER TABLE episode_downloads ADD COLUMN userAgent TEXT;
ALTER TABLE episode_downloads ADD COLUMN referer TEXT;
ALTER TABLE episode_downloads ADD COLUMN segmentDir TEXT;
```

### Fresh Installs

Fresh installs create the v2 schema directly (no migration needed). Room generates the schema from the `@Database` annotation with `SegmentEntity` removed.

### Safety

- `segments` table is defined in Room but never written to in production code (verified by grep — no code calls `SegmentDao.insert` or `SegmentDao.upsert`)
- `DROP TABLE IF EXISTS` handles the case where the table exists (existing v1 databases)
- All existing v1 columns remain intact; new columns are nullable with defaults

### CRITICAL: PaheDatabase Changes

The following changes MUST be made to `PaheDatabase.kt`:

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
        // Create v1 database with test data (ScanCache, DownloadSession, EpisodeDownload, Watchlist)
        // Run migration MIGRATION_1_2
        // Verify:
        // 1. All v1 data preserved
        // 2. segments table dropped
        // 3. New columns accessible
        // 4. SegmentEntity NOT in compiled schema
    }
}
```

---

## 2. CANONICAL STORAGE PATH

### Single Canonical Structure

All components use ONE consistent path model:

```
context.filesDir/
└── downloads/
    └── {episodeId}/
        ├── segments/
        │   ├── 0000.ts
        │   ├── 0001.ts
        │   └── ...
        └── {generated_filename}.mp4
```

### Canonical Path Definitions

```kotlin
// Base download directory (session-level)
val downloadsDir = File(context.filesDir, "downloads")

// Per-episode segment directory
val segmentDir = File(downloadsDir, episodeId)  // e.g., filesDir/downloads/{episodeId}

// Segments subdirectory
val segmentsSubdir = File(segmentDir, "segments")  // e.g., filesDir/downloads/{episodeId}/segments

// Final MP4 location (alongside segments directory)
val outputFileName = FileNameGenerator.episodeFileName(prefix, title)
val outputFile = File(segmentDir, outputFileName)  // e.g., filesDir/downloads/{episodeId}/Ep X - Y.mp4
```

### Path Consistency Across Components

| Component | segmentDir value | outputFile value |
|-----------|------------------|------------------|
| DownloadRepository (enqueue) | `filesDir/downloads/{episodeId}` | N/A (not yet assembled) |
| EpisodeDownloader | `segmentStore.getDirectory()` = segmentDir | `File(segmentStore.getDirectory().parentFile, outputFileName)` |
| FfmpegEpisodeAssembler | `config.segmentDir` | `config.outputFile` |
| DownloadStateReconciler | `episode.segmentDir` | `episode.outputPath` OR computed from metadata |

### CRITICAL: No Contradictory Paths

The following path is **WRONG** and must NOT appear anywhere:
```kotlin
// WRONG — do not use
val outputFile = File(context.filesDir, outputFileName)
```

The correct path is:
```kotlin
// CORRECT — output alongside segments directory
val outputFile = File(segmentDir, outputFileName)
```

### episodeDownloadEntity Fields

- `segmentDir`: Stores the per-episode segment directory path (`filesDir/downloads/{episodeId}`)
- `outputPath`: Stores the final MP4 path (`filesDir/downloads/{episodeId}/{generated_filename}.mp4`)

### DownloadSessionEntity Fields

- `outputDir`: Session-level output directory (`filesDir/downloads`). Use this as the base for segment directories.
- `cacheDir`: NOT used for segment storage in Phase 10. May be removed in future migration.

---

## 3. HLS RESULT TYPE

### Current Implementation Limitation

**HlsClient interface** returns `String?` / `ByteArray?` — null for ALL failures. No HTTP status code is preserved.

### Required HlsResult Types (BOTH text and byte)

```kotlin
sealed class HlsTextResult {
    data class Success(val content: String) : HlsTextResult()
    data class HttpError(val code: Int, val url: String) : HlsTextResult()
    data class NetworkError(val message: String, val url: String) : HlsTextResult()
}

sealed class HlsBytesResult {
    data class Success(val bytes: ByteArray) : HlsBytesResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data class HttpError(val code: Int, val url: String) : HlsBytesResult()
    data class NetworkError(val message: String, val url: String) : HlsBytesResult()
}
```

### Updated HlsClient Interface

```kotlin
interface HlsClient {
    /**
     * Fetch text content (for M3U8 playlists).
     * Returns typed result with HTTP status codes.
     */
    suspend fun getText(
        url: String,
        context: RequestContext,
        timeoutMs: Long = 30_000L
    ): HlsTextResult

    /**
     * Fetch binary content (for segments and AES keys).
     * Returns typed result with HTTP status codes.
     */
    suspend fun getBytes(
        url: String,
        context: RequestContext,
        timeoutMs: Long = 60_000L
    ): HlsBytesResult
}
```

### OkHttpHlsClient Implementation

```kotlin
override suspend fun getText(
    url: String,
    context: HlsClient.RequestContext,
    timeoutMs: Long
): HlsTextResult = withContext(Dispatchers.IO) {
    try {
        val request = buildRequest(url, context, timeoutMs)
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.w(TAG, "GET text failed: ${response.code} for $url")
            response.close()
            return@withContext HlsTextResult.HttpError(response.code, url)
        }

        val body = response.body?.string()
        response.close()
        body?.let { HlsTextResult.Success(it) } ?: HlsTextResult.NetworkError("Empty response body", url)
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "GET text timeout for $url: ${e.message}")
        HlsTextResult.NetworkError("Timeout: ${e.message}", url)
    } catch (e: java.net.UnknownHostException) {
        Log.e(TAG, "GET text DNS failure for $url: ${e.message}")
        HlsTextResult.NetworkError("DNS failure: ${e.message}", url)
    } catch (e: Exception) {
        Log.e(TAG, "GET text error for $url: ${e.message}")
        HlsTextResult.NetworkError("Network error: ${e.message}", url)
    }
}

override suspend fun getBytes(
    url: String,
    context: HlsClient.RequestContext,
    timeoutMs: Long
): HlsBytesResult = withContext(Dispatchers.IO) {
    try {
        val request = buildRequest(url, context, timeoutMs)
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.w(TAG, "GET bytes failed: ${response.code} for $url")
            response.close()
            return@withContext HlsBytesResult.HttpError(response.code, url)
        }

        val bytes = response.body?.bytes()
        response.close()
        bytes?.let { HlsBytesResult.Success(it) } ?: HlsBytesResult.NetworkError("Empty response body", url)
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "GET bytes timeout for $url: ${e.message}")
        HlsBytesResult.NetworkError("Timeout: ${e.message}", url)
    } catch (e: java.net.UnknownHostException) {
        Log.e(TAG, "GET bytes DNS failure for $url: ${e.message}")
        HlsBytesResult.NetworkError("DNS failure: ${e.message}", url)
    } catch (e: Exception) {
        Log.e(TAG, "GET bytes error for $url: ${e.message}")
        HlsBytesResult.NetworkError("Network error: ${e.message}", url)
    }
}
```

### EpisodeDownloader Usage

The downloader must handle both result types:

```kotlin
// M3U8 fetch
when (val result = hlsClient.getText(url, context)) {
    is HlsTextResult.Success -> return result.content
    is HlsTextResult.HttpError -> {
        Log.e(TAG, "M3U8 HTTP ${result.code} for ${result.url}")
        return null  // Triggers retry logic
    }
    is HlsTextResult.NetworkError -> {
        Log.e(TAG, "M3U8 network error: ${result.message}")
        return null  // Triggers retry logic
    }
}

// Segment/Key fetch
when (val result = hlsClient.getBytes(url, context)) {
    is HlsBytesResult.Success -> return result.bytes
    is HlsBytesResult.HttpError -> {
        Log.e(TAG, "Bytes HTTP ${result.code} for ${result.url}")
        return null
    }
    is HlsBytesResult.NetworkError -> {
        Log.e(TAG, "Bytes network error: ${result.message}")
        return null
    }
}
```

### Error Classification (after HlsResult implementation)

| Error Type | HlsResult | Worker Action |
|------------|-----------|---------------|
| M3U8 403/404 | HlsTextResult.HttpError(403/404) | FAILED (stream expired) |
| M3U8 5xx | HlsTextResult.HttpError(5xx) | FAILED (retries exhausted) |
| M3U8 timeout | HlsTextResult.NetworkError | FAILED (retries exhausted) |
| M3U8 parse error | Exception | FAILED |
| AES key 403/404 | HlsBytesResult.HttpError(403/404) | FAILED (key unavailable) |
| AES key 5xx | HlsBytesResult.HttpError(5xx) | FAILED (retries exhausted) |
| AES key timeout | HlsBytesResult.NetworkError | FAILED (retries exhausted) |
| AES key wrong size | ByteArray.size != 16 | FAILED (corrupted key) |
| Segment 403/404 | HlsBytesResult.HttpError(403/404) | FAILED (segment expired) |
| Segment 5xx | HlsBytesResult.HttpError(5xx) | FAILED (retries exhausted) |
| Segment timeout | HlsBytesResult.NetworkError | FAILED (retries exhausted) |
| Decryption failed | Aes128Decryptor | FAILED (corrupted data) |
| No segments | Playlist empty | FAILED (invalid stream) |
| No variants | Master playlist empty | FAILED (invalid stream) |
| Assembly failed | EpisodeAssembler | FAILED (FFmpeg error) |
| Storage full | IOException | FAILED (disk full) |
| Cancellation | CancellationException | CANCELLED |
| Unexpected exception | Exception | FAILED |

**Note**: "403/404 always means URL expiration" is too strong. The architecture treats 403/404 as "likely expired" and fails the episode. User can re-enqueue to get fresh URLs.

---

## 4. CONCURRENCY / maxParallel

### Design Choice

One Worker per episode + Room active-count gating.

### Honest Assessment

**This is a BEST-EFFORT concurrency guideline, NOT a hard distributed semaphore.**

Room count + state update is NOT an atomic distributed semaphore. Race conditions exist:

1. Two Workers start simultaneously, both read `activeCount` before either writes state
2. WorkManager may enqueue Workers faster than Room state updates
3. The `>=` comparison removes the obvious +1 comparison error but races can still exceed maxParallel

### Implementation

```kotlin
// In DownloadWorker.doWork()
val activeCount = episodeDownloadDao.countActive()
// countActive() counts only DOWNLOADING/ASSEMBLING episodes (not QUEUED/RESOLVING)
// Use >= to remove the +1 comparison error; races may still exceed maxParallel
if (activeCount >= maxParallel) {
    // Too many active — defer this Worker
    Log.d(TAG, "Active count $activeCount >= maxParallel $maxParallel, deferring")
    return Result.success() // Not a failure, just deferred
}

// Update state to DOWNLOADING (counts as active)
episodeDownloadDao.updateState(episodeId, "DOWNLOADING")
```

### DAO Query

```sql
-- countActive() — counts only episodes consuming network/disk resources
SELECT COUNT(*) FROM episode_downloads WHERE state IN ('DOWNLOADING', 'ASSEMBLING')
```

### Python Parity (Behavioral Intent, Not Identical Semantics)

- Python: `asyncio.Semaphore(maxParallel)` — cooperative, I/O-bound tasks can overlap at boundaries
- Android: Room active-count gating — cooperative, best-effort with race conditions
- **Parity**: Behavioral intent matched (both are cooperative soft limits), NOT identical synchronization semantics

**Why this is acceptable**:
1. Python's asyncio is single-process; Android WorkManager is multi-process with eventual consistency
2. The approximation is justified because both systems allow temporary over-subscription at I/O boundaries
3. Each Worker is independently resumable via `segmentStore.doneIndices()`
4. The segment-level Semaphore (hlsWorkers=24) provides the actual per-episode concurrency bound

**Documentation**:
> "maxParallel is a best-effort guideline. Under rapid enqueue conditions, more than maxParallel downloads may run concurrently due to Room query/update race conditions. This is acceptable because each Worker is independently resumable and bounded by segment-level Semaphore (hlsWorkers=24). This matches Python's cooperative behavior where asyncio tasks can overlap at I/O boundaries, but does NOT provide identical synchronization semantics."

---

## 5. UNIQUE WORK / KEEP SEMANTICS

### Work Name Format

```kotlin
val workName = "episode_download_${episodeId}"
```

### ExistingWorkPolicy.KEEP Behavior

**KEEP ignores the new request only when unfinished/pending work with that unique name exists.**

- If existing work is RUNNING or ENQUEUED (unfinished): KEEP ignores the new request
- If existing work is in a terminal state (COMPLETED/FAILED/CANCELLED): KEEP allows the new request to be inserted
- If no work exists with that name: KEEP allows the new request to be inserted

**Important**: Terminal WorkManager work does NOT block new enqueue with KEEP. Only unfinished work blocks.

### enqueueWorker Function

```kotlin
fun enqueueWorker(context: Context, episodeId: String) {
    val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf("episode_id" to episodeId))
        .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "episode_download_$episodeId",
            ExistingWorkPolicy.KEEP,  // Only blocks if work is unfinished
            workRequest
        )
}
```

### Behavior by Scenario

| Scenario | Existing Work State | KEEP Behavior | Result |
|----------|---------------------|---------------|--------|
| Initial enqueue | None | Insert new | New Worker created |
| Duplicate enqueue (same episode) | RUNNING | Ignore new | Existing Worker continues |
| Duplicate enqueue (same episode) | ENQUEUED | Ignore new | Existing Worker stays enqueued |
| Re-enqueue after FAILED | COMPLETED (terminal) | Insert new | New Worker created |
| Re-enqueue after CANCELLED | CANCELLED (terminal) | Insert new | New Worker created |
| Re-enqueue after COMPLETED | COMPLETED (terminal) | Insert new | New Worker created |
| Reconcile QUEUED+m3u8Url | None or terminal | Insert new | New Worker created |
| Reconcile DOWNLOADING (Worker not running) | RUNNING (WorkManager says so) | Ignore new | No action needed |

### reEnqueue Pattern (User Retry)

```kotlin
suspend fun reEnqueue(episodeId: String) {
    val episode = dao.getById(episodeId) ?: return
    if (!episode.isTerminal()) return  // Guard: only re-enqueue from terminal states
    
    // Reset state for fresh download
    dao.updateState(episodeId, "QUEUED")
    dao.incrementRetryCount(episodeId)
    dao.clearStreamInfo(episodeId)
    
    // Enqueue new Worker (KEEP will insert because existing work is terminal)
    enqueueWorker(episodeId)
}
```

### Cancel

```kotlin
fun cancelWorker(context: Context, episodeId: String) {
    WorkManager.getInstance(context)
        .cancelUniqueWork("episode_download_$episodeId")
}
```

### Idempotency

- `enqueueWorker()` with KEEP is safe to call multiple times:
  - If Worker is RUNNING: KEEP ignores new request (no duplicate)
  - If Worker is terminal: KEEP inserts new request (correct for re-enqueue)
  - If no Worker exists: KEEP inserts new request (correct for initial enqueue)

---

## 6. DEFERRED WORKER LIVENESS

### Problem

When a Worker sees `activeCount >= maxParallel` and returns `Result.success()`, the episode remains QUEUED in Room. How are deferred QUEUED+m3u8Url episodes guaranteed to make progress?

### Deterministic Scheduling Mechanism

Deferred episodes are guaranteed to make progress through TWO mechanisms:

#### Mechanism 1: Worker Completion Trigger

When a Worker completes (success or failure), it triggers the next deferred episode:

```kotlin
// In DownloadWorker.doWork(), after completing download
private suspend fun triggerNextDeferredEpisode() {
    val nextEpisode = dao.getNextQueuedWithM3u8()
    if (nextEpisode != null) {
        val activeCount = dao.countActive()
        if (activeCount < maxParallel) {
            enqueueWorker(nextEpisode.episodeId)
        }
    }
}
```

**DAO method**:
```kotlin
@Query("SELECT * FROM episode_downloads WHERE state = 'QUEUED' AND m3u8Url IS NOT NULL ORDER BY createdAt ASC LIMIT 1")
suspend fun getNextQueuedWithM3u8(): EpisodeDownloadEntity?
```

#### Mechanism 2: Reconciliation on Startup

If all Workers have completed and no Worker triggers the next deferred episode (e.g., all Workers returned `Result.success()` after deferring), reconciliation on next app startup will:

1. See QUEUED+m3u8Url episodes
2. Enqueue Workers for them

#### Liveness Guarantee

**Combined**: Worker completion trigger handles the common case (immediate scheduling). Reconciliation handles the edge case (all Workers deferred simultaneously).

**No state exists where deferred episodes are permanently stuck** because:
1. Worker completion trigger fires after every Worker finishes
2. Reconciliation fires on every app startup
3. Both mechanisms check for QUEUED+m3u8Url episodes and enqueue them

### DAO Method for Completion Trigger

```kotlin
@Query("SELECT * FROM episode_downloads WHERE state = 'QUEUED' AND m3u8Url IS NOT NULL ORDER BY createdAt ASC LIMIT 1")
suspend fun getNextQueuedWithM3u8(): EpisodeDownloadEntity?
```

**Race condition note**: Between SELECT and enqueue, another process could change the episode's state. This is safe because KEEP prevents duplicate Workers.

---

## 7. FOREGROUND WORKER / ANDROID 16

### Foreground Worker Behavior

WorkManager supports long-running foreground Workers beyond the ordinary short Worker execution window. However:

- Android 16+ job/runtime quota behavior still applies
- Foreground-service restrictions still apply
- The Worker must declare the appropriate foreground service type

### Foreground Service Type Configuration

Because `targetSdk = 37`, the app must declare the foreground service type for WorkManager's internal `SystemForegroundService`:

**Manifest permissions**:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**WorkManager's internal service**: When `setForeground()` is called on a Worker, WorkManager runs the WorkRequest via its internal `SystemForegroundService`. This service requires:
- `FOREGROUND_SERVICE` permission (app-level)
- `FOREGROUND_SERVICE_DATA_SYNC` permission (Android 14+)
- The service type is `dataSync` (matched by the permission)

**No custom DownloadForegroundService component** is needed. WorkManager manages the foreground service lifecycle internally.

### Notification Channel

```kotlin
val channel = NotificationChannel(
    "download_channel",
    "Downloads",
    NotificationManager.IMPORTANCE_LOW
).apply {
    description = "Shows download progress"
    setShowBadge(false)
}
notificationManager.createNotificationChannel(channel)
```

### DownloadWorker Implementation

```kotlin
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val episodeDownloader: EpisodeDownloader,
    private val dao: EpisodeDownloadDao,
    private val notificationManager: DownloadNotificationManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString("episode_id") ?: return Result.failure()
        
        // Set foreground with notification (WorkManager manages the foreground service)
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

### AGENTS.md §17 Compliance

WorkManager version MUST be verified against current toolchain BEFORE implementation. Verify compatibility with:
- compileSdk 37
- targetSdk 37
- minSdk 32
- AGP 9.4.1
- Gradle 9.7.1
- Kotlin 2.4.20
- JDK 25

---

## 8. APPLICATION RECONCILIATION LIFECYCLE

### Owned Coroutine Scope

Replace the unmanaged fire-and-forget `CoroutineScope(Dispatchers.IO).launch` with an explicitly owned lifecycle-safe application scope:

```kotlin
class PaheBatcherApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { PaheDatabase.getInstance(this) }
    private val workManager by lazy { WorkManager.getInstance(this) }
    
    override fun onCreate() {
        super.onCreate()
        
        // Run reconciliation on first process start
        // Idempotent: safe to run repeatedly, deduplication via AtomicBoolean
        if (reconciliationRun.compareAndSet(false, true)) {
            applicationScope.launch {
                val reconciler = DownloadStateReconciler(
                    episodeDao = database.episodeDownloadDao(),
                    workManager = workManager
                )
                reconciler.reconcile()
            }
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }
    
    companion object {
        private val reconciliationRun = AtomicBoolean(false)
    }
}
```

### Key Properties

1. **Process-scoped**: `applicationScope` lives for the entire process lifetime
2. **Supervised**: `SupervisorJob()` prevents child failure from cancelling the scope
3. **Idempotent**: `AtomicBoolean` ensures reconciliation runs only once per process
4. **Safe to run repeatedly**: Reconciliation is inherently idempotent (same state → same result)
5. **No dependency on startup ordering**: Reconciliation can run after any component is initialized

### Idempotency as Correctness Property

Reconciliation is idempotent because:
- Room updates are idempotent (SET state = X)
- `enqueueWorker()` with KEEP is idempotent (no duplicate Workers)
- Segment cleanup is idempotent (deleteRecursively on already-deleted directory is no-op)

---

## 9. MIGRATION WORDING (Corrected)

### Migration vs Fresh Install

- **Migration**: v1→v2 migration is exercised ONLY against existing v1 databases. The `DROP TABLE IF EXISTS segments` handles the case where the segments table exists.
- **Fresh installs**: Room creates the v2 schema directly from the `@Database` annotation. No migration is needed because `SegmentEntity` is not in the entities array.

### Migration Test

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test
    fun migrate1To2() {
        // Create v1 database with test data
        // Run migration MIGRATION_1_2
        // Verify data preserved, segments table dropped, new columns accessible
    }
}
```

---

## 10. ERROR CLASSIFICATION (DownloadWorker Mapping)

### Worker Error Classification

The DownloadWorker maps `EpisodeDownloader.DownloadResult` to Room state:

```kotlin
when {
    // Cancellation detected
    result.wasCancelled -> {
        dao.updateState(episodeId, "CANCELLED")
        Result.failure()
    }
    // Assembly failure (permanent)
    result.assemblyError != null -> {
        dao.updateError(episodeId, "Assembly failed: ${result.assemblyError}")
        Result.failure()
    }
    // Download failure (all retries exhausted)
    !result.success -> {
        dao.updateError(episodeId, result.error ?: "Download failed")
        Result.failure()
    }
    // Success
    else -> {
        dao.updateCompleted(episodeId, result.outputFile?.absolutePath ?: "")
        Result.success()
    }
}
```

### Cancellation Detection

`CancellationException` is detected in the Worker's `doWork()`:

```kotlin
override suspend fun doWork(): Result {
    return try {
        val result = downloadEpisode(episodeId)
        // ... handle result
    } catch (e: kotlinx.coroutines.CancellationException) {
        dao.updateState(episodeId, "CANCELLED")
        Result.failure()
    }
}
```

---

## 11. RECONCILIATION

### DownloadStateReconciler

Runs on app startup (triggered by Application.onCreate).

### Query Model (Fixed)

**Problem**: Previous `getNonTerminalCount()` and `getAllNonTerminal()` excluded COMPLETED episodes, making the COMPLETED reconciliation branch unreachable.

**Solution**: Reconciliation queries ALL episodes, not just non-terminal ones. The fast-path optimization is preserved but cannot skip required COMPLETED reconciliation.

```kotlin
// Get all episodes that need reconciliation
// This includes:
// - Non-terminal episodes (QUEUED, RESOLVING, DOWNLOADING, ASSEMBLING)
// - COMPLETED episodes that may need segment cleanup
@Query("SELECT * FROM episode_downloads WHERE state NOT IN ('FAILED', 'CANCELLED')")
suspend fun getEpisodesForReconciliation(): List<EpisodeDownloadEntity>
```

**Fast-path**: Skip reconciliation only if there are ZERO episodes in any state that requires action:

```kotlin
// Fast-path: skip if no episodes exist at all (fresh install, no downloads)
val totalCount = dao.getTotalEpisodeCount()
if (totalCount == 0) return
```

### COMPLETED Invariant

```
COMPLETED => outputPath != null && outputPath.isNotEmpty() && output exists && output.length > 0
```

If this invariant is violated (e.g., user deleted MP4), reconciliation marks the episode as FAILED.

### Branch Logic

```kotlin
suspend fun reconcile() {
    // Fast-path: skip if no episodes exist at all
    val totalCount = dao.getTotalEpisodeCount()
    if (totalCount == 0) return
    
    // Get all episodes that may need reconciliation
    val episodes = dao.getEpisodesForReconciliation()
    
    for (episode in episodes) {
        when (episode.state) {
            "COMPLETED" -> {
                // Verify COMPLETED invariant: outputPath != null && exists && non-empty
                val outputPath = episode.outputPath
                if (outputPath == null || outputPath.isEmpty()) {
                    // outputPath not set — violation, mark FAILED
                    dao.updateState(episode.episodeId, "FAILED")
                } else {
                    val output = File(outputPath)
                    if (!output.exists() || output.length() == 0L) {
                        // Output missing or empty — mark FAILED (user deleted or storage issue)
                        dao.updateState(episode.episodeId, "FAILED")
                    } else if (episode.segmentDir != null) {
                        // Output exists — clean up orphaned segments from process death during cleanup
                        val segDir = File(episode.segmentDir)
                        if (segDir.exists()) segDir.deleteRecursively()
                    }
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
                    val outputFile = File(File(episode.segmentDir), outputFileName)
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
                // Check if Worker is already running before enqueuing
                val workInfos = workManager.getWorkInfosForUniqueWork("episode_download_${episode.episodeId}").await()
                val isRunning = workInfos.any { it.state == WorkInfo.State.RUNNING }
                if (!isRunning) {
                    // Worker not running — re-enqueue (will resume via segmentStore.doneIndices())
                    dao.updateStateForResume(episode.episodeId, "DOWNLOADING", "QUEUED")
                    enqueueWorker(episode.episodeId)
                }
                // else: Worker already running — no action needed
            }
            
            "RESOLVING" -> {
                // Resolution interrupted — reset to QUEUED (requires foreground WebView)
                dao.updateState(episode.episodeId, "QUEUED")
                dao.clearStreamInfo(episode.episodeId)
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

---

## 12. PROGRESS PERSISTENCE

### Throttling Strategy

Progress is written to Room at most every 5 seconds, not per segment.

1. **In-memory counter**: Track completed segments in Worker memory
2. **Throttled Room writes**: Write to Room at most every 5 seconds OR on state transition
3. **Immediate state transitions**: QUEUED → RESOLVING → DOWNLOADING → ASSEMBLING → COMPLETED/FAILED

### retryCount Semantics

**Definition**: `retryCount` counts the number of times the user (or reconciliation) explicitly re-triggered download for this episode.

**Increment point**: `retryCount` increments ONLY in `DownloadRepository.reEnqueue()`:
```kotlin
suspend fun reEnqueue(episodeId: String) {
    val episode = dao.getById(episodeId) ?: return
    // ...
    dao.updateState(episodeId, "QUEUED")
    dao.incrementRetryCount(episodeId)
    // ...
}
```

**CRITICAL**: Modify existing `EpisodeDownloadDao.updateError()` to REMOVE the retryCount increment:
```kotlin
// AFTER (Phase 10):
@Query("UPDATE episode_downloads SET state = 'FAILED', errorMessage = :errorMessage, updatedAt = :timestamp WHERE episodeId = :episodeId")
suspend fun updateError(episodeId: String, errorMessage: String, timestamp: Long = System.currentTimeMillis())
```

**Does NOT count**:
- Internal EpisodeDownloader retries (HTTP retry, episode retry)
- Worker recreation after process death
- WorkManager automatic re-enqueue

---

## 13. PROCESS-DEATH RECOVERY

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

## 14. WORKMANAGER RETRY

### Decision: NO Result.retry() in Phase 10

EpisodeDownloader already handles retries internally:
- HTTP retry: 5 attempts with exponential backoff
- Episode retry: `autoRetry + 1` attempts with exponential backoff
- Permanent errors are NOT retried

When `EpisodeDownloader.download()` returns `DownloadResult(success = false)`, ALL internal retries have been exhausted.

**Recovery path**: User taps "Retry" → `reEnqueue()` → reset state → enqueue Worker.

---

## 15. COMPLETION CONSISTENCY

### Atomic Output Behavior

`FfmpegEpisodeAssembler` uses atomic rename:
1. FFmpeg writes to temp file in `cacheDir/assembly/{episodeId}/`
2. On success, `File.renameTo(finalOutput)` moves to final location
3. Rename is atomic on same filesystem

### Reconciliation After Assembly Race

**Scenario**: MP4 successfully renamed, process dies, Room still says ASSEMBLING

**Reconciliation**: Detects output exists → marks COMPLETED.

---

## 16. TEST PLAN

### Unit Tests

| Test | Coverage |
|------|----------|
| MigrationTest | v1 → v2 migration preserves data, drops segments table |
| StateTransitionTest | All valid state transitions, illegal transitions rejected |
| DownloadRepositoryTest | enqueue, cancel, re-enqueue |
| DownloadStateReconcilerTest | All reconciliation branches including COMPLETED cleanup |
| HlsResultClassificationTest | Error types mapped to correct states |
| ProgressThrottlingTest | Room writes bounded (not per-segment) |
| UniqueWorkTest | KEEP semantics: duplicate while RUNNING, duplicate while terminal |
| DuplicateEnqueueTest | Same episode enqueued twice — existing Worker remains |
| RetryCountSemanticsTest | updateError does NOT increment, reEnqueue increments |
| ReEnqueueGuardTest | reEnqueue from non-terminal state is no-op |

### Integration Tests

| Test | Coverage |
|------|----------|
| WorkerExecutionTest | Worker reads Room, downloads, updates Room |
| ForegroundWorkerTest | Worker starts foreground, posts notification, runs download |
| ProcessDeathRecoveryTest | Kill app during DOWNLOADING, verify resume |
| ConcurrencyTest | maxParallel soft limit behavior, deferred episode scheduling |
| CancellationTest | Cancel during download, verify CANCELLED state |
| TriggerNextDeferredEpisodeTest | Worker completion triggers next queued episode |
| ReconcilerIdempotencyTest | Running reconcile() twice doesn't create duplicate Workers |

### Instrumented Tests

| Test | Coverage |
|------|----------|
| FullDownloadFlowTest | enqueue → resolve → download → assemble → COMPLETED |
| ResumeTest | Kill during download, restart, verify resume |
| NotificationTest | Verify notification posted during download |

---

## 17. PYTHON PARITY

### maxParallel (Behavioral Intent)

- Python: `asyncio.Semaphore(maxParallel)` — cooperative, I/O-bound tasks can overlap
- Android: Room active-count gating — cooperative, best-effort
- **Parity**: Behavioral intent matched (both are cooperative soft limits), NOT identical synchronization semantics

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

### Batch-Level Retry (ANDROID_REQUIRED_DIFFERENCE)

- Python: Automatic batch retry of failed episodes
- Android: Manual user re-enqueue
- **Document**: ANDROID_REQUIRED_DIFFERENCE in LIMITATIONS.md

---

## 18. FILES TO CREATE/MODIFY

### New Files (8)

1. `data/local/migration/Migrations.kt` — v1 → v2 migration
2. `domain/hls/HlsTextResult.kt` — Typed text results
3. `domain/hls/HlsBytesResult.kt` — Typed bytes results
4. `data/download/DownloadRepository.kt` — Foreground download orchestration
5. `data/download/DownloadStateReconciler.kt` — Startup reconciliation
6. `worker/DownloadWorker.kt` — WorkManager Worker (extends CoroutineWorker)
7. `data/download/DownloadConfig.kt` — Configuration (maxParallel, etc.)
8. `notification/DownloadNotificationManager.kt` — Notification management

### New Application Class

Create `PaheBatcherApplication.kt` for reconciliation trigger (see Section 8).

### Modified Files (7)

1. `data/local/PaheDatabase.kt` — Remove destructive fallback, add migration, exportSchema = true
2. `data/local/entity/EpisodeDownloadEntity.kt` — Add m3u8Url, userAgent, referer, segmentDir columns; remove STATE_PAUSED from canResume()
3. `data/local/dao/EpisodeDownloadDao.kt` — Add countActive(), clearStreamInfo(), incrementRetryCount(), updateStateForResume(), getNextQueuedWithM3u8(), getEpisodesForReconciliation(), getTotalEpisodeCount(); modify updateError() to remove retryCount increment
4. `domain/hls/HlsClient.kt` — Change return types to HlsTextResult/HlsBytesResult
5. `data/hls/OkHttpHlsClient.kt` — Implement HlsTextResult/HlsBytesResult returns
6. `data/hls/EpisodeDownloader.kt` — Handle HlsTextResult/HlsBytesResult, update progress throttling
7. `AndroidManifest.xml` — Add permissions (FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS), application class

### Removed Files (2)

1. `data/local/entity/SegmentEntity.kt` — Dead infrastructure
2. `data/local/dao/SegmentDao.kt` — Dead infrastructure

---

## 19. IMPLEMENTATION SEQUENCE

### Authoritative Order

1. **Verify WorkManager compatibility** (AGENTS.md §17 — blocking prerequisite)
2. **HlsTextResult/HlsBytesResult types** (sealed classes)
3. **HlsClient interface** (change return types)
4. **OkHttpHlsClient** (implement HlsTextResult/HlsBytesResult)
5. **EpisodeDownloader** (handle new result types)
6. **Room migration** (remove SegmentEntity, add columns, bump version)
7. **EpisodeDownloadEntity** (new columns, remove STATE_PAUSED)
8. **EpisodeDownloadDao** (new queries, modify updateError)
9. **DownloadConfig** (configuration)
10. **DownloadNotificationManager** (notification channel, notification creation)
11. **DownloadRepository** (foreground orchestration)
12. **DownloadWorker** (CoroutineWorker with setForeground)
13. **DownloadStateReconciler** (startup recovery)
14. **PaheBatcherApplication** (reconciliation trigger)
15. **Update AndroidManifest.xml** (permissions, application class)
16. **Tests**
17. **Build and verify**

---

## 20. SCOPE GUARD

- **No UI**: DownloadViewModel and UI remain Phase 13
- **No Kwik fix**: Do not attempt to solve the Kwik infrastructure blocker
- **No unrelated refactors**: Do not modify code outside Phase 10 scope
- **No Git commands**: Do not use Git commands

---

## 21. DOCUMENTATION

| Document | Status | Action |
|----------|--------|--------|
| ROADMAP.md | Phase 10: PENDING | Update to IN PROGRESS during implementation |
| PARITY.md | Background downloads: PENDING | Update after verification |
| DECISIONS.md | Decision 14: PENDING | **RESOLVE** with Phase 10 choices |
| LIMITATIONS.md | WebView dependency documented | Add ANDROID_REQUIRED_DIFFERENCE for batch retry |

### Decision 14 Resolution

1. **CoroutineWorker with setForeground()** — for background download execution
2. **Soft maxParallel limit** — Room active-count gating, not hard distributed lock
3. **HlsTextResult/HlsBytesResult** — typed error classification for M3U8/segment/key failures
4. **QUEUED dual meaning** — distinguished by m3u8Url presence
5. **One Worker per episode** — simpler than batch Worker, matches Python's per-episode model
6. **ExistingWorkPolicy.KEEP** — safe for concurrent enqueue without interrupting running Workers
7. **Reconciliation includes COMPLETED** — for segment cleanup after process death

---

## 22. FINAL VERDICT

This architecture addresses all 12 mandatory fixes:

1. **RECONCILIATION — COMPLETED BRANCH**: Fixed query model to include COMPLETED episodes; COMPLETED invariant defined; segment cleanup in COMPLETED branch
2. **CANONICAL STORAGE PATH**: Removed all contradictory paths; single canonical structure defined; EpisodeDownloader/FfmpegAssembler/Reconciler all use same path
3. **HLS RESULT TYPE**: Defined HlsTextResult and HlsBytesResult for both text and byte responses; HTTP status codes preserved for M3U8, AES key, and segment requests
4. **maxParallel**: Removed hard cap claim; documented as behavioral parity not identical semantics; `>=` removes +1 error but races may still exceed
5. **UNIQUE WORK / KEEP SEMANTICS**: Corrected KEEP explanation; terminal work does NOT block new enqueue; all scenarios documented
6. **TEST PLAN**: Removed stale REPLACE references; KEEP semantics tested; retryCount semantics tested
7. **DEFERRED WORKER LIVENESS**: Two mechanisms: Worker completion trigger + reconciliation on startup; no permanent stuck state
8. **FOREGROUND WORKER / ANDROID 16**: Corrected indefinite run claim; documented foreground service type requirement; no custom DownloadForegroundService
9. **APPLICATION RECONCILIATION LIFECYCLE**: Owned CoroutineScope with SupervisorJob; idempotent reconciliation; process-scoped
10. **MIGRATION WORDING**: Removed fresh install claim; clarified migration vs fresh install
11. **IMPLEMENTATION SEQUENCE**: Authoritative order with correct dependency ordering
12. **SCOPE GUARD**: No UI, no Kwik fix, no unrelated refactors

**STATUS**: READY FOR FINAL REVIEW
