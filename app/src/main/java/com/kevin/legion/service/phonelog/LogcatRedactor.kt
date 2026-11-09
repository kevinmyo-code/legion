package com.kevin.legion.service.phonelog

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Scrubs one logcat line before it is written to the phone's spool, and again before it is
 * uploaded (a batch written by an older redactor is cleaned on the way out too). 2026-11-09, Kevin:
 * "Send everything from the phone to the engine so we can audit without adb."
 *
 * The household's own engine is where the line goes, and the conversation log already sends what
 * Kevin said there in clear (2026-10-28). What must never leave the phone in a raw log line:
 *
 * - **Credentials.** The engine's `Authorization: Token`, `Bearer` tokens, cookies, a Gemini
 *   `AIza...` key, a Google `ya29.` access token, a JWT, `key=`/`token=`/`secret=` parameters and
 *   JSON fields, long opaque hex or base64 runs. The head unit's `diag/LogRedactor` net, widened.
 *   [addLiteral] also strips exact values the app knows (its engine token, its Gemini key).
 * - **Mail and other third-party content (CLAUDE.md section 7), matching `LiveAudit`'s rule.** A
 *   line naming a read-through tool ([READ_THROUGH_TOOLS], the set
 *   `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` holds) keeps its tool name and loses its payload
 *   (`response=`, `result=`, `body=`, `content=`) to [THIRD_PARTY]; its `args=` stay, as LiveAudit keeps
 *   them. A line from a mail class
 *   ([MAIL_TAGS]) loses any URL query (a Gmail search) and anything after `response: `. JSON fields
 *   that only mail carries (`snippet`, `subject`, `body`, `sender`, `cc`) are blanked anywhere.
 * - **Contact details, keeping the label.** Email addresses become [EMAIL] and phone numbers
 *   [PHONE]; a contact's name or label ("Mom", "work") stays, because an audit needs to see WHO
 *   a call was for, never their number.
 * - **ID-shaped numbers** (an SSN's `123-45-6789`) become [ID]: the ID vault is never logged
 *   (ADR 0076), and this is the net under that rule, not the rule.
 *
 * Patterns run on the MESSAGE only, never the threadtime header, so a pid, a tid or the timestamp
 * can never be mistaken for a phone number. Bare digit runs are left alone (epoch milliseconds and
 * row ids look exactly like phone numbers); a number is redacted when it is formatted like one, is
 * `+`-prefixed E.164, follows `tel:`, or sits after a key that names it (`phone=`, `number:`).
 *
 * A pattern net, not a proof. Pure Kotlin, no Android types: the unit test needs no Robolectric.
 */
object LogcatRedactor {
    const val MARK = "[redacted]"
    const val THIRD_PARTY = "[redacted: third-party content]"
    const val EMAIL = "[email]"
    const val PHONE = "[phone]"
    const val ID = "[id]"

    /** `LiveToolbox.EPISODIC_EXCLUDED_TOOLS`, copied so this file stays pure; a test pins them equal. */
    val READ_THROUGH_TOOLS: Set<String> = setOf(
        "search_mail", "read_mail", "ask_mail", "get_sitrep", "track_package", "flight_status", "browser",
    )

    /** Tags of classes that hold mail in memory. */
    val MAIL_TAGS: Set<String> = setOf("GmailClient")

    private const val MIN_LITERAL = 8
    private val literals = CopyOnWriteArraySet<String>()

    /** Register an exact secret to strip everywhere. Ignored under 8 characters (it would shred words). */
    fun addLiteral(secret: String?) {
        if (secret != null && secret.length >= MIN_LITERAL) literals.add(secret)
    }

    /** Test seam. */
    internal fun clearLiterals() = literals.clear()

    // "11-09 14:03:22.123  1234  1250 I Tag     : " - everything up to the message.
    private val header = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+[VDIWEFA]\s+(.*?)\s*: """)

    // ---- credentials (the head unit's set, plus cookies and ya29)
    private val authHeader = Regex(
        """(?i)(authorization["']?\s*[:=]\s*["']?)(?:(?:token|bearer|basic)\s+)?[^\s"',;]+""",
    )
    private val cookieHeader = Regex("""(?i)\b((?:set-)?cookie["']?\s*[:=]\s*["']?)[^"'\n]+""")
    private val schemeToken = Regex("""(?i)\b(bearer|token|basic)(\s+)[A-Za-z0-9._~+/=-]{16,}""")
    private val keyParam = Regex(
        """(?i)\b(api[_-]?key|key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|client[_-]?secret|""" +
            """client[_-]?id|secret|password|passwd|pwd|auth|session[_-]?id)=([^&\s"',;]+)""",
    )
    private val jsonSecret = Regex(
        """(?i)("(?:api[_-]?key|key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|client[_-]?secret|""" +
            """client[_-]?id|secret|password|authorization|cookie|session[_-]?id)"\s*:\s*")[^"]*(")""",
    )
    private val googleKey = Regex("""AIza[0-9A-Za-z_-]{20,}""")
    private val googleAccess = Regex("""\bya29\.[0-9A-Za-z_.-]{20,}""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*""")
    private val longHex = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val longOpaque = Regex(
        """(?<![A-Za-z0-9_+-])(?=[A-Za-z0-9_+-]*\d)(?=[A-Za-z0-9_+-]*[A-Za-z])[A-Za-z0-9_+-]{40,}={0,2}""",
    )

    // ---- third-party content
    private val readThroughPayload = Regex("""\b(response|result|body|content)=.*$""")
    private val mailJson = Regex(
        """(?i)("(?:snippet|subject|body|cc|bcc|reply_?to|sender|recipients?|html|plain)"\s*:\s*)""" +
            """("(?:[^"\\]|\\.)*"|\[[^\]]*])""",
    )
    private val urlQuery = Regex("""(https?://[^\s?#"']+)\?[^\s"']*""")
    private val afterResponse = Regex("""(?i)(response:\s).*$""")

    // ---- contacts and IDs
    private val email = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")
    private val tel = Regex("""(?i)\btel:[+\d().\s-]{5,}""")
    private val e164 = Regex("""(?<![\w+])\+\d{8,15}\b""")
    private val formattedPhone = Regex(
        """(?<![\w.:+-])(?:\+?\d{1,3}[\s.-])?(?:\(\d{3}\)\s?|\d{3}[\s.-])\d{3}[\s.-]\d{4}(?![\w.-])""",
    )
    private val namedNumber = Regex(
        """(?i)\b((?:phone|mobile|msisdn|tel|number|phone_?number|caller|callee)["']?\s*[:=]\s*["']?)""" +
            """\+?[\d][\d\s().-]{5,}\d""",
    )
    private val ssn = Regex("""(?<![\w-])\d{3}-\d{2}-\d{4}(?![\w-])""")

    /** One full logcat line: the header kept as it is, the message redacted. */
    fun redactLine(line: String): String {
        val m = header.find(line) ?: return redactMessage(null, line)
        val head = line.substring(0, m.range.last + 1)
        return head + redactMessage(m.groupValues[1], line.substring(m.range.last + 1))
    }

    /** The message part of a line whose tag is [tag] (null when the line had no header). */
    fun redactMessage(tag: String?, message: String): String {
        var out = message
        for (literal in literals) if (out.contains(literal)) out = out.replace(literal, MARK)
        out = credentials(out)
        out = thirdParty(tag, out)
        out = contacts(out)
        return out
    }

    private fun credentials(text: String): String {
        var out = text
        out = authHeader.replace(out) { "${it.groupValues[1]}$MARK" }
        out = cookieHeader.replace(out) { "${it.groupValues[1]}$MARK" }
        out = schemeToken.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$MARK" }
        out = keyParam.replace(out) { "${it.groupValues[1]}=$MARK" }
        out = jsonSecret.replace(out) { "${it.groupValues[1]}$MARK${it.groupValues[2]}" }
        out = googleKey.replace(out, MARK)
        out = googleAccess.replace(out, MARK)
        out = jwt.replace(out, MARK)
        out = longHex.replace(out, MARK)
        out = longOpaque.replace(out, MARK)
        return out
    }

    private fun thirdParty(tag: String?, text: String): String {
        var out = text
        if (namesReadThroughTool(out)) {
            out = readThroughPayload.replace(out) { "${it.groupValues[1]}=\"$THIRD_PARTY\"" }
        }
        if (tag != null && tag in MAIL_TAGS) {
            out = urlQuery.replace(out) { "${it.groupValues[1]}?$MARK" }
            out = afterResponse.replace(out) { "${it.groupValues[1]}$THIRD_PARTY" }
        }
        out = mailJson.replace(out) { "${it.groupValues[1]}\"$THIRD_PARTY\"" }
        return out
    }

    private fun namesReadThroughTool(text: String): Boolean =
        READ_THROUGH_TOOLS.any { tool ->
            var at = text.indexOf(tool)
            var named = false
            while (at >= 0 && !named) {
                val before = if (at == 0) ' ' else text[at - 1]
                val afterAt = at + tool.length
                val after = if (afterAt >= text.length) ' ' else text[afterAt]
                named = !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
                at = text.indexOf(tool, afterAt)
            }
            named
        }

    private fun contacts(text: String): String {
        var out = text
        out = email.replace(out, EMAIL)
        out = tel.replace(out, "tel:$PHONE")
        out = namedNumber.replace(out) { "${it.groupValues[1]}$PHONE" }
        out = e164.replace(out, PHONE)
        out = ssn.replace(out, ID)
        out = formattedPhone.replace(out, PHONE)
        return out
    }
}
