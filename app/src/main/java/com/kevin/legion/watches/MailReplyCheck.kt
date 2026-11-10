package com.kevin.legion.watches

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `mail_reply` watch, evaluated ON THE PHONE (ADR 0083; Kevin 2026-11-10: "notify me whenever a
 * company i applied to emails me back"). Mail never reaches the engine (CLAUDE.md section 7: third-
 * party content is read-through only), so the engine stores only his own list of companies and
 * never sees a message or holds a firing for one.
 *
 * - **Checks headers only**, in code: Gmail `users.messages.list` for mail newer than the last check
 *   (promotions excluded), then each hit's `From` and `Subject` from `format=metadata`. A company
 *   from his list matches the sender's domain, the `From` display name, or the subject (applicant
 *   tracking systems carry the company there, not in the domain). **No model reads mail to decide.**
 * - **Says only what is his**: the company name taken FROM HIS LIST and how long ago, never a
 *   subject, snippet or address ([MailReplyWording.facts]). The raise is marked
 *   `carriesReadThroughContent`, so a reply to it is not kept in episodic memory, and its reason is
 *   generic (the watch, never the company or the mail).
 * - **Stores nothing from mail**: dedupe is a last-checked time ([MailReplyCursor]), never a
 *   message id or hash. Nothing goes to the engine.
 * - **Unreadable is not empty**: no Gmail grant is [MailCheck.CannotSee], said on the Watches
 *   screen and when the watch is set ("cannot see mail"), never rendered as "no replies".
 *
 * Runs on [WatchFiringPoller]'s tick (itself the Claude result loop: no alarm, no WorkManager) at
 * its own [intervalMs] sub-cadence.
 */
