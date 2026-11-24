# PaheBatcher for Android

<p align="center">
    <img width="250" height="250" alt="PaheBatcher for Android logo" src="https://github.com/user-attachments/assets/f68c227b-6b74-4fee-b117-bc5833dcb1e6" />
</p>

<br/>

<p align="center">
  <strong>Find any anime, queue single episodes or whole batches, and watch them —<br/>
  a native Android app for the PaheBatcher workflow.</strong>
</p>

<br/>

<p align="center">
  <a href="https://github.com/HellHarbinger/PaheBatcher-Droid/releases"><img src="https://img.shields.io/github/v/release/HellHarbinger/PaheBatcher-Droid?display_name=tag&label=release" alt="Latest release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg" alt="License: GPL-3.0-or-later"/></a>
  <a href="#download-and-installation"><img src="https://img.shields.io/badge/Android-12L%2B%20(API%2032)-3DDC84?logo=android&logoColor=white" alt="Android 12L+ (API 32)"/></a>
  <a href="#building-from-source"><img src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.4.20"/></a>
</p>

<br/>

<p align="center">
  <a href="#download-and-installation">Download</a> ·
  <a href="#quick-start">Quick Start</a> ·
  <a href="#features">Features</a> ·
  <a href="#how-to-use">How to Use</a> ·
  <a href="#documentation">Documentation</a> ·
  <a href="#license">License</a>
</p>

---

## At a Glance

| You can                         | How                                                                                      |
|---------------------------------|------------------------------------------------------------------------------------------|
| **Find anime**                  | Search from the Home screen; results open straight into the anime's page                 |
| **Browse episodes**             | A paged episode list per anime                                                           |
| **Pick audio & quality**        | Audio options (e.g. `JPN` / `ENG`) per anime or per episode; quality 360p / 720p / 1080p |
| **Download one or many**        | A single episode, the current page, or a selected range (up to 100 episodes per batch)   |
| **Track and control downloads** | A queue tab with live progress and per-episode Pause / Resume / Cancel / Retry           |
| **Watch**                       | Play finished episodes in-app, or stream them without downloading                        |
| **Follow series**               | A watchlist with background checks and a "new episodes" notification                     |
| **Configure**                   | Quality, parallel download limits, Wi-Fi-only mode, storage folder, notification toggles |

