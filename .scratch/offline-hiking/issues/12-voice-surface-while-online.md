---
map: offline-hiking
ticket: 12
title: "Decision: Alfred's hiking tools while online"
type: decision
status: open
status-detail: ""
blockers: ["11"]
blocked-by: ["[[11-how-an-area-is-picked]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Decision: Alfred's hiking tools while online

## Question

With signal, Alfred can help before and after a hike: "download the map for the state park we're
going to this weekend", "what's the trail like", "how far did we hike Saturday". How many tools,
what shape, and what may each one do?

## Constraints (traced 2026-11-09)

- **Payload headroom is about 17 tokens** on the Live setup message (`LiveSetupPayloadSizeTest`;
  AGENT-RULES known baselines). A new tool costs its name, description and schema.
  `ClaudeSessionToolbox` set the precedent: one tool with an `action` field, not four tools.
- Pull-based tools only (CLAUDE.md §7); no hike context injected into the prompt.
- Every tool has a hands path on the regions or hike screen calling the same controller (ADR 0035).
- Outcome verbs only after the SDK reports it: a download is "started" when `OfflineManager`
  accepted it and "downloaded" only when the tile region completes (§7).

## Options

| Option | Shape | Cost |
|---|---|---|
| A. No voice tools; screens only | Downloads and logs by hand. | 0 tokens. Loses the "this weekend" planning Kevin asked for. |
| **B. One `hiking` tool with actions** | `download_area` (by place name or "here"), `list_areas`, `delete_area`, `hike_log` (read summaries), `share_plan` (opens the share sheet; Kevin presses send). | One tool's schema. Must fit the headroom, or something else is trimmed first. |
| C. Fold into `navigate` | Add `mode: walk` and `offline: true` to the existing tool. | Muddles a driving tool's description; the model starts offering offline maps on a commute. |

## Recommendation

**B, sized to the headroom, after the v1 screens ship.** Measure the payload first; if one tool
does not fit, trim existing descriptions before adding (AGENT-RULES). The trail-planning talk
("is that loop hard", "how long will it take") needs no tool: Gemini answers from its own knowledge
online, labelled as general knowledge, not as LEGION's data.

## What `download_area` may not do

- Pick the area from a Mapbox search result and keep it (ticket 11 on the ToS question).
- Start a download on mobile data unless Kevin said so (ticket 10).
- Run unasked: no "you have a calendar event at a park, shall I download it" raise in v1. If wanted
  later it passes the compulsion test (anchored to the calendar event, actionable, silenceable).
