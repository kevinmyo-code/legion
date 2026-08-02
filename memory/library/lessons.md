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
