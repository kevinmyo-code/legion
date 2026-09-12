package com.kevin.legion.outstanding

/**
 * One answer to "what needs doing", across the three stores that hold it.
 *
 * **Kevin, 2026-09-12:** *"alfred will be the executive of my estate, my chief of staff. advise me
 * on my net worth, my stuff that needs doing, errands, schoolwork..."* - and "my stuff that needs
 * doing" turned out to live in three places with nothing asking all three at once:
 *
 * | Store | Holds | Resets? |
 * |---|---|---|
 * | `checklists` + `ChecklistTick` | Recurring lines, ticked per day - the bio plan, groceries | Yes, nightly |
 * | `list_items` | Reminders and one-off todos, with dates and place triggers | No |
 * | `events` where `kind = TASK` | Deadlines with a moment - coursework, anything imported | No |
 *
 * `HomeDigestBuilder` already answers a DIFFERENT question - how is each aspect doing - by reading
 * the aspect digests. This answers what is outstanding right now, which is what a chief-of-staff
 * reply to "what should I be doing" has to be built from rather than each advisor re-deriving it.
 *
 * **The ranking rule, and it is the whole design:** ordered by when a thing stops being possible,
 * never by which table it came from. A deadline tonight outranks an errand with no date, which
 * outranks a recurring line already ticked today.
 *
 * This file is deliberately pure - it takes rows that someone else fetched. `OutstandingController`
 * does the fetching. That split is what lets the ranking be tested without Room, and the ranking is
 * the part worth testing.
 */

/** Where a thing came from, kept only because it changes what the user can DO about it. A checklist
 * line comes back tomorrow; a missed deadline does not. Surfacing the distinction is the point -
 * flattening all three into "todo" would lose the one fact that matters when deciding what to drop. */
enum class OutstandingKind {
    /** An `EventKind.TASK` row: a deadline with a moment. Gone when it passes. */
    DEADLINE,

    /** A `list_items` row: a reminder or one-off todo. Waits indefinitely. */
    REMINDER,

    /** A `checklists` line for today. Resets tonight whether or not it was ticked. */
    CHECKLIST_LINE,
}

data class OutstandingItem(
    /** Store-prefixed so ids from three tables can never collide. */
    val id: String,
    val title: String,
    val kind: OutstandingKind,
    /** When it stops being possible, or null for a thing with no moment. */
    val dueAtMs: Long?,
    /** Past its moment and still not done. Only a [DEADLINE] or a dated [REMINDER] can be. */
    val overdue: Boolean,
    /** Already ticked for today. A [CHECKLIST_LINE] can be done and still be listed - it is what
     * makes a finished day read as finished rather than as empty. */
    val done: Boolean,
    /** The checklist, list or course this belongs to, for grouping. Null when it has none. */
    val source: String?,
)

/**
 * Rank buckets, in the order the ticket names.
 *
 * Deliberately coarse: within a bucket, time decides. A finer ranking would be a judgement about
 * what matters more, and that is the advisor's job with a playbook behind it, not a sort function's.
 */
internal enum class Bucket {
    /** Past its date and undone. The only bucket that is about failure rather than schedule. */
    OVERDUE,

    /** Has a moment still ahead. Sooner first. */
    DATED,

    /** No moment at all - an errand that waits. */
    UNDATED,

    /** Already handled today. Listed last so a finished day reads as finished. */
    DONE,
}

internal fun bucketOf(item: OutstandingItem): Bucket = when {
    item.done -> Bucket.DONE
    item.overdue -> Bucket.OVERDUE
    item.dueAtMs != null -> Bucket.DATED
    else -> Bucket.UNDATED
}

/**
 * The one ordering.
 *
 * Within [Bucket.OVERDUE] the MOST overdue comes first - the thing that has been ignored longest is
 * the thing most likely to have been forgotten rather than deferred. Within [Bucket.DATED] the
 * soonest comes first, which is the ordinary reading. The other two buckets have no moment to sort
 * by, so they keep the order they arrived in, which is each store's own.
 */
fun rankOutstanding(items: List<OutstandingItem>): List<OutstandingItem> =
    items.withIndex().sortedWith(
        compareBy<IndexedValue<OutstandingItem>> { bucketOf(it.value).ordinal }
            .thenBy {
                when (bucketOf(it.value)) {
                    Bucket.OVERDUE -> it.value.dueAtMs ?: Long.MAX_VALUE
                    Bucket.DATED -> it.value.dueAtMs ?: Long.MAX_VALUE
                    else -> 0L
                }
            }
            .thenBy { it.index },
    ).map { it.value }

/**
 * What the whole thing amounts to, in one sentence.
 *
 * A count is a fact the caller should be handed rather than left to derive, and this is the sentence
 * the advisor speaks. **It never says "0 outstanding"** - an absent worry is reported as nothing
 * outstanding, not as a zero, because a zero invites the reader to wonder what it was counting.
 */
fun outstandingSentence(items: List<OutstandingItem>): String {
    val live = items.filterNot { it.done }
    if (live.isEmpty()) return "Nothing outstanding."
    val overdue = live.count { it.overdue }
    val head = "${live.size} outstanding"
    return if (overdue > 0) "$head, $overdue past its date." else "$head."
}
