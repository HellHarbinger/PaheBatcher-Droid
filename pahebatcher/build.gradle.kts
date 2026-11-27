// ---------------------------------------------------------------------------
// Security floors for vulnerable TRANSITIVE dependencies
// ---------------------------------------------------------------------------
// The AGP/lint/KSP toolchain and Media3 introduce several vulnerable
// transitive libraries (BouncyCastle, jose4j, jdom2, commons-lang3, guava,
// httpclient) that cannot be reached through normal dependency declarations
// - they live on the plugin classpath and on tooling-only configurations
// (androidLintTool, KSP processor paths). Each one is floored to the
// patched version declared centrally in gradle/libs.versions.toml, using
// ordinary Gradle dependency CONSTRAINTS: conflict resolution always
// selects the highest requested version, so a floor only ever raises a
// request - it can never downgrade a newer toolchain request, and a
// prerelease below the floor never wins. Guava publishes its JVM/Android
// split inside the version string (-jre / -android) and every graph here
// resolves exactly one flavor, so each floor carries the flavor of the
// graph it constrains (-jre for JVM tooling graphs, -android for the app
// graphs where Media3's request lives). Advisory mapping: DECISIONS.md 167.
buildscript {
    dependencies {
        constraints {
            add("classpath", "com.google.guava:guava:${libs.versions.guava.get()}-jre")
                .because("Security floor: guava ${libs.versions.guava.get()}-jre (CVE-2026-102554)")
            add("classpath", "org.bouncycastle:bcprov-jdk18on:${libs.versions.bouncycastle.get()}")
                .because("Security floor: BouncyCastle jdk18on family ${libs.versions.bouncycastle.get()} (CVE-2026-8763, CVE-2026-13506, CVE-2026-5588)")
            add("classpath", "org.bouncycastle:bcpkix-jdk18on:${libs.versions.bouncycastle.get()}")
                .because("Security floor: BouncyCastle jdk18on family ${libs.versions.bouncycastle.get()} (CVE-2026-8763, CVE-2026-13506, CVE-2026-5588)")
            add("classpath", "org.bouncycastle:bcutil-jdk18on:${libs.versions.bouncycastle.get()}")
                .because("Security floor: BouncyCastle jdk18on family ${libs.versions.bouncycastle.get()} (CVE-2026-8763, CVE-2026-13506, CVE-2026-5588)")
            add("classpath", "org.jdom:jdom2:${libs.versions.jdom2.get()}")
                .because("Security floor: jdom2 ${libs.versions.jdom2.get()} (CVE-2021-33813, XXE)")
            add("classpath", "org.bitbucket.b_c:jose4j:${libs.versions.jose4j.get()}")
                .because("Security floor: jose4j ${libs.versions.jose4j.get()} (CVE-2024-29371, DoS)")
            add("classpath", "org.apache.commons:commons-lang3:${libs.versions.commonsLang3.get()}")
                .because("Security floor: commons-lang3 ${libs.versions.commonsLang3.get()} (CVE-2025-48924)")
            add("classpath", "org.apache.httpcomponents:httpclient:${libs.versions.httpclient.get()}")
                .because("Security floor: httpclient ${libs.versions.httpclient.get()} (CVE-2020-13956)")
            add("classpath", "org.ow2.asm:asm:${libs.versions.asm.get()}")
                .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
            add("classpath", "org.ow2.asm:asm-tree:${libs.versions.asm.get()}")
                .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
            add("classpath", "org.ow2.asm:asm-commons:${libs.versions.asm.get()}")
                .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
            add("classpath", "org.ow2.asm:asm-analysis:${libs.versions.asm.get()}")
                .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
            add("classpath", "org.ow2.asm:asm-util:${libs.versions.asm.get()}")
                .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
}

// App/test/lint-check graphs: guava resolves the -android flavor (Media3
// requests 33.3.1-android, and kotlinx-coroutines-guava's -jre request
// joins the same graphs, where conflict resolution picks one highest
// version - the floor keeps every graph on the patched release). The
// declarable parents below cover their variant classpaths through
// configuration inheritance.
dependencies {
    constraints {
        add("implementation", "com.google.guava:guava:${libs.versions.guava.get()}-android")
            .because("Security floor: guava ${libs.versions.guava.get()}-android (CVE-2026-102554)")
        add("androidTestImplementation", "com.google.guava:guava:${libs.versions.guava.get()}-android")
            .because("Security floor: guava ${libs.versions.guava.get()}-android (CVE-2026-102554)")
        add("lintChecks", "com.google.guava:guava:${libs.versions.guava.get()}-android")
            .because("Security floor: guava ${libs.versions.guava.get()}-android (CVE-2026-102554)")
    }
}

// JVM-only tooling graphs (lint tool + KSP processor paths) are created by
// AGP/KSP plugins and resolve guava's -jre flavor; androidLintTool
// additionally carries the BouncyCastle, commons-lang3 and httpclient
// pins. These configurations cannot be reached from the dependencies
// block, so each is floored as it appears.
val pbSecurityFloors = dependencies.constraints
configurations.matching {
    it.name == "androidLintTool" ||
        it.name.endsWith("Classpath")
}.configureEach {
    pbSecurityFloors.add(name, "com.google.guava:guava:${libs.versions.guava.get()}-jre")
        .because("Security floor: guava ${libs.versions.guava.get()}-jre (CVE-2026-102554)")
    pbSecurityFloors.add(name, "org.ow2.asm:asm:${libs.versions.asm.get()}")
        .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
    pbSecurityFloors.add(name, "org.ow2.asm:asm-tree:${libs.versions.asm.get()}")
        .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
    pbSecurityFloors.add(name, "org.ow2.asm:asm-commons:${libs.versions.asm.get()}")
        .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
    pbSecurityFloors.add(name, "org.ow2.asm:asm-analysis:${libs.versions.asm.get()}")
        .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
    pbSecurityFloors.add(name, "org.ow2.asm:asm-util:${libs.versions.asm.get()}")
        .because("Security floor: ASM family ${libs.versions.asm.get()} (SNYK-JAVA-ORGOW2ASM-21715439, SNYK-JAVA-ORGOW2ASM-21715442, SNYK-JAVA-ORGOW2ASM-21715445, SNYK-JAVA-ORGOW2ASM-21715448, SNYK-JAVA-ORGOW2ASM-21715452)")
    if (name == "androidLintTool") {
        pbSecurityFloors.add(name, "org.bouncycastle:bcprov-jdk18on:${libs.versions.bouncycastle.get()}")
            .because("Security floor: BouncyCastle jdk18on family ${libs.versions.bouncycastle.get()} (CVE-2026-8763, CVE-2026-13506, CVE-2026-5588)")
        pbSecurityFloors.add(name, "org.bouncycastle:bcpkix-jdk18on:${libs.versions.bouncycastle.get()}")
            .because("Security floor: BouncyCastle jdk18on family ${libs.versions.bouncycastle.get()} (CVE-2026-8763, CVE-2026-13506, CVE-2026-5588)")
        pbSecurityFloors.add(name, "org.bouncycastle:bcutil-jdk18on:${libs.versions.bouncycastle.get()}")
            .because("Security floor: BouncyCastle jdk18on family ${libs.versions.bouncycastle.get()} (CVE-2026-8763, CVE-2026-13506, CVE-2026-5588)")
        pbSecurityFloors.add(name, "org.apache.commons:commons-lang3:${libs.versions.commonsLang3.get()}")
            .because("Security floor: commons-lang3 ${libs.versions.commonsLang3.get()} (CVE-2025-48924)")
        pbSecurityFloors.add(name, "org.apache.httpcomponents:httpclient:${libs.versions.httpclient.get()}")
            .because("Security floor: httpclient ${libs.versions.httpclient.get()} (CVE-2020-13956)")
    }
}

// ---------------------------------------------------------------------------
// Release signing - environment-driven, fail-closed (privacy-release-prep Stage 5)
// ---------------------------------------------------------------------------
// The repository contains NO keystore and NO committed credentials - signing
// credentials and keystore files must NEVER be committed. Release signing is
// driven entirely by four environment variables: locally, the owner may set
// them in the Windows environment before a release build; in CI, the trusted
// GitHub Actions release job (which runs only when a PR is merged into main,
// see .github/workflows/release-artifacts.yml) supplies the same contract via
// repository secrets and a temporary runner keystore. `signing.env.example`
// documents the names with dummy values only, and `/signing.env` is gitignored.
// The fail-closed behavior documented below is unchanged and applies equally
// to local and CI builds.
//
// Behavior (fail-closed):
//   * none of the four set  -> release builds stay UNSIGNED (the unchanged
//     default; CI and clean checkouts behave exactly as before);
//   * all four set          -> release builds are signed with the referenced
//     keystore (a machine-local path that never enters the repository);
//   * a PARTIAL set         -> Gradle configuration fails immediately with a
//     clear message, so a half-configured environment can never silently
//     produce an artifact that could be mistaken for a signed release.
//
// The signing identity itself is unchanged from the maintainer's existing
// release key; this block only decides whether THIS build signs with it.
// Values are read via providers.environmentVariable (configuration-cache-safe)
// and are never logged, printed, or embedded in any report.
val pbSigningEnvNames = listOf(
    "PB_KEYSTORE_PATH",
    "PB_KEYSTORE_PASSWORD",
    "PB_KEY_ALIAS",
    "PB_KEY_PASSWORD",
)
val pbSigningValues: Map<String, String?> = pbSigningEnvNames.associateWith { name ->
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
}
val pbSigningPresent = pbSigningValues.count { it.value != null }

if (pbSigningPresent != 0 && pbSigningPresent != pbSigningEnvNames.size) {
    val missing = pbSigningValues.filterValues { it == null }.keys.joinToString(", ")
    throw GradleException(
        "Incomplete release-signing configuration: $pbSigningPresent of " +
            "${pbSigningEnvNames.size} required environment variables are set " +
            "($missing missing). All four (${pbSigningEnvNames.joinToString(", ")}) " +
            "must be set together or all be unset - see signing.env.example. " +
            "Refusing to configure rather than silently producing an unsigned " +
            "artifact that could be mistaken for a signed release.",
    )
}
val pbSigningReady = pbSigningPresent == pbSigningEnvNames.size

// ---------------------------------------------------------------------------
// Release version propagation: -PappVersionName / -PappVersionCode
// ---------------------------------------------------------------------------
// The release workflow resolves the release version and passes it to Gradle as
// -PappVersionName=... / -PappVersionCode=.... Those properties MUST be read
// here: a previous revision hardcoded the values in defaultConfig, so the
// CI-supplied versions were silently ignored (Gradle does not fail on unused
// -P properties) and stale defaults were embedded in the APK and AAB.
// Contract:
//   * property supplied -> validated below (non-blank versionName, positive
//     integer versionCode) and used; an invalid value fails the build during
//     configuration, before any artifact is produced;
//   * property absent   -> the local defaults in defaultConfig apply, so local
//     development builds behave exactly as before;
//   * CI release-artifact build (assembleRelease/bundleRelease requested with
//     CI=true) -> BOTH properties are REQUIRED: a missing value fails the
//     build during configuration instead of falling back to stale defaults.
val pbVersionNameProp = providers.gradleProperty("appVersionName").orNull
val pbVersionCodeProp = providers.gradleProperty("appVersionCode").orNull

if (pbVersionNameProp != null && pbVersionNameProp.isBlank()) {
    throw GradleException(
        "-PappVersionName was supplied but is blank; refusing to configure an " +
            "invalid versionName."
    )
}
val pbVersionCodeNum: Int? = pbVersionCodeProp?.let { raw ->
    val parsed = raw.toIntOrNull()
    if (parsed == null || parsed <= 0) {
        throw GradleException(
            "-PappVersionCode '${raw}' is not a positive integer; refusing to " +
                "configure an invalid versionCode."
        )
    }
    parsed
}

val pbReleaseArtifactRequested = gradle.startParameter.taskNames.any { task ->
    val leaf = task.substringAfterLast(':')
    leaf == "assembleRelease" || leaf == "bundleRelease"
}
if (providers.environmentVariable("CI").orNull == "true" && pbReleaseArtifactRequested) {
    if (pbVersionNameProp == null) {
        throw GradleException(
            "CI release build requires -PappVersionName; refusing to fall back " +
                "to the local default versionName."
        )
    }
    if (pbVersionCodeProp == null) {
        throw GradleException(
            "CI release build requires -PappVersionCode; refusing to fall back " +
                "to the local default versionCode."
        )
    }
}

android {
    namespace = "com.pahebatcher.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pahebatcher.app"
        minSdk = 32
        targetSdk = 37
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 4
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Created only when all four PB_* environment variables are present (see
    // pbSigningReady above); when absent, assembleRelease keeps producing
    // unsigned per-ABI APKs/AAB exactly as before.
    if (pbSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = file(pbSigningValues.getValue("PB_KEYSTORE_PATH")!!)
                storePassword = pbSigningValues.getValue("PB_KEYSTORE_PASSWORD")
                keyAlias = pbSigningValues.getValue("PB_KEY_ALIAS")
                keyPassword = pbSigningValues.getValue("PB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (pbSigningReady) {
                signingConfigs.getByName("release")
            } else {
                null // unsigned by default; never signed with dummy values
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        // Phase 11: BuildConfig.DEBUG used to gate ValidationScreen to debug builds.
        buildConfig = true
    }

    // ABI packaging (privacy-release-prep Stage 4, 2026-10-24). FFmpegKit 8.1.7
    // ships native libraries for arm64-v8a and x86_64 ONLY (verified from the
    // merged APK: the armeabi-v7a/x86 dirs contained just 2 AndroidX .so stubs,
    // no libffmpegkit/libav*). Installing on a 32-bit-only device would crash at
    // FFmpegKit load with UnsatisfiedLinkError, so:
    //   * splits.abi below produces one APK per supported ABI (arm64-v8a,
    //     x86_64) for direct download - a user downloads only their ABI;
    //   * packaging.jniLibs.excludes prunes the 32-bit stub dirs from the
    //     MERGED native libs, so the AAB (which splits do not affect) also
    //     declares only the two ABIs that can actually run the app.
    // AGP forbids combining splits.abi with ndk.abiFilters (conflicting
    // configuration), which is why the pruning is done via packaging excludes.
    // No flavors, no new application IDs, same signing identity as before.
    packaging {
        jniLibs {
            excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**")
        }
    }

    // ABI packaging (privacy-release-prep Stage 4): per-ABI APKs for direct
    // download (app-release-<abi>-unsigned.apk), so a user downloads only the
    // native code for their device instead of the combined universal APK.
    // The two ABIs listed here are exactly the ones FFmpegKit supports (see the
    // packaging comment above); universalApk is off because a combined APK
    // would only re-introduce the redundant download weight.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
}

kotlin {
    jvmToolchain(25)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Phase 11 (§9/§32): ONLY the core icon set (Home/Settings/Search/Close/Refresh/
    // PlayArrow/ArrowBack) — version pinned by the already-verified Compose BOM, NOT
    // material-icons-extended (rejected per §9/§32). material3 1.4.0 no longer exposes
    // the icons package transitively, so the ~dozen core glyphs are declared here.
    implementation(libs.androidx.compose.material.icons.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // OkHttp (Phase 5 validation)
    implementation(libs.okhttp)

    // FFmpegKit (Phase 9 — MP4 assembly candidate, validation only)
    implementation(libs.ffmpegkit.full)
    // Required runtime dependency not declared by ffmpeg-kit-full POM (Maven Central packaging bug)
    implementation(libs.smartexception.java)

    // WorkManager (Phase 10 — background downloads)
    implementation(libs.androidx.work.runtime)

    // Media3 (Phase 11 — player)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.session)

    // Media3 (Phase 17 — direct HLS streaming + OkHttp transport, same media3 line)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)

    // Navigation (Phase 11 — single-activity NavHost)
    implementation(libs.androidx.navigation.compose)

    // Lifecycle ViewModel (Phase 11 — nav-entry-scoped PlayerViewModel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Lifecycle Runtime Compose (Phase 11 §13 — collectAsStateWithLifecycle)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Testing
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    // Android Instrumentation Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
