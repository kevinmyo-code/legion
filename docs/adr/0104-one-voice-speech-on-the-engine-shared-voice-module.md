---
status: accepted
decided: 2026-11-19
decided-by: Kevin
source: "[[decisions#2026-11-19 - One voice: speech on the engine, a shared Android voice module, persona by surface (Kevin)]]"
tags: [adr]
---

# 104. One voice: speech on the engine, a shared Android voice module, persona by surface

## Standing

**The engine is the one voice brain for the phone, the head unit and the web assistant (the demo included).** Midcon is not covered. Nothing below is built yet: each line says what is binding for new work, and today's behaviour stays until the named migration step ships.

- **The engine runs the whole turn.** Speech-to-text, every transcript rule (the crisis pre-check included), the agent graph, the guards, code-spoken lines, filler choice and text-to-speech run on the engine for every client. Until each client's step ships, the phone keeps its own speech-to-text and text-to-speech ([[0092-phone-voice-is-a-cascade-live-retired]], [[0095-alfreds-brain-runs-on-the-engine]]) and the head unit keeps Gemini Live.
- **The crisis pre-check moves to the engine.** The crisis pre-check runs on the engine, right after the transcript and before the model (Kevin, 2026-11-19). **Known gap:** with speech-to-text on the engine, an unreachable engine means the device has no words, so no client can detect a crisis in v1; the client plays the generic `unreachable` clip. The gap closes only with an on-device recognizer (offline spoken commands, a v1 non-goal). Flagged for Kevin. [[0026-crisis-detector-boundary]]'s rule is unchanged (real resources, the character stops); only where its check runs moves. Until a client's engine voice client ships, that client's own pre-check stays.
- **A client keeps only what must be local:** the mic, end of speech (Silero and Smart Turn on the device), barge-in and half-duplex, playback, the earcon and the cached clips, limb tools ([[0098-limbs-device-contract-and-relay]]), the confirm-gate UI, the wake word and the ID vault (phone) and push-to-talk (head unit). The crisis pre-check is not on this list: it runs on the engine.
- **One contract for every client:** `docs/contracts/voice-client-v1.md` (behaviour) and `docs/contracts/voice-wire-v1.md` (wire). Shared test vectors in `server/contracts/voice/v1/` are loaded by every client's tests. The engine serves contract versions N and N-1.
- **One shared Android voice module.** `voice/` at the repo root is a source project that both `settings.gradle.kts` files include. It is not a composite build and not a published artifact. Allowed dependencies: coroutines, OkHttp, onnxruntime, org.json. Not allowed: Hilt, Room, Compose, Firebase, Media3, sherpa, Vosk (these lists: plan default, not ruled; `docs/contracts/voice-client-v1.md` section 15). Tools, UI, the wake word, local commands, push-to-talk, wheel keys and audio focus stay in each app.
  This lifts, for this one module only, the rule that the phone and the head unit share a contract and never code ([[0059-headunit-is-a-limb-in-this-repo]]).
- **Persona: the profile's companion, except surfaces with their own fixed persona.** The engine owns the persona copy (`server/assistant/personas.py`). On the phone and the web the companion is the one the member's profile chose ([[0020-companion-identity-per-profile]]), never forced to one name. Two surfaces have a fixed persona: the head unit speaks as Moose, and a recruiter's visit has its guide. Moose is data, not a second code path. Never per car. Confirmed by Kevin, 2026-11-20 (*"1 yes"*).
  ADR 0020 is not changed. The locked pivot row in root `CLAUDE.md` section 2 ("One global assistant identity") gains one sentence for the two fixed-persona surfaces, by Kevin's ruling of 2026-11-19 (*"Moose stays the head unit's persona, as data"*); cars stay data and the dead per-car identity split stays dead.
