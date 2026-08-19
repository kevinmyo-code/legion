# What a background process may actually do on Android in 2026

Research for `.scratch/proactive-mode/issues/07-scheduling-research.md`.
Target device: Samsung Galaxy A25 (`SM-A256U`), **Android 16 / SDK 36**, sideloaded, no FCM.
Charted 2026-08-16.

**Source labels used throughout:**

- **[PLATFORM]** - developer.android.com or AOSP source (`android.googlesource.com`).
- **[VENDOR]** - Samsung / One UI first-party material.
- **[COMMUNITY]** - forums, blogs, aggregators, issue trackers. Never blended with the above.

---

## 0. Two repo facts that change every answer below

### 0a. LEGION targets **API 34**, not 36

`app/build.gradle.kts:33-38` - `compileSdk = 36`, `minSdk = 24`, **`targetSdk = 34`**. `traced`.

This is load-bearing and the ticket's framing does not account for it. The heaviest restrictions in
this document (the six-hour `dataSync` cap, the `BOOT_COMPLETED` FGS block, the global-DND lockout)
are all gated on **targeting** Android 15+, not on the device running it. A sideloaded app is under
no Play target-SDK requirement, so LEGION can hold 34 indefinitely.

> "New apps and app updates must target Android 16 (API level 36) or higher to be submitted to
> Google Play" - **[PLATFORM]** https://developer.android.com/google/play/requirements/target-sdk
> (a *Play submission* rule, not an OS rule; irrelevant to a sideload).

The install-time floor is much lower: apps targeting below API 23 cannot be installed as of Android
14, unchanged through 16. **[PLATFORM]**
https://developer.android.com/about/versions/14/behavior-changes-all

**So: several catastrophic-sounding constraints are currently dormant, and turn on the day someone
bumps `targetSdk`.** That is a design input, not a reprieve.

### 0b. The ticket's Samsung premise is wrong on the facts it cites

The ticket says `memory/MEMORY.md` records `adb push` to `/data/local/tmp` and `pm clear` as
**OEM-blocked** on this device. It does not. `memory/wireless-adb-available.md:29-31` attributes the
`/data/local/tmp` failure to **Git Bash path mangling** (`secure_mkdirs() failed`, fixed with
`MSYS_NO_PATHCONV=1` or the PowerShell tool), and there is no `pm clear` entry anywhere in the
memory tree. `traced`.

Aggressive OEM behaviour on this specific phone is therefore **not established** by that evidence.
It has to stand or fall on §7's own sources.

---

## 1. Doze and App Standby as they now stand

### 1.1 What Doze suspends

**[PLATFORM]** https://developer.android.com/training/monitoring-device-state/doze-standby

While in Doze the system:

1. Suspends network access.
2. Ignores wake locks.
3. Defers standard `AlarmManager` alarms - including `setExact()` and `setWindow()` - to the next
   maintenance window.
4. Does not perform Wi-Fi scans.
5. Does not let sync adapters run.
6. Does not let `JobScheduler` run (so no WorkManager work).

Maintenance windows: "runs all pending syncs, jobs, and alarms" and "lets apps access the network".
They are "scheduled less frequently over time with longer inactivity" - the interval grows the
longer the device stays idle. The doc gives no numeric schedule; **exact window cadence on this
device: not established from primary sources.**

### 1.2 What still fires in Doze

Same page, verbatim exemptions:

| Mechanism | Fires in Doze? |
|---|---|
| `setAndAllowWhileIdle()` | Yes |
| `setExactAndAllowWhileIdle()` | Yes |
| `setAlarmClock()` | Yes - "system exits Doze shortly before these fire" |
| `setExact()` / `setWindow()` / `set()` | No - deferred to maintenance window |
| FCM **high priority** | Yes - "wake the app and access the network" |
| FCM normal priority | No - deferred to maintenance window |
| `JobScheduler` / WorkManager | No - deferred to maintenance window |

**Rate limit on while-idle alarms. Two primary sources give different numbers and they conflict:**

- **[PLATFORM]** https://developer.android.com/training/monitoring-device-state/doze-standby -
  "Neither `setAndAllowWhileIdle()` nor `setExactAndAllowWhileIdle()` can fire alarms more than once
  per nine minutes, per app."
- **[PLATFORM]** https://developer.android.com/topic/performance/power/power-details - in the
  screen-off/doze row: "While-idle alarms: Limited to **7 per hour**."

Nine minutes implies ~6.7/hr; seven per hour implies ~8.5 min. They are close but not the same, and
neither page acknowledges the other. **Treat "roughly one while-idle alarm per 9 minutes" as the
safe planning number and measure the real ceiling on device.**

### 1.3 App Standby buckets and their actual quotas

**[PLATFORM]** https://developer.android.com/topic/performance/appstandby and
https://developer.android.com/topic/performance/power/power-details

Buckets: **active**, **working set**, **frequent**, **rare**, **restricted**, plus a **never**
bucket for installed-but-never-run apps.

Verbatim resource-limit table:

| Bucket | Regular jobs | Expedited jobs | Alarms | Network |
|---|---|---|---|---|
| Active | Up to 20 min in a rolling 60 min period* | Up to 30 min / rolling 24h* | No execution limits | No restrictions |
| Working set | Up to 10 min in a rolling 4h period | Up to 15 min / rolling 24h | **10 per hour** | No restrictions |
| Frequent | Up to 10 min in a rolling 12h period | Up to 10 min / rolling 24h | **2 per hour** | No restrictions |
| Rare | Up to 10 min in a rolling 24h period | Up to 10 min / rolling 24h | **1 per hour** | **Disabled** |
| Restricted | Once per day for up to 10 min | Up to 5 min / rolling 24h | **One alarm per day (exact or inexact)** | **Disabled** |

