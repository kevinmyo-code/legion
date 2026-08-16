# Map: Hands and senses

Label: `wayfinder:map`
Effort: `.scratch/hands-and-senses/`
Charted: 2026-08-16

## Destination

**LEGION stops being read-only. Decisions locked for the write surfaces (clear DTCs, home control,
place calls and send texts) and the new senses (notification listener, wrench-mode camera, Health
Connect), plus the assistant's actual voice and a configurable morning brief.**

Destination is DECISIONS, not shipped - same shape as `.scratch/android-auto/`. Each surface
graduates its own build tickets (or its own effort, for home control) once its decisions land.
Exception: Clear DTC is small enough that its ticket carries the build spec.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v21), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything. Most of
`memory/library/` is FROZEN Midnight AI history.

**Where this map came from.** A 2026-08-16 brainstorm session that started as competitive research
(`.scratch/competitive-landscape/research/landscape.md`). The finding that framed it: LEGION's
moat is verified whole-life context, and today it almost exclusively READS. The write surfaces and
new senses below are what a JARVIS does that a dashboard does not.

**Skills each session should consult:** `/grilling` and `/domain-modeling` for HITL tickets,
`/research` for research tickets, `/prototype` where a surface needs something to react to.

**Standing preferences for this effort (Kevin, 2026-08-16):**
- Pull-based tools always (CLAUDE.md §7). Every new domain must argue its tool-token cost; the
  count was 69 at the google-account charting and went DOWN when the notes domain landed.
- Nothing that requires a Kevin-hosted backend. External keys follow the BYO shape (KeyVault).
- Destructive or outward-facing actions (Mode 04 erase, sending a text, placing a call) require an
  explicit confirm turn. Never fire on a one-shot.
- Estimates labelled as estimates, in tool descriptions and spoken words (§4 rule 5). Wrench-mode
  vision answers and Health-Connect-derived insights are estimates.
- The mail read-through rule (google-account ticket 07) is precedent for every new sense: read,
  used, dropped; excluded from `EpisodicTurn`/`CompanionMemory`; nothing reaches the Drive backup
  unless a ticket argues otherwise.

### Settled while charting (Kevin, 2026-08-16) - binding on every ticket

| # | Decision | Consequence |
|---|---|---|
| 1 | **Home control integrates Home Assistant; LEGION never rebuilds per-device integrations.** | The Shelly garage opener stays the one hand-wired device. HA is the device layer, LEGION is the voice in front of it. |
| 2 | **Glasses are a peripheral; the phone stays the brain.** | Any glasses work is a display/mic/camera driver, never a second app. Purchase decision deferred to fog. |
| 3 | **Wrench mode ships phone-first.** Glasses raise it to hands-free later; they do not gate it. | Camera source is an implementation detail behind one vision surface. |
| 4 | **Morning brief news comes from Kevin's newsletters via the existing Gmail tool first.** RSS is a later add, in fog. | No aggregator service, no new auth. |
| 5 | **No writes to money, ever.** Restated from the brainstorm so no ticket drifts there. | Ledger stays read-only permanently; auto-pull ingests documents, it never touches accounts. |
| 6 | **Carfax, KBB, Edmunds are dead ends** (no consumer API; unverified for VINwiki). NHTSA recall/VIN decode already shipped in LEGION. | No tickets against them. Paid BYO-key lookups (CarMD, NMVTIS) sit in fog behind wrench mode. |
| 7 | **Calendar, Gmail, and the NHTSA recall checker already exist.** Asserted missing twice during the brainstorm; both times the code had them. | Every ticket greps the tree before proposing anything as new. |

## Decisions so far

<!-- one line per closed ticket -->

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **Glasses purchase and peripheral protocol.** Brilliant Labs Frame/Halo shaped (open source,
  camera + mic + display) but the buy decision and the BLE/audio bridge design wait until wrench
  mode works on the phone (settled decision 3) and until [Wrench mode
  shape](issues/07-wrench-mode-shape.md) fixes what the glasses must deliver.
- **Repair-cost and buying-advisor lookups.** CarMD-style fix/cost by VIN+DTC pairs with wrench
  mode; NMVTIS title lookups are a buying-advisor use case. Both are paid BYO-key APIs whose value
  is unclear until wrench mode lands. API claims are from model knowledge, unverified.
- **RSS news module for the brief**, if newsletters prove insufficient (settled decision 4).
- **An HA hub purchase task** graduates from [Home control scope](issues/03-home-control-scope.md)
  if Kevin does not already run Home Assistant.
- **Proactive delivery beyond the brief.** ProactiveBus exists; whether any of the new senses
  (notifications, health) may raise proactively, and where the compulsion line sits, sharpens
  after the per-sense tickets resolve.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **Any write to money or accounts.** Settled decision 5; it is a CLAUDE.md-level permanent rule,
  not an effort scoping call.
- **Remote start or any car-vendor cloud API.** Vendor accounts and vendor clouds, against the
  no-backend spirit.
- **Meta glasses ecosystem.** Closed SDK, Meta's cloud posture. Wrong tree per the landscape
  research.
- **Comparative or anonymized fleet data.** Permanent CLAUDE.md ban, restated.
- **Sending, replying to, or drafting mail.** Stays out, per the google-account map. Texting is
  [Comms](issues/05-comms.md); mail is not.
