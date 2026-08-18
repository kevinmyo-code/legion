---
map: proactive-mode
ticket: 07
title: "What may a background process actually do on Android in 2026?"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What may a background process actually do on Android in 2026?

## Question

Every delivery and quiet-hours decision depends on what the OS permits. LEGION targets a Samsung
Galaxy A25 on **Android 16 / SDK 36**, sideloaded, with a long-running foreground service.

Establish from primary sources (developer.android.com, the platform behaviour-change pages):

1. **Doze and App Standby as they now stand.** What actually fires for an app in a deep Doze bucket,
   what maintenance windows remain, and how a sideloaded app with no Play Services push differs from
   one with FCM high-priority.
2. **Exact alarms.** `SCHEDULE_EXACT_ALARM` policy today, whether it is grantable to a sideloaded
   app, and what `setExactAndAllowWhileIdle` still guarantees. **LEGION already has
   `notes/AlarmScheduler` which checks the grant and degrades to inexact in words** - establish
   whether that is still the right posture.
3. **WorkManager periodic minimums**, and what Doze does to them in practice.
4. **The Android 15 six-hour `dataSync` cap** and anything later. LEGION's foreground service
   declares `connectedDevice|dataSync|microphone` (`AndroidManifest.xml:150-154`), and
   `.scratch/android-auto/issues/15-*.md` already flags this as unaddressed. **A proactive engine
   riding a service that the OS kills after six hours is a real failure mode.**
5. **Geofencing** - current API, limits, accuracy, battery cost, and whether it survives Doze. The
   Safety and Timing categories both want it.
6. **Notification channels and Do Not Disturb.** Which channel importance can pierce DND, what
   `CATEGORY_ALARM`/`CATEGORY_REMINDER` change, and whether an app may ever bypass DND without the
   user granting it. **This bounds "what may always speak" in quiet hours.**
7. **Samsung specifics.** OEM battery optimisation is aggressive and `MEMORY.md` already records
   OEM-blocked behaviours on this device (`adb push` to `/data/local/tmp`, `pm clear`). Establish
   what One UI does to background work beyond stock Android, and cite it.

Write findings to `research/07-scheduling.md`, cite every claim to its owning URL, label each as
**PLATFORM DOCUMENTATION**, **VENDOR (Samsung)**, or **COMMUNITY REPORT**, then append the Answer
here and set Status: resolved.

**Say plainly which guarantees survive a sideloaded app on a Samsung device with no Play push**, and
name anything that cannot be established without measuring on the phone itself.

## Answer

Resolved 2026-08-17. Full findings with per-claim citations and source labels:
**`.scratch/proactive-mode/research/07-scheduling.md`**.

### Two premise corrections first

1. **LEGION targets API 34, not 36** (`app/build.gradle.kts:38`). The six-hour `dataSync` cap, the
   `BOOT_COMPLETED` FGS block, and the global-DND lockout are all gated on **targeting** 15+, not on
   the device running it. A sideload is under no Play target requirement. **They are dormant today
   and turn on the day someone bumps `targetSdk`.**
2. **The ticket's Samsung premise is wrong on its cited evidence.** `memory/` records the
   `/data/local/tmp` failure as **Git Bash path mangling** (`wireless-adb-available.md:29-31`), not
   an OEM block, and has no `pm clear` entry. Aggressive OEM behaviour on this phone is not
   established by that; it stands on Samsung's own docs (Q7) instead.

### 1. Doze / App Standby

Doze suspends network, ignores wake locks, defers `set`/`setExact`/`setWindow`, and stops
JobScheduler entirely; maintenance windows run everything pending and **get rarer the longer the
device idles**. Surviving in Doze: `setAndAllowWhileIdle`, `setExactAndAllowWhileIdle`,
`setAlarmClock`, high-priority FCM. While-idle alarms are rate-limited - **the docs conflict**:
once per 9 min (doze-standby) vs 7 per hour (power-details). Plan on 9 min.

