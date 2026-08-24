// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Roborazzi (hardening ticket 01): screenshot tests, wired only in app/build.gradle.kts.
    alias(libs.plugins.roborazzi) apply false
}
