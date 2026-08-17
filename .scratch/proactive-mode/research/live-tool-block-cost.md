# The Live tool block is billed on every turn - and it cannot be loaded lazily

Researched 2026-08-17, triggered by the [12h run result](12h-run-result.md). Model in use is
`models/gemini-3.1-flash-live-preview` (`GeminiLiveSession.kt:1555`), direct BYO key, **paid tier**
(confirmed by Kevin 2026-08-17).

## The measurement

`LiveToolbox.declarations()` builds **79 function declarations**. Measured from source by summing
string-literal characters in the declarations region (lines 114-1389), x1.3 for JSON structure, /4
chars-per-token:

| Domain | Tools | ~Tokens |
|---|---|---|
| fleet | 26 | 5,216 |
| money | 12 | 2,118 |
| notes | 4 | 1,478 |
| body | 14 | 1,282 |
| goals | 5 | 1,274 |
| pantry | 4 | 512 |
| core | 5 | 491 |
| media | 3 | 374 |
| mail | 2 | 317 |
| place | 4 | 250 |
| **TOTAL** | **79** | **13,316** |

`manage_item` alone is ~1,004 tokens - more than media, mail and place combined.

**The 1.3x and /4 factors are estimates.** Treat 13,316 as the right order of magnitude, not a
measured token count. `reasoned`, from `built` character counts.

## What the API allows - all `quoted` from Google's docs

1. **No mid-session tool update.** The client-to-server union has exactly four members: `setup`,
   `clientContent`, `realtimeInput`, `toolResponse`. `tools[]` exists only on
   `BidiGenerateContentSetup`, "Session configuration to be sent in the first message". Changing
   the tool set requires a new WebSocket. ([Live API reference](https://ai.google.dev/api/live))
2. **No `toolConfig` on Live.** `BidiGenerateContentSetup`'s field list does not include it, so no
   `functionCallingConfig`, no `allowed_function_names`. And **no doc anywhere states that
   restricted declarations stop being billed** - `not-documented`, so it cannot be relied on even
   if it were reachable.
3. **The whole context window is re-billed every turn.** "The API charges you per turn for all
   tokens present in the session context window... Past tokens are re-processed and accounted for
   in each new turn." System instructions "are counted as part of the input tokens"; tools "are
   also counted", reported as `total_tool_use_tokens`.
   ([best practices](https://ai.google.dev/gemini-api/docs/live-api/best-practices))
4. **No context caching for Live.** The implicit-cache model list names no Live model, explicit
   caching requires `generateContent`, and `BidiGenerateContentSetup` has no `cachedContent` field.
   The pricing page has no cached-token row for this model.
5. **Session resumption exists** (`sessionResumption`, handles valid 2h) but a resumed connection
   still sends a full `setup` including `tools`, so it does not dodge the re-bill. `inferred`.
6. **Google's own guidance: "Keep active set to 10-20 tools maximum."** 79 is roughly 4x that.
   ([function calling](https://ai.google.dev/gemini-api/docs/function-calling))

## Consequence

The only lever is what goes into `setup`. "Lazy loading" in the literal sense is impossible; the
achievable form is **dispatcher tools** - one declaration per domain routing to a Flash REST
sub-agent that holds the domain's real tools. `SubAgent.investigate` already implements exactly
this loop, and four tools (`diagnose_codes`, `triage_symptom`, `ask_maintenance`,
`check_cold_start`) already use it.

**Decided by Kevin 2026-08-17:** five dispatchers (`ask_fleet`, `ask_body`, `ask_goals`,
`ask_pantry`, `ask_mail`) absorbing reads and routine reversible logging. Consequential writes stay
live and named: `clear_codes` (confirm/REFUSED protocol, unverified on a car), `manage_vehicle`,
`register_vehicle`, `set_odometer`, `set_maintenance_interval`, `activate_garage` (physical
action), `set_goal`, `close_goal`, `accept_proposal` (consent protocol), `set_meal_target`,
`set_sleep_target`. Target ~5,300 tokens, a ~60% cut.

**Three tools can never be dispatched**: `import_receipt`, `import_statement`, `show_saved_places`
return null from `LiveToolbox.dispatch` because they open a screen. A sub-agent has no screen, so
dispatching them would be a silent no-op.

**A safety condition, not an option:** `EPISODIC_EXCLUDED_TOOLS` must gain `ask_mail`.
`GeminiLiveSession` matches the read-through rule on the function-call name **as it arrives off the
socket**, and after this change the socket only ever sees `ask_mail`. Without it the mail-to-memory
leak closed in `b0711cb` reopens through the dispatcher.

## What was NOT established

- Whether an idle Live session that completes `setup` and never exchanges a turn is billed at all.
  Billing is defined per turn and a setup-only session has no turns, so it should be free -
  **`inferred`, not stated in any doc.** This matters for the overnight reconnect loop (64 sockets,
  0 turns). Check the API usage page for 2026-08-17 00:14-08:07 rather than trusting this.
- Whether `contextWindowCompression`'s sliding window can evict the setup tools block.
  `not-documented`; the sliding window is described over conversational history.
