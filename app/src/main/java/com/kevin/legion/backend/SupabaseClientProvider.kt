package com.kevin.legion.backend

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Builds and caches the [SupabaseClient] for the household's BYO project
 * (`.scratch/backend-erp/issues/05-migration-path.md`, Phase 1). One client per configured
 * project - most callers should just call [get] and word the null case themselves rather than
 * throwing, matching [com.kevin.legion.gmail.GmailAuth]/[com.kevin.legion.sync.DriveAuth]'s
 * degrade-in-words posture for a not-yet-configured BYO credential.
 *
 * **OkHttp, not the generic docs recommendation of ktor-client-cio, deliberately** (per the
 * brief): [com.kevin.legion.sync.DriveClient] and [com.kevin.legion.service.GeminiLiveSession]
 * already carry OkHttp as a first-class dependency, so this keeps the app to ONE HTTP stack on
 * Android instead of two. The engine is passed explicitly (`httpEngine = OkHttp.create()`) rather
 * than left to Ktor's `ServiceLoader` auto-detection - the debug build never runs R8/minification
 * (see `app/build.gradle.kts`'s `buildTypes.release` note that only release does), so
 * auto-detection would likely work here too, but an explicit engine removes the question rather
 * than banking on it.
 *
 * **The resolved Ktor version, traced rather than guessed:** `./gradlew app:dependencies
 * --configuration debugRuntimeClasspath | grep ktor` shows supabase-kt's OWN internal dependency
 * graph (ktor-client-content-negotiation, ktor-serialization-kotlinx-json, ktor-client-core, and
 * everything auth-kt/postgrest-kt pull in on their own) resolving to **3.5.1** for the
 * highest-traffic modules. `gradle/libs.versions.toml`'s `ktor` version is pinned to that number
 * so the one dependency we declare ourselves (`ktor-client-okhttp`) lands on it too, rather than
 * the version left over after Gradle's conflict resolution silently bumped only the pieces that
 * collided.
 *
 * **`gradle/libs.versions.toml`'s `supabaseKt` is pinned to 3.6.0, not the actual latest release
 * (3.7.0) - read that version's own comment before touching it.** 3.7.0's published artifacts
 * carry Kotlin metadata this project's Kotlin 2.1.0 compiler cannot read
 * (`kaptGenerateStubsDebugKotlin` fails outright); 3.6.0 was verified to compile clean. This is a
 * real, build-breaking incompatibility discovered by actually compiling against 3.7.0 in this
 * session, not a preference.
 */
object SupabaseClientProvider {

    private data class CacheKey(val url: String, val anonKey: String)

    @Volatile private var cachedKey: CacheKey? = null
    @Volatile private var cachedClient: SupabaseClient? = null
    @Volatile private var cachedSession: SupabaseSession? = null

    /**
     * Returns the cached client if [SupabaseConfig] is unchanged since the last call, builds a
     * fresh one if the config just changed (e.g. the driver pasted a new URL/key), or null if
     * nothing is configured yet. Never throws - a malformed config is filtered out by
     * [SupabaseConfig.isConfigured] before any client construction is attempted.
     */
    @Synchronized
    fun get(context: Context): SupabaseClient? {
        val app = context.applicationContext
        if (!SupabaseConfig.isConfigured(app)) return null
        val key = CacheKey(SupabaseConfig.url(app), SupabaseConfig.anonKey(app))
        cachedClient?.let { if (cachedKey == key) return it }

        val session = SupabaseSession(app)
        val client = createSupabaseClient(supabaseUrl = key.url, supabaseKey = key.anonKey) {
            httpEngine = OkHttp.create()
            install(Auth) {
                sessionManager = session
            }
            install(Postgrest)
            // Storage (ticket 09): the receipt-photo durability half of ticket 01 ruling 10 as
            // amended. Same client, same OkHttp engine - no separate install target needed.
            install(Storage)
        }
        cachedKey = key
        cachedClient = client
        cachedSession = session
        return client
    }

    /**
     * The [SupabaseSession] backing the current cached client's Auth plugin, or null if nothing
     * is configured. [SupabaseAuth] reads [SupabaseSession.lastSaveSucceeded] off this after a
     * sign-in to report whether the session will actually survive a restart.
     */
    fun session(context: Context): SupabaseSession? {
        get(context)
        return cachedSession
    }

    /** Test/config-change seam: drops the cached client so the next [get] rebuilds from scratch. */
    @Synchronized
    fun reset() {
        cachedKey = null
        cachedClient = null
        cachedSession = null
    }
}
