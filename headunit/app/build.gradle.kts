import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
    // Django-engine client codegen (backend/engine/api, third "limb" client per ADR
    // 0044). See the openApiGenerate block below.
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.openapi.generator)
    // Firebase: google-services reads app/google-services.json and injects
    // resource strings; crashlytics uploads mapping files on release builds.
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// Secrets are kept out of source control - set them in local.properties
// (which is gitignored) and they're surfaced via BuildConfig.
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// -Pnokey bakes an EMPTY Gemini key (ships nothing), so the build exercises the
// real no-key BYO first-run (add-your-own-key in Settings) instead of auto-BYO
// from the dev key in local.properties. Without the flag, the local.properties
// key is baked in as before for convenient dev/test builds. The launch/Play
// build should use -Pnokey so a personal key never ships.
val geminiApiKey: String =
    if (project.hasProperty("nokey")) "" else localProps.getProperty("GEMINI_API_KEY", "")

// Release signing. The keystore (app/release.jks, gitignored) is a personal
// self-signed key generated for sideloading onto the head unit - it is NOT a
// Play Store upload key. All four values must come from local.properties
// (RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
// RELEASE_KEY_PASSWORD, gitignored) - no hardcoded fallback, so the password
// never lands in source control. assembleDebug doesn't need these; only a
// release build reads them, and Gradle fails that build loudly if any are
// blank rather than silently signing with an empty password.
val releaseStoreFile: String = localProps.getProperty("RELEASE_STORE_FILE", "release.jks")
val releaseStorePassword: String = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias: String = localProps.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword: String = localProps.getProperty("RELEASE_KEY_PASSWORD", "")

