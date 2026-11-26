// buildSrc holds the pure dependency-resolution helpers used by
// pahebatcher/build.gradle.kts (security-pin version comparison and the
// Guava flavor decision). It exists so the logic can be unit-tested:
// build-script bodies cannot be exercised from the app's test source set.
// See DECISIONS.md 167.12 and src/test/.../PbDependencyVersionsTest.kt.
//
// Dependency verification is global (Gradle: the root
// gradle/verification-metadata.xml applies to buildSrc too), so everything
// resolved here is checksum-enforced. junit 4.13.2 (the same line as the
// app's libs.junit) and hamcrest were already recorded; the kotlin-dsl
// plugin's own artifacts (marker POM + the KGP 2.4.10 toolchain) were added
// to the metadata by the documented regeneration command (34 components,
// 503 -> 537) because this module is the first build to apply that plugin.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