\* "Execution quota behavior changed in Android 16" - see §1.5.

Restricted bucket specifics **[PLATFORM]** appstandby: jobs "run once per day in a 10-minute batched
session", "restricted jobs don't run by themselves; there must be at least one other job running or
pending at the same time", and one alarm per day. Restrictions "are loosened when the device is
charging, idle, and on an unmetered network".

Charging: "No execution limits except for the *restricted* standby bucket."

**A deep bucket is where a proactive engine dies.** In `rare`, LEGION gets one alarm per hour and
**no network**; in `restricted`, one alarm per day and no network. Everything else in this document
is downstream of not landing there.

### 1.4 What keeps LEGION out of the deep buckets

**[PLATFORM]** https://developer.android.com/training/monitoring-device-state/doze-standby - an app
is **not** considered idle if "the app has a process in foreground (as activity or foreground
service)" or "the app generates a notification users see on lock screen or notification tray".

LEGION's `AriaForegroundService` satisfies both while it is running. That is the single strongest
scheduling asset the app has, and it is exactly what §4 puts at risk.

**Network in Doze while an FGS runs.** AOSP is explicit here, and this is better evidence than the
prose docs:

**[PLATFORM]** https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/NetworkPolicyManager.java

```java
public static boolean isProcStateAllowedWhileIdleOrPowerSaveMode(
    int procState, @ProcessCapability int capability) {
    if (procState == PROCESS_STATE_UNKNOWN) {
        return false;
    }
    return procState <= FOREGROUND_THRESHOLD_STATE
        || (capability & ActivityManager.PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK) != 0;
}
```

Javadoc: *"Returns true if procState is considered foreground and as such will be allowed to access
network when the device is idle or in battery saver mode."* `FOREGROUND_THRESHOLD_STATE` is
`ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE`, and
**[PLATFORM]** https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityManager.java
orders `PROCESS_STATE_FOREGROUND_SERVICE` **before** `PROCESS_STATE_BOUND_FOREGROUND_SERVICE`.

**Therefore: while `AriaForegroundService` is running, LEGION keeps network access through Doze.**
`traced` from source, not measured.

### 1.5 Android 16's job-quota change - applies to LEGION *today*

**[PLATFORM]** https://developer.android.com/about/versions/16/behavior-changes-all (the **all
apps** page, i.e. not gated on `targetSdk`):

> "Starting in Android 16, we're adjusting regular and expedited job execution runtime quota based
> on the following factors:
> - **Which app standby bucket the application is in**: in Android 16, active standby buckets will
>   start being enforced by a generous runtime quota.
> - **If the job starts execution while the app is in a top state** [...]
> - **If the job is executing while running a Foreground Service**: in Android 16, jobs that are
>   executing concurrently with a foreground service will adhere to the job runtime quota."
>
> "This change impacts tasks scheduled using WorkManager, JobScheduler, and DownloadManager."

Previously (**[PLATFORM]** power-details): "Apps in the *active* standby bucket had no execution
limits. Apps running a foreground service had no execution limit."

**Consequence for LEGION: the foreground service no longer buys unlimited WorkManager runtime.**
Even in `active`, jobs are metered (20 min / rolling hour). This is an all-apps change, so
`targetSdk = 34` does **not** shield the app from it on an Android 16 device.

Two more all-apps Android 16 items from the same page:

- `JobInfo.Builder#setImportantWhileForeground(boolean)` "no longer functions effectively and
  calling this method will be ignored"; `isImportantWhileForeground()` returns `false`.
- New stop reason `STOP_REASON_TIMEOUT_ABANDONED` for jobs whose `JobParameters` was GC'd without
  `jobFinished()`. "If there are frequent occurrences of the new abandoned stop reason, the system
  will take mitigation steps to reduce job frequency." Relevant if LEGION ever leaks a Worker.

**[PLATFORM]** https://developer.android.com/about/versions/16/behavior-changes-16 (targeting 16)
contains **no** background-work changes beyond `ScheduledExecutorService.scheduleAtFixedRate` now
replaying at most one missed execution instead of all of them.

### 1.6 No-FCM vs high-priority-FCM: the actual delta

There is exactly one thing an FCM-equipped app gets that LEGION cannot replicate:

1. **A server-originated wake through Doze.** High-priority FCM wakes the app and grants network
   **[PLATFORM]** doze-standby. Nothing on-device is equivalent, because nothing on-device can know
   about an external event.
2. **An FGS background-start exemption.** "Your app receives a high priority message using Firebase
   Cloud Messaging" is a listed exemption from the Android 12+ ban on starting a foreground service
   from the background - **[PLATFORM]**
   https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
3. **[PLATFORM]** power-details: high-priority FCM has "No execution limits" even in Doze; and since
   Android 13 "App Standby Buckets no longer determine how many high priority FCMs an app can use",
   though "the system now downgrades high-priority messages if an app consistently sends them
   without resulting in notifications".

**What LEGION loses by having no push: nothing about *timing*.** Every proactive trigger LEGION has
is on-device by construction (time, geofence, OBD connect, statement arriving in a folder). The
exact-alarm and geofence exemptions in §2 and §5 cover the same ground as (2). The only genuine loss
is (1), and (1) only matters for triggers LEGION does not have.

---

## 2. Exact alarms

### 2.1 The grant, today

**[PLATFORM]** https://developer.android.com/about/versions/14/changes/schedule-exact-alarms

> "`SCHEDULE_EXACT_ALARM` [...] is **no longer being pre-granted to most newly installed apps
> targeting Android 13 and higher** (will be set to denied by default)."

Affected apps: target 33+, declare the permission, aren't a calendar or alarm clock app, and don't
fall under an exemption. **LEGION targets 34 and is not a clock app, so it is denied by default.**

