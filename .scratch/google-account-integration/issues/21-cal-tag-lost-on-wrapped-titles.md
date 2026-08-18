---
map: google-account-integration
ticket: 21
title: "BUILD: the CAL tag vanishes when an event title wraps"
type: task
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# BUILD: the CAL tag vanishes when an event title wraps

## Answer

**Fixed and verified on the device 2026-08-13.**

**Cause**, read rather than guessed: in `ui/notes/NotesRows.kt`'s `InboxRow`, the title `Text` sat in
the inner `Row` with **no weight**. An unweighted child claims what it wants, so a long title took
the full row width and the `DeckTag` was measured out of existence. Short titles left room and kept
their tag, which is why the first render - four events, all short names - looked correct.

**Fix**: `Modifier.weight(1f, fill = false)` on the title `Text`. The unweighted tag is measured
first and the title wraps into what remains. `fill = false` so a short title still hugs its tag
rather than stranding it at the far edge. Commented in place as load-bearing, not tidying.

**`ui/TodayScreen.kt`'s AGENDA pane does NOT have this bug** - `traced`, not assumed. It renders
through `DeckRow`, whose label already carries `Modifier.weight(1f)` with the tag in a separate
slot, so the tag can never be squeezed out there. No change made.

**Verified on-device**, which is the only way this class of defect is visible: "Astros vs. Los
Angeles Angels baseball game" and "F-150 recall appointment at AutoNation Ford Katy" both now render
`CAL` on their wrapped rows, while the wrapped LOCAL item ("Pay school fees for the fall semester")
keeps its checkbox and REMOVE and stays tagless. 727 tests green - **and green before the fix too**,
which is the point: no unit test could have caught this.

## Question

Nothing to decide. Seen on-device 2026-08-13, in the Notes stream, immediately after
[ticket 17](17-read-all-calendars.md) widened the calendar set and more real events appeared.

**The defect.** A Google event whose title fits one line renders its `CAL` tag correctly
("Mara's bday `CAL`", "Recall appointment for the Outlander `CAL`"). A Google event whose title
**wraps to two lines shows no tag at all** - observed on "Astros vs. Los Angeles Angels baseball
game" and "F-150 recall appointment at AutoNation Ford Katy". Both are Google events: neither has a
checkbox or a REMOVE action.

**Why it matters, and it is not cosmetic.** [Ticket 08](08-deck-surface.md) point 3 requires a
Google event to be distinguishable from a LEGION reminder **in words, not by colour alone** - and by
extension not by the mere *absence* of a control. On a wrapped row the only remaining signal is that
nothing is tickable, which is exactly the inference the ticket refused to rely on. Kevin cannot tell
at a glance which rows LEGION will nag him about and which it will not, and that distinction is the
visible half of [ticket 04](04-what-happens-to-local-timed-items.md)'s whole design.

It was invisible until now only because the four events in the first render all had short titles.

**The fix.** The tag must survive wrapping. `ui/notes/NotesRows.kt`'s `InboxRow` places the tag
inline after the title text; a long title consumes the row and the tag is pushed out. Give the tag
its own guaranteed slot rather than letting it compete for line width - and check the same treatment
on `ui/TodayScreen.kt`'s AGENDA pane, which shares the tag convention and will have the same bug for
the same reason.

## Verification

On the device, with a real Google event whose title wraps: the tag renders. Check both the Notes
stream and Today. **Render it, do not reason about it** - CLAUDE.md §8 (L11), and this bug existed
in a build whose unit tests were entirely green.
