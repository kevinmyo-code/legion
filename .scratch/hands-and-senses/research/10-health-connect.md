# Health Connect: facts for the LEGION scope call

Date: 2026-08-16. Research ticket: `../issues/10-health-connect-research.md`.
Primary sources only: developer.android.com (Health Connect guides, Jetpack release notes) and
Play Console Help for the policy question. Claims not verifiable from those are marked.

## 1. Data types and provenance

Source: https://developer.android.com/health-and-fitness/health-connect/data-types
and https://developer.android.com/health-and-fitness/health-connect/metadata

| Record | Shape | Mandatory fields |
|---|---|---|
| `StepsRecord` | Interval (count over a time range) | `count`, `startTime`, `endTime`, `metadata` |
| `SleepSessionRecord` | Interval, with `stages` list | `stages`, `startTime`, `endTime`, `metadata` |
| `HeartRateRecord` | Series (timestamped BPM `samples` inside an interval) | `samples`, `startTime`, `endTime`, `metadata` |
| `ExerciseSessionRecord` | Interval | `exerciseType`, `laps`, `segments`, `startTime`, `endTime`, `metadata` |
| `ActiveCaloriesBurnedRecord` | Interval | `energy`, `startTime`, `endTime`, `metadata` |
| `TotalCaloriesBurnedRecord` | Interval | `energy`, `startTime`, `endTime`, `metadata` |
| `WeightRecord` | Instantaneous | `weight`, `time`, `metadata` |

- Every record carries a mandatory `Metadata` object. Provenance fields:
  - `dataOrigin`: package name of the writing app. Populated by Health Connect, readable on
    every record; also usable as a read filter (`dataOriginFilter = setOf(DataOrigin("pkg"))`).
  - `device`: `type` (`TYPE_WATCH`, `TYPE_PHONE`, `TYPE_SCALE`, `TYPE_RING`...), optional
    `manufacturer`/`model`.
  - `recordingMethod`: `RECORDING_METHOD_AUTOMATICALLY_RECORDED` / `_ACTIVELY_RECORDED` /
    `_MANUALLY_RECORDED` / `_UNKNOWN`. Distinguishes sensor data from hand-typed entries.
  - `id`, `lastModifiedTime`: populated by Health Connect.
- Net: LEGION can tell WHICH app wrote a row, from WHAT device class, and whether it was
  sensed or typed. Good fit for provenance tagging.

## 2. Permissions model

Source: https://developer.android.com/health-and-fitness/health-connect/read-data
and https://developer.android.com/health-and-fitness/health-connect/get-started

- Per-record-type runtime permissions, declared in the manifest as
  `android.permission.health.READ_<TYPE>` (e.g. `READ_STEPS`, `READ_SLEEP`,
  `READ_HEART_RATE`, `READ_EXERCISE`, `READ_ACTIVE_CALORIES_BURNED`,
  `READ_TOTAL_CALORIES_BURNED`, `READ_WEIGHT`). Requested via the SDK's
  `PermissionController` contract; consent UI is Health Connect's own per-type toggle sheet,
  not a normal Android permission dialog.
- **30-day window: still true, refined.** Default: an app can read data written up to 30 days
  before the moment its first permission was granted. On Android 14+ the limit applies only to
  OTHER apps' data (own writes unlimited); on Android 13 and lower it applies to all data.
  Reading older records without the extra grant errors. Escape hatch:
  `PERMISSION_READ_HEALTH_DATA_HISTORY` (manifest
  `android.permission.health.READ_HEALTH_DATA_HISTORY`), user-grantable in the same sheet.
  Uninstall + reinstall revokes everything and restarts the 30-day clock at the new grant.
- **Background reads.** Foreground reads are the default; reading while backgrounded needs
  `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` AND a runtime feature check:
  `healthConnectClient.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == FEATURE_STATUS_AVAILABLE`.
  Feature is module-version dependent, not guaranteed on every device; docs say degrade
  gracefully if not granted. Both background-read and history-read permissions landed in the
  Jetpack client during 1.1.0 alphas (1.1.0-alpha09 / alpha10)
  (https://developer.android.com/jetpack/androidx/releases/health-connect).
- Reads are paginated (`ReadRecordsRequest`, default `pageSize` 1000, `pageToken` loop) and
  quota-limited (`IllegalStateException` on quota; docs show backoff).

## 3. Play policy vs sideload

Sources: https://support.google.com/googleplay/android-developer/answer/12991134 (Play Console
Help, "Android Health Permissions") and
https://developer.android.com/health-and-fitness/health-connect/get-started

- Play policy: apps requesting health permissions must submit a declaration form in Play
  Console, fit an approved use case (fitness/wellness etc.), and link a privacy policy from
  the app and the store listing. The page is Play Console guidance governing Play
  distribution and review; it is enforced at app review time.
- **Sideloaded app: no Play gate.** Nothing in the platform blocks a non-Play app from
  requesting Health Connect permissions; the user grants them in the Health Connect consent
  UI. Clone-and-run unaffected. (Inference from scope: the policy doc is Play Console
  review process; no manifest-level allowlist is documented anywhere in the HC guides.)
