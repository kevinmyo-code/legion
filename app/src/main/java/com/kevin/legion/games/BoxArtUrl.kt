package com.kevin.legion.games

/**
 * Pure: where a game's cover lives in libretro-thumbnails (ADR 0058's scoped exception to "assets
 * are bundled"). The file name is the ROM's name without extension, because libretro names covers
 * after the No-Intro / Redump name.
 */
object BoxArtUrl {
    private const val BASE = "https://raw.githubusercontent.com/libretro-thumbnails"

    private const val BYTE_MASK = 0xFF
    private val UNRESERVED = (('A'..'Z') + ('a'..'z') + ('0'..'9') + "-._~".toList()).toSet()

    /** libretro replaces these characters in a name with `_` when it names the thumbnail file. */
    private const val LIBRETRO_FORBIDDEN = "&*/:`<>?\\|\""

    fun forGame(game: Game): String =
        "$BASE/${game.system.thumbnailRepo}/master/Named_Boxarts/${encode(libretroName(game.title))}.png"

    fun libretroName(title: String): String =
        title.map { if (it in LIBRETRO_FORBIDDEN) '_' else it }.joinToString("")

    /** Percent-encodes everything outside RFC 3986's unreserved set, as UTF-8; a space is `%20`, never `+`. */
    fun encode(name: String): String = buildString {
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and BYTE_MASK).toChar()
            if (ch in UNRESERVED) append(ch) else append("%%%02X".format(ch.code))
        }
    }
}
