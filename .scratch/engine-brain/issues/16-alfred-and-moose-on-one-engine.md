---
map: engine-brain
ticket: "16"
title: "Alfred and Moose on one engine"
type: build
status: open
size: M
blockers: ["07", "14"]
blocked-by: ["[[07-retire-the-phone-brain]]", "[[14-headunit-thin-limb]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Alfred and Moose on one engine

Part of the brain switch, not a new feature: it is what the switch means once two Android surfaces
answer from the same engine. Ruled by Kevin on 2026-11-24. Every example here is invented, because
the repo is public.

Blocked by 07 for the phone half (the phone has no brain of its own left to disagree with the
engine) and by 14 for the head unit half (the head unit is a limb on the contract). 13 sits under
14 as ordering, not as a dependency of this ticket.

## Decided (Kevin, 2026-11-24)
- **One assistant, two personalities.** The phone and the head unit get the same brain, the same
  tools and the same data. What differs is the personality: the head unit speaks as Moose, the
  phone speaks as the member's own companion, Alfred today. Persona stays data per surface
  ([[0104-one-voice-speech-on-the-engine-shared-voice-module]],
  `server/assistant/personas.py`); no second code path, never per car.
- **Generated UI cards on both surfaces.** Both draw cards in the head unit's art style so the two
  read as one family. The style itself is `headunit/CLAUDE.md` section 12; this ticket does not
  restate or change it.
- **Moose shows cards on the head unit screen too**, not voice alone. A card seen while driving is
  glanceable: big text, one main thing per card, the details only when the car is parked.
- **No internet means no Moose.** The head unit does not work offline and owes no offline
  fallback: with no engine there is no assistant.
- **A conversation does not carry from the phone into Moose.** Walking from the phone to the car
  starts a fresh turn on the head unit.
- **The same voice behavior everywhere, standing.** Moose and Alfred behave exactly the same in
  every voice behavior: goodbyes, hang-ups, interruptions, reminders, timing, all of it. Only the
  personality differs. The phone is the default to match, so a surface copies the phone's shipped
  behavior rather than inventing its own. Where the hardware makes a behavior impossible, the head
  unit's missing wake word being the example, the gap is stated and never asked about.

## Build
- Persona by surface is [[14-headunit-thin-limb]]'s work, not this ticket's: 14 adopts the shared
  `voice/` module with Moose picked by surface on the engine, from the limb's `client` field on the
  wire (`docs/contracts/voice-wire-v1.md`; `docs/contracts/voice-client-v1.md` section on persona).
  This ticket relies on it and leaves the phone unchanged on the profile's companion.
- One card shape served to both limbs, rendered natively by each. The model picks the view and
  tools supply the values, the safety property the shipped generated view already holds
  (`.scratch/generated-ui/map.md`, `show_generated_view`, `service/GeneratedViewQueryRunner.kt`).
- A parked-versus-driving split on the card: the driving form carries one fact, the parked form
  carries the detail. The manifest already describes a tool that `needs` the car parked
  ([[0098-limbs-device-contract-and-relay]]), so the same signal gates the card's detail rather
  than a new one.
- No session carry between limbs: a head unit turn never resumes a phone turn's history.
- Offline on the head unit: nothing stands in for the engine, and the surface says so. The
  no-fallback-brain rule is [[0095-alfreds-brain-runs-on-the-engine]]; the generic
  cannot-reach-the-engine clip is ADR 0104's. Which one the head unit plays is a build detail,
  not a ruling.

## Open
- **The phone's card style is ruled, its record is not.** Generated cards on the phone draw in the
  head unit's style (Decided, above). That is a deliberate exception to ADR 0081 (Typeset) and to
  root `CLAUDE.md` section 2 for generated cards only; the rest of `app/` stays Typeset. Whether
  the exception lands as an amendment to ADR 0081 or as a new ADR is not ruled. No ADR is touched
  by this ticket.
- **This ticket owes the record.** The rulings above have no ADR and no `decisions.md` entry yet.
  One is owed before the build lands.

## Verification (gates)
- [ ] The same invented question asked on both surfaces returns the same facts, and only the
      personality differs.
- [ ] Head unit smoke over its OTA build (no ADB): a card renders, and its driving form shows one
      main thing.
- [ ] Parked and driving forms both checked on the head unit, because the parked signal was not
      determined from code here.
- [ ] Engine unreachable on the head unit: it says so and answers nothing else.
- [ ] A phone turn followed by a head unit turn: the head unit starts fresh, with no history from
      the phone.
- [ ] Voice-behavior parity against the phone's voice cases (`tools/voice_eval/cases.json`): the
      head unit matches the phone's shipped behavior on a goodbye, an interruption and a reminder,
      with only the personality differing, and any hardware gap named in the result.
