---
map: aspect-advisors
title: "Map: Aspect advisors"
charted: 2026-08-13
charted-by: Kevin + Fable
effort: "`.scratch/aspect-advisors/`"
tickets: 21
open: 1
status: open
tags: [map]
---
# Map: Aspect advisors

## Destination

**Five pull-only advisor sub-agents shipped on-device**: BIO coach, LOG planner, FLEET
maintenance advisor, CRED financial advisor, and a cross-aspect HOME advisor - each callable by
the voice orchestrator, each a SubAgent on Kevin's key briefed with a baked-in playbook of
researched best practices plus a deterministic digest of the record. Goals become new stored
data per aspect. Advice can land in the record: propose -> Kevin's spoken yes -> written through
the existing tool layer, tagged advisor-proposed. The map closes when the advisors answer on the
phone, not when the spec is written.

## Notes

- **Execution is IN SCOPE** (Kevin, 2026-08-13) - deliberate override of wayfinder's
  plan-don't-do default, same as cyberdeck-ui. Build tickets graduate from fog as decisions land.
- Decisions grilled at charting, binding on every ticket:
  - **Pull-only.** An advisor speaks only when asked or when the orchestrator routes a question
    to it. No notifications, no ProactiveBus injection, no on-screen advisor panels. Chosen over
    both alternatives explicitly.
  - **Baked-in playbooks.** Each advisor's expertise is researched at dev time and shipped in its
    brief. No runtime search grounding, no runtime fetches (guardrail: assets bundled, never
    fetched). Refreshing a playbook is a dev task.
  - **LLM advises, app computes.** Advisors receive deterministic digests (targets, actuals,
    gaps) built by existing code; they judge and recommend, they do not do arithmetic.
  - **Goals are new data**, stated by Kevin, sitting outside the trust tiers like targets do
    (an intention is not a claim).
  - **Propose -> accept -> write.** Nothing writes without Kevin's yes; accepted proposals go
    through the existing tool layer with advisor-proposed provenance.
- CLAUDE.md §7 applies with teeth here: **no compulsion mechanics** (a coach may be direct; a
  mechanism engineered to pull Kevin back is banned), **advice is an estimate and says so in
  words** (health and money especially), genuine distress still routes to CrisisDetector and the
  advisor stops coaching.
- **Every tool is prompt tokens on every live session, on Kevin's key.** Tool count is 69; this
  effort adds advisor + goal tools. Watch the budget (MEMORY.md, 2026-08-07).
- Skills each session should consult: `/grilling` and `/domain-modeling` for HITL tickets,
  `/research` for research tickets, `/prototype` for prototype tickets.
- **HARD PROCESS RULE.** Commit `map.md`, `issues/**`, `research/**` like any other file. File
  decisions to `memory/library/decisions.md` when made, not when the effort ends.

## Decisions so far

<!-- one line per closed ticket: gist + link -->

- [The advisor contract](issues/01-advisor-contract.md) - one `AdvisorAgent` harness, five briefs
  (playbook + digest builder + proposal schema); shared rules once in the harness prompt; one
  `ask_advisor(aspect, question)` live tool; one-shot `askTyped` (prose + structured proposal in
  one POST); advisors keep a persisted advice log, last ~3 exchanges riding the digest. Kevin's
  context-bloat segue charted as [Lean toolbox](issues/12-lean-toolbox.md).
- [Research: lean toolbox - tool discovery for the live session](issues/12-lean-toolbox.md) -
  findings in `research/lean-toolbox.md`. Gemini Live fixes tools at session setup (no mid-session
  update, unlike OpenAI Realtime); toolbox is actually 71 tools at ~10-11k estimated prompt tokens
  per socket including prewarms; a 12-15-tool core + `discover_tools`/`call_tool` dispatch
  estimates ~2.2-2.7k (~75-80% saved) and needs no API support. Recommended: static aspect
  buckets, validating `call_tool`, spike one domain first. Adoption decision folded into the
  token-budget ticket.
