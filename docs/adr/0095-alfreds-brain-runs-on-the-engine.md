---
status: accepted
decided: 2026-11-17
amended: 2026-11-19
decided-by: Kevin
source: "[[decisions#2026-11-17 - Alfred's brain moves to the engine; the phone becomes a thin limb (Kevin)]]"
tags: [adr]
---

# 95. Alfred's model loop runs on the engine; the phone is a thin limb

## Standing

**AMENDED 2026-11-19 by [[0104-one-voice-speech-on-the-engine-shared-voice-module]]:** speech-to-text and text-to-speech move to the engine too, and the head unit and the web share this brain. Until the phone's engine voice client ships, the phone keeps them as listed below. The crisis pre-check moves to the engine with speech-to-text (right after the transcript, before the model); from then, with no network, the phone has no words to check: no crisis detection is possible offline (a known gap, ADR 0104), the phone plays the generic "can't reach the engine" clip, and the offline line below that the crisis resources still speak no longer holds.

**Alfred's prompt, routing and model-plus-tools loop run on the household's engine, not on the phone.** The phone sends each finished transcript to the engine's turn endpoint and plays back the sentences that stream in.

The phone keeps only what must be local:
- wake word, end of turn and barge-in;
- speech-to-text and text-to-speech, as today: Cloud is Gemini called from the phone, Laptop is Kyutai ([[0092-phone-voice-is-a-cascade-live-retired]]);
- the crisis pre-check (`ai/CrisisDetector.kt`);
- the confirm-gate UI;
- phone-only tools, run through the relay ([[0098-limbs-device-contract-and-relay]]);
- the ID vault ([[0076-the-id-vault]]);
- simple commands ([[0088-simple-commands-on-the-phone]]).

**There is no fallback brain on the phone.** When the engine cannot be reached, the phone says so in words and answers nothing else by voice. Screens keep showing the last data and say how old it is ([[0044-django-is-the-engine]]).

Once the engine brain has taken over, these retire from the phone:
- the model loop (`service/cascade/GeminiTextLoop.kt`);
- the prompt assembly (`ai/AriaBrain.kt`);
- the sub-agents and `ask_engine`;
- the pack overlay (`ai/AlfredPack.kt`).

**The locked safety text** (CRISIS, SAFETY_INSTRUCTIONS, CANNOT_CLAUSE, ASK_BEFORE, NO_PASSWORDS) lives in the engine's code. Every specialist prompt carries it, and a test pins its presence.

## Context

On 2026-11-16, three ship rounds needed phone builds and hours of Gradle for prompt, routing and tool-description fixes. Each was caught only by real-model gates. The Alfred pack ([[0072-alfred-pack-and-self-update]]) reaches four clause slots, and none of those fixes fit in it: they were Kotlin.

Kevin: *"lets plan the fuller agent graph and build it after"*. On 2026-11-17 he dropped the fallback brain, so no two brains have to be kept in step.

## Decision

- **Transport.** One engine endpoint per turn, streamed as Server-Sent Events on the existing gunicorn. WebSocket was rejected because it needs a new server stack.
- **Model and key.** flash-lite on every node, on the household key, which never leaves the engine process ([[0056-the-household-key-serves-the-web-assistant]]).
- **Warm-up.** The engine is woken when Alfred is woken, so a cold instance warms while he speaks.
- **No shadow week.** The switch rides on three things:
  - the eval suites, run on both brains with the same cases ([[0100-agent-runtime-traces-and-eval-gates]]);
  - an on-phone smoke run;
  - a per-member flag, settable in Settings or by a server default, that turns it back until the phone loop is removed. After that, rollback is the previous Cloud Run revision.
- **No data is lost.** Room-only data gets engine homes before the phone loop is removed:
  - goals and advisor advice;
  - voice-logged pending transactions;
  - user-aspect records;
  - proactive raises.
- **Shipping.** Every stage ships when its checks pass, with no per-stage ask, the switch included. Each ship is reported after (Kevin: *"Ship every stage when checks pass"*).

## Consequences

- **Offline.** No voice answers offline or during an engine outage; that is the ruled trade. Simple commands, which use no model, and the crisis resources still speak.
- **Latency.** A plain tool turn pays one more network hop than today. The gains are elsewhere:
  - fixes ship by engine deploy;
  - router turns lose the phone's sub-agent call;
  - router-run reads lose a model round ([[0097-router-run-reads]]).
- **Other clients.** The web assistant moves after the phone, then the head unit, each as a limb.
- **ADRs amended:**
  - [[0092-phone-voice-is-a-cascade-live-retired]]: its text model leaves the phone.
  - [[0072-alfred-pack-and-self-update]]: the phone overlay and the APK-compiled locked set retire with the phone loop. Self-update is unchanged.
  - [[0087-live-surface-diet-and-scripted-opening]]: the 24-declaration budget binds the generalist only. Each specialist has its own limit ([[0096-agent-graph-router-and-specialists]]).
