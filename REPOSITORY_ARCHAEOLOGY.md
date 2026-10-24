# PaheBatcher Python Reference — Complete Repository Archaeology

## Phase 1 Analysis Report

> **Reconciliation note (post-Phase 11.5):** this archaeology was performed against Python reference **3.5.2**, which is no longer present on disk. The current local reference is **`<python-ref-3.7.0>`** (`pyproject.toml` / `src/pahebatcher/config.py` `VERSION = "3.7.0"`; reconciled from 3.6.0 on 2026-10-12). The 3.7.0 tree keeps the same module layout described below, and the line-number citations used throughout `PARITY.md`/`LIMITATIONS.md`/`DECISIONS.md` were spot-verified to match 3.6.0 (2026-09-28) and re-verified against 3.7.0 (2026-10-12). Version strings in §1.1/§1.3 below are the historical 3.5.2 values.

---

# 1. Repository Architecture

## 1.1 Directory Structure

```
pahebatcher/
├── .gitignore
├── LICENSE                          (MIT)
├── METHODOLOGY.md                   (Development methodology document)
├── Makefile                         (Unix build/test/lint targets)
├── README.md                        (Full documentation)
├── pahebatcher.toml.example         (Example config file)
├── pyproject.toml                   (Package config, dependencies, tools)
├── scripts/
│   └── benchmark.py                 (Coherence benchmark script)
├── src/
│   └── pahebatcher/
│       ├── __init__.py              (Version: 3.5.2)
│       ├── __main__.py              (Entry: calls main.main())
│       ├── main.py                  (CLI parsing, service wiring, action dispatch)
│       ├── models.py                (Data models: EpisodeInfo, AnimeInfo, StreamInfo, AppContext)
│       ├── config.py                (Constants: VERSION, HLS_WORKERS, RETRY_ATTEMPTS, regex patterns)
│       ├── config_manager.py        (TOML config persistence: load/save/validate)
│       ├── utils.py                 (Utilities: sanitize, ep_prefix, fmt_bytes, compact_ep_range)
│       ├── tls.py                   (Hardened SSL context: TLSv1.2+, forward secrecy)
│       ├── cache.py                 (Async TTL cache with LRU eviction)
│       ├── store.py                 (SegmentStore: atomic writes, ffmpeg assembly, cleanup)
│       ├── http.py                  (HttpClient: aiohttp session, curl fallback, retry)
│       ├── solver.py                (FlareSolverr client: session management, request handling)
│       ├── downloader.py            (EpisodeDownloader, BatchOrchestrator: 2-stage pipeline)
│       ├── stream.py                (MPV streaming, local HLS proxy, episode navigation)
│       ├── sessions.py              (Session manager: list, resume, delete, clear)
│       ├── watchlist.py             (Persistent watchlist: add/list/show/remove/check/relink)
│       ├── extract/
│       │   ├── __init__.py          (Empty)
│       │   ├── kwik.py              (JsPacker, Kwik URL extraction, stream resolution)
│       │   ├── m3u8.py              (HLS M3U8 parser, AES key extraction)
│       │   └── scanner.py           (AnimePahe API scanner, URL validation, variant discovery)
│       └── ui/
│           ├── __init__.py          (Empty)
│           ├── console.py           (Rich console singleton, ASCII banner)
│           ├── dashboard.py         (Live progress dashboard with per-episode state)
│           ├── tables.py            (Table renderers: episodes, search, summary)
│           └── prompts.py           (Episode selection, download confirmation, settings wizard)
└── tests/
    ├── conftest.py                  (Fixtures: mock solver, mock http, sample data)
    ├── test_cache.py                (8 tests)
    ├── test_coherence.py            (7 tests)
    ├── test_config_extended.py      (6 tests)
    ├── test_config_manager.py       (10 tests)
    ├── test_downloader.py           (7 tests)
    ├── test_http.py                 (8 tests)
    ├── test_kwik.py                 (7 tests)
    ├── test_m3u8.py                 (8 tests)
    ├── test_models.py               (11 tests)
    ├── test_prompts.py              (11 tests)
    ├── test_retry.py                (4 tests)
    ├── test_scanner.py              (12 tests)
    ├── test_scanner_extended.py     (7 tests)
    ├── test_sessions.py             (4 tests)
    ├── test_solver.py               (16 tests)
    ├── test_store.py                (7 tests)
    ├── test_store_extended.py       (7 tests)
    ├── test_tls.py                  (4 tests)
    ├── test_ui.py                   (5 tests)
    ├── test_utils.py                (20 tests)
    └── test_utils_extended.py       (21 tests)
                                    ──────────
                                    195 total
```

## 1.2 Module Dependencies (Import Graph)

```
Level 0 (no internal deps):
  models.py, config.py, utils.py, tls.py, cache.py

Level 1 (depend on L0):
  store.py          → utils.py
  http.py           → config.py, tls.py
  solver.py         → cache.py, config.py

Level 2 (depend on L0+L1):
  extract/m3u8.py   → http.py
  extract/kwik.py   → config.py, models.py, solver.py
  extract/scanner.py → config.py, models.py, utils.py, solver.py

Level 3 (depend on L0-L2):
  ui/console.py     → config.py
  ui/dashboard.py   → config.py, utils.py
  ui/tables.py      → utils.py, models.py
  ui/prompts.py     → models.py, utils.py, scanner.py

Level 4 (depend on L0-L3):
  downloader.py     → cache.py, models.py, store.py, utils.py, kwik.py, m3u8.py, http.py, solver.py, dashboard.py
  stream.py         → models.py, kwik.py, m3u8.py, http.py, solver.py, console.py
  sessions.py       → utils.py
  watchlist.py      → config_manager.py, scanner.py, utils.py, models.py, downloader.py, http.py, solver.py

Level 5 (orchestrator):
  main.py           → everything above
  config_manager.py → (standalone)
```

## 1.3 Package Configuration

**pyproject.toml key details:**
- Name: `pahebatcher`
- Version: `3.5.2`
- Python: `>=3.11`
- Entry point: `pahebatcher = "pahebatcher.main:main"`
- Dependencies:
  - `rich>=13.9.0` — TUI rendering
  - `aiohttp>=3.10.0` — async HTTP client
  - `pycryptodomex>=3.21.0` — AES-128 HLS decryption
  - `curl-cffi>=0.7.0` — TLS fingerprint impersonation (Cloudflare bypass)
- Dev dependencies:
  - `pytest>=8.0`, `pytest-asyncio>=0.24`, `pytest-cov>=5.0`
  - `mypy>=1.11` (strict mode)
  - `ruff>=0.6` (ALL rules)