- [The goal store](issues/02-goal-store.md) - ONE `goals` table with an aspect column (targets
  differ in shape, goals do not); prose statement required, number/unit/deadline optional; an
  optional `metricKey` lets deterministic code compute progress and projection so the advisor
  only interprets; revision trail via `lineageId` + supersede, matching BudgetTarget's
  copy-forward house pattern; voice tools plus a GOALS panel; goal-to-target links inferred, never
  stored. Advice log stores gist + full text + proposal JSON. **Room v15 -> v16**, two additive
  tables (v15 traced from `CarDatabase.kt`; MEMORY.md's v11 is stale).
- [The propose-accept-write protocol](issues/03-propose-accept-write.md) - proposals persist as
  `pending`; `accept_proposal(id)` executes the STORED json so the live model never retypes a
  value; a modification re-asks the advisor for a fresh proposal rather than taking overrides;
  **intentions only** (goals/targets/plans/maintenance/reminders - never an actual, never a
  delete, never a recategorise) on a per-aspect allowlist; provenance in the advice log only, no
  `source` columns; proposals expire after the conversation + ~24h and the refusal is worded.
  Existing direct-dictation write tools keep their no-confirm behaviour untouched.
- [Aspect digests](issues/08-aspect-digests.md) - five `DigestBuilder`s in `advisor/`, read-only
  over existing controllers; **compact labelled text**, not JSON; window is current period + 3
  prior then trends; aggregates plus named exemplars, never raw rows. Every figure carries its
  `TrustTier` (reusing `plan/`'s `combinedTier()`), unverified and estimate figures are marked in
  words, and an empty domain reads "not logged" never zero. Per-aspect contents specced on the
  ticket; each digest also carries that aspect's goals and last ~3 advice-log exchanges.
- [Safety, labelling, and the coach's register](issues/10-safety-and-labelling.md) - candid about
  facts, neutral about Kevin, no manufactured pull; **data never triggers the crisis path**
  (`CrisisDetector` stays speech-only), though the advisor may decline in words; the advisor
  speaks in **whatever persona is active** (alfred/dorothy/custom), so **persona owns tone and the
  harness owns the rules** - all safety copy lives in the harness prompt; **no hard numeric
  floors** (Kevin's call - the human yes in propose-accept is the gate instead). Estimate labels
  ride a structural `basis` field, not prose.
- [The cross-aspect HOME advisor](issues/09-home-advisor.md) - its own condensed cross-aspect
  digest (one headline line per aspect, ~1x not 4x); **synthesis brief, no fifth playbook** - it
  names connections and defers domain depth to the aspect advisor; routed as
  `ask_advisor(aspect="home")`, no new mechanism; **read-only**, hands proposals off to the aspect
  that owns the allowlist. Harness consequence: playbook and writable-set are OPTIONAL parts of a
  brief.
**Six of seven build tickets are BUILT.** Only [Ship pass](issues/20-ship-pass.md) remains, and it
needs the device and Kevin. Reviewed by `senior-dev` (no hole in the four safety-critical
properties) and `bug-hunter` (two MAJOR defects found and fixed - see ticket 18).

Build tickets closed (each holds its build report):
- [Build: BIO and CRED digest builders](issues/16-build-digests-bio-cred.md) - weekly averages not
  daily, unlogged days named, provisional rows marked unverified, coverage gaps in words. Empty vs
  genuine-zero tested in BOTH directions. 15 tests.
- [Build: FLEET, LOG and HOME digest builders](issues/17-build-digests-fleet-log-home.md) - FLEET
  reuses the shipped due-axis logic rather than re-deriving it; LOG's deferral signal is a labelled
  proxy because the schema stores no such fact; HOME computes headlines directly, never
  concatenates the other four, and never sums currencies. 42 tests.
- [Build: goal voice tools and the GOALS panel](issues/19-build-goal-tools-and-panel.md) - three
  tools (~71 -> ~74), a GOALS panel on four screens, `GoalController` as the single path. Revision
  trail deliberately NOT rendered (it is for the advisor's digest). **Previews written but NEVER
  RENDERED - deferred gate on the ship pass.** Known limit: prose goals cannot be revised by voice,
  only by panel. 10 tests.
- [Build: the AdvisorAgent harness](issues/14-build-advisor-harness.md) - `advisor/` package:
  aspect enum, `DigestBuilder` interface, `DigestText` shared formatters (tier tags, unverified,
  estimate, "not logged"), `AdvisorBrief` with playbook and writableOps both optional so HOME
  needs no special case, `AdvisorAnswer` with a `basis` field, `HarnessPrompt` holding all safety
  copy, and the one-shot `AdvisorAgent`. 37 tests. **Surfaced that `askTyped` enforces no output
  shape** - contract corrected, hardening filed as
  [Harden structured output](issues/21-harden-structured-output.md).
- [Build: ship the four playbooks as briefs](issues/15-build-playbook-briefs.md) - four constants,
  Sources stripped, all under the 2,500 ceiling measured with `countTokens`: BIO 2,078, LOG 1,731,
  CRED 1,846, **FLEET 2,497 with only 3 tokens of margin** (trimmed 412; safety sections
  untouched). A keyword + size tripwire test guards both.
- [Build: goal store and advice log](issues/13-build-goal-store.md) - Room **v16** shipped:
  `goals` (lineage + supersede revision trail, prose-first, nullable metric fields) and
  `advisor_advice`, plus DAOs and `MIGRATION_15_16`. Verification re-run by the orchestrator, not
  relayed: 764 tests / 0 failures, migration SQL verbatim against `16.json`, `metricKey` confirmed
  plain TEXT with no CHECK. **Unmet gate carried to the ship pass: the 15->16 migration test has
  never executed.**

- [Token and latency budget](issues/11-token-latency-budget.md) - **measured** with `countTokens`,
  no billed calls. Text-vs-JSON saving CONFIRMED at 38.5% mean (33.7-44.6%), so ticket 08's
  estimate holds. chars/4 sanity-checked at 4.15 chars/token, within ~4%. Advice-log window of 3
  costs 194 tokens - affordable, the playbook is what the budget must watch. Per-question totals:
  BIO 3,233, CRED 3,183, FLEET 3,806, LOG 2,821, HOME 1,038. **Ceiling: 2,500 tokens per playbook,
  4,000 per aspect question, 1,500 for HOME.** `ask_advisor` alone adds 239 tokens to the standing
  socket (+2%, ship it); all five advisor/goal tools add 872 (+7-8%). **FLEET's playbook exceeds
  the cap by ~409 tokens and must be trimmed.** Do not ship playbook `## Sources` to the model -
  500-700 tokens per aspect of dev-facing licensing docs. Latency deliberately UNMEASURED.
- [Research: the BIO coaching playbook](issues/04-bio-playbook.md) - drafted in
  `research/bio-playbook.md`: PAG activity floor, double-progression overload, 10-19 sets/muscle
  band, ISSN protein-by-goal table, weekly-average weight reads, sleep as a first-order variable;
  reactive deloads only (2024 RCT found scheduled ones neutral). Hard boundaries: pain, medical,
  disordered-eating -> CrisisDetector.
- [Research: the LOG planning playbook](issues/05-log-playbook.md) - drafted in
  `research/log-playbook.md`: GTD capture/clarify + weekly review, Eisenhower with delegate
  collapsed to decline-or-automate (solo user), 3-MITs daily, prune-before-sort backlog triage.
  GTD's hard-landscape rule makes "Google owns appointments" the orthodox split, not a compromise.
- [Research: the FLEET maintenance playbook](issues/06-fleet-playbook.md) - drafted in
  `research/fleet-playbook.md`: 17-item mileage+time interval table (NHTSA/AAA), three-tier DTC
  triage (flashing MIL = stop now; P0420 is a symptom to root-cause, not a converter verdict),
  log-first rules matched to `MaintenanceItem`'s actual due-axis semantics, DIY-vs-shop tiers.
- [Research: the CRED finance playbook](issues/07-cred-playbook.md) - drafted in
  `research/cred-playbook.md`: MyMoney five-principles spine, 50/30/20 as diagnostic lens only,
  3-6mo essential-expense fund, avalanche default / snowball when stalled (published behavioral
  basis), app-computed goal projections the LLM only interprets, hard referral boundaries for
  tax/investment/insurance.

## Not yet specified

Build tickets 13-20 graduated 2026-08-13; all decision fog is clear. What remains here resolves
INSIDE those builds or belongs to another effort:
- **LOG planner depth vs the Google Calendar effort.** `.scratch/google-account-integration/`
  just ruled Google owns appointments, LEGION owns reminders. How much of "plan my week" the LOG
  advisor can see and touch hangs on those build tickets (12-16) landing.
- **Playbook refresh cadence.** How and when a baked playbook gets re-researched. A dev-process
  question, sharp only after the first playbooks exist.
- **Lean-toolbox adoption.** Research done, numbers now in: the declared toolbox is
  **~10,500-11,000 tokens, roughly a third of a 32k Live context window before a word is spoken**,
  and this effort is the first concrete proposal to grow it (+239 for `ask_advisor` alone, +872
  for all five advisor/goal tools, on every socket including prewarms). Adoption is still
  undecided and is deliberately not this map's call; build tickets 18 and 19 reference it, and
  ticket 02 already commits to folding the goal tools into aspect buckets IF it is adopted.
  Whoever picks this up should treat it as its own effort, not a sub-task here.

## Out of scope

- **Proactive delivery.** Notifications, morning briefings, ProactiveBus injection - explicitly
  declined at charting in favor of pull-only. A later effort may revisit delivery once the
  advice itself is proven good.
- **On-screen advisor panels.** Rendering the latest advice on aspect screens was offered and
  declined; advisors are a voice surface in this effort.
- **Runtime search grounding.** Declined in favor of baked playbooks.
- **Wrapped / periodic recaps.** Was fog pending the HOME advisor; now settled by it. HOME
  answers "how am I doing overall" **on demand and read-only**, which is a pull surface, not a
  generated monthly artifact. Recaps stay parked where legion-shape's cars decision left them,
  and a recap generator would be proactive delivery - already out of scope above.
- **Commercial anything.** Dead since 2026-07-31.
