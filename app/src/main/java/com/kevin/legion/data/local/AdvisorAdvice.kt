package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One persisted advisor exchange (`.scratch/aspect-advisors/issues/02-goal-store.md`, answer
 * call 7, added when the advisor contract was decided 2026-08-13: "each advisor exchange
 * persists (aspect, question, advice gist, structured proposal, accepted/rejected, timestamp)
 * with the last ~N per aspect riding the digest").
 *
 * **Stores gist + full text + proposal JSON, deliberately at different weights.** [gist] and
 * [proposalJson] are what [AdvisorAdviceDao.recent] hands to the NEXT digest (last ~3 per aspect,
 * exact N pinned by the token-budget ticket) - kept short on purpose so prompt cost stays
 * bounded. [adviceText] is the full response and is never sent back to the model; it exists so
 * the exchange is readable on screen and auditable later, the same "cheap thing rides the prompt,
 * expensive thing rides the record" split the ledger/pantry ingestion tables already use for
 * provenance vs. raw extraction.
 *
 * [outcome] tracks the accept/reject lifecycle of whatever [proposalJson] proposed (e.g. "set
 * this as your new bodyweight target"). `pending` until the driver acts on it, `expired` if it
 * ages out unanswered - see [AdvisorAdviceDao.markOutcome]. Plain TEXT with no CHECK constraint,
 * matching [Goal.metricKey]'s precedent, so a new outcome state is a code change, not a migration.
 *
 * No revision trail here unlike [Goal] - an advice exchange is a point-in-time record of what was
 * said and decided, not something that gets materially revised after the fact. [outcome] is the
 * one field that legitimately changes post-insert, and [AdvisorAdviceDao.markOutcome] updates it
 * in place for exactly that reason.
 */
@Entity(tableName = "advisor_advice")
data class AdvisorAdvice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `bio` / `log` / `fleet` / `cred` / ... - plain TEXT, matching [Goal.aspect]. */
    val aspect: String,
    /** What was asked - Kevin's own words, or the prompt that triggered an unprompted nudge. */
    val questionText: String,
    /** Short summary that rides the digest - see class doc for why this is split from [adviceText]. */
    val gist: String,
    /** The full advisor response, never re-sent to the model - readable/auditable only. */
    val adviceText: String,
    /** Structured proposal (e.g. "new goal", "new target") the driver can act on, if the advice
     * carried one. Null for advice that was purely conversational. */
    val proposalJson: String? = null,
    /** `pending` / `accepted` / `rejected` / `expired`. See class doc for why TEXT with no CHECK. */
    val outcome: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
    /** Set when [outcome] leaves `pending`. Null while still pending. */
    val resolvedAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
