---
map: async-lookups
ticket: "02"
title: "Build: non-blocking read tools on the Live socket"
type: build
status: built
status-detail: "Built 2026-10-28 on feat/async-read-tools, suite green, assembleFast builds. Non-blocking set: ask_fleet, ask_body, ask_goals, ask_pantry (Kevin 2026-10-28: bookkeeping is not a write). Follow-up: the WORKING indicator shows only in THINKING, not while a lookup runs under the holding line (step 4). Ticket 04 run 2 fixes, built not yet on-device: (A) a late WHEN_IDLE result was denied, so LOOKUP_HOLD_CLAUSE now ties it to its question; (B) the fleet router spent its one round on list_vehicles, so it now gets the cars and the dongle state up front."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: non-blocking read tools on the Live socket

Implements ticket 01. Phone only. Branch `feat/async-read-tools`, off `dev` at `e838ccb2`.

## Files

- `app/src/main/java/com/kevin/legion/service/GeminiLiveSession.kt`: `withBlockingBehavior`
  (the choke point, line ~3474) and `sendToolResponse` (~971).
- `app/src/main/java/com/kevin/legion/service/LiveSessionController.kt`: `handleToolCall`, the
  `remainingActiveToolCalls` restore, `toolRunningFor`, `SUB_AGENT_TOOLS`.
- `app/src/main/java/com/kevin/legion/service/LiveToolbox.kt`: `isMutatingTool`, the declaration
  site of each listed tool.
- `app/src/main/java/com/kevin/legion/ai/AriaBrain.kt`: the "One moment." clause (~1197).
- `app/src/main/java/com/kevin/legion/service/FollowUpWindow.kt`: `Elapsed(toolsInFlight)` already
  treats a running tool as not-silence; confirm it still holds when the tool outlives the turn.
- The settings screen that holds "Smart end of turn" and "Interrupt <name>" (find it; both are
  from ADR 0064) and its preference store.
- Tests: `LiveBlockingBehaviorTest`, `AriaBrainHonestyClauseTest`, `FollowUpWindowTest`,
  `LiveSessionControllerToolCallRestoreTest`.

## Steps

1. **Declaration stamping.** Replace the blanket stamp with
   `withToolBehavior(declarations, nonBlocking: Set<String>)`: a name in the set gets
   `behavior: NON_BLOCKING`, every other declaration gets `BLOCKING`, an explicit `behavior`
   already present is left alone, the caller's array is never mutated. The set comes from a new
   `LiveToolbox.NON_BLOCKING_READ_TOOLS` (ticket 01 rule 2) and is EMPTY when the setting is off.
   Rewrite the doc comment: it now says why writes block and why reads may not.
2. **Prove the set is read-only.** A test asserts `NON_BLOCKING_READ_TOOLS` is disjoint from every
   name `isMutatingTool` returns true for, and that `ask_engine`, `aspect_clerk`, `ask_mail` are
   not in it. Read each listed tool's dispatch before adding it; drop any that writes and say so in
   the report.
