# LEGION

A voice-first personal-life ERP. One household runs its own server, and an AI assistant you talk
to can read and change real records: money, cars, food, training, calendar, notes. Every claim it
makes is verified, labelled as an estimate, or refused.

Solo-developer project. Public repo, private-use scale (one household). Read it as a portfolio
piece, not a product.

https://github.com/user-attachments/assets/c32ebf9b-8f26-41fd-9167-d63e413733b6

## At a glance

| | |
|---|---|
| **Clients** | Android phone app (`app/`), React web app (`server/frontend/`), car head-unit app (`headunit/`) |
| **Engine** | Django + DRF over Postgres, the only writer, scoped by household |
| **Voice** | Gemini Live speech-to-speech, on-device end-of-turn detection, interruptible |
| **AI on the side** | Flash-Lite sub-agents for domain lookups, a text model for typed chat, vision for receipts |
| **Phone stack** | Kotlin, Jetpack Compose, Room, ONNX Runtime |

## What it does

| Area | What you get |
|---|---|
| Assistant | One persona (Alfred by default, renameable). Tap to talk or wake phrase. Waits for you to finish a thought instead of cutting in at a pause, and you can talk over it to stop it. Typed messages go to a text model with the same tools |
| Money | Bank feed via Plaid, plus statements parsed on the server and reconciled to the cent against the statement's own totals |
| Cars | Live OBD-II over Bluetooth, trouble codes (read, and a guarded clear), maintenance schedules, drive logs |
| Food | Receipt photos read by vision and checked against the printed total; macros shown as estimates |
| Body | Meals, workouts, sleep |
| Day | Calendar, reminders, Canvas coursework, checklists with tick history, places with arrival triggers |
| Notes and memory | Recorded voice notes with transcript and summary, durable facts the assistant remembers |
| Goals | Budget and habit targets with an advisor pass and a daily brief |
| Music and maps | Spotify voice control; in-app turn-by-turn on Mapbox with spoken cues |
| Extras | Can be the phone's home screen (HOME is the calendar), a games launcher, an ebook and comic reader |

## What you can say to it

<!-- VOICE-SURFACE:START -->

**127 voice tools across 16 domains**, dispatched from one Gemini Live
socket. Every one is declared with a schema and a description written to constrain the
model rather than to sell the feature.

| Domain | Tools | Something you would say |
|---|---|---|
| Getting started | 8 | &ldquo;Give me my brief&rdquo; |
| Your day | 14 | &ldquo;Remind me to renew my registration next month&rdquo; |
| The cars | 28 | &ldquo;Any trouble codes?&rdquo; |
| Driving | 6 | &ldquo;Take me to the hardware store&rdquo; |
| Money | 11 | &ldquo;What's my balance?&rdquo; |
| Food and shopping | 9 | &ldquo;When did we last buy shampoo?&rdquo; |
| Training and sleep | 10 | &ldquo;Squats, five at a hundred kilos&rdquo; |
| Goals and advice | 9 | &ldquo;I want to save five grand by December&rdquo; |
| Music | 4 | &ldquo;Play the Roadtrip playlist&rdquo; |
| Mail and calendar | 5 | &ldquo;Any mail from the garage?&rdquo; |
| Books | 1 | &ldquo;Read me Moby-Dick&rdquo; |
| Phone calls | 3 | &ldquo;Answer it&rdquo; |
| Recordings | 4 | &ldquo;Record this meeting&rdquo; |
| Memory | 3 | &ldquo;Remember that the XJ takes 5W-30&rdquo; |
| Settings and control | 3 | &ldquo;Call yourself Alfred&rdquo; |
| Your own trackers | 9 | &ldquo;What are you tracking for me?&rdquo; |

Full list with plain-language descriptions: **[docs/voice.html](docs/voice.html)** -
generated from the `*Toolbox.kt` files in `service/`, so a tool that exists is on the page or the build fails.

<!-- VOICE-SURFACE:END -->

## How it stays honest

- **LLM extraction only behind a reconciliation gate.** Parsed rows must add up exactly to the
  document's own printed totals, or the whole document is quarantined. Deterministic parsers run
  first; the LLM is the fallback. Full rule: `CLAUDE.md` section 4.
