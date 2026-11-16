package com.kevin.legion.vehicle.diag

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One extra reading a make's pack offers (bundled `assets/diag_packs.json`, chosen by the VIN's
 * make). [verifiedOn] stays EMPTY until Kevin confirms the reading on his own car; until then
 * every surface calls the value unverified. [scale] null means no public source gave an equation,
 * so the value is shown as the raw bytes, never as a guessed number.
 */
@Serializable
data class PackReading(
    val make: String,
    val key: String,
    val label: String,
    /** Request id of the module, hex, e.g. "7E0". */
    val module: String,
    /** "22" (ReadDataByIdentifier, 2-byte id) or "21" (KWP ReadDataByLocalIdentifier, 1-byte id). */
    val service: String,
    val identifier: String,
    @SerialName("byte_offset") val byteOffset: Int = 0,
    @SerialName("byte_count") val byteCount: Int = 1,
    val signed: Boolean = false,
    val scale: Double? = null,
    val offset: Double = 0.0,
    val unit: String = "",
    /** Which car the source was written for: a caveat shown with the reading. */
    val applies: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("verified_on") val verifiedOn: String = "",
) {
    val verified: Boolean get() = verifiedOn.isNotBlank()

    /** The request bytes: the service, then the identifier (1 or 2 bytes). */
    fun requestBytes(): List<Int> {
        val id = identifier.toInt(HEX)
        val svc = service.toInt(HEX)
        return if (svc == Uds.READ_DID) {
            listOf(svc, id shr BYTE, id and Hex.BYTE_MAX)
        } else {
            listOf(svc, id and Hex.BYTE_MAX)
        }
    }

    /** Bytes after the positive service byte and the identifier echo. */
    fun dataAfterEcho(payload: List<Int>): List<Int> = payload.drop(if (service.toInt(HEX) == Uds.READ_DID) 2 else 1)

    /** The shown value, or null when the reply is too short for [byteOffset] + [byteCount]. */
    fun decode(data: List<Int>): String? {
        if (data.size < byteOffset + byteCount) return null
        val bytes = data.subList(byteOffset, byteOffset + byteCount)
        val s = scale ?: return "raw ${Hex.bytes(bytes)}"
        var n = bytes.fold(0L) { acc, b -> (acc shl BYTE) or b.toLong() }
        val bits = byteCount * BYTE
        if (signed && n >= (1L shl (bits - 1))) n -= (1L shl bits)
        return String.format(java.util.Locale.US, "%.1f", n * s + offset)
    }

    companion object {
        private const val HEX = 16
        private const val BYTE = 8
    }
}

@Serializable
data class PackFile(val note: String = "", val readings: List<PackReading>)

object DiagPacks {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<PackReading> =
        runCatching { JSON.decodeFromString(PackFile.serializer(), text).readings }.getOrDefault(emptyList())

    /** The rows for [make] (case-insensitive). No make, or a make with no pack, gets nothing. */
    fun forMake(all: List<PackReading>, make: String?): List<PackReading> {
        val m = make?.trim()?.lowercase() ?: return emptyList()
        return all.filter { it.make.lowercase() == m }
    }
}
