---
status: accepted
decided: 2026-11-10
decided-by: Kevin
source: "[[decisions#2026-11-10 - Proactive speech: no cap, speaks with the screen off, spoken briefs, emergency alarm (Kevin)]]"
tags: [adr]
---

# 82. Proactive speech has no daily cap, speaks with the screen off, briefs on waking and at home, and rings for an emergency

## Standing

**Unprompted speech (the proactive bus, ADR 0029) is governed by the master switch, the category
switches, quiet hours, the 24 h decline window, the speech gate and one delivery per raise. There is
no daily cap.** Four rulings by Kevin on 2026-11-10, standing together:

1. **No cap.** *"no cap on proactive spoken lines"*, *"remove cap"*. Proactive-mode decision 15
   (three spoken lines a day, Safety and Claude tasks outside it) is gone, with its constant, its
   refusal outcome and its count query. Nothing else in the gate moved.
2. **It may speak with the screen off.** *"can speak at screen off. unless i silent the phone
   ofc"*. The speech gate (`ProactiveDelivery.maySpeakAloud`) is now: **the phone is not silenced,
   and no calendar event is live.** Silenced is ringer silent or vibrate, or any Do Not Disturb
   filter; LEGION's own Quiet mode is both, so it counts. An unreadable calendar or ringer still
   means post, never speak. This replaces the screen-on half of proactive-mode decision 17.
3. **Alfred speaks the brief unprompted at wake-up and on arriving home** (Kevin's yes to "spoken
   brief at wake-up or arriving home"). Both are typed Digest raises through the bus, each with its
   own switch inside Digest, each stoppable in one instruction.
4. **An emergency rings.** *"also quiet hours, if any emergency in the area is noted, alarm me"*. A
   Safety raise may carry an `ALARM` urgency; inside quiet hours or on a silenced phone it rings on
   the clock's ring path instead of speaking.

The kill switch still has no exemptions (proactive-mode decision 2): the master and the raise's own
category switch are checked first for every raise, an emergency alarm included.

## Context

The cap was the map's only countable anti-compulsion guarantee, and it rationed lines Kevin wanted:
with the briefs and the Claude results speaking, three a day was spent by mid-morning. The screen-on
gate kept every line off a phone in a pocket, which is where an assistant that "keeps me on track"
mostly is. Ticket 32 (*"sitreps stay tap only or via voice activation only"*) and ADR 0079's
"wake-up is a plain ring" kept the brief tap-only; Kevin reversed both for these two moments.

## Decision

**Speech gate and delivery.** `ProactiveDelivery.route` is the one decision between spoken, posted
and rung, pure in `routeFor`. A raise is posted, never dropped, when the phone is silenced, a meeting
is live, quiet hours refuse it, or nothing is collecting the bus (the assistant service is not
running, so an emitted line would be lost). A "hold and speak at the next screen-on" option, if
Kevin wants one, is one more route value there; no raise site changes.

**The reply microphone.** With the screen off, playing a line needs only the running foreground
service and its network. Opening the microphone needs the service to hold the `microphone`
foreground type, which Android grants only to a service started while LEGION was on screen (a
boot-started one never has it, and its capture hears silence). The bus therefore opens the reply
mic only while the service holds that type; otherwise the line is spoken without listening.

**The wake-up brief** (`spokenbrief/WakeBrief.kt`). On a tap Dismiss of an ALARM on the ring screen,
after the first unlock, between 04:00 and 11:00, once per local day (a Brief me tap counts), it reads
`BriefController.forVoice`, the same controller Brief me's `get_sitrep` reads, and raises that text
as its facts. **It reads no mail** (`WakeBrief.READS_MAIL = false`): no Gmail call, no newsletter
line, and its prompt forbids the subject in words without implying mail was checked. Mail is read
only to answer something Kevin asked, and ticket 32 existed to stop an unasked fetch; his yes would
flip that one constant. Brief me and a spoken "sitrep" still read the newsletters. The turn stays
read-through because the engine's News section is web text. **This rule alone is exempt from quiet
hours** (a per-rule flag, `ProactiveRaise.exemptFromQuietHours`):
dismissing an alarm he set proves he is awake. It still honours a silenced phone, a live meeting
and every switch. Brief me stays as the replay. A timer, an emergency ring and a ring-out never
brief.

**The home brief** (`spokenbrief/HomeBrief.kt`). On arrival at the place labelled `home` (the label
`PlaceController` already gives "home", "house" and "where I live"), after a recorded departure (a
geofence EXIT or the GPS poll seeing him leave; geofences fire ENTER on registration, so a restart
at home is not an arrival), at most once per 6 h by the raise history. Facts: what is still on the
calendar today, tomorrow's first event, and what is waiting (reminders left for home, to-dos with no
date), read from the sitrep's own calendar store; unreadable is said as unreadable. Home's
reminders ride inside it, so one arrival is one raise. Not quiet-hours exempt.

**Stopping them.** "Stop the morning brief" and "stop the home brief" switch that brief off for
good through `why_did_you_say_that`'s `stop` argument; the same switches are rows under Digest on
Settings, Proactive speech (ADR 0035). Stored as rule-keyed rows in `proactive_settings`, no Room
version change.

**The emergency alarm** (`ProactiveUrgency.Alarm`, `service/SafetyAlarms.kt`). Legal only on a
Safety raise; the bus refuses it on any other category (`AlarmNotAllowed`). It rings when quiet
hours or a silenced phone would have kept ordinary speech from waking him: `RingService` on the
alarm stream, `RingActivity` showing the alert's facts with one Dismiss, then Alfred says the line.
Otherwise it is spoken like any Safety line (posted during a meeting with the ringer on). One alarm
per alert id; a second only when its severity rises. If Android refuses to start the ring from the
background, the alert is posted. The feed that raises these is not part of this decision.

## Consequences

- **Supersedes proactive-mode decision 15** (the daily cap) and **the screen-on half of decision
  17**. Amends ADR 0079's "the wake-up is a plain ring; Alfred never speaks on his own when an
  alarm goes off" and ticket 32's "sitreps stay tap only" for these two moments only: nothing arms
  a sitrep on a clock.
- Ticket 05's "the cap governs speech, not existence" survives as "the gate governs speech": every
  refusal except the kill switch and a decline still becomes a notification.
- Both briefs ride the Digest switch, which a fresh install has off. Its state on Kevin's phone is
  not known (Digest had a live raise until ticket 32); if it is on, both briefs start at install,
  with no mail read.
- `why_did_you_say_that` gained one optional argument; its description was tightened to pay for it
  (`LiveSetupPayloadSizeTest`).
- Built picks Kevin did not rule on, each reversible in one place: the 04:00 to 11:00 window, the
  6 h home limit, "silenced" including vibrate and any DND, the wake-up brief honouring a silenced
  phone, and an alarm in a daytime meeting being posted rather than rung. Held for Kevin's yes, off:
  the wake-up brief reading the Gmail newsletters (`WakeBrief.READS_MAIL`).
- The home brief makes no Gmail call: its facts are the calendar store and reminders only.
- Never verified on hardware at the time of writing: speech with the screen off and the phone
  locked, the ring started from the background, the geofence EXIT, and the brief at a real alarm.
