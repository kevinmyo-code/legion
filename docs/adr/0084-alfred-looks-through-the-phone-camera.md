---
status: accepted
decided: 2026-11-10
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-10 - Alfred looks through the phone camera (Kevin)]]"
tags: [adr]
---

# 84. Alfred looks through the phone camera, parked, and what he sees is an estimate he never keeps

## Standing

**"Look at this" puts the phone camera into the open Gemini Live voice session at about one frame
a second, and Alfred answers by voice.** One way in by voice (the `look` tool), one by hand (the Show
button on the Fleet screen, labelled with the assistant's name), both through `look/LookController` (ADR 0035). Frames
go out only while the camera screen is on screen and a conversation is open, and stop the moment
the screen closes. The voice model is unchanged: native-audio Gemini Live only.

## Context

Kevin said yes on 2026-11-10 to the roadmap's "car camera diagnosis" item: a dash warning light,
an engine bay, a part label, seen through the phone and talked about with what LEGION already
knows (OBD codes, DTC meanings, recalls, service history). The Live API documents video input as
JPEG frames on `realtimeInput.video` at up to one frame a second, with `mediaResolution` to trade
detail for tokens. The trust risk is the one every vision model carries: a convincing wrong
answer.

## Decision

These rules bind the feature. Each is enforced where it can be, and stated as a prompt rule where
a prompt is the only lever (section 7: nothing inspects the spoken audio).

- **An estimate, worded as one.** What Alfred says about a picture is a guess ("looks like", "I'm
  not sure"). **Stored data outranks pixels:** a trouble code or sensor reading from OBD beats what
  he sees, he checks `ask_fleet` before naming a fault, and he never reads a number off the picture
  as fact. Prompt rule, carried in the `look` result and the button's opener (`LookRules.RULES`),
  never in the setup payload.
- **No unsafe repair.** A hot cooling system, working under a car without stands, live wiring: he
  says it is a shop job. Prompt rule, same carrier.
- **Phone only, parked only.** The head unit gets no camera. Both doors refuse in words when the
  phone can tell the car is moving, and an open look closes if it starts moving
  (`LookRules.motion`): a fresh GPS speed above 2.5 m/s (the look screen holds GPS while open), or a
  fresh OBD speed above 5 km/h when the dongle is connected. **When neither signal is fresh the app
  cannot tell, and the look is allowed.** That is a real gap, said here rather than hidden.
- **Frames are never stored, synced, logged or remembered.** A frame lives in memory from the
  camera to the socket send and is dropped. There is no snapshot feature; a snapshot is kept only
  if Kevin asks, and nothing builds one until he does.
- **Nothing claims sight before a frame went out.** The `look` result is a success only when a frame
  was actually sent (waited up to 7 s); otherwise it says in words that nothing was seen, and a
  later first frame tells the model the camera is live. The screen says "The assistant can see
  this" only after a frame went out. When the look closes the model is told it can no longer see.
  Section 7's outcome-verb rule, applied to sight.
- **What the camera sees is third-party content.** Text in a picture (a label, a sign, a screen) can
  carry instructions. A turn a frame went out in is read-through, and so is the turn after it (a
  frame sent while the reply plays belongs to the next turn): the same flag and carry mail, web
  grounding and fleet web lookups use (`GeminiLiveSession.markReadThrough`, round 3). On dev that
  flag refuses `memory` remember, a goal constraint and every `ask_engine` call (so engine writes,
  and watches if they arrive through it), keeps the turn out of episodic memory and redacts the
  reply in the conversation record. While a look is open every turn is marked. Like mail, the
  pictures stay in the model's context after that; a later turn is not marked, the same accepted
  gap the mail guard has.
- **No receipts.** Receipt scanning and OCR ingestion stay dead (Kevin's ruling); nothing here
  reads a picture into a record.

## Consequences

- **Unproven on this endpoint until the A25 runs it.** The docs say `gemini-3.8-live` takes video;
  two documented setup fields have been rejected by this endpoint before. `mediaResolution` LOW
  rides on each frame message (`LookRules.SEND_MEDIA_RESOLUTION`), never in `setup`, so a rejection
  breaks a look, not voice. It is the first thing to switch off if a look closes the socket.
- Audio-and-video sessions are documented at 2 minutes without context compression; the phone
  already compresses (32k trigger) and resumes. Whether compression lifts the cap with video is not
  documented and not yet tested.
- While a look is open, and for one turn after, nothing can be saved as a memory or sent to the
  engine (`ask_engine` refuses the whole turn), by design. The refusals now name the camera beside
  mail and web pages. "Remember this part number" is said again after the look closes.
- The trust rules live in one turn of the conversation, so on a long look the 32k sliding window
  can compress them away while frames keep coming. Not handled in the spike.
- The 8 s follow-up window still ends a conversation nobody speaks in, and the look screen then
  closes.
- One new dependency: CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`). One new
  permission: `CAMERA`, asked on the look screen the first time.
