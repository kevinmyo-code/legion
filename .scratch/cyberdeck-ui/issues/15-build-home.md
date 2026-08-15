# Build: HOME rebuild

Type: task
Status: resolved
Blocked by: 13

## Question

Rebuild TodayScreen per ticket 06: INTAKE hero (kcal + meter + pace tick), SYSTEMS SWEEP
checklist, AGENDA, ALERTS; silent domains stated; zero charts; advisory tags; per-panel
tap-through to modules. Existing gap-resolver logic is reused, not rewritten.

## Answer

Built 2026-08-08 (coding agent, worktree, merged). INTAKE hero (DeckMeter with day-elapsed pace
tick, NotLogged renders words never zero), SYSTEMS SWEEP via new pure resolvers reusing existing
gap data, AGENDA retaining item identity from the same NotesController reads the old count used,
ALERTS from LedgerController.quarantinedFiles with QuarantineTag only on a real quarantine.
Review finding 2 fixed: incomplete statement coverage is now a COVERAGE GAP amber advisory - red
is gone from this screen. compile + tests green (tested). The build agent left INTAKE/SLEEP/
TRAINING/FLEET taps inert at its scope boundary (named gap); wired at merge: TodayScreen gained
onOpenBody/onOpenFleet, MainActivity navigates BODY/FLEET. Deferred to 21: on-device rendering.
