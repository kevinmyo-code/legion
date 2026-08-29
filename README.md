

# LEGION

LEGION is a study in trusted autonomy on commodity hardware: a voice-commanded agent system
that acts on real money, real vehicle data, and real sensors, engineered so that every claim
it makes is either verified, labelled as an estimate, or refused. One Android phone app, one
voice assistant, orchestrating a set of life domains ("aspects") through a Gemini Live speech
socket and a bounded tool-dispatch layer. No backend, no server-side component - it runs
entirely on the phone and the user's own Google Drive.

Solo-developer project. Public repo, private-use scale (two phones, one household). Read as a
portfolio piece, not a product.

https://github.com/user-attachments/assets/c32ebf9b-8f26-41fd-9167-d63e413733b6

## What it does today

| Domain | Concrete capability |
|---|---|
| Fleet | Live OBD-II telemetry over Bluetooth (ELM327), DTC read + a real write-path clear-codes flow with a refusal protocol, maintenance schedules, per-drive logging, oil-analysis and fault-trace views |
| Ledger | Bank-statement ingestion (DBS, Bank of America PDF exports) parsed deterministically first, LLM fallback only when no parser recognizes the layout, every row reconciled against the statement's own printed total |
| Pantry | Grocery receipt photo -> Gemini vision extraction (no deterministic path exists for photographs), reconciled against the receipt's printed total, per-item macros carried as explicit estimates |
| Body | Meal and workout logging, sleep tracking, macro/calorie estimation surfaced as estimates |
| Notes / Mail / Calendar | Lists, reminders, Gmail search/read, calendar read, all voice-driven |
| Goals / Advisors | Budget and habit targets with a structured (schema-constrained) LLM advisor pass |
| Music | Full Spotify voice control on the driver's own client ID: play by name resolved against their own library before the catalogue, a 20-action control surface (queue, like, follow, shuffle, seek, add-to-playlist, "more from this artist"), now-playing read from Spotify's own pushed player state, replayable history. Spotify App Remote creates the active device, so it is designed to work with the Spotify app closed |

Six life domains plus music control, one voice loop. Home is a widget dashboard (a pager of
per-aspect cards backed live by the aspect engine); the classic per-domain screens (Today, Money,
Body, Fleet, Notes, Setup) are one tap away behind a "Classic" button, not retired.

## What you can actually say to it

<!-- VOICE-SURFACE:START -->

**115 voice tools across 14 domains**, dispatched from one Gemini Live
socket. Every one is declared with a schema and a description written to constrain the
model rather than to sell the feature.

| Domain | Tools | Something you would say |
|---|---|---|
| Getting started | 8 | &ldquo;Give me a sitrep&rdquo; |
| Your day | 11 | &ldquo;Add milk to the shopping list&rdquo; |
| The cars | 28 | &ldquo;Any trouble codes?&rdquo; |
| Driving | 2 | &ldquo;Open the garage&rdquo; |
| Money | 11 | &ldquo;What's my balance?&rdquo; |
| Food and shopping | 9 | &ldquo;Scan this receipt&rdquo; |
| Training and sleep | 10 | &ldquo;Squats, five at a hundred kilos&rdquo; |
| Goals and advice | 9 | &ldquo;I want to save five grand by December&rdquo; |
| Music | 4 | &ldquo;Play the Roadtrip playlist&rdquo; |
| Mail and calendar | 5 | &ldquo;Any mail from the garage?&rdquo; |
| Phone calls | 3 | &ldquo;Answer it&rdquo; |
| Memory | 3 | &ldquo;Remember that the XJ takes 5W-30&rdquo; |
| Settings and control | 3 | &ldquo;Call yourself Alfred&rdquo; |
| Your own trackers | 9 | &ldquo;What are you tracking for me?&rdquo; |

Full list with plain-language descriptions: **[docs/voice.html](docs/voice.html)** -
generated from `LiveToolbox.kt`, so a tool that exists is on the page or the build fails.

<!-- VOICE-SURFACE:END -->

## The aspect engine: a personal-life ERP