- **Money is `Long` cents**, and every row carries its provenance (`DETERMINISTIC`,
  `LLM_RECONCILED`, `BANK_API`, `UNRECONCILED`). Anything the source does not state is an estimate
  and says so.
- **The assistant never claims an outcome it did not observe.** "Done", "sent", "played" may only
  follow a tool call that succeeded in that turn.
- **The one destructive tool refuses by design.** Clearing car fault codes runs a confirm/refuse
  protocol and is kept out of reach of the sub-agents.
- **Other people's content is read-through only.** Mail is read to answer, then dropped. Never
  stored, synced or remembered.
- **No compulsion mechanics.** No streaks, no "you haven't talked to me in a while".
- **Distress drops the persona** and routes to real resources (US-only for now).

## Architecture

```
  Phone (voice, sensors, offline)     Web (desk)        Head unit (car)
            |  Room replica + outbox      |                   |  MCP
            +-----------------------------+-------------------+
                                          |
                          Django + DRF  (engine, only writer)
                                          |
                               PostgreSQL, scoped by household
```

- **Voice loop:** a Gemini Live socket (`service/GeminiLiveSession.kt`) driven by a turn state
  machine (`service/LiveSessionController.kt`).
- **Turn-taking on the phone** (ADR 0064): Silero VAD and Pipecat Smart Turn on ONNX Runtime
  decide when you are done, with Gemini's server VAD as the fallback. Call-style audio with echo
  cancellation lets you interrupt mid-reply.
- **Tools:** read tools are grouped behind `ask_<domain>` routers so Live's per-turn tool block
  stays small; write tools stay direct.
- **Offline:** the phone reads its Room replica and queues writes when the engine is down, and says
  so.
- **Every voice capability has a by-hand path** that calls the same controller (ADR 0035).
- **Bring your own key:** the Gemini key is pasted by the user and sealed in the Android Keystore.
  No hosted key anywhere.

Deeper: C4 diagrams in `docs/architecture/`, standing decisions in `docs/adr/`, the dated log in
`memory/library/decisions.md`.

## Status

Counted from build output, not memory. Live ticket board: <https://kevinmyo-code.github.io/legion/>.

| Area | Status |
|---|---|
| Android unit tests | 5,379 tests, 0 failures (2026-10-27) |
| Server tests | 718 passed, 42 skipped, against real Postgres (2026-09-16, not recounted) |
| Verified on real hardware | OBD read, maintenance and drive logging on a 1998 Jeep XJ; Spotify on a real drive; wake word; HOME; smart turn-taking and barge-in (2026-10-27) |
| Built, not yet run on hardware | Code clearing on a real fault, a real Mapbox drive, Books, some engine sync paths |
| Known gaps | No Postgres row-level security yet (tenancy is one Python choke point); Supabase not fully retired; onboarding screen not built; no crash reporting, by design |

## Build

```
./gradlew compileDebugKotlin -Pnokey   # compiles with no baked-in key
./gradlew testDebugUnitTest            # read totals from app/build/test-results
./gradlew assembleFast                 # daily driver: release build, debug-signed
adb shell cmd package compile -m speed -f com.kevin.legion   # after each install
```

`local.properties` needs `sdk.dir`. The API key is optional at build time; the app asks for the
user's own on first launch.

Engine and web: see `server/` and `deploy/docker-compose.yml`, which brings up Django and Postgres
together. The head unit is its own Gradle project: see `headunit/README.md`.

**Clone-and-run gap:** Google Drive's Android OAuth client is tied to this app's signing
certificate, so a build signed with your own key loses the phone's Drive backup until you register
your own client.

## How the work is managed

Planning lives in the repo: maps and tickets under `.scratch/`, rendered to the ticket board by
`tools/pending_wiki.py`. Docs are checked by `tools/docs_check.py`; the voice tables above are
generated by `tools/voice_guide.py`, and the build fails if a tool has no copy. Lessons from real
failures: `memory/library/lessons.md`.
