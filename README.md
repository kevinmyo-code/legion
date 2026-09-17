

# LEGION

LEGION is a study in trusted autonomy: a voice-commanded agent system that acts on real money,
real vehicle data, and real sensors, engineered so that every claim it makes is either verified,
labelled as an estimate, or refused.

It is a **personal-life ERP** with two clients over one engine. A household runs its own Django
server; an Android app is the voice limb, a React web app is the desk surface, and a Postgres
database is the single system of record. Aspects are the modules, records are the transactions,
and the primary user interface is a Gemini Live speech socket bound by honesty rules.

Solo-developer project. Public repo, private-use scale (one household, a handful of people). Read
as a portfolio piece, not a product.

https://github.com/user-attachments/assets/c32ebf9b-8f26-41fd-9167-d63e413733b6

## What it does today

| Domain | Concrete capability |
|---|---|
| Fleet | Live OBD-II telemetry over Bluetooth (ELM327), DTC read plus a real write-path clear-codes flow with a refusal protocol, maintenance schedules, per-drive logging, oil-analysis and fault-trace views |
| Ledger | Bank-statement ingestion parsed deterministically first, LLM fallback only when no parser recognizes the layout, every row reconciled against the statement's own printed total |
| Pantry | Grocery receipt photo to Gemini vision extraction (no deterministic path exists for photographs), reconciled against the receipt's printed total, per-item macros carried as explicit estimates |
| Body | Meal and workout logging, sleep tracking, macro and calorie estimation surfaced as estimates |
| Checklists | Recurring and one-off lists, ticked per day. A tick records both the day it counts for and the instant of the tap, so "when did I last tick toothpaste" is answerable from history that deleting the list does not take with it |
| Events | Reminders, appointments, and coursework deadlines imported from Canvas. An event passes whether or not you engage with it; a task is due and can be done, and the two are different row kinds on purpose |
| Places / Voice notes / Memory | Tagged places with arrival triggers, recorded audio with its transcript and summary kept together, durable facts anchored to things outside the conversation |
| Goals / Advisors | Budget and habit targets with a structured, schema-constrained LLM advisor pass, and a cross-aspect daily digest |
| Music | Spotify voice control on the user's own client ID: play by name resolved against their own library before the catalogue, a 20-action control surface, now-playing read from Spotify's own pushed player state |

Six life domains, one voice loop, two clients. The Android shell opens on HOME, a single surface
holding the day, what needs doing, and folded per-aspect rows that tap through to their own
screens.

(The sync layer keys off nine `ASPECT_*` names, which is a different and wider sense of the word
than the six domains `CLAUDE.md` section 1 rules on. The two lists have never been reconciled and
this README does not pretend they have.)

## What you can actually say to it

<!-- VOICE-SURFACE:START -->

**120 voice tools across 15 domains**, dispatched from one Gemini Live
socket. Every one is declared with a schema and a description written to constrain the
model rather than to sell the feature.

| Domain | Tools | Something you would say |
|---|---|---|
| Getting started | 9 | &ldquo;Give me a sitrep&rdquo; |
| Your day | 12 | &ldquo;Remind me to renew my registration next month&rdquo; |
| The cars | 28 | &ldquo;Any trouble codes?&rdquo; |
| Driving | 2 | &ldquo;Open the garage&rdquo; |
| Money | 11 | &ldquo;What's my balance?&rdquo; |
| Food and shopping | 8 | &ldquo;Scan this receipt&rdquo; |
| Training and sleep | 10 | &ldquo;Squats, five at a hundred kilos&rdquo; |
| Goals and advice | 9 | &ldquo;I want to save five grand by December&rdquo; |
| Music | 4 | &ldquo;Play the Roadtrip playlist&rdquo; |
| Mail and calendar | 5 | &ldquo;Any mail from the garage?&rdquo; |
| Phone calls | 3 | &ldquo;Answer it&rdquo; |
| Recordings | 4 | &ldquo;Record this meeting&rdquo; |
| Memory | 3 | &ldquo;Remember that the XJ takes 5W-30&rdquo; |
| Settings and control | 3 | &ldquo;Call yourself Alfred&rdquo; |
| Your own trackers | 9 | &ldquo;What are you tracking for me?&rdquo; |

Full list with plain-language descriptions: **[docs/voice.html](docs/voice.html)** -
generated from `LiveToolbox.kt`, so a tool that exists is on the page or the build fails.

<!-- VOICE-SURFACE:END -->

