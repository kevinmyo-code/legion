---
map: one-home
ticket: "07"
title: "The news surface: sources, refresh on demand, and three distinct failure sentences"
type: build
status: open
status-detail: ""
blockers: ["06"]
blocked-by: ["[[06-news-sources-and-what-may-be-kept]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# The news surface

**Kevin, 2026-09-10:** *"A news feed page > pulled from my gmail or just rss feeds."*

Scope depends entirely on ticket 06. If 06 says "Gmail now, RSS later" this is a small ticket that
promotes an existing card. If 06 admits RSS this is a new fetcher, a subscription list and a storage
posture. **Do not start until 06 has answered**, because the storage question decides the data layer
and it cannot be retrofitted honestly.

## Fixed regardless of what 06 decides

### Refresh is a tap, never a poll

`NewsDigestCard`'s posture, carried forward: the tap is the demand. No background fetch, no
auto-refresh on open, and a message cap on what is fetched (`NEWS_MESSAGE_CAP` is the precedent).

This is not only about cost. An unsolicited fetch that surfaces something is one step from an
unsolicited notification, and CLAUDE.md §7's compulsion test governs any raise the app makes on its
own. A page that updates only when asked cannot become a re-engagement mechanism by accident.

### Empty and unreadable are different sentences

The rule §1 states about calendars applies verbatim here: a `ContentResolver` returns an empty list
for a refused permission and for a clear day, and rendering the first as the second tells the user
they are free when the app cannot see. Command-center 12 already enumerates the three for Gmail:

- *no newsletters in the last day*
- *could not reach Gmail*
- *found N, summary failed*

**Each must be a distinct sentence with its own test.** RSS, if in scope, needs its own set: no items,
feed unreachable, feed returned something unparseable. A feed that 404s and a feed that is quiet are
not the same fact.

### Staleness is shown, in words

Whatever is on screen carries when it was fetched. If 06 rules "read-through", there is nothing on
screen until a tap, and the absence of content is itself honest. If 06 rules "persisted", **a stored
item rendered without its age is a claim about now made from something old.**

### Nothing numeric escapes

A headline is prose. Nothing from this surface may become a ledger row, a macro, a maintenance date
or any asserted figure. If a summary mentions a number, it is the article's number and is rendered as
such - never adopted into an aspect.

## If 06 admits RSS

- **Subscriptions are Kevin's own data**: a table of feed URLs he typed, storable and syncable under
  any reading of §7. Adding and removing one is a hands path.
- **Items follow 06's ruling exactly.** If persisted, they are excluded from episodic memory at the
  write site (the `EPISODIC_EXCLUDED_TOOLS` pattern) rather than by anyone remembering to exclude
  them - §7 is explicit that the guarantee is that it was never stored.
- **No new HTTP or XML stack without a ruling.** `EngineHttp` (Ktor + OkHttp) and
  kotlinx-serialization are what is in the build. `web-and-households`' map says a second HTTP or
  JSON stack is drift, and XML parsing has an Android platform answer. Adding a dependency is a
  decision, not a bump (CLAUDE.md §3).

## The address

From ticket 01. Under option A it is a pane on HOME or a drill-down; under option B it is the second
tab. **Do not create a tab on your own authority** - that is 01's call, and a page that arrives
needing a tab has decided 01 by the back door.

## Verification

- `compileDebugKotlin`, full suite, totals from the JUnit XML.
- One test per failure sentence, asserting they are distinct strings and that the empty case is not
  the unreachable case.
- If persisted: migration test, additive SQL verbatim, `exportSchema`, `SCHEMA_VERSION` bumped in
  lockstep (CLAUDE.md §5), and a test that a stored item never reaches episodic memory.
- **Never touch `GmailAuth.authorize` from a unit test** - it hangs Robolectric. Recorded hazard,
  carried from command-center 12.
- On the phone: a real tap producing a real summary of real mail, which command-center 12 still owes
  and which this ticket inherits rather than escapes.