Always allowed regardless: platform-signed apps, privileged apps, and **"apps on the power allowlist
(via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent)"**. That last one is available to LEGION
and is a genuine second path to exact alarms.

User grant path: **Settings > Apps > Special app access > Alarms & reminders**, or in-app via
`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.

### 2.2 Can a sideloaded app obtain it? Yes.

Nothing in the grant path involves Play, the installer, or the signing certificate. The Play policy
attaches to **publishing** (`USE_EXACT_ALARM` "will not be able to publish a version of their app
with this permission in the manifest unless they qualify"), not to the OS grant.
**[PLATFORM]** same page + https://support.google.com/googleplay/android-developer/answer/12253906

Worth stating plainly since the manifest comment (`AndroidManifest.xml:39-43`) records the choice:
`USE_EXACT_ALARM` is a **normal** permission granted at install with no user prompt, and a sideloaded
LEGION would in fact receive it, because the only thing stopping it is a Play review LEGION never
faces. The project rejected it on proportionality grounds, not because it is unobtainable. That
remains the right call - it is a permission the user cannot revoke - but the reason should be stated
as a choice, not a constraint.

### 2.3 What `setExactAndAllowWhileIdle` still guarantees

**[PLATFORM]** https://developer.android.com/develop/background-work/services/alarms/schedule

- `setExactAndAllowWhileIdle()`: "Invokes alarm at nearly precise time [...] Works even if
  battery-saving measures are in effect", allows work during Doze.
- `setAlarmClock()`: "The system never adjusts the delivery time" and "the system leaves low-power
  modes if necessary to deliver". This is the only true wall-clock guarantee AlarmManager offers -
  at the cost of a user-visible alarm icon and, per the doc, significant battery impact.
- Recommended Doze pattern, verbatim: *"To perform work while the device is in Doze, create an
  inexact alarm using `setAndAllowWhileIdle()`, and start a job from the alarm."*
- **"Android considers exact alarms to be critical, time-sensitive interruptions. For this reason,
  exact alarms aren't affected by foreground service launch restrictions."**

Guarantees it does **not** give: immunity from the ~9-minute / 7-per-hour while-idle rate limit
(§1.2), or immunity from the restricted bucket's one-alarm-per-day (§1.3), or survival of a user
force-stop.

When the grant is revoked: "Your app stops. All future exact alarms are canceled." The system
broadcasts `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, and the doc tells apps to
re-check `canScheduleExactAlarms()` and reschedule - which is precisely what
`ExactAlarmPermissionReceiver` -> `AlarmScheduler.rescheduleAll` already does.

### 2.4 Verdict on `notes/AlarmScheduler`'s posture

**Still correct, and correct for the documented reasons.** Point by point against
`app/src/main/java/com/kevin/legion/notes/AlarmScheduler.kt`:

| Existing behaviour | Verdict |
|---|---|
| `setAndAllowWhileIdle` as the permission-free default | Correct. Doze-exempt, no grant needed, matches the doc's own recommended Doze pattern verbatim. |
| `setExactAndAllowWhileIdle` only when the item is marked exact **and** `canScheduleExactAlarms()` | Correct. This is the documented required check. |
| Degrade to inexact **in words** via `exactDowngraded` rather than silently | Correct, and stronger than the docs require. §4-rule-5-shaped: the app is not asserting a precision it does not have. |
| Re-arm on fire, never `setRepeating` | Correct and now more important than when it was written - `setRepeating` is inexact since API 19, and per-alarm rate limits mean a repeating alarm has no meaningful period anyway. |
| `rescheduleAll` on boot / start / permission-change | Correct; matches the documented receiver guidance. |

**Two gaps to file, not fix here:**

1. `canScheduleExact()` returns `true` unconditionally below API 31 - fine - but there is no path
   that offers the user `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which per §2.1 would grant
   exact alarms *and* (per §1.4 / §4) solve several other problems at once. **This is the single
   highest-leverage permission LEGION is not asking for.**
2. Nothing in `AlarmScheduler` accounts for the rate limit. A proactive engine that fires more than
   ~6 while-idle alarms an hour will have them coalesced without notice.

---

## 3. WorkManager periodic minimums

**[PLATFORM]** https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work

- "**The minimum repeat interval that can be defined is 15 minutes** (same as the JobScheduler API)."
- Flex: "The flex period begins at `repeatInterval - flexInterval`, and goes to the end of the
  interval" and "must be greater than or equal to `PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS`".
  **The numeric value of `MIN_PERIODIC_FLEX_MILLIS` is not established** - the API reference pages
  do not render to a fetcher, and no prose page states the number. Commonly cited as 5 minutes;
  treat that as unverified until read out of the AAR.
- Constraints: "even if the defined repeat interval passes, the `PeriodicWorkRequest` will not run
  until this condition is met. This could cause a particular run of your work to be delayed, or even
  skipped if the conditions are not met within the run interval."
- Expedited work: quota is "based on App Standby Buckets", is "more restrictive than the ones used
  for other types of background jobs", and "an execution time quota applies only when your app is in
  the background".

**What Doze does to it in practice.** Two compounding effects:

1. **Doze itself:** `JobScheduler` does not run at all in Doze; work is deferred to the next
   maintenance window - **[PLATFORM]** doze-standby. So a 15-minute period becomes "whenever the
   next maintenance window lands", and windows get **rarer the longer the device idles**.
2. **Bucket quota:** §1.3. In `frequent`, total job runtime is 10 min per 12 hours. A 15-minute
   period cannot be honoured by arithmetic alone.
3. **Android 16:** §1.5 - running an FGS no longer exempts jobs from the quota.

**Practical read: `PeriodicWorkRequest` is a floor of 15 minutes and a ceiling of nothing.** It is
correct for "eventually, when convenient" work (folder scans, sync, cache maintenance) and wrong for
anything a user would notice being late.

Long-running work: **[PLATFORM]**
https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- `setForeground()` promotes a Worker to an FGS with a notification, which requires an FGS type on
API 34+, and carries an explicit warning: "**Android 16 Note:** Long-running workers can exhaust your
app's job quota. Consider launching the foreground service directly or using user-initiated data
transfer jobs as alternatives."

---

## 4. The six-hour `dataSync` cap - and whether it bites LEGION

### 4.1 What the cap does

**[PLATFORM]** https://developer.android.com/develop/background-work/services/fgs/timeout

> "If an app targets Android 15 or higher, the system places restrictions on how long certain
> foreground services are allowed to run while your app is in the background. Currently, this
> restriction only applies to `dataSync` and `mediaProcessing` foreground service type foreground
> services."

> "The system permits `dataSync` and `mediaProcessing` foreground services to run for a total of
> **6 hours in a 24-hour period**, after which the system calls the running service's
> `Service.onTimeout(int, int)` method."

Mechanics, verbatim from the same page:

- Tracked **separately per type**, and **shared across all of the app's services of that type**:
  "if an app runs a `dataSync` service for four hours, then starts a different `dataSync` service,
  that second service will only be allowed to run for two hours."
- **"if the user brings the app to the foreground, the timer resets and the app has 6 hours
  available."**
- On timeout the service "has a few seconds to call `Service.stopSelf()`" and "is no longer
  considered a foreground service". Failing to stop:
  `android.app.RemoteServiceException: "A foreground service of type [service type] did not stop
  within its timeout: [component name]"` - a **fatal** exception.
