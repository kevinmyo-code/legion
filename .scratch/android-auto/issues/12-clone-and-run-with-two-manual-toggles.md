---
map: android-auto
ticket: 12
title: "Does clone-and-run survive two manual toggles?"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Does clone-and-run survive two manual toggles?

**Unblocked 2026-08-13, and it got worse.** Ticket 02: developer mode explicitly covers media apps,
but **whether the unknown-sources switch survives an Android Auto update is undocumented** - the
question this ticket was written to answer has no documented answer, only an on-unit experiment.
Ticket 01: the same developer option verbatim **"doesn't apply to apps built using the Android for
Cars App Library"**, and the in-call surface appears to need that library's CALLING category, which
is Internal/Closed-Play-track only. **So sideloading may not merely be inconvenient here - it may
structurally exclude LEGION from the car's in-call surface.** That is a third independent pressure on
clone-and-run, not a second.

## Question

CLAUDE.md §2 makes clone-and-run a **hard requirement**: a stranger clones, sideloads, signs in, and
it works. The car surface needs Android Auto developer mode and an unknown-sources toggle before
LEGION appears at all (ticket 02 confirms exactly which, and whether they survive an update). That is
not "clone and it works" - it is "clone, then do two things in a settings screen most people have
never opened".

Decide whether that violates the rule or merely documents against it.

1. **Is a documented manual setup step a clone-and-run violation?** Precedent cuts both ways. The
   Gemini BYO key already requires the user to paste one. Drive already requires signing in. Those
   are in-app flows, though, and this is a toggle inside *another app*.
2. **Does the toggle reset?** Ticket 02 answers this. If Android Auto's unknown-sources setting is
   wiped by an update, the "setup step" is a recurring chore and the honest answer to (1) changes.
3. **How is it surfaced?** README only, an in-app setup screen for the car surface, or a first-run
   check that notices LEGION is not visible to Android Auto and says what to do. LEGION currently has
   **no onboarding screen at all** (CLAUDE.md §10), so there may be nowhere to put it yet.
4. **Does this need a CLAUDE.md amendment?** If Kevin rules that a documented external toggle is
   acceptable, §2's clone-and-run row should say so, or the next session will read the rule and think
   the car surface broke it. If it is filed as a decision, it must land in
   `memory/library/decisions.md` and CLAUDE.md **in the same commit** (`memory/MEMORY.md`'s rule).
5. **The unresolved OAuth finding sits next to this.** CLAUDE.md §2 already records that Drive's
   Android OAuth client is keyed to package + SHA-1, so a stranger's own build fails authorization -
   an open, unresolved threat to clone-and-run predating this map. Do not re-litigate it here, but
   note whether the car surface makes the same rule fail in a second, independent way, because two
   failures may be Kevin's cue to restate the rule rather than keep patching it.
