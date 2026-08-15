# The goal store

Type: grilling
Status: resolved

## Question

What is a long-term goal in the record? Per-aspect goals ("get to 175 lbs", "save $30k by 2028")
were decided as new stored data at charting, sitting outside the trust tiers like targets do.
Open: the schema (one `goals` table with an aspect column vs per-domain tables - test against
legion-shape's "separate storage per domain" ruling); what a goal minimally carries (statement,
aspect, optional number + unit + date, status); how one is captured (voice tool, screen, both);
how a goal relates to per-period targets (a target can serve a goal, but is the link stored or
inferred by the advisor?); lifecycle (achieved / abandoned / revised, and whether history is
kept); and the Room migration (v12+, additive, verbatim generated SQL, exportSchema).

Added on resolving the advisor contract (2026-08-13): this ticket also owns the **advice log
schema** - the contract decided each advisor exchange persists (aspect, question, advice gist,
structured proposal, accepted/rejected, timestamp) with the last ~N per aspect riding the
digest. Decide its table shape in the same migration.

## Answer

Grilled with Kevin, 2026-08-13. Six calls, and the schema they imply.

**1. One `goals` table with an aspect column**, not per-domain tables. Targets are separate
because their shapes genuinely differ (cents+category vs calories+macros vs miles+date); a goal
does not - it is uniformly statement + aspect + optional number. One table gives the digest one
query, the voice layer one tool, and the HOME advisor a single read across all five aspects.
Legion-shape's "separate storage per domain" was a ruling about plan-versus-actual mechanics,
not a rule that every new concept fragments.

**2. Prose required, numbers optional.** Every goal carries Kevin's own statement plus an aspect.
`targetValue`, `unit`, `deadline` are nullable. Forcing a number would manufacture fake metrics
for real goals ("ship the deck"); leaving it optional lets measurable goals get real gap math
while the rest are coached qualitatively. The digest tells the advisor which kind it holds.

**3. A goal may name a known metric.** Optional `metricKey` (TEXT: e.g. `bodyweight_kg`,
`savings_balance_cents`, `odometer_miles`). When set, deterministic code puts current value,
trend, and an on-track/off-track projection in the digest and the advisor only interprets -
"LLM advises, app computes" (contract) and CRED's app-computed-projection rule. When null the
goal is prose-tracked. **Widening the key list is not a migration** (TEXT, no CHECK constraint -
CLAUDE.md §5's `IngestMethod` precedent), so new metrics never cost a schema bump. Confirm it the
§5 way: read the column's `createSql` and check the schema JSON is byte-unchanged after kapt.

**4. Revision trail, house pattern.** Nothing deleted or overwritten. A material change (number,
deadline, statement) inserts a new row sharing a `lineageId` and supersedes the prior; `status`
(`active`/`achieved`/`abandoned`) rides the current row with a `closedAt`. Same copy-forward
shape as `BudgetTarget`/`MealTarget` (traced: both write a row only on change and read the
latest on or before a date). The coaching payoff is falsifiable: the advisor can see a goal that
quietly got easier, which is §7-safe because it is a fact about the record, not invented history.

**5. Voice tools plus a GOALS panel.** `set_goal` / `list_goals` / `close_goal` on the live
session, folded into the aspect buckets the lean-toolbox research proposes so standing prompt
cost stays flat; plus a read-and-edit GOALS panel per aspect screen. Matches how targets already
work (set by voice AND screen, ledger ticket 06 D9/D2).

**6. The goal-to-target link is inferred, never stored.** The digest hands the advisor both
goals and targets for the aspect and it reasons about which serve which. A stored link is
hand-maintained bookkeeping that goes stale silently on every target edit. Revisit only if the
advisor visibly struggles.

**7. Advice log stores gist + full text + proposal JSON**, with `accepted`/`rejected`/`pending`.
Only the gist and proposal ride the digest (last ~3 per aspect, exact N pinned by the
token-budget ticket), so prompt cost stays bounded while the full text stays readable on screen
and auditable later.

### Schema sketch (for the build ticket, not binding on field names)

`goals`: id, lineageId, aspect, statement, targetValue?, unit?, metricKey?, deadlineEpoch?,
status, supersedesId?, closedAt?, createdAt, updatedAt.

`advisor_advice`: id, aspect, questionText, gist, adviceText, proposalJson?, outcome
(pending/accepted/rejected), createdAt, resolvedAt?.

**Migration: Room v15 -> v16** (traced: `CarDatabase.kt` line 109 reads `version = 15`;
MEMORY.md's "Room v11" and this ticket's original "v12+" are both stale). Two additive tables,
verbatim generated SQL, `exportSchema`, migration test, no destructive fallback.

Assumptions ledger: Room v15, `BudgetTarget`/`MealTarget` copy-forward shape and their
no-trust-tier precedent, `plan/Plan.kt` existing - **traced** (read the files). The TEXT-enum
widening rule - **traced** to CLAUDE.md §5, and the ticket instructs verifying it rather than
assuming. Everything else - Kevin's decisions, recorded live.
