package com.kevin.legion.dumb

/**
 * The facts about one posted notification that the hold decision needs, flattened by the listener.
 * [people] and [inviteFrom] are third-party/first-party text read ONLY to decide and never kept; the
 * digest records the package and a count, nothing else (CLAUDE.md section 7, ADR 0062).
 */
data class HeldFacts(
    val key: String,
    val packageName: String,
    val postTimeMs: Long,
    val channelId: String? = null,
    val category: String? = null,
    val ongoing: Boolean = false,
    val foregroundService: Boolean = false,
    val noClear: Boolean = false,
    val groupSummary: Boolean = false,
    val people: NotificationPeople = NotificationPeople(),
    /** LEGION's own Tetris invite carries the sender's household name here. */
    val inviteFrom: String? = null,
)

/** What to do with a notification. */
sealed interface HoldDecision {
    /** Dumb mode is off: not our business. */
    data object Ignore : HoldDecision

    /** Ongoing, foreground, call, navigation, media, alarm, group summary or LEGION's own. */
    data object LeaveAlone : HoldDecision

    /** From a chosen person: stays, and is re-alerted over Do Not Disturb. */
    data class LetThrough(val person: ChosenPerson) : HoldDecision

    /** Cancelled silently and counted for the digest. */
    data object Hold : HoldDecision
}

/**
 * The hold/let-through decision table (ADR 0062), pure. In order:
 * 1. mode off: [HoldDecision.Ignore];
 * 2. ongoing, foreground-service, non-clearable, group summary, or a call/navigation/media/alarm
 *    category: [HoldDecision.LeaveAlone] (a summary is left so its children are cancelled one by one);
 * 3. LEGION's own: the Tetris invite is let through only when its sender is a chosen person (else
 *    held); every other LEGION notification is left alone, because LEGION is the home app and these
 *    are the user's own reminders;
 * 4. a chosen person: [HoldDecision.LetThrough];
 * 5. everything else: [HoldDecision.Hold].
 */
class NotificationHold(
    private val isOn: () -> Boolean,
    private val ownPackage: String,
    private val inviteChannelId: String,
    private val people: () -> List<ChosenPerson>,
) {
    fun decide(facts: HeldFacts): HoldDecision {
        val chosen = people()
        return when {
            !isOn() -> HoldDecision.Ignore
            isSystemLike(facts) -> HoldDecision.LeaveAlone
            facts.packageName == ownPackage -> decideOwn(facts, chosen)
            else -> PersonMatcher(chosen).match(facts.people)?.let { HoldDecision.LetThrough(it) } ?: HoldDecision.Hold
        }
    }

    private fun decideOwn(facts: HeldFacts, chosen: List<ChosenPerson>): HoldDecision {
        if (facts.channelId != inviteChannelId) return HoldDecision.LeaveAlone
        val from = facts.inviteFrom
        val person = from?.let { name -> chosen.firstOrNull { PersonMatcher.namesOverlap(name, it.displayName) } }
        return if (person != null) HoldDecision.LetThrough(person) else HoldDecision.Hold
    }

    private fun isSystemLike(f: HeldFacts): Boolean =
        f.ongoing || f.foregroundService || f.noClear || f.groupSummary || f.category in LEFT_ALONE_CATEGORIES ||
            ALWAYS_LEFT_ALONE_PACKAGE_MARKERS.any { f.packageName.contains(it) }

    companion object {
        /** `Notification.CATEGORY_*` values, spelled out so this file stays plain Kotlin. */
        val LEFT_ALONE_CATEGORIES = setOf("call", "navigation", "transport", "alarm")

        /**
         * Package-name fragments of the system's emergency-alert receivers (cell broadcast: weather
         * warnings, AMBER alerts). Holding one would be a safety failure, and a dumb phone is still a
         * phone. A guess at vendor naming, so it errs toward letting a notification stay.
         */
        val ALWAYS_LEFT_ALONE_PACKAGE_MARKERS = listOf("cellbroadcast", "emergency")
    }
}

/** Whether this notification key and post time were already counted, so a re-post is counted once. */
class SeenKeys(private val capacity: Int = DEFAULT_CAPACITY) {
    private val seen = LinkedHashSet<String>()

    /** Returns true the first time a key+time pair is offered. */
    @Synchronized
    fun firstTime(key: String, postTimeMs: Long): Boolean {
        val id = "$key@$postTimeMs"
        if (!seen.add(id)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }

    private companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
