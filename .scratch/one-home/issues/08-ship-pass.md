---
map: one-home
ticket: "08"
title: "Ship pass: the shell on the A25, and every reasoned claim settled"
type: task
status: open
status-detail: ""
blockers: ["03b", "05", "07"]
blocked-by: ["[[03b-delete-meters]]", "[[05-the-advisor-writes-a-checklist]]", "[[07-the-news-surface]]"]
open-blockers: 3
ready: false
tags: [ticket]
---

# Ship pass

The only ticket on this map allowed to say it is done.

## Why this exists as its own ticket

MEMORY.md, the week of 2026-09-08: *"Roughly a dozen defects found by running it on hardware or
querying live data; a green suite caught none of them."* Three of the four aspects verified had
missing PULL paths - writes went up and nothing came down, with 3438 tests passing.

This map is entirely UI. A passing Robolectric suite says a composable did not throw. It does not say
the tab row looks right, that an alarm opens the renamed route, or that a checklist comes back the
next morning.

## The list, and each item is a device run

1. **Cold start lands on HOME.** The tab row is whatever ticket 01 decided, and it looks deliberate
   rather than like a row with something missing from it.
2. **Every drill-down is still reachable** - money, body, fleet, pantry, checklists - from HOME, by
   the affordance 01 named. Tap each one.
3. **Fire a reminder and confirm it opens the right screen.** This is the failure mode ticket 03
   flags: a stale route string compiles fine and fails at 6am. Test it deliberately, do not wait for
   an alarm to happen.
4. **Build a generated view from the Ask panel in its new home** and see it render. This is ADR
   0035's compliance, and ticket 02 is the only thing standing between it and a voice-only tool.
5. **Tap the newsletters card and get a real summary of real mail.** Command-center 12 has owed this
   since 2026-08-22 and this map inherited it.
6. **Ask the advisor for a workout list, accept it, tick an item.** Then **the next day**: confirm it
   returns with ticks cleared and yesterday's result still readable. Requires a real overnight or a
   deliberate clock change - and if a clock change is used, say so, because it is not the same test.
7. **Ask for a workout list twice** and confirm you do not get two lists (ticket 05, bullet 2).
8. **The news surface's three failure sentences, seen.** At minimum: airplane mode for unreachable.

## The accounting this ticket owes

Per CLAUDE.md §8 (L11), a ticket's verification steps are gates, not notes. **Before this map is
called done, every verification step in tickets 02, 03, 05 and 07 is accounted for as done /
deferred-with-a-named-follow-up / impossible-and-why.** Not silently carried.

The tags that matter (§8 improvement loop): anything reported `reasoned` on this map gets settled to
`on-device` here, or gets a follow-up ticket. The single-call-site claims in ticket 02 were
established by grep and were `traced`; the compiler settles those, and this pass settles the rest.

## Then, and only then

- `python tools/obsidian_sync.py` and `python tools/pending_wiki.py`, committed.
- **Push `dev`.** A correct generated file in an unpushed commit is a stale public page - on
  2026-08-21 the wiki looked three weeks old to Kevin while the local file was perfectly current.
- Map status to closed, and a line in `library/decisions.md` for what ticket 01, 04 and 06 ruled.
