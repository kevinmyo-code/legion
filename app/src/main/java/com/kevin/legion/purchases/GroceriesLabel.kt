package com.kevin.legion.purchases

/**
 * The item-editor history line for the Groceries list (ADR 0055): "Last bought Sep 20 (middle dot)
 * Mia", where every other list keeps "Last ticked" (ADR 0049). Split from [PurchaseWording] to keep
 * each object under detekt's function ceiling, not for any difference in rule: same wording rules.
 */
object GroceriesLabel {

    const val NO_RECORD = "No record of this being bought"

    /** Shown when the log cannot be read: said, never hidden, so a down engine is not mistaken for
     * "never bought". */
    const val UNREADABLE = "Can't reach the bought log, so last-bought is unknown right now"

    /** Only the household's list named Groceries reads "last bought"; the server decides the same
     * way (case-insensitive, trimmed). */
    fun isGroceriesList(listName: String?): Boolean = listName?.trim().equals("Groceries", ignoreCase = true)

    /** "Last bought Sep 20 (dot) Mia"; a loose match names its own text; a backfilled entry says
     * "who not recorded". */
    fun label(match: PurchaseMatch, today: Int): String {
        val who = match.entry.loggedBy ?: "who not recorded"
        val text = if (match.exact) "" else "\"${match.entry.item}\" "
        return "Last bought $text${PurchaseWording.date(match.entry.boughtOn, today)} · $who"
    }

    /** The label for a read of the log: the answer, a plain no-record, or the unreadable sentence. */
    fun labelFor(outcome: PurchaseOutcome<List<PurchaseMatch>>, today: Int): String = when (outcome) {
        is PurchaseOutcome.Ok -> outcome.value.firstOrNull()?.let { label(it, today) } ?: NO_RECORD
        else -> UNREADABLE
    }
}
