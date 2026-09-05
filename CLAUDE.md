# CLAUDE.md

Single source of truth for LEGION. Opus/Fable plans, Sonnet executes, subagents report.
Created 2026-08-01 from the 2026-07-30/31 pivot off Midnight AI. This file holds RULES.
`memory/MEMORY.md` holds STATE.

## Read order

1. **`memory/MEMORY.md`** first. Thin dashboard: what is happening now, blockers, in-flight,
   notes for next session. Always loaded, under 80 lines.
2. **This file (CLAUDE.md)**. Locked architecture, frozen decisions, guardrails. Changes rarely.
3. **`docs/`**. How it fits together and what is binding. `docs/architecture/` is C4 levels 1-3,
   `docs/adr/` is the standing decisions with their supersession history, `docs/glossary.md` is the
   vocabulary index. Docs link into this file and never restate it; if they disagree with CLAUDE.md,
   CLAUDE.md wins and the doc is a bug.
4. **`README.md`**. Public-facing build status, per-aspect detail, verification history. It is
   the authority on what compiles and what is tested; do not duplicate it here.
5. **Anything deeper**: dispatch the `scout` agent against `memory/library/`.
   Card catalog: `memory/library/INDEX.md`. Do not bulk-read shelf files into the main context.
   **Most of that library is FROZEN Midnight AI history** (see §11) - it is reference, not rules.
6. **`TEAM.md`**. Subagent roster and dispatch cadence.
7. **`.claude/skills/`**. Vendored skills, ported from Midnight AI 2026-08-01. Chris Banes's
   Kotlin/Compose guidance (verbatim), plus repo-specific `wayfinder`, `to-spec`, `grillme`, and
   `issue-tracker.md`. **`wayfinder` has no plugin equivalent and exists only here.** Provenance and
   the list of the eight files adapted for LEGION are in `.claude/skills/ATTRIBUTION.md`.

If MEMORY.md and CLAUDE.md disagree: **MEMORY.md wins for state, CLAUDE.md wins for rules.**

## What belongs in this file

**Rulings, not observations.** A ruling is a decision someone made and it does not rot. An
observation describes the code, and the code moves without anyone editing the sentence that
described it.

The test, applied 2026-09-01 when this file was cut from 778 lines to its present size: **does this
state a rule, or does it describe what exists?** A version number, a count, a package map, an
inventory of what is built - those are observations. They belong where they are computed, and every
one of them was wrong at least once here.

What that cut removed, and where it went:

| Was | Now |
|---|---|
| Tech-stack table | Read `build.gradle.kts`. The dropped-dependency list stayed - that one is a ruling |
| Room version changelog | `sed -n '/version = /p' …/CarDatabase.kt`. The migration rules stayed |
| Codebase map | `Glob`, and `docs/architecture/` |
| "Not built yet" list | The board, `docs/index.html` |
| The narrative each carried | `library/claude-md-observations-2026-09-01.md`, verbatim |

**Sixteen lines of this file were struck-through self-corrections** before that cut - `CORRECTED`,
`STALE`, a rule reversed and left visible beside its replacement. That is what an observation looks
like after it rots, and it is the tell to watch for. When correcting a rule, state the live one and
put the history in `library/decisions.md`; the strikethrough belongs there, not here.

The same test now governs `.claude/agents/` (see `TEAM.md`), where the cost was concrete: a stale
device model in a prompt had three separate agents reporting work on a phone that does not exist.
**A stale instruction is not ignored. It is obeyed.**

---

## 1. Identity

- **Product:** LEGION, one **Android phone app**. A single voice assistant orchestrating
  **aspects** of life. Not a launcher, not a head-unit product, not a commercial product.
- **The one-line identity (Kevin, 2026-08-24): a personal-life ERP, queryable and CRUD-able by a
  voice agent.** Aspects/record types/field defs are the master-data layer (fixed metadata tables,
  never runtime DDL), records are the transactions, capability plugins are the modules, widgets
  are the reporting layer, the xlsx mirror is the audit-and-export surface. Three deliberate
  deviations from real ERP: data trustworthiness is first-class (the §4 gate + provenance), the
  primary client is a voice agent bound by honesty rules, and the trust model is two adults with
  BYO everything - so no roles, no tenancy, no approval workflows, ever.
- **Register: Alfred/JARVIS is a BAND, not a name.** A tool with a personality. Not a mascot, not
  the car. Competent, dry, useful. The register copy lives in `ai/Personas.kt`; `AssistantIdentity`
  resolves it. **LEGION is the app; the thing Kevin talks to is a named companion he picks per
  profile**, and the persona's name is swappable. **Never hardcode an assistant name into copy.**
- **The assistant is a CONCIERGE, not a car companion (2026-08-20, Kevin).** The prompt layer
  called the user "the driver" throughout, and the model answered accordingly - a greeting about the
  weather came out as a greeting about the weather *for a drive*. It says "the user" now, and
  `ai/AriaBrain.kt`'s `ASSISTANT_FRAME` states the frame at the head of the shared instructions: the
  person may be at a desk, in a kitchen, in bed, or occasionally in a car, and the assistant must
  never assume which. `ai/PromptRoleNamingTest.kt` fails the build on the next one, with an
  allowlist for where "driver" is genuinely right. **The fleet aspect is unchanged** - car context
  is injected only when the OBD dongle is connected, the one signal that says he is IN a car rather
  than merely owns three. Anything spoken verbatim gets the same treatment.
- **A proactive prompt states its facts or forbids its subject.** Asking the model to mention
  what is "coming up" while handing it no schedule is not a neutral prompt - it is a request for
  content with no source, and it produced an invented lunch appointment with a person who does not
  exist (2026-08-21). An unsolicited raise pre-fetches the facts of anything it invites the model to
  mention, or says in words that it does not know. **Unreadable and empty are different sentences:**
  a `ContentResolver` returns an empty list for a refused permission and for a clear day, and
  rendering the first as the second tells the user they are free when the app cannot see.
  `calendar/OpenerCalendarBriefing.kt` is the worked example;
  `.scratch/proactive-mode/issues/10-what-a-raise-may-say.md` owns the general rule.
