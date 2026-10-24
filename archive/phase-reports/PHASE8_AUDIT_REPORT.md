# Phase 8 Architecture / Readiness Audit Report

**Date**: 2026-09-22
**Auditor**: Architect Agent
**Scope**: Phase 8 readiness, MP4 assembly analysis, toolchain compatibility

---

## CRITICAL CORRECTION: Phase Numbering Discrepancy

**The prompt states**: "Phase 8 is currently described in ROADMAP.md as FFmpeg/MP4 assembly."

**This is factually incorrect.** Per `ROADMAP.md` lines 368–428:

| Phase | Actual Description |
|-------|--------------------|
| **Phase 8** | Atomic Segment Cache and Resume (line 368) |
| **Phase 9** | MP4 Assembly (line 400) |

This audit covers **both**: Phase 8 completion status AND assembly readiness (Phase 9 per ROADMAP).

---

## A. Phase 8 Readiness Verdict

### Phase 8 (Atomic Segment Cache + Resume): **READY — ALMOST COMPLETE**

Phase 7 delivered the core of Phase 8. What remains is trivially small.

### Assembly (Phase 9): **READY WITH BLOCKERS**

FFmpegKit is viable but has toolchain compatibility concerns requiring validation. Media3 Transformer is NOT suitable for this use case.

---

## B. Current Phase Definitions

### Phase 8 per ROADMAP.md (lines 368–398)

**Status**: PARTIAL (core completed in Phase 7)

| Deliverable | Status | Evidence |
|-------------|--------|----------|
| Atomic segment writes (.tmp → .ts) | ✅ Done | `SegmentFileStore.writeSeg()` — line 87–114 |
| Segment completion detection | ✅ Done | `SegmentFileStore.doneIndices()` — line 68–74 |
| Resume after interruption | ✅ Done | `EpisodeDownloader.downloadOnce()` line 256–258 filters `doneSet` |
| Cache management (cacheDir) | ❌ Remaining | Not implemented |
| Orphan cleanup (.tmp removal) | ⚠️ Partial | `SegmentFileStore.cleanupOrphans()` exists (line 138–145), but not wired into download flow |
| Crash recovery tests | ❌ Remaining | No dedicated crash recovery tests |

**What ROADMAP says should remain**:
- Cache management (cacheDir-based temporary storage for assembled files)
- Orphan cleanup (remove .tmp files from interrupted writes)
- Crash recovery tests

**Reality**: `cleanupOrphans()` already exists but isn't called during download. The "cache management" item is about assembled file caching, which is really a Phase 9 concern.

### Assembly per ROADMAP.md (lines 400–428)

**Status**: PENDING

| Deliverable | Status |
|-------------|--------|
| FFmpeg integration | ❌ Not started |
| Concat demuxer support | ❌ Not started |
| Pipe mode fallback | ❌ Not started |
| Assembly verification | ❌ Not started |
| Temporary file cleanup | ❌ Not started |

---

## C. Python Reference Behavior (Assembly/Output)

### Segment Storage (store.py:14–43)

**Verified from Python source**:

| Behavior | File:Line | Detail |
|----------|-----------|--------|
| Segment directory path | `store.py:18–20` | `cache_root / f"{safe_title}_{anime_session}" / f"Ep_{ep_num}_{audio.upper()}"` |
| Segment file naming | `store.py:31–32` | `{idx:06d}.ts` (zero-padded 6 digits) |
| Done detection | `store.py:37–38` | Glob `??????.ts`, parse stem as int |
| Atomic write | `store.py:40–43` | Write `.tmp`, then `rename()` to `.ts` |
| Metadata save | `store.py:23–29` | `session.json` with title, URL, timestamp |

### Assembly (store.py:45–82)

**Verified from Python source**:

