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
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
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

    // Ledger aspect (.claude/plans/wiggly-beaming-quasar.md): PDF text/coordinate
    // extraction for bank-statement parsing. Android has no built-in equivalent
    // to Python's pdfplumber word-position extraction the DBS parser depends on.
    implementation(libs.pdfbox.android)

    // Custom wake word ("hey <name>"), ported from Midnight AI.
    implementation(libs.vosk.android)

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
