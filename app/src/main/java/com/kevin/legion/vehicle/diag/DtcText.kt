package com.kevin.legion.vehicle.diag

import com.kevin.legion.vehicle.DtcMeanings

/**
 * What a scanned code is called, and where that came from. Generic (SAE) codes get a title from a
 * bundled public table ([TABLE_SOURCE]) or LEGION's own generic-code notes; a generic code neither
 * covers says so. A manufacturer-specific code is never described here: its meaning is the
 * carmaker's, and any explanation the assistant offers for it is an estimate, labelled as one.
 */
object DtcText {
    const val TABLE_SOURCE = "SAE generic code table (github.com/mytrile/obd-trouble-codes, MIT)"
    const val SEED_SOURCE = "LEGION's generic-code notes"
    const val MANUFACTURER = "manufacturer-specific, no description on file"
    const val GENERIC_UNKNOWN = "generic code, no description on file"

    data class Meaning(val code: String, val title: String?, val source: String) {
        val words: String get() = title ?: source
    }

    /** Parses the bundled CSV: `CODE,Title` lines, `#` comments, quoted titles allowed. */
    fun parseTable(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val comma = line.indexOf(',')
            if (comma <= 0) null else line.substring(0, comma).trim() to line.substring(comma + 1).trim().trim('"')
        }
        .toMap()

    fun describe(code: String, table: Map<String, String>, seed: Map<String, Pair<String, String>>): Meaning {
        val c = code.trim().uppercase()
        return when {
            DtcMeanings.isManufacturerSpecific(c) -> Meaning(c, null, MANUFACTURER)
            table[c] != null -> Meaning(c, table[c], TABLE_SOURCE)
            seed[c] != null -> Meaning(c, seed[c]?.first, SEED_SOURCE)
            else -> Meaning(c, null, GENERIC_UNKNOWN)
        }
    }
}
