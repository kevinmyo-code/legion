---
map: offline-hiking
ticket: 09
title: "Decision: trip plan before leaving signal, and \"if not back by X\""
type: decision
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-trail-data-and-safety-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Decision: trip plan before leaving signal, and "if not back by X"

## Question

The Galaxy A25 has no satellite messaging or satellite SOS (research 03). With no signal the phone
cannot call for help, and LEGION cannot either. What may LEGION do about safety, and does an
"if I'm not back by X, tell someone" alert get built?

## The plain facts

- No signal: no call, no text, no data, no Alfred. 911 may still connect through another carrier's
  tower if one is in range (FCC rule), but not with no tower at all (reasoned).
- **The A25 has no built-in satellite SOS** (research 03: Samsung's spec lists none; Skylo's
  certified list has no Galaxy A phone). One open door: T-Mobile's satellite text-to-911 (Starlink
  direct-to-cell) is free to compatible phones on any carrier, and T-Mobile lists a "Galaxy A25 SE";
  whether Kevin's A25 5G qualifies is **unconfirmed**. Action for Kevin, outside LEGION: run
  T-Mobile's IMEI checker on the phone. If eligible, enrol; it costs nothing.
- The real answer for an emergency with no signal is a dedicated satellite device: a Garmin inReach
  (Iridium, SOS to a staffed 24/7 centre, weeks of battery; every plan includes SOS; roughly $8 to
  $30 a month plus activation, secondary sources). LEGION says so once, on the hike screen's safety
  card, and never pretends otherwise.
- TPWD and NPS both advise leaving the route and return time with someone not on the trip; NPS
  publishes an "if not back by X, call Y" template (research 03). Option A is that advice, built in.

## Options

| Option | What happens | Rule it touches |
|---|---|---|
| **A. Trip plan, shared by Kevin's own tap** | Before he leaves signal, a "Share trip plan" button builds a short text: park, planned route or GPX name, start time, expected return, car location as a map link, and what to do if he is not back. It opens Android's share sheet; Kevin picks the person and presses send. | None: a send Kevin performs himself. |
| B. Overdue alert, armed per hike, sent by the engine | Kevin arms it before leaving signal: a contact, a time. The phone uploads the plan to the engine. If the phone has not checked in by that time plus a grace period, the engine sends the plan to the contact. Disarmed by "I'm back" or by reaching signal with the hike ended. | An unsolicited send to a third party, by the engine, while Kevin cannot be asked. Needs: an engine send channel (Gmail send is not configured on the engine today; SMS needs a provider such as Twilio, a new dependency and cost), and a new ruling. |
| C. Overdue reminder to Kevin only | At the expected return, the phone reminds HIM (local notification, works offline). | The compulsion test (a)-(d): anchored to a time he set, actionable, no absence talk, silenceable. Passes. |

## Recommendation

**A and C in v1. B is a separate ruling, recommended later and only with these terms if Kevin wants it:**

1. Armed by Kevin per hike, never by default, never inferred from location or calendar.
2. One named contact and one time, shown on the hike screen while armed.
3. The engine sends only the plan text Kevin saw when he armed it; no live location (it has none).
4. A false alarm costs a worried spouse, so disarming is one tap and also automatic when the phone
   reports the hike ended.
5. The send is logged and Kevin is told afterwards that it went.
6. The safety card still says plainly: this is not SOS, and for real backcountry carry a satellite
   messenger.

Why later: it needs an engine send channel that does not exist yet, and a missed disarm (dead phone
battery after he is safely home) sends a false alarm. A (manual share) gets most of the value with
none of that.

## Kevin must say

- A + C in v1: yes or no.
- B at all: yes (with the terms above), later, or never.