## The trust architecture

The reconciliation gate is the core rule (full text: `CLAUDE.md` section 4). It is what makes LLM
extraction safe to use for money and health data at all, rather than a liability.

1. **Deterministic first, where a deterministic path exists.** Ledger tries its hand-written PDF
   parsers before ever calling an LLM. Pantry has no born-digital source (a receipt is a
   photograph), so LLM vision is primary there by necessity, not preference.
2. **Extracted rows must reconcile against the document's own stated total, exactly.** Sum of
   line items equals the printed total, or the whole document quarantines. Nothing partial is
   ever written as fact.
3. **A check that passes when nothing parsed is not a gate.** Every reconciliation layer must be
   unsatisfiable by an empty or partial extraction; an unrecognized line inside a section is a
   hard failure, never a silent skip. This closed a real bug: a statement section's interest rows
   once fell outside every parser's pattern, matched zero rows, and reconciled clean against a
   genuinely-zero total that month, passing a gate it should have failed.
4. **Money is `Long` cents, never `Double`.** The gate depends on exact integer equality.
5. **Every row is tagged with its provenance**: `DETERMINISTIC`, `LLM_RECONCILED`, or, for the
   narrow case where a source states no anchor at all, `UNRECONCILED` - stored provisionally,
   always labelled unverified in words, and deleted the moment a gated document supersedes it.
6. **Anything the source does not state is an estimate, and is labelled one everywhere it
   appears**, in the tool description and the user-facing string both. Pantry's per-item macros
   are the clearest case: a receipt never prints calories, so they are excluded from the gate
   entirely and always rendered as a guess.
7. **A gate that discards its own inputs leaves rows nobody can re-verify.** Every ingestion path
   stores the numbers it checked against, in their own columns, beside the rows they gated. A
   provenance tag records the verdict; it is not evidence.

The same posture governs speech. An outcome verb - done, sent, booked, played - may follow only a
tool call that came back successful in that turn. A tool that failed is treated as no tool at all.

## Architecture

Two clients, one engine, one database. The household hosts it; nothing is hosted for strangers.

```
   Android app                         Web app (React, Vite, TanStack)
   voice + sensors + offline           desk surface: review, edit, ingest
        |                                        |
        |  Room replica, per-aspect outbox       |
        |  reads local when the engine is down   |
        +------------------+---------------------+
                           |
                   Django + DRF  (the engine, and the only writer)
                           |
                      PostgreSQL
                  scoped by household
```

- **Django is the engine** (ADR 0044). One household-hosted server owns the data. A business rule
  lives there once, never also in Kotlin; an integrity rule that must hold even if Django has a
  bug is SQL shipped by a migration.
- **The phone never writes Postgres directly.** It writes through the engine, and when the engine
  is unreachable it keeps reading from its Room replica, queues the write in an outbox, and says
  so in words. Freshness is lost when the server is down; function is not.
- **Households are the tenancy boundary** (ADR 0045), and membership is the only authorization.
  One engine may hold more than one family, each all-or-nothing to its members. There are no
  approval workflows and exactly one role, which exists only to invite and remove people.
- **Voice loop:** `service/GeminiLiveSession.kt` (the Live socket),
  `service/LiveSessionController.kt` (turn state machine), `service/AriaForegroundService.kt`
  (keeps it alive as a foreground service).
- **Tool layer:** `service/LiveToolbox.kt` declares every tool Gemini can call and dispatches each
  one. Several domains hide their read tools behind a single `ask_<domain>` tool that hands the
  question to a bounded sub-agent loop, because Gemini Live re-sends its entire tool block every
  turn with no incremental update and no context caching, so tool count is a measured cost rather
  than a code-quality concern. Write tools were deliberately pulled back OUT of that split after
  two real mis-routed writes: a mis-routed read costs a worse answer, a mis-routed write either
  lands in the wrong store or gets claimed and never happens.
- **Every voice capability has a hands path** (ADR 0035). Voice is the fastest way in and it is
  the way that fails - a loud room, a wake word that does not fire, a mic that opens deaf, all
  observed on the real phone. Both paths call the same controller; two implementations of one
  capability drift into disagreeing.
- **Keys:** the Gemini API key is pasted by the user, validated with a one-token ping, and sealed
  in the Android Keystore. There is no shared or hosted key anywhere in this repo.

## Safety posture

