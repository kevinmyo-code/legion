---
map: one-today
ticket: "04"
title: "Delete what the audit found dead in the day-to-day area"
type: task
status: open
status-detail: "Untouched. Note: five more files carrying stale prose references to ui/TodayScreen.kt were found when that screen was deleted 2026-09-01 (a02e9ab) - CredDigestBuilder, CarAspectSummaries, LiveToolbox, SitrepModule, parts of ui/notes. Fold them into this sweep."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Delete what the audit found dead in the day-to-day area

Free-standing - nothing depends on it, and it makes every other ticket easier to read.

## Confirmed dead, no caller in `main/`

| Thing | Where |
|---|---|
| `AlertRowData`, `AlertsSummary`, `AlertTier`, `capAlertRows`, `alertTargetForAspect` | `ui/TodayGapResolvers.kt:611-650` - types and builders with **no producer**, residue of the ALERTS pane retired 2026-08-22 |
| `onAlertTap` | `ui/TodayScreen.kt:401` - a `val` declared and never passed anywhere |
| `dayOfWeekLetter` | `ui/notes/CalendarAgendaResolver.kt:169` - the WEEK AHEAD strip's letters; the strip was replaced by the month grid |
| `GeneratedListScreen`, `GeneratedDetailScreen`, `GeneratedFormScreen` | `ui/generated/` - referenced only by a screenshot test, not in the NavHost |
| `car_tasks` + `CarTaskDao` | 14 rows, one done. Only surviving reference is a tombstone purge in `TelemetryRecorder.kt:233` |
| `foresight_notes` + `ForesightNoteDao` | 0 rows, no caller at all |
| `wellbeing/` (4 files) | Built, tested, has an alarm receiver - `wellbeing_digest_schedule` has 0 rows so it never fires |

## Doc comments describing things that do not exist

**Four files carry comments referring to `buildAlertRows`. There is no such function.**
`ui/TodayScreen.kt:472` claims *"the DAO read and buildAlertRows survive for the audit surfaces that
still use them"* - neither survives, and no audit surface uses them.
`alertTargetForRaiseCategory` is referenced the same way and has no definition.

This is the fourth flavour of the same problem this project keeps hitting: **a comment that was true
when written and was falsified by a later change.** Deleting the code without deleting the comments
would leave the worse half.

## Live but stale-documented - fix the words, keep the code

- **`WidgetDataSource.allRecordTypes()`** says *"on every real device today that list is empty... so
  this costs one cheap query and returns empty."* The device has 7 active aspects, 9 record types, and
  **272 active records with a future `dueAt`.** The claim is false and the cost is not zero.
- **`place_reminders` (0 rows)** is still counted into `LogDigestBuilder`'s digest.
- **`CarAspectSummaries.fleet()`** returns a title that is dead weight - `LegionMediaLibraryService`
  hardcodes the tab label, as that file's own comment admits.

## Deliberately NOT dead, contrary to what one might assume

`OilAnalysisDrilldownScreen` **is** wired (`FleetScreen.kt:693`); it just renders an empty state
because `oil_analyses` has 0 rows. `GlanceCardOverlay` is live with four callers. `WidgetPagerRoot`
is live behind HOME's DASHBOARD button. **Do not delete these.**
