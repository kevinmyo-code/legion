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

**Kevin, 2026-10-22:** *"im thinking of switching to openai's api instead of gemini. using their gpt
live, gpt 6 sol for reasoning etc. and i was interested in their decisions api... can speed up some of
the pure bool decision making here"*, and then *"new tech has come, things like grokbot and dots from
openai, they run on their own sandboxes etc. what can we upgrade to legion with the latest ai
technology?"*

Research: `research/2026-10-22-landscape.md`.

## Destination

A decided upgrade list, each item with a build ticket, so LEGION uses what changed in AI between
September and October 2026 where it pays, and stays put where it does not.

## Tickets

- 01 decision (resolved): which provider for voice and reasoning, whether decision models earn a place,
  which agent-platform upgrades to build
- 02 build: the daily brief (5am server job, HOME button, modal)
- 03 build: engine MCP on the 2026-07-28 spec with OAuth, for Claude and the Gemini app
