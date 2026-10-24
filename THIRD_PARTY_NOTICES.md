# Third-party notices

PaheBatcher for Android (this repository) is licensed under GPL-3.0-or-later
(see [`LICENSE`](LICENSE)); Copyright (C) 2026 HellHarbinger.

This file inventories the third-party components the application uses, the
license of each, and - where the component ships inside the app package - where
its license text is carried. Licenses below were verified against the actual
artifacts (bundled AAR/JAR contents and Maven Central metadata), not assumed
from memory; the verification evidence lives in the private release-prep ledger.

## Summary

| Component set | License | Shipped in the app package? |
|---|---|---|
| AndroidX (all libraries below) | Apache-2.0 | yes (JVM code + license text) |
| Jetpack Compose (UI, Material 3, icons-core) | Apache-2.0 | yes |
| Kotlin standard library | Apache-2.0 | yes |
| kotlinx-coroutines / kotlinx-serialization-json | Apache-2.0 | yes |
| OkHttp 4.12.0 | Apache-2.0 | yes |
| FFmpegKit `full` 8.1.7 (+ bundled FFmpeg and codecs) | LGPL-3.0-or-later (main); per-component LGPL/BSD below | yes (native `.so` + license texts) |
| smart-exception-java / -common 0.2.1 | BSD-3-Clause | yes (JVM code) |
| JUnit 4.13.2 | EPL-1.0 | no (test-only) |
| androidx.test / androidx.room:room-testing / androidx.work:work-testing / kotlinx-coroutines-test / Compose UI test | Apache-2.0 | no (test-only) |
| Android Gradle Plugin, KSP, Gradle wrapper, Compose compiler plugin | Apache-2.0 | no (build-time only) |

## Runtime dependencies (packaged in every APK/AAB)

Direct dependencies are declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
and `pahebatcher/build.gradle.kts`; versions quoted here are the currently pinned ones.

### AndroidX and Jetpack - Apache-2.0

`androidx.core:core-ktx`, `androidx.activity:activity-compose`,
`androidx.lifecycle:*` (runtime-ktx, runtime-compose, viewmodel-compose),
`androidx.room:*` (runtime, ktx), `androidx.datastore:datastore-preferences`,
`androidx.work:work-runtime`, `androidx.navigation:navigation-compose`,
`androidx.media3:*` (exoplayer, exoplayer-hls, session,
ui-compose-material3, datasource-okhttp), the Compose BOM platform and its
`ui`/`ui-graphics`/`ui-tooling-preview`/`material3`/`material-icons-core`
libraries. AndroidX AARs carry their Apache-2.0 text inside the package at
`META-INF/androidx/**/LICENSE.txt` (verified in the built release APK).

### Kotlin, coroutines, serialization - Apache-2.0

The Kotlin standard library (transitively via the Kotlin Gradle plugin and
libraries), `org.jetbrains.kotlinx:kotlinx-coroutines-core`/`-android`
(1.11.0) and `org.jetbrains.kotlinx:kotlinx-serialization-json` (1.11.0).

### OkHttp - Apache-2.0

`com.squareup.okhttp3:okhttp` 4.12.0 (HTTP transport for site session and
Media3 datasource).

### FFmpegKit - LGPL-3.0-or-later, with per-component licenses

`dev.ffmpegkit-maintained:ffmpeg-kit-full` 8.1.7 (community-maintained fork of
the original arthenica FFmpegKit). Verified from the artifact itself:

* the package's main license text is the **GNU Lesser General Public License
  Version 3** (`res/raw/license.txt` inside the AAR);
* the `full` package variant contains no GPL-only components (the GPL builds
  are the separate `-gpl` variants, not used here);
* **34 license texts** (the LGPL main text plus one per bundled component -
  FFmpeg, dav1d, libaom, libvpx, opus, LAME, OpenH264, libass, harfbuzz,
  freetype, libogg/vorbis, theora, speex, and others) ship inside every built
  APK. AGP 9's release resource optimization renames them to short `res/*.txt`
  paths (debug builds keep `res/raw/license*.txt`); the texts themselves are
  unmodified and packaged either way - verified byte-content probes against the
  built release APK.

`com.arthenica:smart-exception-java` 0.2.1 (+ `-common`) is a runtime
dependency of FFmpegKit that its POM fails to declare (Maven Central metadata
verified); it is declared explicitly here. License: **BSD-3-Clause**
(verified from the published POM).

## Test-only dependencies (never packaged)

`junit:junit` 4.13.2 (EPL-1.0); `androidx.test:*`, `androidx.test.ext:junit`,
`androidx.room:room-testing`, `androidx.work:work-testing`,
`kotlinx-coroutines-test`, `androidx.compose.ui:ui-test-junit4`/`ui-test-manifest`,
`androidx.compose.ui:ui-tooling` (all Apache-2.0).

## Build-time tooling (never packaged)

Android Gradle Plugin 9.4.1, Kotlin 2.4.20 (incl. the Compose compiler and
serialization compiler plugins), Google KSP, and the Gradle 9.8.2 wrapper -
all Apache-2.0. None of these ship in an artifact.

## Notes

* This inventory covers **dependencies**, not the Android OS: platform APIs
  used at runtime are subject to the device's own licensing, unchanged by this
  project.
* No bundled component imposes a license more restrictive than GPL-3.0-or-later
  on this application's own code; the LGPL-covered FFmpegKit native libraries
  remain dynamically loaded `.so` libraries under their own terms, with their
  license texts carried in the package as described above.
