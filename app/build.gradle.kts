import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
}

// Secrets are kept out of source control - set them in local.properties
// (which is gitignored) and they're surfaced via BuildConfig.
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// -Pnokey bakes an EMPTY Gemini key (ships nothing), so the build exercises the
// real no-key BYO first-run instead of auto-BYO from the dev key in
// local.properties. The launch/Play build should use -Pnokey.
val geminiApiKey: String =
    if (project.hasProperty("nokey")) "" else localProps.getProperty("GEMINI_API_KEY", "")

// TomTom, for traffic and ETA (location-intelligence map, settled decision 4). Same convenience-
// key shape as GEMINI_API_KEY and for the same reason: local.properties is gitignored, so a real
// key never reaches a public repo, and -Pnokey exercises the honest no-key path.
//
// **This is the DEV convenience key, not the shipping path.** Clone-and-run means a stranger has
// no key here, so the product path is BYO through KeyVault on the Setup screen, exactly like the
// Gemini and Spotify keys. A build with no TomTom key must degrade in words - "I can't check
// traffic without a TomTom key" - never fail silently.
val tomtomApiKey: String =
    if (project.hasProperty("nokey")) "" else localProps.getProperty("TOMTOM_API_KEY", "")

// AirNow (US EPA air quality). Same BYO shape and the same degrade-in-words rule: with no key the
// air-quality answer says it has no key rather than reporting clean air it never measured. A
// missing reading and a good reading are different sentences (CLAUDE.md sec 1).
val airnowApiKey: String =
    if (project.hasProperty("nokey")) "" else localProps.getProperty("AIRNOW_API_KEY", "")

// Release signing. The keystore (app/release.jks, gitignored) is a personal
// signing key, not a Play Store upload key. All four values must come from
// local.properties - no hardcoded fallback, so the password never lands in
// source control.
val releaseStoreFile: String = localProps.getProperty("RELEASE_STORE_FILE", "release.jks")
val releaseStorePassword: String = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias: String = localProps.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword: String = localProps.getProperty("RELEASE_KEY_PASSWORD", "")