class MailReplyCheck(
    /** The member's live mail_reply watches' company lists, or null when the engine is unreadable. */
    private val companyLists: suspend () -> List<List<String>>?,
    private val search: suspend (query: String) -> MailCheck,
    private val cursor: MailReplyCursor,
    private val announce: suspend (facts: String) -> WatchDelivery,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val lock = Mutex()
    private var lastRunMs: Long? = null

    /** Runs when [intervalMs] has passed. Returns the company names it said (empty when none). */
    suspend fun maybeRun(): List<String> = lock.withLock {
        val now = clockMs()
        val last = lastRunMs
        if (last != null && now - last < intervalMs) return@withLock emptyList()
        lastRunMs = now
        run(now)
    }

    @Suppress("ReturnCount") // guard clauses: no watch, first run, cannot see mail, nothing new, not now
    internal suspend fun run(now: Long): List<String> {
        val lists = companyLists() ?: return emptyList()
        val companies = lists.flatten().distinctBy { it.lowercase() }
        if (companies.isEmpty()) {
            cursor.set(null)
            return emptyList()
        }
        val since = cursor.get()
        if (since == null) {
            // First check after a watch exists: start from now. Mail from before is never news.
            cursor.set(now)
            return emptyList()
        }
        val found = when (val result = search(query(since))) {
            is MailCheck.Found -> result.messages
            is MailCheck.CannotSee -> {
                cursor.cannotSee(result.sentence)
                return emptyList()
            }
            is MailCheck.Failed -> return emptyList()
        }
        cursor.cannotSee(null)
        val hits = found.filter { it.timestampMs >= since }
            .mapNotNull { m -> MailReplyMatch.match(companies, m.from, m.subject)?.let { it to m.timestampMs } }
        if (hits.isEmpty()) {
            cursor.set(now)
            return emptyList()
        }
        val fromThem = hits.filter { it.first.bySender }.map { it.first.company }.distinct()
        val mentioned = hits.filter { !it.first.bySender }.map { it.first.company }.distinct() - fromThem.toSet()
        val names = fromThem + mentioned
        val latest = hits.maxOf { it.second }
        val delivery = announce(MailReplyWording.facts(fromThem, mentioned, now - latest))
        if (delivery == WatchDelivery.RETRY) return emptyList()
        cursor.set(now)
        return if (delivery == WatchDelivery.SILENCED) emptyList() else names
    }

    companion object {
        /** About every 15 minutes (Kevin's spec). */
        const val INTERVAL_MS = 15L * 60 * 1000
        private const val MS_PER_SECOND = 1000

        /** Gmail's `after:` takes epoch seconds. Promotions excluded; mail he sent excluded. */
        internal fun query(sinceMs: Long): String =
            "after:${sinceMs / MS_PER_SECOND} -category:promotions -in:sent -in:chats"

        /** The companies out of a watch's params (`{"companies": [...]}`). */
        fun companiesOf(watch: Watch): List<String> =
            (watch.params["companies"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    }
}

/** One mail header pair, read for matching and dropped: never stored, never sent anywhere. */
data class MailHeaders(val from: String, val subject: String, val timestampMs: Long)

/** What a mail check could see. [CannotSee] (no grant) and [Found] with nothing are different. */
sealed interface MailCheck {
    data class Found(val messages: List<MailHeaders>) : MailCheck
    data class CannotSee(val sentence: String) : MailCheck
    data object Failed : MailCheck
}

/** The last-checked time and whether mail was readable at the last try. Holds no mail. */
interface MailReplyCursor {
    fun get(): Long?
    fun set(ms: Long?)
    fun cannotSee(sentence: String?)
    fun cannotSeeSentence(): String?
}

/** Matching in code, on headers only. */
object MailReplyMatch {
    private const val MIN_SQUASHED = 4

    /** A company from his list, and whether the SENDER matched (else only the subject named it). */
    data class Match(val company: String, val bySender: Boolean)

    /** The company from [companies] (his own words) this message is from, or null. */
    fun company(companies: List<String>, from: String, subject: String): String? =
        match(companies, from, subject)?.company

    /**
     * Sender first (domain or display name), then subject. A subject-only match is said as "mail
     * mentioning X", never "X emailed you": a friend's mail about Acme is not a reply from Acme.
     */
    fun match(companies: List<String>, from: String, subject: String): Match? {
        val bySender = companies.firstOrNull { matches(it, from, "") }
        if (bySender != null) return Match(bySender, true)
        val bySubject = companies.firstOrNull { matches(it, "", subject) }
        return bySubject?.let { Match(it, false) }
    }

    private fun matches(raw: String, from: String, subject: String): Boolean {
        val address = Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from.trim()
        val domain = address.substringAfter('@', "").lowercase().trim().trimEnd('>')
        val display = from.substringBefore('<').replace("\"", "").lowercase()
        val subj = subject.lowercase()
        val name = raw.trim().lowercase()
        return when {
            name.isEmpty() -> false
            '.' in name && ' ' !in name -> domain == name || domain.endsWith(".$name")
            else -> words(display, name) || words(subj, name) || squashedIn(domain, name)
        }
    }

    /** [name] appears in [text] as whole words, so "Acme" never matches "Acmeville". */
    private fun words(text: String, name: String): Boolean =
        Regex("(^|[^a-z0-9])${Regex.escape(name)}($|[^a-z0-9])").containsMatchIn(text)

    /** "Example Corp" in `examplecorp.example` or `jobs.examplecorp.example`. */
    private fun squashedIn(domain: String, name: String): Boolean {
        val squashed = name.filter(Char::isLetterOrDigit)
        if (squashed.length < MIN_SQUASHED || domain.isEmpty()) return false
        return domain.split('.').any { it == squashed }
    }
}

/** The only words a mail_reply raise may say: names from his list and how long ago. */
object MailReplyWording {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MINUTES = 60L

    fun facts(names: List<String>, agoMs: Long): String = facts(names, emptyList(), agoMs)

    /** [from]: the sender matched. [mentioned]: only the subject named them. Newest at [agoMs]. */
    fun facts(from: List<String>, mentioned: List<String>, agoMs: Long): String {
        val parts = buildList {
            if (from.isNotEmpty()) add("${join(from)} emailed you")
            if (mentioned.isNotEmpty()) add("mail mentioning ${join(mentioned)} in its subject came in")
        }
        val whenText = if (from.size + mentioned.size == 1) " ${ago(agoMs)}." else ", the latest ${ago(agoMs)}."
        return parts.joinToString(", and ").replaceFirstChar(Char::uppercase) + whenText
    }

    private fun join(names: List<String>): String = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
    }

    internal fun ago(ms: Long): String {
        val minutes = (ms / MINUTE_MS).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "a minute ago"
            minutes < HOUR_MINUTES -> "$minutes minutes ago"
            minutes < 2 * HOUR_MINUTES -> "about an hour ago"
            else -> "about ${minutes / HOUR_MINUTES} hours ago"
        }
    }

    /** What "why did you say that?" reads: the watch, never the company or the mail. */
    const val REASON = "Your watch for replies from companies you applied to found new mail from one of them."

    const val CANNOT_SEE =
        "This phone cannot see your mail (Gmail is not allowed in Setup), so this watch cannot fire yet. " +
            "That is not the same as no replies."

    fun prompt(facts: String): String =
        "The user asked to be told when a company they applied to emails them back. The facts, and the only " +
            "things you may say: $facts Tell the user in one short sentence. You know nothing else about the " +
            "mail: do not guess what it says. If they ask, offer to read it with the mail tools."
}
