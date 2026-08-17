# BUILD: ship pass - the destination gate

Type: task
Status: open
Blocked by: 13, 14, 15

## Question

Nothing to decide. **This map's destination is SHIPPED. This ticket is the gate.**

1. **Correct the comments that ticket 04 falsified.** `data/local/ListItem.kt`'s doc comment cites
   `notes-lists-calendar` charting decision 6 ("a calendar event is the same entity as a list item")
   and that is no longer true - `startsAt` now means "remind me at T", not "this is a calendar
   event". Check `ItemList.kt`, `notes/NotesController.kt` and `ui/notes/` for the same claim.
   Commit `0088e79` exists because this repo has already shipped comments that outlived the thing
   they described.
2. **Apply the `CLAUDE.md` §7 guardrail** proposed in ticket 07 point 6 - third-party content is
   read-through only - **in the same commit as the build**, per `memory/MEMORY.md`'s own rule, and
   only if Kevin accepts the wording. He has not been asked yet.
3. **Add the feature-add checklist line** if §7 gains the rule.
4. **Update `memory/MEMORY.md`** and file the remaining decisions to `memory/library/decisions.md`.
5. **Full suite green**, `compileDebugKotlin` and `testDebugUnitTest`, before the last commit.

## Verification

Every verification step from tickets 12-15 accounted for as **done / deferred-with-a-named-follow-up
/ impossible-and-why**. Never silently carried. CLAUDE.md §8 (L11) - a ticket's verification steps
are gates, not notes, and this project has already shipped a bug straight through a step that was
reported unmet and read as a footnote.

The named ones, restated so they cannot be lost:
- **Ticket 14's spike**: a provider-inserted event actually reaches Google. Load-bearing inference.
- **Ticket 13's render**: agenda with a Google event and an overdue reminder in the same window.
- **Ticket 15's test**: nothing mail-shaped reaches the episodic log.
- **Ticket 12's device check**: revoke Drive access and confirm the app says so.
- Install by hash, per `memory/`: "Success" from `pm install` can install a different APK.

## Verification 2026-08-16 - NOT BUILT, and here is exactly what is left

Checked against the tree while closing the rest of this effort. Every item below is `traced`.
**This is the only google-account ticket with real work remaining** - 15, 17 and 19 were verified
built and closed the same day.

1. **The falsified comments are still in the tree.** Ticket 04 killed the "a calendar event is the
   same entity as a list item" claim, but it still reads that way at `data/local/ListItem.kt:10-11`
   and `:56`, and at `data/local/ItemList.kt:8-9`. `notes/NotesController.kt` and `ui/notes/` are
   clean - only these three sites lie.
2. **CLAUDE.md §7 has no third-party read-through guardrail.** §7 spans `CLAUDE.md:238-268`; there
   is no such bullet. `library/decisions.md:2370` still records it as "NOT yet applied and not yet
   put to Kevin" - so **this one needs Kevin's wording before it can be written.**
3. **The feature-add checklist has no corresponding line** (`CLAUDE.md:270-285`).
4. **`memory/MEMORY.md` is stale in the other direction**: it lists console tickets 09 and 11 as
   "still needing Kevin" when both are `Status: resolved` and `decisions.md:2379-2405` records the
   consent screen in production and the scope granted on-device.
5. **No `decisions.md` build entry exists for tickets 12-15/17/19**, and no ship-pass accounting of
   their verification steps. Ticket 14's spike (does a provider-inserted event reach Google?) has no
   recorded outcome; ticket 13's render and ticket 12's revoke check likewise.

**Item 2 is the gate.** It needs a decision from Kevin, not code, and the rest is bookkeeping that
should not be done piecemeal around it.
