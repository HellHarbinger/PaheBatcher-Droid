// ---------------------------------------------------------------------------
// Security pins for vulnerable TRANSITIVE dependencies
// ---------------------------------------------------------------------------
// The AGP/lint toolchain introduces several vulnerable transitive libraries
// (BouncyCastle, jose4j, jdom2, commons-lang3, guava, httpclient) that cannot
// be reached through the dependency block - they live on the plugin classpath
// and on tooling-only configurations (androidLintTool, ksp processor paths).
// They are pinned to patched versions declared centrally in
// gradle/libs.versions.toml. Pins are MINIMUM versions: they only ever
// upgrade a requested version, never downgrade one, so a future toolchain
// that ships a newer (already-patched) release is left alone.
// These pins are enforced by Gradle itself in every local and CI build; they
// are NOT managed by Dependabot. Dependabot version updates cover only
// dependencies declared in the supported manifests, and security updates for
// these transitive-only artifacts are not established (LIMITATIONS.md 54.7).
// The version comparison and Guava flavor decision live in buildSrc
// (PbDependencyVersions.kt) so they are unit-tested: the project-level
// helpers below import them, while the buildscript block - which compiles in
// a separate pass that does not apply file-level imports - references them
// fully-qualified. Rationale and advisory mapping: DECISIONS.md.

import com.pahebatcher.buildlogic.pbGuavaFlavor
import com.pahebatcher.buildlogic.pbGuavaTargetVersion
import com.pahebatcher.buildlogic.pbVersionAtLeast

/** Central pin versions read from the gradle/libs.versions.toml catalog. */
fun pbSecurityPins(): Map<String, String> = mapOf(
    "bouncycastle" to libs.versions.bouncycastle.get(),
    "jdom2" to libs.versions.jdom2.get(),
    "jose4j" to libs.versions.jose4j.get(),
    "guava" to libs.versions.guava.get(),
    "commonsLang3" to libs.versions.commonsLang3.get(),
    "httpclient" to libs.versions.httpclient.get(),
)

/**
 * Applies the security pins to a single dependency request.
 *
 * [configurationName] is required only for guava, whose artifact has two
 * publishable flavors: the JVM tooling classpaths (buildscript classpath,
 * androidLintTool, ksp processor classpaths) resolve the -jre flavor, while
 * every other configuration in this project (compile/runtime/lint-checks/
 * annotation-processor classpaths) resolves the -android flavor. The flavor is
 * chosen from the configuration so the two can never collide within one
 * graph; both flavors carry the same patched base version.
 */
fun DependencyResolveDetails.pbApplySecurityPins(
    configurationName: String,
    pins: Map<String, String>,
) {
    val group = requested.group ?: return
    val artifact = requested.name ?: return
    val version = requested.version ?: return

    when {
        group == "org.bouncycastle" && artifact.endsWith("-jdk18on") -> {
            val pin = pins.getValue("bouncycastle")
            if (!pbVersionAtLeast(version, pin)) {
                useVersion(pin)
                because(
                    "Security pin: BouncyCastle jdk18on family aligned to $pin " +
                        "(CVE-2026-8763, CVE-2026-13506, CVE-2026-5588)"
                )
            }
        }

        group == "org.jdom" && artifact == "jdom2" -> {
            val pin = pins.getValue("jdom2")
            if (!pbVersionAtLeast(version, pin)) {
                useVersion(pin)
                because("Security pin: jdom2 $pin (CVE-2021-33813, XXE)")
            }
        }

        group == "org.bitbucket.b_c" && artifact == "jose4j" -> {
            val pin = pins.getValue("jose4j")
            if (!pbVersionAtLeast(version, pin)) {
                useVersion(pin)
                because("Security pin: jose4j $pin (CVE-2024-29371, DoS)")
            }
        }

        group == "com.google.guava" && artifact == "guava" -> {
            val pin = pins.getValue("guava")
            val flavor = pbGuavaFlavor(configurationName)
            // Decision logic lives in buildSrc and is unit-tested: a newer
            // requested base is ALWAYS preserved (flavor applied to it), a
            // sub-pin request is raised to the pin, and an already-correct
            // request is not rewritten - flavor selection never downgrades.
            val target = pbGuavaTargetVersion(version, pin, flavor)
            if (target != version) {
                useVersion(target)
                because("Security pin: guava $target (CVE-2026-102554)")
            }
        }

        group == "org.apache.commons" && artifact == "commons-lang3" -> {
            val pin = pins.getValue("commonsLang3")
            if (!pbVersionAtLeast(version, pin)) {
                useVersion(pin)
                because("Security pin: commons-lang3 $pin (CVE-2025-48924)")
            }
        }

        group == "org.apache.httpcomponents" && artifact == "httpclient" -> {
            val pin = pins.getValue("httpclient")
            if (!pbVersionAtLeast(version, pin)) {
                useVersion(pin)
                because("Security pin: httpclient $pin (CVE-2020-13956)")
            }
        }
    }
}