---

# 2. Search System

## 2.1 Implementation Location

`src/pahebatcher/extract/scanner.py` — `AnimePaheScanner.search()` classmethod

## 2.2 How Search Works

1. **Endpoint**: `https://{host}/api?m=search&q={query}`
   - Tries hosts in order: `[host, "animepahe.pw", "animepahe.com", "animepahe.org"]`
   - Host rotation for resilience

2. **Request mechanism**: `solver.fetch_json(url)` — uses FlareSolverr to fetch the API endpoint (bypasses Cloudflare)

3. **Response parsing**: Expects JSON with `"data"` key containing list of results
   - Each result has: `session` (UUID), `title`, `type`, `year`, `episodes`, `score`

4. **Result data structure**: `list[dict[str, Any]]` — each dict has keys like `session`, `title`, `type`, `year`, `episodes`, `score`

5. **No pagination**: Search returns all results in one response

6. **No explicit caching**: Search results are not cached (only scan results are cached)

## 2.3 Network Flow

```
User query → AnimePaheScanner.search()
  → solver.fetch_json("https://{host}/api?m=search&q={query}")
    → FlareSolverr POST with cmd: "request.get"
    → Returns JSON with data array
  → Returns list[dict]
```

## 2.4 Error Handling

- Catches all exceptions per host, tries next host
- Returns empty list if all hosts fail
- Logs debug messages for failures

---

# 3. Anime Metadata

## 3.1 Implementation Location

`src/pahebatcher/extract/scanner.py` — `AnimePaheScanner.scan()`, `_fetch_title()`, `_parse_episode_page()`

## 3.2 How Metadata is Fetched

1. **Title**: `_fetch_title()` fetches the anime page HTML via `solver.fetch_html(f"https://{host}/anime/{session}")`
   - Extracts from `<h1>` tag or `<title>` tag
   - Deduplicates doubled titles (common pattern on AnimePahe)
   - Falls back to "Unknown Anime"

2. **Episode list**: `_fetch_page(page)` fetches API endpoint:
   - Primary: `https://{host}/api?m=release&id={session}&sort=episode_asc&page={page}`
   - Fallback: `https://{host}/api/{session}/releases?sort=episode_asc&page={page}`
   - Returns paginated JSON with `data` array and `last_page`

3. **Variant discovery**: `discover_all_sessions()` searches for related sessions (SUB/DUB variants) by searching the anime title and matching normalized titles

## 3.3 Data Models

**AnimeInfo**:
```python
@dataclass
class AnimeInfo:
    session: str          # UUID
    title: str            # Anime title
    host: str             # Domain (animepahe.com/pw/org)
    total: int            # Unique episode count
    episodes: list[EpisodeInfo]
    has_session: bool     # Whether cache directory exists
```

**EpisodeInfo**:
```python
@dataclass
class EpisodeInfo:
    number: float         # Episode number (e.g., 1.0, 13.5)
    session: str          # Episode session UUID
    title: str            # Episode title
    fansub: str           # Fansub group name
    audio: str            # "jpn" or "eng"
    play_url: str         # https://{host}/play/{anime_session}/{ep_session}
```

## 3.4 API Endpoints

| Purpose | Endpoint | Method |
|---------|----------|--------|
| Search | `https://{host}/api?m=search&q={query}` | GET via FlareSolverr |
| Episode page | `https://{host}/api?m=release&id={session}&sort=episode_asc&page={page}` | GET via FlareSolverr |
| Episode page (alt) | `https://{host}/api/{session}/releases?sort=episode_asc&page={page}` | GET via FlareSolverr |
| Title page | `https://{host}/anime/{session}` | GET via FlareSolverr |

---

# 4. Episode Discovery

## 4.1 Implementation Location

`src/pahebatcher/extract/scanner.py` — `AnimePaheScanner.scan()`

## 4.2 How Episodes are Scanned

1. **Cache check**: First checks `_scan_cache.json` in cache directory
   - Searches: legacy path + any `*_{session}/_scan_cache.json` via glob
   - Respects TTL (default 60 min, configurable)
   - Returns cached `AnimeInfo` if valid

2. **Variant discovery**: Calls `discover_all_sessions()` to find all related sessions (SUB/DUB variants)
   - Searches by title, matches normalized titles

3. **Title fetch**: Calls `_fetch_title()` to get anime title from HTML

4. **Episode scanning**: For each session, paginates through episode API:
   - Fetches page 1, 2, ... until `last_page`
   - Parses each page via `_parse_episode_page()`
   - Deduplicates by `(number, audio)` tuple
   - Adds `REQUEST_DELAY` (0.4s) between pages

5. **Cache save**: Saves scan results to `_scan_cache.json`

## 4.3 Episode Numbering

- Episodes are stored as `float` (supports fractional episodes like 13.5)
- `ep_str` property returns `str(int(number))` for whole numbers, `str(number)` for fractional
- Sorting: by `(number, audio)` — episodes grouped by number, SUB before DUB

## 4.4 Incremental Scanning

- Cache TTL (default 60 min) prevents re-scanning
- Cache is glob-stable: any cache file with matching session UUID is used
- Watchlist check forces `cache_ttl=0` for fresh scan

---

# 5. SUB/DUB Handling

## 5.1 Detection

**Episode level** (`_parse_episode_page()`):
- Primary: `audio` field from API response (normalized to lowercase)
- Secondary: Title-based detection — if `audio="jpn"` but title contains "DUB", set `audio="eng"`
- Secondary: If `audio="eng"` but title contains "SUB", set `audio="jpn"`

**Play page level** (`_parse_resolution_buttons()`):
- `data-audio="eng"` attribute → DUB
- CSS class containing "eng" → DUB
- Text content containing "dub" → DUB
- `data-fansub` attribute for fansub name

## 5.2 Storage/Disambiguation

- Episodes are stored in `AnimeInfo.episodes` list
- Distinguished by `(number, audio)` tuple
- `get_variant(number, audio)` returns specific variant
- `get_all_variants(number)` returns all variants for an episode number

## 5.3 User Selection

- `audio_lang` setting: `"jpn"` (SUB) or `"eng"` (DUB)
- Audio fallback: If requested audio not available, falls back to any available variant
- Streaming: Mid-session audio toggle via `A` key (rebuilds playlist with other audio)

## 5.4 Cache Directory Structure

```
pahe_cache/
  Spy_x_Family_f1a5749e/
    Ep_001_JPN/
    Ep_001_ENG/      (if DUB variant downloaded)
    Ep_002_JPN/
```

---

