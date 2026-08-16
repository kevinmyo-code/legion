# Lessons (the improvement loop)

> **STATUS: LIVE, with a frozen tail (banner added 2026-08-01).** This shelf still governs
> LEGION, but most of its volume predates the 2026-07-30/31 pivot and describes a head-unit
> car launcher. Trust the 2026-07-31 and later entries; treat everything earlier as Midnight
> AI history unless it is a language/framework fact that survives the platform change. See
> CLAUDE.md §11.


Failure modes the org has actually hit, and the rule that prevents recurrence. This is NOT the
knowledge playbook: `playbook-coding.md` holds facts ABOUT the codebase; this file holds facts
about how an AGENT (or the orchestrator) got something wrong, so the next spawn does not repeat it.

The org does not fine-tune. The only way an agent "learns" is that a lesson here graduates into a
durable prompt surface it reads on its next run: its `.claude/agents/*.md` definition, `CLAUDE.md`,
or a playbook shelf. An entry is only "closed" once its rule is written into that surface.

## The loop

1. **Capture.** Every agent ends its report with an *assumptions ledger*: each non-trivial claim it
   made, tagged with how it was checked. Tags: `built` (compiled), `tested` (unit/integration ran),
   `traced` (followed the code path to its leaf calls), `reasoned` (inferred, NOT executed),
   `on-device` (validated on hardware). A `reasoned`-only safety/correctness claim is a candidate
   failure until something upgrades it.
2. **Catch.** A review gate (senior-dev / bug-hunter / qa) or the orchestrator checks the diff and
   the ledger. A `reasoned` claim that turns out wrong becomes an entry here.
3. **Distill.** One entry: claimed / actual / root-cause class / the rule that would have caught it.
4. **Write-back.** The rule graduates into the relevant agent def or CLAUDE.md. Record where.
5. **Measure.** Add one cheap regression check per entry (a grep, a test, a review-rubric line) so
   the same class cannot silently return. Evals here are assertion-checks and regressions, not
   accuracy-on-a-dataset (there is no dataset and no training).

## Root-cause classes (growing taxonomy)

- **unverified-reachability** — asserting a property (safe / pure / isolated / preview-safe) of a
  code path without tracing it to its leaves. "X only does Y" inferred from X's name, not read.
- **false-success** — reporting an action succeeded when only part of it did (routing flipped but
  playback did not resume; API returned 200 but the effect did not land).
- **relay-without-verify** — the orchestrator upgrading a subagent's `reasoned` claim into stated
  fact when passing it to the user.

## Entries

### L1 — coding (Derek): unverified-reachability
- **Claimed:** (a) `AvatarStudio.loadBackground` "just checks File.exists(), so the previews are
  preview-safe"; (b) earlier, `setMusicSource("phone")` "always reports success honestly."
- **Actual:** (a) `loadBackground` -> `ActiveVehicle.current` -> `ObdBluetoothManager`, whose static
  initializer throws in the preview JVM (`NoClassDefFoundError`), crashing 7 of 11 previews at
  render; (b) `switchToPhone` flips routing to PHONE and reports success even when `resumePhone`
  no-ops (phone session already gone), so the tool can claim it resumed playback that never resumed.
- **Root class:** unverified-reachability.
- **Rule (written into `.claude/agents/coding.md`):** Before asserting a code path is
  safe / pure / isolated / preview-safe / offline-safe, TRACE it to its leaf calls, following every
  helper into the singleton, I/O, or Android API it actually touches. "X only does Y" requires
  reading X, not inferring from its name. If you did not trace it, tag the claim `reasoned` and say
  so explicitly.
- **Regression check:** the frame-clock grep already exists; add to the coding agent's self-check
  that any composable used in a `@Preview` must not touch `ObdBluetoothManager` / `ActiveVehicle` /
  `AvatarStudio` image loads without a `LocalInspectionMode` guard.
- **Status:** CLOSED (rule in coding.md; fix shipped in `d711aff`).

