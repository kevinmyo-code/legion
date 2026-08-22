---
map: hands-and-senses
ticket: 26
title: "BUILD: place a call by name or by number"
type: build
status: built
status-detail: "Built. Owes the on-phone run: call a contact by name, call a number by voice, confirm the read-back precedes the dial."
blockers: ["05"]
blocked-by: ["[[05-comms]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# BUILD: place a call by name or by number

**Every decision is settled in [[05-comms]], resolved 2026-08-21.** Read its resolution before
writing anything.

**This ticket exists because that resolution left nothing behind.** Kevin asked on 2026-08-22
whether he could call someone by voice, and the honest answer was that it had been fully decided a
day earlier, never built, and nothing on the board said so - the wiki lists only OPEN tickets, so
resolving the decision made it vanish. `tools/decision_debt.py` now fails on exactly this shape.

## What to build

A voice tool that places a call. **Texts are ruled out permanently, not deferred** - a call is
verifiable by ear and reversible, a wrong text cannot be un-sent and Kevin would not hear it happen.

- **Targets: contacts AND spoken digits.** Contacts resolve through `ContactsContract.PhoneLookup`,
  already granted and already used by `CallerId` for incoming calls - the same lookup, backwards.
- **Read the target back before dialling.** The name for a contact, the digits grouped for a spoken
  number. **This is load-bearing, not a nicety:** Kevin chose spoken digits against the
  recommendation, and the cost was recorded once - speech-to-text on digit strings is poor, and a
  misheard digit dials a stranger. The read-back is the only thing standing between those.
- **Never guess at a partial match.** A name matching nothing, or matching several people, ASKS. It
  does not pick the nearest. Same posture as `get_reported_crime_history` returning null rather than
  answering about the wrong jurisdiction.
- **No emergency calls.** If Kevin asks for emergency services it says plainly that he should dial
  it himself. Telecom treats those specially, `CallActions` already documents that an emergency call
  cannot even be ended, and a voice assistant mishearing its way into one is a serious real-world
  event.

## Notes for whoever builds it

`answer_call` and `decline_call` already exist and are the shape to follow. `CALL_PHONE` is NOT in
the manifest yet. The failure results must be distinct sentences: no such contact, several matches,
no permission, and "that is an emergency number, dial it yourself" are four different things and
must not collapse into one.

## Verification

- Suite green **both** ways: `./gradlew testDebugUnitTest` and `testDebugUnitTest -Pnokey`.
- `python tools/voice_guide.py` exits 0 with user-facing copy for the new tool.
- Tests for each distinct failure sentence, and for the ambiguous-match case asking rather than
  picking.
- On the phone: call a contact by name, call a number by voice, and confirm the read-back happens
  before the line opens rather than after.