# 6. Stream Resolution

## 6.1 Implementation Location

`src/pahebatcher/extract/kwik.py` — `extract_stream()`, `_resolve_kwik()`

## 6.2 Resolution Flow

### Step 1: Fetch Play Page
- `solver.request(play_url)` — FlareSolverr fetches the play page
- Play URL format: `https://{host}/play/{anime_session}/{episode_session}`
- Returns HTML with resolution menu

### Step 2: Parse Resolution Buttons
- `_parse_resolution_buttons(html)` extracts from `<div id="resolutionMenu">`
- Each button has: `data-src` (Kwik URL), `data-resolution`, `data-audio`, `data-fansub`
- Falls back to regex extraction if no menu found

### Step 3: Quality Selection
- Filters by requested audio (SUB/DUB)
- Selects closest resolution ≤ user preference
- Falls back to highest available if none match

### Step 4: Audio Selection
- 3-strategy detection: `data-audio` attribute → CSS class → text content
- Falls back from DUB to SUB when DUB unavailable

### Step 5: Resolve Kwik URL
`_resolve_kwik()` uses 4 strategies in order:

1. **curl_cffi direct**: TLS impersonation bypasses most Cloudflare
   - `curl_cffi.requests.get(url, impersonate="chrome120", headers=_HEADERS)`
   - Extracts M3U8 URL from response HTML

2. **curl_cffi + domain rotation**: Tries alternative Kwik TLDs
   - TLDs: `["cx", "gg", "si", "me", "net", "in", "cc"]`
   - Swaps domain: `kwik.si` → `kwik.cx` → etc.

3. **FlareSolverr**: Full browser-based resolution
   - `solver.request(url, cache=False, max_timeout=180000, wait=5000)`

4. **FlareSolverr + domain rotation**: Same as above with TLD swapping

### Step 6: Extract M3U8 URL
`_extract_m3u8(html)` uses 3 strategies:

1. **Direct regex**: `r"(https?://[^\s'\"\\>]+(?:uwu\.m3u8|\.m3u8)[^\s'\"\\>]*)"`
2. **JS deobfuscation**: Eval-unwrap + `JsPacker.unpack()` for packed scripts
3. **Source tag**: `<source src="...m3u8...">`

## 6.3 JsPacker Implementation

`JsPacker.unpack()` decodes eval-based JS packer (`p,a,c,k,e,d`):
- Extracts payload, base, count, and mapping from packed string
- Reconstructs variable names from base-N encoding
- Substitutes obfuscated identifiers with decoded values

## 6.4 Network Flow

```
extract_stream(solver, play_url, quality, audio, cookies_str)
  → solver.request(play_url)  [FlareSolverr]
  → _parse_resolution_buttons(html)
  → Quality/audio selection
  → _resolve_kwik(solver, chosen_url, cookies_str)
    → curl_cffi direct
    → curl_cffi domain rotation
    → solver.request(kwik_url)  [FlareSolverr]
    → FlareSolverr domain rotation
  → _extract_m3u8(html)
  → StreamInfo(url=m3u8_url, cookies, user_agent, referer)
```

---

# 7. HLS Parsing

## 7.1 Implementation Location

`src/pahebatcher/extract/m3u8.py`

## 7.2 M3U8 Parsing

**`parse_m3u8(content, base_url)`**:

1. **Master playlist detection**: If `#EXT-X-STREAM-INF` present, returns empty list (caller must fetch variant)
2. **Media playlist parsing**:
   - `#EXT-X-MEDIA-SEQUENCE:{n}` — sets sequence number
   - `#EXT-X-KEY:METHOD=AES-128,URI="{url}",IV=0x{hex}` — extracts key URL and IV
   - Non-comment lines → segment URLs (joined with base_url)
   - Segments track: `url`, `key_url`, `iv`
   - Sequence number auto-increments per segment

**`resolve_m3u8(http, content, base_url, headers)`**:
1. If `parse_m3u8` returns segments, returns them
2. Otherwise, parses as master playlist
3. Follows **last variant** (highest quality by HLS convention)
4. Fetches variant playlist and parses it

## 7.3 Quality Selection

- Master playlist: `#EXT-X-STREAM-INF` lines with bandwidth/resolution
- Current implementation: follows **last variant** (highest quality)
- No explicit quality selection logic in parser — relies on server providing ordered variants

## 7.4 Audio Track Selection

- Not explicitly handled in M3U8 parser
- Audio selection happens at play page level (choosing SUB/DUB Kwik URL)
- HLS streams are typically single-audio

## 7.5 URL Resolution

- All URLs resolved via `urllib.parse.urljoin(base_url, relative_url)`
- Handles both absolute and relative URLs

---

# 8. Decryption

## 8.1 Implementation Location

`src/pahebatcher/downloader.py` — `EpisodeDownloader.run()`

## 8.2 AES-128 Decryption Flow

1. **Key extraction**: From `#EXT-X-KEY:METHOD=AES-128,URI="{url}"` in M3U8
2. **Key fetching**: `http.get(key_url, headers)` — cached with 1-hour TTL
3. **IV handling**:
   - If explicit IV in M3U8: `bytes.fromhex(iv_hex.lstrip("0xX"))`
   - If no IV: `(seq_num).to_bytes(16, "big")` (segment sequence number)
4. **Decryption**: `AES.new(key, AES.MODE_CBC, iv=iv).decrypt(raw_data)`
5. **Library**: `Cryptodome.Cipher.AES` (pycryptodomex)

## 8.3 When Decryption is Needed

- Only when `#EXT-X-KEY:METHOD=AES-128` is present in the M3U8 playlist
- If `key_url` is None for a segment, no decryption needed
- AES key is cached to avoid re-fetching for each segment

## 8.4 Key Caching

- `TTLCache(ttl=3600.0, max_size=128)` — 1-hour TTL, 128 max entries
- Shared across all episodes in a batch via `shared_key_cache`

---

# 9. Downloading

## 9.1 Implementation Location

`src/pahebatcher/downloader.py` — `EpisodeDownloader`, `BatchOrchestrator`

## 9.2 Two-Stage Pipeline

### Stage 1: Resolver (Serial)
- Single worker, processes episodes one at a time
- Fetches play page → extracts Kwik URL → resolves to M3U8
- Puts `(EpisodeInfo, StreamInfo)` into download queue
- `resolve_ahead` controls queue capacity (default: 999 + max_parallel)

### Stage 2: Downloaders (Concurrent)
- N workers (default: 2 `max_parallel`)
- Each worker pulls from download queue
- Downloads HLS segments concurrently per episode