Bucket quotas are the real cliff: `rare` = 1 alarm/hr + **no network**; `restricted` = 1 alarm/**day**
+ no network. A running FGS keeps the app out of idle, and AOSP `NetworkPolicyManager`'s
`isProcStateAllowedWhileIdleOrPowerSaveMode` (threshold `PROCESS_STATE_BOUND_FOREGROUND_SERVICE`)
means **the FGS keeps network through Doze** (`traced` from source, unmeasured).

**Android 16, all apps, applies at target 34:** jobs running concurrently with a foreground service
now **do** adhere to the runtime quota. The FGS no longer buys unlimited WorkManager runtime.
`setImportantWhileForeground` is a no-op; new `STOP_REASON_TIMEOUT_ABANDONED`.

**No-FCM delta is small.** FCM's unique gift is a *server-originated* wake. LEGION's triggers are all
on-device, and exact alarms + geofence transitions carry the same FGS background-start exemption
high-priority FCM does.

### 2. Exact alarms - `notes/AlarmScheduler` posture is STILL CORRECT

`SCHEDULE_EXACT_ALARM` is denied by default for apps targeting 33+; LEGION is at 34, so it is
denied and user-grantable at Settings > Apps > Special app access > Alarms & reminders. **Sideloading
is irrelevant** - the Play policy governs publishing, not the OS grant. (Honest note: `USE_EXACT_ALARM`
is a *normal* permission and a sideloaded LEGION would receive it at install, since the only barrier
is a Play review LEGION never faces. Rejecting it was a proportionality **choice**, not a constraint;
still the right call - the user cannot revoke it.)

`setExactAndAllowWhileIdle` still guarantees near-precise delivery in Doze. It does **not** escape the
~9-min rate limit, the restricted bucket's one-per-day, or a force-stop. `setAlarmClock` is the only
true wall-clock guarantee. Exact alarms are also exempt from FGS launch restrictions.

Every element of the current posture checks out: `setAndAllowWhileIdle` default, gated
`setExactAndAllowWhileIdle`, degrade **in words** via `exactDowngraded`, re-arm on fire, idempotent
`rescheduleAll` on the three triggers. **Two gaps to file:** nothing offers
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (which grants exact alarms outright, §2.1 of research),
and nothing accounts for the while-idle rate limit.

### 3. WorkManager periodic

**15-minute floor** (stated); flex minimum's numeric value **not established** from any primary source.
Doze defers all jobs to maintenance windows, and bucket quota can make the period arithmetically
impossible (`frequent` = 10 min runtime per 12 h). **A floor of 15 minutes and a ceiling of nothing.**
Correct for "eventually"; wrong for anything a user notices being late.

### 4. The six-hour `dataSync` cap - HIGH STAKES, currently dormant

6 h per 24 h, tracked per type, **shared across all services of that type**, reset when the user
brings the app to the foreground. On timeout: seconds to `stopSelf()` or a **fatal**
`RemoteServiceException`; restarting throws `ForegroundServiceStartNotAllowedException`.

**Gated on `targetSdk >= 35`. LEGION is at 34, so it does not bite today.** Three caveats that matter
more than the reprieve: neither `AriaForegroundService` nor `LedgerIngestService` implements
`onTimeout`, so the failure mode is a **crash, not degradation**; the platform ships a compat flag
(`FGS_INTRODUCE_TIME_LIMITS`) and whether Samsung force-enables it is unknown; and **no platform doc
says what a multi-type service does** - do not assume `connectedDevice` shelters `dataSync`.

**Root cause found:** `startForegroundCompat()` uses `dataSync` as its unconditional base type
*precisely because it is the only type with no runtime prerequisite* - and it is also the only one
with a kill timer. **`dataSync` is the type LEGION wants least and relies on most.**

Fix, in order: (1) drop `dataSync` from `AriaForegroundService` and declare `CHANGE_NETWORK_STATE` so
`connectedDevice` is satisfiable with no runtime grant; (2) move `LedgerIngestService` to a
**user-initiated data transfer job** - explicitly "unaffected by App Standby Buckets quotas" and a
near-exact fit since Kevin opens the tab. `shortService` is too small (~3 min) and is ignored when
combined with another type.

Adjacent: 14 documented exemptions let an FGS start from the background. LEGION has **exact alarm,
geofence transition, Companion Device Manager, battery-optimization allowlist, BOOT_COMPLETED** - it
does not need FCM's. Note `microphone` FGS from `BOOT_COMPLETED` is blocked **since Android 14**, so
that one applies at target 34.

### 5. Geofencing

`GeofencingClient` (Play services - present on a retail A25; "no FCM" is a push decision, not an
absence of Play services). **100 per app.** Latency <2 min typical, 2-3 min backgrounded, **up to 6
min stationary**. Radius 100-150 m minimum. Needs `ACCESS_BACKGROUND_LOCATION` at target 34.
**Not restored across reboot** - must re-register on boot. Use `DWELL` + loitering delay to kill
drive-by storms. **Doze survival is not stated by any primary source**; inferred `reasoned` from the
fact that a geofence transition is a documented FGS-start exemption. Measure it.

### 6. Notification channels and DND

**Importance never pierces DND** - it governs loudness once allowed through. The only bypass is
`NotificationChannel.setBypassDnd`, and per AOSP javadoc an app may set it only with **Do Not Disturb
policy access** and only if the user has not touched the channel; otherwise it is system/user-only.
Even then, the *active policy* must permit priority channels to bypass. **So: an app can never bypass
DND without a user grant. Two independent user gates.**

`CATEGORY_ALARM` / `CATEGORY_REMINDER` / `CATEGORY_EVENT` are matched by `ZenModeFiltering` against
the **user's** priority categories - **a claim, not a key**. `INTERRUPTION_FILTER_ALARMS` suppresses
everything except `CATEGORY_ALARM`, which makes `CATEGORY_ALARM` the strongest and makes using it for
a nudge the user did not set exactly the §7 violation it looks like.

Android 15+ targeting apps cannot change global DND; must contribute an `AutomaticZenRule`. LEGION at
34 still could - **it should not.** `AutomaticZenRule` is the right shape anyway: quiet hours become a
mode the user can see and switch off.

### 7. Samsung / One UI

**[VENDOR]** developer.samsung.com/mobile/app-management.html, in Samsung's own words: apps unused
~**3 days** go to **sleeping**; ~**16 days** to **deep sleeping** ("can't perform any activities,
including notifications or updates"). Mechanism: "**a bucket restriction applies [...] Job, Alarm, and
Foreground-service are restricted**", pointing at Android's **restricted bucket** - one alarm/day, no
network. Escape: user marks the app "never sleeping" (Settings > Device care > Battery > Background
usage limits); deep-link intent `com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY`. Samsung
also commits: FGS of apps targeting Android 14 "**will be guaranteed to work as intended**" when built
to the FGS API policy - which LEGION qualifies for on its face.

**[COMMUNITY]** dontkillmyapp.com/samsung corroborates the 3-day figure and reports missed alarm-clock
alarms; records Samsung's July 2024 promise to drop non-standard optimizations. The widely-repeated
"30-50% of notifications lost on Samsung" figure has **no methodology and is not established**.
**No One UI 7/8 (Android 16) developer documentation exists** - Samsung's page stops at One UI 6.

**The real failure mode is not "Samsung kills the FGS."** It is: Kevin talks to LEGION daily but never
opens its UI, One UI marks it unused after 3 days, the app lands in the restricted bucket, and the
proactive engine gets one alarm a day - while the service still runs and everything looks fine.
Whether wake-word sessions count as "use" is unknowable without measuring.

### Bottom line

**Survives:** `setAlarmClock` (exact, costs a visible alarm icon) - `setExactAndAllowWhileIdle` with
the grant or the allowlist, rate-limited ~9 min - `setAndAllowWhileIdle` free and permission-less,
same limit - **FGS keeps network through Doze** - **FGS keeps the app out of AOSP App Standby** -
geofence transitions with 2-6 min jitter.

**Does not survive:** FGS exempting jobs from quota (gone in Android 16, all apps) - WorkManager
honouring its period - any notification piercing DND without a user grant - **anything at all if One
UI marks the app sleeping.**

**Primitive per trigger class:** user-set time -> existing `AlarmScheduler` (exact when granted, said
in words when not). Soft nudge -> `setAndAllowWhileIdle`. Arrival/departure -> geofence, `DWELL`,
re-registered on boot. Car connect -> Companion Device Manager + `connectedDevice`. Folder scan ->
periodic work, no promises, migrate to UIDT. Long ingest -> **UIDT job, never `dataSync` FGS**. Quiet
hours -> honest category + user-granted bypass + `AutomaticZenRule`.

**Three actions, in order:** (1) remove `dataSync` from `AriaForegroundService`; (2) ask for
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` at onboarding - one prompt buys exact alarms, FGS
background-start, and partial Doze exemption; (3) tell the user to mark LEGION "never sleeping".

**Stop believing the foreground service is a licence.** It keeps the process alive and keeps network
in Doze. **It does not keep the app scheduled.**

### Cannot be settled without measuring on the A25

Ten items, with commands, in research §8. The gate on all of them is **#8: does
`AriaForegroundService` survive a 12-hour screen-off unplugged run at all.** Run it first. Also
unmeasurable from docs: LEGION's actual standby bucket day to day; whether One UI auto-sleeps an app
used only by voice; whether Samsung force-enables `FGS_INTRODUCE_TIME_LIMITS` below target 35; whether
the `dataSync` timer applies to a multi-type service; the true while-idle rate limit (9 min vs 7/hr);
geofence latency in Doze; maintenance-window cadence; `MIN_PERIODIC_FLEX_MILLIS`.