- **The one destructive tool refuses by design.** `clear_codes` runs a confirm/refuse protocol
  with an explicit `REFUSED` outcome state, and is deliberately kept out of the sub-agent dispatch
  path so a bounded LLM loop can never reach it directly.
- **Crisis routing drops the persona.** `ai/CrisisDetector.kt` intercepts genuine distress and
  routes to real resources instead of staying in character. Known gap: the resource is US-only.
- **No compulsion mechanics.** No streaks, no manufactured re-engagement, no guilt for time away.
  An unsolicited raise must be anchored to a fact the user could check himself, be actionable now,
  never reference his absence or engagement, and be silenceable forever in one instruction.
- **Third-party content is read-through only.** Anything other people wrote *to* the user - mail
  first - may be read to answer a question and is then dropped. Never persisted, never synced,
  never remembered. The guarantee is that it was never stored, not that something remembered to
  exclude it, so the exclusion lives at the write sites rather than being a habit each new feature
  has to recall. A recording the user deliberately starts is first-party and is kept.
- **Memory stays anchored to falsifiable external facts.** A persona may be warm; it may not
  invent unfalsifiable history with the user.

## Engineering discipline

- **Every non-trivial claim in this project's working notes is tagged** `built`, `tested`,
  `traced`, `reasoned`, or `on-device`. A `reasoned` claim is never allowed to read as a verified
  one. Full standing rules: `CLAUDE.md` section 8.
- **Installs are verified by SHA-256 of the installed APK, never by an ADB "Success" message.** A
  "Success" result installing the wrong build cost a day's worth of on-device evidence early on.
- **Several real bugs were found only by pulling the on-device SQLite database and querying it**:
  a category-mapping error, a merchant-key regex swallowing dates, and a rows-versus-totals
  mismatch worth a five-figure sum of misclassified spend. All three passed the full unit suite.
- **A grep-clean result is not a build-clean result.** Symbol search cannot find a query against a
  dropped column or a file that was never copied; only a real compiler run does, and after that,
  only a real device.
- **A ticket's own verification steps are binding gates, not notes.** Nothing is reported built
  until each step is done, deferred with a named follow-up, or impossible with the reason stated.
- Running ledger of failure modes and the rule each graduated into: `memory/library/lessons.md`.

## How the work is managed

All planning is in the repo, versioned like code, and most tracking surfaces are generated from it
rather than maintained by hand.

- **Live progress wiki: <https://kevinmyo-code.github.io/legion/>** - every open ticket across
  every active effort on one page, grouped by map, filterable by ready / blocked / decision /
  buildable. Served by GitHub Pages from `docs/index.html` and regenerated on every planning
  change, so it cannot drift from the tickets it renders.
- **Work is charted as maps and tickets** under `.scratch/<effort>/` - one `map.md` per effort and
  one Markdown file per ticket, with machine-readable status and blocked-by frontmatter.
- **The derived layer is generated, never hand-edited.** `tools/obsidian_sync.py` rewrites the
  ticket board and a dependency-graph canvas per map; `tools/pending_wiki.py` renders the wiki.
  The repo root doubles as an Obsidian vault.
- **Decisions live in two stores with a hard boundary**: `docs/adr/` says what is binding right
  now, with superseded ADRs keeping their original text; `memory/library/decisions.md` is the
  append-only dated log of what happened when.
- **Architecture is C4 levels 1-3** as Mermaid in `docs/architecture/`. Level 4 is deliberately
  absent; it goes stale the day it lands.
- **Docs are checked, not trusted**: `tools/docs_check.py` fails on any source path named in
  `docs/` that no longer exists, on broken ADR supersession links, and on wikilinks pointing at
  nothing. `tools/voice_guide.py` fails the build when a voice tool has no user-facing copy.

## Honest status

Verified 2026-09-16 unless stated. Numbers here are counted from build output, not from memory.