## 9.3 Segment Download

```python
async def fetch_one(idx, seg):
    async with seg_sem:  # Semaphore(hls_workers)
        raw = await http.get(seg["url"], headers)
        if seg["key_url"]:
            cipher = AES.new(key_map[seg["key_url"]], AES.MODE_CBC, iv=seg["iv"])
            raw = cipher.decrypt(raw)
        await loop.run_in_executor(None, store.write_seg, idx, raw)
        dash.seg_done(key, len(raw))
```

- Concurrent segment downloads via `asyncio.gather()` with `return_exceptions=True`
- Semaphore limits concurrent segments per episode (default: 24 `hls_workers`)
- Segment failures collected, episode fails if any segments fail

## 9.4 Progress Tracking

- `Dashboard` class tracks per-episode progress
- States: resolving → queued → downloading → remuxing → done/fail
- Metrics: segment count, percentage, speed, ETA, file size

## 9.5 Error Handling and Retry

**Episode-level retry**:
- `max_attempts = auto_retry + 1` (default: 3 attempts)
- Exponential backoff: `1.5s * 2^attempt`
- Permanent errors skip retry: "No Kwik link", "No segments"

**Resolver-level retry**:
- `max_attempts = auto_retry + 1`
- Timeout handling: `asyncio.wait_for(..., timeout=120.0)`
- Exponential backoff: `2s * 2^attempt`

**Batch-level retry**:
- After all episodes processed, failed episodes retried
- `max_batch_attempts = auto_retry + 1`
- Delay between batch retries: `2s * batch_attempt`

## 9.6 HTTP Retry (Low-Level)

`HttpClient.get()`:
- 5 attempts with exponential backoff: `0.5s * 2^attempt`
- aiohttp 403 → curl_cffi fallback
- Catches: `TimeoutError`, `aiohttp.ClientError`, `OSError`

## 9.7 Bounded Concurrency

- **Episode concurrency**: `max_parallel` (1-6, default 2)
- **Segment concurrency**: `hls_workers` (8-32, default 24) per episode
- **Total concurrent streams**: `max_parallel * hls_workers` (default: 48)
- Connection pool: `limit_per_host=hls_workers`, `keepalive_timeout=45s`

---

# 10. Atomic Segment Writes

## 10.1 Implementation Location

`src/pahebatcher/store.py` — `SegmentStore.write_seg()`

## 10.2 Write Mechanism

```python
def write_seg(self, idx: int, data: bytes) -> None:
    tmp = self.seg_path(idx).with_suffix(".tmp")
    tmp.write_bytes(data)
    tmp.rename(self.seg_path(idx))
```

1. Write to `{idx:06d}.tmp` (temporary file)
2. Rename to `{idx:06d}.ts` (atomic on same filesystem)

## 10.3 Completion Detection

```python
def done_indices(self) -> set[int]:
    return {int(p.stem) for p in self.dir.glob("??????.ts")}
```

- Only `.ts` files count as completed
- `.tmp` files are incomplete (crash left them)
- On restart, only missing segments are downloaded

## 10.4 Partial Segment Handling

- `.tmp` files are ignored by `done_indices()`
- On restart, incomplete segments are re-downloaded
- No explicit cleanup of `.tmp` files (they remain until overwritten)

---

# 11. Resume/Recovery

## 11.1 Implementation Location

`src/pahebatcher/downloader.py` — `EpisodeDownloader.run()`, `BatchOrchestrator.download()`

## 11.2 Download State Persistence

**Per-episode state**:
- Segment files in cache directory: `pahe_cache/{title}_{session}/Ep_{num}_{AUDIO}/`
- `session.json` metadata file
- No explicit database — file system IS the state

**Per-session state**:
- `session.json` in cache root: `{"title": ..., "url": ..., "updated": ...}`
- Episode directories: `Ep_001_JPN/`, `Ep_002_JPN/`, etc.

## 11.3 Resume Mechanism

1. **Check existing MP4**: If output file exists and size > 0, skip entirely
2. **Check completed segments**: `store.done_indices()` reads existing `.ts` files
3. **Download only missing**: `pending = [(i, s) for i, s in enumerate(segments) if i not in done_set]`
4. **Reassemble**: After all segments complete, run ffmpeg assembly

## 11.4 Crash Recovery

- Atomic segment writes ensure consistency
- `.tmp` files are incomplete, `.ts` files are complete
- On restart, `done_indices()` returns only completed segments
- No explicit state machine — file existence IS state

## 11.5 What Survives Crashes

- All completed segment files (`.ts`)
- Session metadata (`session.json`)
- Partial segments (`.tmp`) are ignored, re-downloaded
- Completed MP4 files are preserved

---

# 12. MP4 Assembly

## 12.1 Implementation Location

`src/pahebatcher/store.py` — `SegmentStore.assemble()`

## 12.2 FFmpeg Invocation

**Primary method (concat demuxer)**:
```python
ffmpeg -y -f concat -safe 0 -i concat.txt -c copy -movflags +faststart {out}
```

**Fallback method (pipe mode)**:
```python
ffmpeg -y -i pipe:0 -c copy -movflags +faststart {out}
```
- Reads segments via stdin pipe
- Used when concat demuxer fails on malformed TS

## 12.3 Assembly Flow

1. Check all segments exist: `missing = [i for i in range(n_segments) if not self.seg_path(i).exists()]`
2. If missing, return False
3. Create `concat.txt` with file paths
4. Run ffmpeg with concat demuxer
5. If fails, fall back to pipe mode
6. Clean up `concat.txt` on success
7. Return success/failure

## 12.4 Output Format

- MP4 with `+faststart` flag (moov atom at beginning)
- Stream copy (`-c copy`) — no re-encoding
- Timeout: 600 seconds per assembly

## 12.5 Cleanup

- `store.cleanup()` removes the episode directory
- Called after successful assembly unless `keep_temp=True`
- Orphan cleanup: cache dirs >24h old removed on exit

---

# 13. Caching

## 13.1 Scan Cache

**Location**: `pahe_cache/{title}_{session}/_scan_cache.json`

**Contents**:
```json
{
  "cached_at": 1234567890,
  "title": "...",
  "session": "...",
  "host": "...",
  "total": 12,
  "episodes": [...]
}
```

**TTL**: Configurable via `cache_ttl` (default: 60 minutes)

**Lookup**: Glob-based — finds any cache file with matching session UUID

## 13.2 AES Key Cache

**Implementation**: `TTLCache(ttl=3600.0, max_size=128)` in `EpisodeDownloader`

**Purpose**: Avoid re-fetching AES keys for each segment