- Starting a new one after exhaustion throws `ForegroundServiceStartNotAllowedException` with
  *"Time limit already exhausted for foreground service type dataSync"*.

### 4.2 When it applies to LEGION: **not today**

The gate is `targetSdk >= 35`. LEGION is at 34 (§0a). **The cap is dormant on this build.**

Three caveats, all of which matter more than the reprieve:

1. **It is one `targetSdk` bump away from fatal.** `LedgerIngestService` (`dataSync` only) and
   `AriaForegroundService` (`connectedDevice|dataSync|microphone`) would both come under it, and
   neither implements `Service.onTimeout` - which means the failure mode is not degradation, it is
   `RemoteServiceException` and a crash. `traced` from the manifest and
   `AriaForegroundService.startForegroundCompat()`.
2. **The cap is a platform compat change and can be force-enabled.** **[PLATFORM]**
   https://developer.android.com/about/versions/15/behavior-changes-15 documents
   `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS <pkg>` and
   `adb shell device_config put activity_manager data_sync_fgs_timeout_duration <ms>`. Whether
   Samsung ships it enabled below `targetSdk 35` on One UI is **not established** - measurable, see
   §8.
3. **Multi-type services: not established.** No platform page states what happens when one service
   declares `dataSync` alongside untimed types. The nearest documented rules are
   **[PLATFORM]** https://developer.android.com/develop/background-work/services/fgs/service-types -
   "If you start a foreground service that includes the `shortService` type and another foreground
   service type, the system ignores the `shortService` type declaration. However, the service must
   still adhere to the prerequisites of the other declared types." That is about `shortService` and
   about *prerequisites*, not timeouts. **Do not assume `connectedDevice` shelters `dataSync`.**

### 4.3 `AriaForegroundService` does not need `dataSync` at all

`startForegroundCompat()` (`AriaForegroundService.kt:833-861`) starts with
`types = FOREGROUND_SERVICE_TYPE_DATA_SYNC` as its unconditional base and ORs in `connectedDevice`
and `microphone` when their runtime prerequisites hold. `traced`.

Against the platform definitions **[PLATFORM]**
https://developer.android.com/develop/background-work/services/fgs/service-types:

| Type | Purpose | Runtime prerequisite | Timed? |
|---|---|---|---|
| `connectedDevice` | "Interactions with external devices that require a Bluetooth, NFC, IR, USB, or network connection" | one of `CHANGE_NETWORK_STATE` / `CHANGE_WIFI_STATE` / `NFC` / ... **or** granted `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` / ... | **No** |
| `microphone` | "Continue microphone capture from the background" | granted `RECORD_AUDIO` | **No** |
| `dataSync` | "Data upload or download, backup-and-restore, import or export, fetch data, local file processing, transfer data between a device and the cloud" | **None** | **Yes, 6h** |

`dataSync` is used here as the always-available fallback precisely *because* it has no runtime
prerequisite. That is the entire reason it is in the manifest, and it is also the only type in the
list that carries a kill timer. **`dataSync` is the type LEGION wants least and relies on most.**

### 4.4 The alternatives, ranked

**[PLATFORM]** https://developer.android.com/about/versions/15/changes/datasync-migration and
https://developer.android.com/develop/background-work/services/fgs/changes

1. **Drop `dataSync` from `AriaForegroundService` entirely.** The wake-word/voice service is a
   `microphone` + `connectedDevice` service by nature; it is not transferring data. The work is
   making the prerequisites reliably true (hold `CHANGE_NETWORK_STATE` in the manifest so
   `connectedDevice` is satisfiable with no runtime grant at all) rather than falling back to a
   timed type. **This is the recommendation.**
2. **`shortService`** for the ingest path - but it is capped at ~3 minutes, and per the service-types
   doc it is ignored if combined with another type. Too small for a folder scan.
3. **User-initiated data transfer (UIDT) jobs** for `LedgerIngestService`. **[PLATFORM]**
   https://developer.android.com/develop/background-work/background-tasks/uidt - requires
   `RUN_USER_INITIATED_JOBS`, must be scheduled "while the application is visible to the user (or in
   one of the allowed conditions)", and crucially: **"Unlike regular jobs, user-initiated data
   transfer jobs are unaffected by App Standby Buckets quotas."** The FGS-changes page adds that
   these are "exempt from ordinary job quotas". Since ledger ingestion *is* user-initiated (Kevin
   opens the Ledger tab), this is a near-exact fit.
