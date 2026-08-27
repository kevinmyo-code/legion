import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
    // Roborazzi (hardening ticket 01): Robolectric-native screenshot tests, running inside
    // testDebugUnitTest like every other JVM unit test. Provides the recordRoborazziDebug /
    // compareRoborazziDebug / verifyRoborazziDebug task triple - see the `roborazzi { }` block
    // below and app/src/test/snapshots/README.md for what each does and when to run it.
    alias(libs.plugins.roborazzi)
    // backend-erp Phase 1: supabase-kt's Postgrest decode/encode needs a real @Serializable
    // generator, not just the kotlinx-serialization-core dependency already on the classpath
    // (that alone gives the runtime types with no annotation processor behind them).
    alias(libs.plugins.kotlin.serialization)
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

            // Roborazzi (hardening ticket 01) draws real pixels through Robolectric's NATIVE
            // graphics shadow layer (Skia via the host JVM), not the LEGACY software-canvas one -
            // NATIVE is what makes a captured PNG resemble what actually renders on a phone rather
            // than Robolectric's own approximation of Android's 2D pipeline. Robolectric 4.10+
            // already defaults to NATIVE, but the mode is pinned explicitly rather than trusted to
            // a default that could silently change under a future Robolectric bump - see
            // app/src/test/resources/robolectric.properties (`graphicsMode=NATIVE`), which every
            // Robolectric test in this module reads, not just the screenshot ones.

            // `roborazzi.output.dir` alone is NOT enough to make a bare `captureRoboImage("x.png")`
            // land under it - confirmed 2026-08-24 by decompiling roborazzi-core-jvm-1.72.0.jar
            // (`FileWithRecordFilePathStrategyKt.fileWithRecordFilePathStrategy`, called from every
            // `captureRoboImage` overload): a relative file path is resolved against
            // `roborazzi.output.dir` ONLY under the `RelativePathFromRoborazziContextOutputDirectory`
            // strategy, and Roborazzi's OWN default is `RelativePathFromCurrentDirectory` - which
            // is exactly why every baseline first landed in the module root (`app/*.png`, the test
            // JVM's working directory) rather than under `outputDir`. Both properties are pinned
            // explicitly here rather than trusted to the `roborazzi { outputDir.set(...) }`
            // convention-plugin DSL, which sets `roborazzi.output.dir` correctly on its own but has
            // no equivalent DSL surface for the strategy property.
            all {
                it.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
            }
        }
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

// backend-erp Phase 2 (2026-08-26): the Compose `resolutionStrategy.force` that used to live here
// is GONE, and the compose-bom moved forward instead. Recorded because the removed block was
// well-argued and someone will be tempted to put it back.
//
// **What it actually did: it broke the app on the phone.** Forcing the runtime to 1.7.0 produced
// `java.lang.NoSuchMethodError: No interface method shouldExecute(ZI)Z in class
// androidx.compose.runtime.Composer` at launch, every time. Unit tests and Roborazzi never
// exercised that path and `assembleDebug` succeeded, so nothing caught it until the APK was
// installed on the A25. Compiling is not running.
//
// **The real root cause was never the new dependencies.** This project is Kotlin 2.1.0, whose
// Compose compiler emits calls against a 1.9-era runtime, while compose-bom 2024.05.00 pins a
// 1.6.7-era one. `navigation-compose:2.8.0` dragging `ui` up to 1.7.0 masked the gap well enough
// to boot. Adding supabase-kt/ktor shifted resolution to 1.9.0, which was CORRECT and would have
// made the app work; forcing it back to 1.7.0 is what made it crash. The four screenshot-test
// failures that motivated the force were the material 1.6.7 ripple not implementing foundation
// 1.9.0's `IndicationNodeFactory` - a signal that material needed to move forward too, not that
// ui needed to move back.
//
// Superseded hardening ticket 06's second item. See that ticket for the full account.

// Roborazzi (hardening ticket 01) - baselines are committed, one flat directory per the ticket's
// own instruction ("baselines committed under app/src/test/snapshots/"). Never run the record task
// from CI or a git hook (ticket item 5, and app/src/test/snapshots/README.md's own instruction) -
// recording is an on-demand, human-reviewed action, same posture this repo already holds for the
// LLM evals.
roborazzi {
    outputDir.set(file("src/test/snapshots"))
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

    // supabase-kt (backend-erp Phase 1, .scratch/backend-erp/issues/05-migration-path.md).
    // BOM first so auth-kt/postgrest-kt below resolve to ONE release with no version.ref of
    // their own - see gradle/libs.versions.toml's supabaseKt comment for why 3.7.0 and for the
    // group-vs-package-name gotcha.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.kotlinx.serialization.json)
    // Ktor needs an explicit engine on Android rather than relying on ServiceLoader
    // auto-detection (undocumented/unreliable under R8 - the debug build here never runs R8, but
    // an explicit engine costs nothing and removes the question). See
    // backend/SupabaseClientProvider.kt for where this is wired in.
    implementation(libs.ktor.client.okhttp)

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
    // Compose UI testing (hardening ticket 01's screenshot tests, and any future Compose
    // behaviour test) - createComposeRule()/createAndroidComposeRule<T>() plus the merged-manifest
    // ComponentActivity host createComposeRule() launches by default under Robolectric.
    testImplementation(libs.ui.test.junit4)
    // debugImplementation, NOT testImplementation: Robolectric's `testDebugUnitTest` merges the
    // DEBUG variant's own manifest (main + debugImplementation-scoped library manifests) into the
    // manifest it resolves activities against - a testImplementation-scoped manifest is never
    // folded in, which is why `createComposeRule()`'s internal `ComponentActivity` host 404'd with
    // "Unable to resolve activity" until this moved here. `ui-test-manifest` contributes exactly
    // one `<activity android:name="androidx.activity.ComponentActivity">` with a LAUNCHER
    // intent-filter, test-only in spirit even though it rides the debug manifest; ProGuard/R8 never
    // sees it since debug is never minified (see `buildTypes.release` above - only release runs R8,
    // and this app has no release-time uses of it either).
    debugImplementation(libs.ui.test.manifest)
    // Roborazzi (hardening ticket 01): Robolectric-native screenshot capture -
    // ComposeTestRule.onRoot().captureRoboImage(...). roborazzi-compose is the Compose-specific
    // capture extension; plain `roborazzi` alone only covers android.view.View.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // ArchTaskExecutor is already on the test RUNTIME classpath transitively, via
    // androidx.room:room-runtime -> room-runtime-android -> androidx.arch.core:core-runtime (Room's
    // default query/transaction executor when none is set on the builder). It is NOT on the test
    // COMPILE classpath at that transitive edge (Gradle module metadata only exposes it at
    // runtime scope there), so RoomTestReset.drainArchDiskIoPool cannot reference the type without
    // this. Pinned to the exact version already resolved transitively (confirmed via
    // `./gradlew app:dependencies --configuration debugUnitTestRuntimeClasspath`), so this changes
    // nothing about the resolved graph - it only makes an artifact already on the classpath visible
    // to the compiler. This is NOT androidx.arch.core:core-testing (InstantTaskExecutorRule and
    // friends), which stays absent - see RoomTestReset's class doc comment,
    // .scratch/hardening/issues/13-the-suite-is-green-by-luck.md.
    testImplementation("androidx.arch.core:core-runtime:2.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.room.testing)
}
