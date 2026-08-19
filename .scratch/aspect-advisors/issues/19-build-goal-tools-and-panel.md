---
map: aspect-advisors
ticket: 19
title: "Build: goal voice tools and the GOALS panel"
type: task
status: resolved
status-detail: ""
blockers: ["13"]
blocked-by: ["[[13-build-goal-store]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: goal voice tools and the GOALS panel

## Question

Goals are set by voice AND screen, matching how targets already work.

- **Voice**: `set_goal`, `list_goals`, `close_goal`. Fold them into the aspect buckets rather
  than adding three standalone declarations if ticket 11's outcome adopts the lean-toolbox
  discover/dispatch shape; otherwise keep the count minimal and justify it on this ticket.
- **Screen**: a GOALS panel per aspect surface, read-and-edit, in the MILSPEC deck language
  (`.scratch/cyberdeck-ui/issues/01-deck-design-language.md`). Consult
  `frontend-design:frontend-design` and the vendored Compose skills.

Open design question this ticket must answer, currently fog on the map: **is a goal's revision
trail ever shown?** A goal that quietly got easier is exactly what the trail exists to make
visible, but a panel that renders every superseded row is noise. Decide, and say why.

Prose goals and measurable goals must be visually distinguishable without relying on colour
alone - a goal with no number is not a broken goal.

Verification: **render the previews before building on them** (CLAUDE.md §8 L11 - ticket 07 of
the cyberdeck effort said exactly this, the step was skipped, and every screen drew body text in
quarantine red). Then install and look at it on the phone.

## Build report

Built 2026-08-13. New: `goals/GoalController.kt` (the one read/write path both surfaces call),
`ui/goals/GoalsPanel.kt` (+ `GoalEditDialog`, 3 previews), `GoalControllerTest` (10 tests).
Edited: `service/LiveToolbox.kt` (+3 tools, ~71 -> ~74) and one `GoalsPanel(aspect = ...)` call
site each in `BodyScreen`/`NotesScreen`/`LedgerScreen`/`FleetScreen`. Nothing under `advisor/`.

### The open question, answered: the revision trail is NEVER rendered on the panel
`GoalsPanel` only ever calls `currentGoals`, never `history`. Rationale: the trail exists for the
ADVISOR's digest - a coaching audience that benefits from seeing a goal that quietly got easier.
Kevin looking at his own goal already knows its history, and rendering every superseded row is
precisely the noise the ticket warned about. A "view history" affordance would be a new build, not
a default.

Prose vs measurable is distinguished by the literal words `TARGET` / `PROSE` in a `DeckTag`, both
`OUTLINE_MUTED` - **same colour, the distinction lives entirely in the word**, extending §4 rule
5's posture past estimates.

`metricKey` is deliberately NOT editable from the panel: it is a code-meaningful key the digest
matches against, and a typo typed into a screen field would silently break progress math with no
validation surface to catch it. Voice is its only writer; the edit dialog carries it forward.

### UNMET GATE - Compose previews were NOT rendered (CLAUDE.md §8 L11)
Three previews were written (mixed rows, empty state, 320dp narrow) exercising `GoalRow` as pure
data-in. **They were never rendered - no Compose preview renderer exists in the agent's
environment, and none exists in the orchestrator's either.** This is the same gap
`ThemePreview.kt` already documents and defers.

Accounted for as **deferred-with-a-named-follow-up**, not silently carried: the
[Ship pass](20-ship-pass.md) must look at the GOALS panel on the device. The claim that the
2026-08-02 `contentColorFor` colour-collision class of bug cannot recur here - because the panel
composes only from `DeckPane`/`DeckRow`/`DeckTag` primitives rather than raw tokens - is
`reasoned`, NOT rendered. That is exactly the shape of claim that failed last time.

### Known limitation worth naming: prose goals cannot be REVISED by voice
Voice `set_goal` matches an existing goal for revision on `metricKey` only. Same aspect + same
`metricKey` on an active goal means a revision; **anything else, including every prose-only
restatement, mints a NEW goal.** Statement-text similarity matching was considered and rejected,
rightly - it would silently merge two goals that happen to share a few words.

The consequence is real and should be understood rather than discovered: the revision trail was
justified by "a goal that quietly got easier stays visible", and for a PROSE goal restated by
voice, that does not happen - Kevin gets two goals instead of a trail. The panel's edit path
passes the exact row and does revise correctly, so the capability exists, just not by voice.
Revisit if prose goals start accumulating duplicates in real use.

### A claim checked and found unfounded
The agent reported minting `lineageId` via `UUID.randomUUID().leastSignificantBits` as "a
deliberate deviation from `Goal`'s doc comment", which says a first row "typically" reuses its own
id. **No such text exists in `Goal.kt`** - grepped for it directly. The approach is fine and
contradicts nothing; the justification was describing a document that does not say that.

### Verification (orchestrator-confirmed)
`compileDebugKotlin -Pnokey` BUILD SUCCESSFUL; `testDebugUnitTest` **874 tests / 0 failures** from
JUnit XML on a forced re-run. `GoalControllerTest` 10/10: prose-only round-trip, measurable
round-trip, same-`metricKey` restatement revises (shares lineage, sets `supersedesId`, only the
newest reads current, nothing deleted), no-op save writes no row, close excludes from current,
close-by-text handles unique/NotFound/Ambiguous, cross-aspect read.
