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

## RECONCILED 2026-09-01: the destination narrowed, and part of this map shipped elsewhere

**This map's destination is superseded, and a slice of it was built without anyone consulting it.**
Both halves are recorded here because the reasoning above is still worth reading; it is the
premise that moved, not the argument.

**Two Kevin rulings narrowed the destination.** On 2026-08-30: *"not voice generated, voice called.
pre made modals"* - which is the opposite of "the AI renders the answer" for everyday surfaces. On
2026-09-01, home became a month calendar with a Meters tab, not push-to-talk. So the phone is NOT
voice-first with a generated answer as its main interaction. **The generated view survives as the
long tail only** - one-off questions no pre-made screen covers - which is a much smaller feature
than this map was chartered for.

**A generated view shipped on 2026-09-01** (`74db850`) under `.scratch/one-today/issues/06-*.md`,
written without checking whether a map already existed. That was a process failure: ticket 02's
own title, *"the model picks the view, tools supply the numbers"*, is the exact rule that ticket
restated as if deriving it. What shipped:
`service/GeneratedViewController.kt`, `service/GeneratedViewQueryRunner.kt`,
`ui/GeneratedViewHost.kt`, the `show_generated_view` tool, and an Ask pane on Meters as its hands
path. Run on the A25 on 2026-09-01.

**It took a different route than this map proposed, and the difference matters.** The map specified
tool BINDINGS (`{component, source_tool, params}`) resolved against an allowlist of read tools.
What shipped is a closed-enum QUERY SPEC - aggregation, source, window, grouping - executed by a
runner against existing controllers. Same safety property, reached differently: the model still
cannot author a value, and it now cannot name a tool either. Whether the binding shape is still
wanted for cases the enum cannot express is genuinely open, and ticket 02 is where that is
settled.

**Ticket-by-ticket, verified by reading the code rather than by memory:**

| Ticket | State after the 09-01 build |
|---|---|
| 01 response schema | **Partly answered.** Closed vocabulary (3 shapes) and flat composition shipped, provenance is a first-class field. **No `schema_version` exists** - grep confirms zero occurrences - so point 5 is entirely unaddressed |
| 02 tool-binding contract | **Answered differently.** Points 1-3 satisfied by the query-spec shape; points 4 (bindable-tool allowlist) and 5 (may a card carry an action) never arose and stay open |
| 03 the renderer | **Built, minus its own stated gate.** Validation-before-Compose, worded refusal and mission-control components all shipped. **No Roborazzi screenshot tests exist** for any component or refusal state, and this ticket says in writing that the refusal states matter more than the happy path |
| 04 ADR 0035 amendment | **Open, with new evidence.** The shipped view has a same-device hands path (Meters > Ask), so it did not need the amendment. The narrow phone-local case the ADR worries about is untouched |
| 05 the phone shell | **Point 1 decided by Kevin on 09-01**: home is the calendar, NOT push-to-talk - the field-test-first recommendation was overtaken by a direct ruling. Points 2 and 3 are answered by what shipped (a modal over the current screen; nothing persists). Points 4 and 5 stay open |
| 06 PC surface | **Open and untouched**, though ADR 0040 has since made `legion-web` the general client, which is the same split by another name |
| 07 harvest | Resolved 2026-08-26, unchanged |

**Nothing here is marked resolved on the strength of the 09-01 build.** Three tickets have real
gaps - no `schema_version`, no refusal-state screenshot tests, no decision on actions in a card -
and marking them done would be exactly the failure CLAUDE.md §12 warns about, where a
fully-decided-and-partly-built feature reads as finished work.

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
