# LEGION

LEGION is a study in trusted autonomy on commodity hardware: a voice-commanded agent system
that acts on real money, real vehicle data, and real sensors, engineered so that every claim
it makes is either verified, labelled as an estimate, or refused. One Android phone app, one
voice assistant, orchestrating a set of life domains ("aspects") through a Gemini Live speech
socket and a bounded tool-dispatch layer. No backend, no server-side component - it runs
entirely on the phone and the user's own Google Drive.

Solo-developer project. Public repo, private-use scale (two phones, one household). Read as a
portfolio piece, not a product.

## What it does today

| Domain | Concrete capability |
|---|---|
| Fleet | Live OBD-II telemetry over Bluetooth (ELM327), DTC read + a real write-path clear-codes flow with a refusal protocol, maintenance schedules, per-drive logging, oil-analysis and fault-trace views |
| Ledger | Bank-statement ingestion (DBS, Bank of America PDF exports) parsed deterministically first, LLM fallback only when no parser recognizes the layout, every row reconciled against the statement's own printed total |
| Pantry | Grocery receipt photo -> Gemini vision extraction (no deterministic path exists for photographs), reconciled against the receipt's printed total, per-item macros carried as explicit estimates |
| Body | Meal and workout logging, sleep tracking, macro/calorie estimation surfaced as estimates |
| Notes / Mail / Calendar | Lists, reminders, Gmail search/read, calendar read, all voice-driven |
| Goals / Advisors | Budget and habit targets with a structured (schema-constrained) LLM advisor pass |

Six domains, one voice loop. Tabs: Today, Money, Body, Fleet, Notes, Setup.

## The trust architecture

The reconciliation gate is the core rule (full text: `CLAUDE.md` §4). It is what makes LLM
extraction safe to use for money and health data at all, rather than a liability.

1. **Deterministic first, where a deterministic path exists.** Ledger tries its two hand-written
   PDF parsers before ever calling an LLM. Pantry has no born-digital source (a receipt is a
   photograph), so LLM vision is primary there by necessity, not preference.
2. **Extracted rows must reconcile against the document's own stated total, exactly.** Sum of
   line items equals the printed total, or the whole document quarantines. Nothing partial is
   ever written - a document either fully lands or produces nothing.
3. **A check that passes when nothing parsed is not a gate.** Every reconciliation layer must be
   unsatisfiable by an empty or partial extraction; an unrecognized line inside a section is a
   hard failure, never a silent skip. (This closed a real bug: a statement section's interest
   rows once fell outside every parser's pattern, matched zero rows, and reconciled clean against
   a genuinely-zero total that month - passing a gate it should have failed.)
4. **Money is `Long` cents, never `Double`.** The gate depends on exact integer equality.
5. **Every row is tagged with its provenance**: `DETERMINISTIC`, `LLM_RECONCILED`, or (for the
   narrow case where a source states no anchor at all, e.g. a mid-cycle CSV export with no
   printed total) `UNRECONCILED` - stored provisionally, always labelled unverified in the UI,
   and deleted the moment a gated document supersedes it.
6. **Anything the source document does not state is an estimate, and is labelled one everywhere
   it appears** - tool description and user-facing string both. Pantry's per-item macros are the
   clearest case: a receipt never prints calories, so they are excluded from the gate entirely
   and always rendered as a guess, never as fact.

## Architecture sketch

```
 Phone mic --> Gemini Live WebSocket (STS, server VAD, half-duplex)
                       |
                LiveSessionController
                       |
              LiveToolbox (declarations + dispatch)
              45 top-level declarations (down from 79) --
              5 high-volume domains collapsed behind
              ask_<domain> dispatcher tools to cut the
              per-turn setup re-bill by ~45%
                       |
        +--------------+---------------------------+
        |              |                            |
  direct tools    ask_<domain> -->            consequential tools
  (cheap, no      SubAgent.investigate         stay named and direct
  sub-agent)      (bounded Gemini Flash         (clear_codes: REFUSED
                  loop, same tool                protocol out of any
                  descriptions the live          sub-agent's hands)
                  model saw)
                       |
                  Room (SQLite, v25, additive-only migrations)
                       |
        Drive appDataFolder sync (BYO account, no server)
```

- **Voice loop:** `service/GeminiLiveSession.kt` (the Live socket), `service/LiveSessionController.kt`
  (turn state machine), `service/AriaForegroundService.kt` (keeps it alive as a foreground service).
- **Tool layer:** `service/LiveToolbox.kt` declares every tool Gemini can call and dispatches
  each one. Five domains (fleet, body, goals, pantry, mail) sit behind a single `ask_<domain>`
  tool apiece, which hands the question to a bounded `SubAgent.investigate` loop instead of
  exposing dozens of narrow tools to the live model directly - Gemini Live re-sends its entire
  tool block on every turn with no incremental update and no context caching, so tool count is a
  real, measured cost, not just a code-quality concern.
- **Sub-agents:** `ai/SubAgent.kt` - one-shot or bounded-investigate Gemini Flash calls, optional
  inline image part for vision (pantry receipts).
- **Storage:** Room, currently schema v25. Every version bump is an additive, verbatim-SQL
  migration with `exportSchema` on; no destructive fallback is used anywhere.
- **Sync:** Google Drive `appDataFolder`, the user's own account, no server in between.
- **Keys:** Gemini API key is pasted by the user, validated with a one-token ping, sealed in the
  Android Keystore. There is no shared or hosted key anywhere in this repo.