- **Manifest-level gates that DO apply to everyone, sideloaded or not:**
  - Declare each `android.permission.health.READ_*` permission.
  - A rationale entry point is required for the permission sheet to link "privacy policy":
    Android 13-: activity with intent filter `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`;
    Android 14+: activity-alias handling `android.intent.action.VIEW_PERMISSION_USAGE` with
    category `android.intent.category.HEALTH_PERMISSIONS`, guarded by
    `android.permission.START_VIEW_PERMISSION_USAGE`.
  - Package-visibility `<queries><package android:name="com.google.android.apps.healthdata"/></queries>`
    (needed for the APK path on 13-).

## 4. Min SDK and device reality

Sources: https://developer.android.com/health-and-fitness/health-connect/get-started and
https://developer.android.com/jetpack/androidx/releases/health-connect

- Two delivery models: Android 13 (API 33) and lower = separate Play Store APK
  (`com.google.android.apps.healthdata`), user must install it, HC app itself needs Android 9
  (API 28)+. Android 14 (API 34)+ = framework module, zero setup.
- SDK support floor per docs: "The Health Connect SDK supports Android 8 (API level 26) or
  higher." Jetpack client: stable `1.1.0` (2025-10-08); `1.2.0-alpha05` (2026-08-12) release
  notes state minSdk is now API 24. So stable 1.1.0 carries a library minSdk above LEGION's:
  expect a manifest-merger conflict at minSdk 24 needing either the 1.2.0 alpha, a
  `tools:overrideLibrary`, or an app minSdk bump. Reasoned from release notes, not built;
  verify with an actual `compileDebugKotlin`.
- Gate at runtime with `HealthConnectClient.getSdkStatus(context)`
  (`SDK_AVAILABLE` / `SDK_UNAVAILABLE` / provider-update-required). Devices below API 28
  simply report unavailable; LEGION's minSdk 24 is fine as long as the feature is gated.
- **LEGION build reality** (`app/build.gradle.kts`): `minSdk = 24`, `targetSdk = 34`,
  `compileSdk = 36`. targetSdk 34 means the app is built against the framework-module world;
  no change needed there.
- **Galaxy A25 (SM-A256U): ships Android 14 / One UI 6 at launch** - framework-module path, no
  separate APK install, background-read feature subject to module version on device.
  Device OS version is common knowledge, not from developer.android.com; confirm on the
  handset (Settings > About phone) before relying on it.

## 5. Freshness

Source: https://developer.android.com/health-and-fitness/health-connect/sync-data

- **No documented latency guarantee anywhere.** Health Connect is a passive on-device store:
  data exists only once the writing app writes it. Docs' explicit model: apps cannot be
  notified of new data; poll on foreground-entry and periodically while foregrounded, using
  the Changes API (`getChanges(token)`, tokens expire after 30 days unused).
- For wearables, docs recommend vendors use `CompanionDeviceService` + BLE GATT notifications
  to write "with low latency," but that is guidance to the vendor, not a guarantee to readers.
- Practical "how did I sleep" at 7am therefore depends entirely on when Samsung Health (or the
  watch's companion app) syncs the night's sleep into Health Connect. Samsung's sync cadence
  is not documented on developer.android.com; unverifiable from primary sources. Empirical
  check on the A25 is the only way to answer it. Mitigation available to LEGION: read at ask
  time (pull tool), and if the sleep session is missing, say so rather than guessing.

## 6. Aggregation API

Source: https://developer.android.com/health-and-fitness/health-connect/aggregate-data

- Native, three shapes: `aggregate(AggregateRequest)` for one bucket,
  `aggregateGroupByDuration(...)` for fixed intervals (e.g. hourly),
  `aggregateGroupByPeriod(...)` for calendar buckets (e.g. per day, per month). All take a
  `metrics` set + `TimeRangeFilter`, optional `dataOriginFilter`.
- Metrics covering LEGION's list: `StepsRecord.COUNT_TOTAL`,
  `SleepSessionRecord.SLEEP_DURATION_TOTAL`, `HeartRateRecord.BPM_AVG/BPM_MIN/BPM_MAX/
  MEASUREMENTS_COUNT`, `ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL`,
  `TotalCaloriesBurnedRecord.ENERGY_TOTAL`, `ExerciseSessionRecord.EXERCISE_DURATION_TOTAL`.
- **Dedup is the killer feature:** for Activity and Sleep types the Aggregate API drops
  duplicate rows written by multiple apps, keeping the user's highest-priority app. Other
  types are summed across all writers. So daily steps via aggregate() is double-count-safe;
  hand-summing raw `StepsRecord`s is not. LEGION should never sum raw records for totals.

## Read-through for the scope ticket

- Everything LEGION wants (sleep, steps, HR, workouts, calories) exists as first-class record
  types with per-type read permissions, provenance metadata, and native deduped aggregation.
- No cloud, no key, no backend: consistent with clone-and-run and the no-Kevin-hosted rule.
- The two real constraints: 30-day history unless the extra permission is granted at consent
  time, and freshness at the mercy of the vendor app's sync cadence (undocumented; test on
  device).