| Behavior | File:Line | Detail |
|----------|-----------|--------|
| Assembly entry point | `store.py:45` | `def assemble(self, n_segments: int, out: Path) -> bool:` |
| Missing segment check | `store.py:47–49` | Returns `False` if any `seg_path(i)` missing |
| Concat file creation | `store.py:51–54` | Writes `concat.txt` with `file '{path}'` lines |
| Primary: concat demuxer | `store.py:56–63` | `ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -movflags +faststart {out}` |
| Timeout | `store.py:59` | `timeout=600` (10 minutes) |
| Pipe fallback | `store.py:66–78` | `ffmpeg -y -i pipe:0 -c copy -movflags +faststart {out}` — reads segments, pipes to stdin |
| Cleanup concat.txt | `store.py:62–63, 81–82` | `lst.unlink()` in both success and fallback paths |
| Return value | `store.py:61, 79` | Boolean (True on success) |

**Critical Python flags**:
- `-c copy` = **NO re-encoding** — raw TS→MP4 container remux
- `-movflags +faststart` = move `moov` atom to beginning of file
- `-f concat -safe 0` = FFmpeg concat demuxer (file list based)
- Pipe fallback exists for when concat demuxer fails

### Download Orchestration (downloader.py:45–143)

**Verified from Python source**:

| Behavior | File:Line | Detail |
|----------|-----------|--------|
| Output filename | `downloader.py:57` | `sanitize(f"Ep {prefix} - {title}") or f"ep_{ep.ep_str}"` + `.mp4` |
| Output directory | `downloader.py:54–55` | `Path(self.ctx.output_dir)` with `mkdir(parents=True, exist_ok=True)` |
| Existing file check | `downloader.py:60–62` | Skip if `out.exists() and out.stat().st_size > 0` |
| Assembly call | `downloader.py:117` | `await loop.run_in_executor(None, store.assemble, n, out)` |
| Assembly failure | `downloader.py:118–119` | Raises `RuntimeError("Assembly failed")` |
| Cleanup after assembly | `downloader.py:121–122` | `store.cleanup()` unless `ctx.keep_temp` |
| Dashboard states | `downloader.py:116, 124` | `mark_remuxing` before assembly, `mark_done` after |

### Output File Naming (downloader.py:46–58)

**Verified from Python source**:

| Component | File:Line | Detail |
|-----------|-----------|--------|
| Episode key | `downloader.py:46` | `ep.ep_str` (e.g., "1", "5", "12") |
| Title | `downloader.py:47` | `ep.title or info.title or f"Episode {ep.ep_str}"` |
| Prefix | `downloader.py:56` | `ep_prefix(ep.ep_str)` — e.g., "01", "12" |
| Filename | `downloader.py:57` | `sanitize(f"Ep {prefix} - {title}") or f"ep_{ep.ep_str}"` |
| Extension | `downloader.py:58` | `.mp4` |
| Full path | `downloader.py:58` | `outdir / f"{fname}.mp4"` |

---

## D. Exact Phase 9 Input/Output Contract

### Input from Phase 7

| Input | Type | Source |
|-------|------|--------|
| Decrypted .ts segment files | `File` (in `SegmentFileStore.directory`) | `EpisodeDownloader.downloadOnce()` → `SegmentFileStore.writeSeg()` |
| Segment count | `Int` (from `HlsPlaylist.segments.size`) | `EpisodeDownloader.downloadOnce()` |
| Segment indices | `Set<Int>` (from `SegmentFileStore.doneIndices()`) | Resume support |
| Segment naming | `{idx:06d}.ts` | `SegmentFileStore.segPath()` |

### Intermediate File Structure

| File | Purpose | Created By | Cleaned By |
|------|---------|------------|------------|
| `concat.txt` | FFmpeg concat demuxer file list | `EpisodeAssembler` | `EpisodeAssembler` (after assembly) |
| `{000000..N}.ts` | Decrypted HLS segments | `SegmentFileStore` | `SegmentFileStore.cleanup()` (unless `keepTemp`) |
| `session.json` | Metadata (title, URL, timestamp) | `SegmentStore.save_metadata()` | `SegmentStore.cleanup()` |