**Scope**: Shared across all episodes in a batch

## 13.3 FlareSolverr Response Cache

**Implementation**: `TTLCache(ttl=120.0, max_size=256)` in `Solver`

**Purpose**: Cache FlareSolverr responses to avoid re-fetching same URLs

**Scope**: Per-solver instance

## 13.4 DNS Cache

**Implementation**: `aiohttp.TCPConnector(ttl_dns_cache=300)`

**Purpose**: Cache DNS lookups for 5 minutes

## 13.5 Cache Storage

All caches are in-memory (Python objects). Only scan cache persists to disk.

---

# 14. Sessions/Cookies

## 14.1 FlareSolverr Integration

**Implementation**: `src/pahebatcher/solver.py` — `Solver` class

**Session Management**:
- Creates persistent FlareSolverr sessions via `sessions.create`
- Auto-rotates on "session not found" errors
- Destroys sessions on cleanup

**Cookie Handling**:
- User cookies injected via `cookie_string` config
- Parsed as semicolon-delimited `name=value` pairs
- Passed to FlareSolverr in request body

**Request Flow**:
1. Ensure session exists
2. POST to FlareSolverr with `cmd: "request.get"`
3. Include: URL, session ID, user cookies, timeout, wait
4. Handle: session errors, Cloudflare blocks, rotation

## 14.2 curl_cffi Integration

**Purpose**: TLS fingerprint impersonation for direct HTTP requests

**Implementation**: `src/pahebatcher/http.py` — `_curl_fetch()`, `_sync_curl_fetch()`

**Usage**:
- Fallback when aiohttp gets 403
- Direct Kwik URL resolution (bypasses FlareSolverr)
- `impersonate="chrome120"` for Chrome TLS fingerprint

## 14.3 User-Agent Handling

- Kwik requests: Fixed Chrome UA string
- FlareSolverr: Uses browser's real UA
- HttpClient: Default aiohttp UA

## 14.4 Session Persistence

- FlareSolverr sessions are ephemeral (lost on restart)
- User cookies can be persisted in `pahebatcher.toml` via `cookie_string`
- No persistent cookie store beyond config file

---

# 15. Configuration

## 15.1 Implementation Location

`src/pahebatcher/config_manager.py` — `ConfigManager`

## 15.2 Configuration File

**Format**: TOML (`pahebatcher.toml` in current directory)

**Location**: `./pahebatcher.toml` (project root)

## 15.3 All Configurable Options

| Key | Type | Default | Valid Values | Effect |
|-----|------|---------|--------------|--------|
| `quality` | int | `1080` | `360`, `720`, `1080` | Video resolution |
| `audio_lang` | str | `"jpn"` | `"jpn"`, `"eng"` | Audio language (SUB/DUB) |
| `max_parallel` | int | `2` | `1`-`6` | Concurrent episode downloads |
| `hls_workers` | int | `24` | `8`-`32` | HLS segment workers per episode |
| `output_dir` | str | `"."` | Any valid path | Download output directory |
| `keep_temp` | bool | `false` | `true`/`false` | Keep .ts files after muxing |
| `resolve_ahead` | int | `999` | `0`+ | Episodes resolver stays ahead |
| `cache_ttl` | int | `60` | `0`+ (minutes) | Scan cache TTL |
| `cookie_string` | str | `""` | Any | Browser cookies for Cloudflare |
| `auto_retry` | int | `2` | `0`-`2` | Auto-retry failed episodes |

## 15.4 Configuration Loading

1. `ConfigManager.load()` reads `pahebatcher.toml`
2. CLI args override loaded values
3. Interactive wizard saves to TOML
4. `config set` command updates TOML

## 15.5 Validation

- Quality: must be in `(360, 720, 1080)`
- Audio: must be in `("jpn", "eng")`
- Parallel: clamped to `[1, 6]`
- Workers: clamped to `[8, 32]`
- Retry: clamped to `[0, 2]`

---

# 16. Storage

## 16.1 Directory Structure

```
./downloads/                          (default output)
  {sanitized_title}/
    Ep 001 - Episode Title.mp4
    Ep 002 - Episode Title.mp4
    ...

./pahe_cache/                         (segment cache)
  {sanitized_title}_{session}/
    session.json                      (metadata)
    Ep_001_JPN/
      000000.ts
      000001.ts
      ...
      concat.txt                      (temporary)
    Ep_002_JPN/
      ...
```

## 16.2 File Naming

- Output: `Ep {prefix} - {title}.mp4` where prefix is `{num:03d}` or `{num:05.1f}`
- Segments: `{idx:06d}.ts` (6-digit zero-padded)
- Temp: `{idx:06d}.tmp` (during write)

## 16.3 Temporary vs Permanent

- **Temporary**: `.tmp` files, `concat.txt`, `.ts` files (if `keep_temp=False`)
- **Permanent**: `.mp4` output files, `session.json`, `_scan_cache.json`

## 16.4 Cleanup

- Orphan cleanup: Cache dirs >24h old removed on exit
- Segment cleanup: After successful assembly (unless `keep_temp=True`)
- Session cleanup: Manual via Session Manager

---

# 17. Playback

## 17.1 Implementation Location

`src/pahebatcher/stream.py` — `run_stream()`

## 17.2 MPV Integration

**Command construction**:
```python
cmd = [
    "mpv",
    f"--force-media-title={mpv_title} [{audio_tag}]",
    "--ytdl=no",
    "--msg-level=all=warn,lavf=error,ffmpeg=error",
    local_url,  # or info.url with headers
]
```

**With remote URL** (fallback):
```python
cmd = [
    "mpv",
    f"--user-agent={info.user_agent}",
    f"--referrer={info.referer}",
    f"--http-header-fields=Cookie: {info.cookie_str}",
    "--demuxer-lavf-format=hls",
    f"--demuxer-lavf-o=referer={info.referer}",
    info.url,
]
```

## 17.3 Local HLS Proxy

**Purpose**: Avoid MPV 403 errors on HTTP/1.1

**Implementation**:
- aiohttp web server on `127.0.0.1:0` (random port)
- Serves m3u8 with rewritten URLs pointing to local proxy
- Proxies segment/key requests to original CDN
- Handles Range headers for MPV probing

## 17.4 Quality/Audio Selection

- Quality: Selected at stream resolution level (play page)
- Audio: Selected via SUB/DUB detection on play page
- Mid-session toggle: `A` key rebuilds playlist with other audio

## 17.5 Episode Navigation

- `N`/`P`: Next/previous episode
- `R`: Replay current
- `S`: Jump to episode by number
- `A`: Toggle audio track
- `Q`: Quit

