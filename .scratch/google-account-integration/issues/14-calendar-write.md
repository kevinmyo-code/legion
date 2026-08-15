# BUILD: Alfred puts an appointment on the calendar

Type: task
Status: resolved
Blocked by: 13

## Answer

**Built 2026-08-13. Verified by the orchestrator directly, not relayed: 692 tests, 0 failures,
0 errors, `cleanTestDebugUnitTest` so nothing was `UP-TO-DATE`. `CALLER_IS_SYNCADAPTER` appears
nowhere in the source except three comments warning against adding it.**

- `CalendarProvider` gains `hasWritePermission` and `insertEvent(...)`. Returns null on refused
  permission or provider failure, never throws.
- `WRITE_CALENDAR` in the manifest.
- New pure `ui/notes/ScheduleIntentResolver.kt`: `resolve(explicitKind)` and
  `confirmationPhrase(kind, itemText, whenPhrase)`, so the spoken sentence is **derived from the
  decision** and no call site hardcodes wording. 11 tests, hitting near-misses ("appointments",
  "event") and the default-on-ambiguity branch hard.
- **Tool count unchanged.** `manage_item` gained one optional `kind` argument rather than a new
  tool being registered.

### Two design calls the agent made and flagged, both accepted

1. **`WRITE_CALENDAR` is requested together with `READ_CALENDAR` at the screen, not at first write.**
   The reason is correct and worth recording: a voice write runs off `AriaForegroundService`, which
   has **no Activity to raise a permission dialog from**, so "in context at first write" is not
   implementable for the path that matters. Asking at the one screen that already asks for calendar
   access is the honest reading of the requirement. A refused `WRITE_CALENDAR` still degrades safely.
2. **A failed calendar write falls back to a local reminder and says why**, rather than refusing.
   Extrapolated from ticket 04's "ambiguous defaults to reminder" to "impossible defaults to
   reminder, out loud". Consistent with ticket 10's rule, which bans *silent* queueing and partial
   answers, not a stated fallback. Accepted.

### Deferred, on-device, NOT claimed - and one is the map's biggest remaining risk

- ~~**THE SPIKE.**~~ **RUN AND PASSED 2026-08-13, on the OnePlus CPH2471 over wireless ADB.**
  Ticket 02's one load-bearing inference is now `on-device`, not `inferred`.
  **Method** (a platform probe, not a probe of our code - deliberately): insert an event straight
  into `content://com.android.calendar/events` on `calendar_id=5` (Kevin Myo Personal, a
  `com.google` calendar at access 700) via `adb shell content insert`, with no
  `CALLER_IS_SYNCADAPTER`, then watch the row.
  **Result**: immediately after insert, `dirty=1, _sync_id=NULL` - exactly the state
  `CalendarProvider2.insertInTransactionInner` was read to produce. **Within 10 seconds, `dirty=0`
  and `_sync_id=75j3gp356go32bb261h6ab9kc9imab9p6pgjab9l61ijcc3264oj4eb674`** - a Google server-side
  id. **Google's closed-source sync adapter uploads an ordinary app's insert.** Deleting the row
  likewise propagated: `deleted=1, dirty=1` then the tombstone cleared within 10 seconds.
  **What this does and does not prove.** It proves the *platform* behaviour, which was the actual
  open question and the thing no amount of doc-reading could settle. It does **not** exercise
  `CalendarProvider.insertEvent` itself, which runs as the app rather than as shell - a different
  UID, though neither is a sync adapter. That remains below.
- **The routing risk, which the unit tests structurally cannot cover.** `kind` is an optional tool
  argument and **anything that is not exactly "appointment" becomes a reminder**. The resolver is
  well tested; what is untested is whether the model actually *supplies* `kind` in real use. If it
  habitually omits it, nothing ever reaches the calendar and the failure is silent-ish - Alfred would
  say "I'll remind you", which is true but not what was asked. **The device test must include an
  unmistakable appointment phrasing and confirm it landed on the calendar, not in Notes.**
- That Alfred said which store he used, heard rather than read.
- That an ambiguous phrasing became a reminder, from a real model call.
- That LEGION did not also fire its own notification for a Google event. `traced` to code
  (`AlarmScheduler` untouched, `addAppointment` never calls `NotesController.addItemDue` on success),
  not observed.

## Question

Nothing to decide. Graduated 2026-08-13 from [ticket 04](04-what-happens-to-local-timed-items.md).
**This is the ticket carrying ticket 02's one load-bearing inference - do the spike FIRST.**

1. **THE SPIKE, before any of the rest.** Insert an event through `CalendarContract` into a
   `com.google` calendar on the device and confirm it **actually reaches Google's servers** - check
   it on another device or in a browser, not in the local provider. Ticket 02's AOSP reading says a
   non-sync-adapter insert is flagged `DIRTY` and notified with `syncToNetwork` true, which is the
   intended upload path; that Google's closed-source adapter completes the upload is `inferred`.
   ~20 minutes. **If it fails, stop and report** - it invalidates ticket 02 and the map returns to
   the REST option.
2. **Write side of the provider layer**: insert with `CALENDAR_ID`, `DTSTART`, `EVENT_TIMEZONE`,
   plus `DTEND` or `DURATION`+`RRULE`. **Never append `CALLER_IS_SYNCADAPTER`** - nothing stops you
   and it is the one way to silently break upload (ticket 02).
3. **`WRITE_CALENDAR` runtime permission**, requested at first write, per ticket 06.
4. **The appointment-versus-reminder call, and saying it out loud.** Ticket 04's named cost: "dentist
   Tuesday at 3" is an appointment, "remind me to change the oil Tuesday" is a reminder, and they go
   to different stores. **Alfred always says which he did** - "Put that on your calendar for Tuesday
   at 3" versus "I'll remind you Tuesday". **Ambiguous defaults to reminder**, because a reminder is
   local, private and trivially undone.
5. **Tool surface**: extend the existing notes tools rather than adding new ones if it can be done
   without widening their descriptions much. Every tool is prompt tokens on every live session.

## Verification

- The spike, above, before anything else.
- On the device: create one by voice, confirm it appears in the Google Calendar app, and confirm
  Alfred said which store he used.
- Create an ambiguous one and confirm it became a reminder, not an appointment.
- Confirm LEGION does **not** also fire its own notification for the calendar event (ticket 04).
