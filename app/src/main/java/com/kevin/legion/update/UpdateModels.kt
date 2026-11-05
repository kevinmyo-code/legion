package com.kevin.legion.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One published phone build, as `GET /api/releases/latest?app=phone` serves it
 * (`server/releases/views.py`'s `AppReleaseSerializer`; alfred-ota tickets 04 and 05).
 */
data class ReleaseInfo(
    val id: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    val signerSha256: String,
)

/** `GET /api/releases/<id>/download`: a signed link to the APK, valid 15 minutes. */
data class DownloadLink(val url: String, val sha256: String, val sizeBytes: Long, val versionCode: Long)

/** What the installed app is, read off PackageManager. */
data class InstalledApp(val versionCode: Long, val versionName: String, val signerSha256s: Set<String>)

/** What a downloaded APK says it is, read off PackageManager without installing it. */
data class ApkFacts(val packageName: String, val versionCode: Long, val signerSha256s: Set<String>)

/** The engine's JSON, parsed strictly: a missing or mistyped field is no release at all. */
object ReleaseJson {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun parseRelease(text: String): ReleaseInfo? {
        val o = obj(text) ?: return null
        val sha = str(o, "sha256")?.takeIf { HEX64.matches(it) } ?: return null
        val signer = str(o, "signer_sha256")?.takeIf { HEX64.matches(it) } ?: return null
        return ReleaseInfo(
            id = str(o, "id") ?: return null,
            versionCode = num(o, "version_code")?.takeIf { it > 0 } ?: return null,
            versionName = str(o, "version_name") ?: return null,
            sha256 = sha,
            sizeBytes = num(o, "size_bytes")?.takeIf { it > 0 } ?: return null,
            notes = str(o, "notes").orEmpty(),
            signerSha256 = signer,
        )
    }

    fun parseDownload(text: String): DownloadLink? {
        val o = obj(text) ?: return null
        val url = str(o, "url")?.takeIf { it.startsWith("https://") } ?: return null
        return DownloadLink(
            url = url,
            sha256 = str(o, "sha256")?.takeIf { HEX64.matches(it) } ?: return null,
            sizeBytes = num(o, "size_bytes")?.takeIf { it > 0 } ?: return null,
            versionCode = num(o, "version_code")?.takeIf { it > 0 } ?: return null,
        )
    }

    private fun obj(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun num(o: JsonObject, key: String): Long? =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