**Status:** v0.2.2 — an early-stage release distributed as signed builds on the
[Releases page](https://github.com/HellHarbinger/PaheBatcher-Droid/releases). **Not on Google
Play.** Requires Android 12L (API 32)+ and a 64-bit device.

## Table of Contents

- [What Is PaheBatcher for Android?](#what-is-pahebatcher-for-android)
- [Features](#features)
- [Download and Installation](#download-and-installation)
- [Quick Start](#quick-start)
- [How to Use](#how-to-use)
- [Settings and Configuration](#settings-and-configuration)
- [Where Downloads Are Saved](#where-downloads-are-saved)
- [Known Limitations](#known-limitations)
- [Documentation](#documentation)
- [Building from Source](#building-from-source)
- [Architecture](#architecture)
- [Testing and Releases](#testing-and-releases)
- [Contributing and Issue Reports](#contributing-and-issue-reports)
- [Acknowledgments](#acknowledgments)
- [License](#license)

## What Is PaheBatcher for Android?

PaheBatcher for Android searches an anime library, resolves episode streams, batch-downloads
episodes as HLS segments, assembles them into MP4 files, exports them to your device storage, and
plays them back — with a modern, Android-native interface.

It is an **independent native Android implementation** (Kotlin + Jetpack Compose) of the
desktop [PaheBatcher](https://github.com/smolfiddle/pahebatcher) project: the original application's
behavior is the reference, while this app is written from scratch for Android. It is not a wrapper
around a website.

SmolFiddle, the author of the original desktop project, also maintains a **separate Flutter-based**
Android app at
[smolfiddle/pahebatcher_mobile](https://github.com/smolfiddle/pahebatcher_mobile). The two
applications are independent implementations with different technology stacks.

## Features

- **Search & browse** — search the library from the home screen, open an anime's details page with a
  paged episode list.
- **Per-episode choices** — pick the audio track and quality before downloading.
- **Downloads** — download a single episode or a batch (a whole page, everything, or a selected
  range). Episodes arrive as HLS segments and are assembled into `.mp4`
  files.
- **Download queue** — live progress, pause/resume and retry, a foreground progress notification,
  and queue records that persist in the database: after a restart the app reconciles them with
  Android's WorkManager, and queued or interrupted work is picked up again where possible
  (best-effort recovery — see
  [Known Limitations](#known-limitations)).
- **Playback** — local playback of downloaded episodes and direct HLS streaming, with media-session
  controls, subtitles and remembered playback position.
- **Watchlist** — follow titles and get notified when new episodes appear, via background checks.
- **Settings** — theme and dynamic color, quality defaults, parallel-download limits, Wi-Fi-only
  downloads, output folder, notification toggles and a diagnostics console.

## Download and Installation

No development tools required:

1. **Get the app** - Open the latest
   [GitHub Releases page](https://github.com/HellHarbinger/PaheBatcher-Droid/releases)
   and download the APK matching your device's architecture:

    | APK                                 | For                                        |
    |-------------------------------------|--------------------------------------------|
    | `pahebatcher-arm64-v8a-release.apk` | Nearly every phone and tablet (64-bit ARM) |
    | `pahebatcher-x86_64-release.apk`    | x86_64 emulators and some Chromebooks      |

2. **Install it** — Open the downloaded APK from your **Downloads** app or file manager.

> If Android blocks the installation, the system prompt offers to allow installation from that
> source — accept it (the wording varies between manufacturers and Android versions), then return to
> the APK.

3. Confirm the installation, then open the app.

**Requirements:** Android 12L (API 32) or newer, **64-bit only** — 32-bit devices (`armeabi-v7a`,
`x86`) are not supported.

### Advanced installation

1. **Get the app** - Open the latest
   [GitHub Releases page](https://github.com/HellHarbinger/PaheBatcher-Droid/releases)
2. **Pick the right file** — `arm64-v8a` for real phones and tablets; `x86_64` for x86_64 emulators.
   > If unsure, run `adb shell getprop ro.product.cpu.abi` on your device (most report `arm64-v8a`).
3. **Install it** — With the Android SDK platform tools you can instead install from a terminal:

```bash
adb install -r <apk>          # replace <apk> with the downloaded file
```

*(Optional)* Verify the checksum against the release's `SHA256SUMS.txt` first:
`sha256sum <apk>` on Linux/macOS, `certutil -hashfile <apk> SHA256` on Windows.

**Upgrading:** release builds are signed with the configured release key, so published releases
upgrade over each other normally as long as that key is unchanged. Debug and release builds share
the application ID `com.pahebatcher.app` but use different signing keys — Android refuses to install
one over the other, so switching between them means uninstalling first, **which erases that build's
app data**.

## Quick Start

1. **Get the app** — download the correct APK from the
   [latest GitHub release](https://github.com/HellHarbinger/PaheBatcher-Droid/releases/latest)
   (`arm64-v8a` for real phones and tablets, `x86_64` for emulators — see the table in
   [Download and Installation](#download-and-installation)). Distribution is through GitHub
   Releases, not Google Play.
2. **Install it** — open the downloaded file and follow the prompts described in
   [Download and Installation](#download-and-installation).
3. **First launch** — the app opens on the **Home** tab with a search field and the bottom bar
   (**Home** / **Downloads**). No account or login exists.
4. **Search something** — type an anime title (or press the keyboard's search key), then tap a
   result to open its episode list. If a "Refresh session" dialog appears, tap **Refresh now** and
   continue.

You can now tap an episode to download or stream it — see
[How to Use](#how-to-use). Your **first download** asks Android for notification permission;
granting it lets you follow download progress and get completion, failure and new-episode alerts.

## How to Use

### Search for an anime

1. On the **Home** screen, type a title into the search field ("Search anime titles…") or press the
   keyboard's search key.
2. Results appear in a list as the query settles; an offline banner appears if the device has no
   connection.
3. Tap a result to open its details page, which scans and lists the available episodes (a progress
   line shows the scan).

### Choose audio and quality

- **Audio:** chips at the top of the details page (e.g. `JPN`, `ENG`) apply to every episode at
  once; an individual episode's row menu offers **Change audio** for a per-episode override. Not
  every series has every option — unavailable audio is reported rather than silently substituted.
- **Quality:** choose the default in **Settings → Download engine → Quality**
  (360p / 720p / 1080p). Downloads **and** direct streaming both resolve with this quality each time
  a stream is resolved, so set it **before** you start — changing it does not affect a download or
  stream already in progress. There is no audio/quality switching inside the player — return to the
  details page to switch.

### Download episodes

1. **One episode:** tap an episode row — the app resolves its stream and opens a panel with
   **Download** (or **Retry download** if it failed) and **Stream**.
2. **Several episodes:** tap the list icon in the top bar (**Select episodes**) to enter selection
   mode, then use **Select all**, **Clear**, **From/To + Range**, or **Latest N**, and tap
   **Download selected (N)** — or **Download this page (N episodes)** without selecting. Batches are
   capped at 100 episodes; the count line shows your selection.
3. The **first time you start a download**, the app asks Android for permission to post
   notifications (you can also grant or change this later in **Settings → Notifications**). Granting
   it lets you follow download progress, get completion and failure alerts, and receive new-episode
   alerts from your watchlist.
4. A batch shows a live summary (*Queued / Skipped / Failed*) and can be stopped with **Stop
   batch** — everything already queued keeps running.

### Manage downloads

- Open the **Downloads** tab: each episode is a row with its current state (queued, downloading,
  assembling, completed or failed) and live progress.
- Per-row actions: **Pause**, **Resume**, **Cancel**, **Retry** — and **Play** once an episode is
  completed. Failed rows stay listed with their reason until retried or cancelled.
- If a completed file already exists where a new one would be written, the app asks before replacing
  it.

### Play and stream

- **Play** (Downloads tab) plays the finished local MP4 — no connection needed.
- **Stream** (on the details page panel) plays the episode directly over HLS **without writing a
  video file to your device** — playback is buffered in memory while you watch. It needs a working
  session and connection.
- The player offers seek, previous/next episode, subtitles and a Retry overlay on failure; your
  playback position is remembered.

### Watchlist and settings

- **Watchlist:** from the **Home** top bar, tap the **Watchlist** icon (a list icon), paste an
  anime's page URL into **Anime URL** and tap **Add**. The app then checks for new episodes in the
  background (frequency set in Settings) and posts a single combined notification; the refresh icon
  (**Check now for new episodes**) runs a check on demand, and **Remove** deletes an entry.
- **Settings:** tap the gear icon on **Home** — see
  [Settings and Configuration](#settings-and-configuration).

## Settings and Configuration

| Section               | Key options                                                                                                                                                            |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Appearance**        | Theme (`System` / `Dark` / `Light`), dynamic color                                                                                                                     |
| **Download engine**   | Quality (360p / 720p / 1080p), max parallel downloads, HLS segment workers, scan cache TTL, keep temp files, auto retry (None / Once / Twice), Wi-Fi-only, auto-resume |
| **Storage**           | Output location — use the default or pick a folder with the system picker                                                                                              |
| **Notifications**     | Notification permission, completion / failure / new-episode toggles                                                                                                    |
| **Background checks** | Watchlist check frequency (15 min – 12 h)                                                                                                                              |
| **Diagnostics**       | In-app console for support and troubleshooting                                                                                                                         |

Settings apply from the next action onward (the next queueing, resolution or notification) —
already-running work is unaffected.

## Where Downloads Are Saved

- Finished episodes are exported as `.mp4` files to **`Movies/<anime title>/`** in device storage by
  default, where your gallery and file manager can see them.
- Choose a different folder in **Settings → Storage → Output location** (system folder picker).
- Temporary segment data is kept inside the app's private storage during a download and cleaned up
  after assembly (unless *Keep temp files* is enabled).

## Known Limitations

- **Website session / Cloudflare** — the site session is maintained through a WebView and is
  inherently fragile; when a quiet refresh fails, the app shows a *Refresh session* dialog
  (**Refresh now** / **Not now**).
- **No in-player audio or quality switching** — switch from the details page instead.
- **64-bit only** — release APKs are split per ABI and ship `arm64-v8a` and `x86_64`
  native libraries only.
- **Background execution** — Android throttles long-running background work, so very long queues can
  be deferred to when the app is in the foreground, and scheduled watchlist checks may run later
  than the chosen interval (Android's periodic scheduling is inexact).
- **Not on Google Play** — distribution is via GitHub releases only.

The full list with workarounds lives in [`LIMITATIONS.md`](LIMITATIONS.md).

## Documentation

| Document                                                           | Purpose                                            |
|--------------------------------------------------------------------|----------------------------------------------------|
| [`SPEC.md`](SPEC.md)                                               | Project specification — required behavior          |
| [`LIMITATIONS.md`](LIMITATIONS.md)                                 | Known platform limitations and workarounds         |
| [`PARITY.md`](PARITY.md)                                           | Feature-by-feature parity with the desktop project |
| [`ANDROID_USER_FLOWS.md`](ANDROID_USER_FLOWS.md)                   | End-to-end user flows, screen by screen            |
| [`ANDROID_ARCHITECTURE.md`](ANDROID_ARCHITECTURE.md)               | Technical architecture                             |
| [`ANDROID_TESTING_AND_RELEASE.md`](ANDROID_TESTING_AND_RELEASE.md) | Testing and release procedures                     |
| [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)                 | Third-party dependency licenses                    |

## Building from Source

**Prerequisites:** JDK 25, the Android SDK (compileSdk 37), and the bundled Gradle wrapper (9.8.2) —
build only through the wrapper.

```bash
./gradlew assembleDebug       # per-ABI debug APKs -> pahebatcher/build/outputs/apk/debug/
./gradlew testDebugUnitTest   # JVM unit tests
./gradlew lintDebug           # Android lint (target: 0 errors)
```

On Windows use `.\gradlew.bat` instead of `./gradlew`. Test locations, release rules and cautions
(for example, never run connected tests against a device holding real app data) are documented in
[`ANDROID_TESTING_AND_RELEASE.md`](ANDROID_TESTING_AND_RELEASE.md).

## Architecture

Single-activity Jetpack Compose app with a strict layering — UI → ViewModel → use cases /
repositories → pure-Kotlin domain logic → implementations (Room, OkHttp + WebView session, HLS
engine, WorkManager, Media3). Background downloads run under WorkManager with a foreground progress
notification; state lives in the database and is reconciled with WorkManager after a restart —
recovery is best-effort, not guaranteed (see [`LIMITATIONS.md`](LIMITATIONS.md)). Full detail:
[`ANDROID_ARCHITECTURE.md`](ANDROID_ARCHITECTURE.md).

**Technology:** Kotlin · Jetpack Compose (Material 3) · Navigation-Compose · Room · DataStore ·
WorkManager · OkHttp · AndroidX Media3 (ExoPlayer) · FFmpegKit

## Testing and Releases

- Unit tests (`testDebugUnitTest`, `testReleaseUnitTest`) and `lintDebug` run on every merged pull
  request and gate the release build; test locations and commands are in
  [`ANDROID_TESTING_AND_RELEASE.md`](ANDROID_TESTING_AND_RELEASE.md).
- On a merge to `main`, CI builds per-ABI **signed** release APKs and an AAB, verifies the
  signatures, and publishes them — together with `SHA256SUMS.txt` — to a
  [GitHub Release](https://github.com/HellHarbinger/PaheBatcher-Droid/releases). Nothing is uploaded
  to Google Play.

## Contributing and Issue Reports

- Read [`SPEC.md`](SPEC.md) first — it, not the desktop code, defines required behavior; development
  conventions are in [`AGENTS.md`](AGENTS.md).
- Make the smallest complete, verified increment: build, run the relevant tests, and update the
  documentation you affect.
- **Issues:** include (1) exact steps to reproduce, (2) expected vs actual behavior, (3) app version
  and build variant, and (4) your device's Android version. Never attach keystores, credentials,
  cookies or session data.

## Acknowledgments

PaheBatcher for Android would not exist without the original desktop
[PaheBatcher](https://github.com/smolfiddle/pahebatcher) project, authored by
[SmolFiddle](https://github.com/smolfiddle). Their work and guidance were of great value at multiple
stages of this project's development, and we are deeply grateful for the foundation and inspiration
they provided.

The PaheBatcher logo used by this application was created collaboratively by this project's
maintainer and SmolFiddle. Both the Kotlin-based Android application in this repository and
SmolFiddle's separate Flutter application use this shared logo.

> **Looking for the Flutter app?** SmolFiddle's separate Flutter-based Android
> implementation is
> at [smolfiddle/pahebatcher_mobile](https://github.com/smolfiddle/pahebatcher_mobile)
> — an independent project from this repository, with its own README, releases and
> support channels.

## License

PaheBatcher for Android is licensed under **GPL-3.0-or-later** — see
[`LICENSE`](LICENSE) for the full terms. You are free to run, study, share and modify it; derivative
works must be distributed under the same license, and there is no warranty. Third-party components
keep their own licenses, inventoried in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