android {
    namespace = "com.kevin.midnightai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kevin.midnightai"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        // The commit is in the name (2026-10-24) so `device_logs.app_version_name` answers "is the
        // latest build installed". Falls back to "1.0" outside a git checkout (clone-and-run).
        versionName = "1.0+" + (runCatching {
            providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "nogit")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")

        // No abiFilters restriction: the app is pure Kotlin/Java now that the
        // offline wake-word engine (sherpa-onnx, arm64-only native libs) was
        // removed - this build installs on any device architecture.
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    // ABI splits. The head unit is sideloaded from an APK Kevin downloads, and the universal
    // debug APK was 262 MB - of which 245 MB was native libraries for FOUR architectures, and
    // 136 MB of that was x86/x86_64, which exist only for emulators. A car head unit is ARM, so
    // more than half the download was bytes that could never execute on the target device.
    //
    // isUniversalApk = false on purpose: a universal APK would silently reappear alongside the
    // split ones and is exactly the file someone grabs by mistake. Two ARM APKs are produced -
    // arm64-v8a for anything current, armeabi-v7a for the older AOSP units - and installing the
    // wrong one fails loudly at install time rather than misbehaving later.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Sideload build for the head unit: release-speed (not debuggable, so ART fully
        // optimizes and Compose runs at real speed) but signed with the DEBUG key, so it
        // installs over the existing debug install and keeps its data. A release.jks-signed
        // APK would need an uninstall first, which wipes the Keystore secrets, sign-ins and DB.
        // BuildConfig.DEBUG is false here, so debug-only UI and telemetry are hidden.
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time is API 26+ but minSdk is 24: on an Android 7.x head unit the
        // first proactive greeting (LocalTime.now) and every logbook date format
        // (util/Dates.kt) would crash. Desugaring backports java.time at compile
        // time - zero behavior change on API 26+ devices, ~150KB APK cost.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        // Room's exported schema JSONs (app/schemas/) are bundled as androidTest
        // assets so MigrationTestHelper can build a real v-N database on disk
        // and validate MIGRATION_N_N+1 against it.
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

// Room emits its schema JSON here on every build - committed to the repo so
// MigrationTestHelper (androidTest) can validate real migrations against a
// known-good prior version instead of relying on destructive fallback.
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

// Generates the Django engine's typed client models (35 classes: Event, Checklist,
// ChecklistItem, ChangesResponse, LoginRequest, LoginResponse, MeResponse,
// ProvenanceEnum, ...) from the vendored OpenAPI 3 contract (openapi/README.md).
// Output lands under build/ - NEVER committed, matching every other generated
// artifact in this build (Room's schema JSON is the one deliberate exception, and
// it is committed for a stated reason there, not by default).
openApiGenerate {
    generatorName = "kotlin"
    inputSpec = "$rootDir/openapi/legion-schema.yaml"
    outputDir = "$buildDir/openapi-generated"
    packageName = "com.kevin.midnightai.backend.engine.api"
    modelPackage = "com.kevin.midnightai.backend.engine.api.model"
    // Models only - no ApiClient/Api-interface classes, no docs, no tests: this
    // limb's transport is the ported EngineHttp/EngineAuth (OkHttp, hand-written,
    // see backend/engine/), not generator-emitted API classes.
    globalProperties = mapOf("models" to "", "modelDocs" to "false", "modelTests" to "false")
    // The schema's uuid-formatted string fields decode to plain Kotlin String, not a
    // java.util.UUID type the kotlinx_serialization templates don't natively serialize.
    typeMappings = mapOf("java.util.UUID" to "kotlin.String", "UUID" to "kotlin.String")
    configOptions = mapOf(
        "serializationLibrary" to "kotlinx_serialization",
        "dateLibrary" to "string",
        "enumPropertyNaming" to "UPPERCASE",
    )
}

// A clean clone must generate before it compiles - openApiGenerate is NOT a manual
// step a contributor has to remember to run first. tasks.named("compileDebugKotlin")
// fails at configuration time here: AGP 9's variant Kotlin-compile tasks are
// registered lazily, later than this build script's own evaluation, so
// TaskCollection.named() cannot resolve the name yet. Matching by type instead
// reaches compileDebugKotlin (and every other variant's compile task) however late
// AGP creates it, with the same "generate before compile" effect the brief asked for.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("openApiGenerate")
}

android {
    sourceSets {
        getByName("main") {
            // build/openapi-generated/, deliberately NOT build/generated/. That subtree is AGP's
            // own; putting a generated source dir inside it broke resource merging in the LEGION
            // repo, and it surfaced as "Unresolved reference" on R.drawable members in a file
            // nowhere near the change, with the drawables present the whole time. Verified there
            // by building the same tree stashed (green) and unstashed (red). It did not bite in
            // this app, but the two limbs should not differ on a trap one of them already hit.
            kotlin.srcDir("$buildDir/openapi-generated/src/main/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Django-engine client (backend/engine/): decodes the generated OpenAPI models.
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    // Dual-target responsive infra: WindowWidthSizeClass (see ui/WindowSize.kt).
    implementation(libs.material3.windowsizeclass)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Media3: the mixtape player. ExoPlayer plays the sideloaded files; the
    // Media3 MediaSession publishes a real platform MediaSession so
    // NowPlayingController (which enumerates ALL active sessions) picks it up
    // for free - no changes needed to the now-playing HUD/badge/system prompt.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    // Coroutines
    implementation(libs.coroutines.android)

    // Gemini Live API speaks over a WebSocket; OkHttp is the Android-viable
    // client (minSdk 24 - the JDK's java.net.http.WebSocket needs API 33).
    implementation(libs.okhttp)

    // ZXing core (QR encoder only — no UI scanning activity, just BitMatrix generation).
    // Used by LogbookExporter to render the download QR on share cards.
    implementation(libs.zxing.core)

    // Firebase: BOM pins all versions; no version needed on individual artifacts.
    // ADB logcat is blocked on the head unit's vendor-locked dev menu, so
    // Crashlytics is the only way to observe crashes in production. No
    // firebase-analytics: it ships usage/device telemetry to a backend, which
    // conflicts with CLAUDE.md sec 9 ("no backend, no data collection") and
    // adds a Play Data Safety disclosure burden. Crashlytics does not need it.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    // (2026-07-19) The entitlement-broker stack (firebase-auth, appcheck-playintegrity,
    // firestore, functions) was REMOVED here: the broker itself was deleted 2026-07-16
    // (CLAUDE.md sec 2/8 - two tiers, no backend) and a grep confirmed zero remaining
    // imports of any of those four artifacts in app/src. Crashlytics + the BOM above
    // are the only live Firebase surface.


    // (2026-10-17) play-services-auth (Google Identity Authorization for the driver's Drive
    // appDataFolder) was REMOVED with the Drive sync, which Kevin retired; nothing else used it.

    // Spotify App Remote SDK (BYO client ID, 2026-07-21 reopen of the CLAUDE.md
    // sec 8 freeze - see memory/library/decisions.md). Optional power-user add-on:
    // the driver registers THEIR OWN Spotify dev app and pastes only its client ID
    // (redirect URI is app-fixed, must match the manifest scheme); nothing ships a
    // shared Kevin client ID, so there is no Development-Mode user cap to trip. The
    // .aar is vendored in app/libs/ (not on Maven); the SDK needs Gson at runtime.
    // Policy caveat: this BYO-own-dev-app pattern's compliance with Spotify's
    // developer terms is GRAY and under review (.scratch/spotify-byo/); Kevin
    // accepted the risk 2026-07-21.
    implementation(files("libs/spotify-app-remote-release.aar"))
    implementation("com.google.code.gson:gson:2.10.1")

    debugImplementation(libs.ui.tooling)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests: the android.jar bundled with local unit
    // tests stubs org.json to throw "not mocked", so JSON-parsing logic (e.g.
    // AgentProtocol) can only be exercised with the actual implementation.
    testImplementation("org.json:json:20231013")
    // backend/engine transport tests: runTest (kotlinx-coroutines-test) driving
    // EngineHttp/EngineAuth against a real local server (okhttp3.mockwebserver).
    // Robolectric only for EngineConfig's real android.content.Context requirement
    // (SharedPreferences) - see the version catalog entry's comment.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    // MigrationTestHelper - proves a real device DB survives each Migration
    // instead of relying on destructive fallback (see Migrations.kt).
    androidTestImplementation(libs.room.testing)
}