4. **Power allowlist.** Not a documented exemption from the FGS timeout - **do not assume it is
   one** - but it does grant exact alarms (§2.1) and FGS background-start (§1.6/2.3).

### 4.5 Adjacent restriction: starting the FGS again after it stops

Whatever kills the service, restarting it from the background is governed by
**[PLATFORM]** https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start.
Full exemption list, verbatim, with LEGION-availability marked:

| # | Exemption | Available to LEGION? |
|---|---|---|
| 1 | "Your app transitions from a user-visible state, such as an activity." | Yes |
| 2 | "Your app can start an activity from the background..." | Situational |
| 3 | "Your app receives a high priority message using Firebase Cloud Messaging." | **No - no FCM** |
| 4 | "The user performs an action on a UI element related to your app... bubble, notification, widget, or activity." | Yes |
| 5 | **"Your app invokes an exact alarm to complete an action that the user requests."** | **Yes, with the grant** |
| 6 | "Your app is the device's current input method." | No |
| 7 | **"Your app receives an event that's related to geofencing or activity recognition transition."** | **Yes** |
| 8 | `ACTION_BOOT_COMPLETED` / `ACTION_LOCKED_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED` | Yes |
| 9 | `ACTION_TIMEZONE_CHANGED` / `ACTION_TIME_CHANGED` / `ACTION_LOCALE_CHANGED` | Yes |
| 10 | `ACTION_TRANSACTION_DETECTED` from `NfcService` | No |
| 11 | System roles (device owner, profile owner) | No |
| 12 | Companion Device Manager + `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | **Plausible - the OBD dongle is a companion device** |
| 13 | **"The user turns off battery optimizations for your app."** | **Yes, if asked** |
| 14 | `SYSTEM_ALERT_WINDOW` (+ visible overlay if targeting 15+) | No |

**Rows 5, 7, 12 and 13 are LEGION's replacements for row 3.** Between an exact alarm, a geofence
transition, a companion-device attachment and the power allowlist, the app can re-enter foreground
state on every trigger class it actually has.

`BOOT_COMPLETED` caveat: apps targeting Android 15+ cannot launch `dataSync`, `camera`,
`mediaPlayback`, `phoneCall`, or `mediaProjection` FGS from a boot receiver, and **`microphone` has
been blocked since Android 14** - **[PLATFORM]** behavior-changes-15 and service-types
("you cannot launch a `microphone` foreground service from a `BOOT_COMPLETED` receiver, with a few
exceptions"). At `targetSdk 34` the `dataSync` block does not apply but the **`microphone` one
does**. `AriaForegroundService.startForegroundCompat()` already ORs `microphone` in only when
`RECORD_AUDIO` is granted, which is necessary but is not the same check - worth verifying on boot.

---

## 5. Geofencing

All **[PLATFORM]** https://developer.android.com/develop/sensors-and-location/location/geofencing
unless noted.

- **API:** `GeofencingClient` via `LocationServices.getGeofencingClient(context)` -
  `addGeofences(GeofencingRequest, PendingIntent)` / `removeGeofences(...)`. This is
  `com.google.android.gms.location`, i.e. **Google Play services**, not AOSP. A retail A25 has Play
  services; "no FCM" is a *product* decision about push, not an absence of Play services, so this
  path is open. The AOSP alternative (`LocationManager.addProximityAlert`) is long deprecated.
- **Limit:** "On single-user devices, there is a limit of **100 geofences per app**. For multi-user
  devices, the limit is 100 geofences per app per device user."
- **Permissions:** `ACCESS_FINE_LOCATION`, plus **`ACCESS_BACKGROUND_LOCATION` if targeting API 29+**.
  LEGION targets 34, so background location is required, and it is a two-step "Allow all the time"
  user flow.
- **Latency:** "Usually the latency is less than 2 minutes, even less when the device has been
  moving. If Background Location Limits are in effect, the latency is about **2-3 minutes on
  average**. If the device has been stationary for a significant period of time, the latency may
  increase (**up to 6 minutes**)." And: "On Android 8.0 and higher, if an app is running in the
  background while monitoring a geofence, then the device responds to geofencing events every couple
  of minutes."
- **Accuracy:** recommended radius **100-150 m minimum**. Location accuracy 20-50 m with Wi-Fi, ~5 m
  with indoor location, "several hundred meters to several kilometers" where Wi-Fi is unavailable.
- **Battery levers:** `setNotificationResponsiveness(int)` ("improves power consumption by increasing
  the latency"), larger radii for frequently-visited places, and `GEOFENCE_TRANSITION_DWELL` +
  `setLoiteringDelay(int)` instead of `ENTER` to stop drive-by alert storms.
- **Reboot:** geofences are **not** restored across a device reboot, app reinstall, cleared app data,
  cleared Play services data, or `GEOFENCE_NOT_AVAILABLE`. "The app should listen for the device's
  boot complete action, and then re-register the geofences required." They *are* restored across a
  Play services upgrade/restart or a location-process crash.
- **Does it survive Doze?** The page never says the word Doze. What it does establish is that
  geofence delivery continues in the background with a stated worst case of ~6 minutes when
  stationary - and §4.5 row 7 confirms a geofence transition is a *documented exemption* allowing an
  FGS to be started from the background, which the system would not offer for an event it suppresses
  in Doze. **Inferred, not stated: geofence transitions are delivered in Doze at degraded latency.
  Label this `reasoned`, and measure it (§8).**

**Read:** geofencing is the strongest primitive LEGION has for the Safety/Timing categories, because
it is the one trigger that both survives backgrounding *and* comes with an explicit FGS-start
exemption. Its costs are the background-location permission prompt and a 2-6 minute jitter that any
copy must be written to tolerate.

---

## 6. Notification channels and Do Not Disturb

### 6.1 Importance never pierces DND

**[PLATFORM]** https://developer.android.com/develop/ui/views/notifications/channels

| User-visible level | Constant | Behavior |
|---|---|---|
| Urgent | `IMPORTANCE_HIGH` | "Makes a sound and appears as a heads-up notification" |
| High | `IMPORTANCE_DEFAULT` | "Makes a sound" |
| Medium | `IMPORTANCE_LOW` | "Makes no sound" |
| Low | `IMPORTANCE_MIN` | "Makes no sound and doesn't appear in the status bar" |
| None | `IMPORTANCE_NONE` | "Makes no sound and doesn't appear in the status bar or shade" |

Importance governs *how loudly* a notification presents when it is allowed through. **It is not a
DND control.** Nothing on this page connects importance to DND.

Also: `setImportance` is "Only modifiable before the channel is submitted to
`createNotificationChannel`" - **[PLATFORM]**
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/NotificationChannel.java.
Channel settings are write-once from the app's side; after that they belong to the user.

### 6.2 The only real bypass: `setBypassDnd`, and it needs a user grant

**[PLATFORM]** AOSP `NotificationChannel.java`, javadoc for `setBypassDnd(boolean)`, verbatim:

> "Sets whether or not notifications posted to this channel can interrupt the user in
> `NotificationManager.INTERRUPTION_FILTER_PRIORITY` mode."
>
> "**Apps with Do Not Disturb policy access** (see `NotificationManager#isNotificationPolicyAccessGranted()`)
> **can set up their own channels this way, but only if the channel hasn't been updated by the user
> since its creation.**"
>
> "Otherwise, this value is only modifiable by the system and the notification ranker."

