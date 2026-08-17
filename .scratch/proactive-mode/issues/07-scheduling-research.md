# What may a background process actually do on Android in 2026?

Type: research
Status: open
Blocked by: -

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
