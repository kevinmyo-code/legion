---
status: accepted
decided: 2026-10-30
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-10-30 - Feed apps blocked outside Matrix mode, with a 15 minute pass (Kevin)]]"
tags: [adr]
---

# 66. Feed apps can be blocked outside Matrix mode, with a 15 minute pass

## Standing

**One switch, "Block feed apps always", blocks feed apps in normal mode too.** It lives on the Matrix
mode settings screen (Settings > Matrix mode, reachable without turning Matrix mode on), is stored under
its own key in the Matrix mode preferences file, not in the mode's own state, and is **off by default**
(clone-and-run: a stranger's build blocks nothing until they choose it). Kevin turns it on once.

**The same ladder decides.** What is blocked is ADR 0062's 2026-10-24 rule, unchanged: the seed list of
known feed apps, Android's Social, Video and News tags, and his manual flips. The bounce, the settings
list and the All apps drawer all read `BlockModel`; nothing keeps a second list.

**The bounce runs when Matrix mode is on OR the switch is.** Matrix behaviour is unchanged. Outside
Matrix mode the bounce screen says "<App> is blocked", with "Go back" as the primary button and "Ask for a
15 min pass" below it.

**The way in is a 15 minute pass, and it is friction, not a lock.** The same 60 second wait as leaving
Matrix mode, on the wall clock from the tap and persisted, then the typed sentence "I am choosing to open
<app> for 15 minutes" (case-insensitive, trimmed). It opens that ONE app for 15 minutes; then the app is
blocked again. Both instants, requested and granted-until, are wall-clock and persisted per package, so a
process kill or a screen lock cannot reset or extend them. The wait is clamped like the Matrix leave's,
and a clock set backwards cannot stretch a pass past 15 minutes. One pass at a time per app; asking again
while waiting keeps the original instant.

**No pass in Matrix mode.** While Matrix mode is on, a pass is refused and a running one does not count.
Matrix leave stays the only way out of Matrix mode.

**A pass ending with the app still open sends it home.** The bounce service sees only window-state
changes and may not read window content (ADR 0062). It remembers the last foreground app from the
events it already gets and checks when the pass ends; it bounces only if that app is still the one in
front. The next window change catches anything the timed check misses.

**Only the block comes out of Matrix mode.** The streak, the held notifications, the digest, chosen
people, call screening and code rain stay Matrix-only.

**The browser stays open, and no URL is blocked in LEGION.** Chrome and anything that answers a web link
are exempt in `BlockModel` (ADR 0062, 2026-10-24), so a feed's website is one tap away. Kevin closes that
hole himself with phone-wide Private DNS (NextDNS); LEGION does no in-app URL blocking.

## Context

Kevin, 2026-10-30: Matrix mode stops the feeds only while it is on, and the feeds are the point. He wants
them blocked all the time, with a way in that costs a minute and a sentence rather than a trip into
Settings to unblock the app (which then stays unblocked).

## Consequences

- `BlockModel.decide` gains a worded rung, `PASS` ("15 min pass, N min left"), after SYSTEM and before
  a manual block, applied only outside Matrix mode. A settings tap on a passed app changes what the app
  is after the pass, not the pass.
- An unfinished request lapses 10 minutes after the tap, and "Go back" drops it, so a pass is never one
  sentence away hours later.
- The accessibility service's description now names both switches.
- **2026-10-30, Kevin could not find the switch:** it also has its own "Feed blocker" row at the top
  level of Settings (same preference as the Matrix mode screen's), and the accessibility service is
  labelled "LEGION blocker" rather than "Matrix mode". The service class and component name are
  unchanged, so an install over the top keeps it enabled.
- The pass's expiry check runs on a `Handler`, which runs on uptime: in deep sleep it fires late, and the
  next window change is the backstop.
- Compulsion test (CLAUDE.md section 7): nothing here is an unsolicited raise; the bounce answers an app
  Kevin opened, and the switch silences all of it in one tap.
