---
map: one-home
ticket: "02"
title: "Rehome the orphans before anything is deleted - the Ask panel first"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-what-the-shell-is-without-meters]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Rehome the orphans

**This ticket exists so that ticket 03 is allowed to delete a file.** Rehome, verify, THEN delete.
The reverse order loses a capability and discovers it on the phone.

## The four orphans, and how each was established

Each claim below came from a grep across `app/src/main/java` for other call sites. That is `traced`,
not `built`: a reflective or string-built call would not show up. Confirm with the compiler - delete
the pane and see what fails - rather than trusting the grep alone.

### 1. The Ask panel - ADR 0035, and the reason this ticket is a gate

`MetersScreen.kt:658` renders a picker over five closed enums (`GeneratedViewShape`, `QuerySource`,
`QueryAggregation`, `QueryWindow`, `QueryGrouping`) and calls `GeneratedViewQueryRunner.run` at
`MetersScreen.kt:697`. **That is the only production call site of `run`.**

`GeneratedViewHost` (`MainActivity.kt:1027`) does NOT cover for it. That is an app-wide overlay that
renders a spec someone else already built; it is a display, not a builder.

Delete the panel and `show_generated_view` becomes a voice-only capability, which ADR 0035 says is
not finished. **Destination comes from ticket 01 question 1.** Wherever it lands:

- It calls the **same** `GeneratedViewQueryRunner` / `GeneratedViewController`. Not a second
  implementation - ADR 0035's own "not a second implementation" clause, and the reason the existing
  panel has no free-text field.
- The pickers stay closed enums, identical to the ones the voice tool validates against.
- Its refusal path (`askRefusal`) moves with it. A refusal that stops being rendered is a silent
  failure.

### 2. `NewsDigestCard` - private, `MetersScreen.kt:877`

The only on-demand trigger for `SitrepBuilder.build(context, setOf(SitrepModule.NEWS))`. Built by
command-center ticket 12, which is `built` and owes a tap on the phone - so this is a capability that
has not yet been confirmed working being moved before it was ever confirmed.

**Move it as-is, do not improve it, and do not merge it into ticket 07's feed.** 07 is blocked on a
ruling (06) that has not been made; folding this into it would drag a working, §7-compliant surface
behind an undecided one. Extract it from `MetersScreen.kt` into its own file so it survives the
deletion, keeping its posture verbatim: tap-to-fetch, never auto-poll, staleness shown, read-through,
no Room row, not even the summary.

### 3. `AreaCard()` - `ui/world/AreaCard.kt:77`, one call site

Called only from `MetersScreen.kt:723`. Needs an address or it is dead code with a live definition,
which is worse than either.

### 4. The pure functions `MetersScreenTest` covers

`buildMeterBreaches`, `moneyUncategorizedSentence`, `groceriesHeroValue` are top-level functions in
`MetersScreen.kt` with 14 tests against them in `ui/MetersScreenTest.kt`.

**They should move, not die.** Breach building in particular is the "Needs you" pane, and that pane
is the one thing on Meters that is not a duplicate of a screen elsewhere - it is a computed judgement
about budget and maintenance. Put them in a file that is not a screen (they are not Composables) and
carry the tests across intact. A test deleted alongside the screen it happened to live next to is
coverage lost for a reason that has nothing to do with the code.

## Also moving, but not orphans

`RecordControlRow` is shared with `VoiceNotesScreen.kt:102` and is safe either way. The weather line
and `MediaMiniBar` have their own components. The Body/Money/Fleet/Lists/Recordings panes summarise
screens that exist - what moves is the summary and the tap target, and nothing is lost if a pane is
dropped instead. **State which ones survive to HOME and which are dropped; do not let a pane vanish
by being forgotten during a move.**

## Verification

- `compileDebugKotlin`, then the full suite. Read totals from the JUnit XML under
  `app/build/test-results/`, never the console summary.
- `ui/MetersScreenTest.kt`'s 14 tests still run, against the functions in their new home, with the
  same assertions. A count before and after in the ticket's report.
- **A test that fails if the Ask panel's hands path disappears again.** The current arrangement had no
  such test, which is why one grep was all that stood between this and an ADR violation. Assert that
  the composable which owns the picker exists and calls the shared runner - or, if that is not
  reachable from a Robolectric test, say so in words and name what was done instead. Do not report
  this step silently unmet (L11).
- On the phone (ticket 08 may absorb this): build a generated view from the picker in its new home and
  see it render.

## Assumptions to carry into the report

The single-call-site claims are `traced` by grep. The claim that moving `buildMeterBreaches` preserves
behaviour is `reasoned` until the tests run.