- **Never hand the model an IANA timezone id.** `America/Chicago` is a database key that happens to
  contain a city, and asserting it made the assistant talk about Chicago to a man in Houston. The
  clock is a UTC offset, the place comes from `LocationController`, and with no fix it says the
  location is unknown rather than guessing one.
- **Aspects:** fleet (OBD, car, maintenance, drives), ledger (money), pantry (groceries and
  macros), notes, dates, places. Six domains, and the list is a decision - a seventh is a ruling,
  not a refactor. **Where each one stores its data is not recorded here**; it has moved twice and
  this table was wrong both times. Read the controller.
- **Repo:** `C:\Users\Kwin\StudioProjects\legion` (second machine: `C:\Users\kevin\AndroidStudioProjects\legion`), public, `github.com/kevinmyo-code/legion`.
  Package `com.kevin.legion`. Clean history, seeded 2026-07-31 by copying surviving Midnight AI
  source.
- **MIDNIGHT_AI (`C:\Users\Kwin\StudioProjects\MIDNIGHT_AI`) is a FROZEN ARCHIVE.** Private, read
  only, historical reference for what was ported. Never build there. Never write LEGION's project
  history into its memory files, which is what happened during the 2026-07-31 port session.
- **RUN SESSIONS FROM THIS DIRECTORY, not from MIDNIGHT_AI.** On 2026-08-01 a session did all its
  work here with its working directory set to the archive. Three things broke silently: `/wayfinder`,
  `/prototype` and every other slash-command resolved to the ARCHIVE's skills (the un-adapted copies
  that still enforce the dead frame-clock motion ban and head-unit preview sizes), the agent roster
  came from the archive's `.claude/agents/`, and Claude Code's own per-project memory was written
  under the MIDNIGHT_AI key. **A session started here gets a fresh, empty auto-memory**, which is
  precisely why the handoff lives in `memory/MEMORY.md` and this file rather than there.
- **Solo dev:** Kevin (kevinmyo@gmail.com).
- **Project Andromeda (`~/PycharmProjects/Andromeda`) retires now that ledger has ported.** Only
  its `duo_ledger.bronze` layer had real content; `silver`/`gold`/`categorization`/`fx`/`agent`
  are empty stubs.

---

## 2. Locked decisions from the pivot (2026-07-30/31)

None of these is re-openable without Kevin. Full record in `memory/library/decisions.md`
(2026-07-31 entries).

| Decision | Consequence |
|---|---|
| **Phone-only** | Head units may still install it; they no longer constrain design. The AOSP 8-10 ceiling, the frame-clock-only motion ban, and the ADB blackout are all LIFTED. Normal Compose animation is allowed. |
| **Commercial model is DEAD** | No billing, tiers, broker, trial, store listing, pricing, or positioning work. `billing/` was dropped entirely. Do not reintroduce it or reason about conversion. |
| **Clone-and-run is a HARD requirement** | A stranger clones, sideloads, signs in, and it works. This, not cost, is what rules out Firestore. It is also why `gradle.properties` must never hardcode `org.gradle.java.home`. |
| **Drive-BYO is the only store** | One shared Google account, two phones, `appDataFolder`. No Firestore, no Kevin-run backend, ever. |
| **One global assistant identity** | Cars are data, not identities. Per-car `CompanionProfile` keying and Midnight AI's `CompanionIdentity` Zero-vs-car-self split are both dead. |
| **The city-pop design language is DEAD** | With it: the mascot Zero, all generated art, `AvatarStudio`, `OccasionStylist`, `WallpaperPresets`, the two-identities decision. `ui/` is a deliberate clean slate, not a gap. **No replacement design language has been chosen yet.** |
| **LLM ingestion is ALLOWED, behind a reconciliation gate** | See §4. This reverses Midnight AI's "no LLM extraction" posture. |
| **Carry-over inventory ruled on (5 calls, 2026-07-31)** | Music: Spotify App Remote kept, no UI, mixtapes retired. Garage: kept, voice-only. Spend gate: retired, no ledger replacement yet. Fleet build/mod photos: retired (photo storage is pantry-ingestion-only). Tagged places: kept as-is. |

**Two findings that still threaten plans, both open:**
1. **Drive's Android OAuth client is keyed to package + SHA-1 signing cert**, so a stranger's own
   build fails authorization. This directly threatens clone-and-run. Unresolved.
2. **Drive has no compare-and-swap**, so today's shared-file last-write-wins sync will silently
   lose rows. Sync must become append-only. Unresolved.

**Do not assume the old wayfinder map applies.** The original
`.scratch/multi-aspect-assistant/map.md` (15 tickets, 12 contested calls) did not survive a
machine port - `.scratch/` was blanket-gitignored and was never committed. It is gone, not stale.

