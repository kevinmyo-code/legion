// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Roborazzi (hardening ticket 01): screenshot tests, wired only in app/build.gradle.kts.
    alias(libs.plugins.roborazzi) apply false
    // KSP (architecture ticket 01, Room moves off kapt): version pinned to "2.2.10-2.0.2" because
    // the ACTUAL Kotlin compiler this repo runs is 2.2.10 (set above), not the "2.1.0" in
    // gradle/libs.versions.toml's `kotlin` ref - that ref is stale for this plugin pair and is
    // only still read by the kotlin-serialization plugin alias. Confirmed against the Gradle
    // Plugin Portal's own maven-metadata.xml for com.google.devtools.ksp, not guessed: 2.2.10 has
    // exactly one published KSP release, 2.2.10-2.0.2.
    alias(libs.plugins.ksp) apply false
    // detekt (architecture ticket 05): applied for real in app/build.gradle.kts, declared here
    // apply-false per the usual root-catalog convention. See libs.versions.toml's `detekt` entry
    // for why 2.0.0-alpha.0.
    alias(libs.plugins.detekt) apply false
}