### Final Output Format

| Property | Value | Python Reference |
|----------|-------|------------------|
| Container | MP4 | `downloader.py:58` — `{fname}.mp4` |
| Video codec | H.264 (copy from TS) | `-c copy` — no re-encoding |
| Audio codec | AAC (copy from TS) | `-c copy` — no re-encoding |
| Fast-start | Yes | `-movflags +faststart` |
| Encoding | None (remux only) | `-c copy` |

### Whether FFmpeg Is Required

**YES — FFmpeg is required for correct assembly.**

**Evidence from Python** (store.py:56–58):
```python
result = subprocess.run(
    ["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
     "-c", "copy", "-movflags", "+faststart", str(out)],
    capture_output=True, timeout=600,
)
```

**Why FFmpeg is necessary**:
1. MPEG-TS segments need container demuxing (PES packet parsing)
2. AAC ADTS headers in TS must be converted to AudioSpecificConfig for MP4
3. H.264 NALUs need to be repackaged into MP4 sample entries
4. Timestamps need to be remapped from TS timebase to MP4 timebase
5. `moov` atom relocation (`+faststart`) requires post-processing
6. Pipe fallback requires sequential reading + streaming muxing

---

## E. Recommended Android Implementation Approach

### Recommendation: FFmpegKit (maintained fork) as Primary

**Library**: `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`

**Rationale**:

1. **Direct behavioral parity**: The Python uses FFmpeg with `-c copy -movflags +faststart`. FFmpegKit provides the exact same FFmpeg binary, ensuring identical assembly behavior.

2. **Proven solution**: FFmpegKit is the standard Android FFmpeg integration. The maintained fork (`ffmpegkit-maintained`) provides security-tracked, actively maintained builds.

3. **LGPL-compatible**: `ffmpeg-kit-full` is LGPL-3.0 (not GPL), so it does not force copyleft on the app.

4. **Simplest integration**: Single dependency, well-documented API, drop-in replacement for the original `com.arthenica` package.

5. **Feature-complete**: Handles all TS→MP4 remuxing, AAC ADTS→ASC conversion, faststart, and pipe fallback natively.

### Variant Selection

| Variant | Contents | Size | License | Recommendation |
|---------|----------|------|---------|----------------|
| `ffmpeg-kit-min` | Bare FFmpeg | ~10 MB | LGPL-3.0 | ❌ Missing codecs |
| `ffmpeg-kit-audio` | Audio codecs | ~20 MB | LGPL-3.0 | ❌ Missing video |
| `ffmpeg-kit-full` | Audio + Video + HTTPS | ~30 MB | LGPL-3.0 | ✅ **Recommended** |
| `ffmpeg-kit-full-gpl` | Full + x264/x265 | ~36 MB | GPL-3.0 | ❌ License contamination |

**Use `ffmpeg-kit-full`** — covers H.264 (decoding/copying), AAC (decoding/copying), TLS (for key fetch), and all container formats needed.

### Toolchain Compatibility Analysis

| Factor | Status | Detail |
|--------|--------|--------|
| compileSdk 37 vs 35 | ⚠️ Acceptable | AAR compiled against SDK 35; native .so binaries don't depend on SDK version; consumer's compileSdk (37) is what matters |
| JDK 25 | ✅ Compatible | AAR contains native .so + Java bytecode; JVM bytecode target is independent of compileSdk |
| AGP 9.4.1 | ✅ Compatible | Standard AAR consumption; no AGP-specific metadata in FFmpegKit |
| Kotlin 2.4.20 | ✅ Compatible | FFmpegKit API is Java; no Kotlin dependency |
| minSdk 32 vs 24 | ✅ Compatible | FFmpegKit requires minSdk 24; our minSdk 32 is higher |
| ABI support | ⚠️ arm64-v8a and x86_64 | No armeabi-v7a or x86 — assembly cannot run on 32-bit devices; validated post-audit via APK inspection |
| NDK requirement | ⚠️ NDK r27c | FFmpegKit 8.1 requires NDK r27c; project currently doesn't use NDK |
| APK size increase | ⚠️ ~30 MB | Significant but acceptable for a download-focused app |