`canBypassDnd()`: "Whether or not notifications posted to this channel can bypass the Do Not Disturb
`INTERRUPTION_FILTER_PRIORITY` mode **when the active policy allows priority channels to bypass
notification filtering**."

**Answer to "can an app ever bypass DND without the user granting it": no.** Two independent user
gates, and note the second one - even with policy access, the *active DND policy* must itself permit
priority channels to bypass. The user can also flip "Override Do Not Disturb" on any channel from
Settings, which is the honest path and the one to design for.

### 6.3 What `CATEGORY_ALARM` / `CATEGORY_REMINDER` actually change

**[PLATFORM]** AOSP
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/ZenModeFiltering.java
- the DND filter classifies notifications by their category:

```java
isAlarm():    return record.isCategory(Notification.CATEGORY_ALARM)
                  || record.isAudioAttributesUsage(AudioAttributes.USAGE_ALARM);
isEvent():    return record.isCategory(Notification.CATEGORY_EVENT);
isReminder(): return record.isCategory(Notification.CATEGORY_REMINDER);
isCall():     return ... || record.isCategory(Notification.CATEGORY_CALL);
```

Those classifications are matched against the **user's** priority categories - **[PLATFORM]** AOSP
`NotificationManager.java`: `PRIORITY_CATEGORY_ALARMS` ("Alarms are prioritized"),
`PRIORITY_CATEGORY_REMINDERS` ("Reminder notifications are prioritized"), `PRIORITY_CATEGORY_EVENTS`
("Event notifications are prioritized").

Filter modes, verbatim from the same file:

- `INTERRUPTION_FILTER_PRIORITY` - "all notifications are suppressed except those matching priority
  criteria."
- `INTERRUPTION_FILTER_ALARMS` - "**all notifications except those of category `CATEGORY_ALARM` are
  suppressed**."
- `INTERRUPTION_FILTER_NONE` - "all notifications are suppressed and all audio streams and vibrations
  are muted."

**So the category is a claim, not a key.** Tagging a nudge `CATEGORY_REMINDER` makes it eligible to
pass DND *if and only if* the user has "Reminders" enabled in their DND priority settings.
`CATEGORY_ALARM` is the strongest of the three because it is the sole survivor of
`INTERRUPTION_FILTER_ALARMS` - and using it for a proactive nudge that is not an alarm the user set
is exactly the kind of thing CLAUDE.md §7 bans.

The truthful design: **category by honest classification, `bypassDnd` only if the user grants it,
and no attempt to be louder than the user asked for.**

### 6.4 Android 15+ closed the back door

**[PLATFORM]** https://developer.android.com/about/versions/15/behavior-changes-15:

> "Apps that target Android 15 (API level 35) and higher can no longer change the global state or
> policy of Do Not Disturb (DND) on a device (either by modifying user settings, or turning off DND
> mode). Instead, apps must contribute an `AutomaticZenRule`, which the system combines into a global
> policy with the existing most-restrictive-policy-wins scheme. Calls to existing APIs that
> previously affected global state (`setInterruptionFilter`, `setNotificationPolicy`) result in the
> creation or update of an implicit `AutomaticZenRule`."

At `targetSdk 34` LEGION could still call `setInterruptionFilter` with policy access. **It should
not.** The rule is one bump from mandatory and `AutomaticZenRule` is the right shape anyway - it
makes LEGION's quiet hours a *mode the user can see and turn off*, which is the compulsion-free
posture CLAUDE.md §7 requires.

---

## 7. Samsung / One UI specifics

### 7.1 Samsung's own documentation - [VENDOR]

https://developer.samsung.com/mobile/app-management.html

- **Sleeping:** "Background applications that have not been used for about **3 days** and causing
  poor system health will go into the sleeping mode."