As of 2026-08-24 the app's spine is a runtime **aspect engine** - the same shape Salesforce uses
for custom objects, arrived at independently: fixed metadata tables (`aspects`, `record_types`,
`field_defs`, `records`), never runtime DDL. A user can say *"make me a workouts aspect with
exercise, weight and reps"* and get a typed record store, generated list/detail/form screens,
dashboard widgets, and voice CRUD - all from one schema definition.

- **One write door.** `engine/RecordStore.kt` is the only writer of records; it enforces
  reference integrity, per-field delete policies (deleting a car with service history *refuses*,
  in words), a 30-day trash, and computed-field materialization. Thirteen field types; money is
  integer cents inside the payload.
- **All six legacy domains migrated onto it** in four reviewed waves, each verified against the
  real device database - counts exact, provenance preserved (the ledger's seven provisional rows
  stayed provisional; a drifted maintenance anchor survived un-merged next to the observed
  service that contradicts it). **Cutover is complete** (2026-08-24, five flips: notes+places,
  pantry, ledger, fleet, and the home flip itself) - every aspect reads and writes through the
  engine, the widget pager is the app's start destination, and the legacy tables are frozen
  writer-less (kept for one soak period, not yet dropped).
- **The data is legible outside the app**: one xlsx workbook per aspect mirrors into a
  user-picked Drive folder (hash-verified writes), doubling as the audit surface and the
  two-phone sync channel, with hand edits re-entering through a validating import gate.
- **The voice layer collapsed** from per-feature tools toward nine generic meta-tools plus a
  bounded executor sub-agent, and prompt-rule obedience is now measured, not hoped: an on-demand
  eval harness scores honesty, quarantine speech, and grounding per prompt version, and ten
  Roborazzi screenshot baselines guard the UI in the ordinary JVM suite.

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
              ~60 declarations visible to the live model --
              30 read tools across 5 domains collapsed behind
              ask_<domain> dispatcher tools (Live re-bills the
              whole tool block every turn); the 8 write tools
              were deliberately pulled back out to direct
              declarations after two real mis-routed writes
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
                  Room (SQLite, v37, additive-only migrations)
                       |
        Drive appDataFolder sync (BYO account, no server)
