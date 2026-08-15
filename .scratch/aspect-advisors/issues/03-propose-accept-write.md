# The propose-accept-write protocol

Type: grilling
Status: resolved
Blocked by: 01, 02

## Question

How does an advisor's proposal become record data on Kevin's spoken yes? Open: the structured
proposal format the advisor returns alongside its prose (which existing tool calls it maps to);
what is writable per aspect (BIO: targets + a workout plan's shape; CRED: budget lines; FLEET:
maintenance items; LOG: tasks/reminders; goals themselves?); the provenance tag
(advisor-proposed - a new value or a note field, and whether it needs schema); partial
acceptance ("yes but 3 days not 4"); and how the no-write-without-yes rule is enforced at the
tool layer rather than by prompt discipline, echoing voice-logging's "tier tagging at the tool
layer, so no domain can forget it".

## Answer

Grilled with Kevin, 2026-08-13. Five calls.

**1. Stored proposal, then `accept_proposal(id)`.** The advisor's structured proposal is persisted
to the advice log as `pending`. Acceptance is a real tool that reads the STORED json and executes
it. The live model never supplies the values - it only names a proposal - so nothing can drift
between what was read aloud and what lands. This is voice-logging's "tier tagging at the tool
layer" applied to consent: the mechanism guarantees it, not the prompt.

**Context that makes this the exception, not a new global rule** (traced,
`service/LiveToolbox.kt`): the ~15 existing `log_*`/`set_*` write tools deliberately have NO
confirm step - voice-logging D ruled "no confirm step; the assistant states what it wrote", and
`log_workout_set`'s own description says "no confirmation needed, just log it and say". Direct
dictation stays exactly as it is. Only advisor-authored writes go through propose-accept.

**2. A modification re-asks the advisor.** "Yes, but 3 days not 4" goes back as a follow-up and
returns a NEW stored proposal to accept. Overrides on the accept tool were rejected specifically
because they put the live model back in the business of supplying numbers, which is what call 1
exists to prevent. Cost: one extra round trip - and the upside is the coach gets to say whether
3 days breaks its own reasoning.

**3. Intentions only, per-aspect allowlist.** A proposal may set goals, targets, plans,
maintenance items and reminders. It may NEVER log an actual, delete, or recategorise history.
**An actual is a claim about what happened and only Kevin can make it** - an advisor writing one
would manufacture `reported`-tier data out of an inference, which is the trust-tier violation
legion-shape's two-tiers decision exists to prevent. The allowlist is per aspect, so a BIO
proposal cannot reach budgets.

**4. Provenance lives in the advice log only.** The accepted row records what was written and
when; provenance is reconstructable against the target's own `updatedAt`. No `source` column on
`budget_targets` / `meal_targets` / `sleep_targets` / workout plans / maintenance items - five
migrations for a field nothing reads on the hot path, and five existing write paths that would
have to keep it correct forever.

**5. Proposals expire.** Valid for the conversation they were made in plus a short TTL (24h is
the starting number, the build may tune it). Past that `accept_proposal` refuses and the
assistant says in words that it will re-check and propose again. Rebuilding the digest and
re-verifying at accept time was considered and rejected: it is a second Gemini call on the accept
path, on Kevin's key, to save a re-ask that is already cheap. **The refusal is worded, never
silent** - §4 rule 7's "said in words" discipline applied to a stale intention.

Assumptions ledger: existing write tools have no confirm step, `log_workout_set`'s description,
`create_workout_plan` already existing, ~15 `log_*`/`set_*` tools - **traced** (read
`service/LiveToolbox.kt`). 24h TTL - **reasoned**, a starting number for the build to tune, not a
measured one. Everything else - Kevin's decisions, recorded live.
