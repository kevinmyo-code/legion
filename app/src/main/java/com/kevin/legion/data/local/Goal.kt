package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One revision of a long-term, cross-aspect goal (`.scratch/aspect-advisors/issues/02-goal-store.md`,
 * grilled 2026-08-13). "Get to 175 lbs", "save $30k by 2028", "ship the deck" - anything Kevin
 * states as an intention for an aspect, held OUTSIDE the record of what actually happened.
 *
 * **One table with an [aspect] column, not per-domain tables** (answer call 1). Targets
 * ([BudgetTarget]/[MealTarget]) are split per-domain because their shapes genuinely differ
 * (cents+category vs calories+macros vs miles+date); a goal does not - it is uniformly a
 * statement plus an optional number. One table gives the digest one query, the voice layer one
 * tool, and the HOME advisor ([GoalDao.allCurrentGoals]) a single read across every aspect.
 * `aspect` is a plain `String` (`bio`/`log`/`fleet`/`cred`/...), not a Kotlin enum, for the same
 * reason [metricKey] is a plain `String` below: nothing here should force a schema bump just to
 * teach the store about a new aspect name.
 *
 * **Prose required, numbers optional** (answer call 2). [statement] is Kevin's own words and is
 * always required - even a goal with a full [targetValue]/[unit]/[deadlineEpoch] still carries
 * one, because the number alone ("175") means nothing without it. [targetValue], [unit],
 * [metricKey], and [deadlineEpoch] are all nullable, and all-null is a VALID, ordinary row - "ship
 * the deck" never gets a manufactured fake number just to fit a NOT NULL column. The digest tells
 * the advisor which kind of goal it is holding: prose-tracked or number-tracked.
 *
 * **[metricKey] is a plain TEXT column with NO CHECK constraint, on purpose** (answer call 3,
 * confirmed against `app/schemas/.../16.json` rather than assumed - see [MIGRATION_15_16]'s doc
 * comment for the verification). When set to a known key (`bodyweight_kg`, `savings_balance_cents`,
 * `odometer_miles`, ...) deterministic code can compute current value / trend / on-track
 * projection for the digest and the advisor only interprets - "LLM advises, app computes"
 * (advisor contract) and CRED's app-computed-projection rule. Widening that key list later is
 * then a code change, never a migration, matching the precedent [IngestMethod] set at v5 for the
 * exact same reason (CLAUDE.md §5).
 *
 * **No [com.kevin.legion.plan.TrustTier] column**, matching [BudgetTarget]/[MealTarget]'s
 * precedent (ticket 05 D3): a goal is an intention Kevin states, not a claim about the world, so
 * it sits outside both trust tiers entirely rather than being force-fit into one.
 *
 * **Revision trail, house copy-forward pattern** (answer call 4). Nothing is ever deleted or
 * overwritten. A MATERIAL change - [targetValue], [unit], [deadlineEpoch], or [statement] itself
 * - inserts a NEW row that shares the same [lineageId] and sets [supersedesId] to the prior row's
 * id. [status] (`active`/`achieved`/`abandoned`) and [closedAt] ride the CURRENT row of a lineage;
 * [GoalDao.currentGoals] reads "the latest row per lineage where status is active" the same way
 * [BudgetTargetDao.currentTargets]/[MealTargetDao.currentTarget] read "the latest row on or
 * before this date" - copy-forward computed at read time, never written as a maintenance step.
 * The coaching payoff named in the answer is falsifiable history: the advisor can see a goal that
 * quietly got easier over successive revisions, because that is a fact sitting in the record, not
 * an invented one (§7-safe).
 *
 * **The goal-to-target link is inferred, never stored** (answer call 6) - deliberately no column
 * here pointing at a [BudgetTarget]/[MealTarget]/[WorkoutPlan] row. The digest hands the advisor
 * both goals and targets for the aspect and it reasons about which serves which; a stored link
 * would be hand-maintained bookkeeping that goes stale silently on every target edit.
 *
 * Carries [syncId] so that wiring Drive sync onto this table later does not need its own
 * migration, matching [GroceryItem]'s precedent.
 */
@Entity(
    tableName = "goals",
    indices = [
        Index(value = ["lineageId"]),
        Index(value = ["aspect", "status"]),
    ],
)
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Groups every revision of the same goal. Shared by every row a [supersedesId] chain links. */
    val lineageId: Long,
    /** `bio` / `log` / `fleet` / `cred` / ... - plain TEXT, see class doc for why not an enum. */
    val aspect: String,
    /** Kevin's own words. Always required, even when [targetValue] is also set - see class doc. */
    val statement: String,
    val targetValue: Double? = null,
    val unit: String? = null,
    /** Known-metric key for app-computed projection math, e.g. `bodyweight_kg`. Null means
     * prose-tracked. TEXT, no CHECK constraint - see class doc, confirmed in [MIGRATION_15_16]. */
    val metricKey: String? = null,
    /** UTC epoch millis, if the goal names a deadline. */
    val deadlineEpoch: Long? = null,
    /** `active` / `achieved` / `abandoned`. Rides the CURRENT row of a [lineageId] chain. */
    val status: String = "active",
    /** The id of the row this one revises, or null for a lineage's first row. */
    val supersedesId: Long? = null,
    /** Set only when [status] moves off `active`. Null on every still-active row. */
    val closedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