- **Deep sleeping:** apps unused for "a long period of time (**currently set to 16 days**, but
  subject to change according to Samsung policies)". "Deep sleeping applications only become active
  when the user opens them [...] **Inactive applications can't perform any activities, including
  notifications or updates**."
- **Mechanism:** "**A bucket restriction applies to any sleeping applications, and features such as
  Job, Alarm, and Foreground-service are restricted.**" Samsung's own page points at Android's
  **restricted bucket** documentation for what that means - i.e. §1.3's bottom row: one job per day
  in a 10-minute batch, **one alarm per day**, **no network**.
- **User escape hatch:** Settings > Device care > Battery > Background usage limits, where apps can
  be marked "never sleeping".
- **Developer deep link:** `com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY` opens those
  screens programmatically.
- **The commitment:** "Foreground services of apps targeting Android 14 will be guaranteed to work as
  intended so long as they are developed according to Android's new foreground service API policy."

**This is the most important finding in §7 and it cuts both ways.** Samsung documents its own kill
switch in its own words, *and* documents an FGS guarantee that LEGION (targeting 34, typed FGS,
correct permissions) qualifies for on its face.

### 7.2 The community picture - [COMMUNITY]

https://dontkillmyapp.com/samsung - the long-standing aggregator of Samsung background-kill reports.

