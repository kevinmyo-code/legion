---
map: hands-and-senses
ticket: 08
title: "Sitrep: a configurable skill, not a feed"
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - 4 calls; renamed to SITREP"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Sitrep: a configurable skill, not a feed

> **Filename kept as `08-morning-brief.md` on purpose.** The feature was renamed to
> "sitrep" on 2026-08-21 (see the resolution); renaming the file would break every
> inbound link from tickets 09, 18, 19 and 21 for no gain. The NAME is sitrep
> everywhere it is spoken, configured, or coded.

## Question

A brief composed from modules Kevin toggles and configures: calendar (built), weather (built),
fleet status (maintenance due, open DTCs, recalls - built as reads), ledger anomalies, pantry
lows, and news from Kevin's AI newsletters via the existing Gmail tool (settled decision 4;
`GmailToolLogic` already passes a `q` query through, so `from:(...) newer_than:1d` is nearly
free). The new work is a module registry, per-module config, one composed summarization on
Kevin's key, and a delivery surface. Decide:

1. **Delivery, and the compulsion line.** Pull-only ("morning brief" spoken/tapped), or one
   scheduled notification at a time Kevin sets? A single user-scheduled digest serves the user; a
   streak or a "you missed yesterday" serves retention and is banned. Where exactly is the line -
   is a notification that says more than "brief ready" already a raise?
2. **The google-account boundary.** That map ruled "any background or proactive Gmail fetch" out
   of scope, returnable only as a fresh effort - this is that effort, for the newsletter module
   only. If the brief is pull-only, the fetch happens on demand and the old rule survives intact;
   if scheduled, a background fetch exists. Decide with eyes open and record which way the
   google-account decision is being amended, if at all.
3. **Module registry shape.** Config lives where (Room, DataStore, synced to Drive appDataFolder
   like other settings)? Newsletter sender list curated how?
4. **Composition.** One Flash sub-agent call over module outputs, or each module pre-formats
   deterministically and the LLM only summarizes the news module? A model choosing what to omit
   from fleet/ledger facts is a model deciding what Kevin does not hear (the briefing precedent
   from google-account ticket 05) - deterministic sections, LLM only where the source is prose?
5. **Read-through.** Newsletter bodies: read, summarized, dropped. Nothing stored, nothing in
   CompanionMemory. Confirm the mail rule holds for the brief.
6. **Tool budget.** Is the brief a tool at all, or a surface outside the live session that CAN be
   asked for by voice?

## Resolution - 2026-08-21 (Kevin, 4 calls)

### 0. It is not a "morning brief" any more. It is a SITREP.

Kevin's call, unprompted by the question asked: *"change the name from mornig brief to just
something like a sitrep or a status report."*

**The name was about to become a lie.** Call 1 made it askable at any hour as well as scheduled, and
a thing called a "morning brief" that you request at four in the afternoon is misdescribing itself.
`sitrep` is time-agnostic, fits the register (dry, competent, faintly military - the Alfred band),
and survives being asked for at 2am.

Everywhere: the tool is `get_sitrep`, the setting is the sitrep schedule, the spoken word is
"sitrep". **"Morning brief" is retired as a name** - anything still using it is stale, including this
ticket's own filename, which is left alone because renaming a resolved ticket breaks every link into
it.

### 1. Delivery: BOTH scheduled and askable

Scheduled at a time Kevin sets, and requestable by voice whenever. This fills the **Digest**
category, which has shipped switched off saying "Nothing uses this yet" since the proactive build -
so everything already built applies to it for free: the master kill switch, quiet hours, the daily
cap, the raise history, decline suppression.

**The compulsion line, stated exactly.** A digest at a time Kevin chose serves Kevin. What is banned,
permanently and not re-openable: "you missed yesterday's", a streak, a count of unread sitreps, any
copy that references his absence or his engagement. CLAUDE.md §7's compulsion test applies unchanged
- clause (c) forbids referencing absence, and a sitrep is the single most tempting place to break it.

### 2. The google-account amendment, recorded rather than absorbed

The google-account map ruled **any background or proactive Gmail fetch out of scope**, returnable
only as a fresh effort. This is that effort, and the scheduled sitrep **does** cross that line: the
newsletter module fetches mail on a timer with nobody asking.

**Amended, narrowly, and this is the whole extent of it:** a Gmail fetch may run unattended ONLY as
part of a sitrep the user scheduled, ONLY over the newsletter sender list, and ONLY with the
read-through rule of call 4 intact. Any other background mail fetch remains out of scope and needs
its own decision. Written here because a rule quietly widened is a rule nobody can rely on.

### 3. Composition: deterministic sections, LLM only for the news

Fleet, ledger, calendar and pantry facts are already exact, and **a model choosing what to omit from
them is a model deciding what Kevin does not hear** - the briefing precedent the google-account map
already ruled against. Those sections are formatted deterministically, reusing `advisor/digest/`'s
builders and `DigestText`'s vocabulary (`[proven]`/`[reported]`, `(estimate)`, and *"not logged,
never 0"*).

One Flash sub-agent call, over the **news module only**, because newsletter bodies are genuinely
prose and genuinely need summarizing. Same split as the reconciliation gate: the app computes, the
model interprets.

### 4. Read-through holds: read, summarized, dropped

No Room row, nothing in `CompanionMemory`, no `EpisodicTurn`. The sitrep is composed and the source
text is gone. Storing even the SUMMARY was rejected: it would persist model-written prose about
Kevin's mail, which is the thing the mail rule exists to prevent.

Consequence, accepted: **"what did yesterday's sitrep say" cannot be answered.** A sitrep is a
snapshot of now, not a feed with a history.

### 5. Notification carries a short summary

Two or three lines readable without opening anything - the point of a brief is not having to go
somewhere. It **is** a raise by the proactive map's definition, so it goes through
`ProactiveBus.speakIfAllowed` under the Digest category like everything else, and delivery follows
ticket 06: spoken when the screen is on and no meeting is running, notification otherwise, never
both.

A bare "brief ready" was rejected as the *less* honest option: a notification whose only function is
to make you open the app is closer to an engagement mechanic than one that just tells you the thing.

### 6. Module registry and tool budget

- **Config in Room**, alongside the proactive switches, for the same reason: it is a setting about
  Kevin, not about a handset. Per-module on/off plus the newsletter sender list.
- **One tool**, `get_sitrep`, with an optional module filter. Not six.
- The newsletter sender list is curated by Kevin, by hand. Nothing infers which senders are
  newsletters - guessing wrong there means silently dropping mail he wanted or summarizing mail he
  did not.

`GmailToolLogic` already passes a `q` through, so `from:(...) newer_than:1d` is nearly free.

## What this leaves for the build

- Which modules ship first (calendar, weather and fleet are all built as reads today; ledger
  anomalies and pantry lows are not).
- The scheduler itself. `notes/AlarmScheduler`'s "re-arm on fire, never setRepeating" is the
  precedent, and ticket 07 of the proactive map established what a background process may actually
  do on this phone.
- Where the schedule time is set on screen.