## 17.6 Playback Position

- Not persisted (MPV handles internally)
- No explicit position tracking in Python code

---

# 18. Watchlist

## 18.1 Implementation Location

`src/pahebatcher/watchlist.py`

## 18.2 State Persistence

**File**: `watchlist.json` (in cwd, git-ignored)

**Entry structure**:
```json
{
  "url": "https://animepahe.pw/anime/{uuid}",
  "session": "{uuid}",
  "host": "animepahe.pw",
  "title": "Anime Title",
  "quality": 1080,
  "audio_lang": "jpn",
  "output_dir": "./downloads",
  "max_parallel": 2,
  "hls_workers": 24,
  "keep_temp": false,
  "auto_retry": 2,
  "added_at": 1234567890,
  "last_checked": 1234567890,
  "downloaded": [1, 2, 3]
}
```

## 18.3 New Episode Detection

1. Scan with `cache_ttl=0` (force fresh)
2. Compare scanned episodes vs `downloaded` history
3. Check files on disk via `_find_existing()`
4. Deduplicate: on-disk + history = existing, else = pending

## 18.4 Deleted Episode Handling

- `downloaded` list tracks all ever-downloaded episode numbers
- If folder exists and episode not on disk but in history → "skipped (deleted)"
- If folder deleted entirely → history cleared, all episodes become "new"
- `watchlist reset` clears history manually

## 18.5 Background Checking

- No daemon/scheduler in Python
- Designed for cron execution: `pahebatcher watchlist check`
- Idempotent: running twice downloads nothing new
- Per-entry isolated failures

## 18.6 Dead Link Recovery

- `_find_single_new_session()` searches by title
- Auto-relinks if exactly one match found
- Manual relink for ambiguous/unknown cases
- Preserves download history across relinks

---

# 19. Tests

## 19.1 Test Structure

```
tests/
├── conftest.py               Fixtures
├── test_cache.py             8 tests: TTL, LRU, concurrency
├── test_coherence.py         7 tests: version, config, benchmark
├── test_config_extended.py   6 tests: constants, validation, roundtrip
├── test_config_manager.py    10 tests: defaults, save/load, validation
├── test_downloader.py        7 tests: find_existing, skip, mock flow
├── test_http.py              8 tests: session, retry, curl fallback
├── test_kwik.py              7 tests: JsPacker, M3U8, buttons
├── test_m3u8.py              8 tests: parsing, AES keys, variants
├── test_models.py            11 tests: fields, properties, edges
├── test_prompts.py           11 tests: range parsing, selection
├── test_retry.py             4 tests: episode/resolver/batch retry
├── test_scanner.py           12 tests: URL parsing, episode page, search
├── test_scanner_extended.py  7 tests: cache, TTL, fallback
├── test_sessions.py          4 tests: listing, metadata, errors
├── test_solver.py            16 tests: cookies, lifecycle, cache, fetch
├── test_store.py             7 tests: segment I/O, assembly, cleanup
├── test_store_extended.py    7 tests: atomic writes, metadata, pipe
├── test_tls.py               4 tests: version, ciphers, compression
├── test_ui.py                5 tests: tables, dedup
├── test_utils.py             20 tests: sanitize, prefix, bytes, range
└── test_utils_extended.py    21 tests: precision, edges, compact
                           ────────
                           195 total
```

## 19.2 Mocking Approach

- **Mock Solver**: `AsyncMock` for `request`, `fetch_json`, `fetch_html`, `ping`
- **Mock HttpClient**: `AsyncMock` for `get`, `start`, `close`
- **Mock Dashboard**: `MagicMock` with spec
- **Mock extract_stream**: `AsyncMock` returning `StreamInfo`
- **Mock SegmentStore**: `MagicMock` with `done_indices`, `write_seg`, `assemble`

## 19.3 Critical Path Coverage

- URL parsing and validation
- Episode range parsing
- M3U8 parsing (simple, AES, variants)
- JsPacker unpacking
- Resolution button parsing
- Segment store (write, read, atomic, cleanup)
- Retry logic (episode, resolver, batch)
- Config validation and persistence
- Cache TTL and eviction

## 19.4 Test Patterns

- All tests use `pytest-asyncio` with `asyncio_mode = "auto"`
- Fixtures in `conftest.py` provide sample data and mocks
- tmp_path fixture for filesystem tests
- Patching for network-dependent code

---

# 20. External Dependencies

## 20.1 Python Dependencies

| Package | Version | Purpose | Android Replacement |
|---------|---------|---------|---------------------|
| `rich` | >=13.9.0 | TUI rendering | Jetpack Compose UI |
| `aiohttp` | >=3.10.0 | Async HTTP client | OkHttp + Coroutines |
| `pycryptodomex` | >=3.21.0 | AES-128 decryption | javax.crypto or Bouncy Castle |
| `curl-cffi` | >=0.7.0 | TLS fingerprint impersonation | OkHttp with custom TLS config |

## 20.2 Desktop-Specific Dependencies

| Tool | Purpose | Android Replacement |
|------|---------|---------------------|
| **FlareSolverr** | Cloudflare bypass (headless Chromium) | Android WebView session |
| **FFmpeg** | TS→MP4 muxing | Media3/FFmpeg library |
| **MPV** | Video playback | Media3/ExoPlayer |
| **Docker** | FlareSolverr runtime | Not needed (WebView) |

## 20.3 Android-Native Replacements

| Python Component | Android Equivalent |
|------------------|-------------------|
| `aiohttp.ClientSession` | OkHttp client with connection pooling |
| `asyncio.Semaphore` | Kotlin coroutines Semaphore |
| `asyncio.Queue` | Kotlin coroutines Channel |
| `asyncio.gather()` | Kotlin coroutines coroutineScope |
| `TTLCache` | Room database with TTL or LruCache |
| `TOML config` | DataStore preferences |
| `Rich TUI` | Jetpack Compose UI |
| `argparse` | Navigation component + argument passing |
| `pathlib.Path` | Android File/DocumentFile APIs |
| `subprocess.run(ffmpeg)` | Media3 or FFmpeg library |
| `mpv` | Media3/ExoPlayer |

---

# 21. Network Flow Map

## 21.1 Complete Network Sequence

