# Ship pass: on-device QA and map close

Type: task
Status: closed (2026-08-16) - superseded; unrun QA recorded on the ticket
Blocked by: 15, 16, 17, 18, 19, 20

## Question

The destination gate. Install on Kevin's phone and walk every surface: daylight contrast check
(muted-tier text outdoors), animator-scale-0 completeness, utility screens inheriting
acceptably, §4 wording present on every provisional/estimate surface, boot/theatre moments
firing exactly where ticket 04 says and nowhere else, driving-mode offer + exit on a real
dongle. Dispatch qa and bug-hunter. Every build ticket's verification steps accounted for as
done / deferred-with-follow-up / impossible-and-why (L11). Map closes when this resolves.

## Closed 2026-08-16 - SUPERSEDED, with its unrun QA recorded

The map already declares this effort shipped: `cyberdeck-ui/map.md:6` says "This map **SHIPPED** and
stays here as history; do not resume it", while this gate ticket stayed open. **The map and the
ticket disagreed, and the tree supports the map** - a rebuilt CRED/HOME/FLEET exists. Closing the
ticket to end the contradiction, and recording what never ran so closing does not erase it.

**Machinery verified present** (`traced`): the animator-scale-0 path is real
(`ui/theme/DeckMotion.kt:63,89` reads `Settings.Global.ANIMATOR_DURATION_SCALE`); the driving-mode
offer and exit route exist (`MainActivity.kt:290-299`, `:465`); rule 7 wording is on the CRED
provisional surfaces (`LedgerScreen.kt:1007,1066`).

**NEVER RAN, and not recoverable by grep** - carry these into any future QA pass:
- Daylight contrast of the muted tier, outdoors.
- Whether utility screens inherit the theme acceptably - a judgement call, not a check.
- Whether the theatre moments fire exactly where ticket 04 says **and nowhere else**. The mechanism
  greps; the firing does not.
- Driving mode against a real OBD dongle.
- Install-and-walk-every-surface, and the qa/bug-hunter dispatch.
- The L11 accounting of every build ticket's own verification steps.
