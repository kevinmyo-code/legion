---
map: ai-upgrade
title: "AI tooling upgrade: provider choice, decision models, agent platforms"
charted: 2026-10-22
charted-by: "Kevin + Opus"
effort: "`.scratch/ai-upgrade/`"
tickets: 3
open: 1
status: open
tags: [map]
---

# AI tooling upgrade

**Kevin, 2026-10-22:** raised switching from Gemini to OpenAI's API (GPT Live for voice, GPT-6 Sol
for reasoning) and was interested in their Decisions API for the pure boolean decision making here.
He then asked, given newer sandboxed agent products such as Grok Bot and Dots, what LEGION could
upgrade with the latest AI technology.

Research: `research/2026-10-22-landscape.md`.

## Destination

A decided upgrade list, each item with a build ticket, so LEGION uses what changed in AI between
September and October 2026 where it pays, and stays put where it does not.

## Tickets

- 01 decision (resolved): which provider for voice and reasoning, whether decision models earn a place,
  which agent-platform upgrades to build
- 02 build: the daily brief (5am server job, HOME button, modal)
- 03 build: engine MCP on the 2026-07-28 spec with OAuth, for Claude and the Gemini app
