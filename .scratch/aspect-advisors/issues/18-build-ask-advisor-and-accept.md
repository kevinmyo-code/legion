# Build: ask_advisor and the propose-accept-write path

Type: task
Status: resolved
Blocked by: 14, 16, 17

## Question

Wire the advisors to the live session and implement acceptance, per
[The advisor contract](01-advisor-contract.md) and
[The propose-accept-write protocol](03-propose-accept-write.md).

- **ONE tool**: `ask_advisor(aspect, question)` where aspect is one of bio/log/fleet/cred/home.
  Not five tools - every declaration is prompt tokens on every live session. Its description
  routes home for overall or cross-cutting questions.
- **`accept_proposal(id)`** executes the **STORED** `proposalJson`. The live model names a
  proposal and never supplies values, so nothing drifts between what was read aloud and what
  lands. This is the enforcement; a prompt rule would not be.
- **Per-aspect allowlist, intentions only**: goals, targets, plans, maintenance items, reminders.
  **Never an actual, never a delete, never a recategorise** - an actual is a claim about what
  happened and only Kevin can make it. A BIO proposal cannot reach budgets. **HOME has no
  writable set at all** and hands off to the aspect advisor.
- **A modification re-asks the advisor** for a fresh proposal. `accept_proposal` takes NO
  overrides, deliberately.
- **Expiry**: valid for the conversation plus ~24h (tune if ticket 11 or the spike suggests
  otherwise). Past that it refuses **in words** and the assistant offers to re-check.
