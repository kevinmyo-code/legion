# Backlog: Recaps (Wrapped family)

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Wrapped family: daily drive logs, monthly recaps, yearly Wrapped. All three tiers share one
visual language (the city-pop cassette-card aesthetic) so they read as one family at different
time windows, not unrelated features. Maintained by the librarian.

- ~~**E7**~~ **Daily drive logs DONE 2026-07-08, needs device verify.** Built as the third,
  lightest Wrapped-family tier (day/month/year) rather than strictly the original
  per-drive-card spec, a 2026-07-08 request ("add a way to generate daily drive logs too...
  daily recaps can stay single colored") reframed it as a calendar-day rollup alongside E5/Sprint
  6, not a standalone post-drive card. New `daily_drive_logs` Room table (v3, bumped correctly
  this time, see the B14 lesson in library/blocking.md and `CarDatabase.kt`'s class doc) +
  `DailyDriveLogDao` + `DailyDriveLogController`. Mirrors `MonthlyRecapController`'s aggregation
  shape (same `TRIP_MILES`/`MPG_TRIP` samples, `code_events`, just scoped to one calendar day) but
  deliberately has no cover-art field at all, no image generation, just a one-sentence narrative
  (`SubAgent`, "write exactly ONE sentence" framing), cheaper and meant to feel like a quick note
  rather than an occasion. Trigger: folded into the existing hourly
  `AriaForegroundService.startRecapMonitor()` loop (generates yesterday's log within a 6-hour
  grace window after midnight, idempotent per day) rather than a new loop. UI: a "DAILY LOGS" row
  of small flat single-colored chips (`DailyLogChip`, 58x40dp, no rotated spine label, visually
  distinct from the taller detailed monthly `RecapSpine`s) above the monthly shelf in the same
  RECAP tab; tapping one shows a text-only detail (`DailyLogContent`, stats + the one-line
  narrative, no cover box). Same debug fake-generator pattern as E5/Wrapped (`+ TEST DAILY`
  button, `BuildConfig.DEBUG`-gated, fabricates a random day in the past 2 weeks).
- ~~**E5**~~ **Generation pipeline + view-latest UI DONE 2026-07-08, needs device verify (no
  adb/AVD in this environment to actually run generation and look at it).** `monthly_recaps` Room
  table + `MonthlyRecapDao`; `MonthlyRecapController` aggregates a calendar month from data that
  already existed but was never surfaced this way, `TelemetryRecorder` now also persists a
  `TRIP_MILES` sample per drive (previously computed and discarded, only the `MPG_TRIP` ratio was
  kept), summed/counted/maxed for miles driven, drive count, longest drive; `MPG_TRIP` samples
  averaged for the month's MPG; `code_events`/`service_records` got new `countInRange` queries.
  Narrative (Side B) is a `SubAgent` one-shot in the same first-person car-self voice as C1 (see
  library/backlog-voice.md). Cover art (Side A) reuses `AvatarStudio.generateBackgroundConcepts`
  (the wallpaper pipeline, see library/backlog-visuals.md) with the avatar composited in and the
  color scheme cycled by month for variety; stats render as real Compose text overlaid on the art,
  never baked into the generated image (image models are unreliable at legible numbers). Trigger:
  hourly check in a new `AriaForegroundService.startRecapMonitor()` loop, generates last month
  within a 5-day grace window after rollover, idempotent per vehicle+month.
  ~~**Archive/shelf UI**~~ DONE 2026-07-08, needs device verify (no recap data exists yet on any
  test device, the Room version bump wiped everything, and a real recap needs a full month of
  driving to generate, so this is genuinely unverified end-to-end). RECAP tab now defaults to the
  shelf: every past cassette rendered spine-out (`RecapSpine`, 40x190dp, rotated label, reuses
  `TabRail`'s exact rotate-the-measured-box trick), horizontally scrollable. Notable months get an
  amber-tinted shell + border + a star, the strategy doc's "rarity common->legendary" concept
  (sec 4 Viral Engine) made visible in the archive itself, not just at generation time. Tapping a
  spine opens the full cassette (extracted into `RecapCassetteContent`, shared with what used to
  be the tab's only view) with a "back to shelf" link. Empty state (no recaps yet) unchanged.
  `notable`/`notableReason` are now actually used, the spine coloring, plus a "star + reason" line
  added to the detail view's stats overlay.
- **Sprint 6 - Yearly Wrapped v0 started 2026-07-08.** New `YearlyWrappedController` (aggregates
  from a year's `MonthlyRecap` rows: sums miles/drives/codes/services, averages MPG, counts
  notable months) + narrative (SubAgent, "year in review" framing) + cover art (same
  `generateBackgroundConcepts` pipeline as monthly, color scheme keyed by year instead of month).
  `AvatarStudio.saveRecapCover` reused with `month=0` as a "whole year" sentinel. Poster export and
  the rest of Sprint 6's real scope (hardware validation, creator seeding) still ahead.
  ~~**Didn't show up on the shelf - FIXED same day, needs device verify.**~~ Shipped stateless
  (computed on demand, never saved), the shelf only lists rows actually persisted, so a generated
  Wrapped had nowhere to be listed even though it displayed fine right after generating. Promoted
  `YearlyWrapped` from an ephemeral data class to a real Room entity (`yearly_wrapped` table, v4,
  bumped correctly, see the B14 lesson in library/blocking.md) with its own DAO; both `aggregate()`
  and `generateFake()` now save their result. New `YearlyWrappedSpine` on the shelf (52x220dp,
  always amber-bordered as the "occasion" tier, cover-art peek behind the label) in its own
  "WRAPPED" row below the monthly shelf.
- **Debug fake-data generators, 2026-07-08 (so all 3 tiers' UI could be previewed without waiting
  a day/month/year of real driving).** `BuildConfig.DEBUG`-gated buttons at the bottom of the
  RECAP tab's shelf screen: **+ TEST DAILY** (`DailyDriveLogController.generateFake`),
  **+ TEST RECAP** (`MonthlyRecapController.generateFake`), **+ TEST WRAPPED**
  (`YearlyWrappedController.generateFake`), each fabricates plausible stats (skips real DB
  aggregation) but still makes real Gemini narrative calls (+ cover art for the monthly/yearly
  tiers), so what's on screen looks like real content, not placeholders. Repeated taps just add
  more shelf entries, no dedup, fine for previewing, matches how the real `generateIfDue` paths
  already have their own existence checks that these debug paths deliberately bypass.
- **Spine art pass, 2026-07-08 ("monthly and yearly recaps give the spine more detail and art.
  daily recaps can stay single colored").** The monthly `RecapSpine` didn't actually have any art
  on it before this, just a flat color, same treatment as daily. Added a dimmed cover-art peek
  behind the rotated label (`RecapSpine`, `YearlyWrappedSpine`, shared `Modifier.rotateSpineLabel()`
  extracted for both plus the pre-existing `TabRail`-style rotation). `DailyLogChip` deliberately
  left alone, flat single color, no art, per the instruction.
- **Manual delete with double confirmation, 2026-07-08.** All 3 tiers' detail views now have a
  DELETE action next to "back to shelf", new `DeleteEntryAction` composable requires two separate
  taps before anything happens: the first arms it (label flips to a red "TAP AGAIN TO DELETE"),
  the second (while armed) opens a final `AlertDialog`, only that dialog's Delete button actually
  removes the row. New `@Delete` DAO methods on all 3 (`MonthlyRecapDao`/`DailyDriveLogDao`/
  `YearlyWrappedDao`). Known gap: deleting a row doesn't delete its cover image file, the generated
  PNG under `filesDir/recaps/` is orphaned on disk. Small, harmless (a few hundred KB per deleted
  entry, not user-visible), but worth cleaning up if this becomes a real storage concern later.

## PENDING GRILL 2026-07-16 — Daily recap cassette (physical recap tier object)

**UNRESOLVED FEATURE IDEA, NOT DECIDED.** The daily recap should get a cassette object, not just the Logbook RECAP tab's text. Ties to the (A) brain-dump's movable-cassette/widget idea. Existing surfaces to reconcile: `DailyDriveLogController` (data), LogbookScreen RECAP tab (current home), the deck/cassette object on Cruise. The question is whether daily recaps should live solely in the Logbook or also be launched as a cassette widget on Cruise (like recaps/Wrapped could be). **STATUS UPDATE 2026-07-16:** The monetization angle (1 sample daily recap free) is SUPERSEDED by the pricing decision: recap prose is Gemini-billed and all AI is behind the $10 unlock, no free sample tier. The feature question itself (cassette object yes/no) stays PENDING GRILL pending feature scope and UI hierarchy decision.
