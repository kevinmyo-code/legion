---
map: aspect-advisors
ticket: 11
title: Token and latency budget
type: task
status: resolved
status-detail: ""
blockers: ["01", "08", "12"]
blocked-by: ["[[01-advisor-contract]]", "[[08-aspect-digests]]", "[[12-lean-toolbox]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Token and latency budget

## Question

Measure, don't guess: with the advisor contract and digest shapes decided, estimate per-question
cost (playbook brief + digest + question tokens on Gemini Flash, on Kevin's key) and latency on
the voice path (a coaching answer arriving 8 seconds after "am I on track?" is a different
product than 2 seconds). Also the standing cost: how many new tools the effort adds to the live
session prompt (currently 69 tools; MEMORY.md flags every tool as per-session token cost) and
whether one `ask_advisor(aspect, ...)` tool holds. Deliverable: numbers in the resolution, plus
a stated ceiling the build tickets must respect. Dispatch the analyst agent for the arithmetic.

## Answer

Analyst pass, 2026-08-13. Method: real tokenizer where possible - gemini-3.5-flash-lite countTokens
(free, no generateContent calls made) against a key present in local.properties. Fallback chars/4
is used only where noted. All sample digests/briefs below are constructed proxies (no DigestBuilder
or harness prompt exists yet) built to the exact shapes ticket 08/09/01 specify, so their CONTENT is
`reasoned`, their TOKEN COUNTS on that content are `measured`.

### 1. Compact text vs JSON digest - CONFIRMED, not just inherited

Built one realistic digest per aspect in both compact-labelled-text and equivalent-JSON, per
ticket 08's specced fields (targets/actuals/gaps/trend/tier/goal/exceptions):

| Aspect | Text tokens | JSON tokens | Saving |
|---|---|---|---|
| BIO | 186 | 336 | 44.6% |
| CRED | 293 | 453 | 35.3% |
| FLEET | 247 | 411 | 39.9% |
| LOG | 177 | 267 | 33.7% |
| HOME | 180 | 294 | 38.8% |

Mean saving 38.5%, every sample at or above 33.7%. Confirms ticket 08's ~30-40% estimate - if
anything it undersold BIO and FLEET a little. `measured` on these five constructed pairs;
`reasoned` that a real DigestBuilder's output will land in the same range (same field density,
same bracket-and-colon punctuation profile).

### 2. Lean-toolbox chars/4 method - sanity-checked against a real tokenizer

Ran countTokens on LiveToolbox.kt lines 100-1171 with `//`-comment lines stripped (the same slice
research ticket 12 measured by chars/4): 56,427 chars -> 13,597 tokens measured, a ratio of 4.15
chars/token. Ticket 12's chars/4 heuristic on its own tighter 43,829-char (whitespace-trimmed)
figure gives 10,957 tokens; applying the measured 4.15 ratio to that same figure gives ~10,561
tokens. Both land inside the ticket's stated 10,000-11,000 band - chars/4 was accurate to within
~4%, not the "could run 20-30% hot" the ticket flagged as a risk. The caveat that remains
genuinely unmeasured: this is Kotlin source (fn(name=..., schema(...)) scaffolding), not the
literal JSON FunctionDeclaration bytes the API serializes - close in density but not
byte-identical, so I did not shrink the ticket's own uncertainty band, I confirmed its center holds.

Extending the same measured ratio to the projected core-set: research's ~155 tokens/tool average
(43,829/71 chars, /4.15) is consistent with what I measured directly on individual tool blocks
below (ask_advisor alone: 946 chars -> 239 tokens, ratio 3.96 - same order). The core+discovery
~2.2-2.7k estimate is not independently re-measured against real declared JSON, but the per-tool
average it's built on now has tokenizer confirmation, not just chars/4. `measured` (ratio,
ask_advisor block) + `reasoned` (extension to the 12-15-tool core and to true wire JSON).

### 3. Advice-log window (last ~3 exchanges) - cost and affordability