### L2 — orchestrator (main): relay-without-verify
- **Claimed to Kevin:** the previews were "verified preview-safe" (relaying Derek's report wording).
- **Actual:** that was Derek's `reasoned`-only inference, not a verified fact; the previews crashed
  on first render in Studio.
- **Root class:** relay-without-verify.
- **Rule (written into `CLAUDE.md` §10):** When relaying a subagent's safety/correctness claim,
  carry its verification tag. Never upgrade "the agent reasoned X" into "X is true." If the claim is
  not covered by the build/tests that actually ran, say it is the agent's reasoning, unconfirmed.
- **Regression check:** orchestrator relays of subagent claims should name the check (built / tested
  / on-device / reasoned-only) rather than stating the claim bare.
- **Status:** CLOSED (rule in CLAUDE.md §10).

### L3 — orchestrator (Marcus): relay-without-verify, applied to brief-to-subagent direction
- **Claimed in a task brief:** (Stark to Derek) Mapbox's `Location.Builder.monotonicTimestamp(Long?)`
  takes MILLISECONDS, instructing `elapsedRealtimeNanos / 1_000_000`.
- **Actual:** it takes NANOSECONDS. Derek implemented the brief exactly as written, `/ 1_000_000`
  included, and had no reason to doubt it - he had already caught a real error elsewhere in the same
  brief (a non-null param that is actually nullable), so this was not inattention. **Nadia caught it
  in review**, by decompiling Mapbox's own `LocationServiceUtils.toCommonLocation`/`toAndroidLocation`
  and finding `elapsedRealtimeNanos` passed through unscaled in both directions. It shipped into a
  commit-ready working tree first. That is the point: the brief's error survived the specialist who
  was checking everything else, because a specialist cannot tell a verified claim from a confident one.
- **Root class:** relay-without-verify, but inverted — the orchestrator briefing a specialist downward,
  not upward to the user.
- **Rule (written into `CLAUDE.md` §10 improvement loop):** When handing a
  specialist an API fact, mark what was actually checked vs. inferred. "Signature traced by javap;
  unit inferred from field name, VERIFY" would have made Derek check it. A verified signature is not
  a verified semantic. Same failure mode as L2 (relaying `reasoned` as fact), pointing downward into
  a brief instead of upward into a report.
- **Regression check:** specialist briefs that assert an API detail should carry a tag (traced /
  tested / verified) or a "VERIFY" flag, not bare assertions. Review gate: flag any technical claim in
  a brief that does not name how it was checked.
- **Status:** CLOSED (rule in CLAUDE.md §10, 2026-07-25).

### L4 — orchestrator (Stark): dispatch is the default, not an escalation
- **Claimed action:** five substantial commits (nav correctness, Spotify, map puck, cost metering, GarageHub) written inline per-session with zero sub-agent dispatch (no Ravi code review, no Vic diff review, no Derek on API surface).
- **Actual:** a retroactive Vic diff pass (post-commit, catch-up mode) found 2 MAJOR defects and 3 MINORs in the same commits, including a silent-undercount bug in a spend meter that had zero test coverage. Derek's independent trace of Mapbox bytecode would have caught the nanoseconds-vs-millis issue on the first-pass API brief, not in a follow-up audit.
- **Root cause:** a harness-injected system-prompt line ("Do not call the AgentTool unless the user requested it") was misinterpreted as a PER-SESSION permission that must be granted each time via the orchestrator's explicit permission. Stark read "unless the user requested" as "you need a standing instruction," and the standing instruction (CLAUDE.md §10, Kevin 2026-07-28) is not in any editable file — it comes from the harness itself — so it could not be checked/verified/re-confirmed. The line is correct and always has been; the misinterpretation was fixable via clarification.
- **Rule (written into `CLAUDE.md` §10 as "Dispatch is the default, not an escalation"):** Stark dispatches at its own discretion, every session, without asking Kevin. The standing instruction is persistent and implicit (a baseline expectation, not an escalation requiring approval). Dispatch is how the orchestrator catches errors the solo-coder (Derek / Ravi / Vic, whoever's on that pass) would have caught. If dispatch does not happen, the mistakes that would have surfaced do not surface.
- **Regression check:** if a work session produces multiple commits with zero specialist dispatches, that is the smell. Add to the orchestrator's session-start checklist.
- **Status:** CLOSED (rule in CLAUDE.md §10, Kevin 2026-07-28).

### L5 — coding + orchestrator: compile-and-tests-green is not done for concurrency/lifecycle code, review each rewrite iteration
- **Claimed action (iteration 1):** `SpotifyController.connect()` added to `MainActivity.onResume`, reports success honestly when the connect landing.
- **Actual (iteration 1):** `connect()` calls `showAuthView(true)`, so Spotify's consent sheet pops on EVERY foreground return, uncapped. Vic's code review caught this (Kevin's own doc comment in the same commit said "must not happen"). **But Stark's next fix made the problem worse before Derek's code-reading fixed it**, and that sequence is the actual failure mode.
- **Iteration 2:** Derek added an `@Volatile connecting` boolean with `ensureConnected` returning `false` while a connect was in flight — supposed to prevent repeat connect calls. Shipped into a state where "foreground the app, immediately say play X, land back on nav screen" did land but surfaced a misleading error message because the `connecting` gate was still true from the PRIOR connect, so the tool route re-fired the "turn on Music app" message instead of trying to play. Result: re-created the ORIGINAL bug through a new code path, against the fix meant to prevent it.
- **Iteration 3:** Stark then rewrote with a `CompletableDeferred`. The rewrite left `SpotifyAppRemote.connect()` uncaught (a throw from the SDK would not invoke either callback). A synchronous throw reaches neither `onConnected` nor `onError`, so the deferred never completes and `inFlight` never clears: the same deferred object remains dead forever. EVERY awaiting caller on that deferred hangs, and EVERY later `connect()` attempt gets the same dead object. Result: Spotify silently disabled for the whole process lifetime with nothing logged, and no way to recover except a process restart (kill-and-relaunch the app). **Ravi caught this in code review** by reading the callback paths, and ALSO caught a second concurrency bug in the same rewrite: the `inFlight` clear was happening outside the lock, which widened the very race the lock existed to prevent.
- **Root class:** compile-and-tests-green treated as sufficient evidence for concurrency/lifecycle changes, where failure modes are **timing-dependent and structurally invisible to unit tests** (the deferred never completes, so the hang is a runtime deadlock, not a test failure; the race is a rare-case data flip, not a crash). The SDK callback pattern is particularly brittle: an uncaught throw disappears into the threading boundary and never becomes a test exception.
- **Rule (written into this section, consider graduating into playbook-coding.md if it recurs):** Review each ITERATION of a concurrent/lifecycle fix, not just the first attempt. A rewrite prompted by a review finding is new code and gets the same gate. Every threading/lifecycle change must be read by a specialist before it merges, and that includes fixes to fixes. Compile + unit tests are necessary, not sufficient.
- **Regression check:** if a function that touches concurrency (locks, Deferred, callbacks, threading), lifecycle (onResume/onCreate/destroy), or shared state gets rewritten more than once in a session without a review between rewrites, flag it for review before merge.
- **Status:** CLOSED (rule stated here; consider graduating into playbook-coding.md).

### L6 — librarian (Marcus): filed observations the tester never made
- **Claimed in `hardware.md`:** under "Confirmed working 2026-07-28", two entries — "puck tracking in
  place" and "Companion Badge visible when nav is fullscreen".
- **Actual:** Kevin reported the OPPOSITE of both. His words were "the custom puck indeed doesnt show"
  and "the full screen is gone" — so there was no fullscreen state for a badge to appear over, and the
  puck was one of the session's headline defects. The brief handed to Marcus said plainly what was
  confirmed working (places by voice, nav starts, embedded map visible) and separately what was broken.
  He merged plausible-sounding neighbours into the confirmed list.
- **Why this one is dangerous:** a `hardware.md` "confirmed working" line is exactly what a future
  session trusts INSTEAD of re-testing, because re-testing needs the car. A fabricated confirmation
  does not decay into uncertainty, it hardens into fact — and it lands in the one shelf whose whole
  purpose is recording what a human actually observed. It also survived a cheap-model summarisation
  step that nothing downstream re-checks.
- **Root class:** same family as L2/L3 (relay-without-verify), now at the FILING boundary rather than
  the report or brief boundary. The librarian is a relay too, and it is the only relay whose output is
  read as ground truth months later.
- **Rule:** device-observation entries must trace to something the tester actually said. Marcus quotes
  or closely paraphrases the tester rather than summarising into a tidy list, and Stark verifies the
  "confirmed working" section of any `hardware.md` FILE before committing — that section specifically,
  not the whole diff. Never let a FILE run go straight to commit unread.
- **Regression check:** a "confirmed working" bullet that names a feature the same session lists as
  broken, or that no quote in the session supports.
- **Status:** CLOSED (rule stated here; corrected in `hardware.md` in the same pass, 2026-07-29).

### L7 — orchestrator (Stark): brief numbers must be verified before handing down

**Claimed in a maintenance-schedule task brief:** (Stark to Derek) "A dual-axis item MUST be counted as due if EITHER axis is overdue. Test: at currentMileage=10, item B with interval-miles=30000/interval-months=36, lastDone=(0, 0) must be item B at 29,990 mi." Instructing Derek to implement a rule that an item is due when `remainingMiles < 50 OR remainingMonths < 2`.

**Actual:** the test case is mathematically wrong. At currentMileage=10 and lastDoneMileage=0, the remaining miles are 30000 - 10 = 29990. But the item's DUAL-AXIS window means both time and miles must stay within the interval's own windows: 29990 miles remaining is within the 30000-mile interval (good), but the lastDoneDate=(0) is 1970-01-01, so remaining months is 23000+ (severely out of window). The test is comparing the wrong quantity: "miles between last-done and now" (10 - 0 = 10) against "miles in the interval" (30000), not remaining. Derek built the test's expected value, failed it (correctly), asked for clarification. Nadia's code review caught the issue by re-reading the semantics: the test's premise was reversed.

**Why this one fails the brief-checking rule:** I verified that Derek was implementing the rule correctly (traced the code paths, confirmed the gate logic was sound), BUT I did not verify the TEST CASE ITSELF—the numbers I put in the brief. A verified signature (e.g., "this API takes milliseconds") is not a verified SEMANTIC (whether the unit is right). Same failure mode as L3.

**Root class:** relay-without-verify, extending L3. The brief itself needs verification of its CLAIMS (numbers, edge cases, test vectors), not just its facts (API signatures, field names).

**Rule (consider graduating into CLAUDE.md §10 or playbook-coding.md):** A brief's NUMBERS and test vectors MUST be verified before they're handed to an implementer. If you inferred the test case, say "VERIFY: at currentMileage X, the expected result is Y because Z." If you calculated it, spot-check the math. A specialist has no way to tell a verified claim from a confident one and will implement the brief exactly as written. The failure is not the implementer's, it's the brief's.

**Regression check:** a brief that includes numbers/edge cases/test vectors should carry a verification marker or a "VERIFY" flag (similar to L3). Review gate: any technical CLAIM in a brief without a verification tag (traced / tested / calculated / VERIFY).

**Status:** CLOSED (rule stated here; consider graduating into an agent def or playbook).

### L8 — orchestrator (Stark): brief under-specified control inventory

**Claimed in a Cruise-dock task brief:** "Rebuild Cruise COMPACT's 9-control strip into a fixed 4x2 dock. The 9 controls are: [list]."

**Actual:** the inventory enumerated 9 slots but allocated only 8 to the list. Derek correctly guessed that the missing one was the app-tray button (the OS app-grid overlay, CruiseButton("app") -> showAppTray) and added it to the 8th dock position. The brief's ambiguity did not block shipping, but it meant Derek had to infer a design decision (what's the 9th control and where does it go) that the orchestrator should have answered explicitly.

**Root class:** orchestrator brief insufficiency. Not Derek's error.

**Rule:** enumerate-and-assign should balance. If you list N controls, assign all N. If you're uncertain about one, say "9 controls, 8 assigned (below), 1 TBD (the app tray—assign to slot 8 if we ship with it)."

**Status:** CLOSED (rule stated here; the implementer resolved it correctly, but it's noted as a brief-writing miss).

### L9 — orchestrator (Stark): property test caught error in orchestrator reasoning

**Context:** maintenance-schedule feature calculates "days remaining" as `(interval - timeSinceLastDone) / 86400` and rounds the result. The brief asserted: "rounding the result UP (ceil) ALWAYS understates the actual remaining days."

**Actual:** a property test generated randomized interval/timeSince values and found a counterexample: when interval=100 days, timeSince=50 days, remaining=50. Rounding 50 to the nearest integer is 50. Rounding UP is 50. But if interval=100, timeSince=75, remaining=25, rounding up to 25 understates. The assertion was correct for floor (always understates). It's WRONG for round-to-nearest (goes both ways).

**Discovered during code review.** The brief included a comment asserting the rule; Derek tested against the comment, caught the mismatch in the first run.

**Root class:** orchestrator reasoning gone into production without test verification. A comment's logical claim (a property that "always" holds) should land with the claim itself as an assertion in a test. "Always" claims are slippery.

**Rule:** "Always"/"never" claims in comments/briefs should come with a property test or a bounded-search proof, not just assertion. If you claim "rounding UP always understates," write `assert(roundUp(remaining) <= actual)` as a test parameterized across the input space, or bound it: "ceil always understates EXCEPT for integers, where it's equal."

**Status:** CLOSED (rule stated here; the code change switched from ceil to floor, eliminating the ambiguity).

**Status (overall L7/L8/L9):** all three are about briefing quality and verification at the orchestrator boundary. Graduate their rules into CLAUDE.md §10 (brief-checking checklist) or a dedicated "how to write a brief" playbook section if this class repeats.

### L11 — orchestrator (Stark): mandated verification step skipped

**What happened (session 2026-08-02):** Ticket 07's OWN resolution carried an explicit ordering instruction under "specified, not asked": "Render the five previews in `ui/theme/ThemePreview.kt` before building screens on the theme. It compiles and has never been drawn." The step was not performed, and screens were built on the theme anyway. The exact class of bug it existed to catch then shipped into a first-run consent screen (the M3 `contentColorFor` collision - see `playbook-coding.md`, and `decisions.md` 2026-08-02).

**Nobody concealed it.** The coding agent's own report listed the preview render as unmet, in writing, with a reason (it could not render Compose previews from its environment). The orchestrator read that and proceeded to review and device-install anyway. The failure is not a bad report; it is an unmet gate that was surfaced correctly and then not acted on.

**Why this fails differently than L2/L3:** those were relay-boundary failures (reporting a reasoned claim as verified, briefing without verification tags). L11 is about acting on a known gap. The information was present and correct; what was missing was treating a ticket-mandated step as blocking rather than as a note.

**Root class:** Orchestrator completeness check. A ticket is only done when all its listed verification steps have been performed. "Rendered on device" != "passed all verification steps."

**Rule (consider graduating into CLAUDE.md §10):** When a ticket resolution lists verification steps (render the previews, run the test, flash the device, check the log), mark each one done/deferred/impossible EXPLICITLY. A step that was supposed to happen but was silently skipped is not an omission, it is a gate failure. If a step is deferred, say so with a follow-up ticket. If it is impossible, explain why and accept the risk. Never ship with a gate list that includes unchecked items.

**Regression check:** Review gate for tickets with explicit verification steps: every step in the resolution must be checked off in the same commit message or a follow-up entry, or explicitly deferred to another ticket.

**Status:** CLOSED 2026-08-02. The rule now lives in **CLAUDE.md §8, "A ticket's own verification steps are gates, not notes (L11)"**, alongside L10, plus a line in §7's feature-add checklist. An entry closes only when its rule sits in a surface something reads; both of those are read every session.

### L12 — orchestrator + architecture: process-wide cache initialization in a conditionally-started service

**What happened (session 2026-08-02, commits 4272146):** `GeminiKeyProvider`, `ProactivePreferences`, and `LedgerFolderPreferences` are process-wide caches seeded once from disk by an `init()` method. The first two were seeded in `AriaForegroundService.onCreate()`; the third was never seeded anywhere (an orphan). Ticket 07 converted `AriaForegroundService` from an unconditionally-started infrastructure service into an explicit opt-in toggle, OFF by default (`AssistantIgnition`). 

**Actual symptoms, both tested on hardware:** On a normal app launch (toggle OFF), nothing seeded any of the three caches, so they stayed empty despite their backing values being intact on disk. The ledger tab showed "No statements folder connected" on every process start (it did not re-prompt - it simply offered CONNECT again, and reconnecting re-granted silently, which is what made it look like it worked), and the spend gate reported "no Gemini key" for a key that WAS saved. Two of the three were correct code stranded by a startup change; the third, `LedgerFolderPreferences.init()`, genuinely had zero callers anywhere and was a plain omission.

**Root class:** Incidental initialization in an unconditionally-started service that later became conditionally-started. When a service is the guarantee of startup, everything it initialises becomes a hidden dependency, and flipping the service to optional removes that dependency silently. Nothing in compile, unit tests, or the senior-dev review caught it - the bug only appeared when the app was installed and opened.

**Fix:** All three caches now seeded in `MidnightApplication.onCreate()`, which runs once per process lifecycle and is independent of any feature toggle. The caches are now guaranteed to exist on every app start.

**General rule worth graduating into `playbook-coding.md`:** Application-global cache initialization must not live in a foreground service or any domain-specific service that might start conditionally. Use `Application.onCreate()` for process-wide invariants. A service is not a safe home for process-wide init. The rule is a corollary of the architecture decision (see `decisions.md` 2026-08-02): do not place feature-triggered startup inside an unconditionally-started infrastructure service. Converse: if something depends on guaranteed startup, do not hide it inside a service that toggles.

**Regression check:** Any cache class with an `init()` method should have a tracing rule: find every callsite of `init()`, verify one of them runs unconditionally on process launch (either in `Application.onCreate` or in an Activity that cannot be bypassed). If `init()` is never called, or only called from a conditional path, flag it as uninitialized.

**Status:** CLOSED 2026-08-02 - the rule now lives in `playbook-coding.md`'s "Application initialization and process-global state" section, which the coding agent reads.


### L10 — orchestrator (Stark): grep-based reconciliation reported "done" before a real compile found more

**Context:** the LEGION repo port (`memory/MEMORY.md` 2026-07-31) needed a reconciliation pass after
pruning retired classes (billing, city-pop art, GPS beacon, embedded nav, etc.) from copied source.
The pass was scoped by `grep -rl` for the retired class names across the tree — 36 files matched,
all 36 were fixed, and the pass was about to be reported as complete.

**Actual:** a real `./gradlew compileDebugKotlin` run, done as a final verification step rather than
trusted-on-faith, found problems the grep pattern structurally could not: (1) `BuildEntryDao.setPhoto()`
querying a Room column (`photoPath`) that had been dropped from the entity — no retired *class* name
involved, so no grep pattern would ever match it. (2) `service/Phase.kt` deleted outright because it
was bundled mentally with the retired `CompanionBadgeController`/`OverlayOwners` cluster ("looked
badge-related") — but `CompanionPhase` and `LiveSessionController` depended on it for ordinary
conversation state, unrelated to the badge feature. (3) `NowPlayingController`, `MusicController`,
`VolumeController`, `MediaNotificationListener` were referenced by kept files (`AriaBrain`,
`LiveToolbox`) but had never actually been copied into the new repo at all — an omission, not a
retirement, and nothing greps for a file's *absence*. (4) Two machine-specific paths
(`local.properties`' `sdk.dir`, `gradle.properties`' `org.gradle.java.home`) broke the build outright
and would have broken it for anyone else cloning the repo too — invisible to any source-level search.

**Root class:** treating "no matches for the known-bad patterns" as equivalent to "compiles." A grep
sweep only ever proves the absence of what you already know to search for. It has no way to catch
schema mismatches, wrongly-scoped deletions grouped by name-association rather than actual dependency,
or files that were supposed to be copied but silently weren't.

**Rule:** for any port/refactor pass large enough that "does it compile" is a real question, the pass
is not done until a real build (or equivalent — a type-checker, a test run) has actually been run,
not just until the last grep comes back clean. Grep-based verification finds symbol-level breaks;
only a real compile finds the rest. State this explicitly in a plan/brief when scoping this kind of
work, so "36 files reconciled" doesn't get reported as "done" one step early.

**Status:** CLOSED (rule stated here; the LEGION repo now builds clean, verified 2026-07-31, and its
own README documents the same lesson so a future session doesn't rediscover it from scratch).

### L13 — coding + architecture: date-only values and instants must not share a formatter

**What happened (session 2026-08-02, commit 0ee27e9):** The first end-to-end pantry ingestion rendered a receipt printed 04/18/2026 as "Apr 17, 2026" — a one-day drift. Root cause, traced to source: EVERY ingestion path normalizes a parsed calendar date to UTC midnight (`atStartOfDay(ZoneOffset.UTC)`) — `DbsStatementParser`, `BofaStatementParser`, `LedgerStatementAgent`, `PantryReceiptAgent`. But rendering called `ZoneId.systemDefault()` formatters. At UTC-5 (the test device), UTC midnight becomes the previous calendar day. The bug had existed in the ledger since it shipped; earlier screenshots showed Apr 26/18/10 for rows printed 27/19/11.

**Why it was invisible:** The bug survived `./gradlew compileDebugKotlin` (compile), the full unit suite green at every point along the way (46 tests when the ledger renderer was reviewed, 69 when the pantry one was), and two senior-dev reviews that each read the affected file. 71 is the count AFTER this fix added its two regression tests. A date one day off still looks like a date. It was only discoverable because the fixture had a KNOWN printed date to compare the screen against. Same shape as the red consent-screen copy (L11) and the missing Gemini key (L12): invisible to every gate except installing it and looking.

**Root class:** storing a date-only value as epoch-millis at UTC midnight (fine for storage) but rendering it in the device timezone (wrong). When a value came from `LocalDate.parse(...)` or was normalized to UTC midnight, it must be read back through the SAME zone it was written in.

**Fix:** New `documentDate`/`documentDateCompact` render formatters in UTC, deployed to the THREE call sites that handle UTC-midnight values: ledger stream row, pantry receipt header, `get_transactions` voice tool. Deliberately NOT a blanket change: eight other call sites use the same formatters on real instants (`CodeEvent.timestamp`, `ServiceRecord.date`, `BuildEntry.date` are `System.currentTimeMillis()`), and on `MaintenanceItem.lastDoneDate` which is LocalDate-backed but normalized to LOCAL midnight. Those were already correct and would have broken under a blanket change. The fix required understanding which call site holds which type.

**General rule (graduating into playbook-coding.md):** A date-only value and an instant are different types. If they share a formatter, the format must be aware of the zone both were written in. LocalDate-backed values normalized to UTC midnight must render in UTC. Real timestamps normalized to local midnight must render in the device timezone. Assign a different formatter per intent, or wrap the format to know its input zone. Never assume `ZoneId.systemDefault()` is safe for both kinds.

**Regression check:** Any formatter used on a timestamp has one of three sources: (a) a System.currentTimeMillis() value (real instant, render in system timezone), (b) a LocalDate.parse() value (date-only, was normalized to UTC midnight, render in UTC), or (c) a local-midnight value (was normalized in LOCAL timezone, render in system timezone). Code that reads `lastDone*` fields or render-calls to formatters should tag which type they hold. If a formatter is used on both kinds, flag it for review.

**Status:** CLOSED 2026-08-02. The rule now lives in **`playbook-coding.md`** under "Date handling and zone conversions", with the three call sites documented. The meta-lesson (invisible to tests, needs device-level verification on data with known ground truth) is noted in this entry and points to L10/L11/L12, the class of bugs that survive compile/test but not device-level observation.



### L14 — architecture: a reconciliation check that passes on zero parsed rows is not a gate

**What happened (session 2026-08-03, commit 4dad45f):** The new `BofaCardStatementParser` shipped
its first round with three reconciliation layers, 163 unit tests green, and a clean senior-dev
review. Run against Kevin's real July card statement it reported `SUCCESS ... DETERMINISTIC` with
sums tying exactly. It had silently dropped four transactions.

BofA prints the Interest Charged rows in a DIFFERENT shape from every other section - no reference
number and no account number column, just `MM/DD MM/DD <description> <amount>`. The row regex
required the trailing ref/acct pair, so all four failed to match and were skipped. The per-section
subtotal check then compared **zero parsed rows against a printed $0.00** and passed. So did the
other two layers, because interest was genuinely zero that month.

**Why every gate missed it:** the fixtures were generated from the same spec the parser was written
from, so parser and fixture shared the assumption that all rows carry ref/acct. 163 tests could not
see it. The reviewer could not see it. The document's own arithmetic could not see it, because zero
equals zero. It was found only by counting: a raw regex probe of the real PDF found 54 date-led
lines and the parser had returned 50.

**The latent failure it was hiding:** the first month a balance carries and interest is nonzero,
those rows drop, the section check fails, and a completely valid statement quarantines. Fail-closed,
so no bad money lands - but a good statement is blocked, and the reason would look like a bank
formatting change rather than our own parser.

**Root class:** a gate whose pass condition is satisfiable by the empty set. Summing extracted rows
and comparing to a printed total is only a real check if extraction is also proven exhaustive.
Reconciling to zero proves nothing.

**Fix:** rows may now take a bare form as well as the full form, and - the part that matters -
inside a recognized section every non-blank line that is not the section's own total MUST parse as
a row, or the whole document quarantines. An unrecognized line shape is a hard failure, never a
skip.

**General rule (graduated into CLAUDE.md §4 as rule 6):** every reconciliation layer must be
unsatisfiable by an empty or partial extraction, and a line the parser does not recognize is a hard
failure. Silently dropping a row you did not recognize is the same sin as accepting one you could
not verify - rule 2 forbids the second and said nothing about the first.

**Verification discipline this reinforces:** fixtures written from the same spec as the parser test
that the parser matches the spec, not that the spec matches reality (same shape as L10). For any
extraction path, run the real document and **count the rows independently of the parser** before
believing a green suite. That probe is what found this, and it is what found the two fatal bugs in
`BofaStatementParser` the day before (commit c41dfc8).

**Status:** CLOSED 2026-08-03. Rule 6 lives in CLAUDE.md §4; the independent-count discipline is
recorded here and in the commit message.

---

### L15

**Individually correct, wrong in aggregate. Four instances now; the suite cannot see any of them.**

Found 2026-08-07, across one session, three of them on a real phone rather than by any test.

1. **`sync/` was structurally unreachable** and passed every test (`setSyncEnabled` had zero callers).
2. **Categorisation was fully built and never wired** - engine, agent, DAO queries, tests, all
   correct, nothing outside the `ledger` package ever calling it. A month read "uncategorised USD
   477.57" with no way to act on it.
3. **The balances row omitted a term.** `get_balance` and `AccountBalanceRow` each computed the
   available figure independently; the UI's copy left out `pendingDeltaCents`. Kevin logged three
   pending charges, the note beneath the figure updated to mention them, and the headline did not.
   Both call sites compiled, both were tested, they disagreed with each other.
4. **Two accounts store opposite signs for the same meaning.** The BofA card parser stores a
   purchase positive (the statement prints it that way); the checking parser stores it negative
   (same reason). Each parser is internally correct and reconciles perfectly against its own
   document. Category totals sum them naively, so Travel read **+124.30** - which looks like income
   and is actually $124.30 spent camping.

**What they share:** every component is right on its own terms. The defect lives in the seam - two
implementations of one definition, a caller that does not exist, a convention that is local rather
than global. Unit tests check pieces against their own spec and are structurally blind to this.

**The tell.** Ask "is this figure computed in more than one place?" and "does this convention hold
across every producer, or only within one?" Both questions found bugs today that three adversarial
audits and 480 passing tests did not.

**The sharpest instance of the blindness** is `RecurrenceTest`: 24 tests, every fixture built with
`atStartOfDay(ZoneOffset.UTC)`. The suite never left UTC, so a UTC-versus-device-zone mismatch was
invisible **by construction** - and the production code did all its day-maths in UTC while
`startsAt` was written in device zone. Every voice-set reminder fired off by the device's whole UTC
offset (five hours early in Kevin's own zone). The tests were not weak. They were *self-consistent*,
which is harder to notice and exactly L10/L14's shape one layer up. Writing a test that crossed two
zones then found a *third* bug nobody had reported: time-of-day carried as a millisecond offset from
local midnight, so a daily 7am reminder became 8am across a DST boundary.

**General rule (graduate into CLAUDE.md §7's checklist):** a figure must have ONE definition with
callers, never two implementations. A sign, unit or timezone convention must be enforced where data
ENTERS the system, not assumed at each reader. And a fixture built in the same frame as the code
proves only self-consistency - vary the frame (zone, account type, currency) or the test cannot see
the bug.

**Status:** OPEN. Instances 1-4 are fixed; the rule is not yet in CLAUDE.md. Also unresolved: wake
word and ambient listening are a FIFTH instance, built and permanently unreachable because no
settings toggle exists (`WakeWordPreferences.setEnabled` has zero callers) - found in the same audit,
not yet fixed, and not listed in CLAUDE.md §10's known gaps.

## L16 (2026-08-13) - a controller that returns a failure SENTENCE makes every caller a silent-failure site

Found by `bug-hunter` during the aspect-advisors build; two MAJOR defects, one of this shape.

`WorkoutController.generatePlan`, `SleepController.setTarget` and `ReminderController.add` all
signal failure by **returning a spoken failure sentence as an ordinary `String`** rather than
throwing or returning a typed result. `LiveSessionController.handleToolCall` wraps every tool in
try/catch and a timeout, which catches thrown failures honestly - and is completely defeated by a
failure that arrives as a normal return value.

The advisor accept path wrapped those strings as success, so `accept_proposal` returned
`success: true` and marked the `advisor_advice` row **`accepted` permanently**. The row could never
be retried, and the advice-log window showed an accepted proposal that had written nothing.
**The database row itself became the false positive**, not merely a log line.

**Rules.**
1. Any new caller of a controller that returns `String` must verify the write by **reading it back
   through the DAO**, never by inspecting the returned message. String-matching a failure sentence
   rots the first time someone rewords it.
2. A failed write must leave its record in a **retryable** state, not a terminal one.
3. When adding a controller entry point, prefer a typed result over a spoken sentence. The spoken
   sentence is a presentation concern and belongs at the tool layer.

**Sibling defect, same commit:** `accept_proposal` had a check-then-act race (read row, check
`pending`, execute, mark accepted, no transaction). Fixed with
`UPDATE ... WHERE outcome = 'pending'` - **rows-affected is the mutual-exclusion point; a preceding
read never is.** Applies to any claim-then-act path in this codebase.

## L17 (2026-08-13) - a declared tool the system prompt never mentions is reached only by luck

Kevin, on the day the advisors landed: *"i asked the ai what are my goals, i already set some with
it, and also i manually typed in a goal in bio, but it couldnt see the goal."*

The goal tools were correct. `list_goals` with no aspect returns every aspect; the handler was
fine; the rows were really in Room. The transcript (`episodic_turns`) showed what actually
happened:

> driver: Let's set some goals. / companion: What goals did you have in mind?
> driver: weight loss
> companion: I have set a daily target of 2000 calories... 8 hours of sleep... a four-day workout plan.

**It set three TARGETS and recorded no GOAL.** Then "look at the goals in the bio" (mis-transcribed
"ghost") was answered with stored diagnostic codes.

**Root cause: `AriaBrain.sharedInstructions` contained the word "goal" ZERO times.** Also zero
mentions of advisor, budget, workout, meal, or sleep. The prompt still described only the car,
while four domains and five advisors had been built around it. Every one of those tools was
DECLARED, so the model could only find one by name-matching the driver's words - and when asked
for a "goal" it reasonably picked the concrete-sounding target tools instead.

**The advisors had the same disease, undetected:** `ask_advisor` was declared and nothing told the
model when to call it, so the entire five-advisor feature would have sat unreachable.

**Rules.**
1. **Shipping a tool is half the work; the other half is teaching the orchestrator when to reach
   for it.** A feature-add checklist item: does the system prompt mention this capability?
2. **When two tools are near-synonyms to a user's ear, the prompt must draw the line.** Goal vs
   target is the live example: a goal is the long-term intention in the driver's words, a target
   is the per-period number serving it. Nothing in the tool descriptions alone conveyed that.
3. **The transcript table is the diagnostic of first resort** for "the assistant did the wrong
   thing" on a device whose logcat is filtered. `episodic_turns` held the answer in six lines.

## L18 (2026-08-13) - a tool is only real when it is BOTH dispatchable and DECLARED to the right session

Same day, same feature, second report from Kevin: *"it still doesnt see goals."* The transcript:

> driver: No, no. I have one goal, don't I?
> companion: I do not seem to have any recorded goals for you, sir.

...while `SELECT` against the live device DB returned exactly one active goal, and the
`allCurrentGoals()` DAO SQL run by hand returned it too.

**Root cause: `set_goal`, `list_goals`, `close_goal`, `ask_advisor` and `accept_proposal` were all
declared inside `LiveToolbox.onboardingDeclarations()` instead of `declarations()`.** In a normal
session none of the five existed. Onboarding, meanwhile, was advertising five tools it has no
dispatch path for (its dispatch lives in the onboarding screen, not `LiveToolbox.dispatch`).

The mistake is mundane and will recur: two agents each appended a `fns.put(...)` block at the end
of a 4,000-line file, and the nearest enclosing function was the wrong one.

**Why nothing caught it.**
- **13 unit tests for `ask_advisor`/`accept_proposal` passed**, every one calling `dispatch`
  directly. Dispatch worked perfectly. No test asked whether the session is ever TOLD the tool
  exists.
- `senior-dev` and `bug-hunter` both reviewed the feature and neither looked at declaration-set
  membership - they checked the handler, the allowlist, the enforcement.
- The orchestrator (me) verified `dispatch` wiring by grep and called it wired.
- L17's prompt fix the same day was real but treated the symptom: teaching the model to reach for
  a tool that was not on the table.

**Rules.**
1. **Assert declaration-set membership in a test**, not just dispatch. `LiveToolboxDeclarationSetTest`
   now pins: every goal/advisor tool is in `declarations()`, `onboardingDeclarations()` holds
   exactly its five capture tools, and the two sets never overlap.
2. **"The handler works" is not "the tool works."** The full chain is declared -> model calls ->
   dispatched -> handled. A test that starts at dispatch skips the half that failed here.
3. **When a user says the assistant cannot see data that exists, check declaration membership
   before the prompt, the query, or the model.** Query and prompt were both innocent this time;
   two rounds were spent on them.

---

## L19 - A layout claim is `on-device` or it is nothing (2026-08-14)

Kevin: "i cant scroll down anymore. the visual obscures the scroll interface." The LOG tab's inbox
list had become unreachable. The cause was mine: quant-viz ticket 13 put a 180dp `DeckBarChart`
plus labels and a caption at the top of `ui/NotesScreen.kt`, whose root is a **non-scrolling
`Column`**. Children take their heights in order and the LAST child gets only the remainder, so the
list was measured down toward nothing.

**The first fix failed, and failed in the most instructive way.** Ticket 14 wrapped the list in
`Box(Modifier.weight(1f))`. That is a real fix for a real problem - a weighted child cannot be
measured to ZERO - and it was reasoned correctly from the source. On the phone it changed nothing
Kevin could feel: the LOG tab stacked ~770dp of fixed furniture (title, calendar, MISSED,
`GoalsPanel`, mode toggle, then `InboxContent`'s OWN sync note and add-item row) above the list on a
~948dp screen. `weight(1f)` guarantees non-zero height. **It does not guarantee USABLE height.**
Only collapsing the calendar made the screen work, which is not a fix, it is a workaround the user
has to perform.

**Ticket 15 is the real fix, and it is architectural:** `InboxContent`'s `LazyColumn` is now the
LOG/ITEMS tab's ONLY scroll surface, and `ui/NotesScreen.kt` feeds its furniture into it through a
`LazyListScope` header slot (the vendored `compose-slot-api-pattern`). Everything scrolls together;
the calendar scrolls away. **Any future LOG furniture goes into that header lambda as an item, never
into a `Column` above `InboxScreen`.** MISSED also lost its nested same-direction `LazyColumn`
(now 4 inline rows plus "+N more").

**Rules.**
1. **A layout/measurement claim carries the `on-device` tag or it is not a claim.** Both failures
   this session (the dead fix, and the false-empty day below) were reasoned-correct from source and
   wrong in the hand. This is L10 ("a grep-clean result is not a done result") pointed at layout:
   the compiler cannot tell you a view is below the fold.
2. **Suspect the container, not the child.** The instinct was "my chart is too tall". The defect was
   that the screen had one scrollable region buried under fixed furniture. Shrinking the child only
   postpones it - one more goal or MISSED row and it returns.
3. **`Modifier.weight(1f)` answers "can this be squeezed to zero", not "can the user reach this."**

**Two follow-on bugs QA caught in the same feature, both the same shape as §4 rule 6 - a surface
reading complete when the data was never fetched:**
- A calendar day with dots filtered to "Nothing here yet", because the grid counted a whole-month
  window while `InboxScreen` fetched **90 days forward only**. The dots promised what the list
  denied. Fixed by widening the fetch to cover a selected day.
- The `ITEMS // N` badge counted the whole loaded set, so filtering to one day made the number go
  UP (29 against three visible rows) once the widened day fetch added rows.
- **The structural answer, adopted in ticket 16:** the day-events popup renders from the SAME
  `merged` month list that draws the dots. Two renderings of one list cannot disagree. Prefer making
  a class of bug impossible by construction over keeping two windows in sync.

## L20 - Judging colour separation by eye is not a check (2026-08-14)

**What happened:** Ticket 01 (palette-tokens) identified the exact risk in writing: "both takes put their green close enough to the mint that a credit did not separate from the seven debits above it." The ticket acted on it by eye, revised the green value, and shipped a corrected palette. Three tickets later (ticket 06), the `dataviz` skill's palette validator was run against the palette and returned: green fails normal-vision separation against mint (dE 10.4, floor 15) **and** CVD separation against amber (dE 5.5 deutan, floor 8). Four alternative greens were tested; all fail both. Green is geometrically squeezed and no value exists that clears both.

**The failure.** Ticket 01 said "that is better" by eye. The arithmetic said it was still a hard fail, by a wide margin. The eye-based revision landed in production and would have shipped if the validator had not been run later. **The misdeed was not lowering the bar; it was treating eye judgment as a completed check rather than as a starting hypothesis.**

**Root class:** unverified-visual-claim treated as verified. The risk was identified, addressed, re-checked by eye, and still wrong. This is the same family as L10/L14 (tests that prove only self-consistency), now at the pixel-judgment layer: a claim that "this color is better" requires arithmetic, not aesthetic judgment.

**Rule (to graduate into playbook-coding.md and the mission-control map's Notes section):** Any palette decision in this repo runs `scripts/validate_palette.js` from the `dataviz` skill before it is recorded as resolved. It is one command, it is computable, and it caught both a wrong decision and a live shipped bug (DeckBarChart using green target line against amber fill at dE 5.5 under deuteranopia) in a single run. An eye-based revision is a starting point, never a conclusion.

**Regression check:** a palette section in decisions.md without a line saying "validator run" or "run the validator as part of" the next measurement pass.

**Status:** CLOSED 2026-08-14. The rule now lives in `playbook-coding.md` under "Palette validation" and in `.scratch/mission-control/map.md`'s Notes section (commit 3ebb0a7).

## L21 - Category questions need a count before resolution (2026-08-14)

**Pattern: three tickets, one shape.** Ticket 04 (assumed 5 red states, found 50 call sites across 6 unrelated uses). Ticket 07 (assumed motion budget was unspent, found shell animation already spending it on every surface). Ticket 09 (assumed controls were a utility-screens problem, found 142 of 191 controls in data surfaces). In each case the ticket's question named a category ("the states that need escalation", "the surfaces that animate", "the screens with controls") and the premise was falsified by the count: **the real scope was larger and less sorted than the mental model assumed.**

**What was found:** each grep took under a minute. Ticket 04's grep (`sem.quarantined` and five colour-call patterns) found 50. Ticket 07's grep (animate/motion names) found the shell already budgeted. Ticket 09's grep (M3 control constructors) found 191 total, 49 in the stated scope, 142 outside it. In all three cases the count either confirmed the ticket was well-framed or reframed it into a larger/different shape. The first two forced the ticket resolution to widen; the third reframed "utility-screens form vocabulary" into "app-wide form vocabulary."

**Root class:** mental-model assumption on scope validated by reading, not by counting.

**Rule:** Before resolving a wayfinder ticket whose question names a category of thing ("the N things that...", "all the [feature] call sites", "surfaces with [behaviour]"), grep for that category and count it first. The count either confirms the ticket's framing is sound or forces a reframe before resolution. It is cheap enough that not doing it costs more (discovering the wrong scope in build tickets later).

**Regression check:** a wayfinder ticket resolution whose question names a category, with no grep-count claim in the answer. Also check: `grep -rl` proved the absence of something by pattern, without separately running the real compile (this is L10 applied to scope).

**Status:** CLOSED 2026-08-14. The rule now lives in **`.scratch/mission-control/map.md`'s Notes section** (commit `79032ed`), where it applies to all remaining surface inventories. The rule has paid off a fourth time: ticket 11 applied it and found the already-reversed cyberdeck decision, which is now step 7 of ticket 11's reusable method, and is recorded in the decisions entry above.

---

## L22 - A device measurement is only valid for the state the device was actually in (2026-08-14)

**Found during mission-control ticket 16.** The verification method this map adopted escalates from reading the diff to installing and looking, to sampling pixels. Over the effort, three escalating steps each caught what the previous could not:

1. Reading the diff caught nothing on the list of five bugs in ticket 16.
2. **Installing and looking** caught the amber-instead-of-mint heroes and the buried FLEET tiles.
3. **Sampling pixels** caught the bezel's 2dp error and the dropped word, both invisible to the eye on downscaled screenshots.

The method works—but it has its own failure mode, and it bit twice in this effort:

**Ticket 14:** I reported a bezel/key overlap from a downscaled screenshot, measured from visual inspection. Pixel sampling later showed no overlap ever existed. The result was a confident, specific, wrong finding.

**Ticket 16, TalkBack audit:** A build agent reported the purge row as a "severe, real, reproducible defect" — measuring at 29dp unarmed and collapsing to 3dp and 1dp when armed, with empty `content-desc`. It spent a very large diagnostic budget (eight-plus rebuilds) on this "defect" before correctly reverting. Then: **the purge row sits last in a scrolling list, so the dump without scrolling measured a partially-clipped node. Scrolled into view, the node is exactly 48dp, matches ticket 04's spec, and carries the correct label.** An earlier audit had seen the same symptom (19dp unscrolled vs 54dp scrolled) and correctly dismissed it as ordinary list behaviour. That finding existed and was not consulted.

**Root class:** measurement-state mismatch. A device measurement (pixel sample, node bounds dump, scroll state check) proves only what the device was actually doing at measurement time. If the target was offscreen, the measurement describes the clip, not the widget.

**Rule (graduating into `playbook-coding.md`, section "Layout and measurement claims"):** Escalate to the device for anything visual, and sample rather than eyeball - but **put the target in the state you are claiming to measure first.** Scroll it into view, open the state that renders it, and state which state the measurement describes. Name your assumptions about device state in your findings. A measurement that does not name its state cannot be trusted to apply elsewhere.

**Regression check:** A device-measurement finding (pixel sample, node bounds, animation frame) that does not state the device's condition (scroll position, expanded/collapsed, connected/disconnected) when measured. Also: a past measurement finding that is re-cited or acted on without confirming the device is still in that state.

**Status:** OPEN, rule needs graduation. File to `playbook-coding.md` "Layout and measurement claims" section alongside the palette-validator rule from L20. This is cost-free to apply and caught a cascading diagnostic failure that consumed a very large budget over false evidence.

---

## L23 - WAL file is required when pulling database state (2026-08-15)

**Context:** Fleet maintenance ticket 01 pulled the device database to understand current vehicle state. `ls -l` on the device showed `legion_database` last written the previous evening and **`legion_database-wal` at 428KB, written that morning** - so the main file alone was a day stale, and all eight of ticket 01's findings would have been wrong. All three files were pulled in the first command; **this is a near-miss recorded before it bit, not a mistake corrected after the fact.** The margin was one `ls -l` that happened to get read.

**Root class:** database-pull procedure incomplete. A write-ahead log is part of the checkpoint state and cannot be omitted.

**Rule:** Any pull of a Room database file for verification/inspection must include all three files: the main database file, `-wal` (write-ahead log), and `-shm` (shared-memory lock). Check modification times: if `-wal` is newer than the main file, the main file's state is stale. Read all three or the database state is not current.

**Regression check:** A database finding from a device pull that does not mention whether `-wal` and `-shm` were present. Also: an unsourced claim about device database state made without confirming the WAL was included.

**Status:** CLOSED. Rule is recorded here and in ticket 01's verification accounting. Next session: graduate this into `playbook-coding.md` or a database procedures section if database pulls become routine.

## L24 - The repo is ahead of its docs; grep the premise before drafting (2026-08-16)

**Session 9, tickets 12 and 13.** Both asked for work that already existed. `AssistantIdentity.kt:8`
reads "No longer placeholder"; the register copy lives in `ai/Personas.kt` (ALFRED, DOROTHY), with
a shipping picker, voice audition, and an already-decided reconnect behaviour
(`LiveSessionController.refreshIdleVoice()`). CLAUDE.md sections 1, 6 and 10 all asserted the
opposite and were corrected in one pass. This is the **third** instance of the pattern.

**Root class:** drafting against an assumed baseline without checking whether the baseline is stale.

**Rule:** grep the premise before drafting against any ticket. If a ticket asserts "X is missing" or
"we have not decided Y", verify against the tree first. Applied for the rest of session 9.

**Regression check:** a ticket resolution asserting a gap without stating how the gap was verified.

**Status:** CLOSED - rule lives in this shelf and on `.scratch/hands-and-senses/map.md`.

## L25 - A map charted from competitive research describes what a product COULD do, not what the user HAS (2026-08-16)

**Session 9, the hands-and-senses map.** It was seeded from
`.scratch/competitive-landscape/research/landscape.md`. Five of its nine ticket-sized items closed
without being answered: **12 and 13** because the work already existed, **11** because the device
does not exist (no wearable), **09** because the data does not exist (statements never reach
Gmail), **04** because Kevin did not want it. Ticket 08 survived and kept its premise.

**Root class:** treating a map drawn from what a category of product can do as equivalent to what
this user needs and can supply data for.

**Rule:** every ticket confirms its data source exists before a session is spent on it. For a
hardware ticket, confirm the device. For a data ticket, confirm the data actually arrives where the
ticket assumes. Ticket 11's own question 6 did this correctly and predicted its own death.

**Regression check:** a ticket whose premise names a data source, with no note on whether that
source was confirmed or assumed.

**Status:** CLOSED - recorded on the map itself as a standing rule for its remaining tickets.

## L26 - The reviewer's "safe to commit" is an input, not a verdict (2026-08-16)

**Session 9, ticket 01 (clear DTC).** Senior review returned "safe to commit as-is" with two
SHOULD-FIX items. Both were fixed before commit instead. One let a real Mode 04 write reach the ECU
and then lose all three observability channels if the dialog was dismissed mid-send - a real
vehicle change with no trace anywhere, which is the ticket's own defect class pointed backwards.
The other made two surfaces contradict each other: after a `RETURNED` clear the voice correctly
said the fault was still active while STORED CODES showed nothing.

**Root class:** treating a passing review as the gate, rather than reading its flags against the
cost of the failure they describe.

**Rule:** on a first-of-its-kind destructive path - a write to external state that cannot be undone
- "should fix" is "fix". A review verdict is an input to the merge decision, not a substitute for
judging the flags.

**Regression check:** a SHOULD-FIX item on a destructive-path change, committed without resolution
and without a named follow-up.

**Status:** CLOSED. Both fixes landed in `bd4de4b`.

## L27 - When a helper exists with no caller, ask what silently degrades (2026-08-16)

**Session 9.** Kevin reported music play working but pause and skip doing nothing. Tracing that
split found `NowPlayingController.hasAccess` - the app's only check for notification-listener
access - with **zero callers**, so `MusicController` swallowed the SecurityException into an empty
session list and the app never said why. Two commits came out of it (`d683d2c`, `ccef947`).

The same orphaned-helper pattern turned up **four times in one day**: `hasAccess`,
`CompanionProfile.savePersona`, `PersonaTraits.assemblePersona`, and the empty
`MediaNotificationListener` (which nonetheless holds a live notification-read grant).

**Root class:** unreachability at the caller boundary. A helper with no production caller is a
capability the app believes it has and does not.

**Rule:** when a helper exists with no production caller, ask **what silently degrades because
nothing calls it** - that is usually a live user-visible defect, not dead weight. **Do not reach
for deletion first:** `hasAccess` was orphaned and the correct fix was to CALL it, twice. If it is
genuinely pre-emptive, mark it ORPHANED with a pointer to the ticket that will wire it.

**Regression check:** a helper with no production call site and no ORPHANED marker.

**Status:** OPEN. Two of the four are resolved (`hasAccess` is now called from `LiveToolbox` and
`SettingsScreen`). `savePersona`/`assemblePersona` remain orphaned by decision - freeform persona
authoring is back-burnered, and re-wiring them naively fails silently because `personaFor()` falls
back to ALFRED on any unrecognised string. `MediaNotificationListener` stays empty by design.

## L28 - Verify the migration against a copy of the real data, not a fixture (2026-08-16)

**Session 9, Room v21->v22.** The new `code_clear_events` table was checked two ways before any
install: (1) diffed **byte-for-byte** against the kapt-generated `createSql` in
`app/schemas/.../22.json`, and (2) applied to a database pulled off the A25 with `adb exec-out` -
all three files (main, `-wal`, `-shm`), each size compared against `ls -l` on the device, with no
checkpoint so the live database was never written to. Result: 47 to 48 tables, only
`code_clear_events` added, zero existing DDL changed, zero row-count drift, `integrity_check` and
`foreign_key_check` clean. Same posture as v19->v20.

**Root class:** a fixture is a minimal spec-compliant database; the real one carries months of data
and shapes a fixture cannot reproduce.

**Rule:** for any schema migration, apply it to a real pulled copy before install, and assert table
count, per-table DDL, row counts, and both integrity pragmas. Verify the migration SQL is
byte-identical to the generated `createSql` rather than eyeballing it - and do the substitution
correctly, since `createSql` already carries backticks around `${TABLE_NAME}`.

**Regression check:** a schema migration merged with no evidence it was applied to a real device
database.

**Status:** CLOSED. See [[L23]] for the WAL-file discipline this builds on.