| Area | Status |
|---|---|
| Android unit suite | **3,549 tests, 0 failures, 0 errors, 0 skipped**, counted from the JUnit XML across 376 result files |
| Web client suite | **52 tests across 6 files**, green; `tsc --noEmit` and the production build both clean |
| Server suite | **718 passed, 42 skipped**, run against a real Postgres rather than SQLite (22 minutes) |
| Room schema | **v70**, additive-only migrations, `exportSchema` on, no destructive fallback anywhere |
| Voice surface | **120 tools across 15 groups**, every one carrying user-facing copy or the build fails |
| Django engine | Deployed to Cloud Run and serving the web client. All nine sync aspects default to it, but **only four were run end to end on a phone** (events, checklists, body, memory); places and voice notes had their pull path exercised and nothing more; **ledger, pantry and fleet have never run against Django on hardware at all**. They were flipped because the Supabase path now fails a `household_id NOT NULL` constraint, not because they were proven |
| Tenancy | One Python choke point (`SyncedModelViewSet`). **ADR 0045 describes Postgres row-level security as the backstop under it; that does not exist** - no `CREATE POLICY` or `ENABLE ROW LEVEL SECURITY` anywhere in `server/`. A missed filter is currently a leak, not a no-op |
| Supabase | Not retired, mid-cutover. `supabase-kt` is still in the build, 12 `Supabase*Backend.kt` files remain, and an install with no engine address or token falls back to Supabase for everything |
| Web client | Live: sign-in, today, month calendar, lists with optimistic ticks and list deletion |
| Fleet OBD read, maintenance, drive logging | Verified on-device against a real 1998 Jeep XJ over ELM327 |
| DTC clear-codes write path and REFUSED protocol | Built, installed, hash-verified, `REFUSED` produced for real on-device. **Never exercised on an actual car** - no fault present to clear |
| Ledger ingestion | Unit-tested against generated fixture PDFs. Real device data has surfaced bugs the suite missed, since fixed |
| Pantry receipt ingestion | Unit-tested against canned model output; the DB write path is not covered by the suite (Robolectric content-resolver gap) |
| Canvas coursework import | 145 task rows on the real device. A bug that made every one of them invisible to the voice layer shipped and survived three weeks, because `read_calendar` filtered on the wrong row kind |
| Checklist tick history | Built and tested, including a live-engine round trip proving a tick outlives the list being deleted. **The voice question has never been asked on the phone** |
| Spotify voice control | Built and unit-tested. **Never run on a phone, and nothing in the Spotify layer has ever run against a real account** - the headline claim (playback with Spotify closed) is unverified |
| Eval harness | First real report exists: clerk/honesty/quarantine/grounding suites at 100% (N=1), tone-judge honestly WEAK at 73% on a genuine rubric ambiguity |
| Screenshot tests | Roborazzi baselines at the device's own profile, verifying inside the ordinary JVM suite |
| Onboarding UI | Not built. The identity and system-prompt plumbing exists; no screen hosts it |
| Wake word | Live, verified by ear on-device. Battery cost still unmeasured |
| Master proactive kill switch | Two proactive paths bypass the shared gate, so the master switch cannot yet silence everything it is meant to |
| Android Auto surface | Installed, never plugged into a head unit |
| Crash reporting | Absent by design. Logging goes through `Log.d`; a swallowed exception is invisible off-device |

## Build / run

**The phone app:**

```
export JAVA_HOME=/path/to/a/jdk-with-jlink   # Android Studio's bundled JBR works
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew compileDebugKotlin -Pnokey   # compiles with no baked-in key, the honest first-run path
./gradlew testDebugUnitTest            # read totals from app/build/test-results, not the console
./gradlew assembleDebug
```

`local.properties` needs `sdk.dir`. `GEMINI_API_KEY` there is optional and convenience-only: the
app is designed to run without one and prompt the user for their own key on first launch.
`gradle.properties` deliberately never hardcodes a JDK path, so a stranger's machine is not
required to match this one.

**The engine and the web client:**

```
cd server
uv pip install -r requirements.txt     # note: `uv sync` empties the venv here
uv run --no-sync python manage.py migrate
uv run --no-sync python manage.py runserver

cd server/frontend
npm install && npm run dev
```

`deploy/docker-compose.yml` brings up Django and Postgres together. Clone-and-run means clone,
compose up, invite the household, one URL.

**Known clone-and-run gap:** Google's Drive Android OAuth client is keyed to the combination of
package name and the app's signing certificate SHA-1. A stranger who clones this repo and builds
with their own signing key will compile and run the app fine, but Drive authorization will fail
against the console configuration as shipped here. This is a real, open gap, not a hypothetical.

## Provenance

A clean-history copy of only the code worth keeping, re-scoped from a head-unit product to a
phone-first assistant with no commercial model. The fleet domain is a direct port; ledger, pantry,
and everything server-side are new design work built around the reconciliation gate. Everything
before that lives in a private, frozen archive and is not part of this repo's history.

One lesson from that port carried forward as a standing rule: a grep-clean migration is not a
verified one. Only a real compiler run, and later a real device, find what search cannot.
