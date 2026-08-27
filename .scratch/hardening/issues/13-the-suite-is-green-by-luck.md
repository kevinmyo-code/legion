---
type: build
status: open
blocked_by: []
map: hardening
---

# The suite is green by luck, and this project treats green as evidence

**Found 2026-08-27 while verifying an unrelated fix. Not a flake in one test - a leak from six of
them that lands on whichever Compose coroutine test runs next.**

## What happens

A full `testDebugUnitTest` run fails intermittently with:

```
kotlinx.coroutines.test.UncaughtExceptionsBeforeTest:
There were uncaught exceptions before the test started.
```

The reported test is innocent. The real exception comes from a DIFFERENT, earlier test class, on a
background thread:

```
Exception in thread "arch_disk_io_1 @Room Invalidation Tracker Refresh#2055"
java.lang.IllegalStateException: Illegal connection pointer 45960
  at org.robolectric.shadows.ShadowLegacySQLiteConnection$Connections.getConnection
```

`RoomTestReset.resetCarDatabaseSingleton()` closes the `CarDatabase` singleton and nulls it, which is
correct and necessary - its own doc comment explains why the JVM-static singleton must not outlive
Robolectric's per-method shadow reset. But Room's `InvalidationTracker` refresh runs on the
`arch_disk_io` executor, and a task already queued when `close()` lands executes afterwards against a
dead connection. The throw is uncaught on that thread, and `kotlinx-coroutines-test` attributes it to
whichever `runTest` starts next.

## Why it is not a flake, and why it matters

**Attribution experiment, run today.** With one unrelated change in the tree the failure landed on
`ui/common/GapEmptyRowTest`. `git stash`-ing that entire change and re-running produced the same
failure on `ui/common/HelpRowTest` instead. Different victim, same cause. Both pass in isolation.

So the suite's colour depends on execution order, and any change that shifts ordering moves the
casualty. **Several runs earlier the same day reported "0 failures" - those were luck, not proof.**

That is the part that matters. This project uses "suite green, N tests, 0 failures" as its standard
evidence in commit messages, ticket resolutions and handoffs. A suite that is green by chance makes
every one of those claims weaker than it reads, and it trains whoever reads them to discount a real
red as "the known flake" - which is exactly what happened to this one, repeatedly, today.

## The six leakers, traced from `system-err` in the JUnit XML

- `data/local/MaintenanceItemDaoTargetedWritesTest`
- `data/local/VehicleDaoTargetedWritesTest`
- `goals/GoalControllerTest`
- `service/LiveToolboxCurrencyTest`
- `ui/fleet/MaintenanceWritesTest`
- `vehicle/VehicleControllerServiceWritesTest`

They are not doing anything unusual; they are simply the classes whose last DAO write leaves a
refresh queued when the reset closes the database.

## Options, none chosen

1. **Drain before closing.** Make `resetCarDatabaseSingleton` settle the invalidation tracker's
   pending work before `close()`. Correct in principle; needs a way to await the `arch_disk_io`
   queue that does not depend on Room internals.
2. **Run the tracker synchronously in tests.** Point `ArchTaskExecutor` at a direct executor so no
   background refresh exists. The standard androidx approach, but it changes execution semantics for
   every Room test in the suite at once.
3. **Swallow this one exception on `arch_disk_io` threads**, narrowly and by message. Cheapest and
   the worst of the three: it hides a real symptom class behind a filter, which is the shape this
   codebase rejects everywhere else.

Recommendation is 1, then 2 if 1 cannot be done without reaching into Room internals. **Not 3.**

## Do not close this by making the failure rarer

The test passes in isolation today. Reordering, sharding or retrying would all make it disappear
without fixing anything, and would leave the suite exactly as untrustworthy as it is now.
