# Home control: what does LEGION actually get to touch?

Type: grilling
Status: open
Blocked by: 02
Scope: **EFFORT, not a ticket.** Chart `.scratch/home-control/` first, using this body as raw
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
