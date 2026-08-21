---
map: hands-and-senses
ticket: 22
title: "Build the sitrep"
type: build
status: built
status-detail: "2026-08-21 - built; owes a run on the phone"
blockers: ["08"]
blocked-by: ["[[08-morning-brief]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Build the sitrep

## Why this ticket exists at all

**Because the sitrep disappeared.** [Ticket 08](08-morning-brief.md) was resolved on 2026-08-21 and
immediately dropped off `docs/index.html`, which lists only OPEN tickets - so a fully-decided,
entirely unbuilt feature became invisible. Kevin noticed within the hour: *"where is the daily brief?
dont see it on pending work anymore. was it built?"*

It was not. Nothing is written.

**The general failure, worth naming: a resolved decision ticket looks identical to finished work on
the board.** Proactive mode only escaped it because the build happened the same night. Any decision
that authorises code needs a build ticket created at resolution time, or the work silently leaves the
map.

## What to build

All of it is decided - see [ticket 08's resolution](08-morning-brief.md) for the reasoning behind
each. Nothing here is re-openable without Kevin.

1. **`get_sitrep` - one tool, optional module filter.** Not six tools. The name is `sitrep`
   everywhere: spoken, configured, coded. "Morning brief" is retired as a name (it lied the moment
   the sitrep became askable at any hour); only the ticket FILENAME keeps it, to avoid breaking
   inbound links.
2. **Module registry in Room**, beside the proactive switches, for the same reason: it is a setting
   about Kevin, not a handset. Per-module on/off, plus the newsletter sender list.
3. **Deterministic sections; the LLM summarises the NEWS MODULE ONLY.** Reuse `advisor/digest/`'s
   builders and `DigestText`'s vocabulary (`[proven]`/`[reported]`, `(estimate)`, and *"not logged,
   never 0"*). A model choosing what to omit from fleet or ledger facts is a model deciding what
   Kevin does not hear.
4. **Scheduled delivery under the Digest category**, at a time Kevin sets - plus askable by voice at
   any hour. This is Digest's first content; the row currently reads *"Nothing uses this yet."* It
   inherits the master kill switch, quiet hours, the daily cap and decline suppression for free.
5. **The notification carries two or three lines**, not "brief ready". Delivered through
   `ProactiveBus.speakIfAllowed`, so ticket 06's rule applies unchanged: spoken when the screen is on
   and no meeting is running, notification otherwise, never both.
6. **Read-through: read, summarised, dropped.** No Room row, nothing in `CompanionMemory`, no
   `EpisodicTurn` - not even the summary. Consequence accepted: *"what did yesterday's sitrep say"*
   cannot be answered, and the sitrep must say so rather than reconstructing one.
7. **`ProactiveCategory.DIGEST.hasContent` flips to `true`** in the same commit that lands the first
   scheduled sitrep, and not before - that flag is what the settings row reads to decide whether to
   say "Nothing uses this yet."

## Decide while building (small, not worth their own tickets)

- **Which modules ship first.** Calendar, weather and fleet are already built as reads; ledger
  anomalies and pantry lows are not, and inventing them here would be a second effort.
- **Where the schedule time is set** on the Setup screen.
- **The scheduler.** `notes/AlarmScheduler`'s "re-arm on fire, never `setRepeating`" is the
  precedent. `.scratch/proactive-mode/issues/07-scheduling-research.md` establishes what a background
  process may actually do on this phone - read it before choosing, and note its finding that a
  Samsung-restricted app gets one alarm a day.

## The amendment this build carries

The google-account map banned **any background or proactive Gmail fetch**. The scheduled sitrep
crosses that line, and [ticket 08](08-morning-brief.md) amends it narrowly: an unattended fetch runs
ONLY inside a sitrep Kevin scheduled, ONLY over the newsletter sender list, with read-through intact.
**Nothing else.** If this build finds itself fetching mail anywhere else, that is out of scope and
needs its own decision.

## Verification

- Suite green; `get_sitrep` covered where it is pure (section formatting, module filtering).
- **On the phone:** a scheduled sitrep actually fires at the set time, survives a reboot, and is
  silenced by the master switch. The last one is not optional - Digest is a category, and settled
  decision 2 says the master has no exemptions.

## Built - 2026-08-21

Green at 1840 tests. `SitrepBuilder`, `SitrepScheduler`, `SitrepAlarmReceiver`, `SitrepSettings`,
`SitrepModule`, Room v29 (`sitrep_modules` + `sitrep_schedule`), `get_sitrep`, two settings rows,
`BootReceiver` re-arm, and `DIGEST.hasContent` flipped in the same change.

### A read-through leak that the build introduced, found in review

The executing agent flagged one call as `reasoned` rather than checked: whether `get_sitrep` needed
adding to `LiveToolbox.EPISODIC_EXCLUDED_TOOLS`. It decided no. **That was wrong, and it had two
paths, not one.**

The news module summarises Gmail bodies, so **the summary is mail content one derivation removed** -
and [ticket 08](08-morning-brief.md) call 4 rejected "store the summary, drop the bodies" in exactly
those words.

1. **Asking by voice** would have written the sitrep into `episodic_turns`, and from there into
   `CompanionMemory` via the consolidation pass. Fixed by adding `get_sitrep` to the excluded set.
2. **The SCHEDULED sitrep** was worse, because the tool-keyed gate cannot see it at all - no tool
   runs. Fixed with `ProactiveRaise.carriesReadThroughContent`, carried through the bus and the
   session controller onto the same flag the mail tools set.

**Path 2 was live and a green suite would never have caught it.** An unanswered raise happens to be
safe, because `captureEpisodicTurn` returns early on a blank driver turn - so the leak only opened
the moment Kevin replied to a spoken sitrep. A narrow accident is not a guarantee.

The existing exact-set drift guard failed on the addition and **that is the guard working**: it
forced the fourth name to be a decision written down here, rather than absorbed silently.

### Owed on the phone

- The alarm fires at the set time, survives a reboot, and is silenced by the master switch. The last
  is not optional - Digest is a category and settled decision 2 says the master has no exemptions.
- The two new settings rows render (no Compose preview was run - the executing agent flagged this).
- No migration test for v28→v29, matching v27/v28's posture; the SQL was diffed against `29.json`.