The live map is **`.scratch/ledger-drive-ingestion/map.md`** ("Ledger Drive-folder ingestion +
basic UI", charted 2026-08-01): 11 tickets in `issues/`, plus `research/`. As of 2026-08-01,
tickets 01 (SAF Drive-folder feasibility) and 02 (design language) are resolved; 03-11 are open.
**All of it is tracked in git** - `.gitignore` now whitelists `.scratch/*/map.md`,
`.scratch/*/issues/**`, and `.scratch/*/research/**` specifically so the earlier loss cannot
repeat. Commit map and ticket changes like any other file.

---

## 3. Tech Stack

Kotlin + Compose, single `app` module, Room, Gemini Live over a WebSocket, Supabase as the system of
record. **Read `build.gradle.kts` and `gradle/libs.versions.toml` for what is actually in the
build** - a list here would be wrong within a month, and has been.

**Dropped deliberately. Do not reintroduce without a ruling:** Mapbox, Firebase, Play Billing,
Media3, ZXing, PdfBox-Android. Each went for a reason recorded in `library/decisions.md`; adding one
back is a decision, not a dependency bump.

---

## 4. The reconciliation gate (the core architectural rule)

**LLM extraction is allowed only behind a deterministic reconciliation gate.** This is the rule
that makes ingestion trustworthy, and it is not negotiable per-feature.

1. **Deterministic first where a deterministic path exists.** Ledger: `DbsStatementParser` and
   `BofaStatementParser` are primary. `StatementDispatcher` falls to `LedgerStatementAgent` (LLM)
   **only** when neither recognizes the layout. Pantry has no deterministic path (receipts are
   photographed, not born-digital), so LLM vision is primary there by necessity, not preference.

   **AMENDED 2026-08-25 (Kevin), DECIDED BUT NOT YET BUILT - the code above is still live.** The
   backend-ERP pivot retires the statement parsers: a statement will be run through the USER'S OWN
   LLM, which masks sensitive data and emits a CSV in a format LEGION defines. There is then no
   deterministic extraction path for ledger statements, **by choice** - so this rule's "where a
   deterministic path exists" clause simply stops applying there. Three reasons it is not a
   loosening: PdfBox cannot run server-side (Deno), the parsers only ever covered DBS and BofA so
   every other bank already fell through to the LLM anyway, and masking before upload matters far
   more now that the truth lives in a cloud Postgres than it did on-device.
   **Rules 2 through 7 are untouched and still bind in full.** In particular the gate gets STRONGER
   here, not weaker: because an LLM-produced CSV has its lines AND its total from one
   nondeterministic process, a single anchor could be satisfied by a self-consistent hallucination
   (rule 6's failure shape in a new place), so the format demands **three** independent anchors -
   printed total, opening balance, closing balance - and a statement that prints fewer cannot use
   the format and falls to rule 7 provisional. Rows are tagged `LLM_RECONCILED`, never
   `DETERMINISTIC`. Ticket: `.scratch/backend-erp/issues/03-the-gate-server-side.md` rulings 3-6.
   Until it is built, treat the parsers as live and this paragraph as the direction of travel.
2. **Extracted rows must reconcile against the document's OWN stated total**, exactly. Sum of
   line items equals the printed total, or the whole document **quarantines**. Nothing partial is
   ever written. Never silently accept.
3. **Money is `Long` cents, never `Double`.** Deliberate deviation from `BuildEntry`/
   `ServiceRecord`'s convention, because the gate depends on exact equality.
4. **Tag the provenance of every row**: `DETERMINISTIC` or `LLM_RECONCILED`.
5. **Anything the document does not state cannot be gated, and must be surfaced as an estimate,
   never as fact.** Pantry's per-item macros (calories/protein/carbs/fat) are LLM guesses from the
   product name; a receipt never prints them. They are excluded from the reconciliation check and
   their tool descriptions must say "estimate".

6. **A check that passes when nothing parsed is not a gate.** Every reconciliation layer must be
   unsatisfiable by an empty or partial extraction. Inside a recognized section, every line that
   is not the section's own total must parse, or the document quarantines - a line the parser does
   not recognize is a hard failure, never a skip. This is rule 2 closed against its own blind spot:
   BofA's card statement prints its interest rows in a different shape, all four silently failed to
   match, and the section check reconciled zero parsed rows against a printed $0.00 and passed. It
   only held because interest was zero that month. **Silently dropping a row you did not recognize
   is the same sin as accepting one you could not verify.**

7. **A source that states NO anchor may be stored PROVISIONALLY, never as fact.** Some documents
   print nothing to reconcile against - no balances, no total, nothing (Bank of America's mid-cycle
   card CSV export is the first). Rules 1-6 are unchanged and such a document can never pass them.
   It may still be ingested, on four conditions, all of which are load-bearing together and none of
   which is optional: extraction is **deterministic** (an LLM adds cost and nondeterminism to rows
   that are already unverifiable, and cannot manufacture an anchor); every row is tagged
   `IngestMethod.UNRECONCILED`; **every surface that renders one says so in words**, never by colour
   or a glyph alone, and any figure containing one is labelled unverified; and the rows are
   **transient** - when a file that DID pass the gate commits over the same account and dates, the
   provisional rows in that window are deleted, so an unverified row can never outlive or
   double-count against the verified one that supersedes it.

   **AMENDED 2026-08-26 (Kevin), and the amendment is deliberately narrow.** Condition 1 above
   requires DETERMINISTIC extraction. Three pantry receipts turned out to be unverifiable for a
   different reason: they were gated correctly at ingestion, but the legacy `pantry_receipts` table
   only ever had `totalCents` - no subtotal/tax/other columns - so **the gate's own inputs were
   never persisted and the check cannot be reproduced from storage.** The photos are gone, so
   re-extraction is impossible.

   Such rows MAY be stored provisionally under rule 7's other three conditions (UNRECONCILED tag,
   said in words on every surface, superseded when a real anchor arrives). **The amendment covers
   rows already extracted and stored, never a new ingestion path** - it does not license choosing an
   LLM for rows you know cannot be gated, which is what condition 1 exists to forbid.

   **The unexplained amount is stored in its own column and never as tax.** `tax := total - sum(lines)`
   would make `sum(lines) + tax = total` true by construction, turning the anchor into an identity -
   rule 6's failure shape - and would silently absorb a genuinely missed line item. `receipts.unaccounted_cents`
   names it for what it is, is never summed into an anchor, and a non-null value forces
   `UNRECONCILED` by check constraint. The real anchor arrives later: matching the receipt total
   against a ledger transaction from the bank statement, which is external and falsifiable in a way
   the receipt's own arithmetic no longer is.

   This narrows what "commit" means. It does not widen what "verified" means. Rule 2's real claim is
   that nothing partial is written **as fact**, and a row the app openly reports as unverified is
   not being asserted as fact. The failure this guards against is not storing a weak row - it is
   storing one that later reads as strong. Decided 2026-08-06 (Kevin); ticket
   `.scratch/ledger-drive-ingestion/issues/12-provisional-card-csv.md`.

8. **A gate that discards its own inputs leaves rows nobody can ever re-verify. Persist the
   anchors, not just the verdict.** Rule 2's guarantee is only as durable as the evidence kept.
   Found twice, both times too late to fix the data: three pantry receipts whose legacy table only
   ever had `totalCents`, so the check cannot be reproduced from storage and the photos are gone
   (rule 7's 2026-08-26 amendment); and then the ledger's WHOLE verified history, whose stated
   total, opening balance and closing balance were read inside a parser at ingestion and never
   written down (`.scratch/backend-erp/issues/12-*.md`, 2026-08-28). The second one is recoverable
   only because the source documents survive in Drive.

   **So: every ingestion path stores the numbers the gate checked against, in their own columns,
   alongside the rows they gated.** A `provenance` tag records the verdict; it is not evidence, and
   a row carrying `DETERMINISTIC` with no retrievable anchors is an assertion nobody can audit. An
   anchor the source did not state is stored NULL and recorded as absent - never synthesised from
   `sum(lines)`, which would make the check an identity (rule 6's shape again). The server schema
   gets this right for both aspects; the phone's legacy tables never did, and that is the gap.

Rule 5 is the §7 safety thesis applied to data: agents and memory are safe to the degree they are
anchored to external, falsifiable reality.

---

## 5. Data Layer

**Additive migrations only.** Verbatim generated SQL, `exportSchema = true`, schema JSON committed
under `app/schemas/`, no destructive fallback on upgrade, a migration test. `data/local/Migrations.kt`
is the authority and `docs/architecture/c3-data.md` has the entity roster.

**Never quote a schema version from a document, including this one.** This section named the wrong
version three times running - v21 while the code was at v25, v34 at v37, v37 at v41 - which is
exactly why it no longer names one:

```
sed -n '/version = /p' app/src/main/java/com/kevin/legion/data/local/CarDatabase.kt
```

**`CarDatabase.SCHEMA_VERSION` must be bumped in lockstep with `@Database(version = ...)`.** It is a
second, hand-maintained copy that `DriveSyncScreen` uses to decide whether a backup may be restored,
so a forgotten bump disables restore on every backup the running app produces.
`CarDatabaseSchemaVersionTest` fails the build on drift.

**Widening an enum stored as TEXT is not a migration.** A `TEXT NOT NULL` column with no CHECK takes
a new constant with no SQL change, no identity-hash change and no version bump. Confirm rather than
assume: read the column's `createSql` in `app/schemas/` and check the JSON is byte-unchanged after a
kapt run.

---

## 6. Codebase Map

**There isn't one here, deliberately.** The map in this section was corrected three times in three
weeks and was wrong again within days each time. `Glob` and `Grep` are current; a list is not.

For structure, read the tree. For how it fits together and what is binding, read `docs/architecture/`
(C4 levels 1-3) and `docs/adr/`.

**Build:** `./gradlew compileDebugKotlin -Pnokey` (the honest no-baked-key path),
`./gradlew testDebugUnitTest`, `./gradlew assembleDebug`. Read test totals from the JUnit XML under
`app/build/test-results/`, never the console summary.

**Setup:** `local.properties` needs `sdk.dir`. Export `JAVA_HOME` per shell and **never commit
`org.gradle.java.home`** - Midnight AI's did, and it broke on any machine without Android Studio at
that exact path, violating clone-and-run. Machine-specific paths live in `memory/MEMORY.md`, not
here.

---

## 7. Guardrails

- **The reconciliation gate (§4) applies to every new ingestion path.** No exceptions per-feature.
- **Estimates are labelled as estimates**, in the tool description and in any user-facing string.
- **Pull-based tools always.** New domains default to tools/sub-agents, not pre-injected context.
- **Lean Room migrations.** Copy generated SQL verbatim, additive only, no destructive fallback.
- **No Kevin-hosted anything.** No backend, no Firestore, no broker, no proxy, no hosted key. Data
  lives on-device and in the driver's own Drive `appDataFolder`. This is what makes clone-and-run
  work and it is the same BYO shape as the Gemini key.
- **The assistant never asserts an outcome it did not observe (2026-08-19, Kevin).** Outcome verbs -
  done, started, sent, opened, booked, played, set - may follow only a tool call that came back
  successful **in that turn**; an unsuccessful result is the same as no tool at all. With no tool,
  it says so plainly and offers the nearest thing it genuinely has a tool for. The clause lives at
  file scope in `ai/AriaBrain.kt` (`CANNOT_CLAUSE`), never per-persona, and enumerates no
  capabilities - it is conditioned on the tool RESULT so it survives every new tool. This is §4's
  posture applied to speech: the gate quarantines a figure it could not verify, this quarantines a
  verb whose outcome it could not verify. `docs/adr/0031-speech-honesty-clause.md`. **Nothing
  inspects the spoken audio, so this is a prompt rule and the only lever there is;
  `AriaBrainHonestyClauseTest` guards its presence, never its obedience.**
- **Third-party content is read-through only (2026-08-22, Kevin).** Anything other people wrote
  *to* Kevin rather than anything Kevin created or chose to import - mail first, and anything of
  that shape later - may be read to answer a question and must then be dropped. Never persisted to
  Room, never synced, never remembered, never used to form a durable memory. **The guarantee is
  that it was never stored, not that something remembered to exclude it**, which is why the
  exclusion lives in `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` and is applied at the write sites rather
  than being a habit each new feature has to remember. Proposed in
  `.scratch/google-account-integration/issues/07-*.md` point 6 and accepted verbatim.

  **CARVE-OUT 2026-09-01 (Kevin): a recording Kevin deliberately starts does not fall under this
  rule.** Asked directly whether it binds a recorded meeting: *"drop 7 entirely. we can keep all
  transcripts."* A voice note's audio, verbatim transcript and summary are persisted, synced and
  retained like any other record, other people's speech included. **The line is provenance, not
  subject:** mail arrives unasked, a recording exists because he pressed a button. The mail path is
  untouched - `EPISODIC_EXCLUDED_TOOLS` still binds every tool in it, and mail still never reaches
  Room. Consent in the room is Kevin's to handle and he handles it out of band (*"they will know,
  i'll tell tem"*): no in-app indicator, no spoken announcement, recorded as a decision made with
  open eyes rather than left unasked. `docs/adr/0041-a-recording-kevin-starts-is-first-party.md`.
  **A transcript states no total, so §4's numeric gate has no purchase - what stands in for it is §4
  rule 8: keep the evidence.** Summary anchored by the transcript beside it, transcript anchored by
  the audio beside it, all three deleted together. Nothing numeric heard in a recording may be
  asserted as fact or become a ledger row without going through that aspect's own ingestion path.
- **Every voice capability has a non-voice path (2026-08-22, Kevin).** *"all voice capabilities
  also must have a non voice UI capability."* Anything LEGION can do by voice must also be doable
  by hand. Voice is the fastest way in and it is the way that FAILS - a loud car, a sleeping
  person, a wake word that does not fire, a mic that opens deaf, a closed socket, a misheard
  name, all observed on the real phone. When voice is the only path, every one of those failures
  becomes total. It does NOT mean screen parity for every parameter, and it does NOT mean a
  second implementation - both paths call the same controller, because two implementations of
  one capability drift into disagreeing. `docs/adr/0035-every-voice-capability-has-a-hands-path.md`.
- **No comparative or anonymized fleet data**, ever.
- **Network calls degrade gracefully offline.**
- **Assets are bundled** in `assets/` or `res/`, never fetched at runtime.
- **Safety (AMENDED 2026-08-02, Kevin):** the old blanket ban on the
  assistant claiming sentience, feelings, or being real is **LIFTED**. A warm, characterful
  companion that expresses feeling is the product direction now, not a failure mode. Personas may
  be affectionate, may say they are glad you are back, may have moods and opinions. This is a
  personal app for two adults who installed it knowingly, not a consumer product aimed at
  strangers.
  What remains binding, and is narrower on purpose:
  - **No compulsion mechanics.** Streaks, re-engagement pings, manufactured return, guilt for being
    away. Warmth is welcome; a mechanism engineered to pull someone back is not. The difference is
    whether the feeling serves the user or the retention.

    **The compulsion test, so this is checkable rather than felt about (Kevin, 2026-08-21,
    `.scratch/proactive-mode/issues/03-compulsion-test.md`).** An Alfred rest-nudge is mechanically
    IDENTICAL to a re-engagement ping - same notification, same unprompted speech, same hour of the
    night. Only content and intent differ, so intent has to be written down as something a future
    ticket can be checked against. **Every unsolicited raise must:**

    - **(a)** be anchored to a fact the user could verify himself - the clock, a goal he set, a
      sleep target, a weather alert;
    - **(b)** be actionable right now;
    - **(c)** **never reference his absence, his streak, or his engagement with the app**;
    - **(d)** be silenceable forever in one instruction.

    **(c) and (d) are the load-bearing halves.** Without them "it's past 10pm" becomes "you haven't
    talked to me in three days" by increments, and nobody notices the day it crosses over.

    **What the test does NOT cover, stated so its existence is not mistaken for coverage.** A test
    over the raise registry can check that a raise DECLARES an anchor (a) and a silence instruction
    (d). It cannot check that the anchor is real, that the raise is actionable (b), or that the
    wording avoids guilt (c) - **those two stay human-reviewed.** And Kevin ruled on 2026-08-21 that
    a nudge about **a goal he set and then ignored is PERMITTED**, which means the line between a
    useful reminder and a scolding one now rests on tone, in the shared proactive clause, and tone is
    the weakest lever this codebase has. Clause (c) still binds: such a nudge may name the goal, its
    deadline, or its next action, and may never characterise how long it went unattended.
  - **Memory stays anchored to external falsifiable facts** about the car, the statements, the
    receipts. A persona may be fond of the driver; it may not invent unfalsifiable history with them.
  - **Genuine distress still routes to `ai/CrisisDetector.kt`:** surface real resources and STOP
    performing the character. Never counsel, never simulate a professional. This one is not a matter
    of taste - it is the case where staying in character can actually hurt someone.
    **Known gap: the crisis resource is US-only (988).**
- **Motion is NOT restricted anymore.** The frame-clock-only rule and the `ui/Motion.kt` ban list
  were head-unit constraints (animator scale 0 on cheap AOSP units). Phone-only lifts them. Use
  normal Compose animation.

### Feature-add checklist

- [ ] Ingestion path? Reconciliation gate wired, quarantine on mismatch, provenance tagged.
- [ ] Source states no anchor at all? §4 rule 7's four conditions, all of them: deterministic
      extraction, `UNRECONCILED` tag, said in words on every surface, deleted when a gated file
      covers it.
- [ ] Anything the source document does not state? Labelled an estimate, excluded from the gate.
- [ ] Pull-based tool, not a pre-injected context block.
- [ ] Room change? Verbatim generated SQL, additive, `exportSchema`, migration test.
- [ ] Gemini call? On the user's own key, cheap one-shot sub-agent where possible.
- [ ] Money? `Long` cents.
- [ ] Does it need a backend? Then it is wrong. Rework it onto Drive `appDataFolder` or on-device.
- [ ] Does it survive clone-and-run by a stranger with their own signing cert?
- [ ] New tool? Its failure result says in words what did NOT happen, and nothing claims success
      unless the underlying action ran. §7's outcome-verb rule needs a real result to stand on.
- [ ] Reads anything other people wrote to Kevin? Read-through only: used to answer, then
      dropped. Nothing to Room, nothing synced, nothing remembered, not even a summary.
- [ ] New voice tool? It has a hands path to the same capability, calling the same controller.
      A capability reachable only by voice is not finished (ADR 0035).
- [ ] Safety: no sentience claims, no compulsion mechanic, no unfalsifiable memory about the user.
- [ ] Built from a resolved ticket? Every verification step in that resolution accounted for as
      done / deferred-with-a-follow-up / impossible-and-why. See §8 (L11).

---

## 8. Working Model

- Plan with a strong model (Fable/Opus) in plan mode; execute approved plans with Sonnet.
- Plans are execution-specs: exact file names, signatures, edge cases, verification section. No
  taste calls left to execution.
- During execution: follow the plan exactly. Unexpected surface (design question the plan does not
  answer, architectural fork) means STOP and surface, not improvise.
- One logical change per commit. `compileDebugKotlin` + `testDebugUnitTest` green before each commit.
- Subagent roster and cadence: see `TEAM.md` (`.claude/agents/` holds project specialists).

### Dispatch is the default, not an escalation (STANDING, Kevin 2026-07-28)

Stark dispatches the team at its own discretion. Kevin does not need to ask, per task or at all.
Some harness configurations inject "do not use subagents unless the user requested it" into the
session prompt; **this section IS that request, given once, standing, for all future sessions.**
Judgement still applies: a one-line fix does not need a three-agent pipeline, and TEAM.md's cost
note still governs. The default is delegate-and-review; the exception is trivial work.

### A grep-clean result is not a done result (L10, 2026-07-31)

The port's first reconciliation pass fixed everything a grep for retired class names could find
(36 files). The real compile then caught what grep structurally cannot: a Room DAO querying a
dropped column, a file deleted because its name looked badge-related when core state depended on
it, and several files referenced but never copied over (nothing greps for an absence). **Run the
real build.** Ledger's PdfBox/Robolectric finding is the same shape: it was caught by running the
spike, not by reading the docs.

### A ticket's own verification steps are gates, not notes (L11, 2026-08-02)

A resolved ticket's verification instructions are binding on whoever builds it. Ticket 07 said, in
writing, "render the five previews in `ui/theme/ThemePreview.kt` before building screens on the
theme." That step was not performed, screens were built on the theme anyway, and the exact class of
bug it existed to catch shipped into the first-run consent screen: `surface` and `errorContainer`
held one colour value, M3's `contentColorFor` resolves by value testing `errorContainer` first, and
every screen drew its body text in quarantine red. It was caught by installing the APK.

**Nobody hid it.** The executing agent listed the step as unmet, in writing, with a valid reason
(it could not render Compose previews). The orchestrator read that and proceeded. The failure was
treating a surfaced gap as a note rather than a gate.

**The rule.** Before reporting a ticket built, account for every verification step in its
resolution as **done / deferred-with-a-named-follow-up / impossible-and-why**. Never silently
carry an unmet one. When an executing agent reports a step it could not perform, that is a
blocking item for the orchestrator to resolve or explicitly accept, not a footnote to relay
onward. This is the L2/L3 relay discipline pointed at execution completeness rather than at
verification tags.

### Improvement loop

The org does not fine-tune; agents learn only by lessons graduating into the prompt surfaces they
read next run (`.claude/agents/*.md`, this file). Ledger: `memory/library/lessons.md`.
- **Agents end reports with an assumptions ledger:** every non-trivial claim tagged `built` /
  `tested` / `traced` / `reasoned` / `on-device`.
- **Orchestrator relay rule:** carry a subagent's verification tag when relaying. Never upgrade
  "the agent reasoned X" into "X is true."
- **Briefs carry verification tags too**, pointing downward at what the orchestrator asserts to a
  specialist. A verified signature is not a verified semantic (L3: `javap` confirmed
  `monotonicTimestamp(Long)`, the brief asserted milliseconds, it was nanoseconds).
- When a `reasoned` claim proves wrong, file it in `lessons.md` and graduate its rule into an agent
  def or this file. An entry closes only when its rule lives in a surface something reads.

### No framework. Dependencies are parameters. (Kevin, 2026-09-05)

Asked whether LEGION should adopt an Android framework, the answer after research was no, and Kevin
ruled it so. The platform plus Jetpack plus Compose IS the framework; Google's own guidance labels
Hilt "for complex projects" and manual DI "for simple apps", and this is one developer, one phone.
What the codebase lacks is two conventions, not a library. Both bind NEW code; the existing 33
controllers migrate only as they are touched, never in a sweep.

- **A controller takes its collaborators as parameters, never `Context`.** The database, a backend,
  a clock. The caller resolves them once. `Context` threaded through 645 signatures is why 202 of
  318 test files need Robolectric and why there is no seam to fake a backend - and with a second
  client arriving (Django, see `docs/adr/` two-clients ADRs) a thin client that calls RPCs is the
  shape that wants this most.
- **One `AppGraph`, a plain Kotlin class built in `MidnightApplication.onCreate`**, holding the DB,
  the Supabase client and the backends. That is what Google calls manual DI. No annotation
  processor; Room's kapt is already the build's tax and Hilt would double it.
- **Screen-level state holders for the busiest screens** (calendar, ledger, pantry) as `ViewModel`
  subclasses exposing one `StateFlow<UiState>`, collected with `collectAsStateWithLifecycle`.
  `lifecycle-viewmodel-compose` is already on the classpath. Rotation, process death and a Realtime
  push all re-run a screen-local loader today; that surfaces as "the list flashed empty".

**Adopt later, on a named trigger:** Koin if a DI library is ever wanted (fits the `object` style,
no codegen; prefer it over Hilt). A `:core:data` module when a clean compile is intolerable - and
before that, Room kapt to KSP, which is cheaper. Navigation 3 when navigation-compose 2.8 blocks a
real need. **Never for this app:** Circuit, Decompose, MVI frameworks, a use-case layer, Flutter,
React Native, KMP. Phone-only is a ruling and there is no second platform to share with.

### Branching

| Branch | Role |
|---|---|
| `main` | Mirrors `dev`. Claude may merge `dev` into `main` and push it (Kevin, 2026-08-19, reversing the PR-only rule below). |
| `dev` | The trunk. Everything lands here. |

- Feature work branches off `dev`: `feat/<thing>`, `fix/<thing>`. Small commits, merge often,
  delete the branch after.
- **Claude merges `dev` into `main` and pushes it** (Kevin, 2026-08-19). No PR. Merge only `dev`,
  never a feature branch, and only when `dev` is green.
- The anti-pile rule survives: on 2026-07-16 Midnight AI had 45 commits of real work sitting
  unpushed across five local branches. Push often; do not rebuild that pile.

---

## 9. Communication Style

- No em dashes. No emojis. Ever.
- No fluff, no embellishment, no verbose explanations.
- Code paste-ready. Full file or full function, imports included. No elisions, no TODO placeholders.
- Direct and actionable. Numbered lists for steps, tables for comparisons.
- Acknowledge errors and fix without defensiveness.

---

## 10. What is not built

**The board is the answer:** `docs/index.html`, generated from ticket frontmatter. This section used
to list unbuilt work by hand and spent most of its life half struck-through, because a list of
absences goes stale the moment one is filled.

`README.md` remains the authority on what compiles and what is tested.

---

## 11. The inherited library

`memory/library/` was copied wholesale from Midnight AI on 2026-08-01 rather than rewritten, so
nothing was lost. **Most of it is FROZEN car-launcher history.** Every shelf carries a status
banner at the top and `INDEX.md` carries a status column. Read the banner before acting on a shelf.

- **LIVE** (governs LEGION): `decisions.md` (the 2026-07-31 pivot entries at the end),
  `lessons.md`, `playbook-coding.md` (partly - Kotlin/Compose/Room/Drive conventions hold, the
  head-unit and city-pop sections do not), `INDEX.md`.
- **FROZEN** (Midnight AI archive, reference only): `blocking.md`, `sprints.md`, `hardware.md`, all
  seven `backlog-*.md`, `playbook-business.md`, `playbook-qa.md`, `archive.md`, all three
  `session-*.md`.

Do not act on a FROZEN shelf's blockers, sprints, or backlog items. They describe a head-unit car
launcher with a commercial model, and all three of those premises are dead.

---

## 12. The Obsidian vault layer

Added 2026-08-18 so Kevin can browse the library, the maps, and the tickets himself instead of
asking for the next map every session. The repo root IS the vault; nothing moved.

- **Tickets stay the source of truth.** Their `Type` / `Status` / `Blocked by` header lines now live
  in YAML frontmatter instead of the body. Same information, same place, machine-readable.
- **`tools/obsidian_sync.py` regenerates the derived layer** and is idempotent. Run it after editing
  any ticket header or adding a ticket:

  ```
  python tools/obsidian_sync.py          # write
  python tools/obsidian_sync.py --check  # report only
  ```

  It rewrites ticket and map frontmatter, one `.canvas` dependency graph per map, and `vault/Board.md`.
- **Anything READY is built, not parked (Kevin, standing, 2026-08-21).** *"from now on, anything
  thats ready to build (no more decisions needed) should get built."* A `ready` ticket with no open
  decisions is unstarted work, not a plan - resolving a decision and stopping is what left the
  sitrep fully decided and unwritten. **Building may run in parallel with grilling another effort**;
  Kevin asked for that shape explicitly, so dispatch the build and keep the conversation going.
  Verification is unchanged: compile, suite green, and say plainly what is owed on the phone.
- **A resolved decision ticket must leave a BUILD ticket behind, created at resolution time.** The
  wiki lists only OPEN tickets, so resolving a decision makes it vanish - and a fully-decided,
  entirely unbuilt feature then looks exactly like finished work. It happened within an hour of the
  sitrep being resolved on 2026-08-21: Kevin asked "where is the daily brief? was it built?" and it
  had not been. Proactive mode escaped the same trap only because its build happened the same night.
  **If a decision authorises code, open the build ticket in the same commit that resolves it.**
- **Never hand-edit `vault/Board.md` or a `.canvas`.** Edit the ticket, re-run the script.
- **`ready: true` on a ticket means open with every blocker resolved, AND not parked, AND still
  owing code.** It is computed, so it goes stale the moment a status changes without a re-run. Treat
  a stale board as a script that was not run, not as a fact.
- **Two ticket statuses beyond open/resolved, added 2026-08-20 (Kevin).** Both are OPEN - they are
  states of progress, not kinds of work, which is why they live in `status` and not in `type`:
  | Status | Means | On the wiki and the board |
  |---|---|---|
  | `built` | Built, suite green, owing a run on real hardware. **"build" is a lie once it is built.** | Chip reads `built`, state reads "needs a run on the phone", own board section |
  | `kiv` | Parked on purpose by Kevin. Not dead, not queued. | Chip reads `KIV`, never `ready`, sorted last; an all-KIV map sinks to the bottom of the page |
  The four buckets - ready / built / blocked / KIV - are disjoint by construction, so they sum to the
  open count. They were not, on the first cut: three `built` tickets also carried `ready: true` and
  were counted twice. If those numbers stop adding up, that is the bug to look for.
- Vault entry points live in `vault/`: `LEGION.md` (hand-written home), `Board.md` (generated),
  and three Bases (`Tickets`, `Maps`, `Library`). `.obsidian/` holds shared config; per-machine
  workspace state is gitignored.
- `memory/library/` shelves carry `shelf` / `status` / `kind` frontmatter and wikilink each other.
  **Do not wikilink source code paths** - the graph is for the knowledge library, and hundreds of
  code nodes would drown it. Code paths stay in backticks.

---

## 13. The docs layer

Added 2026-08-18. `docs/` answers **how it fits together** and **what is binding**. It is the sixth
documentation surface and the boundaries matter:

| Question | Surface |
|---|---|
| What are the rules? | **this file** |
| What is happening now? | `memory/MEMORY.md` |
| What happened, and when? | `memory/library/`, chiefly `memory/library/decisions.md` |
| What is planned? | `.scratch/*/map.md` and tickets, surfaced at `vault/Board.md` |
| What compiles and is tested? | `README.md` |
| How does it fit together, what is binding? | `docs/` |

- **ADRs (`docs/adr/`) say what is binding NOW. `decisions.md` says what happened WHEN.** Every
  decision still gets a `decisions.md` entry; only standing ones also get an ADR. Superseded ADRs
  keep their original text. Format and the test for whether something earns one:
  `.claude/skills/domain-modeling/ADR-FORMAT.md`. **The old prohibition on `docs/adr/` in that file
  was reversed by Kevin on 2026-08-18.**
- **`docs/glossary.md` holds pointers, never authoritative definitions.** CLAUDE.md still owns the
  vocabulary. A second competing glossary is the failure the read order exists to prevent, and that
  half of the old prohibition survives.
- **Diagrams are Mermaid in Markdown**, not Canvas: they render in Obsidian and on GitHub, and they
  diff in a pull request. C4 levels 1 to 3 only; level 4 goes stale the day it lands.
- **Run `python tools/docs_check.py` after moving or renaming documented code.** It fails on a
  source path named in `docs/` that no longer exists, on ADR frontmatter that is missing or invalid,
  on a one-sided supersession link, and on a wikilink pointing at nothing. This is L24 turned into a
  check instead of a hope.
- `docs/adr/adr-index.md` is generated by `tools/obsidian_sync.py` from ADR frontmatter. Do not
  hand-edit it.
- **A commit hook keeps the generated layer from going stale.** `.claude/settings.json` holds a
  `PreToolUse` hook on `Bash(git commit*)` that re-runs `obsidian_sync.py` and `pending_wiki.py` and
  stages their outputs, so the wiki and the board ride along in the same commit that changed a
  ticket. Kevin, 2026-08-21: *"that page needs to be up to date."* It fires BEFORE the commit
  deliberately - a hook that regenerated afterwards would leave the repo one commit stale, and Pages
  serves the repo. **It is not a substitute for running the scripts yourself** while iterating; it is
  the backstop for forgetting. Verified by planting a sentinel and watching a real commit trip it.
  **It regenerates; it does not PUSH.** On 2026-08-21 the page looked stale to Kevin while the local
  file was perfectly current - `dev` was 17 commits unpushed and Pages was serving three-week-old
  HTML. A correct generated file in an unpushed commit is a stale public page. **Push `dev` when a
  chunk of work lands**, or the wiki lies to whoever reads it.
- **`docs/index.html` is the progress wiki, and the GitHub Pages home page** - every OPEN ticket across every map on one page,
  grouped by map, filterable by ready / blocked / decision / buildable / test / KIV. Generated by
  `python tools/pending_wiki.py` from the SAME ticket frontmatter `obsidian_sync.py` maintains, so
  it cannot drift from `vault/Board.md`; re-run it after resolving anything. Do not hand-edit it.
  A map with no open tickets vanishes from the page entirely. It uses the app's own mission-control
  palette (`ui/theme/Color.kt`) rather than a second visual language.
- **`docs/voice.html` is the USER-facing guide** - what LEGION can do by voice, in plain language,
  linked from the top of the wiki. `docs/index.html` is for whoever is building LEGION; this is for
  whoever is using it, and a visitor landing on a wall of tickets has no other way to find out what
  the app does. Kevin asked for it 2026-08-21.
  **Generated by `python tools/voice_guide.py`, and drift is a hard failure.** The 97 tool NAMES come
  from `service/LiveToolbox.kt` so nothing can be missed; the user-facing COPY lives in
  `tools/voice_guide_copy.py`. **Add a voice tool without adding copy and the script exits non-zero
  and names it** - the same posture as `docs_check.py`. Do not paste the Kotlin `description` into
  the copy file: those are written to steer a model, are full of "never say X unless" caveats, and
  read badly to a human.
  **The same script also rewrites a condensed block in `README.md`** between
  `<!-- VOICE-SURFACE:START/END -->` - a domain/count/example table for whoever is assessing the
  ENGINEERING rather than learning to use the app (Kevin, 2026-08-21: "a condensed version that
  potential recruiters and technical interviewers can look at"). Generated for the same reason: a
  capability table in a README is exactly the kind of thing that quietly rots into a lie about the
  project. Do not hand-edit inside those markers.
- **Pages serves `docs/` from `dev`**, so the wiki is live at the repo's Pages URL and updates on
  every push. `docs/.nojekyll` stops Jekyll rewriting the Markdown docs alongside it. **The repo is
  public, so the wiki is public** - ticket titles and status details included. That was already true
  of `.scratch/`; Pages only makes it easier to read.