## Safety posture

- **The one destructive tool refuses by design.** `clear_codes` (DTC clear) runs a confirm/refuse
  protocol with an explicit `REFUSED` outcome state; it is deliberately kept out of the
  sub-agent dispatch path so a bounded LLM loop can never reach it directly.
- **A master proactive kill switch is designed to have no exemptions** - five categories
  (Safety, Timing, Wellbeing, Fleet, Digest), each independently toggleable, with a single
  master switch above them that is meant to silence everything. As of this writing that
  guarantee is not yet fully honored in code (see Honest status below) and is called out here
  rather than glossed over.
- **Crisis routing drops the persona.** `ai/CrisisDetector.kt` intercepts genuine distress and
  routes to real resources instead of staying in character - this is the one place "in
  character" is not allowed to continue.
- **No compulsion mechanics.** No streaks, no manufactured re-engagement, no guilt for time away.
- **Memory stays anchored to falsifiable external facts** (car data, statement data, receipt
  data) - a companion persona may be warm, but it may not invent unfalsifiable history.

## Engineering discipline

- **Every non-trivial claim in this project's working notes is tagged** `built` (compiled),
  `tested` (a test ran), `traced` (followed to its actual leaf calls), `reasoned` (inferred, not
  executed), or `on-device`. A `reasoned` claim is never allowed to read as a verified one.
  Full standing rules: `CLAUDE.md` §8.
- **Installs are verified by SHA-256 of the installed APK, never by an ADB "Success" message** -
  a "Success" result installing the wrong build cost a day's worth of on-device evidence early
  in this project.
- **Several real bugs were found only by pulling the on-device SQLite database and querying it
  directly** - a category-mapping error, a merchant-key regex swallowing dates, and a rows-vs-
  totals mismatch worth a five-figure sum of misclassified spend all passed the full unit suite and
  were only visible against real data.
- **A grep-clean result is not a build-clean result.** Symbol-level search cannot find a query
  against a dropped column or a file that was never copied - only a real compiler run does.
- Running ledger of failure modes and the rule each one graduated into:
  `memory/library/lessons.md`.

## Honest status

| Area | Status |
|---|---|
| Fleet OBD read, maintenance, drive logging | Verified on-device against a real 1998 Jeep XJ over ELM327 |
| DTC clear-codes write path + REFUSED protocol | Built, installed, hash-verified, `REFUSED` produced for real on-device. **Never exercised on an actual car** (no fault present to clear) |
| Ledger ingestion (DBS/BofA parsers + LLM fallback + reconciliation gate) | Unit-tested against generated fixture PDFs; real device data has surfaced bugs the suite missed (see above), since fixed |
| Pantry receipt ingestion | Unit-tested against canned model output; DB write path not covered by the test suite (Robolectric content-resolver gap) |
| Tool-block dispatcher split (79 -> 45 declarations) | Built, on-device; one dispatched write path (meal logging) broke on first real use and was fixed and re-verified same day |
| Unit test suite | **1485 tests, 2 known failing** (`BioDigestBuilderTest`), confirmed pre-existing on a clean worktree at HEAD. This is stated plainly, not rounded up |
| Onboarding UI | Not built. The identity/system-prompt plumbing exists; no screen hosts it |
| Wake word | Blocked - the Vosk model asset was never actually added to the repo, only documented |
| Google Drive sync | Built, never executed against real data - both devices have diverged locally with nothing to reconcile them yet |
| Master proactive kill switch | Two of the app's proactive paths (`AmbientListener`, `TelephonyController`) currently bypass the shared gate, so the master switch cannot yet silence everything it is meant to |
| Android Auto surface | Installed, never plugged into a head unit |
| Firebase / crash reporting | Absent by design. Logging goes through `Log.d`; a swallowed exception is currently invisible off-device |

## Build / run

```
export JAVA_HOME=/path/to/a/jdk-with-jlink   # Android Studio's bundled JBR works
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew compileDebugKotlin -Pnokey   # compiles with no baked-in key - the honest first-run path
./gradlew testDebugUnitTest            # unit suite (see Honest status for current pass rate)
./gradlew assembleDebug
```

`local.properties` needs `sdk.dir` (Android SDK path). `GEMINI_API_KEY` there is optional and
convenience-only - the app is designed to run without one and prompt the user for their own key
on first launch. A release build additionally needs four `RELEASE_STORE_*` values.
`gradle.properties` deliberately never hardcodes a JDK path, so a stranger's machine is not
required to match this one.

**Known clone-and-run gap:** Google's Drive Android OAuth client is keyed to the combination of
package name and the app's signing certificate SHA-1. A stranger who clones this repo, changes
nothing, and builds with their own signing key will compile and run the app fine, but Drive sync
authorization will fail against Google's console configuration as shipped here. This is a real,
open gap, not a hypothetical one - it is the reason the sync architecture is described as
"designed for BYO Drive" rather than "verified BYO Drive" above.

## Provenance

LEGION was pivoted from a private, single-purpose car-launcher predecessor on 2026-07-31: a
clean-history copy of only the code worth keeping, re-scoped from a head-unit product to a
phone-only assistant with no commercial model. The fleet domain is a direct port; ledger and
pantry are largely new design work built around the reconciliation gate. Everything before that
date lives in a private, frozen archive and is not part of this repo's history. One lesson from
that port carried forward as a standing rule here: a grep-clean migration is not a verified one -
only a real compiler run, and later a real device, find what search cannot.
