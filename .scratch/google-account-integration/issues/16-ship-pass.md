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
