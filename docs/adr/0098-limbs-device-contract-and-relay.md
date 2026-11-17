---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - Alfred's brain moves to the engine; the phone becomes a thin limb (Kevin)]]"
tags: [adr]
---

# 98. Limbs: one device contract, and a relay for what only a device can do

## Standing

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