Built one representative 3-exchange window (question + advice prose + proposal + outcome per
exchange, matching the goal-store ticket's gist/proposal/outcome shape): 194 tokens for all three
combined, i.e. ~65 tokens/exchange. `measured` on this one constructed sample; `reasoned` that
other aspects land in the same range (advice prose is bounded by the harness's own "keep answers
short" rule once that rule exists).

3 is affordable. It is the cheapest line item in the per-question budget - smaller than every
digest except LOG's (177 tokens, comparable), and an order of magnitude smaller than any playbook.
Even a pessimistic 3x (verbose advice text, ~600 tokens for 3 exchanges) would still be dwarfed by
FLEET's 2,909-token playbook. The playbook, not the advice log, is what the budget must watch.

### Per-aspect, per-question total (measured components, `reasoned` harness overhead)

Harness overhead: no harness prompt exists yet. Drafted one plausible instantiation of ticket 01's
"shared rules once in the harness prompt" (pull-only, tier-inheritance, no-arithmetic,
propose-not-write, crisis-stop, short-voice-answer) and measured it: 448 tokens. `reasoned`
content, `measured` token count of that draft - the real harness prompt the build ticket writes
may land smaller or larger.

Question tokens: a short spoken question ("Am I on track for my goal?") measures 8 tokens; a
longer multi-part one measures 34 tokens. Table below uses the short case; add ~25 for a compound
question.

| Aspect | Playbook (trimmed, no Sources) | Digest (incl. goals) | Advice-log x3 | Overhead | Question | TOTAL |
|---|---|---|---|---|---|---|
| BIO | 2,397 | 186 | 194 | 448 | 8 | 3,233 |
| CRED | 2,240 | 293 | 194 | 448 | 8 | 3,183 |
| FLEET | 2,909 | 247 | 194 | 448 | 8 | 3,806 |
| LOG | 1,994 | 177 | 194 | 448 | 8 | 2,821 |
| HOME | 208 (synthesis brief, no playbook) | 180 | 194 | 448 | 8 | 1,038 |

Playbook figures are the four research drafts with their `## Sources` section stripped (measured
separately: full drafts including Sources run 3,021-3,580 tokens - do not ship Sources to the
model, it is dev-facing licensing documentation, not advisor instruction, and costs 500-700 tokens
per aspect for zero coaching value).

### Standing live-session cost: today vs with the advisors' tools

- Today: ~10,500-11,000 tokens for 71 declared tools, tokenizer-confirmed per finding 2 above.
- Plus one ask_advisor(aspect, question) tool (drafted in LiveToolbox's own style, measured):
  +239 tokens -> ~10,750-11,250 (+~2%). Trivially affordable; ticket 01's call that one tool holds
  is correct on the numbers.
- Plus the full advisor/goal tool set (ask_advisor, accept_proposal, set_goal, list_goals,
  close_goal - 5 tools, drafted and measured together): +872 tokens -> ~11,400-11,900 (+~7-8% over
  today's baseline). Still small in isolation, but it is a permanent standing cost on every socket
  including prewarms that never carry a conversation (MEMORY.md's standing flag), and it stacks
  with whatever else lands next.
- What the core+discovery lean-toolbox shape changes: goal-store ticket 02 already commits to
  folding set_goal/list_goals/close_goal "into the aspect buckets the lean-toolbox research
  proposes so standing prompt cost stays flat." If that shape is adopted, the advisor/goal tools
  ride behind discover_tools(domain) instead of the declared toolset, and the standing socket cost
  stays near the core+discovery ~2.2-2.7k regardless of how many more advisor tools get added later
  - vs. paying the full ~872 tokens (and every future addition) on every socket forever under
  today's shape. This ticket does not re-decide adoption (12's own scope), but the number that
  should drive that decision is this: the toolbox is already a third of a 32k Live context window
  before a word of conversation, and the advisor effort is the first concrete proposal to grow it
  further - the segue that opened ticket 12 was correct to worry about exactly this addition.

### Latency - UNMEASURED, explicitly

No generateContent or Live-session calls were made (billed; out of scope for this pass). What
follows is `reasoned` from published model characteristics, not measured:

- askTyped is a one-shot REST POST on gemini-3.5-flash-lite with ~1,000-3,800 input tokens (per
  the table above) and an expected ~100-300 token spoken-length output. Flash-Lite's public
  latency profile is optimized for low time-to-first-token; a plausible ballpark for the full
  round trip is low single-digit seconds, but this is not measured and must not be treated as a
  number to design against.
- Calling ask_advisor from the Live session is a tool hand-off, not a normal instant tool: the
  Live model must emit the function call, LEGION makes the askTyped POST, then returns the result
  as a function response before the model can speak it. LEGION already has a working precedent for
  exactly this shape: diagnose_codes's own tool description (traced, service/LiveToolbox.kt line
  ~135) instructs the model to "Tell the driver you're digging into it before calling this - it
  takes a little while." The advisor tools should carry the same instruction rather than treat
  sub-agent latency as a novel problem.
- If/when ask_advisor moves behind discover_tools/call_tool (lean-toolbox adoption), ticket 12
  already reasoned the first-use cost at one additional model turn, order 0.5-1.5s - additive to
  the hand-off latency above, not a replacement for it.
- A real number requires an on-device spike (drive ask_advisor by voice on Kevin's phone, time
  first-token-to-audio) - that belongs to the build ticket's own verification section, not to this
  token-budget pass.

### Ceiling the build tickets must respect

- Playbook cap: 2,500 tokens, measured on the trimmed (no-Sources) text that actually ships in the
  brief. FLEET's current draft (2,909 tokens trimmed) exceeds this by ~409 tokens (~16%) and must
  be cut before it ships - candidates: condense the 17-row interval table (fold the Notes column
  into fewer words per row), tighten section 4 (seasonal/storage care) and section 5 (DIY-vs-shop
  cost heuristics), both of which run longer in prose than the other three playbooks' equivalent
  sections. BIO (2,397), CRED (2,240), LOG (1,994) are all under the cap already.
- Per-question total cap: 4,000 tokens for an aspect advisor (BIO/CRED/FLEET/LOG) - harness
  overhead + playbook + digest + advice-log window + question, all as they will actually ride the
  wire. Every aspect in the table above fits once FLEET's playbook is trimmed to the 2,500 cap
  (3,806 - 409 = 3,397, comfortably under 4,000 with headroom for a longer question or a slightly
  richer digest).
- HOME cap: 1,500 tokens - it has no playbook, so its total (1,038 measured) already sits well
  under; the 1,500 figure is headroom for a richer cross-aspect digest than the minimal sample
  built here, not a number HOME is close to hitting.
- Standing toolbox delta cap: the ask_advisor tool alone (+239 tokens, confirmed) is
  unconditionally affordable and should ship undeferred. The four goal-lifecycle tools (+633
  tokens beyond ask_advisor) should NOT be declared at the top level if the lean-toolbox shape
  (ticket 12) lands first - fold them into an aspect bucket behind discover_tools, per ticket 02's
  own stated plan, rather than adding another permanent 633 tokens to every socket. If ticket 12 is
  not adopted before the goal store ships, the 872-token full-set number is the one to budget
  against, and it is still affordable in isolation (~7-8% of today's baseline).

### Assumptions ledger

- Playbook token counts (trimmed and full, all four aspects): `measured` (countTokens,
  gemini-3.5-flash-lite, key from local.properties).
- Digest text/JSON token counts and the 33.7-44.6% saving range: `measured` on constructed
  samples; digest CONTENT itself is `reasoned` from ticket 08's specced fields, not read from a
  real DigestBuilder (none exists yet); generalization of the saving range to real code is
  `reasoned`.
- LiveToolbox 56,427-char/13,597-token measurement and the 4.15 chars/token ratio: `measured`.
  Extrapolation of that ratio onto ticket 12's 43,829-char figure, and onto the true JSON wire
  format rather than Kotlin source: `reasoned`.
- ask_advisor (239 tokens) and the 5-tool advisor/goal set (872 tokens): `measured` on drafted
  declarations written in LiveToolbox's own fn(...) style; the declarations themselves are
  `reasoned` proxies for what a build ticket will actually write, not the real thing.
- Harness-overhead draft (448 tokens) and advice-log-window sample (194 tokens/3 exchanges):
  `reasoned` content (no harness prompt or advice log exists yet), `measured` token count of the
  drafted text.
- Question tokens (8 short / 34 long): `measured` on two constructed sample utterances.
- All latency figures: `reasoned` from published Flash-Lite characteristics and LEGION's existing
  diagnose_codes hand-off precedent (`traced`, service/LiveToolbox.kt). No billed call was made;
  no latency number here is `measured`. An on-device spike is a build-ticket verification item,
  not something this pass can close.
- The playbook and per-question ceilings, and the FLEET-must-trim flag: `reasoned` from the
  measured table directly above them - not independently validated against any external token
  budget standard, since none is stated anywhere in the map or the contract tickets.
