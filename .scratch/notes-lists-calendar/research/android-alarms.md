# Research: the Android alarm mechanism, and what it costs

Ticket: `.scratch/notes-lists-calendar/issues/03-android-alarm-mechanism.md`
Map: `.scratch/notes-lists-calendar/map.md` (charting decision 5: alarms are local)
Researched: 2026-08-07. Sources are `developer.android.com` unless noted.

**Verification tags used below:** `doc` = quoted from a primary Android/Google page, URL given.
`unverified` = could not be confirmed against a primary source in this pass. Nothing here is
`built`, `tested` or `on-device` - no code was written and nothing was run on a phone.

---

## Local facts

Read from the repo, not assumed.

### `app/build.gradle.kts`

| Field | Value |
|---|---|
| `compileSdk` | **36** (Android 16) |
| `minSdk` | **24** (Android 7.0) |
| `targetSdk` | **34** (Android 14) |
| `applicationId` | `com.kevin.legion` |
| core library desugaring | enabled (`java.time` back to API 24) |

**`targetSdk = 34` is the number that decides most of this document.** Every "apps targeting
Android 13 or higher" behaviour change below applies to LEGION today.

### Permissions declared in `app/src/main/AndroidManifest.xml`

```
BLUETOOTH (maxSdkVersion 30)      BLUETOOTH_SCAN            BLUETOOTH_CONNECT
BLUETOOTH_ADMIN (maxSdkVersion 30) ACCESS_FINE_LOCATION     ACCESS_COARSE_LOCATION
RECORD_AUDIO                      POST_NOTIFICATIONS        READ_PHONE_STATE
FOREGROUND_SERVICE                FOREGROUND_SERVICE_MICROPHONE
FOREGROUND_SERVICE_CONNECTED_DEVICE                         FOREGROUND_SERVICE_DATA_SYNC
INTERNET                          ACCESS_NETWORK_STATE      ACCESS_WIFI_STATE
```

**Not declared:** `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`.

### Correction to the ticket's premise

The ticket says LEGION "has never scheduled an alarm or posted a user-facing notification."
Half right, and the half that is wrong is the cheap half.

- **Alarms: correct.** Zero `AlarmManager` references anywhere in `app/src/main/java`. No
  precedent at all. (`traced` - grep across the whole main source set.)
- **Notifications: not correct.** Two notification channels already exist and are created at
  service start:
  - `service/AriaForegroundService.kt:841` - `createNotificationChannel()`, `IMPORTANCE_LOW`
  - `service/LedgerIngestService.kt:148` - `createNotificationChannel()`, `IMPORTANCE_LOW`
  Both are foreground-service notifications, not user-facing alerts, and both are `IMPORTANCE_LOW`
  (no sound, no heads-up). A reminder needs a **new, separate channel** at `IMPORTANCE_HIGH`;
  neither existing channel can be reused, because channel importance is fixed at creation and the
  user owns it thereafter.
- **`POST_NOTIFICATIONS` is already declared and already requested at runtime**, in
  `ui/SettingsScreen.kt:91-111`, as step 1 of the assistant-ignition permission chain
  (`POST_NOTIFICATIONS`, then `RECORD_AUDIO`). So the runtime plumbing exists; what does not exist
  is any handling for the case where it is *denied*, from the reminder domain's point of view.
- **There is no `BootReceiver`.** There used to be; it was deleted in ticket 07 of the
  `legion-shape` effort (see the comment at `AndroidManifest.xml:102-106`) because it did
  car-launcher `startActivity` on boot. A reminder domain has to bring one back, for a
  structurally different reason. Do not resurrect the old behaviour along with the file.

---

## Findings

### 1. Exact vs inexact alarms, and what changed in 12/13/14/15

