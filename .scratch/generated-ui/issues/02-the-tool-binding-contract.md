---
map: generated-ui
ticket: "02"
title: "The tool-binding contract: the model picks the view, tools supply the numbers"
type: grilling
status: open
status-detail: "Answered differently by the 2026-09-01 build: a closed-enum query spec, not {component, source_tool, params} bindings. Points 1-3 satisfied; point 4 (bindable-tool allowlist) and point 5 (actions in a card) still open."
blockers: ["01"]
blocked-by: ["[[01-the-response-schema]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The tool-binding contract: the model picks the view, tools supply the numbers

## Question

The safety property the map hangs on. Decide:

1. **Binding shape.** `{component, source_tool, params}` resolved by the renderer, versus the model
   inlining values it has already read. Recommend bindings, strongly: a model that emits values can
   emit wrong ones, and a rendered figure is an assertion. This is the §7 outcome-verb posture
   applied to pixels.
2. **Who executes the binding, and when.** The renderer calling the tool itself, or the session
   resolving bindings before handing the payload down. Latency, double-fetching and the "the model
   already called this tool this turn" case all live here.
3. **What happens when a binding fails.** The card must say what it could not read, in words. An
   empty card and an unreadable one are different sentences, exactly as CLAUDE.md §1 already
   requires for the calendar.
4. **Which tools are bindable at all.** Recommend an allowlist of READ tools. Nothing that writes,
   nothing with side effects, and nothing in `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` (third-party
   content is read-through only, §7 - a generated card must not become the place a mail body gets
   persisted as a screenshot of itself).
5. **Whether a card may carry an action**, and if so how a tap is authorised. This is where a
   read-only surface quietly becomes a write surface; decide it deliberately rather than by
   feature creep.

## Prior art in this repo

`service/LiveToolbox.kt` and `service/EngineToolbox.kt` already return structured results the model
consumes. The binding contract is largely a matter of declaring which of those are renderable and
what their result shape guarantees, rather than building a new data path.
