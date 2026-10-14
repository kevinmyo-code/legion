// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // kotlinx-serialization is the wire format the openapi-generator "kotlin" generator
    // emits for backend/engine/api's model classes (configOptions.serializationLibrary =
    // kotlinx_serialization, see :app's openApiGenerate block). Pinned to the SAME
    // hardcoded "2.2.10" as the two Kotlin plugins above, deliberately not through the
    // libs.versions.toml "kotlin" alias (which is stale at "2.1.0") - a Kotlin compiler
    // plugin must match the Kotlin compiler version actually resolved on the classpath,
    // and hardcoding it here alongside its siblings keeps that one version visible in one
    // place instead of silently drifting from a second, unused catalog entry.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    // Firebase: applied false here, applied in :app only.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.openapi.generator) apply false
}