```

- **Voice loop:** `service/GeminiLiveSession.kt` (the Live socket), `service/LiveSessionController.kt`
  (turn state machine), `service/AriaForegroundService.kt` (keeps it alive as a foreground service).
- **Tool layer:** `service/LiveToolbox.kt` declares every tool Gemini can call and dispatches
  each one. Five domains (fleet, body, goals, pantry, mail) hide their read tools behind a single
  `ask_<domain>` tool apiece, which hands the question to a bounded `SubAgent.investigate` loop
  instead of exposing dozens of narrow tools to the live model directly - Gemini Live re-sends its
  entire tool block on every turn with no incremental update and no context caching, so tool count
  is a real, measured cost, not just a code-quality concern. The write tools were deliberately
  pulled back OUT of that split after two real mis-routed writes: a mis-routed read costs a
  slightly worse answer, a mis-routed write either lands in the wrong store or gets claimed and
  never happens.
- **Sub-agents:** `ai/SubAgent.kt` - one-shot or bounded-investigate Gemini Flash calls, optional
  inline image part for vision (pantry receipts).
- **Storage:** Room, currently schema v37. Every version bump is an additive, verbatim-SQL
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

## How the work is managed

All planning is in the repo, versioned like code, and most of the tracking surfaces are generated
from it rather than maintained by hand.

- **Live progress wiki: <https://kevinmyo-code.github.io/legion/>** - every open ticket across
  every active effort on one page, grouped by map, filterable by ready / blocked / decision /
  buildable. Served by GitHub Pages from `docs/index.html` and regenerated on every planning
  change, so it cannot drift from the tickets it renders.
- **Work is charted as maps and tickets** under `.scratch/<effort>/` - one `map.md` per effort
  (destination, settled decisions, out-of-scope) and one Markdown file per ticket, with
  machine-readable status and blocked-by frontmatter. A ticket's verification steps are binding
  gates, not notes: nothing is reported built until each step is done, deferred with a named
  follow-up, or impossible with the reason stated.
- **The derived layer is generated, never hand-edited.** `tools/obsidian_sync.py` rewrites the
  ticket board (`vault/Board.md`) and a dependency-graph canvas per map; `tools/pending_wiki.py`
  renders the wiki above from the same frontmatter. The repo root doubles as an Obsidian vault.
- **Decisions live in two stores with a hard boundary**: `docs/adr/` says what is binding right
  now (one file per standing decision, superseded ADRs keep their original text);
  `memory/library/decisions.md` is the append-only dated log of what happened when.
- **Architecture is C4 levels 1-3** as Mermaid in `docs/architecture/` - context, containers, and
  the paths worth a picture (voice loop, ingestion, music, data). Level 4 is deliberately absent;
  it goes stale the day it lands.
- **Docs are checked, not trusted**: `tools/docs_check.py` fails on any source path named in
  `docs/` that no longer exists, on broken ADR supersession links, and on wikilinks pointing at
  nothing.

## Honest status

| Area | Status |
|---|---|
| Fleet OBD read, maintenance, drive logging | Verified on-device against a real 1998 Jeep XJ over ELM327 |
| DTC clear-codes write path + REFUSED protocol | Built, installed, hash-verified, `REFUSED` produced for real on-device. **Never exercised on an actual car** (no fault present to clear) |
| Ledger ingestion (DBS/BofA parsers + LLM fallback + reconciliation gate) | Unit-tested against generated fixture PDFs; real device data has surfaced bugs the suite missed (see above), since fixed |
| Pantry receipt ingestion | Unit-tested against canned model output; DB write path not covered by the test suite (Robolectric content-resolver gap) |
| Tool-block dispatcher split | Built, on-device; one dispatched write path (meal logging) broke on first real use and was fixed and re-verified same day. Write tools have since been moved back to direct declarations |
| Spotify voice control (App Remote spine, 20-action surface, own-library-first playlists, replayable history) | Built and unit-tested, ten of twelve tickets merged. **Never run on a phone, and nothing in the Spotify layer has ever run against a real account** - the headline claim (playback with Spotify closed) is unverified |
| Aspect engine + data migration | Room v37, four migration waves senior-reviewed and **verified against the real device database** - counts exact, provenance preserved, including seven provisional ledger rows staying provisional and a drifted maintenance anchor surviving un-merged |
| Engine cutover | **Complete, all five aspects, device-verified 2026-08-24.** Notes+places, pantry, ledger, and fleet all read/write through `engine/RecordStore.kt`; the widget pager is the app's start destination (`WidgetPagerActivity` deleted, folded into `MainActivity`). 569 active engine records, zero duplicate guids, Kevin's own verdict on the phone: "i like it". Legacy tables frozen writer-less, not yet dropped |
| xlsx mirror / Drive | **Probed for real**: folder picked on-device, workbook written over SAF, read-back hash verified, opened from Drive. Two-phone round trip not yet exercised (second phone not updated) |
| Eval harness | First real report exists: clerk/honesty/quarantine/grounding suites at 100% (N=1), tone-judge honestly WEAK at 73% on a genuine rubric ambiguity |
| Screenshot tests | Ten Roborazzi baselines at the device's own 384x832dp profile, verifying inside the ordinary JVM suite |
| Unit test suite | **2,530 tests, green both key ways** (with and without a baked Gemini key) |
| Onboarding UI | Not built. The identity/system-prompt plumbing exists; no screen hosts it |
| Wake word | Live - verified by ear on-device 2026-08-20 ("hey alfred" opens a turn); battery cost still unmeasured |
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

clean-history copy of only the code worth keeping, re-scoped from a head-unit product to a
phone-only assistant with no commercial model. The fleet domain is a direct port; ledger and
pantry are largely new design work built around the reconciliation gate. Everything before that
date lives in a private, frozen archive and is not part of this repo's history. One lesson from
that port carried forward as a standing rule here: a grep-clean migration is not a verified one -
only a real compiler run, and later a real device, find what search cannot.