```
1. Search (optional)
   → GET https://{host}/api?m=search&q={query}  [via FlareSolverr]

2. Scan
   → GET https://{host}/anime/{session}  [via FlareSolverr]  (title)
   → GET https://{host}/api?m=release&id={session}&page={n}  [via FlareSolverr]  (episodes, paginated)
   → GET https://{host}/api?m=search&q={title}  [via FlareSolverr]  (variant discovery)

3. Stream Resolution (per episode)
   → GET https://{host}/play/{anime_session}/{ep_session}  [via FlareSolverr]  (play page)
   → GET https://kwik.{tld}/{path}  [via curl_cffi or FlareSolverr]  (Kwik page)
   → GET https://cdn.../{m3u8}  [via HttpClient]  (master playlist)
   → GET https://cdn.../{variant.m3u8}  [via HttpClient]  (media playlist)

4. Download (per episode)
   → GET https://cdn.../{segment.ts}  [via HttpClient]  (segments, concurrent)
   → GET https://cdn.../{key}  [via HttpClient]  (AES key, cached)

5. Assembly (per episode)
   → ffmpeg subprocess  (local, no network)
```

## 21.2 Request Dependencies

```
Search → Scan → Resolution → M3U8 Fetch → Segment Download → Assembly
   ↓        ↓         ↓            ↓              ↓
 FlareSolverr    FlareSolverr    HttpClient    HttpClient    Local
```

## 21.3 Caching Points

- Search results: Not cached
- Scan results: Cached with TTL (60 min default)
- FlareSolverr responses: Cached (120s TTL)
- AES keys: Cached (1 hour TTL)
- DNS: Cached (5 minutes)
- Segment files: Persistent on disk

---

# 22. Data Flow Map

```
User Input (URL/Query)
    ↓
Search (optional) → list[dict]
    ↓
URL Parsing → (host, session)
    ↓
Scan → AnimeInfo (title, episodes)
    ↓
Episode Selection → list[EpisodeInfo]
    ↓
Quality/Audio Config → AppContext
    ↓
Resolver (per episode)
    → Play page HTML
    → Resolution buttons
    → Quality/audio selection
    → Kwik URL
    → StreamInfo (m3u8 URL, cookies, headers)
    ↓
M3U8 Fetch → str (master playlist)
    ↓
M3U8 Parse → list[dict] (segments with URLs, keys, IVs)
    ↓
Segment Download (concurrent)
    → Raw bytes (encrypted)
    → AES decrypt (if needed)
    → Atomic write to .ts
    ↓
Assembly
    → FFmpeg concat
    → MP4 output
    ↓
Cleanup (optional)
    → Remove .ts files
    → Update dashboard
```

---

# 23. Desktop Dependency Replacement Map

## 23.1 FlareSolverr → Android WebView

**What FlareSolverr does**:
- Headless Chromium browser
- Solves Cloudflare challenges
- Returns HTML with cookies
- Manages browser sessions

**Android replacement**:
- Android WebView with CookieManager
- Real browser engine handles Cloudflare
- Extract cookies via CookieManager API
- Session persistence via EncryptedSharedPreferences

**Key differences**:
- FlareSolverr is headless; WebView is real UI component
- FlareSolverr manages its own Chromium; WebView uses system WebView
- FlareSolverr has session API; Android needs custom session management

## 23.2 FFmpeg → Media3/FFmpeg Library

**What FFmpeg does**:
- Concatenates .ts segments into MP4
- Stream copy (no re-encoding)
- Adds faststart flag

**Android replacement**:
- Media3/ExoPlayer for playback (not assembly)
- FFmpeg library (e.g., mobile-ffmpeg) for muxing
- Or: Native Android MediaCodec + Muxer

**Key differences**:
- Python uses subprocess; Android uses library calls
- Android needs to handle file access differently

## 23.3 MPV → Media3/ExoPlayer

**What MPV does**:
- HLS playback
- Audio track switching
- Episode navigation
- Local proxy for 403 bypass

**Android replacement**:
- Media3/ExoPlayer for HLS playback
- Track selection API for audio switching
- Custom media source for local proxy

**Key differences**:
- MPV is external process; ExoPlayer is embedded
- MPV has CLI interface; ExoPlayer has programmatic API
- Local proxy may not be needed with ExoPlayer

## 23.4 curl-cffi → OkHttp with Custom TLS

**What curl-cffi does**:
- TLS fingerprint impersonation (Chrome 120)
- Bypasses Cloudflare TLS detection

**Android replacement**:
- OkHttp with custom ConnectionSpec
- Or: Cronet with custom TLS config
- Or: System WebView for challenge solving

**Key differences**:
- curl-cffi is Python library; OkHttp is Java/Kotlin
- TLS fingerprinting may not be needed with WebView cookies

---

# 24. Android Architecture Mapping

## 24.1 Python → Android Layer Mapping

| Python Module | Android Layer | Android Component |
|---------------|---------------|-------------------|
| `main.py` | Navigation | NavHost, Navigation |
| `models.py` | Domain | Data classes, Room entities |
| `config.py` | Domain | Constants |
| `config_manager.py` | Data | DataStore preferences |
| `utils.py` | Domain | Extension functions |
| `tls.py` | Network | OkHttp TLS config |
| `cache.py` | Data | LruCache or Room |
| `store.py` | Data | Room + File APIs |
| `http.py` | Network | OkHttp client |
| `solver.py` | Network | WebView session manager |
| `extract/kwik.py` | Domain | Stream resolver |
| `extract/m3u8.py` | Domain | HLS parser |
| `extract/scanner.py` | Data | Anime repository |
| `downloader.py` | Domain | Download engine |
| `stream.py` | Domain | Playback engine |
| `sessions.py` | Data | Session repository |
| `watchlist.py` | Data | Watchlist repository |
| `ui/console.py` | UI | Compose theme |
| `ui/dashboard.py` | UI | Download screen |
| `ui/tables.py` | UI | List composables |
| `ui/prompts.py` | UI | Dialog composables |

## 24.2 Proposed Android Architecture

```
UI (Compose)
  ↓
ViewModel
  ↓
Use Case
  ↓
Repository
  ↓
Domain Implementation
  ↓
Network / Database / Storage / Download Engine / Player
```

## 24.3 Key Android Components

| Component | Purpose |
|-----------|---------|
| `AnimeRepository` | Search, scan, metadata |
| `EpisodeRepository` | Episode discovery, selection |
| `StreamResolver` | Play page → M3U8 URL |
| `HlsManifestParser` | M3U8 parsing |
| `HlsSegmentDownloader` | Segment download engine |
| `SegmentStore` | Atomic segment writes |
| `EpisodeAssembler` | FFmpeg muxing |
| `DownloadRepository` | Download state persistence |
| `SessionRepository` | Session management |
| `WatchlistRepository` | Watchlist persistence |
| `PlaybackRepository` | Media3 playback |
| `CloudflareSessionProvider` | WebView session management |