// Buildscript classpath pins (plugin/AGP toolchain transitives). NOTE: this
// block compiles in a separate pass that cannot see script-level declarations,
// so the pin MAP is duplicated locally; the version comparison and Guava
// flavor decision come from buildSrc, which is on the classpath of every
// build script, so both passes share ONE tested implementation. The shared
// helpers above serve every project configuration instead.
buildscript {
    // Lock the plugin/AGP classpath too (separate gradle.lockfile for the
    // buildscript: buildscript-gradle.lockfile).
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    val pbPins = mapOf(
        "bouncycastle" to libs.versions.bouncycastle.get(),
        "jdom2" to libs.versions.jdom2.get(),
        "jose4j" to libs.versions.jose4j.get(),
        "guava" to libs.versions.guava.get(),
        "commonsLang3" to libs.versions.commonsLang3.get(),
        "httpclient" to libs.versions.httpclient.get(),
    )
    configurations.all {
        resolutionStrategy.eachDependency {
            val g = requested.group ?: return@eachDependency
            val n = requested.name ?: return@eachDependency
            val v = requested.version ?: return@eachDependency
            when {
                g == "org.bouncycastle" && n.endsWith("-jdk18on") -> {
                    val pin = pbPins.getValue("bouncycastle")
                    if (!com.pahebatcher.buildlogic.pbVersionAtLeast(v, pin)) useVersion(pin)
                }
                g == "org.jdom" && n == "jdom2" -> {
                    val pin = pbPins.getValue("jdom2")
                    if (!com.pahebatcher.buildlogic.pbVersionAtLeast(v, pin)) useVersion(pin)
                }
                g == "org.bitbucket.b_c" && n == "jose4j" -> {
                    val pin = pbPins.getValue("jose4j")
                    if (!com.pahebatcher.buildlogic.pbVersionAtLeast(v, pin)) useVersion(pin)
                }
                g == "com.google.guava" && n == "guava" -> {
                    // The buildscript classpath always runs on the JVM: -jre
                    // flavor. pbGuavaTargetVersion preserves any newer
                    // requested base instead of forcing the pin (the old
                    // inline check downgraded e.g. 34.0.0-jre to 33.7.2-jre).
                    val pin = pbPins.getValue("guava")
                    val target = com.pahebatcher.buildlogic.pbGuavaTargetVersion(v, pin, "jre")
                    if (target != v) useVersion(target)
                }
                g == "org.apache.commons" && n == "commons-lang3" -> {
                    val pin = pbPins.getValue("commonsLang3")
                    if (!com.pahebatcher.buildlogic.pbVersionAtLeast(v, pin)) useVersion(pin)
                }
                g == "org.apache.httpcomponents" && n == "httpclient" -> {
                    val pin = pbPins.getValue("httpclient")
                    if (!com.pahebatcher.buildlogic.pbVersionAtLeast(v, pin)) useVersion(pin)
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
}

// Project configurations (compile/runtime/ksp/lint/annotation-processor classpaths):
// same pins as the buildscript classpath above.
configurations.all {
    val pbPins = pbSecurityPins()
    val pbConfigurationName = name
    resolutionStrategy.eachDependency {
        pbApplySecurityPins(pbConfigurationName, pbPins)
    }
}

// Dependency locking: every project configuration resolves against the checked
// in pahebatcher/gradle.lockfile (the buildscript classpath locks into
// pahebatcher/buildscript-gradle.lockfile, activated above). After any
// intentional version change regenerate locks AND checksums TOGETHER (locks
// alone fail dependency verification for newly resolved artifacts):
//   ./gradlew --write-verification-metadata sha256 --write-locks <build tasks>
// The full task list is documented in .github/dependabot.yml.
dependencyLocking {
    lockAllConfigurations()
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
