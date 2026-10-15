package com.kevin.midnightai.diag

/**
 * Scrubs obvious secret shapes out of a log line BEFORE it is written to disk, and again before
 * it is uploaded (a batch written by an older redactor is still cleaned on the way out).
 *
 * The drive log leaves the head unit for the household's own engine (2026-10-15), and the one
 * thing it must never carry is a credential. This is a pattern net, not a proof: it catches
 * the shapes this app actually emits (the engine's `Authorization: Token <key>`, a Gemini
 * `?key=AIza...`, a Spotify client id, a JWT, a long hex or base64 run) and replaces them with a
 * fixed marker. It cannot know an arbitrary secret, so [addLiteral] also lets the app register
 * exact values (the engine token, the Gemini key) that are removed wherever they appear.
 *
 * Voice transcripts are NOT scrubbed: they are acceptable in the household's own server (Kevin,
 * 2026-10-15). This file adds no logging of them either.
 *
 * Pure Kotlin, no Android types, so the unit test needs no Robolectric.
 */
object LogRedactor {
    const val MARK = "[redacted]"

    private val literals = java.util.concurrent.CopyOnWriteArraySet<String>()

    /** Register an exact secret value to strip everywhere. Ignored when shorter than 8 characters
     * (a short literal would shred ordinary words). */
    fun addLiteral(secret: String?) {
        if (secret != null && secret.length >= MIN_LITERAL) literals.add(secret)
    }

    /** Test seam: forget registered literals. */
    internal fun clearLiterals() = literals.clear()

    private const val MIN_LITERAL = 8

    // Authorization: Token abc / Authorization=Bearer abc / "authorization":"Basic abc".
    private val authHeader = Regex(
        """(?i)(authorization["']?\s*[:=]\s*["']?)(?:(?:token|bearer|basic)\s+)?[^\s"',;]+""",
    )

    // A scheme word followed by a long opaque value. 16+ so "Token refreshed" survives.
    private val schemeToken = Regex("""(?i)\b(bearer|token|basic)(\s+)[A-Za-z0-9._~+/=-]{16,}""")

    // key=..., access_token=..., client_id=... in a URL or form body.
    private val keyParam = Regex(
        """(?i)\b(api[_-]?key|key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|client[_-]?secret|client[_-]?id|secret|password|passwd|pwd|auth)=([^&\s"']+)""",
    )

    // JSON: "client_id":"abc", "token": "abc".
    private val jsonField = Regex(
        """(?i)("(?:api[_-]?key|key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|client[_-]?secret|client[_-]?id|secret|password)"\s*:\s*")[^"]*(")""",
    )

    private val googleKey = Regex("""AIza[0-9A-Za-z_-]{20,}""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*""")
    private val longHex = Regex("""\b[0-9a-fA-F]{32,}\b""")

    // 40+ chars of base64url alphabet that mixes letters and digits. No '/' so a file path or a
    // package name is not eaten; the lookaheads keep a long plain identifier out.
    private val longOpaque = Regex(
        """(?<![A-Za-z0-9_+-])(?=[A-Za-z0-9_+-]*\d)(?=[A-Za-z0-9_+-]*[A-Za-z])[A-Za-z0-9_+-]{40,}={0,2}""",
    )

    fun redact(line: String): String {
        var out = line
        for (literal in literals) if (out.contains(literal)) out = out.replace(literal, MARK)
        out = authHeader.replace(out) { "${it.groupValues[1]}$MARK" }
        out = schemeToken.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$MARK" }
        out = keyParam.replace(out) { "${it.groupValues[1]}=$MARK" }
        out = jsonField.replace(out) { "${it.groupValues[1]}$MARK${it.groupValues[2]}" }
        out = googleKey.replace(out, MARK)
        out = jwt.replace(out, MARK)
        out = longHex.replace(out, MARK)
        out = longOpaque.replace(out, MARK)
        return out
    }
}
