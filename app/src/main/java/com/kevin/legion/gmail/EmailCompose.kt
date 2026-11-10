package com.kevin.legion.gmail

import okio.ByteString.Companion.encodeUtf8

/**
 * The pure half of the `email` tool (ADR 0086): the raw message Gmail's `drafts.create` takes,
 * address checks, picking ONE recipient from contacts or recent mail, and the words of every
 * result. No Android, no network, so all of it is a plain JVM unit test target, the
 * [GmailToolLogic] posture. The send gate is its own class, [EmailSendGate].
 */
// One file holds the email tool's pure rules and every result's words, so a test reads them together.
@Suppress("TooManyFunctions")
object EmailCompose {

    /** A plain address: something@domain.tld, no spaces, no angle brackets. Deliberately strict. */
    private val ADDRESS = Regex("^[A-Za-z0-9._%+'\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    fun isAddress(text: String): Boolean = ADDRESS.matches(text.trim())

    /** Header text with line breaks removed, so a spoken subject can never inject a header. */
    internal fun oneLine(text: String): String = text.replace(Regex("[\\r\\n]+"), " ").trim()

    /** RFC 2047 encoded-word for a non-ASCII subject; ASCII passes through unchanged. */
    internal fun encodeHeader(text: String): String =
        if (text.all { it.code in PRINTABLE_ASCII }) text else "=?UTF-8?B?${text.encodeUtf8().base64()}?="

    /**
     * The RFC 2822 message for `drafts.create`, as Gmail's `raw` field wants it: base64url. Gmail
     * fills `From` with the signed-in account itself. The body goes base64 under UTF-8, so any
     * character Kevin dictates survives. [to] must already be a checked address ([isAddress]).
     */
    fun rawMessage(to: String, subject: String, body: String): String {
        require(isAddress(to)) { "not an address" }
        val bodyB64 = body.replace("\r\n", "\n").replace("\n", "\r\n").encodeUtf8().base64()
            .chunked(BASE64_LINE).joinToString("\r\n")
        val mime = "To: ${to.trim()}\r\n" +
            "Subject: ${encodeHeader(oneLine(subject))}\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            bodyB64 + "\r\n"
        return mime.encodeUtf8().base64Url()
    }

    // ------------------------------------------------------------------ recipients

    /** One possible recipient, from a contact or from a `From` header in recent mail. */
    data class Candidate(val name: String, val address: String)

    sealed interface Pick {
        data class One(val candidate: Candidate) : Pick
        data class Several(val candidates: List<Candidate>) : Pick
        data object NoMatch : Pick
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Which of [candidates] the spoken [name] means. A candidate matches when every word of the
     * spoken name is a word of its display name or of its address's local part. Exactly one
     * distinct ADDRESS among the matches is a pick; two or more is [Pick.Several] (ask Kevin);
     * none is [Pick.NoMatch]. Never a nearest guess: a near miss is no match.
     */
    fun pick(name: String, candidates: List<Candidate>): Pick {
        val spoken = words(name)
        if (spoken.isEmpty()) return Pick.NoMatch
        val matches = candidates.filter { c ->
            if (!isAddress(c.address)) return@filter false
            val own = words(c.name) + words(c.address.substringBefore('@'))
            spoken.all { it in own }
        }
        val byAddress = matches.groupBy { it.address.trim().lowercase() }
        return when (byAddress.size) {
            0 -> Pick.NoMatch
            1 -> Pick.One(byAddress.values.first().first().let { it.copy(address = it.address.trim()) })
            else -> Pick.Several(byAddress.values.map { it.first() }.take(MAX_CHOICES))
        }
    }

    /** Splits a `From` header (`Jane Roe <jane@example.com>` or a bare address) into a [Candidate]. */
    fun parseFrom(header: String): Candidate? {
        val angled = Regex("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>\\s*$").find(header)
        if (angled != null) {
            val address = angled.groupValues[2].trim()
            return if (isAddress(address)) Candidate(angled.groupValues[1].trim(), address) else null
        }
        val bare = header.trim()
        return if (isAddress(bare)) Candidate("", bare) else null
    }

    const val MAX_CHOICES = 4
    private const val FIRST_PRINTABLE = 0x20
    private const val LAST_PRINTABLE = 0x7E
    private val PRINTABLE_ASCII = FIRST_PRINTABLE..LAST_PRINTABLE
    private const val BASE64_LINE = 76 // RFC 2045 line length

    // ------------------------------------------------------------------ result words

    fun drafted(to: String, subject: String): String =
        "Draft saved in Gmail Drafts, NOT sent. Tell the user it is to $to, subject \"$subject\", and give a " +
            "one-line gist. Then offer: check it in Gmail, or say send. Call action=send only after the " +
            "user says send in a later reply. Anything else leaves the draft as it is."

    fun several(name: String, choices: List<Candidate>): String =
        "Nothing was drafted: \"$name\" matches more than one address: " +
            choices.joinToString("; ") { if (it.name.isBlank()) it.address else "${it.name}, ${it.address}" } +
            ". Ask the user which one, then draft again with that address."

    fun noMatch(name: String): String =
        "Nothing was drafted: I found no address for \"$name\" in contacts or recent mail. Ask the user " +
            "for the email address. Never guess one."

    const val NEED_FIELDS =
        "Nothing was drafted: an email needs a recipient, a subject and a body. Ask the user for what is missing."

    const val NEEDS_COMPOSE_CONSENT =
        "Nothing was drafted or sent: Gmail needs the user's OK for LEGION to write drafts and send. " +
            "The Google approval screen is open on the phone; ask the user to approve it, then try again."

    const val NEEDS_COMPOSE_CONSENT_NOT_OPENED =
        "Nothing was drafted or sent: Gmail needs the user's OK for LEGION to write drafts and send, and " +
            "the approval screen could not be opened. Ask the user to open LEGION and ask again."

    const val CREATE_FAILED =
        "Nothing was drafted: Gmail did not save the draft. Say it was not drafted."

    const val CREATE_OFFLINE =
        "Nothing was drafted: I can't reach Gmail, no connection."

    fun sent(to: String): String = "Sent to $to. Gmail confirmed it. The draft has left Drafts."

    const val NOT_SENT_STILL_DRAFT =
        "NOT sent: Gmail did not send it. The draft is still in Drafts. Say exactly that."

    const val SEND_UNCONFIRMED_DRAFT_GONE =
        "Gmail did not confirm the send, but the draft has left Drafts, so it may have gone out. Do not " +
            "say sent. Say it is unconfirmed and ask the user to check Sent in Gmail."

    const val SEND_UNCONFIRMED =
        "NOT confirmed sent: Gmail never answered. As far as I know it was not sent and the draft is still " +
            "in Drafts. Do not say sent. Ask the user to check Gmail before trying again."

    fun opened(subject: String): String =
        "Gmail is open on the phone. The draft is in Drafts, subject \"$subject\". It is not sent."

    const val OPEN_FAILED =
        "Gmail could not be opened on the phone. The draft is still in Drafts, not sent."

    const val NO_DRAFT_TO_OPEN =
        "There is no draft from this conversation to open. Nothing was sent."
}
