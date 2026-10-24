# PARITY.md — Python → Android Feature Parity Checklist

## Status Legend

- ✅ **PYTHON_PARITY** — Directly reproduced from PaheBatcher
- 🔄 **PYTHON_BEHAVIOR_ANDROID_IMPLEMENTATION** — Same behavior, different implementation
- 📱 **ANDROID_EXTENSION** — Added specifically for Android
- ⚠️ **ANDROID_REQUIRED_DIFFERENCE** — Behavior differs due to platform constraints
- ❌ **BLOCKED** — Technically unresolved

---

## Phase 3 Completion Status

**Phase 3: Models, Configuration, and Persistence — COMPLETED**

> *Symbol/schema note (pre-Phase-11 reconciliation): some component names in this historical phase snapshot were later renamed or removed; the current Room schema is version **3** with `SegmentEntity` removed (see §9.3).*

Implemented components:
- Domain models: `EpisodeInfo`, `AnimeInfo`, `StreamInfo`, `AppContext` (direct port from Python `models.py`)
- Utility functions: `StringUtils` (port of Python `utils.py`: sanitize, epPrefix, fmtBytes, compactEpRange, audioBadge)
- Configuration persistence: `AppConfig` (DataStore-based, replaces Python `config_manager.py`)
- Room database: `PaheDatabase` with entities for scan cache, download sessions, episode downloads, segments, and watchlist (Phase 3 state — the `segments` table/entity was subsequently removed by the Phase 10 migration to schema v2; current entities are listed in §9.3)
- DAOs for all entities with reactive Flow queries
- Validation and clamping logic matching Python reference exactly

All unit tests pass (43 tests). Build and lint successful.

---

## Phase 4 Completion Status

**Phase 4: AnimePahe Search/Scanning/Parsing — COMPLETED**

Implemented components:
- `AnimePaheParser`: URL parsing (extract session_id, page, category), search result HTML parsing (extract session_id, label, title), episode page JSON parsing (extract episode data with audio detection), title extraction from HTML `<title>` tag, anime title extraction from API JSON, title normalization (strip " (Sub)", " (Dub)" prefixes)
- `EpisodeRangeParser`: Parses episode ranges (1-12, 1,4,7, 13-, 1,5,10-15, fractional episodes), non-interactive episode selection matching Python semantics
- `HttpClient` interface + `StubHttpClient` for testing
- `AnimeRepository`: Anime search with host rotation, title fetching from HTML, variant discovery with API title fallback, episode page fetching with pagination
- `EpisodeRepository`: Full scan flow with cache integration, variant discovery, pagination, episode deduplication (by number+audio), sorting (number then audio: eng < jpn), scan result caching with TTL
- `ScanCacheRepository`: TTL-based cache using Room database with kotlinx.serialization for JSON serialization of episode lists
- `FakeHttpClient`: Deterministic ordered-response fake supporting multiple responses per URL pattern (for testing paginated flows)

**Key decisions for Phase 4:**
- Use `kotlinx.serialization.json` instead of `org.json` (Decision 21) — `org.json` throws "Method not mocked" in JVM unit tests
- Title fallback from API response (Decision 22) — API's `"anime"` field used when HTML title extraction fails
- FakeHttpClient pattern for testing (Decision 23) — No Mockito dependency needed

**Test coverage:** 70 new unit tests (27 parser tests, 17 range parser tests, 8 AnimeRepository tests, 9 EpisodeRepository tests, 8 ScanCacheRepository tests). Total: 113 tests.

**Build verification:** assembleDebug SUCCESS, testDebugUnitTest SUCCESS, lint SUCCESS (no errors).

---

## Phase 5 Implementation Status

**Phase 5: WebView/Session/Cookie Handling — COMPLETED**

Implemented components:
- `CloudflareSessionProvider` interface — Domain-layer interface for browser session mechanism (no Android types exposed)
- `SessionPersistence` interface — Domain-layer persistence contract (safe metadata only, no raw cookies)
- `WebViewRequestBridge` interface — Domain-layer request bridge for Cloudflare-protected operations
- `CloudflareDetector` utility — Detects Cloudflare challenge responses in HTTP responses
- `AndroidCloudflareSession` — Android WebView implementation with Cloudflare detection, session lifecycle, Mutex synchronization
- `AndroidSessionPersistence` — SharedPreferences-based session metadata persistence
- `AndroidWebViewRequestBridge` — Bridges WebViewRequestBridge to CloudflareSessionProvider
- `WebViewHttpClient` — Bridges HttpClient interface to WebView session

**Key decisions for Phase 5:**
- CloudflareSessionProvider interface does NOT expose Android types to domain layer (clean architecture)
- Session persistence stores metadata only — NOT raw cookies (TLS-bound, cannot be transferred to OkHttp)
- CloudflareDetector recognizes HTTP 403 + cf-mitigated: challenge + Server: cloudflare
- WebViewHttpClient bridges existing HttpClient interface so AnimeRepository/EpisodeRepository work unchanged

**Test coverage:** 58 new unit tests (17 CloudflareDetector, 17 CloudflareSessionProvider, 10 SessionPersistence, 14 WebViewRequestBridge). Total: 177 tests.

**Build verification:** assembleDebug SUCCESS, testDebugUnitTest SUCCESS (177 tests), lint SUCCESS.

---

## Phase 6 Implementation Status

**Phase 6: Stream Resolution — COMPLETED + CORRECTED (Phase 6.1)**