- **The head unit gets engine voice as its second release, HU-2, by OTA.** The hand-installed first release (HU-1) keeps today's Live voice. HU-1's pairing must hand over the engine sign-in (Kevin, 2026-11-19: *"pairing also signs head unit in yes."*). The Gemini key hand-over is transitional: the key leaves the head unit in HU-3, with Live and the sub-agents that still call Gemini.
- **The two turn-taking models are allowed on the head unit** (Silero VAD and Smart Turn, about 11 MB plus the onnxruntime native library).
- **Audio-first** (the text model hearing the audio directly) is engine-only, behind a flag, and on hold there pending the open design decisions below. Kevin's words were *"audio first earlier . together with the speed branch."* and *"audio first default at ship"*. They could not be met safely: the phone-loop version was built, failed blind review (a write could run from audio; a failed transcript could freeze the turn; the checks ran after the model), and the phone already answers from a speculative transcript draft on about 9 turns in 10, so its gain over that baseline was unmeasured and likely small. The orchestrator parked it on 2026-11-20 under Kevin's delegation (*"just ship once u thin its ok"*). That is the orchestrator's call, not a ruling by Kevin. Nothing on `dev` belongs to phone audio-first.
- **Open design decisions (2026-11-20).** Kevin has five decisions pending that may change v1: whether clients stream audio to the engine while he speaks (streaming speech-to-text, ended by the client's end-of-turn signal); which transcription model; a "think harder" lane on a larger model; how late background answers are spoken; and whether a speech-to-speech front is prototyped on the head unit. v1's one POST per utterance may change under the first. None is decided here.
- **Engine unreachable:** the client plays its cached "can't reach the engine" clip and answers nothing else by voice (plan default, not ruled; `docs/contracts/voice-client-v1.md` section 15). Only the limb-local set named in the client contract runs with no network.

## Context

Three voice stacks answer for one household today: the phone's cascade (speech and turn-taking on the phone, the model loop moving to the engine), the web's cascade on the engine (ADR 0102, with the phone's turn-taking ported to the browser, ADR 0103), and the head unit's Gemini Live session on its own key. Each fix to a spoken rule had to land up to three times, and the head unit had its own persona path. Kevin, 2026-11-19: the engine is the one brain; one shared voice module for the two Android apps; Moose stays the head unit's persona, as data.

## Decision

- Speech moves to the engine for every client (one multipart POST per utterance, an SSE reply, a tool request and result relay, cancel). The web door's shape is the base; `/api/agents/turn` gains audio.
- A shared source module, not a second copy and not a library release, because the head unit needs the same turn-taking and playback rules as the phone and a port would drift.
- Engine voice reaches the head unit as HU-2, not inside HU-1, so HU-1's hand install is not held up by voice work. If the first OTA fails on the unit, HU-2 needs a USB install.
- Order (plan default, not ruled; `docs/contracts/voice-client-v1.md` section 15): the contract (this ADR), the engine speech stage (V1, dark; shipped 2026-11-20), the module extracted inside the phone with no behaviour change, the phone's engine client behind a flag, HU-2, web alignment, HU-3, then the phone's own loop retires. Audio-first on the engine is on hold.

## Consequences

- **Amended:**
  - [[0059-headunit-is-a-limb-in-this-repo]]: the two apps share the `voice/` module's code.
  - [[0098-limbs-device-contract-and-relay]] versioning: a voice client older than N-1 is refused (`contract_unsupported`) rather than given text-only turns; it has no text-only mode.
  - [[0092-phone-voice-is-a-cascade-live-retired]] and [[0095-alfreds-brain-runs-on-the-engine]]: speech-to-text and text-to-speech leave the phone when its engine client ships.
  - [[0098-limbs-device-contract-and-relay]] and [[0095-alfreds-brain-runs-on-the-engine]]: the relay carries voice turns, and the crisis pre-check leaves the limbs' keep-lists for the engine; 0095's offline line that the crisis resources still speak holds only until the phone's engine voice client ships.
- **Head unit size:** about +11 MB of models and an estimated 6 to 10 MB of onnxruntime native code per ABI. Smart Turn's speed on the unit's chip is not measured.
- **Latency:** phase one (speech-to-text on the engine) is expected at about parity with the phone today, plus one Cloud Run hop. That hop, cold starts and SSE buffering are not measured.
- **Offline:** with speech-to-text on the engine, no spoken request works with no network. The phone's local commands are off by default and are wired only on the retired Live path today, so the offline set is the non-speech one in the client contract.
- **Not built again on the phone-only loop:** a separate backchannel or turn-taking port for the head unit, and any new Moose text-to-speech path besides navigation cues.
