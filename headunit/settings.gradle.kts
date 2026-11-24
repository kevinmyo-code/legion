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
        // Mapbox Navigation SDK v3 (HU-1 T5), the same repository and rule as the phone project's
        // settings.gradle.kts: it resolves ANONYMOUSLY (no secret downloads token on this machine),
        // and credentials are attached only if a `MAPBOX_DOWNLOADS_TOKEN` Gradle property exists.
        // Scoped to Mapbox's groups so nothing else is looked up there.
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            content { includeGroupByRegex("com\\.mapbox.*") }
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

rootProject.name = "AiApp"
include(":app")
 