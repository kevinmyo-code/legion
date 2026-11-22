---
status: accepted
decided: 2026-11-17
amended: 2026-11-19
decided-by: Kevin
source: "[[decisions#2026-11-17 - Alfred's brain moves to the engine; the phone becomes a thin limb (Kevin)]]"
tags: [adr]
---

# 98. Limbs: one device contract, and a relay for what only a device can do

## Standing

**AMENDED 2026-11-19 by [[0104-one-voice-speech-on-the-engine-shared-voice-module]]:** voice turns ride this contract (`docs/contracts/voice-client-v1.md`). The crisis pre-check moves to the engine: it runs right after the transcript, before the model; with no network the limb has no words to check, so no crisis detection is possible offline (a known gap, ADR 0104), and it plays the generic "can't reach the engine" clip. Until a limb's engine voice client ships, its own pre-check stays, as below. Versioning: for a voice client, a contract version older than N-1 gets `contract_unsupported` (409) instead of the "text-only turns" in the Decision below, which a voice client cannot use.

**The engine is the hub. Every device that attaches to it is a limb on one contract.** The phone is the first limb, and the head unit ([[0059-headunit-is-a-limb-in-this-repo]]) and the web app follow.

A limb is:
- a device token acting as its member, under household tenancy ([[0045-households-are-tenants]]);
- a capability manifest it uploads when it connects. The manifest lists the tools it can run for the agents (each with its schema, whether it `writes`, whether it is `read_through`, whether it `actuates`, and what it `needs`: screen, unlock, car parked) and the data streams it can send.

**The relay is resumable.** A turn that needs a limb's tool ends its stream with a `tool_request`. The limb runs the call with its own code, confirm gates included, and posts the result. That post streams the rest of the turn. No engine thread waits on a device.

## Context

Kevin: *"one powerful backend on the cloud, and various limbs that attach to it"*.

OBD, navigation, music, calls, screens, the camera and alarms can only run on the device. The engine runs on gunicorn with four threads per instance and min-instances 0. A thread held while a locked phone answers would starve the instance.

## Decision

- **Each limb has a `Limb` row.** It records the kind, the app version, the contract version and the manifest. The engine serves contract versions N and N-1. A limb on an older major version gets text-only turns and is told to update.
- **Idle limbs long-poll the engine for requests.** These are limbs that are not in a conversation, such as a car gateway or a home hub. No socket and no broker are needed.
- **Every relayed call carries a call id.** A limb never runs the same call twice, so a write is never repeated.
- **A relayed read-through tool taints the turn before the request goes out** ([[0090-no-write-after-third-party-text]]). A relayed write in a tainted turn is refused on the engine, and checked again on the limb.
- **These stay local on every limb because they must:**
  - the wake word;
  - the mic and the speaker;
  - physical actions;
  - the crisis pre-check.

  On the phone, the ID vault ([[0076-the-id-vault]]) also stays local. Its relayed tool opens the fingerprint screen and returns no value.
- **Anything that actuates in the physical world:**
  - always needs a confirm;
  - is never callable in a tainted turn;
  - is never run by a scheduled agent;
  - is never run unattended;
  - is never voice-only ([[0035-every-voice-capability-has-a-hands-path]]).
- **Telemetry such as OBD readings arrives as batched HTTPS posts** from a gateway limb. Each reading is stored as fact about what that sensor reported at that time, with its source.

## Consequences

- Specialists name relay tools by capability. A tool is offered only when an online limb of that member declares it.
- An iOS app or the head unit is then mostly UI on the same contract.
- What a locked phone does to screen-needing tools is checked on the device when the relay lands. Until then, such a tool refuses in words.

## Trust boundary (engineering note, 2026-11-21; not a ruling)

What the engine trusts today, stated so nobody reads more into the relay's checks:
- **The manifest is the owner's own app's word.** It arrives over the member's own device token, which can already read and write everything that member can. Each tool's `tool_class`, `writes`, `actuates`, `read_through` and `confirm` are self-declared.
- **What the engine checks itself** (`server/agents/legion/relay.py`): the class must be one it knows (deny by default); mail- and message-like names never relay; a name that sounds like outside text must carry an outside class; a call that names one of the engine's own outside actions (a calendar read, a place search, caller names) taints the turn whatever the class says. These catch an honest mistake and a tool nobody classified. They do not stop a manifest that lies: a mail reader named `summary` and classed `household` would be offered, untainted.
- **`confirm: limb`** hands the yes to the limb's own gate. The engine trusts that the gate exists.
- **If a third-party limb ever attaches** (a device or app not the member's own): before it may, the engine must own each tool's class, write flag and confirm rule itself, keyed by tool in an engine-side registry, never taken from the manifest; and such a limb's results are outside text by default.
