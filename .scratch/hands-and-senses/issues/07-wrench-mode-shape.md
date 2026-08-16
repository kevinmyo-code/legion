# Wrench mode: live stream or snap-and-ask, and how the car's history gets in the room

Type: grilling
Status: open
Blocked by: 06

## Question

The differentiator is not vision, it is context: a generic model guesses at an engine bay; an
agent that knows the VIN decode, the stored DTCs, the freeze frame, and every service record is
diagnosing. With [the Live-video facts](06-wrench-vision-research.md) on the table, decide:

1. **Session shape.** Continuous Live video (hands-free, expensive, session limits) vs
   snap-and-ask one-shots (cheap, deliberate, phone in hand between shots) vs hybrid (Live audio
   session + photo tool calls when looking is needed). Settled decision 3: phone-first either way.
2. **Context injection.** How does fleet knowledge reach the vision call - preamble (tokens on
   every turn) or tools the model pulls (`get_dtc_history`, `get_service_records`, already
   exist in some form in `LiveToolbox` - grep first, settled decision 7)? What is the marginal
   token cost per shape?
3. **The estimate rule, spoken.** "That looks like the charcoal canister" never "that is". Vision
   answers are §4 rule 5 estimates; the tool description and Alfred's register both say so. Where
   is the line - component identification is an estimate, torque specs quoted from where?
   (LEGION has no service-manual source; saying "check the manual" honestly beats inventing a
   number. This is the memory-anchored-to-falsifiable-facts rule applied to wrenching.)
4. **Capture artifacts.** Does a wrench session leave anything behind - photos into
   `PantryPhotoStore`-like storage (currently pantry-only by a locked carry-over ruling), a
   maintenance-log note ("diagnosed EVAP, canister located")? Photo storage beyond pantry
   REOPENS a 2026-07-31 locked call, so it needs Kevin explicitly, or stays ephemeral.
5. **Entry.** How does the mode start - a phrase in the live session ("look at this"), a screen,
   both? Does driving Phase suppress it (camera + driving = no)?
