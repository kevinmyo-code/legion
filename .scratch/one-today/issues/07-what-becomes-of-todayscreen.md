---
type: build
status: open
blocked_by: []
map: one-today
---

# TodayScreen is half-emptied. Rehome the survivors, then delete it.

The 2026-09-01 calendar-home cutover took the two biggest things off `ui/TodayScreen.kt` and left
the screen registered but no longer a tab, *"pending a further call on whether it has any role
left"* (`LegionRoute.TODAY`'s own doc comment). This is that call.

**What already moved:**

| Was on Today | Now |
|---|---|
| The agenda / next-event hero | `CalendarScreen`'s day view |
| INTAKE, BIO, LOG half-tiles | `MetersScreen` |
| CRED, FLEET half-tiles | `MetersScreen` |

The pure builders behind those tiles (`buildIntakeTile`, `buildCredTile`, `buildFleetTile`,
`buildBioTile`, `buildLogTile` in `ui/TodayGapResolvers.kt`) are REUSED by `MetersScreen`, not
copied. **`TodayGapResolvers.kt` stays** whatever happens to the screen - it also holds
`AgendaEntry`/`AgendaSource`, which `ui/agenda/DayAgenda.kt` and every calendar surface depend on.

## Six things still live only on TodayScreen

Deleting the screen without rehoming these deletes them, silently. That is the whole risk.

| Survivor | Where it should go, and why |
|---|---|
| `GoalChecklistPanel(compact = true)` - the day's plan | **`CalendarScreen`'s day view.** It is literally a checklist of things to do today, sitting one pane away from the other things to do today. The strongest fit of the six, and the one that makes the day view complete rather than merely moved |
| Weather + `AreaCard` (severe weather, quakes, wildfire, air quality) | **`MetersScreen`.** A standing reading about conditions is exactly the meter idiom, and it is the one meter whose source is outside the app |
| `MediaMiniBar` - Spotify transport | **`MetersScreen`, pinned last.** Not a meter, but it is glanceable and it already has a full panel behind it at `settings/spotify/media` |
| The newsletters tile (`SitrepBuilder.build`, fetch-on-demand) | **`MetersScreen`.** It is a "there is something waiting for you" row, which is what the Needs You pane already is |
| The `DASHBOARD` button - the ONLY entry to the widget pager | **Setup.** The pager is an opt-in alternate surface that Kevin field-tested and reverted on 2026-08-25; a settings row is the honest weight for it. It must not be orphaned - seven on-device grid-feel rounds are behind it |
| `onOpenAlarm`'s deep-link target (`LegionRoute.TODAY`) | **Retarget to `CALENDAR`.** A tapped alarm should land on the day the reminder belongs to, which is now the calendar's day view |

## Then delete the screen

Once all six are rehomed: delete `ui/TodayScreen.kt`, its `composable(LegionRoute.TODAY)`
registration, and the `TODAY` route constant. **Keep `ui/TodayGapResolvers.kt`** - see above; if its
name then misleads, renaming it is a separate change, not part of this one.

Check before deleting, because these are the paths that break silently:

- Every `EXTRA_ROUTE` caller. Traced 2026-09-01: `LiveSessionController` targets `FLEET_PLACES` and
  `MONEY_PANTRY_IMPORT`, `ReminderAlarmReceiver` targets `NOTES`. None targets `TODAY` - but
  `onOpenAlarm` does, from inside the shell.
- `legacyRouteForAspect` in `ui/widgets/WidgetPagerScreen.kt` cross-checks a hardcoded set of
  routes; `LegionRouteTest` asserts against it.
- `onOpenCategory`'s Money drilldown and the key-settings advisory row both fire from this screen.

## Why this is not ticket 04

[[04-delete-the-residue]] deletes things with **no caller at all**. Every survivor above has a live
caller and a real reason to exist - the work here is deciding where each belongs, and the deletion
is the last step rather than the point. Ticket 04 can land independently and in either order.

## The rule this is applying

The calendar-home cutover was justified by ending with FEWER surfaces answering "what do I need to
do". Leaving a half-emptied Today screen registered would leave exactly the second surface that
change existed to remove - and it would be the worse of the two, because it now shows a subset of
the truth while looking like a complete home screen.