android {
    namespace = "com.kevin.legion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kevin.legion"
        minSdk = 24
        // DO NOT bump this to 35 without reading AriaForegroundService.startForegroundCompat and
        // BootReceiver first (2026-08-17). At 35, `dataSync` joins `microphone` on the
        // BOOT_COMPLETED-prohibited foreground-service-type list, which breaks the boot-time
        // reconciliation AssistantIgnition.resumeIfEnabled relies on outright - there would be no
        // type left that BootReceiver is allowed to start with. 35 also activates the `dataSync`
        // 6h/24h foreground-service time cap and Service.onTimeout(), which neither
        // AriaForegroundService nor LedgerIngestService implements today - an unimplemented
        // onTimeout on a capped dataSync service is a fatal RemoteServiceException, not a soft
        // stop. Both are real work items, not a toggle to flip.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "TOMTOM_API_KEY", "\"$tomtomApiKey\"")
        buildConfigField("String", "AIRNOW_API_KEY", "\"$airnowApiKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
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
    }

    // A shareable APK must not carry the owner's own life in it.
    //
    // `assets/midnight_import/` is the one-shot seeding bundle described in
    // data/MidnightImport.kt: gzipped NDJSON exported off the private Midnight AI
    // archive. It is correctly gitignored and has never been committed - verified
    // 2026-08-17 with `git ls-files` and a `git rev-list --all --objects` scan, both
    // zero - so a stranger's CLONE never has it and MidnightImport.run() no-ops there
    // exactly as its doc claims.
    //
    // What gitignore cannot do is keep it out of an APK BUILT ON THIS MACHINE, since
    // assets/ is packaged from the working tree rather than from git. Those files
    // carry a real VIN, the ELM327's Bluetooth MAC, real lat/long in `places`, and
    // written drive narratives. Handing anyone a build - a reviewer, an employer, a
    // sideload - would disclose all of it.
    //
    // The bundle therefore lives in `src/debug/assets/`, NOT `src/main/assets/`, so a
    // release build has no such directory to package. This is deliberately structural
    // rather than a packaging filter: the filter was tried first
    // (`variant.packaging.resources.excludes`, scoped by build type) and PROVEN NOT TO
    // WORK - a debug APK built with it still contained all 14 files, because
    // `packaging.resources` governs Java resources and never touches Android assets.
    // A guard that looks right and excludes nothing is worse than no guard, so it was
    // replaced with a source set that cannot silently fail.
    //
    // Debug keeps the bundle on purpose: the import has still never run against its
    // own unset-flag condition (memory/MEMORY.md), and moving it out of debug too
    // would delete the only remaining way to exercise that path.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time is API 26+ but minSdk is 24; desugaring backports it at
        // compile time (ported rationale from Midnight AI's util/Dates.kt).
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
        // assets so MigrationTestHelper can validate migrations on disk.
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
    testOptions {
        unitTests {
            // PdfBox-Android's fonts/glyphlists/cmaps ship in the AAR's assets/,
            // reachable only via Android's AssetManager - not on a plain JVM unit
            // test's classpath. Robolectric (below) needs this to shadow that
            // AssetManager with the real merged assets instead of a stub.
            isIncludeAndroidResources = true

            // Android framework stubs throw "not mocked" by default, so ANY class that calls
            // android.util.Log is untestable in a plain JVM test - the logic under test never even
            // runs. Added 2026-08-21 when MicArbiter's priority tests all failed on Log.d rather
            // than on anything they were asserting.
            //
            // Returning defaults instead is the standard fix and the right trade here: this repo
            // logs the WHY of decisions heavily, and a rule that says "do not log in anything you
            // want to unit test" would push logging out of exactly the code most worth explaining.
            //
            // The cost, stated: a test can no longer distinguish a stubbed framework call from a
            // real one. Nothing here asserts on framework behaviour - the pure-logic seams
            // (decideOnHistory, claimAnnouncement, nearest, MicArbiter) are pure by design and take
            // their inputs as parameters - so there is nothing to mask.
            isReturnDefaultValues = true
        }
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material3.windowsizeclass)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    // ProcessLifecycleOwner (aspect-engine ticket 20 MUST-FIX 2): real app-foreground/background
    // triggers for MirrorSync, wired from MirrorLifecycleBinder / MidnightApplication.
    implementation(libs.lifecycle.process)
    // Single-activity shell (ticket 07): bottom-nav tabs + absorbed sub-routes.
    implementation(libs.navigation.compose)
    // Not used directly by app code. Declared to raise navigation-compose's
    // transitive kotlinx-serialization 1.6.3 to the 1.8.1 Room 2.8.4's
    // MigrationTestHelper needs: AGP's consistent resolution pins the
    // androidTest classpath to whatever the app runtime resolves, so the
    // migration test cannot be fixed from the test configuration alone.
    implementation(libs.kotlinx.serialization.core)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Coroutines
    implementation(libs.coroutines.android)

    // Gemini Live API speaks over a WebSocket; OkHttp is the Android-viable
    // client (minSdk 24 - java.net.http.WebSocket needs API 33).
    implementation(libs.okhttp)

    // Google Identity Authorization API: on-device authorization to the
    // driver's OWN Google Drive appDataFolder for BYO-cloud cross-device sync.
    implementation(libs.play.services.auth)

    // Geofencing (location-intelligence ticket 05): GeofencingClient, separate artifact from
    // play-services-auth above (that one is Drive sign-in only).
    implementation(libs.play.services.location)

    // Ledger aspect (.claude/plans/wiggly-beaming-quasar.md): PDF text/coordinate
    // extraction for bank-statement parsing. Android has no built-in equivalent
    // to Python's pdfplumber word-position extraction the DBS parser depends on.
    implementation(libs.pdfbox.android)

    // Aspect-engine mirror/sync (ticket 20): xlsx workbook read/write. Plain-JVM library, no
    // Android assets - see MirrorCodec's own doc comment for why this needs no Robolectric.
    implementation(libs.fastexcel.writer)
    implementation(libs.fastexcel.reader)

    // Custom wake word ("hey <name>"), ported from Midnight AI.
    implementation(libs.vosk.android)

    // Media3 session (`.scratch/android-auto/map.md`). CLAUDE.md §3 lists Media3 as deliberately
    // dropped at the pivot - it is back for the Android Auto media surface only
    // (LegionMediaLibraryService), NOT the ExoPlayer stack. Kevin approved re-adding it 2026-08-13.
    implementation(libs.media3.session)
    // androidx.media (legacy media-compat) - see libs.versions.toml's media-compat entry for why an
    // explicit dependency is needed even though media3-session already pulls this in transitively:
    // MediaConstants (root-hints and custom-action key constants) isn't exposed on the compile
    // classpath from a transitive-only, implementation-scoped dependency.
    implementation(libs.media.compat)

    // Spotify App Remote SDK (BYO client ID): the driver registers their own
    // Spotify dev app; nothing ships a shared Kevin client ID. The .aar is
    // vendored in app/libs/ (not on Maven); needs Gson at runtime.
    implementation(files("libs/spotify-app-remote-release.aar"))
    implementation("com.google.code.gson:gson:2.10.1")

    debugImplementation(libs.ui.tooling)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    // Robolectric: needed so ledger PDF-parsing unit tests can shadow
    // AssetManager and reach PdfBox-Android's bundled fonts/glyphlists (see
    // testOptions.unitTests.isIncludeAndroidResources above for why).
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.room.testing)
}
