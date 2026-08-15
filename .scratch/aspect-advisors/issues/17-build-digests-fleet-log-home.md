# Build: FLEET, LOG and HOME digest builders

Type: task
Status: resolved
Blocked by: 11, 13

## Question

The remaining three `DigestBuilder`s from [Aspect digests](08-aspect-digests.md). Same format,
window, granularity and trust-tier rules as ticket 16 - read that ticket's non-negotiables, they
apply here unchanged.

- **FLEET** - maintenance due by whichever comes first, miles or date, with `neverDone` items
  reading overdue-now (match `MaintenanceItem`'s documented due-axis semantics; the FLEET
  playbook's rules were written against them); recent DTCs with severity tier; odometer trend;
  last service dates.
- **LOG** - open tasks by age band; overdue reminders; the calendar horizon (READ-ONLY - Google
  owns appointments, LEGION owns reminders); repeated-deferral flags, which the playbook reads as
  a signal of a mis-sized, dead or dreaded task.
- **HOME** - the condensed cross-aspect digest from
  [The cross-aspect HOME advisor](09-home-advisor.md): ONE headline line per aspect (the gap that
  matters, trend direction, any goal off track) plus all goals and flagged exceptions. Sized at
  roughly one aspect digest, NOT four - if it approaches four, the design has been missed.

HOME's synthesis brief also lands here if ticket 14 did not write it: it connects across aspects
and **defers domain depth to the aspect advisor** rather than improvising.

Verification: as ticket 16, plus an explicit test that the HOME digest stays within its size
target with all five aspects populated.

## Build report

Built 2026-08-13. `advisor/digest/FleetDigestBuilder.kt`, `LogDigestBuilder.kt`,
`HomeDigestBuilder.kt`, 42 tests (11 + 16 + 15).

**FLEET** reuses `ui/fleet/FleetRows.kt`'s shipped `buildDueRows` / `distinctFaultsByFirstSeen`
rather than re-deriving `MaintenanceItem`'s due-axis semantics a second time - the exact
duplication the ticket warned would silently corrupt maintenance answers. DTC severity is a small
classifier keyed off `FleetPlaybook.TEXT`'s own literal code lists, kept separate from
`TrustTier`. `neverDone` reads "overdue-now (never logged)", distinct from an axis-anchored
overdue item, and that distinction is tested.

**LOG** reads the calendar horizon through `CalendarProvider` READ-ONLY. Repeated-deferral is an
**explicitly labelled reasoned proxy** (open >=14d, touched >=3d after creation) because no
deferral-count column exists anywhere in the schema - the playbook wanted a signal the data does
not actually store, and the builder says so rather than implying the number is real.

**HOME** computes each headline directly off the DAOs and **never calls the other four builders** -
the concatenation approach ticket 09 rejected. Its CRED headline ranks the worst gap across
currencies **without ever summing SGD and USD**, matching §4 rule 5. It also does NOT re-embed
goals, because `AdvisorAgent.composeContext` already appends `GOALS:` and the advice-log window for
every aspect including HOME (traced) - a nice catch that avoids duplicating the harness's work.

### Unmeasured, carried to the ship pass
HOME's "stays small" test uses the same chars/4 heuristic the harness documents as `reasoned`,
asserting under 300 tokens with all five aspects populated. **No real `countTokens` pass has been
run on any shipped builder output.** Named as a gate on [Ship pass](20-ship-pass.md).

Also `reasoned` not validated: the DTC severity mapping has never seen real ELM327 output, and
odometer figures are tagged `REPORTED` on the argument that a driver-stated baseline plus a trip
estimate has nothing external to check it against.

Verification (orchestrator re-run): compile green, **874 tests / 0 failures** from JUnit XML;
`FleetDigestBuilderTest` 11/11, `LogDigestBuilderTest` 16/16, `HomeDigestBuilderTest` 15/15.

## Senior review finding (2026-08-13) - SHOULD-FIX, open

`HomeDigestBuilder.kt` hardcodes `TrustTier.REPORTED` on every headline line (lines ~107, 138,
148, 167, 177) instead of computing it with `combinedTier()` over the underlying rows, which is
what `CredDigestBuilder` and `BioDigestBuilder` do.

Ticket 08's "law, not taste" section says every digest figure carries its tier **via
`combinedTier()`**. This is a deviation from the stated mechanism, not from the outcome:
`REPORTED` is the conservative value, so **this can never present a REPORTED figure as PROVEN** -
the failure mode it guards against cannot occur. But hardcoding means a HOME headline built
entirely from proven rows still reads `[reported]`, understating what the record actually knows,
and the next person copying this builder inherits the shortcut.

Fix by computing the tier from the same rows each headline is derived from.