- **No hard numeric floors** (Kevin's call). The human yes is the gate. Do not add range
  validation that was explicitly declined.

**Leave the ~15 existing `log_*`/`set_*` tools alone.** They deliberately have no confirm step
(`log_workout_set`: "no confirmation needed, just log it and say"). This protocol is the
exception for advisor-authored writes, not a new global rule.

Verification: unit tests that acceptance writes exactly the stored proposal; that an out-of-
allowlist operation is refused; that an expired proposal refuses with wording, not silence.

## Build report

Built 2026-08-13. New: `advisor/AdvisorBriefs.kt` (the five-brief registry),
`advisor/AdvisorProposalExecutor.kt`. Edited: `service/LiveToolbox.kt` (+`ask_advisor`,
+`accept_proposal`, `mapAdvisorResult` split out for testability). 14 tests in
`LiveToolboxAdvisorTest`.

### The allowlist as implemented
| Aspect | writableOps |
|---|---|
| BIO | `set_goal`, `set_meal_target`, `set_sleep_target`, `create_workout_plan` |
| LOG | `set_goal`, `set_reminder`, `add_task` |
| FLEET | `set_goal`, `set_maintenance_item` |
| CRED | `set_goal`, `set_budget` |
| HOME | (empty) |

`set_maintenance_item` writes **only** `intervalMiles`/`intervalMonths`, never
`lastDoneMileage`/`lastDoneDate`/`neverDone` - those are actuals. The structural guarantee is
better than the naming: `AdvisorProposalExecutor` never imports `logServiceDirect` or any other
`log_*` write path, so "intentions only" holds by construction rather than by discipline.

**The enforcement that makes consent real:** the executor takes no override parameters and
**never trusts an `aspect` field inside the proposal JSON** - it always writes against the calling
brief's aspect. The live model supplies an id and nothing else.

### Fix applied by the orchestrator after the agent reported
The agent correctly found that `AdvisorAgent.ask` **discarded the model's raw text** on a parse
failure, so ticket 18's "the advice is still spoken" was impossible at the tool layer, and it
substituted a canned apology. It flagged this honestly rather than claiming the requirement met.

That was a defect in the landed harness contract, not in this ticket, so it was fixed here rather
than accepted: `AdvisorResult.ParseFailed` now carries `rawText`, and `mapAdvisorResult` RELAYS
the prose with a plain caveat that there is no proposal behind it. **The prose is usually good
coaching - it is the JSON envelope that failed** - and discarding words because their wrapper was
malformed turns a formatting problem into a silent loss of the advice. Only the structured half
(proposal, per-figure `basis` tags) is genuinely unavailable, and the caveat says so. A test
asserts the prose is relayed verbatim and that no proposal is advertised.

### Reasoned deviations, both recorded rather than hidden
- **Expiry is a flat 24h off `createdAt`, not conversation-scoped.** `LiveToolbox` is stateless
  and has no notion of "this conversation", so a same-day later conversation could still accept.
  Never less permissive than the decision, occasionally more. Ticket 03 called 24h a starting
  number, so this stays within its own framing.
- **No dedicated test for the `set_maintenance_item` executor path** - the agent named this gap
  itself. Every other allowlist op is directly tested. Followed up immediately below.

### Verification (orchestrator re-run, not relayed)
`compileDebugKotlin` green; `testDebugUnitTest --rerun-tasks` **887 tests / 0 failures**, then
**888 / 0** after the ParseFailed fix and its new test. Tested directly: acceptance writes exactly
the stored proposal; a BIO proposal reaching for a budget is refused; any proposal trying to log an
actual is refused; HOME has no writable ops; an expired proposal refuses in words and marks the row
`expired`; hostile extra arguments to `accept_proposal` cannot influence the write.

## Review findings and fixes (2026-08-13)

`senior-dev` reviewed against the tickets; `bug-hunter` read the call chains end to end. Senior
review found **no hole** in the four safety-critical properties (intentions-only, consent
enforcement, said-in-words, persona-owns-tone) and confirmed the v16 migration SQL byte-verbatim.
The bug hunt found two MAJOR defects, both now fixed.

### 1. MAJOR - a failed write was recorded `accepted` and reported success
Three controllers signal failure by **returning a spoken failure sentence as an ordinary String**
rather than throwing: `WorkoutController.generatePlan` (sub-agent null - network/rate-limit),
`SleepController.setTarget` (<=0, >24h, NaN, Infinite - reachable from `optDouble` on malformed
proposal JSON), `ReminderController.add` (its private `normalizeLabel` strips a place like
"location" to blank). The executor wrapped those strings as `Ok`, so `accept_proposal` returned
`success: true` and marked the row `accepted` **permanently** - unretryable, and shown as accepted
forever in the advice-log window. The DB row itself became the false positive.

**Fixed by read-back verification, never string-matching** (matching a controller's failure
sentence would rot the moment someone reworded it): each affected op captures `now`, calls the
controller, then re-reads the target row through its DAO and checks it actually landed. New
`ExecuteResult.WriteFailed` returns `success: false` and **leaves the row `pending` so Kevin can
say yes again**.

Sleep-target rejection of NaN/Infinite/<=0/>24h is commented as **input sanity, explicitly NOT the
safe-range floor policy Kevin declined** - nothing substitutes a "safe" value.

### 2. MAJOR - check-then-act race in `accept_proposal`
Read row, check `pending`, execute, mark accepted - no transaction. Two concurrent calls (double
tap, or a model retry racing the original past `TOOL_TIMEOUT_MS`, whose orphaned coroutine still
completes) could both observe `pending` and both write: two identical reminders or tasks, a
spurious extra goal revision, or the Gemini workout sub-agent fired twice on Kevin's key.

**Fixed with an atomic claim**: `AdvisorAdviceDao.claimIfPending` is
`UPDATE ... WHERE id = :id AND outcome = 'pending'`, and **rows-affected is the mutual-exclusion
point** - the plain read never was. 0 rows means someone else holds it; refuse without executing.
A claimed row settles `accepted` on verified success or `revertToPending` on failure.

### 3. SHOULD-FIX - HOME hardcoded its trust tier
`bioHeadline`/`credHeadline` now compute the tier with `combinedTier()` over the rows each figure
actually drew from. `fleetHeadline` stays a documented hardcode: `MaintenanceItem` carries no
per-row `TrustTier` and `FleetDigestBuilder` does not call `combinedTier` either, so there is
nothing to combine - traced, not assumed.

Also closed the one named coverage gap: `set_maintenance_item` now has a direct test proving it
writes **only** intervals and leaves `lastDoneMileage`/`lastDoneDate`/`neverDone` untouched.

### Verification (orchestrator re-run)
`compileDebugKotlin` green, `testDebugUnitTest --rerun-tasks` **905 tests / 0 failures** from
JUnit XML (up from 888).

**Residual, honestly tagged:** `claimIfPending`'s atomicity rests on SQLite's writer
serialization - `reasoned`, since the Robolectric test is single-threaded and confirms sequential
rows-affected semantics, not a true race. The ship pass is where a real double-tap gets tried.
