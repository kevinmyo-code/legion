---
map: generated-ui
title: "Map: The phone answers in generated UI, the PC keeps a fixed one"
charted: 2026-08-25
charted-by: "Kevin + Opus"
effort: "`.scratch/generated-ui/`"
tickets: 7
open: 7
status: open
tags: [map]
---
# Map: The phone answers in generated UI, the PC keeps a fixed one

## Destination

**The phone becomes voice-first with the AI rendering the answer, while the coming PC surface keeps
a fixed, browsable UI.** Kevin, 2026-08-25: *"the PC app that consumes the supabase backend will
have a fixed rich UI. on a phone you dont really need all that. just push to talk + wake word, and
I ask the AI - how much have I spent? ai flashes a generated UI of my ledger with what i asked."*

Destination is DECISIONS locked and the schema plus renderer specified. Building follows, and
deliberately NOT while the backend arc's data-layer phases are in flight.

## Notes

**Domain:** LEGION (CLAUDE.md binds where not explicitly superseded here).

The approach is Server-Driven / Generative UI as Kevin's own research framed it: the model emits a
strict JSON schema, a native Jetpack Compose renderer maps it to real components. Not generated
code, not a webview. The alternatives surveyed and rejected as whole-hog adoptions, though worth
reading for prior art: Google A2UI and MCP for streaming UI schemas, DivKit and Cash App Redwood
for server-driven native rendering, Vercel's json-render for enforcing strict LLM output.

### The two-surface split is what makes this legal

[[0035-every-voice-capability-has-a-hands-path]] says every voice capability has a non-voice path.
It never said on the SAME DEVICE, and the PC surface satisfies it for most capabilities. **The
exception is narrow and real: anything time-critical and phone-local.** The ADR's own worst case is
`answer_call` - the call arrives, the assistant mishears, and a PC in another room is not a
fallback. Ticket 04 owns the amendment.

### The safety property the whole map hangs on

**The model chooses the VIEW. Tools supply the DATA.** If the LLM emits layout and values together
it can render a balance it invented, and a card reading `$1,240` asserts that figure exactly as
much as saying it aloud does. A binding-based schema (`{component, source_tool, params}`) means the
model is picking presentation over data it cannot author. This also makes CLAUDE.md §4 rule 5 and
rule 7 expressible in the renderer: the card shows `unverified` and `as of` because the tool result
carries them, not because the model remembered to. Ticket 02 owns it.

### Standing constraints carried forward

- **A generated view is speech.** The outcome-verb rule and the estimates-are-labelled rule apply
  to what a rendered card claims, not only to what is spoken.
- The renderer emits mission-control components ([[0023-design-language-mission-control]]) or the
  app grows a second visual language.
- Schema-validate before rendering. A malformed or hostile payload fails to a worded error, never
  a crash and never a blank.
- Token cost and latency are design inputs, not afterthoughts: a binding is small, a data payload
  is not.

## Decisions so far

- **The split itself** (Kevin, 2026-08-25): phone is voice-first with generated answers, PC is a
  fixed rich UI. Recorded here rather than in a ticket because it is the map's premise.

## Not yet specified

- **What the PC surface actually is** - platform, framework, and whether it shares anything with
  the phone beyond the Supabase schema. Ticket 06 scopes only the parts that constrain the phone.
- **Sequencing against the backend arc.** This map must NOT run concurrently with
  `.scratch/backend-erp/` phases 2 to 6. Those rewrite the data layer under every screen; running a
  UI paradigm change at the same time means two large uncertainties at once and no way to tell
  which one broke something. The single exception is ticket 07, which is time-boxed BEFORE phase 6
  deletes the code it needs to read.
