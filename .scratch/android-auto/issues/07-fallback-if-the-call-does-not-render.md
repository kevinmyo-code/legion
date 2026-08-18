---
map: android-auto
ticket: 07
title: "What if Android Auto will not render the call?"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# What if Android Auto will not render the call?

## REFRAMED 2026-08-13, after tickets 01 and 04 resolved

**This ticket was written as "does the mic work". It is now "what does the driver see and touch".**

Both blockers are resolved and the question changed underneath them:

- **The microphone is no longer at stake.** There are two independent routes to the car's HFP mic -
  a plain foreground service using `MODE_IN_COMMUNICATION` + `setCommunicationDevice` (ticket 04), or
  a self-managed call, which Bluetooth surfaces to the car regardless of what Android Auto draws
  (ticket 01). Neither needs Android Auto to render anything. They are **alternatives, not a stack**.
- **The real risk moved to distribution.** Android Auto's in-call surface appears to be attached to
  `androidx.car.app.category.CALLING`, which is Internal/Closed-Play-track only and which Android
  Auto's unknown-sources developer option explicitly does not cover. **A sideloaded LEGION may be
  structurally unable to reach it**, no matter how correct the telephony is.
- **Case 4 below is now LIVE**, not hypothetical. Settled decision 1 was taken on a premise that has
  since been falsified, and only Kevin can re-take it.
- One 30-minute head-unit session (bare `ConnectionService`, one button, no Car App Library) settles
  whether gearhead draws it. `dumpsys telecom`'s bound-services list during the call separates
  "gearhead never got it" from "gearhead got it and declined to draw" - **those are different
  rulings**, so run it before resolving this ticket.

## KEVIN'S CONSTRAINT, 2026-08-13 - decide this ticket on it, not on the microphone

**"I don't need the now playing bar, because the main AA screen has Spotify sharing the screen with
maps and another app."**

That is a requirement, and it reverses the weight of the four cases below. A media app takes the
active media session, so LEGION appearing on that card means **Spotify losing it** - every time.
A call takes the call surface instead, ducks the music, and gives the card back. **The media surface
is the one that costs him something he has said he wants to keep.**

So case 3 (media only) is now the *expensive* outcome, not the safe fallback it was written as.
See ticket 11 item 0. Test tonight: browse LEGION's tree with Spotify playing and watch the card.

## Question

Settled decisions 1-3 all assume the self-managed call renders and gets the car's microphone. Ticket
01 says whether it renders; ticket 04 says who gets the mic and whether a call is even required to
get the car's one. This ticket rules on what the destination becomes under each outcome, and it must
be resolved before any build ticket graduates.

The cases, and what each needs decided:

1. **It renders and gives the car mic.** The destination stands unchanged. Decide only whether the
   media surface keeps a push-to-talk custom action as a second way in, or whether tapping play is
   the sole trigger.
2. **It renders, but the mic is still the phone's.** Settled decision 3's whole justification is
   gone; the call is then only a *surface*, not a mic strategy. Is a call-shaped UI still worth
   `MANAGE_OWN_CALLS` and a `ConnectionService`, or does it collapse into the media surface?
3. **It does not render at all.** The media surface is the only surface. Decide what LEGION is then:
   a push-to-talk custom action on the media transport that opens the phone's mic (if ticket 04 says
   that is even possible while projecting), or briefings plus `onPlayFromSearch` only, with no live
   conversation in the car at all. **This is the case where the destination shrinks**, and Kevin must
   say by how much rather than have it decided for him.
4. **Ticket 04 says a plain foreground service can get the car mic without a call.** Then the call
   route is unnecessary rather than impossible, and settled decision 1's "two surfaces, deliberately"
   should be re-opened with Kevin - it was taken on a premise that no longer holds.

Also decide the honest floor: **if live conversation in the car is impossible on every route, is a
briefings-only media app still worth building**, or does the effort stop? Naming that now stops a
diminished version being shipped by inertia.

Do not resolve this on recommendation. Case 3 and case 4 both change what Kevin agreed to at
charting, and settled decisions are his.
