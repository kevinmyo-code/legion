package com.kevin.legion.dumb

import java.net.URLDecoder

/**
 * Phone-number comparison for dumb mode's people list and call screening (ADR 0062). A number is
 * reduced to its digits and compared by its tail, because the same line arrives as
 * "+1 (555) 123-4567" in a contact, "5551234567" from a notification and "+15551234567" from
 * Telecom. The platform's own matcher (`PhoneNumberUtils.compare`) is not used: it needs a real
 * Android runtime, and this file must be testable as plain Kotlin.
 *
 * Deliberately loose at the tail, strict at the length: fewer than [MIN_DIGITS] digits never
 * matches anything (a short code is not a person), and two long numbers must agree on their last
 * [TAIL_DIGITS] digits. The cost is that a 7-digit local number can match the same 7 digits in
 * another area code; for a household's few chosen contacts that is the right side to err on.
 */
object PhoneNumbers {
    const val MIN_DIGITS = 7
    const val TAIL_DIGITS = 10

    /** The digits of [raw] and nothing else; "" for null or a number with no digits. */
    fun normalize(raw: String?): String = raw.orEmpty().filter { it.isDigit() }

    /** True when [a] and [b] are plausibly the same line. */
    fun same(a: String?, b: String?): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        val shorter = minOf(x.length, y.length)
        val tail = minOf(shorter, TAIL_DIGITS)
        return shorter >= MIN_DIGITS && x.takeLast(tail) == y.takeLast(tail)
    }

    /** The number inside a `tel:` URI (percent-encoding undone), or null for any other scheme. */
    fun fromTelUri(uri: String?): String? {
        val text = uri?.trim().orEmpty()
        if (!text.startsWith("tel:", ignoreCase = true)) return null
        val body = text.substring("tel:".length).substringBefore(';').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(body, "UTF-8") }.getOrDefault(body)
        return normalize(decoded).ifEmpty { null }
    }
}
