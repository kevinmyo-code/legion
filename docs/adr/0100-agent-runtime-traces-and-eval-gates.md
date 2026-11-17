---
status: accepted
decided: 2026-11-17
decided-by: Kevin
source: "[[decisions#2026-11-17 - Alfred's brain moves to the engine; the phone becomes a thin limb (Kevin)]]"
tags: [adr]
---

# 100. The agent runtime is a reusable package; traces and eval suites gate every release

## Standing

**The agent runtime is a small in-house package with no LEGION imports.** It covers:
- the loop;
- the typed turn state;
- the graph, with a checkpoint after every node;
- the model-provider seam.

An import-boundary test enforces that it never imports LEGION code, so a second deployment could reuse it. LEGION's specs, tools and guards plug into it.

**Every turn leaves a trace:**
- the router's decision and why;
- each model call: tokens, cached tokens, latency, estimated cost;
- each tool call, with its arguments redacted as the conversation record already does, and mail never recorded;
- guard hits;
- the final text.

Traces and turns are kept 30 days. They are visible only to their member.

**A prompt, tool or graph change does not ship if any eval suite's pass rate falls below its bar.** The suites run several times per case against invented household data. Real-model suites run in the laptop ship script only; no CI key exists. The deterministic checks run in CI on every push: routing, specs, guards, and cassette replay of recorded model output.

## Context

On 2026-11-16, three ship rounds were each caught only by real-model gates, run by hand. Kevin dropped the shadow week: the switch rides on the suites alone. The ruling: *"Ship every stage when checks pass"*.

A framework such as LangGraph would hide the parts that make Alfred trustworthy:
- per-turn taint;
- `writes` on every tool;
- confirm by an args hash;
- tenancy;
- the relay.

## Decision

- **Graders are deterministic first:**
  - route, tool and arguments;
  - no write without a yes;
  - every figure in the reply appears in a tool result of that turn;
  - the honesty check: an outcome verb with no successful action fails;
  - no empty reply.
  
  A model grades tone only, after calibration against labels Kevin gives once.
- **The switch to the engine brain needs three things:**
  - every suite run on both brains against the same cases, with the engine at or above the phone;
  - an on-phone smoke run over adb;
  - a per-member flag that turns it back.
- **Failed real turns become private cases** in a git-ignored folder. Real utterances never enter the public repo. An invented twin of a failed turn may.
- **Guards live in code, not prompts:**
  - each specialist's allowlist;
  - the write guard on taint held in the turn state;
  - confirm-before-write;
  - `visible()` on every tool;
  - the cost cap (`llm/cap.py`).

## Consequences

- A full live run costs about $0.50 (estimate) and takes a minute or two.
- The honesty check flags live turns but blocks nothing, since the text has already streamed. It fails hard only in evals.