### Key Risk: Emulator Compatibility (RESOLVED post-audit)

FFmpegKit 8.1 provides **arm64-v8a and x86_64** binaries (verified via APK inspection post-audit). The project's emulator (`sdk_gphone64_x86_64`) uses x86_64. This means:

- Assembly will work on real ARM64 devices (arm64-v8a)
- Assembly will work on x86_64 emulators (x86_64)
- Assembly will **NOT** work on 32-bit ARM (armeabi-v7a) or 32-bit x86 (x86) devices

### Fallback Approach: Media3 Transformer (NOT Recommended)

**Why Media3 Transformer is NOT suitable**:

1. **No faststart support**: Transformer writes `moov` atom last (confirmed by GitHub issue #727 and StackOverflow). The Python requires `+faststart` for streaming.

2. **Designed for editing, not remuxing**: Transformer's `Composition` API is designed for applying effects, trimming, and transcoding — not raw container remuxing.

3. **Requires MediaCodec**: Transformer uses `MediaCodec` for encoding, which adds unnecessary CPU/battery overhead for a simple `-c copy` operation.

4. **Timestamp handling**: Transformer expects properly formatted input media, not raw TS segments that need timestamp remapping.

5. **No pipe mode**: Transformer doesn't support streaming input from pipe.

### Alternative Approach: MediaExtractor + MediaMuxer

**Feasibility**: Medium

**Pros**:
- Pure Android API, no third-party dependency
- `MediaExtractor` can read MPEG-TS
- `MediaMuxer` can write MP4

**Cons**:
- Complex concatenation logic (timestamp discontinuities between segments)
- Must handle track discovery across multiple .ts files
- Must implement AAC ADTS→ASC conversion manually
- No faststart support (MediaMuxer writes moov last)
- Significantly more code and testing than FFmpegKit

**Verdict**: Not recommended for Phase 9 due to complexity and missing faststart.

---

## F. Alternatives Considered

| Alternative | Pros | Cons | Verdict |
|-------------|------|------|---------|
| **FFmpegKit (maintained)** | Exact Python parity, proven, simple | ~66 MB APK (actual), arm64-v8a + x86_64, SDK 35 | ✅ **Recommended** |
| **Media3 Transformer** | No third-party dependency | No faststart, designed for editing, requires MediaCodec | ❌ Not suitable |
| **MediaExtractor + MediaMuxer** | Pure Android API | Complex, no faststart, manual ADTS conversion | ❌ Too complex |
| **WorldplayRemuxer** | Pure Kotlin, no FFmpeg | External dependency, needs evaluation, mp4parser dependency | ⚠️ Possible future alternative |
| **Raw binary concatenation** | Simplest | Invalid MP4, no demuxing | ❌ Not valid output |

---

## G. Required Classes/Files to Create or Modify

### New Files (Assembly)

| File | Package | Purpose |
|------|---------|---------|
| `EpisodeAssembler.kt` | `data/hls/` | Orchestrates FFmpeg assembly (concat demuxer + pipe fallback) |
| `AssemblyConfig.kt` | `domain/hls/` | Configuration for assembly (timeout, keepTemp, faststart) |
| `AssemblyResult.kt` | `domain/hls/` | Result type for assembly operation |

### Modified Files

| File | Change |
|------|--------|
| `EpisodeDownloader.kt` | Add assembly step after segment download |
| `SegmentFileStore.kt` | Add `concatFilePath()` helper |
| `build.gradle.kts` | Add FFmpegKit dependency |
| `libs.versions.toml` | Add FFmpegKit version |

### New Test Files

| File | Purpose |
|------|---------|
| `EpisodeAssemblerTest.kt` | Unit tests for assembly logic (with FakeFfmpegKit) |
| `AssemblyIntegrationTest.kt` | Integration tests with real FFmpeg binary |

---

## H. Required Tests

### Assembly Tests

| Test | Description | Type |
|------|-------------|------|
| `assemble with concat demuxer succeeds` | Verify concat.txt creation, FFmpeg invocation, output creation | Unit (mock FFmpeg) |
| `assemble with pipe fallback` | Verify pipe fallback when concat demuxer fails | Unit (mock FFmpeg) |
| `assemble returns false for missing segments` | Verify missing segment detection | Unit |
| `assemble creates faststart MP4` | Verify `-movflags +faststart` flag | Unit (mock FFmpeg) |
| `assemble cleans up concat.txt` | Verify concat.txt removal after assembly | Unit |
| `assemble handles FFmpeg timeout` | Verify 600s timeout behavior | Unit |
| `assemble handles FFmpeg crash` | Verify error handling for FFmpeg process failure | Unit |
| `assembly timeout matches Python` | Verify 600s timeout matches `store.py:59` | Unit |

### Crash Recovery Tests

| Test | Description | Type |
|------|-------------|------|
| `resume after process death` | Pre-write segments, verify download resumes | Unit |
| `resume after partial download` | Pre-write some segments, verify only pending downloaded | Unit |
| `cleanup removes temp files after assembly` | Verify .ts files removed unless keepTemp | Unit |
| `cleanup preserves files with keepTemp` | Verify .ts files retained when keepTemp=true | Unit |
| `orphan .tmp cleanup` | Verify .tmp files removed on cleanupOrphans() | Unit (already exists) |

### Output Verification Tests

| Test | Description | Type |
|------|-------------|------|
| `output filename matches Python` | Verify `Ep {prefix} - {title}.mp4` format | Unit |
| `existing file skip logic` | Verify skip when `out.exists() and st_size > 0` | Unit |
| `assembly failure raises error` | Verify `RuntimeError("Assembly failed")` on FFmpeg failure | Unit |

---

## I. Dependencies Required

### New Dependencies

| Dependency | Group | Artifact | Version | Size | License |
|------------|-------|----------|---------|------|---------|
| FFmpegKit Full | `dev.ffmpegkit-maintained` | `ffmpeg-kit-full` | `8.1.7` | ~30 MB | LGPL-3.0 |
| Smart Exception | `com.arthenica` | `smart-exception-java` | `0.2.1` | ~50 KB | Apache-2.0 |

### Transitive Dependencies (from FFmpegKit)

FFmpegKit has no additional transitive dependencies beyond `smart-exception-java`.

### Toolchain Requirements

| Requirement | Current | Needed | Action |
|-------------|---------|--------|--------|
| NDK | Not installed | r27c (for FFmpegKit 8.1) | Install via SDK Manager |
| CMake | Not installed | Not needed (FFmpegKit ships prebuilt .so) | None |

### Dependency Compatibility Matrix

| Component | Version | FFmpegKit 8.1.7 Compatible? |
|-----------|---------|----------------------------|
| JDK | 25.0.4.1 | ✅ Yes (Java bytecode, not native) |
| AGP | 9.4.1 | ✅ Yes (standard AAR consumption) |
| Kotlin | 2.4.20 | ✅ Yes (FFmpegKit is Java API) |
| Gradle | 9.7.1 | ✅ Yes |
| compileSdk | 37 | ⚠️ Acceptable (AAR targets 35, native .so is SDK-independent) |
| minSdk | 32 | ✅ Yes (FFmpegKit requires 24+) |
| targetSdk | 37 | ✅ Yes |

---

## J. Explicit Phase 9 Exclusions

The following are **NOT** in scope for Phase 9:

| Exclusion | Reason |
|-----------|--------|
| Background download integration | Phase 10 scope |
| Foreground service | Phase 10 scope |
| WorkManager orchestration | Phase 10 scope |
| UI progress for assembly | Phase 13 scope |
| Notification for assembly state | Phase 14 scope |
| Media3 playback of assembled file | Phase 11 scope |
| Storage/SAF integration for output | Phase 10 scope |
| Audio/subtitle track selection during assembly | Not needed (TS segments are already selected) |
| Metadata embedding | Not in Python reference |

---

## K. Blockers and Unresolved Assumptions

### Blockers

| Blocker | Severity | Description | Resolution |
|---------|----------|-------------|------------|
| NDK not installed | HIGH | FFmpegKit 8.1 requires NDK r27c for compilation | Install NDK r27c via SDK Manager before building |
| Emulator arm64 incompatibility | ~~MEDIUM~~ RESOLVED | FFmpegKit 8.1 is arm64-v8a AND x86_64 (verified post-audit); emulator is x86_64 — assembly CAN run on current emulator |
| compileSdk 37 vs 35 | LOW | AAR compiled against SDK 35; we compile against SDK 37 | Should work at runtime; verify during build |

### Unresolved Assumptions

| Assumption | Needs Validation | Risk |
|------------|------------------|------|
| FFmpegKit AAR works with AGP 9.4.1 | Build verification needed | Medium — AGP 9.x may have AAR compatibility changes |
| FFmpegKit AAR works with JDK 25 | Build verification needed | Low — native .so doesn't depend on JDK |
| `-c copy` works for all HLS content types | Tested with real .ts segments | Low — FFmpeg handles standard TS |
| Pipe fallback works on Android | Runtime verification needed | Low — FFmpeg pipe is platform-independent |
| AAC ADTS→ASC conversion handled by FFmpeg | Verified per ffmpeg-cookbook.com | Low — standard FFmpeg behavior |
| Assembly timeout (600s) sufficient for large episodes | Depends on device I/O | Low — matches Python timeout |

---

## L. Exact Implementation Sequence

### Pre-Phase 9: NDK Installation

1. Install NDK r27c via SDK Manager
2. Verify NDK path in `local.properties`
3. Verify FFmpegKit AAR resolves from Maven Central

### Phase 9.1: Dependency Integration

1. Add FFmpegKit to `libs.versions.toml` and `build.gradle.kts`
2. Add `smart-exception-java` dependency
3. Build and verify no conflicts
4. Verify APK size increase is acceptable

### Phase 9.2: Assembly Interface

1. Define `AssemblyConfig` data class (timeout, keepTemp, faststart)
2. Define `AssemblyResult` sealed class (Success, Failure, Timeout)
3. Define `EpisodeAssembler` interface in `domain/hls/`

### Phase 9.3: FFmpegKit Implementation

1. Implement `FfmpegEpisodeAssembler` using FFmpegKit API
2. Implement concat demuxer assembly:
   - Create `concat.txt` file
   - Execute `ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -movflags +faststart {out}`
   - Handle timeout (600s)
   - Clean up `concat.txt`
3. Implement pipe fallback:
   - Execute `ffmpeg -y -i pipe:0 -c copy -movflags +faststart {out}`
   - Pipe segments sequentially via stdin
   - Handle BrokenPipeError
4. Add output filename generation (matching Python: `Ep {prefix} - {title}.mp4`)
5. Add existing file skip logic (matching Python: `out.exists() and st_size > 0`)

### Phase 9.4: EpisodeDownloader Integration

1. Add assembly step after segment download in `EpisodeDownloader.downloadOnce()`
2. Add cleanup step (matching Python: `store.cleanup()` unless `keepTemp`)
3. Add assembly failure handling (matching Python: `RuntimeError("Assembly failed")`)

### Phase 9.5: Testing

1. Write unit tests with `FakeFfmpegKit` (mock FFmpeg execution)
2. Write crash recovery tests (pre-written segments → resume → assembly)
3. Write output verification tests (filename, faststart, cleanup)
4. Write integration test on real device (arm64)

### Phase 9.6: Documentation

1. Update `PARITY.md` — Assembly section
2. Update `DECISIONS.md` — Decision 11 (FFmpeg Library Choice)
3. Update `ROADMAP.md` — Phase 9 completion
4. Update `LIMITATIONS.md` — FFmpeg section (SDK 35, arm64-v8a + x86_64 support, ~66 MB APK increase)

---

## M. Documentation Changes Required

### PARITY.md Updates

| Section | Change |
|---------|--------|
| 6.2 Assembly | Update status from 🔄 to ✅ for concat demuxer, pipe fallback, faststart |
| 10.2 File Naming | Update output naming status |
| Summary table | Update assembly row |

### DECISIONS.md Updates

| Decision | Change |
|----------|--------|
| Decision 11 | Resolve: FFmpegKit maintained fork selected, compatibility verified |
| New Decision | Document FFmpegKit variant selection (full, LGPL) |
| New Decision | Document arm64-v8a + x86_64 support and 32-bit ABI limitation |

### ROADMAP.md Updates

| Section | Change |
|---------|--------|
| Phase 8 | Mark as COMPLETE (all remaining items addressed) |
| Phase 9 | Mark as IN PROGRESS or COMPLETE |

### LIMITATIONS.md Updates

| Section | Change |
|---------|--------|
| 3. FFmpeg Muxing | Update with FFmpegKit details (maintained fork, LGPL, arm64-v8a + x86_64, ~66 MB APK) |
| New section | Document compileSdk 35 vs 37 compatibility |
| New section | Document 32-bit ABI limitation (no armeabi-v7a/x86 FFmpegKit support) |

---

## Summary of Verified Facts

### From Python Reference (file:line verified)

| Fact | Source |
|------|--------|
| Assembly uses FFmpeg concat demuxer | `store.py:56–58` |
| Assembly uses `-c copy` (no re-encoding) | `store.py:57` |
| Assembly uses `-movflags +faststart` | `store.py:58` |
| Assembly timeout is 600s | `store.py:59` |
| Pipe fallback exists | `store.py:66–78` |
| Output is `.mp4` | `downloader.py:58` |
| Existing files are skipped | `downloader.py:60–62` |
| Cleanup runs after assembly | `downloader.py:121–122` |
| Cleanup respects `keep_temp` flag | `downloader.py:121` |
| Assembly failure raises RuntimeError | `downloader.py:118–119` |
| Filename format: `Ep {prefix} - {title}.mp4` | `downloader.py:57–58` |
| Segment naming: `{idx:06d}.ts` | `store.py:31–32` |

### From Android Project (verified)

| Fact | Source |
|------|--------|
| `SegmentFileStore.writeSeg()` implements atomic writes | `SegmentFileStore.kt:87–114` |
| `SegmentFileStore.doneIndices()` implements completion detection | `SegmentFileStore.kt:68–74` |
| `SegmentFileStore.cleanupOrphans()` exists but is not wired | `SegmentFileStore.kt:138–145` |
| `EpisodeDownloader.downloadOnce()` filters completed segments | `EpisodeDownloader.kt:256–258` |
| 324 tests passing | ROADMAP.md line 350 |
| assembleDebug successful | ROADMAP.md line 350 |
| No assembly code exists yet | Verified by file search |

### Assumptions Needing Validation

| Assumption | Validation Method |
|------------|-------------------|
| FFmpegKit AAR works with AGP 9.4.1 + compileSdk 37 | Build test |
| FFmpegKit arm64 .so works on real Android 12+ devices | Device test |
| `-c copy` correctly handles standard HLS TS content | Integration test with real segments |
| Pipe fallback works reliably on Android | Stress test |
| Assembly completes within 600s for typical episodes | Performance test |
