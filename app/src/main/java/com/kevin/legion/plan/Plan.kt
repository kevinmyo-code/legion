package com.kevin.legion.plan

/**
 * The shared plan-versus-actual vocabulary, `.scratch/legion-shape/issues/05-target-log-gap-vocabulary.md`
 * (D1-D8). **Words only, on purpose (D7).** This package holds the tier type and the gap type
 * and NOTHING else - no DAO, no storage, no base class, no interface a domain (budget, workouts,
 * maintenance, macros) must implement. Ticket 03 already killed the idea of a generic plan
 * "engine"; this is the residue that survived - "if `plan/` ever grows a DAO, this decision has
 * been violated" is the ticket's own words, not a paraphrase.
 *
 * **What is NOT here, deliberately:**
 * - A `Target` type. D1/D3: a target has a period and is copied forward (D2), but it is an
 *   INTENTION, not a claim about the world - it sits outside both trust tiers entirely, so it
 *   is not a "gap" input in the sense [TrustTier] describes and each domain keeps its own target
 *   storage shape (a budget's is a Room row per category; a workout plan's would be its own).
 * - A `LogEntry` interface. D4 says every domain's log entry shares exactly four things (when,
 *   which tier, how much, what it's about) - stated as a NAMING CONVENTION four domains follow
 *   independently, never as a Kotlin interface they implement. An interface here is exactly the
 *   generic base class D7/ticket 03 rejected.
 */

/**
 * Ticket 02's two trust tiers, generalised: [PROVEN] means an outside source agrees (a statement
 * reconciled against its own printed total); [REPORTED] means the driver - or an AI acting on the
 * driver's behalf - said so, with nothing external to check it against. Ticket 02's own worked
 * examples of [REPORTED] already include "a spend category" and `IngestMethod.UNRECONCILED`
 * (Bank of America's mid-cycle card CSV, which states no anchor to reconcile against at all) -
 * this enum is that same rule, named once so ledger, workouts, maintenance and macros never
 * reinvent it slightly differently.
 */
enum class TrustTier { PROVEN, REPORTED }

/**
 * D6, expressed exactly once so four domains cannot diverge: a set of actual entries is
 * [TrustTier.REPORTED] as a whole the instant ANY one of them is - never a proportion, never
 * "mostly proven". Deliberately strict and simple: a proportional rule would be a second thing
 * to get wrong in four places, which is precisely what ticket 03 exists to prevent (D6's
 * resolution text names its own accepted cost: "a grocery budget reads 'reported' all month
 * because of one pending card row").
 *
 * An empty receiver reduces to [TrustTier.PROVEN] - there is no reported entry to taint a gap
 * that has no entries at all. This is a fact about the reduction, not a claim that an EMPTY gap
 * should be *displayed* as proven; a caller with zero actuals decides that presentation question
 * itself (an unspent budget line, for instance, has nothing to be cautious about yet).
 */
fun Iterable<TrustTier>.combinedTier(): TrustTier =
    if (any { it == TrustTier.REPORTED }) TrustTier.REPORTED else TrustTier.PROVEN

/**
 * D5: the gap is ONE computation - target minus actual - and "remaining" (budget), "adherence"
 * (workouts), "overdue" (maintenance) and "distance from target" (macros) are four DISPLAYS of
 * that one subtraction, in different units, never four separate algorithms. This type carries
 * that computation's result and its combined [TrustTier] and nothing else - no persistence
 * annotation, no Room, no base class or interface a domain must extend (D7).
 *
 * [T] stays generic on purpose: a budget's unit is `Long` cents, a workout's is reps, a
 * maintenance item's is days overdue, a macro's is grams - `plan/` does not get to assume which,
 * and forcing one (e.g. hardcoding `Long`) would smuggle a storage assumption back into a package
 * whose whole point is to hold none.
 */
data class PlanGap<T>(
    val target: T,
    val actual: T,
    val gap: T,
    val tier: TrustTier,
)
