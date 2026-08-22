---
map: hands-and-senses
ticket: 03
title: "Home control: what does LEGION actually get to touch?"
type: grilling
status: kiv
status-detail: "KIV 2026-08-21, Kevin - parked, not queued"
blockers: ["02"]
blocked-by: ["[[02-ha-api-research]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Home control: what does LEGION actually get to touch?

material; [ticket 02's findings](02-ha-api-research.md) become that map's resolved research. See
map.md, "Efforts in disguise".

## Question

Settled decision 1 makes HA the device layer. This ticket decides the LEGION side, with
[the HA API facts](02-ha-api-research.md) on the table.

1. **The hub reality.** Does Kevin run Home Assistant today? If not, this ticket graduates a
   hardware task (HA Green vs Pi vs container on something already running) into the map before
   anything else can be verified on-device. What devices exist in the house today (the Shelly
   garage opener is known; what else - lights, locks, thermostat, plugs)?
2. **Tool surface and budget.** Roughly `get_states` + `call_service`, or HA's conversation
   endpoint (research point 4)? Every tool is prompt tokens on every live session. Can home
   control land as ONE tool with an action parameter, the way the tool count went down when notes
   landed? Write the candidate tool descriptions in the answer - the description is the only thing
   the model reads.
3. **The danger tier.** Locks and the garage are not lights. Which entity classes require a
   confirm turn, and which fire on a one-shot? Where does the existing GarageOpener flow fold in -
   does Shelly stay direct or route through HA once a hub exists?
4. **Token custody and lapse.** Long-lived token pasted into KeyVault like the Gemini key. What
   does Alfred say when the hub is unreachable (offline-degradation rule) or the token is revoked?
   Words, not glyphs.
5. **Clone-and-run.** A stranger without HA skips the aspect entirely; the aspect must be invisible
   until configured, like the Gemini key screen. Confirm the shape.

## KIV - 2026-08-21 (Kevin)

Parked on purpose. Not dead, not queued.

The garage relay already works through the Shelly path and is governed by its own rule (a spoken
offer, never automatic, because the relay cannot report door state). **That is the only home device
LEGION touches, and it stays that way** until this is picked up.

When it is, the fork that was on the table: read everything but write only what is safe to get
wrong (lights, scenes, setpoints) versus read-only versus everything-with-confirmation. **Locks were
the sharp case** - the one device where a mistaken action has a real-world security consequence, and
a voice assistant mishearing in a car is the wrong place for it.