Implemented components:
- `PlayPageParser` — Parses AnimePahe play page HTML to extract resolution buttons, Kwik URLs, audio tracks, and fansub information
- `QualitySelector` — Quality selection logic matching Python kwik.py semantics (build quality map, select best resolution); it now receives entries already exact-audio-filtered by `AudioSelector` (audio slice, DECISIONS 142)
- `AudioSelector` — exact open-set audio matching (requested `audio` == entry `data-audio`, no filter-fallback, explicit `unavailableMessage` failure); the SUB/DUB pair is only a display mapping (`StringUtils.audioBadge`) — a deliberate divergence from Python's closed `is_dub` behavior (DECISIONS 142)
- `JsPacker` — JavaScript unpacking for eval-based packer (p,a,c,k,e,d) used by Kwik
- `M3u8Extractor` — M3U8 URL extraction from HTML/JavaScript (direct match, script unpacking, source tag fallback)
- `KwikResolver` — Kwik URL resolution with domain rotation (KWIK_TLDS: cx, gg, si, me, net, in, cc)
- `StreamResolverImpl` — Main orchestrator integrating play page fetch, quality/audio selection, and Kwik resolution
- `StreamResolver` interface — Domain-layer stream resolution contract
- `ResolutionEntry` model — Parsed resolution button data (`resolution`, `kwikUrl`, `audio`, `fansub` — `audio` is the source's raw `data-audio` string, preserved open-set; parse-time dedup is first-wins on `(audio, resolution, kwikUrl)`)

**Phase 6.1 Corrections:**
- Removed dead `DIRECT_URL_PATTERN` from StreamResolverImpl (M3u8Extractor already covers this)
- Fixed FlareSolverr error message to Android/WebView-appropriate text ("Failed to fetch episode page via WebView session")
- Added `decodeJsonString()` to AndroidCloudflareSession for proper Unicode escape decoding (`\u003C` → `<`, etc.)
- Fixed WebView HTML content capture to occur in `onPageFinished` before Cloudflare redirects
- Added 3 realistic M3u8Extractor test fixtures (Kwik HTML, packed script, direct URL)

**Key decisions for Phase 6:**
- Decision 29: Kwik resolution uses WebView session (CloudflareSessionProvider) instead of curl_cffi/FlareSolverr
- Domain rotation preserved from Python reference for resilience
- OkHttp reserved for non-Cloudflare endpoints (HLS segments, AES keys) per Decision 26
- DIRECT_URL_PATTERN removed because M3u8Extractor.extractM3u8() already covers direct URL matching as Strategy 1
- Cookies in StreamInfo remain empty list — cookie/session management is handled by the WebView session, not passed through StreamInfo (shipped as an empty list per **Decision 32**, not a pending dependency: `StreamInfo.cookies` has no consumer, and the session cookie is applied inside the WebView during resolution)

**Test coverage:** 73 new unit tests (16 PlayPageParser, 12 QualitySelector, 7 AudioSelector, 10 JsPacker, 14 M3u8Extractor, 6 KwikResolver, 8 StreamResolverImpl). Total: 250 tests.

**Build verification:** assembleDebug SUCCESS, testDebugUnitTest SUCCESS (250 tests), lint SUCCESS.

**Real-Device Validation (Phase 6.1):**
- ✅ Session established on Android 16 (5-8 cookies, Cloudflare completed)
- ✅ AnimePahe play page fetched via WebView (22-24K chars HTML)
- ✅ Resolution buttons parsed (360p/720p/1080p SUB found)
- ✅ Quality selection (720p SUB selected correctly)
- ✅ Real Kwik URL obtained (kwik.cx/e/...)
- ❌ (Phase 6.1) Kwik → M3U8: BLOCKED — later re-diagnosed as a **missing `Referer` header**, not broken domain infrastructure (LIMITATIONS §2B, Decision 72)
- ✅ (Phase 11.5E-4, production APK on device) Kwik page fetched with `Referer: https://animepahe.com/` → `Kwik request referrer present: true` → `Kwik page fetch success=true, htmlLen=22274` → `Cloudflare challenge completed (cycle 1)` → **M3U8 extracted** (`https://vault-13.owocdn.top/…/uwu.m3u8`) → 146/146 segments downloaded

---

## 1. Core Features

### 1.1 Search System

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Anime search | `scanner.py:search()` | `AnimeRepository.search()` | 🔄 | FlareSolverr → WebView |
| Host rotation | `scanner.py:60` | `AnimeRepository` | 🔄 | Same logic, different HTTP |
| Result parsing | `scanner.py:search()` | `AnimePaheParser.parseSearchResults()` | ✅ | JSON parsing identical |
| No pagination | — | — | ✅ | Single response |

### 1.2 Anime Metadata

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Title fetch | `scanner.py:_fetch_title()` | `AnimeRepository.getTitle()` | 🔄 | HTML parsing |
| Episode list | `scanner.py:_fetch_page()` | `EpisodeRepository.getEpisodes()` | 🔄 | API pagination |
| Variant discovery | `scanner.py:discover_all_sessions()` | `AnimeRepository.discoverVariants()` | 🔄 | Search + match |
| Episode parsing | `scanner.py:_parse_episode_page()` | `AnimePaheParser.parseEpisodePage()` | ✅ | JSON parsing |

### 1.3 Episode Discovery

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Scan caching | `scanner.py:_cache_path()` | `ScanCacheRepository.saveScanCache()` | ✅ | Room (replaces Python file cache) |
| TTL expiry | `scanner.py:_load_cache()` | `ScanCacheRepository.getCachedScan()` (TTL check) | ✅ | Same logic |
| Glob-stable lookup | `scanner.py:scan()` | `ScanCacheRepository.getCachedScan(session, …)` | ✅ | Same pattern (lookup by session) |
| Incremental scan | `scanner.py:scan()` | `EpisodeRepository.scan()` | ✅ | Same logic |

### 1.4 SUB/DUB Handling

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Audio detection | `scanner.py:_parse_episode_page()` | `AnimePaheParser.parseEpisodePage()` (audio detection) | ✅ | Same logic |
| Title-based detection | `scanner.py:_parse_episode_page()` | `AnimePaheParser.parseEpisodePage()` (title-based detection) | ✅ | Same logic |
| Variant selection | `models.py:get_variant()` | `AnimeInfo.getVariant()` (strict exact match) + `AnimeInfo.defaultVariant()` (`jpn`-else-first — the SPEC-sanctioned neutral bulk default, used only where a bulk action needs a pre-confirmation value) | ✅ | Direct port for `getVariant`; the strict per-number audio routing is the audio-slice model (DECISIONS 142) |
| Audio selection at resolve | `downloader.py:resolver()` (requested audio → silent fallback to another variant) | `StreamResolverImpl.resolve()` via `AudioSelector.select()` — exact open-set match with **no fallback**, and `AudioSelector.unavailableMessage()` for an unsatisfiable request | ⚠️ | Deliberate divergence (DECISIONS 142): Python falls back to a different variant, Android never substitutes audio anywhere (SPEC `SPEC.md:920-923`) |

**Language behavior verified in Phase 15 (2026-10-04) — no reproducible language-selection defect was found.** Preferred audio selects an available release **variant at resolve time**; supported values are `jpn` and `eng` only, matching the reference (`main.py:84 --audio choices=["jpn","eng"]`, `config_manager.py:107` validates `v in ("jpn","eng")`; Android `AppConfig.kt:372-374`). **The setting does not add languages that are absent from upstream episode data.** Established through the app's real network path on physical device `REDACTED-SERIAL`: *Tomb Raider King* search returned one entry, details fetched exactly one session, and the episode list read `eng` for eps 1–11 and `jpn` for ep 12 — so episode 1 resolved to `eng` because `eng` is the only release upstream publishes for it (12 releases / 12 episodes). `discover_all_sessions` is ported faithfully (`scanner.py:131-153` ↔ `AnimeRepository.discoverVariantsWithTitle` + `EpisodeRepository` with the identical `(number, audio)` dedupe), and both references issue the same single non-paginated search, so Python would discover the same single session. Multi-language selection beyond `jpn`/`eng` is out of parity scope (0 matches for `kor`/`korean` anywhere in `pahebatcher-3.7.0/src`). **Superseded in part by the 2026-10-08 audio slice (SPEC §22/§23) and Decision 144:** there is no global Preferred-audio setting anymore — `main.py:84 --audio` / `config_manager.py:107` still exist in Python, but Android now selects audio **per episode** (number → audio mapping; DECISIONS 142), and audio values are open-set rather than restricted to `jpn`/`eng`. The observation that upstream publishes at most one audio release per episode (for the titles exercised live) still stands, and the `discover_all_sessions` port note above is unchanged. **Option-set source (DECISIONS 144, implemented and verified 2026-10-10):** the screen's audio selector is populated from a bounded play-page probe — the first-seen union of `data-audio` strings over at most `merged sessions + 1` sampled play pages, persisted inside the EXISTING scan-cache row (`SPEC.md:876-890`) — and falls back to release-listing audio only when the probe yields nothing; the chain is `EpisodeRepository.audioProbeTargets()` → `AudioOptionRepository.ensureProbed()` → `AnimeDetailsViewModel.audioOptions`, with one row per episode NUMBER carrying the requested audio (`variantOrCarrier`) and the listing-only batch/Watchlist gates removed where the number exists. That is **representative sampling, NOT a complete per-episode audio inventory** (LIMITATIONS §43.1), and it was **VERIFIED (test)** only — no live probe (LIMITATIONS §43.4).

---

## 2. Stream Resolution

### 2.1 Play Page Resolution

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Play page fetch | `kwik.py:extract_stream()` | `StreamResolver.fetchPlayPage()` | 🔄 | FlareSolverr → WebView |
| Resolution menu parse | `kwik.py:_parse_resolution_buttons()` | `PlayPageParser.parseResolutionMenuEntries()` | ✅ | Same regexes; menu div only (Python returns `[]` when absent) |
| Quality selection | `kwik.py:extract_stream()` | `QualitySelector.select()` (invoked from `StreamResolverImpl.resolve()`) | ✅ | Quality rule unchanged; it now runs over entries already exact-audio-filtered by `AudioSelector` (SPEC §22) |
| Menu-less fallback selection | `kwik.py:241-247` | `QualitySelector.selectFallback()` + the exact-audio filter in `StreamResolverImpl`/`AudioSelector` | ⚠️ | 11.5E-4: `setdefault` map + quality rule only, **no audio rule** (was audio-filtered). Audio-slice update (D3, DECISIONS 142): menu-less entries keep the URL-label audio heuristic, but the resolver still demands an exact match for the requested audio — Python would accept any audio there |
| Text resolution from button text | `kwik.py:196` (`re.match`) | `PlayPageParser.extractResolution()` (`^(\d+)\s*p`) | ✅ | 11.5E-4: anchored at text start (was an unanchored `find`) |
| Fansub fallback text | `kwik.py:209` (`split("·")[0]`) | `PlayPageParser.extractFansub()` | ✅ | 11.5E-4: split head = whole text when there is no `·` (was `""`) |
| Audio selection | `kwik.py:extract_stream()` | `AudioSelector` (invoked from `StreamResolverImpl.resolve()`) | ⚠️ | Same invocation point, different contract (DECISIONS 142): Android matches the requested audio against the entry's **open-set** `data-audio` string exactly (`jpn`/`eng`/anything), with no fallback and an explicit `unavailableMessage` failure when nothing matches (D4: zero sources is also an explicit failure, never a silent pick). Python's request values are closed (`jpn`/`eng`) and `downloader.py` substitutes another variant when the request misses |

### 2.2 Kwik Resolution

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| curl_cffi direct | `kwik.py:_sync_curl_fetch()` | N/A — no direct-fetch path implemented (WebView-only resolution) | ⚠️ | Android resolves through the WebView session; `http.py`'s 403→curl fallback has no equivalent |
| Kwik request Referer | `kwik.py:_HEADERS["Referer"]` | `KwikResolver.KWIK_REQUEST_HEADERS` → `CloudflareSessionProvider.fetchPage(..., extraHeaders)` | ✅ | 11.5E-4: `Referer: https://animepahe.com/` on Kwik requests, **and on no other request** (play-page fetch passes an empty map) |
| Playlist / segment referrer | `kwik.py:132` (`referer=url`), `:143` (`referer=alt`) | `StreamInfo.referer = KwikResolution.sourceUrl` | ✅ | 11.5E-4: the KWIK page that produced the playlist — the CDN answers 403 for the play page or `animepahe.com` |
| Domain rotation | `kwik.py:_resolve_kwik()` | `KwikResolver.resolve()` TLD loop (private `swapDomain()`) | ✅ | Same TLDs (KWIK_TLDS) |
| Rotation drops query string | `kwik.py:85-90` | `KwikResolver.swapDomain()` | ✅ | 11.5E-4: only `scheme://host + path` is re-emitted (a stale `?token=` is never replayed); **11.5F re-verified**: `swapDomain`/`KwikValidator.swapTld` touch only the host and preserve the path byte-for-byte |
| Kwik URL selection order + verbatim use (invariant) | `kwik.py:176-255` — `#resolutionMenu` `data-src` (`:188`) → `(?:href\|data-src)…(\d+)p` fallback (`:242`) → last-resort `data-src["']?(https?://<KWIK_DOMAINS>/[ef]/\w+)` (`:255`); chosen URL is then used **as-is** | `PlayPageParser.parseResolutionMenuEntries` (`:42`), fallback patterns, and last-resort regex with the same `[ef]` class (`PlayPageParser.kt:125`); `StreamResolverImpl.kt:97-100` passes `selectedEntry.kwikUrl ?: extractFallbackKwikUrl(html)` unchanged into `kwikResolver.resolve()` | ✅ | **11.5F audit**: `grep` across `app/src/main` finds exactly one `/[ef]/` occurrence — the mirrored regex itself; **no code rewrites `/e`↔`/f` or any other path segment** (the only URL mutation in the Kwik path is the TLD swap above). `referer = resolution.sourceUrl` (`StreamResolverImpl.kt:160`) is that exact page URL. Covered by `PlayPageParserTest`, `StreamResolverImplTest` (asserts `lastResolvedUrl`/`referer` equal to the input `data-src` URL), `KwikResolverTest` |
| FlareSolverr fallback | `kwik.py:_resolve_kwik()` | `KwikResolver.fetchViaWebView()` | 🔄 | WebView session (validated) |
| M3U8 extraction | `kwik.py:_extract_m3u8()` | `M3u8Extractor` | ✅ | Same regexes; verified against the real captured page (11.5E-4 fixture, byte-identical result) |
| `unicode_escape` decode | `kwik.py` (`.decode("unicode_escape")`) | `M3u8Extractor.decodeUnicodeEscape()` | ✅ | 11.5E-4: full escape table; deviations documented in Decision 72 (`\/` unescape, String-vs-bytes input) |

### 2.3 JS Unpacking

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| JsPacker.unpack() | `kwik.py:JsPacker` | `JsPacker.unpack()` | ✅ | Direct port |
| Eval unwrapping | `kwik.py:_extract_m3u8()` | `M3u8Extractor` | ✅ | Same logic |
| Source tag fallback | `kwik.py:_extract_m3u8()` | `M3u8Extractor` | ✅ | Same regex |

---

## 3. HLS Parsing

### 3.1 M3U8 Parsing

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Master playlist | `m3u8.py:parse_m3u8()` | `HlsPlaylistParser.parse()` | ✅ | Direct port, handles master+media |
| Media playlist | `m3u8.py:parse_m3u8()` | `HlsPlaylistParser.parse()` | ✅ | Direct port |
| AES key extraction | `m3u8.py:parse_m3u8()` | `HlsPlaylistParser.parseKeyAttribute()` | ✅ | Direct port |
| IV handling | `m3u8.py:parse_m3u8()` | `HlsPlaylistParser.parseKeyAttribute()` | ⚠️ | 128-bit, 0x prefix, padding — deliberate fix of Python's `lstrip`-style bug (see §4.1 IV row); documented deviation, not an identical port |
| URL resolution | `m3u8.py:urljoin()` | `HlsPlaylistParser.resolveUrl()` | ✅ | Direct port |

### 3.2 Quality Selection

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Last variant follow | `m3u8.py:resolve_m3u8()` | `EpisodeDownloader.downloadOnce()` | ✅ | Constraint 6: variants.last() |
| No explicit selection | — | — | ✅ | Server provides order |

### 3.3 Audio Track Selection

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Pre-selection | `kwik.py:extract_stream()` | `StreamResolver` | ✅ | Same logic |
| Not in M3U8 | — | — | ✅ | Single-audio streams |

---

## 4. Decryption

### 4.1 AES-128 Decryption

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Key fetching | `downloader.py:run()` | `EpisodeDownloader.fetchKeyWithRetry()` | ✅ | OkHttp via HlsClient, 5 retries |
| Key caching | `downloader.py:_aes_key_cache` | `AesKeyCache` | ✅ | Same TTL (3600s), maxSize=128 |
| IV calculation | `downloader.py:run()` | `HlsPlaylistParser.parseKeyAttribute()` | ⚠️ | 128-bit, 0x prefix, padding — same deliberate `lstrip`-bug fix as §3.1; the two IV rows now carry identical status and note (11.5E-6 reconciliation) |
| Decryption | `downloader.py:run()` (`cipher.decrypt(raw)`, raw CBC, no unpadding) | `Aes128Decryptor.decrypt()` (`AES/CBC/NoPadding`, raw CBC, no unpadding; output length equals input length) | ✅ | FIXED 2026-10-13 - the previous `AES/CBC/PKCS5Padding` delegated unpadding to the JCE provider, which both stripped the trailing 1-16 pad bytes Python keeps on disk and rejected non-PKCS7 payloads Python accepts. Closed by the final parity cycle; pre-fix text and verification are retained in the RESOLVED block at the end of this file. (status glyph re-encoded 2026-10-19, Phase A2 doc hygiene) |
| No-decrypt path | `downloader.py:run()` | `EpisodeDownloader.downloadOnce()` | ✅ | Pass-through if no key |

---

## 5. Downloading

### 5.1 Two-Stage Pipeline

> **Production wiring status (updated by Phase 11.5E-4):** the resolver classes (`StreamResolverImpl`, `KwikResolver`, `M3u8Extractor`), the downloader (`EpisodeDownloader`), and the background orchestration (`DownloadRepository`/`DownloadWorker`) all exist and are exercised by unit/instrumentation tests (including `FullDownloadFlowTest`, which uses an in-process fake HlsClient + on-device FFmpegKit-generated TS). Production now HAS a caller for the whole chain: Home scans → Anime Details scans → `AnimeDetailsViewModel` resolves the selected episode through `StreamResolverImpl` → `EpisodeDownloadQueue.queueResolvedEpisode()` (implemented by `DownloadRepository`) persists the session/episode/stream rows → unique `DownloadWorker` work → `EpisodeDownloader`. `DetailsHandoffTest` proves the hand-off against the real Room database and WorkManager on a device. **Phase 11.5E-4 then verified the whole chain live on device (production APK, real network):** `Resolving stream … (quality=1080, audio=eng)` → Kwik page fetched with the Referer header → `M3U8 extraction result` → `Stream resolved` → `Enqueued episode …_1_eng` → `DownloadWorker: Starting download work` → `EpisodeDownloader: Starting download` → `AesKeyCache: Cached key` → `Playlist: 146 segments` → `Download complete: success=true, total=146, completed=146, failed=0` with 146 atomic `.ts` writes (`tmp=0`), and a second pass reporting `146 completed, 0 pending` (resume). The chain then completes at MP4 assembly since **Phase 11.5E-5** (previously it terminated in `FAILED` — `Missing 1 segments before assembly`, an index-base mismatch resolved by the `segmentIndices` boundary translation): `Starting MP4 assembly for 146 segments` → `Assembly succeeded via concat demuxer` → `Ep_001_-_Spy_x_Family_Ep._1_animepahe.mp4` → exported to `content://media/external/video/media/116` → Worker SUCCESS (LIMITATIONS §26 **RESOLVED**).

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Resolver stage | `downloader.py:resolver()` | `StreamResolverImpl` (invoked from `AnimeDetailsViewModel.startResolution()`) | ✅ | WebView-based; production caller exists since 11.5E-3 |
| Resolved stream → queue | `downloader.py` (resolver result → `download_queue`) | `EpisodeDownloadQueue.queueResolvedEpisode()` → `DownloadRepository` | ✅ | 11.5E-4: parent session row → episode row → `resolveComplete(m3u8Url, userAgent, referer)` → unique work; enforced by foreign key (Room `PRAGMA foreign_keys = ON`). **11.5E-6 submission guard**: re-submission of a live (`DOWNLOADING`/`ASSEMBLING`) or valid `COMPLETED` row is suppressed before any write (KDoc idempotency contract / §17.1 / Python `downloader.py:60-62` skip semantics), while `QUEUED`/`RESOLVING` zombie recovery, `FAILED`/`CANCELLED`, and `COMPLETED`-without-output still re-queue. **Device-verified 11.5E-6**: fresh enqueue and CANCELLED re-submission proceed (`Enqueued … Worker enqueued` → download resumed to `completed · 146/146`), COMPLETED re-submission logs `Suppressed re-submission` and the row stays `completed · 146/146 · Play`; `QueueResolvedEpisodeGuardTest` 8/8 on device. **Slice B (DECISIONS 146)**: the seam now RETURNS `EnqueueOutcome` (`Accepted` / `AlreadyActive` / `AlreadyDownloaded` / `OutputMissing` / `AlreadyPaused` / `Refused`) instead of `Unit`, so a request the pipeline refused is no longer reported as a fresh enqueue; **closure (DECISIONS 146.10)**: a hand-off lost to a concurrent Cancel/failure/removal reports `Refused` — "nothing was queued and nobody owns the episode" — instead of `AlreadyActive`, whose own contract promised a row the pipeline owns; the terminal claim is a single guarded `UPDATE … WHERE state = :expectedState`, deliberately NOT `@Transaction`ed and deliberately terminal-only, so `reEnqueue`'s pre-claimed `RESOLVING` and the stream-less `QUEUED` zombie repair (H-1) still proceed exactly as before; completed-output verification moved into the ONE shared `EpisodeOutputVerifier` rule that `DownloadStateReconciler` also uses — a persisted `outputUri` is no longer taken on trust, it must resolve through the injected `ContentResolver` and otherwise the `outputPath` decides; `createRow` claims a new row with `INSERT OR IGNORE` straight into `RESOLVING`, so concurrent first submissions create the row exactly once and only the winner reports `Accepted`; and `ensureSession` switched from `upsert` to `insertIfAbsent` + `update`, because `REPLACE` fires `ON DELETE CASCADE` and would have wiped a sibling episode's row. Failed/paused/downloaded/paused-by-user refusals are distinct from each other, and the stream-less `QUEUED` zombie (LIMITATIONS §24 finding 2 escape (a)) is now explicitly reported as `Accepted` rather than as already-queued. Evidence: `EnqueueOutcomeMappingTest` 8/8, `QueueResolvedEpisodeGuardTest` 10/10 and `RetryReResolutionTest` 16/16 (device) |
| Download stage | `downloader.py:download_worker()` | `EpisodeDownloader.downloadOnce()` | ✅ | HlsClient (OkHttp); reached in production through the hand-off above (`DetailsHandoffTest`) |
| Existing-output skip | `downloader.py:60-62` — `run()` returns before fetching the playlist when the output exists with `st_size > 0` | `EpisodeDownloader.download()` pre-check (top of `download()`, before the attempt loop) | ✅ | **11.5E-6**: non-empty existing output short-circuits with `reusedExistingOutput = true` and zero network I/O; `DownloadWorker` then skips `forceFinal` so persisted progress is not overwritten with 0. JVM-verified: `existing non-empty output short-circuits before any network request` (asserts `requestedUrls.isEmpty()`), `empty existing output is ignored and the download runs normally` (zero-byte file re-runs — Python's `st_size > 0` rule) |
| Audio label persisted on the episode row | `downloader.py:277` (`ep.audio = info.audio` after a successful resolution — an in-memory mutation used by the results table) | `DownloadRepository.queueResolvedEpisode` persists `audio = episode.audio`, i.e. the **requested** variant label | ⚠️ display-only | The requested label is part of the row's **identity** (`episodeId = {animeSession}_{epStr}_{audio}`), and `reEnqueue` re-derives that key from the row's own columns and refuses the retry if it does not round-trip (`DownloadRepository.kt:929-945`) — so swapping it for the resolved label would break Retry. Python has no such key and mutates its object instead. Nothing is lost about the *resolved* audio: `StreamResolverImpl` returns it (`StreamInfo.audio`, set from `AudioSelector.select()`), the resolution panel shows it (`AnimeDetailsScreen` renders `stream.audio` — gate-verified live: panel `eng · Yameii`), and the download itself is byte-identical either way. Because the audio slice filters to the EXACT requested audio, requested == resolved by construction whenever resolution succeeds (gate logcat: request `audio=eng` → resolved `audio=eng`). LIMITATIONS §33.8 |
| resolve_ahead | `downloader.py:resolve_ahead` (`download_queue = asyncio.Queue(maxsize=resolve_ahead + max_parallel)`, `downloader.py:205-208`) | `AppConfig.updateResolveAhead()` → `AnimeDetailsViewModel.configuredResolveAhead()` / `WatchlistChecker.resolveAheadCapacity()` → **`ResolveAheadGate.awaitCapacity`** (Phase 12C) | ✅ | **Consumed since Phase 12C**: before each episode of a batch (or of a watchlist auto-download) is resolved, the gate waits while `resolvedAheadCount() >= resolve_ahead + max_parallel`, i.e. it lets fewer than the queue's `maxsize` (`resolve_ahead + max_parallel`) RESOLVED-but-still-QUEUED rows stand in front of the downloaders (`EpisodeDownloadDao.countResolvedPending()`), so the key really bounds how far stream resolution runs ahead of download capacity — the same quantity Python's queue maxsize bounds. Settings row added (0/1/2/4/8/Unlimited, default 999). **Still no resolver-prefetch *pipeline***: Android resolution goes through the shared application-scoped WebView rather than a plain HTTP client — the same transport whether the screen is up or not (the narrow 12D amendment for the check Worker is DECISIONS 91) — so for a batch started from the details screen the gate bounds *how far ahead of capacity* it resolves while the screen is up, not *how much it does in the background*; a background check stages through that same gate (LIMITATIONS §33.2/§33.3) |

### 5.2 Segment Download

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Concurrent segments | `downloader.py:fetch_one()` | `EpisodeDownloader.downloadOnce()` | ✅ | Coroutines + Semaphore |
| Semaphore control | `downloader.py:seg_sem` | `Semaphore(hlsWorkers)` | ✅ | Same logic (default 24) |
| Error collection | `downloader.py:results` | `mutableListOf<String>()` | ✅ | Same pattern |
| Progress tracking | `downloader.py:dash.seg_done()` | `onProgress` callback | ✅ | Same metrics |
| Live segment download (device) | — | `EpisodeDownloader` + `SegmentFileStore` | ✅ | Phase 11.5E-4: real CDN playlist → `total=146, completed=146, failed=0`, 146 atomic writes, `tmp=0`; resume reported `146 completed, 0 pending` |
| Segment **file index** base | `downloader.py:77` — `enumerate(segments)` (0-based position) | `HlsPlaylistParser` sets `HlsSegment.index` = `#EXT-X-MEDIA-SEQUENCE`, `writeSeg(segment.index, …)` | 🔄 | Same behavior, different key: Android files are keyed by the playlist's media sequence (`000001.ts…00146.ts`) instead of enumerate position — a deliberate Phase 8 identity that `doneIndices()`/resume depend on. Every consumer now agrees because `EpisodeDownloader` hands assembly the playlist's own indices via `AssemblyConfig.segmentIndices` (Phase 11.5E-5 boundary translation). LIMITATIONS §26 **RESOLVED** |

### 5.3 Retry Logic

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| HTTP retry | `http.py:HttpClient.get()` (`http.py:89-108`; no `retry_fetch` exists in Python — citation corrected 11.5E-6) | `EpisodeDownloader.fetchM3u8WithRetry()` | ✅ | 5 attempts, 0.5s base |
| Episode retry | `downloader.py:run()` | `EpisodeDownloader.download()` | ✅ | autoRetry+1 attempts, 1.5s base |
| Exponential backoff (HTTP) | `http.py:0.5 * (2**attempt)` | `RETRY_BASE_DELAY_MS * (1L shl attempt)` | ✅ | Same formula |
| Exponential backoff (episode) | `downloader.py:1.5 * (2**attempt)` | `EPISODE_RETRY_BASE_DELAY_MS * (1L shl attempt)` | ✅ | Same formula |
| Permanent error skip | `downloader.py:130` — `is_permanent = "No Kwik link" in msg or "No segments" in msg` | `EpisodeDownloader.download()` classifies "No segments" **plus assembly failure and HTTP 403/404** as permanent | ⚠️ | "No segments" matches Python exactly (the "No Kwik link" arm has no Android equivalent — resolution is a separate stage). **Divergence (reviewer F5, verified against source)**: Python *retries* an assembly failure (`downloader.py:117-119` raises `RuntimeError("Assembly failed")`, which is not permanent → retried up to `max_attempts` with 1.5 s backoff), while Android treats it as permanent (`EpisodeDownloader.kt:195-199` — *line citation corrected 2026-09-28, reviewer L-3; the previous `:517` citation pointed at `val assemblyConfig = …`, not the classification*; `result.assemblyError != null` is the assembly arm, deterministic FFmpeg errors); 403/404 permanence is the next row. Code change excluded from 11.5E-5 (queue/retry redesign — DECISIONS 73) |
| 403 handling | `http.py:99-101` (`curl_cffi` fallback **inside** the attempt) | `EpisodeDownloader` classifies HTTP 403/404 as **permanent** and does not retry (Phase 10 arch v10 §3) | ⚠️ | No impersonating fallback exists on Android, and a re-resolve would need the foreground WebView. Consequence recorded in LIMITATIONS: an expired stream URL is terminal for that run. **404 divergence (11.5E-6, verified)**: Python does *not* treat 404 as permanent — only 403 short-circuits to the curl fallback, every other failure including 404 is retried until the last attempt (`http.py:89-108`, `raise_for_status()` → lines 102-104), while Android classifies 404 permanent too (`EpisodeDownloader.kt:818,856,888`); code change deferred with the §24 retry work |

### 5.4 Concurrency Control

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Segment parallelism | `downloader.py:hls_workers` | `EpisodeDownloader.hlsWorkers` | ✅ | Same range (default 24) |
| Connection pooling | `http.py:TCPConnector` (one shared `ClientSession`, `limit_per_host=24`) | ONE app-scoped host-keyed `Semaphore(hlsWorkers)` shared by every Worker (`HlsHostConcurrencyLimiter.kt:69`); the OkHttp client/pool was **not** shared pre-A2.1 — each `DownloadWorker` built its own `OkHttpHlsClient` (`DownloadWorkerFactory.kt:113` fallback); since A2.1 Decision 5 ONE app-scoped client (`AppContainer.kt:371`) is injected through `DownloadWorkerFactory.kt:83`, so the pool is shared | ⚠️ | **CORRECTED 2026-10-19 (Phase A2, DATA-012)** — the previous row claimed OkHttp connection-pooling parity; that was an overclaim. Shared: the per-host in-flight bound (semaphore). Not shared: the `OkHttp` connection pool — every worker constructs its own `OkHttpClient`, so no HTTP/2 connection reuse spans concurrent episodes and sockets/threads multiply. Python's one shared `ClientSession` with `limit_per_host=24` covers both properties. The code fix (one app-scoped injected client) is deferred as a resource/lifetime product decision (DECISIONS 159.5); `OkHttp`'s `Dispatcher.maxRequestsPerHost` cannot be the mechanism because it does not gate blocking `Call.execute()`. The 2026-10-13 semaphore fix stands as written and is retained in the RESOLVED block at the end of this file. SUPERSEDED 2026-10-20 (A2.1 Decision 5): the deferral above is closed — the one app-scoped `OkHttpHlsClient`/connection pool now exists (`AppContainer.kt:371`, factory `DownloadWorkerFactory.kt:76`, limiter `HlsHostConcurrencyLimiter.kt:69`), so both halves of the parity hold. |
| Keepalive | `http.py:keepalive_timeout=45` | `OkHttp` default | ✅ | OkHttp handles internally |

---

## 6. Segment Store

### 6.1 Atomic Writes

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Write to .tmp | `store.py:write_seg()` | `SegmentFileStore.writeSeg()` | ✅ | Atomic .tmp → .ts |
| Rename to .ts | `store.py:write_seg()` | `SegmentFileStore.writeSeg()` | ✅ | Atomic rename |
| Done detection | `store.py:done_indices()` | `SegmentFileStore.doneIndices()` | ✅ | Same glob pattern |
| Missing detection | `downloader.py:pending` | `EpisodeDownloader.downloadOnce()` | ✅ | Same logic |
| Caller-supplied dir | — | `SegmentFileStore(directory: File)` | ✅ | No hardcoded cacheDir |
| Existence check index base | `store.py:47` — `[i for i in range(n_segments) if not self.seg_path(i).exists()]` — checks exactly the files the assembly will consume | `FfmpegEpisodeAssembler.assemble()` — `config.segmentIndices.filter { !segmentStore.hasSeg(it) }` | ✅ | Same invariant (verify precisely the files FFmpeg will read; error text `Missing N segments before assembly` preserved). The index list comes from the playlist (`segmentIndices`, Phase 11.5E-5) instead of `range(n)` because Android files are media-sequence keyed (§5.2); an index-list/count mismatch is rejected up front (`Assembly config mismatch: …`). Verified on JVM + device: a positional config honestly reports `Missing 1`, the playlist config assembles. LIMITATIONS §26 **RESOLVED** |

### 6.2 Assembly

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Concat demuxer | `store.py:_assemble_concat()` (`store.py:199`) | `FfmpegEpisodeAssembler` (**fallback**, Phase 11.5H) | ✅ | **Parity since Python 3.6.1 (Phase 12C re-audit):** the reference now runs `_assemble_pipe` first and the concat demuxer *only* when the pipe path fails (`store.py:191-201`), which is exactly Android's order — DECISIONS 80's "deliberate order divergence" is retired. Command shape still byte-identical (`-f concat -safe 0 … -c copy -f mp4 -movflags +faststart`); Android keeps it as the fallback branch (`ConcatFileGenerator` created only when used) |
| Single contiguous MPEG-TS input | `store.py:_assemble_pipe()` (`store.py:191-197`, Python's **primary** since 3.6.1) | `FfmpegEpisodeAssembler.executePipeFallback()` (**primary**, Phase 11.5H) | ✅ | Same order on both sides now. Android keeps a temp `_pipe_input.tmp` instead of feeding stdin (Decision 79) so no argv-contract test changed; it measures **0 / 0** holes and reproduces Python's `total_ticks` exactly (video 127,800,172, audio 62,631,937 on the Rearguard pair). Cost: a transient `_pipe_input.tmp` ≈ sum of segment bytes — LIMITATIONS §30.3 |
| AudioSpecificConfig declaration | `store.py:fix_aac_esds()` (`store.py:196,200,240-262`, added in Python 3.6.1) | `AudioSpecificConfigNormalizer` | ✅ | **Parity since Python 3.6.1 (Phase 12C re-audit):** both sides rewrite the same `esds` AudioSpecificConfig byte (AOT 1 → 2: `0b 88` → `13 88` on the Phase 11.5H pair, `0b 90` → `13 90` on the Phase 12C pair) *after* assembly, as metadata only, best-effort, never re-encoding — DECISIONS 81's "deliberate divergence" is retired and LIMITATIONS §30.1 is superseded. Same conditional (`objectTypeIndication == 0x40 && AOT == 1`), same tolerance for a mismatch (leave the file alone). Still required because the device's only AAC decoder (`c2.android.aac.decoder`) rejects AOT 1 with `0x2003` (LIMITATIONS §29); **Phase 12C media comparison** confirms both sides emitted identical ASC bytes `13 90` |
| faststart flag | `store.py:assemble()` | `FfmpegEpisodeAssembler` | ✅ | `-movflags +faststart` on both paths |
| Timeout | `store.py:timeout=600` | `FfmpegEpisodeAssembler` | ✅ | Configurable, default 600s matching Python |
| Assembly of a **real** playlist | `store.py:assemble()` | `FfmpegEpisodeAssembler` | ✅ | **Phase 11.5E-5 device-verified** (production APK, real 146-segment Kwik stream): `Starting MP4 assembly for 146 segments` → `Assembly succeeded via concat demuxer` → `Ep_001_-_Spy_x_Family_Ep._1_animepahe.mp4` (146 705 229 B) → exported to `content://media/external/video/media/116` → Worker SUCCESS. MP4 validated: `ftyp` first, `moov@32` before `mdat@1124176` (faststart), tracks `avc1`+`mp4a`, `mvhd` 1458.803 s (24.31 min), file size = MediaStore `_size`. LIMITATIONS §26 **RESOLVED** |

> **Phase 11.5F media validation (2026-09-28, read-only):** the two production outputs
> currently in `Movies/PaheBatcher/` on `REDACTED-SERIAL` (MediaStore user 10 ids **194**
> `Ep_001_-_Spy_Classroom_Ep._1_animepahe.mp4` = **118,001,664 B** and **195**
> `Ep_001_-_One_Piece_Ep._1_animepahe.mp4` = **237,934,196 B**) were pulled **byte-exact**
> (`content read --uri … --user 10` via `adb exec-out`; both lengths equal `_size`
> exactly) and probed with WSL ffprobe 8.0.1 (all outputs under
> `.investigation/media-11_5/`). Findings: (1) box layout **byte-for-byte the same shape
> as the Python reference** — `ftyp(32) → moov → free(8) → mdat` (faststart confirmed on
> all three files); (2) streams: id194 = h264 High@5.1, 1920×1080, 24000/1001, HE-AAC
> 48 kHz mono, duration **1626.775 s**, 580,297 bps; id195 = h264 High@3.1, 1920×1080,
> HE-AAC 44.1 kHz stereo, duration **1509.347166 s**, 1,261,123 bps; (3) **full decode of
> both files exits 0** (id194 emits only the AAC parametric-stereo advisory, which the
> Python reference outputs emit as well); (4) **playback CONFIRMED on the device**: VLC
> (`org.videolan.vlc`) renders both files, OSD total `25:09` = ffprobe 1509.35 s, frames
> advance, seek-to-≈24 min renders correctly (screenshots in
> `.investigation/media-11_5/`); (5) **honest pairing caveat**: no title-matched Python
> reference file exists on this machine — pairing is by nearest-duration Ep 1
> (`Welcome_to_Demon_School_Iruma-kun…Ep_001`, 1500.287506 s: Δ **9.06 s** vs id195,
> Δ **126.49 s** vs id194); (6) **timestamp-structure difference vs the Python corpus**
> recorded in **LIMITATIONS §28** (Android outputs: 161/151 seam discontinuities, grid
> holes 238/218, frames÷duration 1.00607–1.00614; Python outputs: 0 holes, exactly
> 1.000000) — same ffmpeg command on both sides, unattributed because no same-title pair
> exists; **not claimed as parity or as a defect** |

> **Phase 11.5G media/playback reconciliation (2026-09-28) — the two caveats above are now
> resolved, and one new limitation is recorded:**
>
> 1. **A genuine matched pair now exists** (same title/episode/fansub/quality — *not*
>    duration-nearest): `The World's Strongest Rearguard` Ep. 1, Android MediaStore
>    **id 220** vs `<python-ref-3.6.0>\downloads\…\Ep_001_…animepahe.mp4`, pulled byte-exact
>    to `.investigation/media-final/`. This retires caveat (5).
> 2. **§28 is ATTRIBUTED** (was "unattributed"): reconstruction controls show the seam
>    holes are *created by FFmpeg's multi-file MPEG-TS concat path* — 1 input → **0** holes;
>    a PAT-aligned byte-split of the same bytes into 142 files → **141** holes despite
>    provably contiguous, sample-count-identical input (`recon/bs2.mp4`); a `-f segment`
>    re-cut of the same bytes → **141** holes / **8.42 s** (`recon/reassembled.mp4`); the
>    plain packet-count split → **141** holes / 46.25 s (`recon/bytesplit.mp4`, which is *not*
>    PAT-aligned and loses 60 audio samples); production → **142** / 8.50 s. Every figure is
>    re-derived and persisted in `recon/count_gaps_all.txt` (the control table originally
>    published in §28 mislabeled two of these artifacts — corrected after background review,
>    reviewer M-1). Android's runtime FFmpeg is **`n8.1.2`** (logcat:8189) while the
>    Python repo pins **no** FFmpeg version anywhere, which is the honest limit of the
>    claim. **No remux/transcode/concat change was made** (out of scope).
> 3. **New LIMITATIONS §29 — in-app (Media3) playback of the matched-pair file fails**,
>    reproduced twice on `REDACTED-SERIAL` with full Logcat. Root cause classified **C (Android
>    platform/decoder limitation)**: `C2SoftAacDec … aacDecoder_ConfigRaw decoderErr =
>    0x2003` at codec-config time; the AudioSpecificConfig `0b 88` (AAC-Main, 22050 Hz,
>    mono) is **byte-identical in the Python reference file**, so that file would fail
>    identically in Media3 — classes **A** (our file) and **B** (source content) are ruled
>    out by VLC rendering the file on the same device and by a full `ffmpeg` decode
>    exiting 0 for both files — persisted in `.investigation/media-final/decode_full.txt`
>    *(citation corrected after review: `decode_compare.txt`, originally cited here, holds no
>    exit code and its script could not record one)*. The `decode_compare.txt` `profile=HE-AAC` / `sample_rate=44100` reading of these same files is reconciled with the declared AudioSpecificConfig at `LIMITATIONS.md:1359`.
>    Class **D** is ruled out by that evidence. **No player code defect was found; no
>    playback code was changed** (no codec switch / remux / transcode / Media3 redesign).
>    The assembly pipeline itself is unaffected: FFmpeg assembles, exports to MediaStore
>    and the file plays in VLC.

> **Phase 11.5H — Android-vs-Python MP4 parity corrective (2026-09-29):** items (2) and (3)
> of 11.5G above are **superseded** by measured fixes (DECISIONS 80 + 81; LIMITATIONS §28/§29
> marked RESOLVED, residuals in §30). Comparison set is now a **four-file** set on one
> title-matched episode (The World's Strongest Rearguard Ep. 1) — Python
> `…animepahe.mp4` (112,134,041 B, `ea217fe3…`), Android-BEFORE (`android_rearguard_ep1.mp4`,
> 112,170,554 B, `086f2723…`), Android-AFTER (`…_after.mp4`, 112,134,018 B, `5e2287e4…`,
> order fix only) and Android-FINAL (`…_final.mp4`, 112,134,018 B, `560dd43b…`, both fixes)
> — all retained in `.investigation/media-final/` with `comparison_report.{txt,json}`,
> `byte_report.txt`, `gaps_all.txt`, `track_payload_report.txt`,
> `asc_profile_comparison.txt`, `final_ffmpeg_decode.txt`, `vlc_{final,python}_playback.log`.
>
> 1. **Timeline parity achieved — reported as measured, not as blanket parity.**
>    AFTER and FINAL: **0 video / 0 audio holes** (BEFORE: 142/142), video `total_ticks`
>    **127,800,172** and audio **62,631,937** — both **exactly** Python's; container
>    `duration` **1420.225329 s** and `bit_rate` **631640** equal Python; video `stts`
>    byte-identical (17,024 entries). Residual differences vs Python are listed precisely
>    in **LIMITATIONS §30**: one `esds` byte (deliberate), audio `stts` run grouping
>    11,228 vs 11,231 at identical `total_ticks` (→ file is 23 B smaller), and the
>    single-input temp-file disk cost. **No parity claim is made for those items.**
> 2. **Media payload proven untouched.** `mdat` payload md5 `6bf15684…676a6`
>    (111,055,878 B) is identical for Python, AFTER and FINAL; per-track elementary-stream
>    md5s (video `40a5738b…`, audio `8da0e8b9…`) are identical across **all four** files.
>    BEFORE's different `mdat` md5 at the same size is therefore audio/video **interleaving
>    order** under the stretched timeline, never a content change — `track_payload_report.txt`.
> 3. **LIMITATIONS §29 RESOLVED — Media3 now plays the app's own output, verified on device.**
>    The AAC-Main declaration originates in the source TS (ADTS `profile = 0` on **10,663**
>    frames of `recon/src.ts`), contradicts the payload (SBR+PS ⇒ HE-AACv2, LC core only), and
>    is rejected by the device's only AAC decoder. `AudioSpecificConfigNormalizer` corrects
>    **one byte** of metadata. After the fix: logcat `I/AscNormalizer: Corrected
>    AudioSpecificConfig at offset 743396` → `I/C2SoftAacDec: Reconfiguring decoder:
>    0->44100 Hz, 0->2 channels` → **playback advances 00:18 → 00:54** (`media3_playback_00m18s.png`,
>    `media3_playback_00m54s.png`, OSD 23:40) with **0** lines matching
>    `aacDecoder_ConfigRaw|Codec reported err|Decoder failed|ExoPlaybackException|Failed to decode`.
>    Before the fix the same episode logged 3 such lines (`logcat_before_fix_playback.txt`).
> 4. **Independent decoders agree the corrected declaration is the right one.** FFmpeg
>    `-v warning -xerror` on FINAL → **STRICT_OK, zero warnings**; the AAC-Main declaration
>    emits 30,581 PS advisories. ffprobe reads FINAL as `HE-AACv2 / 44100 / 2 ch` with no
>    advisory and Python as `HE-AAC / 44100 / 1 ch` **with** one. VLC's FAAD reports
>    `AAC SBR+PS (channels: 2, samplerate: 44100)` and **0 playback errors for both files** —
>    which is precisely why VLC could not certify the original declaration.
> 5. **Nothing outside the assembly path changed.** No re-encode (`-c copy` on both paths),
>    no codec/decoder/Media3/player change, no Kwik or download-resume change.

### 6.3 Cleanup

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Episode cleanup | `store.py:cleanup()` (`shutil.rmtree(self.dir)`) | `SegmentFileStore.cleanup()` | 🔄 | Different mechanics: Python deletes the whole segment directory unconditionally, while Android deletes segment artifacts but **preserves a `.mp4` output** living in the same directory (Android assembles in-place; Python's output is written to `output_dir`, outside the segment dir). Keeps cleanup safe for startup reconciliation (Phase 8/10) |
| Orphan cleanup | `main.py:419-425` — age-based sweep after session finish: `cache_dir/*/*/*.ts` older than 86400 s → `rmtree` of the session dir | `SegmentFileStore.cleanupOrphans()` | 🔄 | **Citation/semantics corrected 11.5E-6** (`main.py` line 86400 was a mangled line number — 86400 is the age threshold in seconds). Android only removes `.tmp` crash artifacts in the *current* episode's directory, at download start; there is no cross-session age sweep — segments must survive process death for resume (§9/§17.1), so a Python-style 24 h sweep would break Android's persistence contract |
| keep_temp flag | `downloader.py:keep_temp` (config key, default False; `downloader.py:121` keeps segments when True) | `EpisodeDownloader.keepTemp`, fed from `DownloadConfig.keepTemp` (`DownloadWorker.executeClaimedWork`), which `AppConfigDownloadConfigSource` fills from the persisted `keep_temp` setting written by the `SettingsScreen` switch | ✅ | **Wired in Phase 12A** — retires the 11.5E-6 ⚠️ (the field was hard-coded `false` and `DownloadConfig` had no `keepTemp` member). Default still matches Python (`false`); the switch is read once per worker run and its effective value is asserted by `DownloadWorkerConfigTest` |

---

## 7. Caching

### 7.1 Scan Cache

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| JSON persistence | `scanner.py:_save_cache()` | `ScanCacheRepository` (Room) | 🔄 | Room instead of file |
| TTL check | `scanner.py:_load_cache()` | `ScanCacheRepository.getCachedScan()` (TTL check) | ✅ | Same logic |
| Glob lookup | `scanner.py:scan()` | `ScanCacheRepository.getCachedScan(session, …)` | ✅ | Same pattern |
| Cache path | `scanner.py:_cache_path()` | N/A — Room database (no filesystem cache path) | 🔄 | Different storage mechanism |

### 7.2 AES Key Cache

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| In-memory TTL | `cache.py:TTLCache` | `AesKeyCache` | ✅ | ConcurrentHashMap + LRU eviction |
| 1-hour TTL | `downloader.py:ttl=3600` | `AesKeyCache(ttlMs=3600000)` | ✅ | Same value |
| Max size 128 | `cache.py:max_size=128` | `AesKeyCache(maxSize=128)` | ✅ | Same limit |
| Shared across episodes | `downloader.py:shared_key_cache` (one cache for every episode in the process) | `EpisodeDownloader.keyCache` | ⚠️ | **Divergence recorded 11.5E-6**: `DownloadWorkerFactory.kt:113-114` (pre-A2.1 fallback) constructed a fresh `OkHttpHlsClient()` + `AesKeyCache()` per Worker, so the cache spans segments/retries *within* one episode's run but is **not** shared across concurrently downloading episodes — every episode refetches the same key. TTL/size parity (rows above) unaffected; cross-worker sharing deferred until A2.1 Decision 5 — CLOSED 2026-10-20: `AppContainer.kt:379` now owns ONE app-scoped `AesKeyCache` injected through `DownloadWorkerFactory.kt:84`, shared across every Worker. |

### 7.3 FlareSolverr Response Cache

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| 120s TTL | `solver.py:ttl=120` | N/A | ❌ NOT IMPLEMENTED | FlareSolverr replaced by WebView session — no equivalent cache needed |
| 256 max size | `solver.py:max_size=256` | N/A | ❌ NOT IMPLEMENTED | WebView session manages its own cache |

---

## 8. Sessions/Cookies

### Phase 5 Implementation Status

**Status**: COMPLETED — Full Implementation

**Implemented Components**:
- `CloudflareSessionProvider` interface — domain-layer contract
- `AndroidCloudflareSession` — Android WebView implementation *(final-audit fixes 2026-10-07, DECISIONS 139: **M2** - the real main-frame HTTP status captured by `onReceivedHttpError` is surfaced into `PageResult` error text (`HTTP 401`, etc.; `0` now reports the WebView-reported-no-error case) instead of the previous silent `statusCode = 0`; **M3** - a failed `performPageFetch` restores the original `WebViewClient` before reporting, with a diagnostic log line)*
- `SessionPersistence` interface — domain-layer persistence contract
- `AndroidSessionPersistence` — SharedPreferences implementation
- `WebViewRequestBridge` interface — domain-layer request bridge
- `AndroidWebViewRequestBridge` — implementation delegating to CloudflareSessionProvider
- `CloudflareDetector` utility — detects Cloudflare challenge responses in HTTP
- `WebViewHttpClient` — bridges HttpClient interface to WebView session

**Validated Results (Android 16)**:
- ✅ WebView successfully loads AnimePahe and completes Cloudflare challenge
- ✅ Cookie extraction works (5 cookies including cf_clearance)
- ✅ Cookie names: cf_clearance, SERVERID, latest, XSRF-TOKEN, animepahe_session
- ❌ Cookie transfer to OkHttp does NOT bypass Cloudflare (403 with `cf-mitigated: challenge`)
- ❌ OkHttp's TLS fingerprint is detected by Cloudflare
- ⚠️ Kwik resolution not yet tested with real URLs

**Architecture**:
- WebView is the browser/session mechanism for all Cloudflare-protected AnimePahe requests
- OkHttp can be used for non-Cloudflare endpoints (HLS segments, AES keys)
- All AnimePahe API requests (search, episodes, play pages) go through WebView

**Session Lifecycle**:
- Creation: `establishSession()` with Mutex synchronization
- Reuse: `ensureSession()` returns existing valid session
- Expiry detection: `isSessionValid()` checks cookie presence AND timestamp freshness (20-minute threshold)
- Refresh: `establishSession(forceRefresh = true)`; **SPEC §6.10 prompt on failure (final audit H1, 2026-10-07, DECISIONS 139):** detection sites (bridge pre/post-check `!isReady`, `AndroidCloudflareSession.fetchPage` `!isReady`, real main-frame 401/403) raise `SessionRefreshSignal.requestUserPrompt()`, and a root `MainActivity` AlertDialog offers **Refresh session** / **Not now** instead of failing silently; Refresh calls `AppContainer.forceRefreshWebViewSession()` → `establishSession(forceRefresh = true)` and the prompt hides on success. Evidence: `SessionRefreshSignalTest` 4/4 + `AndroidWebViewRequestBridgeSessionPromptTest` 3/3 + `SessionRefreshViewModelTest` 6/6 (JVM), `SessionRefreshPromptDialogTest` 2/2 (device)
- Release: `releaseSession()` destroys WebView (dispatched to main thread)
- No Activity/Context in singleton state

**Persistence**:
- User-Agent, active host, validation timestamp, cookie count
- Does NOT persist raw cookie values (TLS-bound)
- 20-minute freshness threshold

**Test coverage**: 58 tests (CloudflareDetector 17, CloudflareSessionProvider 17, Persistence 10, Bridge 14)

### 8.1 FlareSolverr Integration

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Session creation | `solver.py:_ensure_session()` | `AndroidCloudflareSession.establishSession()` | ✅ | WebView API |
| Session rotation | `solver.py:request()` | `AndroidCloudflareSession.establishSession(forceRefresh=true)` | ✅ | WebView API |
| Session destruction | `solver.py:destroy_session()` | `AndroidCloudflareSession.releaseSession()` | ✅ | WebView API |
| Cookie injection | `solver.py:_user_cookies` | `CookieManager.setCookie()` | ✅ | Android API |
| Request handling | `solver.py:request()` | `AndroidWebViewRequestBridge.fetchPage()` | ✅ | WebView loading |

### 8.2 curl_cffi Integration

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| TLS impersonation | `http.py:_curl_fetch()` | `OkHttp with TLS config` | ❌ | Cannot impersonate Chrome TLS (validated) |
| Chrome fingerprint | `kwik.py:impersonate="chrome120"` | `OkHttp ConnectionSpec` | ❌ | TLS fingerprint detected (validated) |
| 403 fallback | `http.py`: HTTP 403 → curl | `WebView fallback` | 🔄 | WebView required (validated) |

### 8.3 User-Agent Handling

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Fixed Chrome UA | `kwik.py:_HEADERS` (Chrome/120 string) | Real WebView UA (`CloudflareSessionProvider.getSessionInfo().userAgent`) | ⚠️ Different string — deliberate | Android must never send a hard-coded User-Agent (SPEC §6). The real WebView UA measured **HTTP 200** on the Kwik page, the CDN playlist and the segments (11.5E-4), so functional parity holds while the string differs |
| Kwik Referer | `kwik.py:_HEADERS["Referer"]` | `KwikResolver.KWIK_REFERER` passed as `extraHeaders` | ✅ | Same value: `https://animepahe.com/` (11.5E-4) |
| FlareSolverr UA | `solver.py:sol.userAgent` | `WebView.getUserAgent()` | 🔄 | Real UA |
| Default aiohttp UA | `http.py` | `OkHttp.userAgent` | 🔄 | Different default |

---

## 9. Configuration

### 9.1 TOML Config

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Load/save | `config_manager.py` | `DataStore` | 🔄 | Different format |
| Validation | `config_manager.py:_validate()` | `AppConfig` validation (`data/config/AppConfig.kt`) | ✅ | Same rules |
| Clamping | `config_manager.py:_validate()` | `AppConfig` clamping (coerceIn/coerceAtLeast) | ✅ | Same ranges |
| CLI set | `config_manager.py:cli_set()` | `SettingsScreen` (`ui/settings/SettingsScreen.kt`) + `SettingsViewModel` — writes the real config keys from Phase 12A | 🔄 | **Phase 12A**: the settings screen now writes `quality`, `max_parallel`, `hls_workers`, `cache_ttl`, `keep_temp`, `auto_retry`, `show_console` (plus the Phase 11 theme keys) into the SAME DataStore `AppConfig` uses, and every one of them has a production consumer (§9.5). Full `cli_set` parity (setting ANY key, e.g. `resolve_ahead`/`output_dir`, from a command line) has no Android equivalent — Android is a GUI app with no CLI |

### 9.2 Config Keys

| Key | Python Default | Android Default | Status | Notes |
|-----|----------------|-----------------|--------|-------|
| quality | 1080 | 1080 | ✅ | Same |
| audio_lang | "jpn" | — (removed; legacy bytes inert) | ⚠️ | **Removed from Android by the audio slice (SPEC §23, verified 2026-10-08).** Python keeps the key (`main.py:84 --audio`, `config_manager.py:107` validates `jpn`/`eng`); Android now has NO global audio preference — `AppConfig.KEY_AUDIO_LANG` / `updateAudioLang` / `configuredAudioLang()` and the `SettingsScreen` Preferred-audio row are gone entirely (§9.1 audit note), and legacy `audio_lang=…` bytes in DataStore have no reader. Audio is selected per episode as a number → audio mapping (§9.5/§9.6 superseded below; DECISIONS 142). Gate evidence: a four-segment Settings dump with no audio row anywhere |
| max_parallel | 2 | 2 | ✅ | **Resolved in Phase 12A (2026-09-29).** The pre-12A divergence — `DownloadConfig.maxParallel = 3`, retained deliberately in 11.5 item (c) / DECISIONS 78 while settings→Worker wiring did not exist — is now superseded: `DownloadConfig.maxParallel` defaults to **2** (Python `config_manager.py:18`), the `SettingsScreen` row writes `max_parallel` (1..6) into `AppConfig`, and `AppConfigDownloadConfigSource` delivers it to `DownloadWorker`, which reads it **ONCE per run** before the gate. `DownloadWorkerConfigTest` asserts both the once-per-run read and that the persisted value (not the constructor default) drives the gate; `ConcurrencyTest` pins the persisted value in its setup. **Unchanged, still a documented difference:** Android's gate is a soft `countActive()` cap with a deferred-episode trigger while Python uses an `asyncio.Semaphore` (LIMITATIONS §4) — numeric parity does not imply mechanism parity. |
| hls_workers | 24 | 24 | ✅ | Same |
| output_dir | "." | "./downloads" | ⚠️ | Android needs explicit |
| keep_temp | false | false | ✅ | Same |
| resolve_ahead | 999 | 999 | ✅ | Same default value (`AppContext.resolveAhead`) **and consumed since Phase 12C**: `AnimeDetailsViewModel.configuredResolveAhead()` → `ResolveAheadGate.awaitCapacity` staging bound (§5.1), read once per batch; Settings row offered as 0/1/2/4/8/Unlimited(999) |
| cache_ttl | 60 | 60 | ✅ | Same |
| cookie_string | "" | "" | ✅ | Same |
| auto_retry | 2 | 2 | ✅ | Same |

### 9.3 Implementation Details

**DataStore Configuration Persistence:**
- Implemented `AppConfig` class using AndroidX DataStore Preferences
- Each configuration key stored as a separate preference
- Validation and clamping logic directly ported from Python `_validate()`:
  - Quality: only 360, 720, 1080 allowed
  - Audio language: only "jpn" or "eng" allowed
  - Parallel downloads: clamped to [1, 6]
  - HLS workers: clamped to [8, 32]
  - Auto retry: clamped to [0, 2]
  - Resolve ahead and cache TTL: clamped to >= 0
- Flow-based reactive updates for UI observation
- Defaults match Python reference exactly — and since Phase 12A they are the SAME values the background worker runs with: `DownloadConfig.from(AppContext)` maps the persisted snapshot (`max_parallel`, `hls_workers`, `auto_retry`, `keep_temp`) and `DownloadConfig.maxParallel` now defaults to 2, retiring the pre-12A "documented intentional divergence" (worker default 3) recorded in §9.2 (DECISIONS 83)

**Room Database Persistence:**
- Implemented `PaheDatabase` with **version 3 schema** [**SUPERSEDED 2026-10-22**: the current schema is **version 4** — `PaheDatabase.kt:52`; `MIGRATION_3_4` adds `EpisodeDownloadEntity.replaceRequested`/`replacePriorUri` for the Replace-file feature (DECISIONS 152); it was added with no destructive fallback — `PaheDatabase.kt:40-41`. The v1→v2→v3 migration history below stands as written; at the time of writing v3 was the current schema.] (`MIGRATION_1_2` drops the `segments` table and adds `m3u8Url`/`userAgent`/`referer`/`segmentDir` columns — see PHASE10_ARCHITECTURE_V10.md §1; `MIGRATION_2_3` adds the nullable `outputUri` column and the `playback_position` table — PHASE11_ARCHITECTURE_V2 §29, added by Phase 11)
- Entities (v3 list; unchanged at v4 — the v4 migration only adds columns to `EpisodeDownloadEntity`, the five-entity set below is still the current set):
  - `ScanCacheEntity`: Replaces Python `_scan_cache.json` files
  - `DownloadSessionEntity`: Replaces Python `session.json` files (note: earlier revisions of this document called it `SessionEntity` — the actual class is `DownloadSessionEntity`)
  - `EpisodeDownloadEntity`: Tracks per-episode download state
  - `WatchlistEntity`: Replaces Python `watchlist.json`
  - `PlaybackPositionEntity`: Playback position per media item (Phase 11; Python plays via mpv, which persists position itself — no direct Python table equivalent)
- `SegmentEntity`/`SegmentDao` **existed at Phase 3 but were removed from the production database by the Phase 10 v1→v2 migration** (segment state now derived from the filesystem via `SegmentFileStore.doneIndices()`); they are no longer current Room entities
- All entities support cascade delete for cleanup
- DAOs provide Flow-based reactive queries for UI observation
- Database survives process death, configuration changes, and app restarts

**Domain Models:**
- `EpisodeInfo`: Direct port from Python with `epStr` and `cacheDirectoryName()` properties
- `AnimeInfo`: Direct port with `getVariant()`, `getAllVariants()`, `getUniqueEpisodeNumbers()`, plus `defaultVariant()` (`jpn`-else-first — the SPEC-sanctioned neutral bulk default added by the audio slice)
- `StreamInfo`: Direct port with `toHeaders()` and `cookieStr` properties
- `AppContext`: Configuration container with defaults matching Python reference

**Utility Functions:**
- `StringUtils.sanitize()`: Direct port of filename sanitization
- `StringUtils.epPrefix()`: Direct port of episode number formatting
- `StringUtils.fmtBytes()`: Direct port of byte formatting
- `StringUtils.compactEpRange()`: Direct port of episode range compaction
- `StringUtils.audioBadge()`: Direct port of audio badge display

### 9.4 SPEC §23 items previously untracked (added 11.5E-6)

SPEC §22/§23 (`SPEC.md:929-947`) lists twelve settings minimum; most map to §9.1/§9.2 rows above. The three items with no parity coverage anywhere in this document:

| Feature | SPEC | Android Equivalent | Status | Notes |
|---------|------|-------------------|--------|-------|
| Wi-Fi-only downloads | `SPEC.md:944` | Settings Switch row → `wifiOnlyConstraints()` helper → `NetworkType.UNMETERED` constraint attached at the three download build sites when ON (default OFF = no constraint) | ✅ | **Shipped and device-verified in Phase 13 (2026-10-02).** Constraint helper: `WifiOnlyConstraint.kt:28`, applied at `DownloadRepository.kt:1191`, `DownloadWorker.kt:994` and `DownloadStateReconciler.kt:388`; evidence `WifiOnlyConstraintTest` 4/4 (ON attaches UNMETERED, OFF attaches no constraint, reconciler reenqueue reads the setting) plus `TriggerNextDeferredEpisodeTest` (deferred enqueue attaches the current setting), green in the 383-test device run. Python has no equivalent (Android-specific requirement). **Enqueue-time only (DECISIONS 105): already-queued/running WorkRequests keep their existing constraints, the setting never cancels or rewrites them, OFF only makes future enqueues unconstrained, and reconciler/deferred re-enqueues read the current setting.** History: the row read ❌ NOT IMPLEMENTED through 2026-10-01, when no download work request carried an UNMETERED constraint and no setting existed — the only `Constraints` user in `src/` was `WatchlistCheckScheduler.kt:56` for the periodic *watchlist check* (the original "no `Constraints.Builder` usage exists in `src/`" wording was true at 11.5E-6 and false since 12D) |
| Notification settings | `SPEC.md:945` | Manifest `POST_NOTIFICATIONS` + notification channel `download_progress` + `DownloadNotificationManager` + `NotificationPermissionRepository`; Settings permission-status row + one-time first-enqueue runtime prompt | ✅ | **Shipped in Phase 13 (DECISIONS 95.A):** the Settings permission-status row (grant/deny state with a request / open-system-settings action) and the ONE-time `RequestPermission` prompt at the first enqueue (asked-once latch, launcher registered at `AnimeDetailsScreen.kt:130`); the `NotificationPermissionRepository.kt:25-27` contract ("belongs to the enqueue flow of Phase 13") was fulfilled — evidence `FirstEnqueuePromptTest` 4/4 and `NotificationPermissionTest` 6/6 (device, 383-test run). **Phase 14 completed (2026-10-03):** per-type channel preference rows shipped — `notify_completion`/`notify_failure`/`notify_watchlist` (all default ON), each with a production consumer at its post site; 4 app channels (progress LOW + complete/failure/watchlist DEFAULT); completion/failure/watchlist notifications device-verified; grouping keys + tap-to-open actions verified by `NotificationInteractionTest`. Evidence: `SettingsPersistenceTest` (device), `NotificationTest` (device), `WatchlistCheckWorkerTest` (device).  **SPEC §18 example-coverage (Phase 14 scope ruling, PHASE14_PLAN §2.1):** shipped examples are Download completed, Download failed and New watchlist episode detected (plus the Phase 10/11 progress notifications); OUT OF SCOPE with no notification - Download paused (the notification itself stays out of scope per DECISIONS 119 item 5; the two clauses that are superseded are DECISIONS 94.2's "Pause intentionally unsupported" and its "no underlying state" premise - the PAUSED state and the Pause/Resume controls shipped 2026-10-05, DECISIONS 129), Session requires user action (DECISIONS 109 derives display states only), Cloudflare/session expired (DECISIONS 102, session handling automatic behind the WebView abstraction); a notification for a non-existent state would fake state (AGENTS §5). |
| Auto-resume downloads | `SPEC.md:946` | WorkManager persisted work + `DownloadStateReconciler` startup reconciliation | ✅ | **Phase 14 core (2026-10-03):** `auto_resume` DataStore setting (default OFF); `DownloadErrorClass` typed classification (TRANSPORT/EXPIRED/NO_CONTENT/ASSEMBLY/IN_RUN_RETRYABLE/DISK_FULL - `DISK_FULL` added 2026-10-07 for SPEC §13 insufficient-storage / ENOSPC pre-flight and classification (final audit H2, DECISIONS 139)); `shouldAutoResume()` — TRANSPORT-only, bound `AUTO_RESUME_MAX_RETRIES = 7`, exponential 30s backoff; canonical `downloadNetworkConstraints(wifiOnly, autoResume)` 4-case matrix applied at all three enqueue sites; CAS park (DOWNLOADING→QUEUED) + `Result.retry()` + backoff; segments persist via `doneIndices` so a resume re-downloads nothing. Evidence: `AutoResumeEligibilityTest` 10/10 (JVM), `DownloadErrorClassMappingTest` 20/20 (JVM; 6 cases added for the DISK_FULL classification), `WifiOnlyConstraintTest` + `TriggerNextDeferredEpisodeTest` (device, 406-test run), `AutoResumeConnectivityTest` 2/2 (rows 419/420), `ProcessDeathRecoveryTest` 4/4 incl. row 421, `CancellationTest` 2/2 incl. row 422 (all device, 411-test full-suite run). **Gate closed 2026-10-03:** the PARITY 9.4 connectivity-severing instrumentation test (row 419) is device-verified - `AutoResumeConnectivityTest.severedTransport_midDownloadParksThenAutoCompletesWithZeroRedownload` executes sever - park - restore - auto-complete with zero re-download (debug-scoped loopback fixture; sever at fixture origin), inside the 411-test full-suite run; reboot continuation remains a manual gate (LIMITATIONS §24). History — **Downgraded from ✅ by the 2026-09-28 reconciliation review (reviewer H-1);** as of 2026-09-28 what was implemented: process-death survival (`ProcessDeathRecoveryTest`, `RealProcessDeathSeedTest`), stranded-`DOWNLOADING` reconciliation, and segment persistence so a **manual** Retry resumes with zero re-downloads (`ResumeTest` — its two cases are FAILED-requeue and stranded-`DOWNLOADING`, **not** a network-interruption scenario, which the previous wording falsely cited). As of 2026-09-28, NOT implemented: **no automatic resume when connectivity returns for downloads** — no download work request carries a `NetworkType.CONNECTED` constraint or backoff policy (the Phase 13 Wi-Fi-only UNMETERED gate only pauses new enqueues on metered networks and never resumes failed work), every download failure ends in `Result.failure()` (terminal for WorkManager) after the downloader's `autoRetry+1` attempts, and `FAILED` is a terminal row; there is also no DataStore setting (`SPEC.md:918-920`). *(Claim narrowed 2026-10-01: "zero `Constraints.Builder`/`setRequiredNetworkType`/`NetworkType` usages in `src/main`" was true at the 2026-09-28 review and false since Phase 12D — `WatchlistCheckScheduler.kt:50-54` sets `NetworkType.CONNECTED` for the periodic watchlist check; no such usage exists anywhere on download work.)* The same root cause kept Wi-Fi-only above ❌ until Phase 13 shipped its enqueue-time UNMETERED constraint (2026-10-02); auto-resume itself was then assigned to Phase 14 and shipped there (2026-10-03). Reboot continuation is WorkManager's default rescheduling but has not been device-tested — recorded as a manual gate in LIMITATIONS §24 (11.5E-6 re-audit). **Phase assignment settled 2026-10-01 (DECISIONS 96): Phase 14** (it changes failure semantics). **To reach ✅**: a `NetworkType.CONNECTED` constraint + bounded `Result.retry()`/backoff on transport failure, plus an instrumentation test that severs connectivity mid-download and asserts automatic resumption - **SATISFIED 2026-10-03** (NetworkType.CONNECTED constraint + bounded Result.retry()/30 s exponential backoff + device-verified sever-and-resume test) |

### 9.5 Settings surface + consumed configuration (Phase 12A)

`SettingsScreen` is only allowed to expose a control whose value production code actually reads (AGENTS §5). Current state (the table covers the Phase 11/12A/12C rows; Phase 13 added the output-location SAF row, the Wi-Fi-only setting and the notification-permission status row + first-enqueue prompt — their consumers and evidence are at §9.4 above and the Output directory row below):

| Setting | Control | Production consumer | Status |
|---------|---------|---------------------|--------|
| `quality` | Radio 360p/720p/1080p | `AnimeDetailsViewModel.configuredQuality()` → `StreamResolver.resolve(quality = …)` | ✅ |
| `cache_ttl` | Slider 0–240 min | `AnimeDetailsViewModel.configuredCacheTtlMinutes()` → `EpisodeRepository.scan(…, cacheTtlMinutes)` (0 disables the scan cache) | ✅ |
| `max_parallel` | Radio 1–6 | `AppConfigDownloadConfigSource` → `DownloadWorker` concurrency gate (read once per run) | ✅ |
| `hls_workers` | Slider 8–32 | `DownloadConfig.hlsWorkers` → `EpisodeDownloader` segment semaphore | ✅ |
| `keep_temp` | Switch | `DownloadConfig.keepTemp` → `EpisodeDownloader` segment retention after mux | ✅ |
| `auto_retry` | Radio None/Once/Twice | `DownloadConfig.episodeRetryCount` (RAW value) → `EpisodeDownloader` attempts (`autoRetry + 1`) | ✅ |
| `show_console` | Switch (default **OFF**) | **Gates log-buffer retention** via `AppConfig.showConsole` → `PaheBatcherApplication` → `AppLog.setBufferingEnabled` (Phase 12B: OFF = lines not retained + retained lines cleared; Logcat unconditional), and gates the `Open console` row → `ConsoleRoute` | ✅ (Android-only feature) |
| `resolve_ahead` | Radio 0/1/2/4/8/Unlimited(999) | **Phase 12C**: `configuredResolveAhead()` → `ResolveAheadGate.awaitCapacity(downloadQueue, resolve_ahead + max_parallel)` before each batch/watchlist resolution, measured with `EpisodeDownloadDao.countResolvedPending()`; on expiry the batch reports `Timed out waiting for download capacity …` **plus how many episodes in that pass were never attempted**, and a watchlist check reports `FAILED` (§12.4) — neither silently ignores the setting | ✅ |
| `theme`, `dynamic_color` | Radio + Switch (Phase 11) | Theme machinery | ✅ |

**Deliberately NOT exposed (documented deferrals, never fake-wired — DECISIONS 84):** `cookie_string` (WebView session owns cookies, DECISIONS 26) and `output_dir` (SAF/external storage is a Phase 13 decision, LIMITATIONS §5 — **the SAF architecture itself is now settled, DECISIONS 92: a new `output_tree_uri` DataStore key is the Phase 13 output-location row's consumer, while the Python-parity `output_dir` path key stays retired on Android because scoped storage cannot honour an arbitrary path**). **Phase 12C re-evaluated the other two keys from the Phase 12B "KEEP DEFERRED" verdict and gave `resolve_ahead` a real consumer** (staging gate) — it is a row *because* production code reads it (DECISIONS 90, LIMITATIONS §33.1). The `audio_lang` row Phase 12C added was **REMOVED by the audio slice (SPEC §23, 2026-10-08)**: Android has no global audio preference anymore, the whole config path (`KEY_AUDIO_LANG`/`updateAudioLang`/`configuredAudioLang()`) is gone, and legacy bytes are inert (DECISIONS 142). No row exists whose value is only persisted.

**Configuration load semantics (parity-relevant):** the background Worker reads the persisted configuration **once at the start of each run** (`DownloadWorker.refreshConfig`) and keeps that snapshot for the whole run — the Android equivalent of Python building one `AppContext` at process start. A settings change therefore applies to the NEXT run, never mid-run, and a settings read failure is logged and leaves the current configuration in place (configuration is never a dependency of download execution). Resolution and scan reads behave the same way in `AnimeDetailsViewModel`.

**Diagnostics Console (Android-only, no Python row):** `AppLog` → Logcat (always) + bounded in-memory `LogBuffer` (500 entries, 4000 chars per message and per rendered throwable, memory-only — never Room, never disk), surfaced by `ConsoleScreen` only after the user enables `Show Console`. **Phase 12B:** the persisted key also gates *retention* — while it is OFF nothing is buffered and turning it OFF clears the buffer (atomic with the append, DECISIONS 88); there is deliberately **no log-content filter** — secret handling is a documented call-site rule, not machinery (DECISIONS 86 corrected, LIMITATIONS §31.2). **Release logging gate — final SPEC gap closure, 2026-10-05 (SPEC §33 "have debug logging disabled/restricted in release", DECISIONS 131):** `AppLog` now consults a single level gate **before either sink**, and it defaults to `BuildConfig.DEBUG`, so in a **release** build DEBUG/INFO lines reach neither Logcat nor `LogBuffer` while WARN/ERROR always pass; the gate has a test-only setter, the `Show Console` retention key stays a separate and independent gate, and no content filtering was added. This supersedes Decision 86 item 1 ("Logcat always") and Decision 88's "Logcat is unconditional" **for diagnostic levels in a release build** — both still describe the debug variant, which is the only variant exercised on device. The gate is a runtime level filter in `AppLog` only: it does not strip third-party library logs, release minification is off so there is no log-stripping mechanism, and there is still no content filter (LIMITATIONS §38.8). Evidence: `AppLogDiagnosticsGateTest` 7/7 (JVM — gate closed suppresses DEBUG/INFO from both sinks, WARN/ERROR survive, default matches the compiled variant). **Update 2026-10-06 (DECISIONS 132):** the gate is now asserted in the **release** variant as well — `android.onlyEnableUnitTestForTheTestedBuildType=false` in `gradle.properties` enables AGP's release unit-test task, and `AppLogReleaseVariantTest` 3/3 + the variant-aware `AppLogDiagnosticsGateTest` 7/7 + `LogBufferTest` run against the compiled **release** `BuildConfig`, giving `testReleaseUnitTest` **845/0/0/0** alongside `testDebugUnitTest` **845/0/0/0**. The three evidence tiers stay separate: source ✅, release-JVM ✅, release-APK-runtime ❌ never executed (release artifacts stay unsigned unless the maintainer sets the PB_* signing variables added 2026-10-24) - LIMITATIONS **Update 2026-10-07 (DECISIONS 138) - the three release-reachable URL-leak paths the final audit identified are closed:** (a) HlsPlaylistParser's URI-resolution WARN now emits LogUrlSanitizer.url(baseUrl) plus LogUrlSanitizer.diagnostic for the relative reference and the parser's own message (a malformed URL's query tail sits outside any scheme match, which is why the exception text uses diagnostic, not message); (b) the Kwik failure chain sanitizes at the throw (StreamResolverImpl keeps host+path in the exception message, which also feeds the persisted error text) AND again at the release-emitted ERROR in DownloadRepository; (c) the throwable channel is closed CENTRALLY: AppLog.renderThrowable runs every rendered stack trace - message, Caused by chain and frame metadata - through LogUrlSanitizer.diagnostic BEFORE either sink, and Logcat now receives that same pre-sanitized rendering instead of the raw Throwable, so no release-reachable WARN/ERROR exception can reintroduce a complete request URL. The claim is exactly these paths plus the centralized exception rendering - deliberately not a every URL in the application claim (DEBUG-only lines and non-URL call-site messages are unchanged, LIMITATIONS 39.5). Evidence: HlsPlaylistParserUrlLogSanitizationTest 1/1, AppLogThrowableSanitizationTest 5/5, LogUrlSanitizerTest 18/18 (4 new diagnostic cases), StreamResolverImplTest +1 throw-site case, AppLogReleaseVariantTest 4/4 (URL-bearing WARN/ERROR while the release gate is closed), device ReResolutionLogSanitizationTest 1/1 (the real DownloadRepository catch against real Room); both JVM suites **857/0/0/0**. §39.1) |

### 9.6 Settings functional audit (Phase 15, 2026-10-04)

Every row `SettingsScreen` exposes was audited against a **live consumer** or a **passing production-path test**; persistence alone was never accepted as evidence. Result: **19/19 FUNCTIONAL — 0 PARTIALLY FUNCTIONAL, 0 COSMETIC / NO EFFECT, 0 BROKEN, 0 UNVERIFIED.** The 19 = the 18 configuration controls below + the notification-permission status row; the `Open console` row is conditional and appears only while `Show Console` is ON (its appearance was verified live). Persistence across relaunch/force-stop was checked for every setting, but is recorded here only as a precondition — not as the evidence. **Superseded by the audio slice (2026-10-08, SPEC §23):** the Preferred-audio row no longer exists, so the live inventory is now **18 rows = 17 configuration controls + the permission status row** — `SettingsContentTest` asserts that exact inventory and was GREEN on device in the full 2026-10-08 connected run, and the 13-scenario gate re-dumped the screen in four scroll segments with no audio row anywhere; the table below keeps the historical 19-row audit with the Preferred-audio line struck as removed (DECISIONS 142).

| Row | What production code did with the value | Evidence tier |
|-----|------------------------------------------|---------------|
| Quality | `quality=1080` in the run config, `1920x1080` in the mux | LIVE (device) |
| ~~Preferred audio~~ | **REMOVED by the audio slice (SPEC §23, 2026-10-08)** — no `audio_lang` key, no `SettingsScreen` row, no consumer (legacy bytes inert); audio is chosen per episode as a number → audio mapping (§9.5/§9.6 below, DECISIONS 142) | — (row deleted; `SettingsContentTest` 18 rows GREEN on device + 4-segment live dump, no audio row) |
| Max parallel downloads | `maxParallel=2` | LIVE (device) |
| HLS workers | `hlsWorkers=24` | LIVE (device) |
| Keep temp files | `keepTemp=false` + segment cleanup after assembly | LIVE (device) |
| Auto retry | `episodeRetry=2` | LIVE (device) |
| Check frequency | console `interval=60m policy=UPDATE state=[ENQUEUED]` | LIVE (device) |
| Output location | console `Output tree state: NotConfigured` | LIVE (device) |
| Show Console | conditional `Open console` row renders + Console opens | LIVE (device) |
| Theme | visible UI change | LIVE (device) |
| Dynamic color | visible UI change | LIVE (device) |
| Scan cache TTL | consumed by `EpisodeRepository.scan(…, cacheTtlMinutes)` | **TEST** — consumer verified by a passing production-path test; no live log line exists for this consumer (disclosed, not upgraded) |
| Resolve ahead | consumed by `ResolveAheadGate.awaitCapacity` | **TEST** — same disclosure |
| Wi-Fi-only downloads | `UNMETERED` constraint at the three enqueue sites | Device persistence + `WifiOnlyConstraintTest` |
| Auto-resume downloads | 4-case constraint matrix + `shouldAutoResume()` | Device persistence + instrumentation tests |
| Completion / Failure / New watchlist episodes | persisted and gated at each notification post site | Device persistence + notification instrumentation tests |
| Notification permission status | `NotificationPermissionRepository` state + request / open-system-settings action | LIVE (device) + `NotificationPermissionTest` |

**Python-parity note:** Wi-Fi-only downloads, auto-resume, the per-type notification preferences, Show Console, Theme and Dynamic color are **Android extensions with no Python equivalent** — they are deliberately not claimed as Python parity (§9.4, §9.5). `cookie_string` and `output_dir` remain deliberately not exposed (DECISIONS 84/96).

---

## 10. Storage

### 10.1 Directory Structure

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Output directory | `main.py:output_dir` (Python: joins `args.output` with `safe_title = sanitize(anime.title)`) | `filesDir/downloads/{episodeId}` staging + export to a user-picked SAF tree (`<selected-tree>/<sanitized-anime-title>/`), else MediaStore `Movies/<sanitized-anime-title>/` — per-anime since Gate A (2026-10-08) | ✅ | Fixed internal location implemented in Phase 10 (PHASE10_ARCHITECTURE_V10.md §2, Decision 40); assembled MP4 lives inside this directory alongside segments. User-selectable external storage **shipped in Phase 13 (2026-10-02)** as a SAF tree pick (DECISIONS 92) — an arbitrary POSIX path like Python's `output_dir` is impossible on Android (documented divergence). Phase 11 architecture (**PHASE11_ARCHITECTURE_V2.md §27/§30**, Decisions 45+54) implements idempotent MediaStore export to `Movies/PaheBatcher/` outside `Android/data` — **implemented and verified in Phase 11** (MigrationTest 5/5, MediaStoreExportTest 8/8; cross-store atomicity remains best-effort — LIMITATIONS §18). *(Pre-Gate-A flat layout — superseded for NEW exports by the Gate A block below; the directory remains the legacy home for existing files.)* **SAF export target implemented and device-verified in Phase 13 (DECISIONS 92): hybrid pipeline — staging and FFmpeg stay on private `File` paths, the assembled MP4 exports to a validated SAF tree when configured else to the MediaStore default, byte-verified before the local copy is deleted, no SAF atomic-rename claim, collisions reused-by-size or uniquified (never overwritten), `output_tree_uri` persists the tree; evidence `SettingsOutputLocationTest` 7/7, `OutputTreeRepositoryTest` 8/8, `SafExporterTest` 9/9, `ExportDestinationRouterTest` 5/5, JVM `SafExportNamesTest` 16/16**. **Gate A — per-anime export directory (2026-10-08, DECISIONS 141):** new exports land in `Movies/<sanitized-anime-title>/` (MediaStore `RELATIVE_PATH`) or `<selected-tree>/<sanitized-anime-title>/` (SAF — the anime directory is created/reused beneath the chosen tree), reproducing Python's model of output root → `sanitize(anime.title)` → sanitized episode filename (`main.py` computes `safe_title = sanitize(anime.title)`; `utils.py` supplies `sanitize`, underscore collapsing included). The directory name derives from the existing `DownloadSessionEntity.title` — no duplicate title state. Android applies directory-component hardening BEYOND the Python sanitizer (path separators, control characters, `.`/`..` traversal, unsafe leading/trailing characters, Windows-reserved names, deterministic fallback) via `OutputPathNames.harden()`, so hardened directory cases are deliberately NOT byte-for-byte Python-identical; episode file naming stays `FileNameGenerator` (§10.2, unchanged). Legacy flat files under `Movies/PaheBatcher/` are retained and playable — intentionally never migrated, renamed, deleted or rewritten. Platform mechanics (Android-only, not Python behaviors): MediaStore writes `RELATIVE_PATH`, SAF creates/reuses the directory under the tree. Evidence: JVM `testDebugUnitTest` 915/915 (`StringUtilsTest` 23/23, `OutputPathNamesTest` 17/17, `OutputLocationDescriptionTest` 4/4), `lintDebug` 0 errors / 0 warnings, `assembleDebug` + `assembleDebugAndroidTest` SUCCESS, physical device `REDACTED-SERIAL` — ordinary (`Movies/One_Piece/`), apostrophe (`The World's Strongest Rearguard` → `The_Worlds_Strongest_Rearguard/`), real legacy-file playback and two-episode batch all PASS with production playback; Unicode-title scenario NOT exercised on device (JVM Unicode coverage; LIMITATIONS §40)** |
| Cache directory | `main.py:cache_dir` | `filesDir/downloads/{episodeId}` (segmentDir) | ⚠️ PARTIAL | Segment staging implemented via SegmentFileStore in the same fixed directory as the output (segments cleaned after assembly, output preserved — corrective F-C1); configurability/SAF pending |
| Session metadata | `store.py:session.json` | `Room:DownloadSessionEntity` | 🔄 | Room instead of a JSON file; **wired to the download flow** — `DownloadRepository.ensureSession()` creates/refreshes the row on enqueue (title/host/quality/audioLang/outputDir/cacheDir/state; `audioLang` is the episode's own audio — `DownloadRepository.kt:223` — not a global preference; `createdAt` preserved; `ensureSession` uses `insertIfAbsent` (Room `INSERT OR IGNORE`) plus `update` rather than `upsert`, because `REPLACE` deletes the conflicting parent and the FK's `ON DELETE CASCADE` then wipes every child episode row — DECISIONS 72, re-hardened in DECISIONS 146). Device evidence: `Download session … created` (11.5E-4 and 11.5E-5 runs); sessions screen live `1080p · jpn` (audio-slice gate, 2026-10-08). *(Stale "not wired" note corrected in 11.5E-5.)* |
| Scan cache | `scanner.py:_scan_cache.json` | `Room:ScanCacheEntity` | 🔄 | Database (fully implemented) |
| Segment files | `store.py:*.ts` | `SegmentFileStore` | ✅ | Same structure |

### 10.2 File Naming

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Output naming | `downloader.py:56-58` — `sanitize(f"Ep {prefix} - {title}") or f"ep_{ep.ep_str}"` | Local staging: `FileNameGenerator.episodeFileName()` — **unchanged**. Export destination: `ExportFileNames.exportedName()` (Slice A) | ⚠️ | **Local staging stays exact Python parity**: same `Ep {prefix} - {title}.mp4` pattern with full-string sanitization (corrected in Phase 11.5E-5 — the Sanitization row below is about that local name; the private segment directory, the assembled MP4 inside it and every resume identity key are untouched). Production local output `Ep_001_-_Spy_x_Family_Ep._1_animepahe.mp4`, unchanged by Gate A (2026-10-08), which restructured only the containing directory (see §10.1 Output directory row; DECISIONS 141). **The EXPORTED destination leaf name is deliberately audio-suffixed (Slice A, 2026-10-11 — DECISIONS 145, LIMITATIONS §44): `Ep_001_-_Spy_x_Family_Ep._1_animepahe_[jpn].mp4`** — Android has one shared destination per anime where Python has a per-run directory, so both audio variants of one episode previously computed the same leaf name and MediaStore's exactly-one-row rule then deleted the other variant's file (SPEC.md:633); the `_[audio]` token is derived from `episode.audio` alone, so export order cannot affect it, and a pre-Slice-A unsuffixed file is never deleted, renamed or migrated — it is reused only as a size-verified alias when no other Room row references it (the ⚠️ marks the export destination only; local naming remains ✅ Python parity) |
| Segment naming | `store.py:seg_path()` | `SegmentFileStore.segPath()` | ✅ | Same format (`{idx:06d}.ts`) |
| Sanitization | `utils.py:sanitize()` applied to the FULL `f"Ep {prefix} - {title}"` string (`downloader.py:57`) | `FileNameGenerator.episodeFileName()` = `StringUtils.sanitize("Ep $prefix - $title")` | ✅ | Direct port since **Phase 11.5E-5** (was title-only, keeping spaces in the prefix). Verified: JVM `FileNameGeneratorTest` 17/17 (`Ep_001_-_Pilot.mp4`, `Ep_013.5_-_Clash_of_Kings.mp4`, dedicated prefix-separator test); the three FFmpeg androidTest filename assertions now expect the Python form, clearing the 3 pre-existing `Clash[ of ]Kings` known failures (instrumentation baseline 4 → 1). Production name: `Ep_001_-_Spy_x_Family_Ep._1_animepahe.mp4`. Python's `or f"ep_{ep.ep_str}"` fallback is unreachable (sanitize preserves the `Ep ` prefix) and is intentionally not ported (DECISIONS 73) |

---

## 11. Playback

### 11.1 MPV Integration

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| HLS playback | `stream.py:run_stream()` — MPV plays the resolved HLS playlist directly (`--stream`, `main.py:76,404-405`) | **Phase 17 (`PlayerMode.STREAM`)**: Anime Details → `AnimeDetailsViewModel.streamEpisodeId()` → `PlayerRoute(episodeId, mode = STREAM)` → `PlayerViewModel.loadStreamPlaylist()` → `StreamEpisodeReconstructor` (Room row → unexpired scan cache → network scan) → JUST-IN-TIME `StreamResolver.resolve()` → `HlsPlaybackSourceFactory.create()` (OkHttp + `StreamInfo.toHeaders()`) → `HlsMediaSource` → the one existing ExoPlayer. DOWNLOAD mode keeps `PlaybackSourceResolver.sourceFor(row)` / `getCompletedPlaylist(...)` unchanged | ✅ **IMPLEMENTED + DEVICE-VERIFIED (Phase 17, 2026-10-05)** | **Phase 17 supersedes the Phase 16 verdict.** `media3-exoplayer-hls` + `media3-datasource-okhttp` **1.11.1** were added through the existing `media3` version key (no other toolchain change), so LIMITATIONS §36 is **RESOLVED**. Evidence, physical device `REDACTED-SERIAL`, real network + real Cloudflare session, no fixture: search → scan → `resolve(quality=1080, audio=eng)` → `hostPath=vault-13.owocdn.top/stream/…/uwu.m3u8`, `referer=kwik.cx/e/xChstKfzzw4i` → Media3 `state=READY`, `durationMs=6601462` **parsed from the real media playlist** → playhead `0 → 2919 ms` over 3 s → `playerError=null` (`Phase17DeviceStreamingVerificationTest`). Request headers (User-Agent, Referer, Cookie) proven **on the socket for the playlist, the AES-128 key and the segment** through the real playback stack (`StreamHlsHeaderTest`, emulator + physical). The route carries **only** `episodeId` + `PlayerMode` — no URL, no `StreamInfo`, no UA/Referer/cookies; streaming reads Room (never writes it), never touches WorkManager/`EpisodeDownloadEntity`/FFmpeg. Still absent and out of Phase 17 scope: subtitles, in-player quality/audio switching, streamed next/previous, a multi-episode stream playlist (rows below). **CORRECTED 2026-10-06 - the sentence above is Phase 17 history, not current state: streamed next/previous is IMPLEMENTED (rows "Episode navigation" and "Next/Previous" below; DECISIONS 130 item 8 supersedes this clause, exactly as it already superseded `ROADMAP.md:914` and `LIMITATIONS.md:2393`). What is still absent as of 2026-10-06: in-player quality/audio switching and a multi-episode stream playlist.** **(Update 2026-10-06, second closure round: subtitles - the third item that historical sentence listed - shipped the same day as an in-playback text-track control; see the `Subtitles` row in 11.1, the `Subtitles / text tracks` row in the verified table, and DECISIONS 133.)** The Phase 16 note is retained as history: *"No `HlsMediaSource` … `media3-exoplayer-hls` is not a declared dependency"* — true until Phase 17, false now. |
| Quality selection | `stream.py:run_stream()` | **Resolve-time**: `AnimeDetailsViewModel.configuredQuality()` → `StreamResolverImpl.resolve(quality = …)` → `QualitySelector.select()` (§2.1) | 🔄 | **Applied during stream/variant resolution, not during playback** — *Phase 15 correction (2026-10-04): this row previously claimed Media3 `TrackSelection`, a symbol that does not exist anywhere in `app/src/main`.* There is still no in-playback quality control: the player shows whatever rendition the resolver selected. **Phase 17 note:** a direct STREAM entry resolves again at stream start with the persisted `AppContext.quality` (`resolveStreamFor`), so streaming and downloading pick the rendition through the *same* `QualitySelector`. Python picks the rendition while streaming. |
| Audio switching | `stream.py:run_stream()` | **Per-episode audio at resolve (audio slice)**: a STREAM entry resolves with `episode.audio`; a batch resolves each number with `explicitAudioOverrides[number] ?: selectedAudio` — the explicit override, else the current screen-level context, derived once at batch start (Option C, DECISIONS 143; it superseded the audio slice's `selectedVariants[number]` record); a watchlist `any` entry resolves through `AnimeInfo.defaultVariant()` (§1.4, §9.5) | ⚠️ | **Different mechanism at a different stage** — *Phase 15 correction: previously claimed `TrackSelection`, which does not exist.* Python's `a` key flips `audio_pref` and rebuilds the playlist **inside the player**; Android fixes the audio **before resolution** from the effective per-number value (exact match, no substitution). **Audio slice (2026-10-08):** the global `configuredAudioLang()` preference this row used to cite is gone with the Preferred-audio row (SPEC §23) — the request always comes from the tapped row's audio or the batch's derived mapping. **Option C (2026-10-09, DECISIONS 143):** the "tapped row's audio" half is superseded — a row tap toggles only the number, and single resolution uses the same `override ?: context` formula (`AnimeDetailsViewModel.effectiveVariantOf`). **In-player audio switching is still NOT implemented** — see the three-mechanisms note below. **Option C render contract device-verified (2026-10-10):** the full connected suite on `REDACTED-SERIAL` covered the selector, one-row-per-number in every context and the `getAllVariants(N)` override gate — 9/9 after a found-and-fixed gating defect (DECISIONS 144 device addendum); the probe chain itself stays test-only (§1.4). |
| Episode navigation | `stream.py:run_stream()` | `PlayerViewModel.playNextEpisode()` / `playPreviousEpisode()` (STREAM mode, in-place source swap) | ✅ | **Implemented 2026-10-05 (final SPEC gap closure, SPEC §15 "episode navigation", DECISIONS 130).** The neighbour list is the session's own episode list, read once per entry through the cache-first `EpisodeRepository.scan` seam and filtered to the same audio variant by the pure `streamSiblingIds(...)`, so navigation is bounded by what the session really contains and never wraps and never crosses SUB/DUB. A switch re-enters `loadStreamPlaylist`, so the target episode goes through the same reconstruction ladder, the same just-in-time resolve and the same bounded retry budget as the entry's own seed — same ExoPlayer, same route, never a stacked route. An unreadable list renders **both** skip controls disabled rather than a control that pretends a next episode exists (AGENTS §5). DOWNLOAD mode is untouched and keeps Media3's own playlist skip controls. Evidence: `StreamSiblingIndexTest` 5/5 (JVM), `StreamEpisodeNavigationUiTest` 4/4, `StreamPlaybackLifecycleTest` 6/6 and the `PlayerLifecycleTest` 8/8 regression (all device `REDACTED-SERIAL`). **Real-CDN device evidence (2026-10-06, DECISIONS 135):** `Phase17StreamingSiblingNavigationDeviceTest.realCdnNextAndPreviousEpisodeNavigationStaysHealthyCoherentAndBounded` on `REDACTED-SERIAL` drives the production ViewModel over the live backend — real search → real scan → seed at N → `READY` → N to N+1 (new media id, the SAME ExoPlayer, mode still `STREAM`, no load error) → Next attempted at the last episode and **refused for a full 60-second window** (media id, sibling index and error all unchanged) → Previous back to N (again `READY` with a playlist-parsed duration) — after which the MediaSession still serves that same player and still carries its seeded episode id (the route-seed limitation of LIMITATIONS §38.3 is documented there, not asserted away). **Deliberate difference from Python:** `PlaylistPlayer` walks a pre-built multi-episode playlist; Android keeps one media item and swaps the source in place — see DECISIONS 130 |
| Local HLS proxy | `stream.py:_prepare_local_hls()` | `MediaSource` | ⚠️ | **Not needed and not built.** Phase 16 concluded "not needed — there is no streaming path to proxy for"; Phase 17 added the streaming path and still does not proxy it: Media3's OkHttp `DataSource` talks to the CDN directly carrying `StreamInfo.toHeaders()` — the same UA/Referer the downloader already sends. A local proxy would add a listener and a second hop for no behavioural gain (SPEC §15's local-proxy allowance is therefore unused) |
| Subtitles | `stream.py:355-395` — MPV is started on the playlist with no subtitle flag of its own, and `stream.py:437-451` handles only `n`/`p`/`r`/`q`/`a`/`s` — so whatever subtitle renditions the media declares are MPV's to render, and Python exposes **no subtitle control** of its own | `PlayerViewModel.subtitleOptions` / `selectedSubtitleIndex` (derived from Media3 `Tracks` by `PlayerSubtitles.kt`) → `PlayerScreen`'s `subtitle_control` button + `SubtitlePanel` (Off + every declared rendition) → `applySubtitleSelection` on the existing player | ✅ | **Implemented 2026-10-06 (SPEC §15 "subtitles where available", DECISIONS 133).** Two derivation rules: a rendition is offered only when the media **declares a name for it** (manifest `NAME` else `LANGUAGE`) — MPEG-TS decoders always publish an unlabelled, language-less `application/cea-608` placeholder, and offering that would be a control that pretends to do something (AGENTS §5) — and the button is rendered **only while at least one such rendition exists**, so a media with none shows no control at all. Selecting re-enables the text track type and pins group + track with a `TrackSelectionOverride` built from the player's *current* tracks; **Off** disables the text track type and clears text overrides (Media3's own "no subtitles" switch), so the default renderer behaviour is restored rather than a track being forced. The choice is held per entry and re-applied when a source swap changes the tracks. `PlayerViewModel`, the stream reconstruction, navigation and the MediaSession are otherwise untouched. Evidence: `SubtitleStateDerivationTest` 13/13 (JVM, incl. `aTextTrackTheMediaNeverNamedIsNotOffered`), `PlayerSubtitleControlTest` 8/8 (device: control appears only with a declared rendition, panel opens/selects/announces), `SubtitleFixturePlaybackDeviceTest` 2/2 (device: real READY playback — select fetches `/subs.m3u8` + `/subs0.vtt`, Off leaves `disabled=[3]` / `overrides={}` / unselected, the choice survives a sibling switch, a no-rendition sibling publishes no control). **Boundaries:** no subtitle download, persistence, translation or custom rendering; the device evidence is a local fixture rendition rather than a real-CDN soft-subtitle title (LIMITATIONS §39, DECISIONS 133) |

### 11.2 Playback Controls

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Next/Previous | `stream.py:choice` | STREAM: `PlayerViewModel.playNextEpisode()` / `playPreviousEpisode()` behind the custom centre-controls row; DOWNLOAD: Media3's own skip buttons over the local playlist | ✅ | **Implemented 2026-10-05 (SPEC §15 "next episode" / "previous episode", DECISIONS 130).** In STREAM mode those two buttons are ours because Media3 derives a skip button's enabled state from the player's media-item playlist and a stream entry has exactly **one** media item: they are wired to the ViewModel's bounded neighbour switch, are rendered **disabled rather than hidden** at either end of the sibling list (the presentation Media3 itself uses when a playlist ends), and are suppressed while a switch is in flight so one tap cannot race two resolves. Their icons and content descriptions are Media3's own resources, so the row stays visually and semantically identical to the DOWNLOAD row, and everything else in that row — seek back, play/pause, seek forward — is still Media3's default `PlayerDefaults.CenterControls`. A refused switch (out of bounds, wrong mode, load in flight) never moves the navigation index and never touches download state. Evidence: `StreamEpisodeNavigationUiTest` 4/4 (bound + bounded + disabled-until-known + DOWNLOAD-ignores-the-sibling-list), `StreamPlaybackLifecycleTest` 6/6 (second resolve, new mediaId, index moved, bounds refused, download rows untouched), `PlayerLifecycleTest` 8/8 (local-path regression) — all device |
| Replay | `stream.py:choice` | `Player.seekTo(0)` | 🔄 | Different API |
| Episode select | `stream.py:choice` | `Player.seekTo(index)` | 🔄 | Different API |
| Audio toggle | `stream.py:choice` (`a` → `audio_pref` → playlist rebuilt) | **No equivalent player control** — audio is fixed at resolve time (row above) | ⚠️ | **Not applicable to the current single-track local-MP4 workflow** — *Phase 15 correction: this row previously claimed `TrackSelector`, which does not exist anywhere in `app/src/main`.* In the Phase 15-verified downloads the produced MP4 carried exactly one audio track, so there is no second in-player track to switch to; choosing the other language means resolving and downloading the other variant. Marked N/A rather than implying an Android player control exists. |
| Position persistence | Not implemented | `PlaybackState` | 📱 | Android extension — Phase 11 (**PHASE11_ARCHITECTURE_V2.md §29, Decisions 47+53: Room `PlaybackPositionEntity`, keyed by `episodeId`, rows persist indefinitely in v1**) — **implemented and tested**: `PlaybackPositionRepository` (upsert-on-save) is wired into `PlayerViewModel`, covered by `PlaybackPositionRepositoryTest` (§36) and used by `Phase11UiTestSupport`; Python has no equivalent. *(Stale "designed, NOT implemented" note corrected in 11.5E-5.)* |

**Three distinct mechanisms — do not conflate (Phase 15 audit, 2026-10-04):**

1. **Resolve-time audio selection — the only mechanism Android implements.** `quality` is applied while the stream is resolved (`AnimeDetailsViewModel.configuredQuality()` → `StreamResolverImpl` → `QualitySelector`); **audio is the effective per-number value** `explicitAudioOverrides[number] ?: selectedAudio` (Option C, DECISIONS 143): a direct resolve and a batch both request it (`effectiveVariantOf`/the batch-start snapshot), and a watchlist `any` entry resolves through `AnimeInfo.defaultVariant()` (`jpn`-else-first, the neutral bulk default). **Audio slice (2026-10-08):** the match is **exact and open-set** (entry `data-audio` == requested audio, no filter-fallback; an unsatisfiable request is an explicit `AudioSelector.unavailableMessage()` failure — SPEC `SPEC.md:920-923`), and there is **no global `audio_lang` setting** anymore (Python still has `main.py:84 --audio choices=["jpn","eng"]`; Android's `AppConfig` key is gone — DECISIONS 142). **Option C (2026-10-09) superseded the audio slice's `episode.audio`-on-direct-resolve / `selectedVariants[number]`-on-batch wording:** the screen-level context plus explicit per-override values are now the only audio state, and `AnimeInfo.defaultVariant` remains the initial-context and watchlist-only fallback. The selection **never adds a language upstream does not publish** (§1.4) and **never substitutes another audio silently** (§7 sweep: zero violations — `AnimeInfo.defaultVariant` is the only fallback-shaped code and it is SPEC-sanctioned).
2. **HLS audio-track composition.** The resolver picks **one** variant playlist per episode, and the assembled MP4 carries whatever that variant contains. **Verified scope:** the Phase 15-verified downloads (*Tomb Raider King* eps 1–2, physical device) each produced an MP4 with **exactly one video and one audio track**, confirmed by the app's own ffmpeg-kit log (`Video: h264 (avc1), yuv420p, 1920x1080` / `Audio: aac (mp4a), 48000 Hz, stereo, 65 kb/s`). This is a statement about **the tested output only** — no claim is made that every possible upstream variant yields a single-audio-track file.
3. **Media3 playback track selection — NOT implemented, NOT tested, NOT claimed.** There are zero occurrences of `TrackSelector`, `TrackSelection`, `TrackSelectionParameters` or `setPreferredAudioLanguage` in `app/src/main`; `ui/player` exposes transport controls only. Multi-track playback was therefore never exercised. Python's in-player audio toggle (`stream.py:choice`) and Android's resolve-time variant selection are **not identical mechanisms**; this is the exact difference SPEC §31 requires to be documented, not a parity claim.

---

## 12. Watchlist

### 12.0 Session management (Phase 12A data foundation)

SPEC groups Sessions with the watchlist, so the session side of Phase 12 is tracked here. Rows are written by the existing download pipeline (`DownloadRepository.ensureSession`); **Phase 12A added the read/summary/state surface, no schema change and no screen.** **Phase 12B verified that surface as *behavior* on a real Room database (instrumentation, no mock DAOs): `SessionRepositoryRoomTest` 5/5.**

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Session list | `sessions.py:SessionManager.get_sessions()` (one `session.json` per cache folder, `st_mtime` desc) | `SessionRepository.observe()` / `summaries()` (Room `updatedAt DESC`) | ✅ | Same ordering (newest first) and same title/`ep_count` fields; derived from the same rows the download engine writes — no second source of truth, no directory scan |
| Resume target | `sessions.py:SessionManager.run()` → `str(target["url"])` | `SessionRepository.resumeUrlOf()` = `https://<host>/anime/<uuid>` | ✅ | Reconstructed from the stored host + session UUID — the same URL format the parser produces |
| State transitions | `session.json` `state` field | `SessionRepository.setState()` (CREATED/DOWNLOADING/PAUSED/COMPLETED/FAILED) | ✅ | Unknown states are rejected (IllegalArgumentException) instead of being persisted unreadable; `SessionRepositoryTest.setState accepts the five declared states and rejects anything else` |
| Create/refresh | writing `session.json` | `SessionRepository.upsert()` / `updateMetadata()` | ✅ | Existing rows are updated in place (`@Update`) because `sessionDao.upsert` is `INSERT OR REPLACE`, which would fire `ON DELETE CASCADE` on `episode_downloads` and erase downloaded episodes — reviewer H1 fix, covered by `upsert of an existing session never deletes its episode rows` |
| Delete | removing the cache folder | `remove()` / `removeAll()` (cascade) | ✅ | Tested |

### 12.1 State Management

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| JSON persistence | `watchlist.py:WatchlistManager` | `WatchlistRepository` (`data/repository/WatchlistRepository.kt`) over `Room:WatchlistEntity` | ✅ | **Repository layer added in Phase 12A** (retires the previous "no repository layer yet"): `addOrUpdate` = `WatchlistManager.add_entry` (keeps `addedAt`, the downloaded history and the real title), `find` = `find_entry` precedence (1-based index → URL-parsed session → exact session/URL → case-insensitive substring) resolved against insertion order, `remove` = `remove_entry`, plus `markChecked` and `setDownloadedEpisodes` — 13 JVM cases in `WatchlistRepositoryTest` **and 6 on-device Room-backed cases in `WatchlistRepositoryRoomTest` (Phase 12B, real database, no mock DAOs)**. No JSON file exists to load, so a "session file" cannot silently vanish |
| Entry structure | `watchlist.py:WatchlistEntry` | `WatchlistEntity` | ⚠️ | Same fields, different audio semantics since the audio slice: Python's `audio_lang` is a **concrete** per-entry preference defaulting to `jpn` (`watchlist.py:158,619`) that feeds `get_variant` and a SUB/DUB display badge (`watchlist.py:321` — `jpn → SUB`, anything else → DUB); Android's `audioLang` defaults to the neutral **`"any"`** sentinel consumed via `AnimeInfo.defaultVariant()`, is displayed raw (`1080p · any`), and Android's badge is open-set (`jpn → SUB`, `eng → DUB`, else the raw value uppercased — StringUtils.audioBadge). DECISIONS 142 |
| Atomic write | `watchlist.py:save()` | Room single-row writes through `WatchlistDao` (`@Insert`/`@Update`) | ⚠️ PARTIAL | Every Phase 12A write is a single row, so SQLite makes it atomic and no partial-watchlist window exists today; the multi-row `Room.withTransaction()` wrapper is still not used — a future multi-row operation must take it before claiming this row ✅ |
| Corrupted handling | `watchlist.py:load()` | No per-entry JSON file exists (Room rows are schema-valid or absent); `WatchlistRepository.decodeDownloaded` repairs a corrupt `downloaded` JSON field to `[]` | ✅ | Python's corrupt-file recovery has no Android analogue because there is no file to corrupt; the equivalent failure (bad history JSON in a row) is covered by `corrupted downloaded json decodes to an empty list not an exception` (WatchlistRepositoryTest). Verified in Phase 12A data foundation; UI-level handling still belongs to the watchlist screen phase |

### 12.2 New Episode Detection

Implemented by **`domain/watchlist/WatchlistChecker`** (Phase 12C) — one `check()` per entry, driven from the Watchlist screen ("Check now"), by batch auto-download and, since Phase 12D, by the background `WatchlistCheckWorker` (DECISIONS 91). Table below is verified against `watchlist.py:685-1050`.

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Force fresh scan | `watchlist.py:732,769-771` (`cache_ttl_watch = 0`) | `WatchlistChecker` calls `EpisodeRepository.scan(..., cacheTtlMinutes = 0)` | ✅ | Every check bypasses the scan cache so a just-published episode is seen. Behaviorally exercised end-to-end by `new episodes are detected and queued with the entry's own preferences`; **no test pins the TTL value itself** (disclosed, not claimed) |
| Existing detection | `watchlist.py:1000-1026` (variant per number, skip if already handled) | `WatchlistChecker` + `EpisodeDownloadQueue.isEpisodeAlreadyHandled` | ✅ | Handled = output present **or** row `RESOLVING`/`DOWNLOADING`/`ASSEMBLING` **or** `QUEUED` with a resolved stream; `FAILED`/`CANCELLED`/`QUEUED`-without-stream are **not** handled and are retried (Python's `run()` re-queues them too); JVM: `an episode the pipeline already handles is skipped before resolving`. **History backfill is a stricter, separate check**: `isEpisodeOutputPresent` (row `COMPLETED` **and** output present) reproduces Python's `_find_existing` (`watchlist.py:1015-1019`), so a handled-but-in-flight row is skipped *without* entering the deleted-skip history — otherwise a later `FAILED`/`CANCELLED` retry would look deliberately deleted (review finding H-1; JVM: `an in-flight download is skipped but never enters the deleted-skip history`) |
| Deleted skip | `watchlist.py:1021` (`folder_exists and num in downloaded`) | `WatchlistEntity.downloaded` + output lookup | ✅ | History number present **and** folder present **and** output absent → counted as skipped/deleted and **never re-downloaded** (`an episode deleted on purpose is skipped and never re-downloaded`) |
| Folder reset | `watchlist.py:987-996` (`if not folder_exists and downloaded_set: downloaded_set.clear(); entry.downloaded = []`) | `WatchlistChecker` (`if (!folderExists && history.isNotEmpty()) history.clear()` + `setDownloadedEpisodes(emptyList())`) | ✅ | **Identical rule, identical granularity, identical order**: both sides clear *that entry's* `downloaded` list as a whole when the series folder is gone (neither side prunes number-by-number), no other entry is touched, and the check runs after the title refresh in both. `missing download storage resets the skip history so everything is pending again` |
| Scan failure | `watchlist.py:767-782` (scan exception → console `✗ Scan failed:`, counted, `last_checked` saved, `continue`) | `check()`'s catch → `FAILED (the real exception message)`, `last_checked` persisted | ✅ | Same isolation, same persistence, same "other entries still run" behaviour (`a scan failure is isolated and still persists last_checked`, `one broken entry never stops the run over the healthy ones`). **Distinct from the row below** — a scan failure never carries the "Search unreachable" text |
| Unreachable search during recovery | `watchlist.py:936-950` (search exception → console `✗ … auto-migrate failed:`, counted) vs `watchlist.py:92-93` (a *successful* empty search → `"none"` → `no session found`) | `findCandidates` → `null` **only when no host answered** → `FAILED ("Search unreachable — could not look up a replacement session.")`; an *answered* empty result → `LINK_DEAD ("… no session found …")` | ✅ | The two outcomes are never conflated: `AnimeRepository.searchWithOutcome` tracks `Unreachable` vs `Results(empty)`, which the reference's plain candidate list cannot express; JVM: `an unreachable search is reported as FAILED, never as no session found`, `a dead link with no matching session is reported without touching the entry` |

### 12.3 Dead Link Recovery

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Title search | `watchlist.py:59-94` (`_find_single_new_session`) | `WatchlistChecker` normalized-title search | ✅ | 404/dead session → recover title from cache (`ScanCacheRepository.cachedTitle`) → normalized search → **exactly one** match proceeds, 0 = `UNKNOWN_TITLE`, ≥2 = `AMBIGUOUS` — same three-way outcome as Python (`a dead link with no matching session is reported without touching the entry`, `two exact-title candidates are never auto-migrated`, `a dead link with an unusable title and no cache is an honest unknown-title failure`, `the stored scan cache supplies the title the dead link needs`) |
| Auto-relink | `watchlist.py:97-118` (`_relink_entry_via_search`) | `WatchlistRepository.relink` (delete old row + upsert same entry under the new session) | ✅ | `addedAt`, `downloaded` history, preferences and title survive the move; the checker **re-reads the row from Room first**, so a stale caller snapshot cannot wipe history (`a dead link with exactly one matching session is relinked and rescanned`, `a migrated session that is still dead keeps the new link and reports the error`) |
| Manual relink | `watchlist.py:377` (`cli_relink`) | `WatchlistRepository.relink` + per-row **Relink** control on every Watchlist entry | ✅ | The mechanism and the user-facing control both shipped (relink control in Phase 13, 2026-10-02); there is no CLI on Android (documented divergence below). Interaction contract per DECISIONS 108: per-row Relink exposed on every entry (no persisted per-entry status exists, so status-gating would need a rejected schema change), candidate search shared with the automatic path (`WatchlistChecker.findCandidates`), 0/1/n-candidate dialog with explicit confirm, success → `relink` → standard check rescan, migrated-still-dead keeps the new link — evidence `WatchlistScreenTest` 11/11 (device), JVM `WatchlistRelinkTest` 7/7, `Phase13ResponsiveTest` 12/12. **Documented divergences:** Python's global relink-all mode and the `new_url` parameter are not ported — zero-candidate recovery remains remove-and-re-add-by-URL (PARITY §12.5) |
| History preservation | `watchlist.py:entry.downloaded` | `WatchlistEntity.downloaded` | ✅ | Preserved across relink and across folder-reset rules |

### 12.4 Background / scheduled checks (Phase 12C)

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Automatic periodic check | `watchlist.py:run_watchlist_check` (`:685`) invoked by Python's auto/watchlist mode | **WorkManager periodic worker** `WatchlistCheckWorker` → `WatchlistChecker.checkAll()`, registered once by `WatchlistCheckScheduler` (`watchlist_periodic_check`, 15 min, `ExistingPeriodicWorkPolicy.KEEP`, `NetworkType.CONNECTED`) from `PaheBatcherApplication.onCreate`; the foreground `Check now` (`WatchlistViewModel.checkNow`) remains for immediate runs | ✅ | Shipped **Phase 12D** (DECISIONS 91). No Activity is needed: the request path is the existing **application-scoped** WebView (`AndroidCloudflareSession` builds `WebView(applicationContext)` on the main looper and never attaches it to a window), reached through `WebViewHttpClient` behind one shared page lock (`AppContainer.webViewPageLock`) also held by `StreamResolverImpl`. The WorkManager-only constraint is respected — **no** AlarmManager, no custom timers, no foreground service, no daemon, no second WebView or session (AGENTS §13). Verified on `REDACTED-SERIAL`: `count=1 state=[ENQUEUED]` on cold start and again after a process kill, exactly one JobScheduler job, and a run captured with the launcher resumed that issued three real `animepahe.com` requests and ended `Worker result SUCCESS`, plus a further **unforced** periodic run at 20:31:22 with the launcher resumed and no PaheBatcher Activity (`.investigation/phase12d/device_12d_verify.txt`). Residual: periodic fire is inexact (observed spacing ~18 min for a 15-minute request) and no interval setting exists — LIMITATIONS §33.2 |
| Check idempotence / bounds | `watchlist.py` runs one check at a time | `WatchlistViewModel.checkNow` for the manual path — re-entrant-guarded, sequential, dies with the screen — and `WatchlistCheckWorker` for the scheduled path (one unique periodic work, `ExistingPeriodicWorkPolicy.KEEP`, so only one instance can ever exist) | ✅ | One coroutine at a time per caller; **`last_checked` lands in Room** (`markChecked`, **including on failure**) so the *fact* that a check ran survives process death. The per-entry **result** itself is held in this session's `results` state (rendered by `WatchlistRow.lastResult`, §12.4) — the reference likewise writes only `last_checked` to its JSON and keeps per-entry summaries in the console. Nothing pretends otherwise |
| Auto-download of new episodes | `watchlist.py:1002` (`anime.get_variant(num, entry.audio_lang)`), staging `resolve_ahead + max_parallel` (`downloader.py:205-208`) | `WatchlistChecker` → `entry.audioLang == "any"` → `AnimeInfo.defaultVariant()` (`jpn`-else-first) / a concrete value → `AnimeInfo.getVariant(number, entry.audioLang)` **strict, skipped with a recorded reason when absent** → `ResolveAheadGate.awaitCapacity` → `DownloadRepository` | ✅ | Per-entry audio, per-entry quality, the same staging capacity as a batch; a capacity timeout is reported as `FAILED` for that entry, never silently dropped. **Audio slice (2026-10-08):** new entries persist the `audioLang = "any"` sentinel (rendered live as `1080p · any`, survived a force-stop in the 13-scenario gate); a concrete legacy value is exact-match-or-skip — an unavailable concrete audio is a recorded skip, never a substitution (`WatchlistCheckerTest` JVM, device `WatchlistCheckWorkerTest`). *(Note: the `prefer_audio=entry.audio_lang` argument passed to `scanner.scan` at `watchlist.py:771,857` is a **dead parameter** in Python — `extract/scanner.py:202` declares it and never reads it; Android's `EpisodeRepository.scan` therefore takes no audio argument either, which matches Python's effective behaviour, PARITY §12.2/§9.2)* |
| Result reporting | Python console output | `WatchlistRow.lastResult` (`UP_TO_DATE` / `NEW_EPISODES …` / `LINK_DEAD` / `UNKNOWN_TITLE` / `AMBIGUOUS` / `FAILED …`) rendered per row | ✅ | Produced from that entry's real outcome, never a canned string |

### 12.5 Watchlist/batch surface (Phase 12C — **Minimal Phase 13 prerequisite surface**)

Phase 12 needs a way to *exercise* detection, recovery and batch semantics, so a bounded slice of Phase 13 shipped with it: a `WatchlistRoute` list screen (add by URL, per-entry result, remove, `Check now`) reached from a **Home top-bar icon**, plus the Anime Details **batch selection controls** (top-bar toggle, a batch panel that scrolls as the first item of the episode list, per-number checkboxes, a single ordinal range control `From`/`To` with an unknown-total fallback, a selection summary, live counters, honest error/summary) that carry the Phase 12C batch behavior. *(Phase 12 UX correction, DECISIONS 155: the older duplicate expression range field was removed — `From`/`To` is now the single range control — and the batch panel moved into the episode `LazyColumn` so it can never starve the list of height; `PageBar` stays pinned above it.)* *(Phase 12 UI review, DECISIONS 156: `Select all` relabelled from `All` so its selection-only role is explicit, and when the selection is exactly the current page the `Download selected` CTA is hidden so the two download buttons are never presented as different when they target the same set; `PageBar`'s Go-to-page visibility now derives from the number strip's measured width (adaptive to width/font scale) and is hidden when every page is a visible button.)* Deliberately NOT included: MediaSession, Media3 redesign, WorkManager redesign, notifications, analytics, cloud sync, remote logging, a second config store, a Sessions screen, theming work, or any other Phase 13 screen. Navigation invariant preserved: the documented 2-tab bottom bar is untouched — `HomeScreen.onOpenWatchlist` is a defaulted parameter placed after `modifier`, so all 5 existing androidTest call sites compile unchanged.

**`Stop batch` semantics (review finding L-9, previously undocumented):** an Android-only affordance with no reference equivalent — Python's batch is Ctrl-C/EOF-driven and exposes no in-flight stop control — verified by `cancelling a batch stops the loop without inventing a result`. Pressing it cancels the submit loop: rows **already handed to WorkManager keep running** (they are independent, resumable downloads — not retracted, not requeued, and **not** counted as failed or skipped), the queued/skipped/failed counters freeze at their last published value, and numbers not yet submitted are simply never attempted. The counters therefore describe *the loop's own work*, not the fate of every selected number.

---

## 13. UI Features

> **Scope correction (11.5E-6 — the previous blanket "none of the screens exist / only a static home screen" claim was stale):** production now ships real destinations for Home, Search, Anime Details, Downloads, Player and Settings (`AppNavHost`), plus `ValidationScreen`, and search → resolve → single-episode download → MP4 → playback is device-verified end-to-end (§5.1). **Phase 12C then shipped the minimal prerequisite slice** — multi-episode (batch) selection by episode number on Anime Details and a Watchlist list screen (§12.5) — so those two are no longer "pending Phase 13" items. **Phase 13 scope, restated 2026-10-01 after the first Phase 13 architecture review (DECISIONS 92-102; authoritative inventory in `ROADMAP.md` Phase 13):** a Sessions screen (SPEC §20 actions per DECISIONS 94 — Pause intentionally unsupported **at that time; superseded 2026-10-05 by DECISIONS 129**, which reinstated `STATE_PAUSED` and shipped Pause/Resume on both the Sessions and Downloads screens), the manual relink control (§12.3), batch-lifecycle navigation UX + its six specified tests (DECISIONS 93), offline indication via an app-scoped `NetworkMonitor` (DECISIONS 97), the output-location/SAF row + SAF export target (DECISIONS 92), the Wi-Fi-only and notification-permission settings rows and the `POST_NOTIFICATIONS` runtime prompt at enqueue (DECISIONS 95/96), and the cross-cutting Material 3/accessibility/error-state/`@Immutable` completion (DECISIONS 98). **Reassigned out of Phase 13:** media session and all notification content (completion/failure/watchlist/channels) → **Phase 14** (DECISIONS 95); auto-resume → Phase 14 (DECISIONS 96; both Phase 14 assignments shipped 2026-10-03 - DECISIONS 113-121); the settings wizard, `cli_set` and `cookie_string` → **intentional divergences, no phase** (DECISIONS 96); SPEC §24 suggested screen #12 "Web/session authentication screen" → **intentional N/A**, session handling is automatic behind the WebView abstraction (DECISIONS 102). Functional engine settings shipped in Phase 12A (§9.5) and were extended in 12C; the watchlist/session data layer shipped in 12A (§12.1). Individual rows below carry their own current status. **Final pre-approval review (2026-10-01) findings N-1…N-10 settled in DECISIONS 103-112:** revoked/user-deleted SAF exports follow §29 FAILED + explicit-Retry recovery with no resurrection on re-grant (103), the SAF export temp contract is `.pb.part` with a rename-less MediaStore fallback and a crash matrix (104), Wi-Fi-only is enqueue-time only (105), Delete-session/Clear-all are terminal-only (106), cache size counts temporary artifacts only (107), the manual relink contract is fixed (108), the session display-state precedence table is complete (109), Sessions launches from a Downloads top-bar action (110), responsive verification of all new surfaces is a Phase 13 exit criterion (111), and the four stale historical scope statements are annotated (112).** **Phase 13 shipped 2026-10-02:** every item in the restated scope above is implemented and verified (Sessions screen, manual relink, batch-lifecycle UX + its six tests, offline indication, output-location/SAF row + SAF export target, Wi-Fi-only and notification-permission settings rows with the first-enqueue prompt, cross-cutting completion) — `@reviewer` APPROVED and `@tester` PASS; per-item evidence in the ROADMAP Phase 13 exit criteria.

### 13.1 Terminal UI (Rich)

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Banner | `ui/console.py:print_banner()` | `SplashScreen` | 🔄 | Compose |
| Episode table | `ui/tables.py:episode_table()` | `EpisodeListScreen` | 🔄 | LazyColumn |
| Search table | `ui/tables.py:search_results_table()` | `SearchResultsScreen` | 🔄 | LazyColumn |
| Summary table | `ui/tables.py:summary_table()` | `DownloadSummaryScreen` | 🔄 | Compose |
| Progress bar | `ui/dashboard.py:Dashboard` | `DownloadsScreen` (rows mapped by `DownloadRowMapper`) | 🔄 | LinearProgressIndicator for the bar itself (terminal table vs Compose rendering). *Android-equivalent corrected 2026-10-05: no `DownloadProgressScreen` symbol exists anywhere in `app/src/main` — the dashboard row is `DownloadsScreen`.* **SPEC §25 metrics now render on that row (final SPEC gap closure, DECISIONS 128):** downloaded bytes, speed, ETA, completed/total segments, percentage and `retries: N`, all read from the persisted Room row — `ProgressTracker` measures a bounded 10-second window and ETA is remaining bytes over that measured rate, the mapper surfaces speed/ETA **only while the row is really DOWNLOADING** (a queued, paused or finished row can never render a stale `B/s` or `0:00`), and the progress notification keeps showing percentage only. Evidence: `ProgressMetricsTest` 13/13, `ProgressThrottlingTest` 6/6, `DownloadRowFormatTest` 8/8, `DownloadRowMapperTest` 22/22 (JVM) and `DownloadMetricsDeviceTest` 1/1 (device: persisted bytes equal the bytes actually served, rate present only while downloading). **SPEC §26 action errors (final audit H5, 2026-10-07, DECISIONS 139):** a thrown Cancel/Retry/Pause/Resume is no longer silent - `DownloadsViewModel.actionError` publishes a URL-sanitized message (never a `://`), the screen renders a dismissible banner (`downloads_action_error`, polite live region) and logs via `AppLog.e`, so LIMITATIONS §24 R2 is RESOLVED. Evidence: `DownloadsViewModelActionErrorTest` 5/5 (JVM) + `DownloadsScreenActionErrorTest` 2/2 (device). **Persisted audio label on the row (Slice B, DECISIONS 146):** the episode line now reads `Episode N · <audio>` using the ROW's OWN persisted `audio` column — never the screen's current audio selection — because the SUB and DUB downloads of one episode are two distinct rows that were rendered identically. Mapping is `jpn` → "Japanese", `eng` → "English", any other non-blank code echoed uppercased (SPEC §22 is an open set — never guessed at, never collapsed to SUB/DUB), and blank/NULL shows no label at all rather than a default. No new DB field, no Settings option, no change to title/status/progress/actions/semantics or to the row's layout. Evidence: `DownloadRowMapperTest` 26/26 (JVM) + `DownloadsRowAudioLabelTest` 5/5 (device) |

### 13.2 Interactive Prompts

| Feature | Python Location | Android Equivalent | Status | Notes |
|---------|-----------------|-------------------|--------|-------|
| Episode selection | `ui/prompts.py:select_episodes()` | `AnimeDetailsScreen` batch panel (Phase 12C): per-**number** checkboxes, Select all / Clear, `EpisodeRangeParser` range field, live queued/skipped/failed counters, `Stop batch`, honest summary/error | ✅ | Selection is by episode number, and since Option C (DECISIONS 143, superseding DECISIONS 142's row-tap audio switch) a row tap toggles ONLY the number: audio is separate screen-level state — the list renders one row per number for the current context, an explicit per-number override is written through that row's own `Change audio` control, and the batch derives `override ?: context` per selected number at start — matching Python's by-number selection; single-episode selection resolves the same effective variant outside batch mode. **SPEC §31 "latest-N selection" — CLOSED 2026-10-05 (final SPEC gap closure, DECISIONS 127):** Python's `latest` mode (`--latest N` / the `N` key, `main.py:75,347-348`) now has a real UI entry point: the batch panel's **Latest N** field and **Latest** button (testTags `anime_details_batch_latest` / `anime_details_batch_latest_apply`) call `AnimeDetailsViewModel.selectLatestEpisodes(n)`, which feeds the SAME `EpisodeRangeParser.selectEpisodes(episodes, "latest", latestN)` and writes the SAME `selectedNumbers` state as **All** and the range field — so the count line, the counters and the batch pipeline are unchanged. Guards: a running batch disables the control, a count below 1 is rejected instead of inventing episode 1, and an empty catalogue reports the honest no-match message. *The previous wording of this disclosure ("no UI entry point", "production code calls only `"all"` and `"range"`") is superseded: the three production call sites are now `AnimeDetailsViewModel.kt:1211,1241,1284`.* Evidence: `EpisodeRangeParserTest` 16/16 and `AnimeDetailsViewModelBatchTest` 21/21 (JVM, full 941-green suite of 2026-10-08), `AnimeDetailsBatchScreenTest` 15/15 (device, isolated re-run in portrait; 2 new exact-pair highlight tests were added by the audio slice and one old render-contract test was INVERTED, never deleted). **Option C (2026-10-09) evidence:** the `selectedVariants` store and its two exact-pair highlight tests are superseded — the render tests now pin the context-row contract (one row per number; rows for other-audio variants never appear as separate entries), `AnimeDetailsViewModelBatchTest` 21/21 covers number-only row taps and context bulk, and `AnimeDetailsAudioSelectionTest` 25/25 covers the selector/override/notice/lifetime matrix in the full **966-green** JVM suite of 2026-10-09; on device `AnimeDetailsBatchScreenTest` 14/14, `AnimeDetailsAudioScreenTest` 9/9 and `AnimeDetailsAudioRecreationTest` 1/1 are green (the recreation test green IN the full 484-test suite run, not just isolated) |
| Range parsing | `ui/prompts.py:_parse_ep_range()` | `EpisodeRangeParser` | ✅ | Same logic (now reachable from the batch panel's range field) |
| Download confirm | `ui/prompts.py:confirm_download()` | **No dialog** — the Anime Details `Download` button (Phase 7) and the batch panel's `Download selected (N)` button (Phase 12C) *are* the confirmation | ⚠️ | *Correction in Phase 12C: the previously cited `DownloadConfirmDialog` does not exist anywhere in `app/src/main` (grep) — Android confirms by explicit action rather than by a second prompt.* |
| Settings wizard | `ui/prompts.py:wizard_config()` | `SettingsScreen` exists (Phase 11) but no wizard flow | ⚠️ INTENTIONAL DIVERGENCE | Stale-note correction (11.5E-5 review): the screen exists; wizard-style config prompting still has no equivalent. **Reclassified 2026-10-01 (DECISIONS 96):** SPEC §23 mandates a settings *screen* (shipped), not a first-run wizard; `SettingsScreen` covers every field the Python wizard prompts for, so a wizard would be a redundant second flow — deliberate divergence, not pending work |

---

## Summary

> **Scope statement (corrected 11.5E-6 — the previous pre-Phase-11 wording was stale):** Phases 1–10 built the download/resolution/background-download infrastructure, and Phases 11.5E-3…E-7 wired the production entry point *(scope range corrected 2026-09-28, reviewer L-5: previously "E-3…E-5", which under-counted — E-6 added the submission guard + pre-download output short-circuit and E-7 added the Retry re-resolution, both entry-point behavior)*: Home search → Anime Details → stream resolution → single-episode download → assembled MP4 → MediaStore export → playback, device-verified end-to-end (§5.1). The APK **is** a working single-episode downloader; it is not yet a *complete* one — **Phase 12C shipped multi-episode (batch) selection by episode number (SPEC §22) and the watchlist check/relink screen (§12, §12.5)**, both device-verified, while functional settings remain partial (SPEC §23, §9.4 — `cookie_string`/`output_dir` still deferred), scheduled background watchlist checks now run as a WorkManager periodic job over that same application-scoped WebView transport (shipped 12D; LIMITATIONS §33.2 was rewritten from BLOCKER to RESOLVED), and the remaining §24 retry/queue findings stay open *(11.5E-7 resolved finding 1: Downloads Retry now re-resolves a fresh stream; findings 2–3 and R3-R6 remain (R2 resolved 2026-10-07 by final audit H5; R1 resolved 2026-10-13 by the per-host limiter))*. See §5.1 (pipeline wiring), §12 (watchlist), §13 (scope correction), and LIMITATIONS.md §14/§24/§33. *(Update 2026-10-02: `output_dir` shipped as the Phase 13 SAF export target and `cookie_string` is an intentional divergence with no phase (DECISIONS 96) — functional settings remain partial only for the Phase 14 rows (auto-resume, notification channel preferences) *(both shipped in Phase 14, 2026-10-03 - PARITY rows 603/604, DECISIONS 113-121; this clause now records the historical Phase 13 state)*.)*

**Feature-count table below is a SNAPSHOT THROUGH PHASE 11.4** — it was not re-aggregated for the Phase 11.5 rows or the corrections listed in the footnote (reviewer N-5). Do not quote "59% parity" from it as the current figure.

| Category | Total Features | ✅ Direct Port | 🔄 Different Impl | ⚠️ Platform Diff | 📱 Extension | ❌ Not Implemented |
|----------|----------------|----------------|-------------------|------------------|--------------|------------|
| Search | 4 | 2 | 2 | 0 | 0 | 0 |
| Metadata | 4 | 1 | 3 | 0 | 0 | 0 |
| Discovery | 4 | 4 | 0 | 0 | 0 | 0 |
| SUB/DUB | 4 | 4 | 0 | 0 | 0 | 0 |
| Resolution | 8 | 6 | 2 | 0 | 0 | 0 |
| HLS Parsing | 9 | 9 | 0 | 0 | 0 | 0 |
| Decryption | 5 | 4 | 0 | 1 | 0 | 0 |
| Downloading | 13 | 8 | 1 | 4 | 0 | 0 |
| Segment Store | 12 | 11 | 1 | 0 | 0 | 0 |
| Caching | 9 | 7 | 0 | 0 | 0 | 2 (SolverCache) |
| Sessions | 11 | 5 | 2 | 0 | 2 | 2 (curl_cffi) |
| Configuration | 12 | 9 | 0 | 2 | 0 | 1 |
| Storage | 5 | 2 | 1 | 0 | 0 | 2 |
| Playback | 10 | 0 | 7 | 3 | 0 | 0 |
| Watchlist | 8 | 2 | 0 | 0 | 0 | 6 |
| UI | 8 | 0 | 8 | 0 | 0 | 0 |
| **Total** | **126** | **74 (59%)** | **27 (21%)** | **10 (8%)** | **2 (2%)** | **13 (10%)** |

*Counts re-aggregated during the pre-Phase-11 documentation reconciliation to reflect the corrected rows: AES decryption (✅→⚠️), `max_parallel` (✅→⚠️), two-stage pipeline rows (§5.1: 2×✅→⚠️, 1×✅→🔄, 1×✅→⚠️), and `SettingsScreen` rows (2×🔄→❌). Rows corrected only for symbol names (e.g. `SearchMapper`→`AnimePaheParser.parseSearchResults()`) do not change statuses or counts. Phase 11.5E-5 row corrections (§5.2 index base ❌→🔄, §5.3 permanent-skip ✅→⚠️, §6.1 existence check ❌→✅, §6.2 real-playlist assembly ❌→✅, §10.2 sanitization ⚠️→✅, plus stale-note fixes in §9.1/§10.1/§13.2) are reflected in the rows themselves; the table above predates the Phase 11.5 row additions (device-download rows etc.) and was **not** re-aggregated in this slice. Phase 11.5E-6 corrections (§3.1 IV ✅→⚠️, §4.1 IV ✅→⚠️, §5.4 pooling ✅→⚠️, §7.2 cross-episode AES sharing ✅→⚠️, §6.3 episode-cleanup ✅→🔄 + orphan-cleanup ✅→🔄 + keep_temp ✅→⚠️, new rows in §5.1 and new §9.4) are likewise reflected in the rows themselves and **not** re-aggregated here. Phase 11.5E-7 added no row-status changes to this table; the 2026-09-28 reconciliation (reviewer H-1) downgraded §9.4 "Auto-resume downloads" ✅→⚠️ in the row itself, also **not** re-aggregated here.*

**Phase 7 Implementation Update (COMPLETE)**:
- M3U8 parser: IMPLEMENTED (HlsPlaylistParser — master + media, EXT-X-KEY, IV with 128-bit fix)
- AES-128 decryption: IMPLEMENTED (`Aes128Decryptor` - raw CBC via `AES/CBC/NoPadding`, empty-input guard; the PKCS#5 unpadding step was removed 2026-10-13 for Python parity)
- Segment download engine: IMPLEMENTED (EpisodeDownloader — dual-level retry matching Python)
- Concurrent segments: IMPLEMENTED (Semaphore-bounded, 24 workers default)
- HlsClient HTTP abstraction: IMPLEMENTED (getText + getBytes, OkHttpHlsClient)
- AesKeyCache: IMPLEMENTED (LRU + TTL, maxSize=128, ttl=3600s)
- SegmentFileStore: IMPLEMENTED (atomic .tmp → .ts, caller-supplied directory)
- Live CDN validation: **VERIFIED (Phase 11.5E-4, production APK on device)** — `AesKeyCache: Cached key …/mon.key (16 bytes)`, `Playlist: 146 segments, 0 completed, 146 pending`, `Download complete: success=true, total=146, completed=146, failed=0`, 146 atomic `.ts` writes, `tmp=0`, resume `146 completed / 0 pending`. The former blocker was a missing `Referer` header, not unavailable infrastructure (LIMITATIONS §2B, Decision 72)

**Phase 8 Implementation Update (COMPLETE)**:
- Orphan cleanup: WIRED (cleanupOrphans() called at start of EpisodeDownloader.downloadOnce())
- Crash recovery tests: ADDED (5 new tests — partial resume, stale tmp recovery, interrupted state, full resume, unrelated file preservation)
- Atomic writes: VERIFIED (Phase 7 + Phase 8 tests)
- Resume behavior: VERIFIED (completed segments preserved, only missing segments downloaded)

**Phase 9 Implementation Update (COMPLETE)**:
- EpisodeAssembler interface: IMPLEMENTED (domain/hls/)
- FfmpegEpisodeAssembler: IMPLEMENTED (data/hls/) — concat demuxer primary + temp-file fallback (Decision 79)
- FileNameGenerator: IMPLEMENTED (output naming matches Python: `Ep {prefix} - {title}.mp4`)
- ConcatFileGenerator: IMPLEMENTED (FFmpeg concat demuxer input format)
- Assembly integrated into EpisodeDownloader: WIRED (after segment download, before cleanup)
- ABI detection: IMPLEMENTED (arm64-v8a + x86_64 supported; armeabi-v7a/x86 returns clear error)
- Atomic output: IMPLEMENTED (.tmp → rename on success; .tmp cleanup in finally block)
- Timeout: IMPLEMENTED (configurable, default 600s matching Python)
- Existing output skip: IMPLEMENTED (skip if file exists and size > 0)
- Assembly failure classified as permanent (no fruitless episode retry)
- Pipe fallback: Android adaptation (temp-file concatenation, NOT stdin pipe) — see Decision 79
- Assembly tests: 32 Phase 9 tests (FileNameGenerator: 16, ConcatFileGenerator: 6, FfmpegEpisodeAssembler: 6, EpisodeDownloader assembly integration: 4)
- All 361 tests pass, 1 pre-existing failure unrelated to Phase 9
- Real FFmpeg execution: VALIDATED on x86_64 emulator (emulator-5554, API 36)

**Phase 10 Implementation Update (COMPLETE — @tester gate PASS, cycle 3)**:
- WorkManager 2.11.2: VERIFIED compatible with AGP 9.4.1, Gradle 9.7.1, Kotlin 2.4.20, compileSdk 37, JDK 25
- HlsTextResult/HlsBytesResult: IMPLEMENTED (typed results with HTTP status codes)
- HlsClient interface: UPDATED (returns HlsTextResult/HlsBytesResult instead of String?/ByteArray?)
- OkHttpHlsClient: UPDATED (returns typed results with HTTP status codes)
- EpisodeDownloader: UPDATED (handles typed results, distinguishes permanent 403/404 from transient 5xx errors, wasCancelled field, correct output path)
- Room migration v1→v2: IMPLEMENTED (drops segments table, adds m3u8Url/userAgent/referer/segmentDir columns) + TESTED (MigrationTest 3 tests on device; Room schema JSON exported via KSP `room.schemaLocation`)
- EpisodeDownloadEntity: UPDATED (new columns, STATE_PAUSED removed, canResume() fixed)
- EpisodeDownloadDao: UPDATED (countActive, clearStreamInfo, updateStateForResume, getNextQueuedWithM3u8, getEpisodesForReconciliation, getTotalEpisodeCount, saveStreamInfo, updateError without retryCount increment; 11.5E-7: `beginRetry` atomic terminal-row claim + `updateErrorIfState` CAS error write that never converts a cancellation into a failure; 11.5F: `updateCompletedIfState` CAS completion write used by the Worker so a lost claim never stamps COMPLETED over a newer state). **Dead/test-only members reconciled in Phase 11.5 item (c), 2026-09-28**: `getActive()`, `deleteBySessionId()`, `countBySessionId()` (zero callers in `src/`) and `incrementRetryCount()` (test-only) were **DELETED**, together with `StateTransitionTest.incrementRetryCount_increments_exactly_once` — whose coverage is already provided by `beginRetry_claims_a_terminal_row_exactly_once`. `DownloadSessionDao.getActive()` (a further zero-caller member found during execution) was deleted in the same pass. `delete(episodeId)` was **RETAINED by explicit disposition**: it is the only direct row-removal primitive and **two** tests depend on it for cross-test fixture draining — `TriggerNextDeferredEpisodeTest.kt:90` **and** `TotalSegmentsPersistenceTest.kt:79` (the second added after reviewer m-6) — so deleting it would require a less-clear substitute rather than remove a dead path; it is retained as *generic CRUD*, which is a different reason from `incrementRetryCount()`, a *domain path* whose Worker wiring never existed. Note also that V10 §12 designed `incrementRetryCount` to be called by the Worker's retry path — that wiring never happened and the symbol no longer exists; production `retryCount` increments only via `beginRetry` (user Retry), so the `retries: N` UI counter does **not** count automatic attempts
- DownloadConfig: IMPLEMENTED (maxParallel=3, hlsWorkers=24, progressThrottleMs=5000ms; dead notificationChannelId/notificationChannelName fields removed — corrective F-L2)
- DownloadNotificationManager: IMPLEMENTED (IMPORTANCE_LOW channel download_progress, progress notifications; app-owned vector small icons — corrective Finding 8; unconditional channel creation — corrective F-L3). **Phase 11.5 item (c), 2026-09-28: `buildCompletionNotification` / `buildFailureNotification` DELETED as dead code** (zero production call sites) together with their two `NotificationTest` cases and the now-orphaned `ic_stat_download_done` / `ic_stat_error` vectors; only the progress half is wired today. Phase 14 must re-create them when it wires completion/error notifications (ROADMAP Phase 14 rows updated in the same slice) ***(superseded 2026-10-03: both builders and both ic_stat vectors were re-created and production-wired in Phase 14; `NotificationTest` green on device - LIMITATIONS 8 bullet, DECISIONS 113-121)***
- DownloadRepository: IMPLEMENTED (enqueue, cancel with persisted CANCELLED state, reEnqueue with ExistingWorkPolicy.KEEP; **11.5E-7 — §24.1 fixed**: `reEnqueue` from FAILED/CANCELLED/completed-without-output claims the row (`beginRetry`), re-resolves a FRESH stream through the production resolver and enqueues the Worker only from a CAS-guarded `resolveComplete` — matching Python's re-resolve-on-every-attempt (`downloader.py:233-275`), **one attempt per user Retry** with no `auto_retry` backoff loop ported (LIMITATIONS §24.3)); **final audit M1 (2026-10-07):** `cancel()` no longer claims a terminal row unconditionally - `cancelIfNotTerminal(episodeId, timestamp)` CAS-flips to CANCELLED only from a non-terminal state (always cancels the unique Work; returns whether it claimed), closing the LIMITATIONS §27 blind-cancel residual (DECISIONS 139)
- DownloadWorkerFactory: IMPLEMENTED (custom WorkerFactory for dependency injection)
- DownloadWorker: IMPLEMENTED + CORRECTED (CoroutineWorker + setForeground(FOREGROUND_SERVICE_TYPE_DATA_SYNC); maxParallel gating before DOWNLOADING transition; ProgressTracker with single bounded flush coroutine — no runBlocking, no per-callback coroutine launches; NonCancellable terminal write on cancellation; next deferred episode triggered on EVERY completion — success, failure, cancellation, exception — via try/finally under NonCancellable, corrective F-C2; **11.5F — state-write discipline**: entry is an atomic CAS claim (`claimEntryState` accepting QUEUED/DOWNLOADING/ASSEMBLING, losers exit with zero mutation), every failure write is `updateErrorIfState(DOWNLOADING)`, the cancellation branch is CAS `DOWNLOADING -> CANCELLED` under `NonCancellable` + rethrow, completion is `updateCompletedIfState(DOWNLOADING)` with the export skipped on a lost claim, and an escaping non-CE exception persists a sanitized guarded FAILED (LIMITATIONS 27 FIXED, DECISIONS 77)); **final audit H4 (2026-10-07):** assembly now writes its phase - `EpisodeDownloader.download(..., onAssemblyStart)` CAS-flips DOWNLOADING -> ASSEMBLING before the assembler runs, every post-download write is conditioned on the claimed state via `expectedWriteState()`, and assembly-failure messages carry `result.error`; device-proven by `DownloadWorkerAssemblyStateTest` (exactly one DOWNLOADING -> ASSEMBLING, COMPLETED expected=ASSEMBLING, no leftover `.ts`), LIMITATIONS §15 RESOLVED (DECISIONS 139)
- DownloadStateReconciler: IMPLEMENTED (handles all states, idempotent, COMPLETED invariant enforced; COMPLETED cleanup uses output-preserving SegmentFileStore.cleanup() — corrective F-C1; WorkInfo queries are non-blocking suspendCancellableCoroutine — corrective F-L1)
- PaheBatcherApplication: IMPLEMENTED (explicit WorkManager.initialize + Configuration.Provider; default initializer removed in manifest; AtomicBoolean idempotent reconciliation)
- AndroidManifest: UPDATED (FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS permissions, SystemForegroundService with foregroundServiceType=dataSync, application class)
- Segment directory: PHASE10_ARCHITECTURE_V10.md §2 (corrective Finding 9) — all components store segments in `filesDir/downloads/{episodeId}`, with the assembled MP4 written alongside them. Cleanup is output-preserving (corrective F-C1): `SegmentFileStore.cleanup()` removes only segment artifacts (.ts/.tmp/concat.txt), keeps any `*.mp4` output, and deletes the directory only when no output is present; the reconciler's COMPLETED branch uses the same selective cleanup instead of `deleteRecursively()`
- Tests: 393 unit tests pass (35 classes, includes ProgressThrottlingTest + F-C1 cleanup tests). Instrumentation: 93/96 pass — ALL Phase 10 behavioral tests green (migration 3, state transitions 17, repository 6, unique work 2, duplicate enqueue 3, worker execution 2, foreground worker 1, cancellation 1, concurrency 2, process-death 3+2 protocol, resume 2, notification 6, reconciler 10 — incl. the new F-C1 output-survival test, FullDownloadFlowTest 1 — new F-C1 end-to-end success path, TriggerNextDeferredEpisodeTest 1 — new F-C2 failure-path trigger). The 3 failures are PRE-EXISTING FFmpeg filename assertion failures that pre-date Phase 10 (expected value matches neither the Android implementation nor the Python reference — see §10.2)
- Build: BUILD SUCCESSFUL
- APK: Deployed to emulator-5554 (adb -s emulator-5554 install -r, per AGENTS.md §20); app + androidTest APKs both verified installed via `pm list packages` before protocol runs
- Runtime verified on emulator-5554 (x86_64, API 36): custom WorkerFactory init log; DownloadWorker execution log; SystemForegroundService isForeground=true types=0x1 (dataSync) with NO MissingForegroundServiceTypeException/SecurityException; active download_progress NotificationRecord with FOREGROUND_SERVICE flag and app-owned icon resource + notification-shade screenshot; REAL process-death protocol (seed Worker mid-download with row persisted DOWNLOADING → operator force-stop process death, pid gone, row still DOWNLOADING → relaunch → reconciler log "Found 1 episodes for reconciliation" → stranded episode recovered, Worker restarted, FGS isForeground=true restored, row DOWNLOADING again → cleanup step verified empty DB); cancellation persisted as CANCELLED and not resurrected by reconciliation; maxParallel deferral with real Workers
- @tester gate: ✅ PASS (cycle 3) — all 7 gate items executed and verified by @tester with fresh evidence: build SUCCESS; unit 393/393 --rerun; lint 0 errors/12 warnings; instrumentation 96 total / 93 pass / exactly the 3 pre-existing FFmpeg filename failures (TriggerNextDeferredEpisodeTest PASS in-suite — F-G1 test-isolation regression cleared); FGS `isForeground=true types=0x00000001` + `download_progress` channel dumpsys; real process-death protocol (seed → operator kill, row persists DOWNLOADING → relaunch → reconciler recovers → worker restarted, FGS restored → cleanup PASS, row removed). Two earlier gate runs FAILED (cycle 1: 3 CRITICAL + 4 HIGH findings; cycle 2: F-G1) and were fixed and re-verified — full history in ROADMAP.md Phase 10 tester-gate note and DECISIONS.md Decision 40.
- NOT yet claimed: ~~arm64-v8a runtime validation (deferred — only x86_64 emulator available)~~ **superseded 2026-09-28 (reviewer M-2)**: arm64-v8a runtime validation **was achieved in Phase 11.5E-5** on physical device `REDACTED-SERIAL` (REDACTED-MODEL, arm64) — FFmpegKit load + execute + real 146-segment faststart MP4 assembly, and the **full instrumentation suite ran on that arm64 device** (199/1 in 11.5E-6, 216/1 in 11.5E-7, 222/1 in the 11.5F review round and **224/1 in the 11.5F final run** (DECISIONS 73/74/75/77); `adb devices` → single `REDACTED-SERIAL`) *(as observed 2026-09-28 — round-6 finding 11; **superseded 2026-10-04 in Phase 15: `REDACTED-SERIAL` was online again and carried the whole connected suite — 413 tests / 1 failure / 0 errors / 0 skipped — plus the real search → details → resolve → download → MP4 → Media3 playback verification**)*. The Phase 9/10 x86_64 emulator session (`emulator-5554`) is no longer the platform: Phase 15 ran its emulator connected suite (413 tests / 7 failures) on an x86_64 emulator, and `emulator-5554` itself was not connected at the close of Phase 15. What is still not claimed: nothing further on the arm64 runtime question; the connected-run package-vanishing hazard is documented in LIMITATIONS.md §13 (**Phase 15 pinned its trigger to the connected test task itself by a before/after experiment; the mechanism inside the toolchain remains undetermined**)
- Notification permission (pre-Phase-11 clarification): `POST_NOTIFICATIONS` is **declared in the manifest only** — the app does **not** request the runtime permission yet (no `ActivityResultContracts.RequestPermission`/`requestPermission` call exists in production code). The foreground-worker/notification runtime evidence above was obtained **with the permission granted explicitly by the tests** (`Phase10TestSupport.grantNotificationPermission()`). On a fresh install without a prior grant, the foreground service still runs but its progress notification may be hidden; first-install permission prompting is a future notification/UI-phase concern (LIMITATIONS.md §4, §8). Do not claim notifications appear automatically on a fresh install. *(Superseded 2026-10-02: Phase 13 added the one-time first-enqueue runtime permission prompt and the Settings permission-status row (DECISIONS 95.A) — `FirstEnqueuePromptTest` 4/4, `NotificationPermissionTest` 6/6; a grant is still required for any notification to appear, and the manifest-only wording above records the pre-Phase-13 state.)*
- `STATE_ASSEMBLING` (pre-Phase-11 clarification): the state constant exists (`EpisodeDownloadEntity.STATE_ASSEMBLING`) and the reconciler has a handler for it (`DownloadStateReconciler`), but **no production code path writes this state** (grep of `app/src/main` — *reader* enumeration refreshed 2026-09-28: `DownloadStateReconciler`, `DownloadRepository` live-row/submission-guard branches, `DownloadRowMapper` concept + active-state list, `EpisodeDownloadDao.countActive()`; only tests construct entities in `ASSEMBLING`). Therefore production end-to-end ASSEMBLING recovery is **not** verified under natural assembly flow — it is an implementation/reconciliation limitation, documented in LIMITATIONS.md §15 (which also records that `DownloadsScreen`'s assembling progress bar and `DownloadRowMapper.ASSEMBLING` are consequently production-unreachable UI branches). The state is intentionally retained. **(SUPERSEDED 2026-10-07, final audit H4: production now writes this state - `DownloadWorker.executeClaimedWork` CAS-flips DOWNLOADING -> ASSEMBLING through the `onAssemblyStart` hook of `EpisodeDownloader` before the assembler runs, so the assembling UI/mapper branches above are production-reachable; device-proven by `DownloadWorkerAssemblyStateTest`. The claims above describe the pre-2026-10-07 code; LIMITATIONS §15 now records the resolution. DECISIONS 139.)**

---

## Phase 11 Completion Status

**Status: Phase 11 COMPLETE — final independent tester gate PASS** (`TESTER VERDICT: PASS`, Phase 11K, 2026-09-25; per PHASE11_ARCHITECTURE_V2.md — Decisions 41–69; slices 1–8 executed; final-gate evidence in the ✅ @tester bullet below; the earlier strict FAIL gates are retained beneath it as clearly-marked historical record)

**Implemented and verified:**
- Material 3 "outer-space" theme: dark default, Dark/Light/System `ThemeMode`, dynamic color default OFF, DataStore round-trip, dark first frame, LIGHT-persisted cold start settles light — ThemePersistenceTest 7/7 (§36-11)
- Color contrast: programmatic WCAG check over all §5 mandated pairs incl. corrected dark `outline #6672AC` and `inversePrimary #6250D4` — implemented and green in the accessibility suite
- Flat single-NavHost navigation: per-tab stack retention (`restoreState`), `launchSingleTop` no-op on repeat, process-death restoration (graph + selected tab + args) — NavigationTest 8/8 (§36-4)
- Notification intent/navigation: cold-tap → Downloads, warm-tap, no duplicate entry on Downloads, return from Player, unknown/absent extra → Home — IntentNavigationTest 7/7 (§36-5)
- Home first-use semantics: exactly (tagline, search field + hint, `Movies/PaheBatcher/` storage text, empty-downloads guidance), no invented rows — HomeFirstUseSemanticsTest 2/2 (§36-3)
- Search IME: IME Search → same submission path, result tap clears focus/hides IME, navigation leaves no IME — SearchImeTest 4/4 (§36-10), stable in the final gate. The two 2026-09-25 instrumentation timing races (IME dispatch latency vs. debounce; typedQuery timestamp-boundary) were resolved **test-side** by the injectable debounce seam (Phases 11G/11I — DECISIONS 68, LIMITATIONS §21 **RESOLVED**); production `DEBOUNCE_MS = 350L` unchanged; `typedQueryExecutesThroughTheDebounceWithoutAnySubmission` passed **10/10 consecutive first-attempt** runs before the final gate
- Settings: only real DataStore-backed controls (theme mode, dynamic color), no dead rows — SettingsContentTest 3/3 (§36-13)
- Accessibility semantics (content descriptions, decorative exclusion, progress semantics, terminal-only live regions, font-scale) — AccessibilitySemanticsTest 6/6; touch targets ≥ M3 minimums incl. custom player slots, dialog focus restoration — TouchTargetAndFocusTest 5/5 (§36-9; §21.5 asserted via `touchBoundsInRoot` + `useUnmergedTree`, Decision 64)
- Media3 playback (local MP4, x86_64 API 36 emulator): playback, navigate-away pause (covered-not-popped), background pause, rotation preserves position+state, next/prev does not deepen stack, route exit releases player, cold entry restores saved position before play, no auto-resume on re-visibility — PlayerLifecycleTest 8/8 (§36-6). Playback position persisted per-mediaItem and restored across cold entry; Media3 `ProgressStateJob` does not drive Compose `ProgressState`, so the seek slider reads position via `POSITION_UI_TICK_MS = 500L` (Decision 62). **Verification attribution clarified 2026-10-01 (DECISIONS 101):** the wording above records the Phase 11 gate platform; the same suite also ran **8/8 green on physical device `REDACTED-SERIAL` (REDACTED-MODEL, arm64)** — `TEST-REDACTED-MODEL - 16.xml` (2026-09-30, 271 tests / 1 documented `ForegroundWorkerTest` baseline) includes `completedEpisodePlaysRealLocalMp4EndToEnd` (`PlayerLifecycleTest.kt:91`), whose seeded row plays a **bare-path `outputPath`** (`outputUri = null`). Bare-path playback on physical hardware is therefore verified; MediaStore `content://` playback is verified through the §30 evidence chain; SAF-child-URI playback is **not** verified and belongs to the Phase 13 tests of DECISIONS 92. *(Superseded 2026-10-02: SAF-child-URI playback is now device-verified — `SafPlaybackDeviceTest` 2/2 on `REDACTED-SERIAL` (Phase 13, DECISIONS 92.8); the wording above records the Phase 11 state.)*
- Playback-position/seek UI contract: production `Box(testTag = "player_seek_slider")` wrapper (Decision 64) anchors the slider semantics for tests
- Room v2→v3 migration (`outputUri` + playback-position table) — MigrationTest 5/5 (§36-14)
- Speed/ETA/totalSegments state-mapper contract: no `0 B/s`/`0:00` producible from unavailable metrics; persisted `totalSegments`/`completedSegments` read back from Room drive the percentage — TotalSegmentsPersistenceTest 2/2 (§36-2)
- MediaStore export to `Movies/PaheBatcher/` (outside `Android/data`): happy path visible via MediaStore query, insufficient-space keeps local file, orphaned pending sweep, byte-count mismatch retry, idempotent duplicate prevention — MediaStoreExportTest 8/8 (§36-7/§36-17); `_size=null` → fd-stat fallback and EACCES-as-ENOSPC test substitute recorded in Decision 63
- Reconciler invariant incl. exported/user-deleted branches — DownloadStateReconcilerTest 13/13; end-to-end download → assembly → output — FullDownloadFlowTest 1/1
- Regression baselines: unit **468/468** (`--rerun-tasks`, 46 XML files, 0 failures); lintDebug **0 errors / 25 advisory** (no suppressions, no `lint.xml`) — §36-16 baseline numbers exceeded (393→468 unit, 12→25 advisory-only warnings)
- Notification permission architecture (§36-15): grant/deny recorded to DataStore, API 32 no-op, single LOW channel until Phase 14 — `NotificationPermissionRepository` state layer; the in-app runtime *prompt* is deferred to Phase 13 enqueue (LIMITATIONS §4/§8) **(Prompt shipped in Phase 13, 2026-10-02 — DECISIONS 95.A asked-once gate at first enqueue: `FirstEnqueuePromptTest` 4/4, `NotificationPermissionTest` 6/6; the permission-status row shipped in Settings too.)**
- Build/APK: `assembleDebug` BUILD SUCCESSFUL, APK **114,869,141 bytes** (+9,261,176 vs 105,607,965 baseline; media3/nav/lifecycle AARs, Decision 61, §37-4 closed); deployed to emulator-5554 per AGENTS §20
- **@tester gate: ✅ PASS (final — Phase 11K, 2026-09-25)** — `TESTER VERDICT: PASS — PHASE 11 READY FOR FINAL DOCUMENTATION CLOSURE`. Complete independent gate on a recovered healthy `emulator-5554` (AVD `Pixel_10_Pro_XL_API36`, `sys.boot_completed=1`, healthy through every stage): **all 14 Phase 11 instrumentation suites green — 79 tests PASS** (ThemePersistenceTest 7, NavigationTest 8, IntentNavigationTest 7, HomeFirstUseSemanticsTest 2, SearchImeTest 4, SettingsContentTest 3, AccessibilitySemanticsTest 6, TouchTargetAndFocusTest 5, PlayerLifecycleTest 8, MigrationTest 5, TotalSegmentsPersistenceTest 2, MediaStoreExportTest 8, DownloadStateReconcilerTest 13, FullDownloadFlowTest 1); **`PlayerLifecycleTest#poppingThePlayerEntryReleasesItsExoPlayer` executed explicitly — PASS**; SearchIme typed-query timing test **10/10 consecutive first-attempt PASS**; unit **468/468** (`--rerun-tasks`, 46 suites, 0 failures / 0 errors / 0 skipped); lint **0 errors / 25 known pre-existing warnings** (no suppressions); `assembleDebug` + `assembleDebugAndroidTest` **BUILD SUCCESSFUL** (debug APK **114,869,141 bytes / 109.55 MiB**, androidTest APK **3,646,831 bytes / 3.48 MiB**); deployment verified with the explicit emulator serial (both packages present, non-empty `pm path`, installed APK hashes match the built artifacts); **Phase 10 compatibility suites green** (MigrationTest, TotalSegmentsPersistenceTest, MediaStoreExportTest, DownloadStateReconcilerTest, FullDownloadFlowTest); diagnostic cleanliness clean (0 case-sensitive marker matches across `app/src`); change boundary intact (`DEBOUNCE_MS = 350L`, factory production default, `TEST_DEBOUNCE_MS = 10_000L` test-only in `SearchImeTest.kt`). `AccessibilitySemanticsTest` passed **6/6 in targeted reproduction and 6/6 again inside the gate**. The emulator process exit occurred **after** the gate completed and has no effect on the verdict.
- *Historical (superseded — retained for the record): strict `TESTER VERDICT: FAIL` gates earlier on 2026-09-25. First failure class: the SearchImeTest IME-submit/debounce instrumentation timing race (`pending debounce must be cancelled by the IME submit (one request total) expected:<1> but was:<2>`, `SearchImeTest.kt:103`; also `elapsed 364–379ms vs 350ms debounce`, line 97), later reproduced in typed-query form (`elapsed 344ms < 350ms`, line 172) — both harness timing-boundary races resolved by the bounded test-only fixes of Phases 11G/11I (DECISIONS 68; LIMITATIONS §21 RESOLVED; the production debounce never changed). The Phase 11J gate then stopped at `AccessibilitySemanticsTest` with the exact failing assertion lost to tool-output truncation; on the recovered healthy emulator that suite passed 6/6 twice — recorded as a **historical infrastructure-correlated event, not an application defect** (DECISIONS 69).

**Not claimed (honest non-claims):**
- **HLS playback**: **IMPLEMENTED + DEVICE-VERIFIED — Phase 17 (2026-10-05).** The Phase 16 correction of this line (which had recorded "not exercised against live streams — `media3-exoplayer-hls` pinned 1.11.1" and then, after source inspection, "NOT IMPLEMENTED: the module is not even a declared dependency") is now superseded: `media3-exoplayer-hls` and `media3-datasource-okhttp` **1.11.1** are declared through the existing `media3` key, a real AnimePahe episode streams end to end on physical device `REDACTED-SERIAL` (Media3 `STATE_READY`, playlist-derived `durationMs=6601462`, playhead `0 → 2919 ms`, `playerError=null`), and the request headers are proven on the wire for playlist, AES key and segment. What remains verified *from earlier phases*: local-MP4 playback of assembled output (Phase 15 device evidence, in the *Final Verification and Documentation Reconciliation* section). See §11.1 for the full Phase 17 row and evidence.
- **Poster images**: live poster API unverified (state 4) — four-state placeholder contract only (§37-1)
- **Quality selection / audio-track selection**: not implemented in Phase 11 (no track-selection UI exists in production `ui/player/`) — exit-criteria item honestly not met; scoped to a later phase
- **arm64-v8a runtime validation**: ✅ **achieved in Phase 11.5E-5** on device `REDACTED-SERIAL` (arm64) — FFmpegKit runtime + real MP4 assembly + full instrumentation suite (this row read "deferred, only x86_64 emulator available" through 11.5E-4; corrected 2026-09-28, reviewer M-2)
- **Runtime notification prompt**: deferred to Phase 13; permission *state* architecture is what Phase 11 verifies *(Shipped in Phase 13, 2026-10-02 — DECISIONS 95.A: one-time first-enqueue prompt, `FirstEnqueuePromptTest` 4/4 + `NotificationPermissionTest` 6/6.)*
- **MP4 assembly of a real episode**: ✅ **achieved in Phase 11.5E-5** (this row read "NOT achieved — 0-based pre-check rejects 1-based files" through 11.5E-4) — production run: 146/146 segments → `Assembly succeeded via concat demuxer` → `Ep_001_-_Spy_x_Family_Ep._1_animepahe.mp4` (146 705 229 B; faststart `moov` before `mdat`, tracks `avc1`+`mp4a`, `mvhd` 1458.803 s) → MediaStore `content://media/external/video/media/116` (`video/mp4`, `is_pending=0`, 1920×1080) → Downloads UI `completed · 146/146 segments · 100%`; LIMITATIONS §26 **RESOLVED**
- **search → scan → resolve → download entry point**: ✅ **reached in Phase 11.5E-2/E-3/E-4** and verified on device with the production APK (Home search → Anime Details scan → `StreamResolverImpl` → `EpisodeDownloadQueue` → `DownloadWorker` → `EpisodeDownloader`). ~~What remains Phase 13 is the *range/all-episodes* entry and the Settings-driven quality~~ **Stale claim corrected 2026-10-01 (first Phase 13 architecture review, F-9): both shipped in Phase 12C** — the range/all-episodes entry is the batch panel's `EpisodeRangeParser` range field (§13.2, shipped 12C) and Settings-driven quality is `quality` → `configuredQuality()` → `StreamResolver.resolve(quality = …)` (§9.5, shipped 12A); `LIMITATIONS §14` describes the pre-11.5 state

---

## Final Verification and Documentation Reconciliation (Phases 15–16, 2026-10-05)

> **This section is the parity statement of record produced by the Phase 15/16 documentation pass, updated by Phase 17.** It records the *verification level* of each claim rather than upgrading any row above it, and it also records the one core requirement the Phase 16 pass found to be **not implemented** — streaming, see §11.1 and LIMITATIONS §36. **Phase 17 implemented and device-verified that requirement**, so the streaming rows below now read IMPLEMENTED with their evidence, while LIMITATIONS §36 is marked RESOLVED. Where this section and an older row disagree, this section reflects the latest evidence. **The project status now depends on the remaining scope items**, not on streaming. **Status update 2026-10-08:** those scope items are closed for SPEC purposes — the 2026-10-07 final validation pass closed every genuine finding it raised (DECISIONS 139) and the read-only M4/L1–L3 forensic re-assessment returned `M4/L1–L3 — NO GENUINE SPEC BLOCKER FOUND` (DECISIONS 140), so the current verified status is `SPEC — SATISFIED`. The rows and sections below keep their exact verification levels and their documented differences: unsigned release artifact with no release-runtime execution, no physical reboot run, and the intentional Python `auto_retry` retry-loop difference (LIMITATIONS §24.3 / §38.8 / R5) — verification limitations and a documented parity difference, not open SPEC requirements.

### Verification levels used

| Level | Meaning |
|-------|---------|
| VERIFIED (device) | Observed live on physical device `REDACTED-SERIAL` through the app's own network path, logs or UI |
| VERIFIED (test) | Exercised by a passing production-path test (JVM or instrumentation); **no live log line exists for this consumer** — the distinction is preserved, never collapsed |
| DIFFERENT BY DESIGN | Android behavior intentionally differs from Python; the difference is documented in its row |
| EXTERNAL | Not exercisable in that environment because an external service refused it |
| DEFERRED | Not implemented, deliberately, with a recorded reason |
| NOT IMPLEMENTED | Required by `SPEC.md` but absent from the source — never presented as parity |

### Phase 15 automated verification — counts of record

| Run | Result |
|-----|--------|
| JVM unit tests | **719 tests / 0 failures / 0 errors / 0 skipped** (70 classes) |
| Lint | **0 errors, 34 warnings, 1 hint** |
| `assembleDebug` | SUCCESS — APK **118,337,811 bytes** |
| `assembleDebugAndroidTest` | SUCCESS |
| Connected — emulator | **413 tests / 7 failures / 0 errors / 0 skipped** |
| Connected — physical `REDACTED-SERIAL` (REDACTED-MODEL) | **413 tests / 1 failure / 0 errors / 0 skipped** |
| Isolated re-runs (**kept separate from the full-run numbers**) | `CancellationTest` 2/0 · `MediaSessionLifecycleTest` 1/0 · `SettingsOutputLocationTest` 7/0 · `ExampleInstrumentedTest` 1/0 |

Full-suite failures are recorded **as failures**; they are not rewritten as zero-failure runs because isolated re-runs passed. Every failure was classified — **PRODUCT: none**; the rest are TEST, ENVIRONMENTAL or EXTERNAL SERVICE — with the isolated-re-run context in `ROADMAP.md` Phase 15 and LIMITATIONS §35.

**Superseded by the audio-slice verification pass — counts of record (2026-10-08, DECISIONS 142):**

| Run | Result |
|-----|--------|
| JVM unit tests (`testDebugUnitTest` + `testReleaseUnitTest`) | **941 tests / 0 failures / 0 errors / 0 skipped** each (92 files; +50 over the 891 pre-slice baseline) |
| Lint (`lintDebug`) | **0 errors, 38 warnings, 1 hint** (pre-slice baseline unchanged) |
| `assembleDebug` + `assembleRelease` | SUCCESS (both) |
| Connected — physical `REDACTED-SERIAL` (full run) | **483 tests / 8 failures / 0 errors / 0 skipped** (29m2.97s) — **PRODUCT: none**; every failure root-caused: 1 old batch-render contract pin (inverted to the new exact-pair contract), 3 `FirstEnqueuePromptTest` precondition assumption-violations, 2 timing races, 1 GateA cross-class order prerequisite, 1 landscape-composition effect |
| Isolated re-runs (kept separate from the full-run numbers) | `AnimeDetailsBatchScreenTest` 15/0 · `CancellationTest` 2/0 · `FirstEnqueuePromptTest` 4/0 · `MediaSessionLifecycleTest` 1/0 · `AutoResumeConnectivityTest` 2/0 · `GateASpecialTitleDeviceTest` 1/0 then `GateASecondEpisodePlaybackDeviceTest` 1/0 (direct `am instrument` pair, same install session) |
| 13-scenario physical-device gate | **11 PASS / 2 NOT EXERCISED / 0 FAIL** (scenarios derived from `SPEC.md:876-951` + the approved slice requirements; the scenario table is recorded in the ROADMAP audio-slice section and the session's final report) |

The Phase 15/16 table above keeps its original numbers as history; the convention that full-suite failures are never rewritten as zero-failure runs still applies.

**Superseded by the Slice C per-episode failure-reason pass - counts of record (2026-10-12, DECISIONS 147):**

| Run | Result |
|-----|--------|
| JVM unit tests (`testDebugUnitTest`) | **1038 tests / 0 failures / 0 errors / 0 skipped** (98 files; +22 over the 1016 pre-slice baseline - `FailureCategoryTest` 8, `AnimeDetailsBatchFailureAttributionTest` 14) |
| Lint (`lintDebug`) | **0 errors, 38 issues (37 warnings + 1 hint)** - pre-slice baseline unchanged |
| `assembleDebug` + `assembleDebugAndroidTest` | SUCCESS (both) |
| Focused instrumentation (new class, on device `REDACTED-SERIAL`) | `BatchFailureNotificationTest` **11 tests / 0 failures**, with `POST_NOTIFICATIONS` granted so the fixed-id replacement test executes rather than skipping |
| Connected - physical `REDACTED-SERIAL` (full run, confirmatory) | **545 tests / 1 failure** - and that test **remains FAILING**: `Phase17StreamingSiblingNavigationDeviceTest` asserting Media3 reaches READY on a real CDN stream. **Corrected (2026-10-12):** this cell previously cited **LIMITATIONS 43.4** (live AnimePahe unreachable, hard IP-level Cloudflare 403) - **wrong**; the run's logcat shows AnimePahe reached and scanned, the Cloudflare session passed and the M3U8 extracted, with no 403, 404, 410, 5xx, timeout, DNS failure or challenge. Verified cause: a Media3 **malformed-media** failure loading HLS media from the resolved CDN host (`ParserException: Loading finished before preparation is complete {contentIsMalformed=true, dataType=1}`); stream resolution succeeded and the preceding episode reached `STATE_READY`. Reproduced **3 of 3** identical runs; the underlying cause of the bad bytes is **unverified** (raw bytes not captured) and is claimed neither permanent nor transient. **Not a Slice C notification/failure-attribution regression.** See **LIMITATIONS 46.5** and the pre-existing correct account in **LIMITATIONS 42.2**. **PRODUCT: none** |
| Isolated re-runs (kept separate from the full-run numbers) | `CancellationTest` 2/0 - it failed in one full run and passed in the previous full run, the confirmatory run and in isolation, all on a **byte-identical `app-debug.apk`** (built 11:52:14, before both runs), so it is timing flakiness and not a regression |
| Debug APK | installed per AGENTS 20 (one device `REDACTED-SERIAL`, state `device`; `lastUpdateTime 2026-10-12 13:24:20`) |

**Slice C adds no fifth channel and no Room migration.** `fourAppChannelsExistWithTheShippedImportance` stays green at four app-owned channels - the fifth channel a device may show is media3's own, created by `DefaultMediaNotificationProvider` when a MediaSession is active - and the room database stays at **v3**.

**Superseded by the post-audio improvements pass - counts of record (2026-10-12, DECISIONS 148):**

| Run | Result |
|-----|--------|
| JVM unit tests (`testDebugUnitTest`) | **1067 tests / 0 failures / 0 errors / 0 skipped** (100 files; +29 over the Slice C figure of 1038 - 2 new classes, `DownloadNotificationTextTest` 8 and `ScanProgressTest` 4, and 21 tests added to 4 existing classes) |
| Lint (`lintDebug`) | **0 errors, 38 issues** - the same total as the Slice C baseline; the only issues in files this slice touched are the two pre-existing `AnimeDetailsScreen.kt` ones (`ModifierParameter` at the unchanged `modifier` position, `InlinedApi` shifted 176 -> 181 by this slice's added lines) |
| `assembleDebug` + `assembleDebugAndroidTest` | SUCCESS (both) |
| Focused instrumentation (7 classes, device `REDACTED-SERIAL`, **user 10**) | **104 tests / 0 failures** - `AnimeDetailsScreenTest` 34 (9 new), `AnimeDetailsAudioScreenTest` 9, `AnimeDetailsBatchScreenTest` 14, `BatchFailureNotificationTest` 15 (4 new), `NotificationTest` 11, `EnqueueOutcomeMappingTest` 13 (2 new), `NavigationTest` 8 |
| Connected - full run | **NOT RUN this pass**: `connectedDebugAndroidTest` uninstalls both APKs and wipes application data (DECISIONS 1992), which the operator did not authorize. The Slice C figure in the table above (545 tests / 1 failure) stays that session's recorded history and was **not** re-verified here |
| Debug APK | installed per AGENTS 20 to **user 10 ("Android Dev")** on the one connected device `REDACTED-SERIAL` (`lastUpdateTime 2026-10-12 18:58:27`); no install or destructive op was performed on user 0 |

The Phase 15/16 and audio-slice tables above keep their original numbers as history; the same convention applies.

### Position of each core feature area at the end of the Phase 15/16 pass (audio-slice rows refreshed 2026-10-08)

| Area | Position |
|------|----------|
| Search, details, episode scan + cache TTL | VERIFIED (device); on the emulator the same build is **EXTERNAL**-blocked by a Cloudflare challenge (constant 260-char body → `searchWithOutcome` reports challenge → "No results found.") |
| Session/Cloudflare handling | VERIFIED (device) behind the WebView abstraction |
| Stream resolution, quality selection, Kwik/M3U8 | VERIFIED (device) |
| Audio language selection | VERIFIED (device) — **per-episode audio at resolve (audio slice, SPEC §22)**: exact open-set match against the row's or the batch's recorded number → audio value, no substitution, explicit failure when unavailable; see §1.4, §5.1 and §11; the global `audio_lang` preference is gone (§9.2, DECISIONS 142); the OPTION SET itself is discovered by a bounded play-page probe (§1.4, DECISIONS 144) — **VERIFIED (test)** only, no live probe (LIMITATIONS §43.4) |
| HLS segment download, retry, concurrency, cancellation, auto-resume, process death | VERIFIED (test) + VERIFIED (device) (full pipeline run); **PAUSED across a REAL external process kill VERIFIED (device) 2026-10-06** — `RealProcessDeathPausedDeviceTest` on `REDACTED-SERIAL`: phase 1 seeds a real worker through a loopback HLS origin on a fixed port, waits for ≥ 1 persisted segment, pauses (durable `PAUSED`, speed/ETA zero, `.ts` retained), records `{pid, port, episodeId, segments, bytes}` and sleeps; the operator then runs `adb shell am force-stop --user 10 com.pahebatcher.app` (the instrumented command dies with `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` — a real external kill, no process survived), `am start` relaunches, and phase 2 asserts in a **fresh pid** that the row is still `PAUSED`, the retained segments are untouched, nothing auto-resumed and no work is live — then resumes to `COMPLETED` with exactly the bytes the segments write and `servedTotal < 2 × segmentCount` (no re-download of the retained ones). An in-process `new repository → reconcile()` check was explicitly **not** accepted as this evidence (DECISIONS 134) **Evidence archived 2026-10-07 (DECISIONS 138):** the operator-run transcript is now durably archived in the repository's existing evidence convention at .investigation/phase17p7/ - README.txt (full protocol narrative, device REDACTED-SERIAL, date/time, and the suite-mode vs external distinction), 1-phase1-stdout.txt (` INSTRUMENTATION_RESULT: shortMsg=Process crashed.`), 2-kill-transcript.txt (PRE-KILL pid 17626, the exact ` adb ... am force-stop --user 10 com.pahebatcher.app` command, POST-KILL pid empty), 3-phase2-instrument.txt (` OK (1 test)` in fresh pid 18025), 4-logcat-snapshot.txt (ActivityManager ` from pid 17836` external kill + obituary of 17626, relaunch pid 17937, ` Playlist: 40 segments, 10 completed, 30 pending`, ` completed=40, failed=0`), 5-pidof-timeline.txt (500 ms host-side pid samples across all four observed pids) and SHA256SUMS.txt (SHA-256 of every evidence file) - captured from a NEW external-kill execution on 2026-10-07, not reconstructed from the earlier run. The suite-mode XML artifact remains an in-process single-PID regression gate only and is explicitly NOT evidence of external process death; the protocol itself stays operator-driven (LIMITATIONS 39.4). |
| MP4 assembly + MediaStore / SAF export | VERIFIED (device) — 140/140 then 141/141 segments → MP4 → export |
| Media3 playback of assembled output | VERIFIED (device) — `ExoPlayerImpl: Init … [AndroidXMedia3/1.11.1]`, real frames, 00:19 / 23:02 against upstream `duration: 00:23:01`; the played file had **one video + one audio track** (tested output only) |
| **Streaming / direct HLS playback (SPEC §15, §39 "Streaming works.")** | **VERIFIED (device) — Phase 17, 2026-10-05** — real search → scan → resolve (`quality=1080`, `audio=eng`) → `hostPath=vault-13.owocdn.top/stream/…/uwu.m3u8` → Media3 `STATE_READY`, `durationMs=6601462` parsed from the real media playlist, playhead `0 → 2919 ms` over 3 s, `playerError=null`, on `REDACTED-SERIAL`; headers (UA/Referer/Cookie) proven on the wire for playlist + AES-128 key + segment on emulator and device (`StreamHlsHeaderTest`); ladder, retry budget and DOWNLOAD-isolation proven on device (`StreamPlaybackLifecycleTest` 5/5) (§11.1, LIMITATIONS §36 **RESOLVED**); **real-CDN episode navigation verified 2026-10-06** — N → N+1 → refused boundary → back to N on the live backend with one player, one route and a coherent MediaSession (§11.1 `Episode navigation` row, DECISIONS 135) |
| Settings (18 rows) | VERIFIED — **18/18 FUNCTIONAL** (17 configuration controls + the notification-permission status row; the Preferred-audio row was removed by the audio slice), evidence tier per row in §9.6 |
| Watchlist check/relink/notifications | VERIFIED (test) + VERIFIED (device) |
| In-player audio/quality track switching | **NOT IMPLEMENTED** — no `TrackSelector`/`TrackSelection` in `app/src/main`; DIFFERENT BY DESIGN, documented in §11 |
| Multi-track playback | **NOT CLAIMED AND NOT TESTED** |
| SUB/DUB switching (SPEC §39) | Implemented as per-episode variant selection with a batch number → audio mapping (audio slice; no `audio_lang` setting — DECISIONS 142) — **VERIFIED (test)** for the dual-audio path; the live dual-audio sample still could not be staged because no upstream title exposed two variants for the SAME episode (§1.4, LIMITATIONS §35.5/§41); the selector's option set now comes from the bounded probe (§1.4, DECISIONS 144) — **VERIFIED (test)** only (LIMITATIONS §43.4) |
| **Subtitles / text tracks (SPEC §15 "subtitles where available")** | **VERIFIED (device) — 2026-10-06.** A declared text rendition is offered by name (manifest `NAME` else `LANGUAGE`), selected through `TrackSelectionOverride`, cleared by disabling the text track type; the control does not exist when the media declares none. Evidence: `SubtitleStateDerivationTest` 13/13 (JVM), `PlayerSubtitleControlTest` 8/8 + `SubtitleFixturePlaybackDeviceTest` 2/2 (device `REDACTED-SERIAL`, real READY playback with a fixture rendition) — §11.1 `Subtitles` row, DECISIONS 133 |
| **Per-episode failure reasons + batch failure summary (Slice C, DECISIONS 147)** | **VERIFIED (test)** - 22 new JVM tests pin every `FailureCategory` mapping, the typed-only rule (a plain exception carrying an `AudioSelector.unavailableMessage`-identical sentence classifies `UNKNOWN`), ZERO resolver requests for `EPISODE_NOT_IN_LISTING`, episode + requested-audio identity in every reported failure, count/list agreement, skips and `Refused` never reported as failures, and every notification condition; 11 on-device instrumentation tests pin the `-9002` fixed-id replacement, channel/group/icon, and byte-identical text when nothing is attributable. **NOT claimed end-to-end against live AnimePahe** (LIMITATIONS 43.4) |
| **Post-audio improvements (probe status + targeted re-probe, honest scan progress, batch-failure reason breakdown, derived lost-claim verdict - DECISIONS 148)** | **VERIFIED (test)** - all five probe states render with their own wording (sampled vs listing-derived vs never-checked), the retry affordance is bound to the caller's action and costs exactly one bounded pass per tap (dedupe, completed / no-targets / offline / running-batch refusals all zero-request, cache `cachedAt` and `cacheTtlMinutes` byte-identical after a retry), scan progress reports only observed pages against the server's own total and is silent on a cache hit and after cancellation, the batch summary names typed failure reasons with counts and no exception prose, and the lost-creation-claim branch derives its verdict from the row it re-reads (LIMITATIONS R6 RESOLVED). **NOT claimed end-to-end against live AnimePahe** (LIMITATIONS 43.4) |

### Intentionally different Android behavior (no parity claim)

Resolve-time per-episode audio selection instead of an in-player audio toggle, and no global audio setting at all (§11; DECISIONS 142) · SAF tree export instead of an arbitrary POSIX `output_dir` (DECISIONS 92/96) · no `cookie_string` (WebView owns cookies, DECISIONS 26/96) · Android-only settings with no Python equivalent — Wi-Fi-only, auto-resume, per-type notification preferences, Show Console, Theme, Dynamic color (§9.4/§9.6) · no CLI (`--latest`/`--range`/relink-all have no Android counterpart; the batch panel and per-row Relink replace them, with `latest`-mode logic now exposed through the batch panel's **Latest N** control (supersedes the earlier "ported but unexposed" wording — §13.2 row, DECISIONS 127)) · deferred, still-open items in LIMITATIONS §24 (expired-stream recovery, findings 2–3, R3-R6 (R1 RESOLVED 2026-10-13 by the per-host limiter)) and LIMITATIONS §30.

### Requirements with no Android equivalent (NOT a design difference) — empty as of 2026-10-06

| Requirement | Status | Evidence |
|-------------|--------|----------|
| (none open) | — | This table's only row was `SPEC §15 "subtitles where available"`, recorded **UNVERIFIED — never tracked** because no subtitle/text-track handling existed in `app/src/main` and no upstream title with a soft-subtitle track had been tested. **Subtitles shipped 2026-10-06**, so the row moved to the verified table above (§11.1 `Subtitles`, DECISIONS 133); the boundaries that remain are stated in LIMITATIONS §39, not as a missing requirement. |

**Streaming is no longer in this table.** The Phase 16 pass found `SPEC §15 "HLS playback"` / `SPEC §39 "Streaming works."` unimplemented and recorded them here as an untracked gap; **Phase 17 implemented and device-verified the feature** (§11.1, LIMITATIONS §36 RESOLVED), so both rows moved to the verified table above. The last item that remained here — subtitles — **also moved out on 2026-10-06**: the control is implemented and device-verified (§11.1 `Subtitles`, DECISIONS 133), so this table now holds no open requirement, and the paragraph's former "still unverified and unclaimed" clause is superseded by that row.

---

## Critical Path Items

1. **Cloudflare bypass** (IMPLEMENTED) — CloudflareSessionProvider interface + AndroidCloudflareSession with real WebView
2. **TLS fingerprinting** (VALIDATED) — OkHttp cannot bypass Cloudflare TLS detection
3. **WebView session management** (IMPLEMENTED) — Lifecycle, persistence, request bridge, Cloudflare detection
4. **Stream resolution** (IMPLEMENTED) — PlayPageParser, quality/audio selection, KwikResolver, M3u8Extractor, JsPacker
5. **HLS download engine** (IMPLEMENTED) — HlsPlaylistParser, Aes128Decryptor, EpisodeDownloader, SegmentFileStore
6. **FFmpeg muxing** (IMPLEMENTED) — FFmpegKit 8.1.7 (Decision 11), EpisodeAssembler, FfmpegEpisodeAssembler, concat demuxer + pipe fallback
7. **Local HLS proxy** (NOT NEEDED — Phase 16, re-confirmed Phase 17, 2026-10-05) — Phase 17 added a real streaming path and still does not proxy it: Media3's OkHttp `DataSource` talks to the CDN directly with `StreamInfo.toHeaders()` (§11.1 `HLS playback` / `Local HLS proxy` rows, LIMITATIONS §36 RESOLVED); previously shown as "PENDING — ExoPlayer compatibility"
8. **Background downloads** (COMPLETE — @tester gate PASS, cycle 3) — WorkManager 2.11.2 + CoroutineWorker + setForeground

---

## Notes

- [**NOTE 2026-10-22**: the five percentages below are the ORIGINAL whole-project survey tally and have **not** been recomputed — they are historical survey figures, not a current parity status. The per-feature Status column of this document is authoritative. The 8% bullet carries its own supersession detail.]
- 59% of features can be directly ported (same logic, same behavior)
- 21% require different implementation (same behavior, different API)
- 8% have platform differences or are open parity discrepancies (behavior differs or needs validation; AES unpadding semantics is no longer among them - closed 2026-10-13; **`max_parallel` 3 vs 2 is no longer among them**: it was moved to the *decided* column by DECISIONS 78 / 9.2 and is only counted here from the pre-11.5G tally, which is stale and not recomputed because the percentages come from the original whole-project survey)
- 2% are Android extensions (added for Android)
- 10% are not yet implemented (future phases or replaced by Android-native alternatives)
- Assembly pipe fallback is an Android adaptation (Decision 79) — different implementation, same observable behavior
- Watchlist implementation details (Phase 12) were mostly not implemented at survey time — since Phase 12 shipped they are implemented and tested (§12)
- UI (Phase 13) uses different implementation (Compose vs Rich terminal)

---

## Reference reconciliation and §4 parity verification (2026-10-12)

**Reference root.** `scripts/check-doc-citations.ps1` now resolves every Python citation against **`<python-ref-3.7.0>`** (the single authoritative `$PythonRef`; `pyproject.toml` and `src/pahebatcher/config.py` both report `VERSION = "3.7.0"`). The previous root `<python-ref-3.6.0>` no longer exists on disk. All 26 remaining `3.6.0` mentions were classified before touching anything: one checker config line (changed), four current-reference statements (updated with supersession notes in `REPOSITORY_ARCHAEOLOGY.md`, `PHASE11_ARCHITECTURE_V1.md`/`V2.md` and Decision 76), and 21 historical evidence records (past gate runs, older decisions, a frozen media-comparison path) left untouched as history.

**Citation audit.** 302 Python citation numbers across `DECISIONS.md`, `LIMITATIONS.md`, `PARITY.md` and `ROADMAP.md` were re-read line by line against 3.7.0. Content was correct everywhere except two pointer-only drifts, both corrected: `extract/scanner.py:200` became `202` because line 200 is now `pass` while the `scan(prefer_audio: str = "jpn", cache_ttl: int = 60)` signature lives at 202 — and `prefer_audio` occurs nowhere else in the package, so every "declared but never read" claim still holds — and the `sessions.py` +1 line shift (`81-86` to `82-87`, `91` to `92`, `93` to `94`, `104` to `105`, `105` to `106`, `111` to `112`). Three `.kt` citations resolved to blank lines and were re-pointed at the lines that actually carry their claims (`StreamResolverImpl.kt:160`, `DownloadWorker.kt:194-200,223-227`, `WatchlistChecker.kt:681-682`). Re-read and confirmed correct as written: `main.py:84`, `main.py:329`, `main.py:332`, `main.py:419-425`, `downloader.py:54`, `downloader.py:214-217`, `sessions.py:75`, `kwik.py:188`, `kwik.py:241-247`, `kwik.py:254-256` (the bare `:188`/`:242`/`:255` shorthand on the Kwik row resolves inside `kwik.py:176-255`, not to `stream.py`). Gate result: `PASS - all documentation citation checks passed.` (exit 0). No production, Gradle or test source was modified by this reconciliation.

**§4 classification** (SPEC §8 pipeline and SPEC §31 checklist; symbols read from both trees):

| Area | Python 3.7.0 | Android | Classification |
|---|---|---|---|
| Search and variant discovery | `extract/scanner.py:131-153` (`discover_all_sessions`) | `AnimeRepository.discoverVariantsWithTitle`, `AnimePaheParser.parseAnimeUrl`, `SessionRepository.resumeUrlOf` | PARITY VERIFIED |
| Episode pagination and scan-cache integrity | `extract/scanner.py:260-266` (3 page attempts with exponential backoff, `_PAGE_FETCH_ATTEMPTS = 3`), `extract/scanner.py:295-302` (a partial scan is never cached and the user is told) | `EpisodeRepository.scan` - `AnimePaheParser.classifyEpisodePage` (`AnimePaheParser.kt:270`) reads every fetched payload as ROWS / END_OF_PAGINATION / UNREADABLE: an absent, `null` or empty `data` array ENDS the session as a completed scan (`extract/scanner.py:280-281`), an unfetched or unreadable page sets `AnimeInfo.completed` (`AnimeInfo.kt:59`) to false, and the cache write is gated on that flag (`EpisodeRepository.kt:268`), so a partial scan is returned but never persisted; `AnimeDetailsViewModel.kt:1007` and `WatchlistChecker.kt:256` refuse to present it as an inventory | FIXED 2026-10-13 - cache refusal and user notification now MATCH the reference (PARITY VERIFIED); the 3-attempt backoff is not ported (one primary endpoint request plus one fallback-URL request, no delay) - robustness-only, described below |
| Master-playlist variant choice | `extract/m3u8.py:82-84` (`variants[-1]`) | `EpisodeDownloader.downloadOnce` (`playlist.variants.lastOrNull()`) | PARITY VERIFIED |
| Quality rule | `kwik.py:241-247` builds the quality map, best not exceeding the request else the smallest | `QualitySelector.select` — `sortedDescending()` then `firstOrNull { <= requested } ?: last()` (identical rule) | PARITY VERIFIED |
| Kwik URL selection and referer | `kwik.py:188`, `kwik.py:254-256` | `PlayPageParser.parseResolutionMenuEntries`, `StreamResolverImpl.doResolve`, `referer = resolution.sourceUrl` at `StreamResolverImpl.kt:160` | PARITY VERIFIED (blank-target citation re-pointed from `:135`) |
| Audio selection at resolve | `downloader.py:214-217` (silent `variants[0]` substitution) | `AnimeInfo.variantOrCarrier`, `AudioSelector.matches` plus `unavailableMessage`, `AUDIO_UNAVAILABLE` | INTENTIONAL AND DOCUMENTED ANDROID ADAPTATION (DECISIONS 142-144) |
| Global audio preference | `main.py:84`, `config_manager.py:107` | key deliberately absent from `AppConfig`; per-number mapping instead | INTENTIONAL AND DOCUMENTED ANDROID ADAPTATION (DECISIONS 142, SPEC §22/§23) |
| Output naming and audio identity | `downloader.py:56-58` (no audio token; `_find_existing` is audio-blind), `store.py:156-158` (`Ep_<n>_<AUDIO>` cache dir) | `FileNameGenerator` (local name is the Python form), `ExportFileNames.exportedName` (`_[jpn]` leaf), `EpisodeId.build` | INTENTIONAL AND DOCUMENTED ANDROID ADAPTATION (DECISIONS 145, LIMITATIONS §44) |
| Orphan cache sweep | `main.py:419-425` (deletes `.ts` trees older than 24 h) | `SegmentFileStore.cleanupOrphans` (`.tmp` artifacts only) | INTENTIONAL AND DOCUMENTED ANDROID ADAPTATION (segments must survive process death) |
| Decryption | raw AES-CBC without an unpadding step | `Aes128Decryptor` raw CBC (`AES/CBC/NoPadding`), no unpadding step | RESOLVED 2026-10-13 - see the RESOLVED block at the end of this file |
| Per-host concurrency cap | one shared session with a per-host limit | `HlsHostConcurrencyLimiter` - one app-scoped host-keyed semaphore shared across Workers | RESOLVED 2026-10-13 - see the RESOLVED block at the end of this file |
| Assembly and ASC fix | `store.py` pipe then concat, `-c copy -movflags +faststart`, `fix_aac_esds` | `FfmpegEpisodeAssembler` single-input then concat, same flags, `AudioSpecificConfigNormalizer` | PARITY VERIFIED (Android renames a temp output before publishing it) |
| Segment resume | `store.py` `done_indices` plus `downloader.py` pending recompute | `SegmentFileStore.doneIndices` plus the `EpisodeDownloader` resume base | PARITY VERIFIED (both index-based) |
| Transport | `solver.py` FlareSolverr plus curl_cffi cascade | WebView session serialised behind `pageLock` | INTENTIONAL AND DOCUMENTED ANDROID ADAPTATION (SPEC §7) |
| In-player audio switching | `stream.py:243` audio preference rebuilt inside the player | none — playlists are pinned to the seed episode's audio | KNOWN FUNCTIONAL DEVIATION (marked N/A at the Audio-toggle row for single-track MP4) |

**Newly recorded deviation — scan-cache integrity.** Python retries a failed page three times and then refuses to cache the incomplete scan, telling the user to re-run (`extract/scanner.py:260-266`, `extract/scanner.py:295-302`). Android ends that session's pagination on the first failed page (`EpisodeRepository.kt:163`) and persists the truncated result whenever the TTL is positive (`EpisodeRepository.kt:284-286`), so a partial episode list becomes the cached truth until the TTL expires. No behavior was changed to produce this finding — it was simply unchecked until now, and it is recorded here rather than folded into the existing ✅ scan-caching row.

**RESOLVED 2026-10-13 - the deviation recorded above is closed; that sentence and its line numbers are retained as pre-fix history.** `EpisodeRepository.scan` now classifies every fetched payload (`AnimePaheParser.classifyEpisodePage`, `AnimePaheParser.kt:270`; result type `EpisodePageKind`, `AnimePaheParser.kt:304`): an absent, `null` or empty `data` array ends the session as a completed scan, matching `extract/scanner.py:280-281`, while an unfetched or unreadable page sets `AnimeInfo.completed` (`AnimeInfo.kt:59`, Python `completed_all` at `extract/scanner.py:252`) to false and ends only that session's pagination. The cache write is gated on that flag (`EpisodeRepository.kt:284`, `if (cacheTtlMinutes > 0 && completedAllPages)`), so an incomplete refresh neither overwrites a previously valid entry nor re-freshes its `cachedAt`, while a legitimate empty scan still caches as a completed one. The partial result is still RETURNED rather than thrown, matching `extract/scanner.py:304`, but it is no longer presented as an episode inventory: `AnimeDetailsViewModel.kt:1007` routes it to the existing `ERROR_SCAN_FAILED` state and `WatchlistChecker.kt:256` marks the check `FAILED` instead of refreshing the entry's title. Cancellation is not swallowed into a partial result - `currentCoroutineContext().ensureActive()` re-raises it before a cancelled request can be scored as a failed page, and since the Phase A NET-001 fix `AndroidWebViewRequestBridge.kt:164` rethrows `CancellationException` ahead of its generic `catch (Exception)` at `:171` (previously it caught `CancellationException` like any other `Exception`), the `ensureActive()` is now defense-in-depth behind the bridge's own rethrow. **Verified 2026-10-13:** 18 new tests (8 parser classification, 8 repository cache-integrity including cancellation, 1 ViewModel, 1 WatchlistChecker) covering complete pagination plus cache read, an unfetched page, an unparsable page, an incomplete refresh that must not overwrite a valid cache entry, retry after an incomplete scan, a valid empty scan, and cancellation; focused suites 118 tests / 0 failures / 0 errors, full JVM suite 1085 tests across 100 suites with 0 failures and 0 errors, `lintDebug` 38 findings (unchanged from the pre-change baseline), `assembleDebug` BUILD SUCCESSFUL, `scripts/check-doc-citations.ps1` PASS. **Not ported:** the three attempts with exponential backoff of `extract/scanner.py:261-266` - Android issues one primary endpoint request plus one fallback-URL request with no delay, a robustness difference that does not change what is cached.

**UNVERIFIED (unchanged by this pass).** The live audio probe and live export remain external/Cloudflare-bound (LIMITATIONS §43.4), and the five post-audio slices are not claimed to be verified end-to-end. Nothing in this reconciliation was executed: no Gradle build, no Android or Python test run, no device run, because no production source changed. Every classification above is a source-level comparison, not a runtime measurement.
**RESOLVED 2026-10-13 - two deviations closed in the final parity cycle. The sentences and line numbers recorded at the Decryption row and the Connection-pooling row above are retained as pre-fix history.**

**(1) Decryption / unpadding.** `Aes128Decryptor.kt:85` now asks the JCE for `AES/CBC/NoPadding`, reproducing Python's raw `cipher.decrypt(raw)` (`downloader.py:99-105`) exactly: ciphertext length and plaintext length are always equal, and the call cannot fail on padding. The previous `AES/CBC/PKCS5Padding` diverged in two directions. It silently dropped the trailing 1-16 pad bytes that Python keeps on disk, so the byte total reported through `dash.seg_done(key, len(raw))` and the bytes written for every encrypted segment disagreed with the reference; and `doFinal` threw `BadPaddingException` on any ciphertext whose final block did not decode as PKCS#7, which Android converted into a failed segment even though the reference downloads that payload successfully. The change therefore **removed a rejection of reference-valid input rather than introducing one**, and no input Python accepts is rejected: Android still fails only on empty ciphertext, on a key or IV that is not 16 bytes, and on input that is not a positive multiple of the 16-byte block - which is precisely when pycryptodome raises `ValueError` as well. Downstream assembly is unaffected because `FfmpegEpisodeAssembler.kt:122` checks segment COUNT and non-zero size only, never a per-segment byte length or 188-byte MPEG-TS alignment. The empty-ciphertext guard is deliberately retained: a zero-byte segment is corrupt rather than unpadded, and AGENTS 12 requires that incomplete segments are never considered completed.

**(2) Per-host concurrency cap.** `HlsHostConcurrencyLimiter.kt:90` holds one `kotlinx.coroutines.sync.Semaphore` per distinct hostname, sized from the persisted `hls_workers` setting read live (A2.1 Decision 6), whose default is `DownloadConfig.kt:17` `hlsWorkers` (default 24, matching Python `HLS_WORKERS`). `OkHttpHlsClient.kt:79` and `OkHttpHlsClient.kt:123` wrap the blocking `Call.execute()` **and the body read** in `withHostSlot`, so an in-flight request means the same thing it does in aiohttp, where a connection stays busy until the response is fully consumed. `AppContainer.kt:353` owns a single app-scoped instance and hands it to the one `DownloadWorkerFactory` (`PaheBatcherApplication.kt:140`, limiter wired at `PaheBatcherApplication.kt:163`) that both WorkManager compositions use (`PaheBatcherApplication.kt:203` and `PaheBatcherApplication.kt:292`), so however many Workers run they share one ceiling per host instead of each contributing `hls_workers` of their own; previously `maxParallel` (up to 6) multiplied by `hls_workers` (24) could put 144 requests in flight against a single CDN host. `acquire()` is called outside the `try`, so a waiter cancelled while queued holds no permit and releases none.

**Verified 2026-10-13:** 15 new tests - 7 in `HlsHostConcurrencyLimiterTest` observing **actual simultaneous in-flight work** (a counter incremented inside the critical section, not a semaphore-call assertion) for the same-host cap being both reached and never exceeded, independent per-host budgets, cancellation while waiting with no permit leak, failure while a slot is held, and eight repeated episode bursts that neither leak a permit nor deadlock; 2 in `OkHttpHlsClientHostLimiterIntegrationTest` driving the **production** client against a real loopback HTTP origin that withholds its responses until the cap is genuinely observed open at once, asserting peak exactly 3 with the limiter and greater than 3 without it; 5 new `Aes128DecryptorTest` cases including a published NIST SP800-38A two-block CBC known-answer vector, a non-PKCS7 payload that must decrypt, pad retention, non-block-aligned rejection and multi-block chaining; and 1 `EpisodeDownloaderTest` end-to-end proving a non-PKCS7 ciphertext downloads successfully and the on-disk bytes equal Python's raw CBC output. Full JVM suite **1100 tests across 102 suites with 0 failures and 0 errors**; `lintDebug` **38 findings, unchanged** from the pre-change baseline; `assembleDebug` BUILD SUCCESSFUL; the debug APK was inspected from the inside and contains `HlsHostConcurrencyLimiter`, `withHostSlot` and `AES/CBC/NoPadding` and no longer contains an `AES/CBC/PKCS5Padding` string; `scripts/check-doc-citations.ps1` PASS.

**Not ported.** Python's `curl_cffi` 403 fallback (`http.py:99-101`) - a TLS-fingerprint impersonation library with no Android-native equivalent; Android classifies 403 as permanent and does not retry it. **Retry and backoff are NOT a deviation**: Python's `RETRY_ATTEMPTS = 5` and `RETRY_BASE_DELAY = 0.5` with `0.5 * (2**attempt)` (`config.py:4-5`, `http.py:90-108`) are matched exactly by `EpisodeDownloader.kt:790`, `EpisodeDownloader.kt:824` and `EpisodeDownloader.kt:861`, which use the same 5 attempts, the same 500 ms base and the same `RETRY_BASE_DELAY_MS * (1L shl attempt)` curve for the M3U8, the AES key and every segment.

---

## Final verification gate (2026-10-13) — runtime AES parity, redownload/export, notification audio labels, quiet session refresh

This gate introduced **no new production behaviour**. It re-verified behaviour that was built earlier the same day, promoted one previously source-level-only claim to runtime parity, and reconciled the four documentation files against the current source with pointer-only citation fixes. No production, Gradle or test source was modified by this pass. Where something was not observed live it is marked NOT RUN, NOT DEVICE-OBSERVED or NOT DEVICE-VERIFIED with its reason; nothing is upgraded by assertion.

### Workstream A — AES-CBC parity is now RUNTIME parity

The headline change for this file. The real reference interpreter was **executed**, not merely read: WSL ran `<python-ref-3.7.0>/venv/bin/python3` (Python 3.14.4, pycryptodomex 3.24.0) against the source tree `<python-ref-3.7.0>`. (WSL invocation must go through a `.sh` script file; inline `wsl bash -c` through PowerShell breaks.) Reference behaviour `downloader.py:101-103` is `AES.new(key_map[...], AES.MODE_CBC, iv=seg["iv"])` followed by `cipher.decrypt(raw)`; there is NO unpadding anywhere in the reference src tree; `ValueError` is raised on non-block-aligned input and on bad key/IV length, and there is no empty-input guard. Android matches at `Aes128Decryptor.kt:85` with `Cipher.getInstance("AES/CBC/NoPadding")`.

The executed harness produced 14 vectors - 10 decrypt ok, 4 raise (`invalid_block_length_15`, `invalid_block_length_20`, `short_key_8_bytes`, `short_iv_8_bytes`). The committed fixtures `app/src/test/resources/aes_parity_reference.tsv` (3797 bytes, SHA-256 E4E44BAB…D45F) and `.json` (SHA-256 5A292D73…5812) were **re-executed this session and matched byte-for-byte**, so the fixtures' provenance is runtime-established rather than asserted.

Tests: `AesPythonParityTest.kt` 12/12 and `Aes128DecryptorTest.kt` 17/17. The parity suite covers successful decryption bytes, PKCS7-shaped plaintext retention (never stripped), non-PKCS7 trailing bytes accepted, malformed block lengths, invalid key/IV lengths, empty ciphertext, wrong-key agreement, and the NIST SP800-38A F.2.1 two-block CBC known-answer vector.

**The one divergence, intentional and explicitly asserted:** empty ciphertext - Python returns 0 bytes (success), Android returns null (`Aes128Decryptor.kt:68-71`, rationale `:48-50`). Android was deliberately **not** weakened to match: a zero-byte segment is corruption and AGENTS 12 forbids treating incomplete segments as complete. The test name states exactly that: `empty ciphertext divergence is explicitly documented not silently equalized`.

Stated precisely: this is **RUNTIME parity established by executing the reference**, superseding the earlier source-level-only claim in DECISIONS 150.11 that no Python interpreter was available.

### Workstream B — duplicate redownload/export semantics (VERIFIED, behaviour unchanged by this gate)

Python parity anchor: `downloader.py:60-62` skips an episode whose output already exists; Android mirrors it through `EpisodeOutputVerifier.kt:61` `isPresent`, consumed by `DownloadRepository.kt:590` `suppressedOutcome` and `:643` `outputPresent` -> `EnqueueOutcome.AlreadyDownloaded` (the two pointers re-aimed 2026-10-19 after the §3.1 gate work shifted the lines; same facts, plus the destination-probe admission gate below). The verified behaviour, case by case:

1. completed + valid export + re-request -> suppressed, no worker enqueued;
2. completed + segment cache deleted + export intact -> still suppressed, because the predicate reads only `outputUri`/`outputPath` and never `segmentDir`;
3. completed + exported file deleted -> real re-download;
4. unresolvable output URI -> deterministic real re-download, never reported as success;
5. valid old export + intentional new download -> NOT possible today; see the open product decision below.

Individual, batch and watchlist queueing all funnel through `queueResolvedEpisode`, so the duplicate semantics are shared, not duplicated. The MediaStoreExporter same-size branch cannot destroy a newly downloaded local file in production, because `EpisodeDownloadDao.kt:103` (`claimTerminalRequeue`) and `:265` (`beginRetry`) null `outputUri` atomically in the same UPDATE that admits the re-download; residual same-size reuse is crash-recovery of identical bytes and is an already-accepted decision (LIMITATIONS 44.2).

The user-visible notice at `AnimeDetailsViewModel.kt:511` reads "Already downloaded — delete the existing file to download it again" (em dash, UTF-8 U+2014), rendered at `AnimeDetailsScreen.kt:611`. Tests: `EpisodeOutputVerifierRuleTest` 12/12, `AnimeDetailsViewModelTest` 38/38; instrumentation `AnimeDetailsScreenTest` OK (34 tests) on device.

**OPEN PRODUCT DECISION (recorded, not invented):** for a COMPLETED row whose only output is an app-private local file, the notice is unactionable - no delete/force-redownload affordance exists. `PHASE11_ARCHITECTURE_V2.md:653` deferred this to a Phase 13 deliberate UX that never shipped; `SPEC.md:819` requires explicit confirmation before deleting completed user media.

### Workstream C — notification audio-language labels (VERIFIED, behaviour unchanged by this gate)

Persisted metadata only: `EpisodeDownloadEntity.kt:59-60` `audio`, identity `EpisodeId.kt:55-56` `{session}_{epStr}_{audio}`. `DownloadWorker.kt:249` reads the row once and threads `episode.audio` at `:579`, `:702`, `:1199` and `:1205`. The notification text helpers - `DownloadNotificationManager.kt:116` `episodeLine`, `:130` `progressText`, `:140` `completionText`, `:87` `failureText` - carry that audio; the batch aggregates `:157` `batchSummaryText` and `:179` `reasonSummaryText` take NO audio parameter, so a run-level summary can never invent a language.

Fallback labelling is `DownloadRowMapper.kt:165-170` `audioLabelFor`: null/blank -> null, `jpn` -> "Japanese", `eng` -> "English", anything else -> the uppercased code (so `kor` renders `KOR`). `listOfNotNull(...).joinToString(" · ")` leaves no dangling separator and reproduces the pre-audio text byte-for-byte. Individual and batch share one worker, one manager and one set of text helpers, so the semantics cannot drift between the two paths.

Tests: `DownloadNotificationAudioLabelTest` 11/11, `DownloadNotificationTextTest` 8/8. Export identity/filenames unchanged (`ExportFileNames.kt`, `ExportFileNamesTest` 12/12). **NOT DEVICE-OBSERVED:** no live notification carrying a language label was watched on a physical device; the unit tests assert the pure text helpers only.

### Workstream D — quiet session refresh (VERIFIED, behaviour unchanged by this gate)

The user-directed contract: ONE quiet background attempt first; success -> no popup; failure -> prompt from that point on; the prompt is latched so later signals do not silently retry; an explicit user retry clears the latch only after a successful recovery. In `SessionRefreshViewModel.kt`: `:86` `silentAttemptFailed` latch (an instance field, NOT initialised in `init`, so recomposition cannot re-run it), `:96` `silentAttemptInFlight` coalescing guard, `:126-127` latched -> prompt, `:130-131` drop concurrent signals, `:139` quiet success -> no popup, `:141-142`/`:147`/`:151` failure -> latch + prompt, `:153` guard released in `finally`, `:165` `showPromptPreservingVisibleError` preserves an honest error, `:188` latch cleared ONLY on success. The ViewModel is Activity-scoped (`MainActivity.kt:127`) so recreation retains the latch and the collector; the signal bus is a no-replay `MutableSharedFlow(replay = 0, extraBufferCapacity = 4, DROP_OLDEST)` (`SessionRefreshSignal.kt:60-64`). Logging is exception-TYPE-ONLY at `:150` and `:206` - no signed URL, cookie, authorization header or session token reaches any log.

Tests: `SessionRefreshViewModelTest` 12/12 covering all eight required behaviours, `SessionRefreshSignalTest` 4, `WebViewRequestBridgeTest` 19, `CloudflareSessionProviderTest` 17, and `AndroidWebViewRequestBridgeSessionPromptTest` 3 (that one lives in `src/test`, not `androidTest`). **DEVICE-OBSERVED:** the quiet SUCCESS path - a genuine HTTP 403 followed by "Session recovered by the background refresh" with no popup. **NOT DEVICE-VERIFIED:** the failure branch that raises the popup; `SessionRefreshPromptDialogTest`'s signal test was NOT RUN because it would drive repeated REAL `forceRefreshWebViewSession` calls in a 15-second waitUntil loop against the live service.

### Verification — counts of record, device state and documentation reconciliation

| Run | Result |
|-----|--------|
| JVM unit tests (`testDebugUnitTest`) | **105 suites / 1,141 tests / 0 failures / 0 errors / 0 skipped** |
| Lint (`lintDebug`) | **38 warnings, 0 errors** |
| `assembleDebug` | BUILD SUCCESSFUL - APK **119,242,126 bytes**, SHA-256 `6411B70BE9FEA6E2010808A89285BC5678699CA1BA9B934E78117CAEB95B2267` |

Device: exactly one device was connected, serial `REDACTED-SERIAL` (model REDACTED-MODEL), Android user 10 "Android Dev". `firstInstallTime` 2026-10-12 11:56:22 and App Data Size 4,034,385,636 bytes were UNCHANGED before and after the gate (no uninstall, no data clear). A real search returned 5 genuine live results. All four exports were present with unchanged sizes and `is_pending=0`.

Documentation reconciliation was pointer-only across the four docs: `DownloadNotificationManager.kt:133` -> `:179`, `DownloadWorker.kt:423,541` -> `:424,543`, `DownloadRepository.kt:1100` -> `:1191`, `DownloadWorker.kt:955` -> `:994`; the two historical audit records carrying superseded numbers were preserved with explicit SUPERSEDED markers rather than rewritten.

### Verification-kind legend

Three levels are used above and they are never collapsed into one another:

| Level | Meaning |
|-------|---------|
| RUNTIME parity | The Python reference interpreter was actually EXECUTED against the real reference tree and its output compared with Android's (Workstream A) |
| SOURCE-LEVEL parity | The claim was established by reading the reference source only, with no interpreter run - the pre-gate state of Workstream A, now superseded there |
| Test / observation level | A JVM unit test runs production-path code host-side with no device; an instrumentation test exercises the production APK path on a device; a physical-device observation is behaviour watched live on device `REDACTED-SERIAL` outside the test harness |

A passing JVM test is not a device observation, and a source-level reading is not runtime parity. No row here is upgraded beyond the evidence level it actually carries, and the unobserved items stay marked NOT RUN / NOT DEVICE-OBSERVED / NOT DEVICE-VERIFIED with their reasons attached.

## Replace file - Android-native feature parity status (2026-10-13, DECISIONS 152)

This feature has **no Python counterpart**. The reference implementation has no intentional re-download-over-a-valid-export path; the closest reference behaviour is the ordinary duplicate suppression that Python applies when a finished file already exists. It is therefore recorded here as an Android-native addition, not as a port - and the parity claim attached to it is the negative one that matters: it did not change any behaviour that DOES have a reference counterpart.

| Area | Status | Evidence level |
|------|--------|----------------|
| Ordinary duplicate suppression when a valid export exists | UNCHANGED | Test / observation level - `ReplaceFileDuplicateProtectionTest` cases 7-10 plus the pre-existing §29 suites, all green |
| `EpisodeOutputVerifier` and the completed-output guard | UNCHANGED, not weakened | Test / observation level - same suite asserts the gate returns the same answer either side of the feature |
| Confirmation dialog opens on tap, does nothing by itself | IMPLEMENTED | Test / observation level - `ReplaceFileConfirmationTest` cases 1-2 and a physical-device observation of the dialog and its `Cancel` / `Confirm replacement` buttons |
| Cancel, dismiss, navigate away leave export and row untouched | IMPLEMENTED | Test / observation level - `ReplaceFileConfirmationTest` cases 3-6 and a physical-device `Cancel` that restored identical row state |
| Repeated taps / confirmations never start a second run | IMPLEMENTED | Test / observation level - `ReplaceFileConfirmationTest` case 6, `ReplaceFileDuplicateProtectionTest` cases 8 and 10 |
| Old export preserved byte-for-byte when the replacement fails | IMPLEMENTED | Test / observation level - `ReplaceFileOutputSafetyTest` case 16, real Worker run, real filesystem bytes |
| Staged commit with the old export retired only after verification | IMPLEMENTED | Test / observation level - `ReplaceFileOutputSafetyTest` cases 11-15 and 17-19, real Room rows and real MediaStore items |
| No second copy left behind | IMPLEMENTED | Test / observation level - `ReplaceFileOutputSafetyTest` case 15 asserts exactly one `.mp4` and an empty staging directory |
| No cross-episode overwrite | IMPLEMENTED | Test / observation level - `ReplaceFileOutputSafetyTest` case 18 refuses deletion while another row references the URI |
| Notification `Replace file` action | IMPLEMENTED, NOT device-verified | Test / observation level - unit and instrumentation assertions on the label, request code, immutability and failure suffix; a live system-notification tap was never performed |
| Replacement of one of the four real exports on the device | NOT RUN, deliberately | Not run - it would destroy a user's existing export, which this gate is forbidden to do |

No row above is upgraded beyond the evidence level it carries. The negative rows stay negative: the notification action tap and the destruction of a real export were never observed on the device, and neither is claimed.

Verification totals for this feature: instrumentation **19 of 19 required cases passing**; full JVM suite **105 suites / 1,141 tests / 0 failures / 0 skipped**; `lintDebug` **0 errors**; `assembleDebug` BUILD SUCCESSFUL with the APK installed to serial `REDACTED-SERIAL` under Android user 10. All four exports remain present and total 843,942,729 bytes, unchanged from before the feature.

## Episode paging / batch / range - Android-native status (2026-10-17, DECISIONS 153)

The reference has no app-level paging UI: Python fetches and renders every page of a title. The rule
"show 100 episodes per app page (30 at or below 100), build each page from the server's 30-row
windows, batch one page, and offer an independent ordinal range" is therefore an ANDROID-NATIVE
product rule, not a Python parity claim. What is carried over unchanged from the reference is the
SELECTION and EXECUTION semantics it does define.

| Area | Status | Evidence level |
|------|--------|----------------|
| `total` / `per_page` / `last_page` read as OPT-IN values | IMPLEMENTED | Test - `AnimePaheParser.parsePageMeta` |
| App page = 100 over 100 episodes, 30 at or below (exactly 100 included) | IMPLEMENTED | Test - `EpisodePagingTest` app-page sizing |
| Unknown total -> API's own window; never invented, never zero | IMPLEMENTED | Test - `EpisodePagingTest` + `AnimeDetailsViewModelPagingTest` unknown-total case |
| App page built from the API windows its ordinals span (boundary window shared) | IMPLEMENTED | Test - `EpisodePagingTest` API-window mapping |
| Open fetches app page 1 only (4 of 9 windows for a 250-episode title) | IMPLEMENTED | Test - `AnimeDetailsViewModelPagingTest` open case |
| Page switch fetches only that app page's windows; open windows cost nothing | IMPLEMENTED | Test - same suite, `goToPage` / already-open range cases |
| Numbered app-page buttons (first/last + a window around current), a validated page-number input, and Go-to-page visibility derived from the ACTUAL strip presentation (adaptive to the measured width/font scale; hidden when every page is a visible button) | IMPLEMENTED | Test - `EpisodePagingTest` window + `AnimeDetailsScreenTest` 12 pager cases + JVM `AnimeDetailsPageBarBudgetTest` 6/6 (budget width/font-scale adaptation) |
| Evicted window is re-fetched for real, and the page is never silently short | IMPLEMENTED | Test - eviction case + `EpisodeRepositoryLazyListingTest` |
| Page-error retry keeps the selected page and its error slot | IMPLEMENTED | Test - transient-failure case |
| Page-scoped batch queues only that page's distinct episodes | IMPLEMENTED | Test - four page-batch cases |
| Batch ceiling of 100 distinct episodes enforced at EXECUTION, never truncated | IMPLEMENTED | Test - `EpisodePagingTest` + over-ceiling case |
| Watchlist auto-download uses the same ceiling, independent of UI state | UNCHANGED | Test - `WatchlistCheckerTest` (25 tests, untouched) |
| Ordinal range: 100 inclusive, only its windows, visible page unmoved | IMPLEMENTED | Test - three range cases |
| Failed required window -> no partial range reported as success | IMPLEMENTED | Test - range-failure case |
| Individual download, multi-audio identity, already-downloaded, duplicate-enqueue | UNCHANGED | Test - existing suites, all still green |
| Page-scoped batch on a real device | NOT DEVICE-VERIFIED | Not run - the JVM suite is host-side only |
| Pager, page navigation, ordinal range and already-downloaded skip on a real device | DEVICE-VERIFIED | Device `REDACTED-SERIAL` (One Piece, 1,180 eps, 2026-10-17): `Page 1 of 12 · 1180 episodes`, `1 2 3 … 12` buttons + `Go to page` input all tappable; page 5 -> Episode 401+; ordinal range 200-202 from page 1 -> `3 selected`, queued exactly Ep.200 + Ep.201, both `completed`; re-queue of the same range -> `queued 0 · skipped 2 · failed 0`. Page-batch label `Download this page (100 episodes)` accurate (queue contents remain unit-asserted, not tapped on device) |

No row is upgraded beyond the evidence it carries. The device row now records what was actually
observed on `REDACTED-SERIAL` on 2026-10-17 (the numbered pager, page navigation, the independent ordinal
range resolving straight from page 1, and the already-downloaded skip); the exact range queue
identities (Ep. 200/201) are both unit-asserted and device-observed. Two things stay at their
unit-test evidence only: the page-batch queue contents (its label was read on device, the queue was
not executed there to avoid ~100 real downloads) and multi-audio identity (One Piece on this
instance exposes only `jpn`, so there is no multi-audio episode to exercise on this title).

---

### 2026-10-18/19 — Prompt 3 final release gate (DECISIONS 157, LIMITATIONS 50) — device-verified additions

| Behaviour | Python reference | Android implementation | Status | Evidence |
|---|---|---|---|---|
| Skip re-download whose output still exists, even with no DB row | `downloader.py:60-62` (output-file existence) | Admission gate consults `EpisodeExporter.destinationOutput` (`Uri?`) at both entry points (absent row; COMPLETED row with unverifiable pointers) through `destinationSurvives` (probe failures contained → admit); real probe = `MediaStoreExporter.destinationOutput` (Gate A per-anime directory, audio suffix, §30.2 ` (n)` tolerance, owner-scoped, `IS_PENDING` excluded, IO-dispatched, title chain `sessionDao.getById(sessionId)?.title ?: animeTitle`); router mirrors export routing (SAF active → conservative `null`); rejections report `EnqueueOutcome.AlreadyDownloaded` | IMPLEMENTED + DEVICE-VERIFIED | `OutputDestinationProbeGateTest` 4/4 (argument-pinned fake), `MediaStoreDestinationOutputTest` 5/5 (real provider), `EnqueueOutcomeMappingTest` 13/13; live 2026-10-18: Overflow ep1 session-delete → re-enqueue → "Already downloaded — delete the existing file to download it again" + logcat `Suppressed re-submission of c33ffb68-…_1_jpn (destination survives)`; multi-audio `…[jpn] (1).mp4` + `…[eng].mp4` coexist; MediaStore `is_pending=0`, sizes match; residuals: LIMITATIONS 50 (SAF conservative null, legacy flat-name not probed, uninstall-orphan `(1)`) |
| Reconciler must not demote a COMPLETED row whose export survives (URI churn) | (Python has no persistent-URI equivalent; the invariant is the same "output exists → not failed") | `DownloadStateReconciler` probes before the demote CAS and re-points `outputUri` via `EpisodeDownloadDao.repairOutputUriIfState` (CAS on state); probe NULL still demotes "file missing" exactly as before | IMPLEMENTED | `DownloadStateReconcilerTest` 18/18 incl. the severed-URI repair case (real provider); demotion cases unchanged and still green |
| Notification carries the language label | `downloader.py` results table audio column (display-only) | `DownloadRowMapper.audioLabelFor` → `DownloadNotificationManager` (title "Downloading", content `<title> · <audioLabel> — N%`) | VERIFIED UNCHANGED (verify-only) | Live 2026-10-18: progress `… · Japanese — 42%`; completion `… · Japanese` / `… · English`; failure `Download failed — …` + reason line. No production change was made or needed (DECISIONS 157.2) |
| Expired-session recovery must not false-succeed on a revoked-but-present `cf_clearance` | `cloudflare.py` freshness handling (Python has no WebView/cookie-partition problem) | `AndroidCloudflareSession.clearStaleClearanceCookie()` on `forceRefresh=true` only: `removeAllCookies` store-wide reset (main-looper API; flush on IO); prompt timing unchanged | IMPLEMENTED + DEVICE-VERIFIED | Stage-E logcat 2026-10-18: `Cleared stale cf_clearance before forced re-establishment` → `Session established successfully: host=animepahe.pw` → `Session recovered by the background refresh`; `StaleClearanceCookieClearingTest` 2/2 (real `CookieManager`, both storage forms, `__ddg1_`, re-seed cycle); live-cookie evidence that WebView-created cookies defeat per-tuple expiry: DECISIONS 157.3 |
| Range validation bounded by the real available count | `cli.py`/`downloader.py` range end-point validation (catalogue-length only) | `validateRange` gains `EpisodePaging.ordinalCountBound(apiTotal, perPage, apiLastPage)`; OutOfBounds reports the real bound; unknown total short-circuits as before | IMPLEMENTED + DEVICE-VERIFIED | Live 2026-10-18 (Overflow, 291 eps): boundaries 101/201; `101–200` → "100 of 100 episode numbers selected"; `1–999` rejected at most-100; `250–349` rejected past last ep 291 |
| Page-batch, pager, multi-audio identity, already-downloaded, duplicate-enqueue | — | UNCHANGED by this cycle | UNCHANGED | Regression re-run 2026-10-19: full JVM 109 suites / 1,189 tests / 0 failures / 0 errors (`--rerun-tasks`); the seven affected instrumented suites 73/73 on `REDACTED-SERIAL` (user 10); 24-step live journey all PASS (2026-10-18) |

No row above changes any earlier row; this section is purely additive. Pre-existing pointer drift
disclosed (not edited, to keep earlier rows byte-preserved): the `DownloadRepository.kt:929-945`
(`reEnqueue` round-trip), `:1191` (Wi-Fi constraint) and `:223` (`ensureSession` audioLang)
pointers in earlier rows drifted in earlier phases and now sit at `:1137+`, `:1487` and `:317`
respectively — same facts, later lines; only the two pointers this cycle invalidated
(`:555`/`:567`/`:595` → `:590`/`:643`) were re-aimed in place above.

### 2026-10-19 — Phase A whole-codebase audit + behavior-preserving hardening (DECISIONS 158) — additive

| Behaviour | Python reference | Android implementation | Status | Evidence |
|---|---|---|---|---|
| Cancellation must never be swallowed into a fake failure | (Python asyncio cancellation semantics) | `AndroidWebViewRequestBridge` rethrows `CancellationException` ahead of its generic catch; `AndroidCloudflareSession` catch order fixed; `KwikResolver` rethrow; `SessionsViewModel` request-delete/clear-cache rethrow; `SettingsViewModel` / `AppViewModelFactory` / `AnimeDetailsViewModel` / `DownloadStateReconciler` / `PaheBatcherApplication` CE arms | IMPLEMENTED | Full JVM 109 / 1,192 / 0F / 0E; NET-001 / AQR-001 / AQR-002 / AQR-004 / AQR-009 |
| Full-episode byte copy must not run on the CPU-bounded dispatcher | (Python asyncio I/O loop) | `FfmpegEpisodeAssembler.assemble` wraps its blocking body in `withContext(Dispatchers.IO)` | IMPLEMENTED | Full JVM rerun; AQR-003 |
| Per-host HLS cap + session handling must not share a process-wide client cache across instances | (Python has no OkHttp client pool) | `OkHttpHlsClient` timeout-client cache made instance-level | IMPLEMENTED | Full JVM rerun; AQR-005 |
| A remote packed-script payload must not crash the app on a malformed base | (Python `kwik.py` unpack guards) | `JsPacker.unpack` rejects base outside `2..digits.length` (returns input unchanged) | IMPLEMENTED | 3 new `JsPackerTest` cases (base 0 / 1 / 99); full JVM 1,192 total; AQR-007 |
| A watchlist DAO/checker fault must not crash the ViewModel | (Python per-entry isolation) | `WatchlistViewModel` `checkNow` / `startRelink` guarded — honest `FAILED` result or honest unreachable-search state | IMPLEMENTED | Full JVM rerun; AQR-008 |

No row above changes any earlier row; this section is purely additive. Phase A changed no feature
behavior — every edit is a behavior-preserving correctness/hardening fix (CE-rethrow discipline,
correct dispatcher, crash guards), each verified green. AQR-010 (exported `MediaSessionService`
accepts all controllers) was deferred as a product/security decision *in Phase A* (DECISIONS 158.4)
and has since been IMPLEMENTED as the user-directed Option 1 controller policy in Phase A2 — see the
dated section directly below (DECISIONS 159.2).

---

### 2026-10-19 — Phase A2 audit reconciliation + AQR-010 controller policy (DECISIONS 159) — additive

| Behaviour | Python reference | Android implementation | Status | Evidence |
|---|---|---|---|---|
| (Android-native surface — Python has no MediaSession) | — | Media3 1.11.1 controller policy: `MediaSessionControllerPolicy` (`MediaSessionControllerPolicy.kt:52`, the `isControllerAllowed` predicate) accepts own-package OR platform-trusted controllers; `onGetSession` returns null for everyone else (`PaheMediaSessionService.kt:104`, the `return null`) and the session callback's `onConnect` returns `super.onConnect` / `ConnectionResult.reject()` (`PaheMediaSessionService.kt:171`; pointers re-aimed 2026-10-21, AQR-119, and 2026-10-23 after the AQR-138/140 insertions) | IMPLEMENTED (AQR-010 Option 1) | `MediaSessionControllerPolicyTest.kt:15` (own/trusted-foreign/untrusted-foreign/prefix/null-own cases); `javap` evidence against the extracted `media3-session-1.11.1` AAR for both stub paths' `isTrusted`; lint-clean via function-level `@OptIn(UnstableApi)`; live lock-screen/quick-settings/Bluetooth re-test NOT done this round — `adb -s REDACTED-SERIAL shell dumpsys media_session` is the session-presence evidence |
| A cancelled relink search must never land in a newer dialog | (Python has no equivalent UI flow) | `startRelink` captures the opened dialog instance and applies its result only while that same instance is shown (`WatchlistViewModel.kt:293`) | IMPLEMENTED (AQR-040) | `WatchlistRelinkTest.kt:382` cancel→reopen race using the opt-in HTTP hold (`FakeHttpClient.kt:25`) |
| Cancellation rethrows stay regression-tested (NET-001) | Python asyncio cancellation semantics | JVM tests pin all three feasible CE arms: WebView bridge, `KwikResolver`, `SessionsViewModel.launchAction` | IMPLEMENTED (AQR-017/AQR-051) | `AndroidWebViewRequestBridgeCancellationTest.kt:84`, `KwikResolverTest.kt:235`, `SessionsViewModelCancellationTest.kt:160-171`; the `AndroidCloudflareSession` arm is WebView-bound → instrumentation-only, deferred with AQR-051 |
| Connection-pooling parity claim corrected to the truth | `http.py:TCPConnector` | §5.4 Connection pooling row, CORRECTED 2026-10-19: per-host semaphore shared, OkHttp pool per-worker | ⚠️ claim narrowed — code fix deferred as a product decision (DECISIONS 159.5), RESOLVED 2026-10-20 by A2.1 Decision 5 | DATA-012; `DownloadWorkerFactory.kt:113` |
| Test assertions must be falsifiable (no tautologies, no `toString()` scraping) | — | TEST-010's five sites rewritten stronger, TEST-011 value-equality, TEST-008 dead helpers deleted, TEST-009-family left as recorded, AQR-052 mojibake fixed | VERIFIED | full JVM forced rerun 113 / 1,211 / 0F / 0E / 0S; androidTest compile green |

No row above changes any earlier row; this section is purely additive. Phase A2 changed no feature
behavior: every edit is a behavior-preserving correctness/hardening/test fix, each verified green.
Pending user decisions are enumerated in DECISIONS 159.5 — nothing was silently skipped.

---

### 2026-10-23 - Pause/Resume run-ownership race fix (DECISIONS 164) - additive

| Behaviour | Python reference | Android implementation | Status | Evidence |
|---|---|---|---|---|
| A run-owned state mutation is accepted only when the row matches BOTH the expected state and the run's persisted `downloadRunId`; ownership check and mutation are atomic | Python's single asyncio download loop per process has no second in-flight run able to write a stale state, so no equivalent guard exists or is required | Room v5 adds the nullable `downloadRunId` column (`PaheDatabase.kt:203`); a run identity is minted per `runWork()` (`DownloadWorker.kt:284`) and stamped inside the atomic claim (`claimEntryRunWithRunId`, `EpisodeDownloadDao.kt:297`; call site `DownloadWorker.kt:608`); the five run-owned `*IfOwned` writers require state AND token in one UPDATE (`EpisodeDownloadDao.kt:186`, `EpisodeDownloadDao.kt:314`, `EpisodeDownloadDao.kt:428`, `EpisodeDownloadDao.kt:484`, `EpisodeDownloadDao.kt:538`) | IMPLEMENTED AND VERIFIED | `RunOwnershipDeviceTest.kt:182` barrier test 43/43 on device (2026-10-23 00:38); `StateTransitionTest.kt:338`; `MigrationTest.kt:358` |
| A stale, cancelled Run A can no longer overwrite Run B's claimed row; Run B still finishes ASSEMBLING -> COMPLETED and exports | (no Python equivalent - single writer) | Stale writes affect 0 rows and are logged as ownership losses (`DownloadWorker.kt:322` cancellation variant, `DownloadWorker.kt:1089` auto-resume park variant) | VERIFIED | `RunOwnershipDeviceTest.kt:182` (stale A loses, B finishes the full pipeline) |
| Genuine cancellation of the CURRENT run still persists CANCELLED; user, repository and reconciliation actors keep state-only compare-and-set | Python cancel path unchanged (single writer) | Cancellation actor semantics unchanged; run-owned cancellation writes carry the token | VERIFIED | `RunOwnershipDeviceTest.kt:382`; `PauseResumeDeviceTest.kt:132`; `RealProcessDeathPausedDeviceTest.kt:126` |
| Room v4 -> v5 migration preserves all pre-upgrade rows; `downloadRunId` is NULL for pre-upgrade rows and is claimed by the first claiming run | (n/a - schema-free reference) | `PaheDatabase.kt:201`, `PaheDatabase.kt:203`; no destructive fallback (`PaheDatabase.kt:94`) | VERIFIED | `MigrationTest.kt:358`, `MigrationTest.kt:409`, `MigrationTest.kt:431` |
| SCOPE BOUNDARY (documented, not a parity claim): the progress flusher (`updateProgress`, `EpisodeDownloadDao.kt:140`) and `updateTotalSegments` (`EpisodeDownloadDao.kt:612`) are outside the ownership guard by design | (n/a) | Accepted, fields-only residual - a stale run may land progress/total fields; state and ownership cannot be overwritten | DOCUMENTED RESIDUAL | DECISIONS 164 bullet 6; LIMITATIONS 4 |

Device runs for this round (2026-10-23): 43 + 134 + 95 + 26 + 1 + 1 = 300 tests passing across six runs; JVM Debug 114 classes / 1,236 tests and Release 113 / 1,231, all 0 failures, 0 errors, 0 skipped (fresh rerun 2026-10-23); lintDebug + lintRelease 0 errors, 0 fatal (fresh rerun 2026-10-23). Push readiness, public-release security and release signing are separate gates assessed in their own rounds - deliberately NOT merged into this parity addition.

[Back to Index](Index.md)