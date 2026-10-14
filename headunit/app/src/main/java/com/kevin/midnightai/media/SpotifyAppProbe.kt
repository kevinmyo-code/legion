package com.kevin.midnightai.media

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * What Spotify app this unit actually has, as App Remote sees it. App Remote 0.8.0 (decompiled
 * `ReleaseSpotifyLocator`) binds ONLY to `com.spotify.music` / `.canary` / `.partners`, and only
 * when that package's signing certificate matches Spotify's own: a repackaged or modded APK (a
 * common preinstall on cheap head units) or Spotify Lite (`com.spotify.lite`) fails with
 * `CouldNotFindSpotifyApp` even though "Spotify" is right there on the home screen.
 */
object SpotifyAppProbe {

    /** Packages checked, App Remote-capable ones first. */
    val CANDIDATE_PACKAGES = listOf(
        "com.spotify.music",
        "com.spotify.music.partners",
        "com.spotify.music.canary",
        "com.spotify.lite",
    )

    /** App Remote's accepted SHA-256 signing-cert fingerprints per package (from the 0.8.0 aar). */
    private val KNOWN_FINGERPRINTS = mapOf(
        "com.spotify.music" to setOf(
            "644D12F22FE5AD82A2BF0BEC66E99E699EB0A41608919507C784F648009B9290",
            "6505B181933344F93893D586E399B94616183F04349CB572A9E81A3335E28FFD",
            "3F6A8113086F25779B7316CAEC8B09C89A7EFA56446EC1B8877D3C1CC98FF518",
        ),
        "com.spotify.music.partners" to setOf(
            "7297CBC5200825B6F0EB54645EEB07248CE739896CD719368B4CB43E993429AD",
        ),
        "com.spotify.music.canary" to setOf(
            "F6D3ED98DF54AFE03F57E1D0137A4C8FD140C9634D1BA0C642610BA6AFC9CF9D",
            "7669D9835F4DE4CB96E21068F09F49ED74C4CB1C81A320FE984420217C5C0BDE",
        ),
    )

    /** One installed Spotify package. [signatureOk] null = App Remote does not accept this package at all. */
    data class Found(val pkg: String, val version: String?, val signatureOk: Boolean?)

    /** Pure: is [sha256Hex] (upper-case, no colons) an App Remote-accepted signature for [pkg]? Null when App Remote never binds [pkg]. */
    fun signatureAccepted(pkg: String, sha256Hex: String): Boolean? =
        KNOWN_FINGERPRINTS[pkg]?.contains(sha256Hex.uppercase())

    /**
     * Pure: one Settings line for what was found. App Remote-usable first; otherwise the most
     * specific reason it cannot bind.
     */
    fun describe(found: List<Found>): String {
        if (found.isEmpty()) return "Spotify app: not installed on this unit"
        val usable = found.firstOrNull { it.signatureOk == true }
        if (usable != null) return "Spotify app: ${usable.pkg} ${usable.version.orEmpty()}".trimEnd()
        val modded = found.firstOrNull { it.signatureOk == false }
        if (modded != null) {
            return "Spotify app: ${modded.pkg} ${modded.version.orEmpty()} is not Spotify-signed " +
                "(modded/repackaged) - App Remote refuses it; install Spotify from Play"
        }
        val other = found.first()
        return "Spotify app: only ${other.pkg} ${other.version.orEmpty()} - App Remote needs the full app (com.spotify.music)"
    }

    /** Reads the installed Spotify packages. Never throws. */
    fun probe(context: Context): List<Found> = CANDIDATE_PACKAGES.mapNotNull { pkg ->
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sig = info.signatures?.singleOrNull()
            val sha = sig?.let { s ->
                MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }
            }
            Found(
                pkg = pkg,
                version = info.versionName,
                signatureOk = if (KNOWN_FINGERPRINTS.containsKey(pkg)) sha?.let { signatureAccepted(pkg, it) } ?: false else null,
            )
        }.getOrNull()
    }
}
