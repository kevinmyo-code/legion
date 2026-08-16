# What does Health Connect actually expose, and on what terms?

Type: research
Status: resolved
Blocked by: -

## Question

Health Connect is Android's on-device health data broker: steps, sleep, heart rate, workouts from
any watch/app, no cloud, no key. Before [the scope ticket](11-health-connect-scope.md) can be
argued, surface the facts from Android's own docs (developer.android.com/health-and-fitness):

1. **Data types.** The record types relevant to LEGION: sleep sessions/stages, steps, heart rate,
   exercise sessions, active/total calories, weight. What granularity and provenance metadata
   (which app wrote the record) come with each?
2. **Permissions model.** Per-record-type read permissions, the consent UI, background-read
   permission (added in API 35 era - current rules), and the 30-day read window folklore: is
   "apps can only read records from the last 30 days" still true, under what conditions?
3. **Play Store / declaration requirements.** Health permissions trigger Play policy declarations
   for PUBLISHED apps - what applies to a sideloaded clone-and-run app? Any manifest-level gates?
4. **Min SDK and device reality.** Health Connect as platform component vs the separate APK on
   older Android; what does LEGION's current minSdk mean for it? Galaxy A25 (Kevin's device)
   ships which path?
5. **Freshness.** When a watch syncs through its vendor app into Health Connect, what latency is
   documented? Is "how did I sleep" answerable at 7am from last night's data in practice, per
   docs or the API's own sync semantics?
6. **Aggregation API.** Does the SDK aggregate (daily step totals, sleep duration) natively, or
   does LEGION sum raw records - and cite the API shape.

Write findings to `research/10-health-connect.md`, cite every claim to the owning URL, then append
the Answer here and set Status: resolved.

## Answer

Full findings with citations: [research/10-health-connect.md](../research/10-health-connect.md).
Researched 2026-08-16 against developer.android.com and Play Console Help only.

1. **Data types.** All six exist as first-class records: `StepsRecord` (interval count),
   `SleepSessionRecord` (interval + `stages`), `HeartRateRecord` (series of BPM samples),
   `ExerciseSessionRecord`, `Active/TotalCaloriesBurnedRecord`, `WeightRecord` (instantaneous).
   Every record carries mandatory `Metadata`: `dataOrigin` (writing app's package),
   `device` (type/manufacturer/model), `recordingMethod` (sensed vs manual), `lastModifiedTime`.
   Provenance is queryable and filterable.
2. **Permissions.** Per-type runtime grants (`android.permission.health.READ_STEPS` etc.) via
   Health Connect's own consent sheet. 30-day folklore is TRUE and current: default read window
   is 30 days before first grant (Android 14+ exempts an app's own writes); older reads error
   unless `READ_HEALTH_DATA_HISTORY` is granted. Uninstall resets the clock. Background reads
   need `READ_HEALTH_DATA_IN_BACKGROUND` plus a runtime feature-availability check; foreground
   pull tools need neither.
3. **Play / sideload.** Play's declaration form, approved-use-case list, and privacy-policy
   rules are Play Console review requirements; no platform-level allowlist exists, so a
   sideloaded app just requests and the user grants. Manifest gates that DO apply: declare each
   READ permission, provide the permissions-rationale activity/alias (`VIEW_PERMISSION_USAGE` +
   `HEALTH_PERMISSIONS` category on 14+), `<queries>` for `com.google.android.apps.healthdata`.
4. **Min SDK / device.** Android 14+ = framework module, zero setup; 13 and below = separate
   Play APK requiring Android 9+. LEGION: `minSdk 24, targetSdk 34`; gate at runtime with
   `HealthConnectClient.getSdkStatus()`. Watch-out (reasoned, verify at build): stable
   connect-client 1.1.0 has a library minSdk above 24 (1.2.0-alpha05 notes lowering it to 24),
   so expect a manifest-merger fix (`tools:overrideLibrary`, alpha dep, or minSdk bump).
   Galaxy A25 ships Android 14 = framework path (device fact, confirm on handset).
5. **Freshness.** No documented latency anywhere. HC is a passive store; readers poll
   (foreground-entry + Changes API, tokens expire in 30 days). "How did I sleep" at 7am depends
   on Samsung Health's sync cadence, which is undocumented; must be tested on the A25. Pull
   tool should report "not synced yet" when the session is absent.
6. **Aggregation.** Native: `aggregate` / `aggregateGroupByDuration` / `aggregateGroupByPeriod`
   with `STEPS.COUNT_TOTAL`, `SLEEP_DURATION_TOTAL`, `BPM_AVG/MIN/MAX`,
   `ACTIVE_CALORIES_TOTAL`, `ENERGY_TOTAL`, `EXERCISE_DURATION_TOTAL`. Aggregates dedupe
   Activity and Sleep across multiple writer apps by user-set priority; raw-record summing does
   not. Never hand-sum totals.
