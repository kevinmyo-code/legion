---
type: build
status: built
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

## FIXED 2026-08-27. Option 1, and the first placement of it was wrong.

**Option 2 was investigated and disqualified on evidence, not deferred.** Room's
`assertNotMainThread()` checks `Looper.getMainLooper().thread` DIRECTLY, not
`ArchTaskExecutor.isMainThread()` (traced in Room 2.8.4's own source). Two tests already carry
`.allowMainThreadQueries()` precisely because Robolectric's test thread IS the main-looper thread, so
making disk IO run on the calling thread would have tripped that assertion across the whole 79-file
surface that relies on hopping off it. A far larger blast radius than the leak.

**The fix is a pool-quiescence barrier** (`RoomTestReset.drainArchDiskIoPool`) built only from
`java.util.concurrent` plus `ArchTaskExecutor`'s public `Executor` - no Room internals. Submit
exactly as many barrier tasks as the pool has threads; the instant all of them are parked together,
every previously queued or running task is provably complete, whatever the queue depth.

**The first placement failed, and finding that out took a real run rather than an argument.** The
drain was put in `@Before` alongside the existing reset, and the suite STILL leaked - 7 leaking
classes on the next full run. Robolectric resets its shadow layer for test method M *before any of
M+1's code runs*, `@Before` included, so a drain there only ever catches work that was already safe.
It has to run inside the CAUSING test's own lifecycle: `@After`/`tearDown`, or a `finally` around
the Statement for the one `@Rule`-only screenshot test. 78 files updated, plus two that build their
own in-memory `CarDatabase` and so never went through the singleton at all.

**Verification, deliberately not one green run**, since green-by-luck is the whole subject of this
ticket. Five clean full runs total across two independent checks, each with `test-results` deleted
first: **2,744 tests, 0 failures, and 0 classes emitting `Illegal connection pointer` in
`system-err`.** The leak count is the assertion that matters - a green suite that still leaked would
mean the casualty moved rather than the cause being fixed.

**Hardened after review:** the barrier's waits are BOUNDED. A stale `ARCH_DISK_IO_POOL_SIZE` fails
two different ways and neither is silent - a grown pool under-drains (the leak returns, looking like
the original bug), a shrunk pool could never trip the barrier at all. Unbounded waits would have
turned that second case into a suite that HANGS, producing no report and no failing test name. It
now fails with an error naming the constant.
