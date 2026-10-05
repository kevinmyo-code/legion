pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (wake-word ticket 18, 2026-10-03): k2-fsa publishes the Android AAR only as a
        // GitHub release asset, not on Maven Central. The static-link-onnxruntime variant is used: onnxruntime
        // is folded into the one JNI library (24 MB for arm64) instead of shipping beside it (32 MB). An ivy layout over the release URL keeps the
        // 50 MB binary out of this public repo and still resolves for a stranger who clones and
        // builds (CLAUDE.md sec 2, clone-and-run). Scoped to its one group so nothing else is
        // ever looked up on GitHub.
        ivy {
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/sherpa-onnx-static-link-onnxruntime-[revision].aar") }
            metadataSources { artifact() }
            content { includeGroup("com.k2fsa.github") }
        }
        // Mapbox Navigation SDK v3 (`.scratch/mapbox-nav/`). Anonymous by design: the core SDK
        // artifacts download with no secret token (probed with curl 2026-10-03, and this block is
        // what turns that into a Gradle fact), so a stranger's clone with zero Mapbox setup still
        // resolves - CLAUDE.md sec 2 clone-and-run. Credentials are attached ONLY when a
        // `MAPBOX_DOWNLOADS_TOKEN` Gradle property exists, as insurance if Mapbox re-locks the repo
        // (their ToS wording still says Downloads API requests carry a key); absent, nothing is sent.
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            val downloadsToken = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull
            if (!downloadsToken.isNullOrBlank()) {
                authentication { create<BasicAuthentication>("basic") }
                credentials {
                    username = "mapbox"
                    password = downloadsToken
                }
            }
        }
    }
}

rootProject.name = "Legion"
include(":app")