3. **Scheduling.** In `sendToolResponse`, when the tool is in the non-blocking set, put
   `"scheduling": "WHEN_IDLE"` INSIDE the `response` object next to the tool's own fields (Google's
   Live tools page, read 2026-10-28: `response: { result: ..., scheduling: ... }`). Never on a
   blocking tool. Log `LIVE_LATENCY tool_ms=... tool=... sent=... async=true` so a capture can
   tell the two apart; `tools/voice_latency.py` should accept the extra field without breaking
   (ticket 03 owns the script's new columns; here just do not break its parser).
4. **Phase machine.** Today a tool runs only during THINKING. Now a tool can finish while the
   model is SPEAKING (the holding line), LISTENING (holding line done, follow-up window armed) or
   after the user has started a new turn. Walk `handleToolCall`'s restore: the "last tool done and
   phase still THINKING" guard already refuses to stomp SPEAKING; make sure a tool finishing in
   LISTENING does not flip the strip to THINKING or close the mic, and that `toolRunningFor`
   still shows WORKING on the Matrix bar while a non-blocking tool runs. Add the pure cases to
   `LiveSessionControllerToolCallRestoreTest`.
5. **Follow-up window.** With the holding line spoken and `turnComplete` received, the 8 s window
   arms while the lookup is still in flight. `Elapsed(toolsInFlight > 0)` is already "not
   silence". Confirm the in-flight count the window reads includes non-blocking tools, and that
   the answer's own `turnComplete` re-arms normally. Pin it in `FollowUpWindowTest` if a new case
   is needed.
6. **Cancellation.** `toolCallCancellation` handling (`CancelledToolReport`) was built for
   blocking tools. A non-blocking read cancelled by barge-in: do not send the response, log it, no
   report needed (nothing was written). Confirm by reading, add a test only if the path changes.
7. **Prompt.** Extend the 2026-10-27 clause in `AriaBrain.kt`: before a lookup, one brief
   in-character line and then the call; while it runs, say nothing more; never state a figure,
   a date, a name or an outcome that the result has not yet given; when the result arrives, answer
   from it. Keep it at file scope beside `CANNOT_CLAUSE`, not per persona. Add the presence test.
8. **Setting.** "Answer lookups while talking" (copy must not name the assistant), default ON,
   beside the ADR 0064 switches. Off empties the non-blocking set at the next `buildSetup`
   (a running session may keep its declarations until it reconnects; say so in the subtitle if
   that is the behaviour).
9. **Docs.** `docs/adr/0065-read-only-lookups-may-be-non-blocking.md` (format:
   `.claude/skills/domain-modeling/ADR-FORMAT.md`; references ADR 0031, ADR 0064, ticket 01).
   `memory/library/decisions.md` entry dated 2026-10-28. README "Tools" bullet: one sentence.
   `docs/architecture/c3-voice-loop.md` if it describes tool blocking. Run `python tools/docs_check.py`
   (two pre-existing ADR 0061 problems are known), `python tools/obsidian_sync.py`,
   `python tools/pending_wiki.py`.

## Verification

- `./gradlew compileDebugKotlin -Pnokey` green.
- `./gradlew testDebugUnitTest`: totals from `app/build/test-results` JUnit XML, 0 failures
  (baseline on dev: 5,379 tests; `AppFoldersScreenshotTest` is a known flake).
- `./gradlew detekt`: no new issues (4 pre-existing).
- `./gradlew assembleFast` builds. Install is Kevin's (ticket 04).
- On-device (ticket 04): "any trouble codes?" gives a holding line, then the answer arrives
  unprompted; the `LIVE_LATENCY` line shows the tool finished after the holding line started.

## Findings from ticket 04 run 2 (A25, gemini-3.8-live, 2026-10-28)

`WHEN_IDLE` works: after each async lookup the model ended its turn, the result arrived, and the
model opened a new turn by itself. Two failures, both fixed on this branch, neither yet heard on
the phone:

- **A late result was denied.** `ask_body` "What did I eat yesterday?" came back with five meals,
  1,083 ms after turn_complete; the new turn said "I don't have that information." No holding line
  was spoken on that turn. Reasoned cause: `CANNOT_CLAUSE` and `DATA_ACCESS_CLAUSE` both key on a
  tool "this turn", and a `WHEN_IDLE` result opens a turn with no user speech. Fix:
  `LOOKUP_HOLD_CLAUSE` now says such a result belongs to the question that asked for it, counts as
  this turn's result, is never denied, and when it lacks what was asked is described instead; the
  router's relay frame names the question as the user's and says to answer it. Presence is pinned
  in `AriaBrainHonestyClauseTest`; obedience needs the next phone run.
- **The fleet router spent its one round on discovery.** "Any trouble codes on the Jeep?" made the
  router call only `list_vehicles`. The premise that a codes read needs an id first is wrong: no
  fleet tool takes an id, the stored-record tools already take `vehicle` by name, and the live
  codes tools read only the car the dongle is in. The router could not know which car that was.
  Fix: `LiveToolbox.fleetRouteContext` appends the cars (each under a name `VehicleResolver`
  matches), the active car and the dongle state to the router's system instruction. Pinned in
  `LiveToolboxFleetRouteContextTest`; whether the real router then picks a codes tool needs the
  next phone run. With no dongle, the honest answer is stored code history plus "live codes need
  the dongle", not a live read.
- **First run, `clear_codes` mis-route (not fixed here).** "Any trouble codes" went to
  `clear_codes`, which refused in 6 ms. Its description says the confirmed=false call "reads the
  stored codes", which makes the one live-declared tool that mentions codes sound like a reader;
  `ask_fleet` lists "trouble codes" only mid-list among nine other subjects. Proposed, for Kevin:
  in `clear_codes`, "it reads the stored codes and returns" becomes "it previews what would be
  erased and returns", and "Use when the user asks to clear codes, reset the check engine light,
  or erase trouble codes." becomes "Use ONLY when the user asks to clear, reset or erase codes; to
  read or ask about codes, use ask_fleet." Roughly token-neutral.

## Out of scope

Timeout changes (ticket 04 measures first), `ask_mail`, `ask_engine`, the head unit, typed chat.