**The two classes.** `doc`
([Schedule alarms](https://developer.android.com/develop/background-work/services/alarms/schedule))

- **Inexact** - "the system delivers the alarm at some point in the future," respecting Doze and
  battery-saving restrictions. The docs call this the default choice for most apps.
- **Exact** - "the system invokes the alarm at a precise moment." The docs say to use these
  "sparingly," only for "core functionality" that requires precise timing, naming alarm clocks and
  calendar apps as the examples.

**Per-API guarantees.** `doc` (same page)

| API | Guarantee | Permission needed on API 31+ |
|---|---|---|
| `set()` | Fires within **one hour** of trigger time on Android 12+, absent battery restrictions. Never fires early. | none |
| `setWindow(t, windowLengthMillis, …)` | Fires inside the window. **If the app targets Android 12+, values under `600000` (10 min) are typically clipped to `600000`.** | none |
| `setInexactRepeating()` | First fire within the window; subsequent intervals vary. Android batches these across apps to save battery. Interval must be one of the `INTERVAL_*` constants (`INTERVAL_FIFTEEN_MINUTES`, `INTERVAL_DAY`, …) - no custom interval. | none |
| `setAndAllowWhileIdle()` | Inexact, but permitted to fire during Doze. | none |
| `setExact()` | "nearly precise time," still subject to battery-saving measures. | yes |
| `setExactAndAllowWhileIdle()` | "nearly precise time," bypasses battery-saving restrictions / fires during Doze. | yes |
| `setAlarmClock()` | Precise. "the system never adjusts their delivery time… leaves low-power modes if necessary to deliver the alarms." Shown to the user as a pending alarm in the status bar. | yes |
| `setRepeating()` | **Inexact since API 19.** `doc` - "all repeating alarms are inexact on Android 4.4+ (API level 19+)". Its only advantage over `setInexactRepeating()` is a custom interval. | n/a (it is inexact) |

**Version timeline.**

- **Android 12 (S, API 31).** `SCHEDULE_EXACT_ALARM` is introduced; apps targeting 12+ that call
  the exact-alarm APIs must declare it or get a `SecurityException`. Users grant it under
  Settings > Apps > Special app access > Alarms & reminders. `doc`
  ([Android 12 behavior changes](https://developer.android.com/about/versions/12/behavior-changes-12)).
  Also in 12: `setWindow` clipping to a 10-minute floor, and the one-hour `set()` window, both from
  the Schedule alarms page. Also in 12: **notification trampolines are banned** - a `BroadcastReceiver`
  may not `startActivity()` in response to a notification tap; use a `PendingIntent` on
  `setContentIntent()`. That is directly relevant: tapping a reminder must open the item.
- **Android 13 (T, API 33).** `USE_EXACT_ALARM` is introduced, install-granted, non-revocable, but
  only for apps that are "an alarm clock app or a timer app" or "a calendar app that shows
  notifications for upcoming events." `doc`
  ([Android 13 features](https://developer.android.com/about/versions/13/features)).
  Also in 13: `POST_NOTIFICATIONS` becomes a runtime permission (see §6).
- **Android 14 (U, API 34).** `SCHEDULE_EXACT_ALARM` "is no longer pre-granted to most newly
  installed apps targeting Android 13 and higher - the permission is denied by default." Survives
  as granted only if the app already held it before the OS upgrade; a backup-and-restore transfer
  onto Android 14 keeps it **denied**. `doc`
  ([Schedule exact alarms are denied by default](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)).
  **This is the change that bites LEGION**: `targetSdk = 34`, so a fresh sideload onto any Android
  14+ phone starts with the permission denied.
- **Android 15 (V, API 35).** **No AlarmManager or exact-alarm change found.** `doc` for the
  absence: the [Android 15 behavior changes: all apps](https://developer.android.com/about/versions/15/behavior-changes-all)
  page lists notification OTP redaction, notification hiding during screen share, background
  network-access restrictions, and widgets/pending-intents being cancelled on force-stop - nothing
  about alarms. Absence of evidence on one page is not proof; treat "Android 15 changed nothing for
  alarms" as `reasoned`, not `doc`.
- **Android 16 (API 36).** `compileSdk` is 36 but `targetSdk` is 34, so Android 16's
  targetSdk-gated changes do not apply to LEGION until the target is raised. Android 16 did change
  job execution quotas by app state `doc` (App standby buckets page), which affects WorkManager, not
  AlarmManager. Not researched further - out of the ticket's scope.

**Conflicting statements, flagged rather than resolved.** The Schedule alarms page says
`SCHEDULE_EXACT_ALARM` is "not pre-granted to fresh installs on Android 13+", while the Android 14
change page frames denial-by-default as an **Android 14 device** behaviour applied to apps
**targeting** 13+. These are not the same claim. For LEGION the distinction is moot - it targets 34
and will run on 14+ phones, so it is denied by default either way - but do not quote the looser
version as fact.

### 2. `SCHEDULE_EXACT_ALARM` vs `USE_EXACT_ALARM`

| | `SCHEDULE_EXACT_ALARM` | `USE_EXACT_ALARM` |
|---|---|---|
| Introduced | Android 12 (API 31) | Android 13 (API 33) |
| Protection | User-granted, special app access | Normal, granted at install |
| Revocable | Yes, by the user **and by the system** | No, only by uninstalling |
| Eligibility | Any app with a genuine need | Alarm/timer apps, or calendar apps showing event notifications |
| Play gate | none | **Restricted permission, requires a Play Console declaration and review** |

`doc` for the table: [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms),
[Android 13 features](https://developer.android.com/about/versions/13/features).

**Grant flow for `SCHEDULE_EXACT_ALARM`.** It is *not* a runtime dialog. There is no
`requestPermissions()` for it. `doc`:

1. Declare `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>`.
2. Call `alarmManager.canScheduleExactAlarms()` before every exact-alarm call.
3. If false, explain in your own UI why, then
   `startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))`, which drops the user on
   the system "Alarms & reminders" screen with a single toggle: *Allow setting alarms and reminders*.
4. Register a receiver for `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`,
   re-check `canScheduleExactAlarms()`, and reschedule - "logic should be similar to
   `ACTION_BOOT_COMPLETED` handling."

**What revocation does.** `doc`, quoted: *"When the `SCHEDULE_EXACT_ALARM` permission is revoked
for your app, your app stops, and all future exact alarms are canceled."* Two consequences, both
severe: the process is killed, and **every pending exact alarm is silently gone**. The
state-changed broadcast is the only chance to notice and re-arm.

**The Play policy, and whether it binds a sideloaded app.** The policy text is a *publishing*
gate, not an OS gate. `doc`
([Play Console: permissions and APIs that access sensitive information](https://support.google.com/googleplay/android-developer/answer/16558241)),
quoted: *"Apps that request this restricted permission are subject to review, and those that do not
meet the acceptable use case criteria will be disallowed from publishing on Google Play."*

Reading that against LEGION:

- The enforcement mechanism named is **review at publish time on Google Play**. LEGION is
  sideloaded and will never be submitted. Nothing in the policy text describes an OS-side check.
- LEGION also arguably *qualifies* on the merits: the map's destination is a calendar with event
  notifications, which is one of the two named acceptable cases.
- **Nonetheless the recommendation below does not use `USE_EXACT_ALARM`.** Not because of the
  policy, but because §3 makes exact alarms unnecessary in the common case and
  `SCHEDULE_EXACT_ALARM` covers the uncommon one with a flow the user can see and control.
  Claiming an install-granted, non-revocable permission for a two-user app is a bigger hammer than
  the problem needs. If that judgement is ever revisited, note that the *technical* difference is
  entirely about revocability, not capability - the docs say holders of either "schedule exact
  alarms" identically.
- **`unverified`:** whether Play Protect, the sideload installer, or `pm install` performs any
  client-side check on a restricted permission at install time. I found no primary source saying it
  does and none saying it does not. If `USE_EXACT_ALARM` is ever chosen, this must be tested on the
  actual phone rather than assumed.

**One escape hatch worth knowing.** `doc`: if the exact alarm is set with an
`AlarmManager.OnAlarmListener` rather than a `PendingIntent`, **no permission is required**. This
is useless for reminders - a listener only lives as long as the process, and the whole point of a
reminder is that it fires when the app is not running - but it is why some sample code appears to
set exact alarms without the permission.

### 3. Does a personal reminder actually need an exact alarm?

**This is the ticket's highest-value question and the documented answer is: mostly no.**

The load-bearing fact, `doc`, quoted from
[Schedule alarms](https://developer.android.com/develop/background-work/services/alarms/schedule):

> *"On Android 12 (API level 31) and higher, the system invokes the alarm within one hour of the
> supplied trigger time, unless any battery-saving restrictions are in effect."*

So a plain `set()` gives: never early, at most one hour late, **and that one hour is the
unrestricted case - Doze and battery saver can push it further** (§4). `setWindow()` narrows it to
a 10-minute floor for a targetSdk-31+ app, again absent battery restrictions.

The honest tradeoff, stated as fact rather than guess:

| Reminder shape | Inexact acceptable? |
|---|---|
| "Buy milk" on a shopping list, due today | Yes, trivially. An hour of slop is invisible. |
| "Camping trip Saturday", a day-level nudge | Yes. |
| "Leave for the dentist at 14:20" | **No.** An hour late is a missed appointment. |
| "Wake me at 06:30" | **No**, and this is `setAlarmClock()` territory, not `setExact()`. |
| Morning reminder after the phone idled overnight | **Inexact is actively unsafe.** See §4 - this is the case the ticket correctly singles out. |

The map's decision 4 says an item carries at most one optional trigger and "most items have
neither." That shape argues strongly for inexact-by-default. But the same map's destination
includes "timed events" and a calendar, and a calendar event that fires an hour after the meeting
started is not a calendar. **The permission problem does not fully disappear; it shrinks to the
subset of items the user explicitly marks as time-critical.** That is a much better place to be
than requiring it up front, and it is the shape the recommendation takes.

### 4. Doze and app standby buckets - the overnight case

**Doze.** `doc` ([Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)).
The device enters Doze when unplugged, stationary and screen-off for a period. While in Doze the
system:

- suspends network access
- ignores wake locks
- **defers standard alarms**, explicitly including `setExact()` and `setWindow()`, until the next
  maintenance window
- runs no Wi-Fi scans, no sync adapters, **and no `JobScheduler`** - which means **no WorkManager**

Maintenance windows are brief periods where pending alarms and jobs all run. Critically, `doc`:
*"Over time, maintenance windows are scheduled less frequently during extended inactivity periods."*
An overnight idle is precisely the "extended inactivity" case, so the gap before the next
maintenance window is at its widest exactly when the morning reminder is due. The docs do not
publish the interval schedule; **`unverified`: how long the gap actually gets after 8 hours idle.**

**What survives Doze.** `doc`, three things:

1. `setAndAllowWhileIdle()` - inexact, but delivered during Doze.
2. `setExactAndAllowWhileIdle()` - exact, delivered during Doze. Needs the permission.
3. `setAlarmClock()` - *"continue to fire normally. The system automatically exits Doze shortly
   before these alarms fire."* Needs the permission.

**The nine-minute floor.** `doc`, quoted: the `*AndAllowWhileIdle` methods *"cannot fire alarms
more than once per nine minutes, per app."* This is a per-app rate limit, not per-alarm. It is
harmless for reminders (nobody sets two reminders nine minutes apart and cares) but it means an
allow-while-idle alarm is **not** a general-purpose ticker.

**App Standby buckets.** `doc`
([App Standby Buckets / power details](https://developer.android.com/topic/performance/power/power-details)):

| Bucket | Alarm limit |
|---|---|
| Active | no execution limits |
| Working set | 10 per hour |
| Frequent | 2 per hour |
| Rare | 1 per hour |
| Restricted | **one alarm per day, exact or inexact** |

These are *rate* limits, not deferrals, and at normal reminder volumes only **Restricted** is
dangerous. A phone the user actually uses daily should not put LEGION in Restricted - `doc` says
apps are released from standby immediately when plugged in - but this is a real failure mode worth
one line in the UI if a reminder is ever observed not to fire.

**The genuinely fatal case, and it is not Doze.** `doc`
([Background optimizations](https://developer.android.com/topic/performance/background-optimization)),
quoted: for apps the user has put in the **"restricted" battery state**, on Android 9+,
*"Alarms aren't triggered"* and *"Jobs aren't executed."* Full stop. That is a user-chosen setting
(Battery usage > Restricted), not an automatic bucket, and no API can work around it. A reminder
app must not pretend this cannot happen.

**Conclusion for the overnight morning reminder.** A plain `set()` scheduled the night before is
**not reliable** for a 06:30 alert - it is a standard alarm and Doze defers standard alarms to a
maintenance window whose frequency has been decaying all night. The cheapest fix that keeps the
permission problem away is `setAndAllowWhileIdle()`: it is inexact (no permission) but Doze-exempt.
Its residual slop is the one-hour inexact window; **`unverified`: whether the one-hour guarantee
from the Schedule alarms page is stated to hold for the allow-while-idle variant specifically -
the page groups it under inexact but does not restate the bound for it.** Do not claim a one-hour
ceiling for `setAndAllowWhileIdle` without testing it.

### 5. Surviving reboot

`doc` ([Schedule alarms](https://developer.android.com/develop/background-work/services/alarms)),
quoted: *"Alarms are canceled when the device shuts down."* All of them. There is no persistence.

The prescribed pattern, verbatim from the docs:

1. Declare `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>`.
2. A `BroadcastReceiver` filtering `android.intent.action.BOOT_COMPLETED`, which re-reads the store
   and re-arms every future alarm.
3. Register it in the manifest **with `android:enabled="false"`**, so it costs nothing at boot for
   users who have set no reminders.
4. Flip it on with `PackageManager.setComponentEnabledSetting(..., COMPONENT_ENABLED_STATE_ENABLED,
   DONT_KILL_APP)` when the first alarm is set, and back off when the last is cancelled. `doc`:
   *"Programmatically enabling the receiver overrides the manifest setting and persists across
   reboots."*

Two conditions the docs attach:

- `doc`: the broadcast is only delivered if *"the app was launched by the user at least once."*
  Fresh sideload, never opened, no boot broadcast. Fine here, but it means alarms cannot be
  pre-seeded before first launch.
- The same rescheduling routine must be reachable from the
  `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` receiver (§2), because permission
  revocation also wipes pending exact alarms. One `rescheduleAll()` function, three callers: boot,
  permission-state-change, and app start.

**`unverified`, and it matters:** whether pending alarms survive an **app update** (sideloading a
new APK over the old one) or a **force-stop**. I could not find a primary source stating either
way for `AlarmManager` specifically. The Android 15 page does say all *pending intents* are
cancelled when an app is force-stopped, and alarms are held as `PendingIntent`s, which suggests
force-stop kills them - but that page is discussing widgets, so do not treat the inference as
verified. **Practical mitigation regardless: re-arm everything on app start, unconditionally.**
It is idempotent (same request code + `FLAG_UPDATE_CURRENT` replaces rather than duplicates) and it
makes the question moot. This is the cheapest insurance on the whole list.

### 6. `POST_NOTIFICATIONS`

`doc` ([Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)).

- Runtime permission from **Android 13 (API 33)**. Required for essentially all non-exempt
  notifications, including foreground-service notifications.
- LEGION targets 34, so **it controls when the dialog appears** - the system does not force it at
  first launch. That is the good path, and the app already uses it (`ui/SettingsScreen.kt`).
- On a fresh install on Android 13+, **notifications are off until granted**.
- Exemptions that still post when denied: media-session notifications, and `CallStyle` for apps
  with `ConnectionService`. Neither helps a reminder. FGS notifications appear in Task Manager but
  **not the notification drawer** when denied - which is why LEGION's ignition flow already asks.
- Recommended checks: `NotificationManagerCompat.areNotificationsEnabled()` before posting, and
  `shouldShowRequestPermissionRationale()` to decide whether to show a rationale.

**The important gap for this domain.** `areNotificationsEnabled()` is a whole-app answer.
A reminder can also fail because the user disabled *that one channel* while leaving the app's
notifications on - the docs describe per-channel user control but the app-level API will still
return true. **`unverified`: whether `NotificationManager.getNotificationChannel(id).importance ==
IMPORTANCE_NONE` is the documented way to detect a blocked channel.** It is the commonly used check,
but I did not confirm it against a primary page. Whatever the mechanism, the design rule stands:
**a reminder that cannot notify must be visible as a broken reminder inside the app**, not silently
armed. The ticket names this exact risk and it is the right one to name.

### 7. Recurring alarms - `setRepeating` or re-arm on fire?

**Re-arm on fire. The docs do not leave this ambiguous, though they arrive at it from two
directions.**

- `setRepeating()` **is inexact anyway** on API 19+, so it buys no precision. `doc`. Its only
  remaining feature over `setInexactRepeating()` is a custom interval, and the docs recommend
  against using it for that: *"Use `setInexactRepeating()` instead of `setRepeating()`."*
- `setInexactRepeating()` can only take the `INTERVAL_*` constants. There is no
  `INTERVAL_EVERY_TUESDAY`. Anything the recurrence ticket is likely to want - weekdays, monthly,
  "first Sunday" - **is not expressible**.
- There is no repeating variant of any exact API at all. No `setExactRepeating`, no
  `setRepeatingAndAllowWhileIdle`. If a recurring reminder must be exact, one-at-a-time is the only
  option that exists.
- Wall-clock recurrence and `RTC` interval arithmetic disagree across DST boundaries and month
  lengths. A fixed-millisecond interval cannot express "09:00 local, every day." Re-arming lets
  each occurrence be computed from a calendar rather than added to a timestamp. (`reasoned`, not
  from a doc - but it follows from `setInexactRepeating` taking a millisecond interval.)

**So: store the rule, compute the next occurrence, arm exactly one alarm, and re-arm the next when
it fires.** Feed this to ticket 04. It also composes cleanly with §5 - `rescheduleAll()` on boot
just recomputes next-occurrence from the stored rules, and never needs a persisted alarm table.

The cost to be aware of: if the phone is off when an occurrence is due, the re-arm never happens,
and the chain stops. Boot recovery must recompute forward from *now*, and decide explicitly what to
do with occurrences that were missed while the phone was off. The map lists that as not-yet-specified
("what happens if the phone was off when it was due"); this finding says it is **not optional** for a
re-arming design, because getting it wrong stops all future occurrences rather than just losing one.

### 8. WorkManager as an alternative

**Not appropriate for time-of-day reminders. The boundary is sharp and documented.**

- `doc` ([Schedule alarms](https://developer.android.com/develop/background-work/services/alarms)):
  use WorkManager for "scheduled background work (e.g. updating your app, uploading logs)"; use
  alarms for "time-based operations outside your app's lifetime" that "should fire even when the
  device is asleep."
- `doc` ([Define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)),
  quoted: *"The minimum repeat interval that can be defined is 15 minutes."* And: *"The interval
  period is defined as the minimum time between repetitions. The exact time that the worker is
  going to be executed depends on the constraints… and on the optimizations performed by the
  system."* No upper bound on lateness is given at all.
- **The decisive one:** `doc` (Doze and App Standby) - Doze *"doesn't let `JobScheduler` run
  (affects WorkManager tasks)."* WorkManager is built on JobScheduler. **WorkManager is strictly
  worse than an inexact alarm in Doze**, because at least `setAndAllowWhileIdle()` has a documented
  Doze exemption and WorkManager has none.
- The Android 14 migration table itself lists WorkManager only under "background work updates and
  logging," and routes "user action after a specific time" to `set()` and "user action within a time
  window" to `setWindow()`. `doc`.

WorkManager is the right tool for the *other* half of this domain - a nightly sync of lists between
Kevin's phone and his wife's, say. It is the wrong tool for anything the user is waiting on.

---

## Recommendation

**Ship inexact first. Add exact only where the user asks for it, item by item.**

### The API, by reminder kind

| Reminder kind | API | Permission |
|---|---|---|
| Default: any item with a time trigger | `setAndAllowWhileIdle(RTC_WAKEUP, …)` | **none** |
| Item the user marked "exact" / a calendar event with a start time | `setExactAndAllowWhileIdle(RTC_WAKEUP, …)`, **falling back to `setAndAllowWhileIdle` when `canScheduleExactAlarms()` is false** | `SCHEDULE_EXACT_ALARM` |
| Not recommended for now | `setAlarmClock()` | `SCHEDULE_EXACT_ALARM` |

`setAndAllowWhileIdle` rather than plain `set()` as the default, because §4 shows plain `set()` is
deferred by Doze and the overnight morning reminder is a first-class use case, not an edge case.
It costs nothing extra: still inexact, still no permission.

`setAlarmClock()` is held back deliberately. It is the strongest guarantee available and it shows a
pending-alarm icon in the status bar, which is wrong for "buy milk" and right for "wake me up."
Revisit it only if a genuine wake-me-up feature appears.

### Permissions to declare

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
```

`POST_NOTIFICATIONS` is already declared. **Do not declare `USE_EXACT_ALARM`** - see §2; the
capability is identical to `SCHEDULE_EXACT_ALARM`, and taking a non-revocable install-granted
restricted permission for a two-person app buys nothing but a policy question nobody needs to argue.

### User-facing flow

1. **Nothing is asked at install.** A user who never sets a timed reminder is never prompted for
   anything.
2. **First timed reminder:** if `POST_NOTIFICATIONS` is not granted (API 33+), request it inline
   with a one-line rationale. If refused, see failure modes.
3. **First reminder the user marks "exact":** show LEGION's own explanation, then
   `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. If the user declines, **the reminder is still
   created**, downgraded to inexact, and **says so on the item** - "may fire up to an hour late"
   (in words, per the CLAUDE.md §4 rule 7 habit of labelling weakened data in text, not colour).
4. **A dedicated notification channel** for reminders, `IMPORTANCE_HIGH`, created at first use.
   Neither existing `IMPORTANCE_LOW` foreground-service channel can be reused.
5. **Tapping the notification opens the item** via a `PendingIntent` on `setContentIntent()`.
   Not via a receiver that calls `startActivity()` - that is the Android 12 trampoline ban (§1).

### Components to build

- `ReminderScheduler` - one `rescheduleAll()` entry point, called from **three** places: app start
  (unconditionally, per §5), `BOOT_COMPLETED`, and
  `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. Idempotent; same request code per item
  plus `FLAG_UPDATE_CURRENT`.
- `ReminderReceiver` - fires, posts the notification, and for a recurring item computes and arms the
  next occurrence (§7).
- `BootReceiver` - new file, `android:enabled="false"` in the manifest, flipped on when the first
  alarm is armed. **Not** the deleted car-launcher one; it must never `startActivity`.
- `ExactAlarmPermissionReceiver` - `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` → 
  `rescheduleAll()`.

### Failure modes, each with a decided answer

| Failure | What actually happens | What LEGION does |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` denied at first ask | `canScheduleExactAlarms()` false; calling `setExact*` throws `SecurityException` | Never call it unguarded. Downgrade the item to inexact and label it on the item in words. |
| `SCHEDULE_EXACT_ALARM` revoked later | `doc`: **the app is stopped and all pending exact alarms are cancelled** | `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` receiver → `rescheduleAll()`, which re-arms everything as inexact. Items visibly downgrade. |
| `POST_NOTIFICATIONS` denied | Alarm still fires; nothing is shown. **Silent failure - the exact shape the ticket warns about.** | Do not arm silently. Any item with a time trigger shows an in-app "reminders can't notify" state while the permission is missing, with a one-tap fix. The alarm is still armed, so granting later heals without re-entry. |
| Reminder channel blocked but app-level allowed | Alarm fires, nothing shown | Check channel importance too, not just `areNotificationsEnabled()`. (Mechanism `unverified` - see §6.) |
| Doze, phone idle overnight | Standard alarms deferred to a decaying maintenance window | Default is already `setAndAllowWhileIdle` / `setExactAndAllowWhileIdle`, both Doze-exempt. |
| App in Restricted battery state | `doc`: **alarms are not triggered at all** | Unfixable in code. Surface it once in Settings as a known cause if a reminder is reported missed. |
| Restricted standby bucket | One alarm per day, total | Same treatment; extremely unlikely on a daily-driver phone. |
| Reboot | All alarms cancelled | `BootReceiver` → `rescheduleAll()`. |
| APK reinstalled / force-stopped | `unverified` whether alarms survive | `rescheduleAll()` on every app start makes it moot. |
| Phone off when a recurring occurrence was due | Re-arm never runs; **the whole chain stops** | Boot recovery must recompute forward from now, not from the missed occurrence. Ticket 04 must decide whether a missed occurrence is announced or dropped. |

---

## Open questions

Things the documentation did not settle. Each needs either a better source or a phone.

1. **Does the one-hour inexact bound apply to `setAndAllowWhileIdle()`?** The Schedule alarms page
   states the bound for `set()` and groups `setAndAllowWhileIdle` under inexact, but never restates
   it for the allow-while-idle variant. Since that API is the recommendation's default, this is the
   single most load-bearing unverified claim here. **Measure it on-device before promising the user
   any number.**
2. **How far apart do Doze maintenance windows get after a full night idle?** Documented as
   "less frequently"; no figure published. Matters only if question 1 resolves badly.
3. **Do pending alarms survive an app update or a force-stop?** Not found in primary docs.
   Mitigated by re-arming on app start, so this is curiosity rather than a blocker.
4. **Is `getNotificationChannel(id).importance == IMPORTANCE_NONE` the documented way to detect a
   user-blocked channel?** Widely used, not confirmed against a primary page here.
5. **Does anything outside Play enforce the `USE_EXACT_ALARM` restriction at sideload time?**
   No source found either way. Irrelevant under the recommendation above; becomes blocking only if
   that choice is revisited.
6. **Did Android 15 or 16 change anything about alarms?** The Android 15 "all apps" page shows
   nothing, but I did not read Android 15's targetSdk-gated page or Android 16's pages in full.
   Low risk while `targetSdk` stays at 34; **re-check before raising the target.**
7. **Does the OEM matter?** Not researched. Several Chinese OEM skins are known to apply
   aggressive alarm killing beyond AOSP. Whatever phones Kevin and his wife actually carry decides
   whether this is worth a line. Not an AOSP-documented behaviour, so no primary source exists.

---

## Sources

- https://developer.android.com/develop/background-work/services/alarms/schedule
- https://developer.android.com/develop/background-work/services/alarms
- https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- https://developer.android.com/about/versions/13/features
- https://developer.android.com/about/versions/12/behavior-changes-12
- https://developer.android.com/about/versions/15/behavior-changes-all
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/topic/performance/power/power-details
- https://developer.android.com/topic/performance/background-optimization
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://developer.android.com/develop/background-work/background-tasks
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://support.google.com/googleplay/android-developer/answer/16558241 (Play Console, restricted permissions)

**Could not be read.** The `AlarmManager` API reference
(`developer.android.com/reference/android/app/AlarmManager`, both Java and Kotlin variants) and
`androidx.work.PeriodicWorkRequest` returned only page navigation to the fetcher, never the javadoc
body. Every method-level claim above therefore comes from the *guide* pages, not the javadoc.
The AOSP mirror of `AlarmManager.java` on `android.googlesource.com` returned 404 on the paths
tried. **If a future session needs exact javadoc wording - particularly for question 1 - read those
reference pages in a browser rather than trusting this document.**

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| minSdk 24 / targetSdk 34 / compileSdk 36, and the permission list | `traced` (read from the repo) |
| Zero `AlarmManager` usage in main source | `traced` (grep) |
| Two `IMPORTANCE_LOW` channels exist; `POST_NOTIFICATIONS` already requested at runtime | `traced` (grep + line numbers) |
| Every API guarantee, permission behaviour, Doze rule, bucket limit, boot pattern and WorkManager bound in Findings | `doc`, URL on each |
| Android 15 changed nothing for alarms | `reasoned` from one page's silence |
| Force-stop cancels pending alarms | `reasoned` from the Android 15 widgets/pending-intent note; **not verified** |
| Wall-clock recurrence cannot be expressed as a millisecond interval | `reasoned` |
| The Play `USE_EXACT_ALARM` restriction is a publish-time gate only | `doc` for the mechanism named; **`reasoned`** that no other enforcement exists |
| Everything in Recommendation | `reasoned` design judgement built on the `doc` findings above. **Nothing was built, compiled or run.** |
