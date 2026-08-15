# What is the alarm mechanism on current Android, and what does it cost?

Type: research
Status: resolved
Blocked by: none

Resolved 2026-08-07. Full findings with sources: `.scratch/notes-lists-calendar/research/android-alarms.md`.

## Question

Charting decision 5 puts alarms local rather than outsourcing them to Google Calendar. That decision
was taken knowing it is the largest new-platform-surface item on this map, but **not knowing what it
actually costs on a modern Android target**. Establish the facts before anything is designed.

LEGION has never scheduled an alarm or posted a user-facing notification. There is no precedent in
the codebase to copy.

### What to establish, against primary sources (Android developer documentation, not blog posts)

1. **Exact vs inexact alarms.** What `AlarmManager` guarantees, and what changed across Android 12,
   13, 14 and 15. Which of `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` applies to an app like this
   one, whether either is grantable without a Play Store policy declaration, and what the runtime
   flow to request it looks like.
2. **Whether a personal reminder even needs an exact alarm.** An inexact alarm avoids the permission
   entirely. Establish the actual delivery-window behaviour so the tradeoff is a fact, not a guess.
3. **Doze and app standby.** What happens to a scheduled alarm when the phone has been idle
   overnight, which is precisely when a morning reminder must fire.
4. **Surviving reboot.** `RECEIVE_BOOT_COMPLETED`, the receiver, and what has to be re-scheduled.
5. **`POST_NOTIFICATIONS`.** The runtime permission from Android 13 on, and what the app should do
   when it is refused - a reminder that cannot notify is a silent failure, and this app has a
   documented history of features that pass every test while being structurally unable to run.
6. **The target/min SDK this app actually declares.** Read `app/build.gradle.kts` rather than
   assuming; the answers above are all version-dependent.
7. **Recurrence interaction.** Whether recurring reminders mean scheduling one alarm at a time and
   re-arming on fire, or something else. Ticket 04 needs this.

### What the answer must record

A concrete recommendation: which alarm API, which permissions, what the user-facing permission flow
is, and what the failure modes are when a permission is refused or revoked later. Cite the primary
sources.

### Feeds

Ticket 04 (recurrence) and the eventual build. Independent of ticket 01, so it can run in parallel.

## Answer

**Reminders do NOT need an exact alarm, and therefore need no new user-facing permission at all.**
That was the ticket's highest-value question and it comes back a clear no, which removes the single
biggest lump of work charting decision 5 was expected to cost.

### The ticket's own premise was wrong, corrected here

It claimed the app "has never scheduled an alarm or posted a user-facing notification." Half wrong,
and the half that is wrong is good news. **Verified locally, not taken on the researcher's word:**

- Two `IMPORTANCE_LOW` notification channels already exist (`AriaForegroundService.kt:841`,
  `LedgerIngestService.kt:148`), and `POST_NOTIFICATIONS` is already declared in the manifest AND
  already requested at runtime as step 1 of `ui/SettingsScreen.kt`'s permission chain.
- Alarms genuinely are virgin territory: **zero `AlarmManager` references** anywhere in `app/src/main`.
- A `BootReceiver` did exist and was deleted during `legion-shape` ticket 07. It has to come back,
  but for a different reason and **without** its old `startActivity` behaviour.

### Local facts that decide everything (verified)

`compileSdk 36`, **`targetSdk 34`**, `minSdk 24`. targetSdk 34 is the load-bearing one: Android 14's
deny-`SCHEDULE_EXACT_ALARM`-by-default applies. Not declared today: `SCHEDULE_EXACT_ALARM`,
`USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`.

### The mechanism

| | Choice |
|---|---|
| **Default for every reminder** | `setAndAllowWhileIdle()` - inexact, permission-free, and Doze-exempt |
| **Only when the user marks an item exact** | `setExactAndAllowWhileIdle()`, gated on `canScheduleExactAlarms()` |
| **Declare** | `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` |
| **Do NOT declare** | `USE_EXACT_ALARM` |

Plain `set()` fires within one hour of the trigger and never early, which is fine for a personal
reminder - but **plain `set()` is deferred by Doze**, and Doze maintenance windows get rarer the
longer a phone idles. That breaks precisely the overnight-into-morning reminder this ticket singled
out. `setAndAllowWhileIdle` fixes it at zero cost: still inexact, still permission-free, Doze-exempt.

`USE_EXACT_ALARM` is rejected on proportionality, not policy. Its capability is identical to
`SCHEDULE_EXACT_ALARM`; the only difference is that it is non-revocable, and its Play restriction is
a publish-time review gate a sideloaded app never reaches. Taking a non-revocable permission to
avoid a revocable one is not a trade worth making for a two-person app.

When exact permission is refused, **downgrade silently to inexact and say so in words on the item**.
Never fail quietly - this app has a documented history of features that pass every test while being
structurally unable to run.

### Three findings that change the design

1. **Revoking `SCHEDULE_EXACT_ALARM` kills the app process and cancels every pending exact alarm.**
   Documented behaviour. The `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` broadcast is the
   only recovery path.
2. **WorkManager is strictly worse than an inexact alarm here.** Doze blocks JobScheduler outright,
   so WorkManager has no Doze exemption while `setAndAllowWhileIdle` does, plus a 15-minute minimum
   period and no stated lateness bound. Ruled out on facts, not taste.
3. **A user putting the app in "restricted" battery state means alarms do not fire at all.**
   Unfixable in code. It can only be detected and explained.

### Shape of the implementation

One idempotent `rescheduleAll()`, with exactly three callers: app start, `BOOT_COMPLETED`, and the
exact-alarm-permission-state-changed broadcast.

### Consequence ticket 04 (recurrence) must own

**Re-arm on fire, not `setRepeating`.** `setRepeating` has been inexact since API 19 so buys no
precision; `setInexactRepeating` only accepts the `INTERVAL_*` constants so cannot express "weekdays"
or "monthly on the 3rd"; and no exact repeating API exists at all.

The sharp edge: **if the phone is off when an occurrence is due, the chain stops rather than skips.**
Boot recovery must recompute forward from now, not resume from the last fired occurrence.

### The load-bearing thing nobody has verified

**Whether the documented one-hour lateness bound applies to `setAndAllowWhileIdle` specifically.**
The guide groups it under inexact alarms but never restates the number for it. That is the API this
answer makes the default, so it is the most consequential unverified claim here. **Do not promise
Kevin any delivery figure until it is measured on his own phone.** Added to the map's fog.

Also unverified, and flagged rather than smoothed over: whether alarms survive an app update or
force-stop (mitigated by re-arming on start regardless), and every method-level claim comes from
Android's *guide* pages rather than javadoc, because the `AlarmManager` and `PeriodicWorkRequest`
API reference pages would not render through WebFetch on either the Java or Kotlin URLs, and the
AOSP mirror 404'd. A future session should read those two pages in a browser.
