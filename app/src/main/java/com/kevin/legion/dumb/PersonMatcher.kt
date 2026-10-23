package com.kevin.legion.dumb

/**
 * What a notification says about WHO it is from, reduced to plain strings by the listener so the
 * matcher is testable without Android. This is third-party content (CLAUDE.md section 7): it is read
 * to make one decision and then dropped. Nothing here is ever stored.
 */
data class NotificationPeople(
    /** `Person.uri` / `EXTRA_PEOPLE_LIST` entries: `tel:`, `mailto:` or a contacts lookup URI. */
    val uris: List<String> = emptyList(),
    /** Sender names from the notification's people and messaging sender persons. */
    val names: List<String> = emptyList(),
    /** The conversation title, or the notification title when there is no conversation. */
    val title: String? = null,
    /** A group chat's title is the group's name, never a person's, so it is not matched against names. */
    val isGroup: Boolean = false,
)

/**
 * Decides whether a notification is from one of the chosen people (ADR 0062). Matches, in order:
 * a `tel:` URI against the chosen numbers; a contacts URI against the contact id; a sender name
 * (or a number used as a name, which is how an unsaved contact appears) against the chosen names
 * and numbers; and, for a one-to-one conversation only, the title.
 *
 * Name matching is EXACT after [normalizeName] (case, emoji and punctuation dropped). First-name
 * guessing was left out on purpose: a wrong match lets a stranger through, which is the failure the
 * mode exists to prevent, while a miss only holds one of Mia's messages until the digest.
 */
class PersonMatcher(private val people: List<ChosenPerson>) {

    fun match(from: NotificationPeople): ChosenPerson? =
        from.uris.firstNotNullOfOrNull(::matchUri)
            ?: from.names.firstNotNullOfOrNull(::matchName)
            ?: from.title?.takeIf { !from.isGroup }?.let(::matchName)

    private fun matchUri(uri: String): ChosenPerson? {
        val number = PhoneNumbers.fromTelUri(uri)
        val id = contactIdOf(uri)
        return when {
            number != null -> byNumber(number)
            id != null -> people.firstOrNull { it.contactId == id }
            else -> null
        }
    }

    private fun matchName(name: String): ChosenPerson? {
        val wanted = normalizeName(name)
        // An unsaved contact is shown by its number, so a "name" that is a phone number is a number.
        val looksLikeNumber = PhoneNumbers.normalize(name).length >= PhoneNumbers.MIN_DIGITS
        return when {
            wanted.isEmpty() -> null
            else -> people.firstOrNull { normalizeName(it.displayName) == wanted }
                ?: if (looksLikeNumber) byNumber(name) else null
        }
    }

    private fun byNumber(number: String): ChosenPerson? =
        people.firstOrNull { p -> p.numbers.any { PhoneNumbers.same(it, number) } }

    companion object {
        /** Lower-case letters and digits only, words separated by single spaces. */
        fun normalizeName(name: String): String = name.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")

        /**
         * The contact id inside `content://com.android.contacts/contacts/lookup/<key>/<id>` or
         * `.../contacts/<id>`; null for anything else.
         */
        fun contactIdOf(uri: String): String? {
            if (!uri.startsWith("content://com.android.contacts/contacts/")) return null
            val last = uri.substringBefore('?').trimEnd('/').substringAfterLast('/')
            return last.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        }

        /**
         * Invite names (household names such as "Mia") vs contact names ("Mia Wong"): one's words
         * contain the other's.
         */
        fun namesOverlap(a: String, b: String): Boolean {
            val x = normalizeName(a).split(' ').filter { it.isNotEmpty() }.toSet()
            val y = normalizeName(b).split(' ').filter { it.isNotEmpty() }.toSet()
            return x.isNotEmpty() && y.isNotEmpty() && (x.containsAll(y) || y.containsAll(x))
        }
    }
}
