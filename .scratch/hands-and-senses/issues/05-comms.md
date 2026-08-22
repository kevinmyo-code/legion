---
map: hands-and-senses
ticket: 05
title: "Comms: place a call, send a text"
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - calls yes, texts never; contacts plus spoken digits"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Comms: place a call, send a text

## Question

The hands-busy case (driving, wrenching) wants "call Mom" and "text Sam I'm ten minutes out".
Android gives both without any cloud: `Intent.ACTION_CALL` (or dialer intent), `SmsManager` for
SMS. Known limit to state up front: third-party apps cannot send RCS; texts LEGION sends are SMS,
green-bubble, and a thread Kevin continues by hand lives in his SMS app. Decide:

1. **Outbound only?** Sending and calling is one scope; reading threads is another (and overlaps
   [the notification listener](04-notification-listener.md) for incoming). Does comms stay
   write-only, with incoming handled as notifications?
2. **The confirm turn.** Standing preference says outward-facing actions confirm first. For a
   text: Alfred reads back recipient + body verbatim, waits for yes. For a call: is confirmation
   the dialer screen itself (ACTION_DIAL, Kevin presses call) or does LEGION place it directly
   (ACTION_CALL, needs CALL_PHONE permission)? Direct placement is the hands-free point - but is
   it always, or only in driving Phase?
3. **Contact resolution.** "Sam" matching two contacts: disambiguate by voice. Does LEGION read
   contacts on demand (pull tool over `ContactsContract`) and what reaches Gemini - full contact
   list never; candidate names for the utterance only?
4. **Message content and the model.** The body Kevin dictates obviously goes through Gemini (it is
   the transcript). Read-through applies to anything else: no thread history, no stored messages,
   nothing in CompanionMemory. Confirm.
5. **Tool budget.** `send_text` + `place_call`, or one `comms` tool? Write the descriptions.

## Resolution - 2026-08-21 (Kevin, 2 calls)

### 1. Placing calls: YES. Sending texts: NO, and not deferred - ruled out.

They are different risk classes and the difference is not about difficulty:

- **A call is verifiable by ear and reversible.** It dials, Kevin hears it, he hangs up. A mistake
  costs seconds and mild embarrassment.
- **A text is irreversible and silent.** A wrong recipient cannot be un-sent, and he would not hear
  it happen. There is no read-back that makes that reversible - only one that makes it *feel*
  reviewed, and a confirm you learn to say yes to reflexively is not a confirm.

This is the same asymmetry the answer/decline work already lives under: `TelecomManager` actions are
observable, so the assistant can report what actually happened rather than what it attempted.

### 2. Target: contacts AND spoken digits (Kevin, against the recommendation)

Contacts resolve through `ContactsContract.PhoneLookup`, already granted for caller ID.

**The recommendation was contacts-only with the name read back before dialling, and Kevin took the
wider option. The cost is recorded once, here, and not re-argued:** speech-to-text on digit strings
is poor, and a misheard digit dials a stranger. That is not a hypothetical - it is the failure mode
of every voice dialler ever shipped.

**Two things the build must therefore do**, and they are not optional given the choice:

1. **Read the target back before dialling** - the name for a contact, the digits grouped for a spoken
   number. This was optional under contacts-only; it is load-bearing now, because it is the only
   thing standing between a misheard digit and a stranger's phone ringing.
2. **Never guess at a partial match.** A name matching nothing, or matching several people, asks -
   it does not pick the nearest. Same rule as `get_reported_crime_history` returning null rather
   than answering about the wrong jurisdiction.

### 3. Emergency numbers

Not raised in the grilling and it needs stating: **the assistant does not place emergency calls.**
Telecom treats them specially, `CallActions` already documents that an emergency call cannot even be
ended, and a voice assistant mishearing its way into one is a serious real-world event. If Kevin
asks for emergency services, it says plainly that he should dial it himself.