- Documents aggressive restrictions across Marshmallow through Android 14; "Put unused apps to sleep"
  puts apps unused ~3 days into restricted mode (agrees with Samsung's own 3-day figure).
- Reports missed **alarm clock** alarms after a weekend of non-use - the mechanism being sleeping
  apps landing in the restricted bucket's one-alarm-per-day.
- Records that in **July 2024 Samsung "officially promised to drop the non-standard optimizations"**
  for Android 14+, quoting Samsung: "our collaboration with Google has resulted in a unified policy
  that we expect will create a more consistent and reliable user experience for Galaxy users."
- Recommends users disable Adaptive Battery, remove the app from sleeping lists, and set Battery
  optimization to "Don't optimize".

Corroborating **[COMMUNITY]** coverage of the same commitment:
https://9to5google.com/2023/05/05/samsung-background-android-14/ and
https://www.sammobile.com/news/samsung-android-14-one-ui-6-0-update-kill-background-apps-less-frequently/

**[COMMUNITY]** claims that do **not** have a primary source and should not be repeated as fact: the
frequently-cited "30-50% of push notifications are delayed or lost on Samsung" figure appears only in
blog aggregations with no methodology. **Not established.**

**No One UI 7 or One UI 8 (Android 16) specific developer documentation was found** beyond the
Android 14 / One UI 6 page above. Samsung's app-management page has not been updated for it.
**Current One UI 8 behaviour on this device: not established from any source, primary or otherwise.**

### 7.3 What this means for LEGION concretely

The failure mode is **not** "Samsung kills the foreground service". It is: *Kevin does not open
LEGION's UI for three days, One UI marks it sleeping, the OS drops it to the restricted bucket, and
the proactive engine gets one alarm per day and no network* - while the foreground service is
possibly still running and everything looks fine.

A voice assistant is precisely the app that **gets used constantly without its UI ever being
opened**. Talking to it is not the same signal as launching it. Whether One UI's usage analysis
counts wake-word sessions as "use" is **not established and cannot be** without measuring (§8).

Three mitigations, in order:

1. **Ask for the battery-optimization allowlist** (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
   One prompt buys: exact alarms without the separate grant (§2.1), FGS background-start (§4.5 #13),
   partial Doze exemption for network and wake locks (§1.2). It is the highest-value single prompt in
   this entire document.
2. **Onboarding must tell the user to mark LEGION "never sleeping"** in Settings > Device care >
   Battery > Background usage limits. There is no API for this; it is a user action, and the Samsung
   deep-link intent above can take them there.
3. **Instrument it.** Log the standby bucket at every service start so a degradation is visible in
   logcat rather than inferred from silence a week later.

---

## 8. What cannot be settled without measuring on the A25

Every item here is a real unknown, not a hedge. Commands assume the full adb path from
`memory/wireless-adb-available.md` and `MSYS_NO_PATHCONV=1` or the PowerShell tool.

| # | Question | How to settle it |
|---|---|---|
| 1 | Which standby bucket LEGION actually sits in, day to day | `adb shell am get-standby-bucket com.kevin.legion` - sample daily; 10=active, 20=working set, 30=frequent, 40=rare, 45=restricted |
| 2 | Whether One UI auto-sleeps LEGION when only voice is used and the UI is never opened | Do not open the UI for 4+ days; check bucket daily and check Settings > Battery > Background usage limits > Sleeping apps |
| 3 | Whether Samsung enables `FGS_INTRODUCE_TIME_LIMITS` below `targetSdk 35` | `adb shell am compat` state for the package; or run a `dataSync` FGS 7h and see whether `onTimeout`/`RemoteServiceException` appears in logcat |
| 4 | Whether the `dataSync` timer applies to a **multi-type** service (`connectedDevice\|dataSync\|microphone`) | Same soak, on `AriaForegroundService` specifically, after (3) is force-enabled |
| 5 | Real while-idle alarm rate limit: once/9 min vs 7/hour (conflicting docs, §1.2) | Force Doze (`adb shell dumpsys deviceidle force-idle`), arm 12 `setExactAndAllowWhileIdle` alarms 3 min apart, log actual fire times |
| 6 | Whether geofence transitions are delivered in Doze, and at what latency on this device | Register a geofence, force-idle, cross the boundary, log delta |
| 7 | Maintenance-window cadence after 1h / 6h / 12h of idle | `adb shell dumpsys deviceidle` state transitions over a long soak |
| 8 | Whether `AriaForegroundService` survives an overnight screen-off unplugged run at all | 12h soak, logcat for service death; this is the baseline every other measurement rests on |
| 9 | `PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS` numeric value | Read the constant out of the WorkManager AAR; docs do not state it |
| 10 | Whether the user-facing DND policy on this device permits priority channels to bypass (§6.2's second gate) | `adb shell dumpsys notification --noredact` / `settings get global zen_mode` while DND is on |

Item 8 is the gate on all the others. Run it first.

---

## 9. Bottom line

**What actually survives, for a sideloaded app on a Samsung device with no push:**

| Guarantee | Survives? | Why |
|---|---|---|
| `setAlarmClock` fires at the stated wall-clock time | **Yes** | System leaves low-power modes to deliver; never adjusted. Costs a visible alarm icon. |
| `setExactAndAllowWhileIdle` fires within seconds, in Doze | **Yes, with the grant** | Needs `SCHEDULE_EXACT_ALARM` (denied by default at target 34; user-grantable) **or** the power allowlist. Rate-limited to roughly one per 9 min. |
| `setAndAllowWhileIdle` fires in Doze, no permission at all | **Yes** | Inexact. Same rate limit. This is the free, always-available primitive. |
| Foreground service keeps network through Doze | **Yes** | AOSP proc-state threshold, §1.4. `traced` from source. |
| Foreground service keeps the app out of App Standby | **Yes on AOSP, uncertain on One UI** | Doc says a foreground process prevents idle; Samsung's sleeping-apps layer is a separate mechanism. |
| Foreground service exempts jobs from quota | **No, not since Android 16** | All-apps change, applies at target 34. §1.5. |
| `dataSync` FGS runs indefinitely | **Yes today, no after a `targetSdk` bump** | Gated on target 15+; LEGION is at 34. §4.2. |
| WorkManager periodic runs on its period | **No** | 15 min floor, no ceiling. Doze defers to maintenance windows; bucket quota can make 15 min arithmetically impossible. |
| Geofence transition delivered while backgrounded | **Yes, 2-6 min jitter** | And it is a documented FGS-start exemption. Needs `ACCESS_BACKGROUND_LOCATION` and re-registration after every reboot. |
| Notification pierces DND | **Only if the user says so** | Two user gates. No app-only path exists, at any importance, with any category. |
| Anything at all, if One UI marks the app sleeping | **No** | Restricted bucket: one alarm per day, no network. Samsung's own documentation, §7.1. |

**Which primitive for which trigger class:**

| Trigger class | Primitive | Notes |
|---|---|---|
| "At 7:00, say this" - a time the user set | `setExactAndAllowWhileIdle` via the existing `AlarmScheduler`, with the grant; `setAndAllowWhileIdle` when refused, **said in words** | Already built and already correct. |
| "Roughly this evening" - a soft, proactive nudge | `setAndAllowWhileIdle` | No grant, no rate-limit pressure at that cadence, no permission prompt to justify. |
| "When he gets home / leaves work" | Geofence with `DWELL` + loitering delay, 100-150 m radius | The one trigger that both survives backgrounding and can start an FGS. Re-register on `BOOT_COMPLETED`. |
| "When the car connects" | Companion Device Manager / Bluetooth connection + `connectedDevice` FGS | Exemption #12 from background-start restrictions. |
| "Scan the ledger folder eventually" | `PeriodicWorkRequest`, 15 min floor, no promises; **migrate to a UIDT job** | UIDT is explicitly exempt from bucket quotas and is a genuine fit - Kevin opens the tab. |
| "Long ingest that must finish" | UIDT job, **not** a `dataSync` FGS | Removes the six-hour cliff before it exists. |
| "Speak during quiet hours" | Honest category + user-granted channel bypass; contribute an `AutomaticZenRule` for LEGION's own quiet mode | Never `CATEGORY_ALARM` for something that is not an alarm. |

**The three actions that matter most, in order:**

1. **Remove `dataSync` from `AriaForegroundService`.** It is the fallback type precisely because it
   has no runtime prerequisite, and it is the only type in that service with a kill timer. Declare
   `CHANGE_NETWORK_STATE` so `connectedDevice` is satisfiable without any runtime grant, and the
   fallback stops being a timed type.
2. **Ask for `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` at onboarding.** One prompt, three
   payoffs: exact alarms, FGS background-start, partial Doze exemption.
3. **Tell the user, in onboarding, to mark LEGION "never sleeping."** Samsung documents its own
   sleeping-apps mechanism and documents no API to opt out of it. A voice assistant used daily
   without its UI ever being opened is the exact profile that gets put to sleep.

**And the thing to stop believing:** the foreground service is not a licence. Since Android 16 it no
longer exempts jobs from quota, it never exempted alarms from the while-idle rate limit, and on One
UI it does not protect the app from being classified as unused. **The service keeps the process
alive and keeps network in Doze. It does not keep the app scheduled.**

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| `targetSdk = 34`, manifest FGS types, `startForegroundCompat` type assembly, `AlarmScheduler` behaviour | `traced` (read from repo files) |
| Every quoted platform limit, exemption list, permission rule, and API behaviour | `traced` (quoted from the cited URL) |
| FGS keeps network through Doze (proc-state threshold argument) | `traced` from AOSP source; **not measured on device** |
| Geofence transitions are delivered during Doze | `reasoned` from the FGS-start exemption; **not stated by any primary source** |
| `MIN_PERIODIC_FLEX_MILLIS` = 5 min | **not established** - commonly cited, no primary source read |
| Multi-type FGS timeout behaviour | **not established** - no platform page addresses it |
| One UI 7/8 behaviour on Android 16 | **not established** - Samsung's developer page stops at One UI 6 |
| "30-50% of notifications lost on Samsung" | **not established** - [COMMUNITY] blog claim, no methodology |
| Everything in §8 | **unmeasured by construction** - that is what the table is for |