---

# 25. Parity Gap Analysis

## 25.1 Direct Port (Python Parity)

- [ ] Episode data models
- [ ] M3U8 parsing logic
- [ ] JsPacker unpacking
- [ ] Resolution button parsing
- [ ] Quality/audio selection logic
- [ ] Episode range parsing
- [ ] Filename sanitization
- [ ] Segment store structure
- [ ] Atomic write mechanism
- [ ] Cache TTL logic
- [ ] Config validation

## 25.2 Same Behavior, Different Implementation

- [ ] HTTP client (aiohttp → OkHttp)
- [ ] Concurrency (asyncio → Kotlin coroutines)
- [ ] Configuration (TOML → DataStore)
- [ ] TUI (Rich → Compose)
- [ ] Progress tracking (Dashboard → Compose state)

## 25.3 Android-Native Replacement

- [ ] FlareSolverr → WebView session
- [ ] FFmpeg subprocess → FFmpeg library
- [ ] MPV → Media3/ExoPlayer
- [ ] curl-cffi → OkHttp TLS config
- [ ] File paths → Android storage APIs

## 25.4 Android Extensions

- [ ] Background downloads (WorkManager)
- [ ] Notifications
- [ ] Scoped storage
- [ ] Battery optimization
- [ ] Process death recovery

## 25.5 Blocked/Unresolved

- [ ] Cloudflare bypass effectiveness with WebView
- [ ] FFmpeg library availability on Android
- [ ] TLS fingerprinting without curl-cffi
- [ ] Local HLS proxy necessity with ExoPlayer

---

# 26. Risk and Blocker Identification

## 26.1 Technical Risks

1. **Cloudflare bypass**: WebView may not solve all challenges that FlareSolverr handles
2. **TLS fingerprinting**: OkHttp may be detected by Cloudflare without curl-cffi impersonation
3. **FFmpeg availability**: Not all Android devices have FFmpeg; may need bundled library
4. **Storage permissions**: Scoped storage adds complexity for download management
5. **Background execution**: Android restricts long-running background tasks
6. **Memory constraints**: HLS segment buffering must be bounded

## 26.2 Potential Blockers

1. **Cloudflare evolution**: If AnimePahe increases Cloudflare protection, WebView may not suffice
2. **FFmpeg muxing**: If FFmpeg library is too large or unavailable, assembly may fail
3. **ExoPlayer HLS**: If ExoPlayer doesn't handle all HLS features, playback may break
4. **Cookie synchronization**: WebView ↔ OkHttp cookie sync is non-trivial
5. **Session persistence**: FlareSolverr sessions don't persist; Android needs equivalent

## 26.3 Ambiguities in SPEC.md

1. **WebView session lifetime**: How long should WebView stay alive?
2. **Cookie refresh mechanism**: How to detect and refresh expired Cloudflare cookies?
3. **FFmpeg library choice**: Which FFmpeg library to use on Android?
4. **Local proxy necessity**: Is local HLS proxy needed with ExoPlayer?
5. **Background download limits**: What are practical limits for WorkManager?

---

# 27. SPEC.md Ambiguity Identification

## 27.1 Unclear Specifications

1. **Section 6 (Cloudflare/WebView)**: "Wait for page completion/challenge completion" — how to detect completion programmatically?

2. **Section 7 (FlareSolverr replacement)**: "If a WebView session can satisfy the required browser/session state, use it" — what constitutes "satisfying"?

3. **Section 14 (MP4 Assembly)**: "Choose an Android-compatible implementation" — no specific recommendation given

4. **Section 15 (Streaming)**: "If the original stream flow allows SUB/DUB switching, reproduce it using Media3's track-selection facilities where possible" — "where possible" is vague

5. **Section 16 (Background Downloads)**: "Use WorkManager for persistent scheduled/background orchestration" — how to handle download state across process death?

6. **Section 19 (Watchlist)**: "triggering background checks where Android permits" — what are the practical limits?

## 27.2 Contradictions

1. **Section 5 vs Section 6**: "Do NOT make the app a WebView wrapper" but "Use WebView only where it is genuinely required" — when is it "genuinely required"?

2. **Section 10 vs Section 34**: "Use bounded concurrency" vs "prioritize low memory usage" — how to balance high segment concurrency with memory constraints?

3. **Section 12 vs Section 13**: "Cache as first-class feature" vs "Use Android-compatible storage APIs" — how to balance caching needs with scoped storage?

## 27.3 Missing Specifications

1. **Error recovery details**: How to handle partial downloads across app updates?
2. **Migration strategy**: How to handle schema changes in Room database?
3. **Update mechanism**: How to update the app without losing download state?
4. **Internationalization**: Should the app support multiple languages?
5. **Accessibility**: What accessibility features are required?

---

# 28. Implementation Recommendations

## 28.1 Phase 1 Priorities

1. **Core models**: Port data classes directly
2. **M3U8 parser**: Port parsing logic directly
3. **JsPacker**: Port unpacking logic directly
4. **Segment store**: Port atomic write mechanism
5. **Config manager**: Port validation logic

## 28.2 Phase 2 Priorities

1. **HTTP client**: OkHttp with retry logic
2. **Stream resolver**: WebView-based session management
3. **Download engine**: Coroutine-based with bounded concurrency
4. **Room database**: Schema for episodes, downloads, sessions

## 28.3 Phase 3 Priorities

1. **Compose UI**: Search, episode list, download dashboard
2. **Media3 playback**: HLS with track selection
3. **WorkManager**: Background download orchestration
4. **Notifications**: Download state changes

## 28.4 Critical Path

```
Models → Config → Scanner → Stream Resolver → Download Engine → Assembly → Playback
```

Each phase must be complete before proceeding to the next.

---

# 29. Conclusion

The PaheBatcher Python implementation is a well-structured, production-quality application with:

- **Clear separation of concerns**: Each module has a single responsibility
- **Comprehensive error handling**: Retry logic at multiple levels
- **Robust recovery**: Atomic writes and segment-level resume
- **Good test coverage**: 195 tests covering critical paths
- **Clean architecture**: Dependency injection, no global state

The Android port must preserve this behavior while replacing desktop-specific mechanisms with Android-native equivalents. The most significant challenges are:

1. **Cloudflare bypass**: WebView-based session management
2. **FFmpeg muxing**: Library-based assembly
3. **Background execution**: WorkManager orchestration
4. **Storage management**: Scoped storage compatibility

The analysis provides a complete map of every Python module, its behavior, and its Android equivalent. This should drive all subsequent implementation work.
