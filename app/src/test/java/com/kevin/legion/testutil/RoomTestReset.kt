package com.kevin.legion.testutil

import androidx.arch.core.executor.ArchTaskExecutor
import com.kevin.legion.data.local.CarDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Forces [CarDatabase.getDatabase]'s process-static singleton to rebuild on
 * its next call.
 *
 * **Why this exists.** Robolectric resets its native SQLite shadow layer
 * per @Test METHOD, but [CarDatabase]'s `INSTANCE` is a plain Kotlin object
 * singleton that survives across methods within a class run. Reusing a
 * [CarDatabase] opened against a PREVIOUS method's now-torn-down shadow layer
 * throws `IllegalStateException: Illegal connection pointer N` the moment any
 * DAO touches it - not a real bug in [CarDatabase], just a JVM-static
 * singleton meeting a per-method-reset test double. Call this from an
 * `@Before` in any Robolectric test that reaches [CarDatabase.getDatabase]
 * (directly, or transitively via a controller), so each test method gets a
 * genuinely fresh instance built against ITS OWN Robolectric application.
 *
 * **Extended for `.scratch/hardening/issues/13-the-suite-is-green-by-luck.md`
 * (2026-08-27).** Room's default query/transaction executor is
 * `ArchTaskExecutor.getIOThreadExecutor()` (unset by [CarDatabase.getDatabase]'s
 * builder, confirmed by reading Room 2.8.4's own source rather than assuming),
 * a background thread pool that is a JVM-wide singleton and outlives any one
 * test method. A DAO write schedules an `InvalidationTracker` refresh on that
 * pool, and Robolectric resets its native SQLite shadow layer the moment the
 * TEST METHOD THAT SCHEDULED IT returns - independently of whether or when
 * [CarDatabase.close] is ever called. If that refresh has not finished by
 * then, it throws `Illegal connection pointer` against a shadow layer that no
 * longer recognises it, on an `arch_disk_io` thread, uncaught, and
 * `kotlinx-coroutines-test` blames whichever `runTest` happens to start next -
 * not the class that caused it.
 *
 * **[drainArchDiskIoPool] alone in `@Before`, closing the PREVIOUS leftover
 * instance, is NOT sufficient - proven, not assumed, by an empirical run that
 * still leaked with exactly that placement.** Robolectric's reset for test
 * method M happens before ANY of test method M+1's code runs, `@Before`
 * included, so draining in M+1's `@Before` only ever catches work that was
 * already safe. The drain has to run inside test method M's OWN lifecycle,
 * before M returns - i.e. from M's `@After`/`tearDown`, or (for the rare test
 * with no plain `@Before`/`@After` to hang it on, e.g. a `@Rule`-launched
 * Activity) from wherever that test's own Statement chain naturally finishes.
 * Both halves are real and both are needed: this function's own `@Before`
 * drain still matters for the reuse-safety [resetCarDatabaseSingleton] was
 * originally written for, and the `@After`-side call closes the actual leak.
 * Call [drainArchDiskIoPool] from `@After`/`tearDown` in any Robolectric test
 * that touches Room at all, including one that builds its OWN [CarDatabase]
 * (e.g. via `Room.inMemoryDatabaseBuilder`) instead of going through
 * [resetCarDatabaseSingleton] - the pool is shared process-wide, so a
 * locally-built instance can leak the exact same way.
 */
object RoomTestReset {
    /**
     * The fixed size of `androidx.arch.core.executor.DefaultTaskExecutor`'s disk-IO thread pool
     * (`Executors.newFixedThreadPool(4, ...)`, thread names `arch_disk_io_0`.."arch_disk_io_3") -
     * read from `core-runtime`'s own source rather than assumed, and it matches the
     * `arch_disk_io_1` thread name in the ticket's crash log. This is ANDROIDX INTERNAL and has no
     * public constant, so re-check it against `DefaultTaskExecutor` on any dependency bump that
     * touches `androidx.arch.core`. **The two ways it can go stale fail differently, and neither
     * is silent-and-wrong:** if the real pool GREW, the barrier still trips (our tasks all park
     * together) but proves less than it claims, since the extra threads' work was never waited on -
     * an under-drain, and the leak would simply come back looking like the original bug. If the
     * real pool SHRANK below this number, the barrier can never trip at all, which is why
     * [drainArchDiskIoPool]'s waits are bounded: that case fails the run with a named error
     * instead of hanging it.
     */
    private const val ARCH_DISK_IO_POOL_SIZE = 4

    /** Generous for a drain that normally completes in microseconds, short enough that a stuck
     * pool fails the run rather than hanging it. See [drainArchDiskIoPool] for why the difference
     * matters more than the number. */
    private const val DRAIN_TIMEOUT_SECONDS = 30L

    /**
     * Blocks the calling thread until every task already queued or running on
     * [ArchTaskExecutor]'s disk-IO pool has finished, using only that pool's public `Executor`
     * (never Room internals, per the ticket's own preference for option 1 over option 2 - this
     * codebase does not change Room's execution semantics for the whole suite to chase one leak).
     *
     * **How the proof works.** The pool is a FIXED-size pool of [ARCH_DISK_IO_POOL_SIZE] worker
     * threads pulling from one FIFO queue. We submit exactly that many barrier tasks. A worker
     * thread only reaches the barrier after it has fully finished whatever task it was already
     * running (a thread pool always completes its current task before polling the queue for the
     * next), and FIFO ordering means none of our barrier tasks can be dequeued ahead of a task
     * that was queued before it. So the instant all [ARCH_DISK_IO_POOL_SIZE] threads are
     * simultaneously parked at the [CyclicBarrier], every task submitted before this call - queued
     * OR mid-execution - is provably complete, regardless of queue depth. This holds without
     * knowing the queue's contents, which is what makes it usable from outside Room.
     */
    fun drainArchDiskIoPool() {
        val readyBarrier = CyclicBarrier(ARCH_DISK_IO_POOL_SIZE)
        val done = CountDownLatch(ARCH_DISK_IO_POOL_SIZE)
        val ioExecutor = ArchTaskExecutor.getIOThreadExecutor()
        repeat(ARCH_DISK_IO_POOL_SIZE) {
            ioExecutor.execute {
                // Bounded, not a bare await(). The barrier only trips when all
                // ARCH_DISK_IO_POOL_SIZE threads are parked here at once, which is exactly what
                // proves the pool is drained - but it is also why a WRONG pool size would park
                // these tasks forever. A bare await() would turn that into a suite that HANGS,
                // and a hang is a far worse failure than a failure: it produces no report, no
                // failing test name, and on CI it burns the whole job's time budget before
                // anyone learns anything. Timing out and letting the throw surface turns a
                // stale constant into a named, diagnosable error instead.
                runCatching { readyBarrier.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                done.countDown()
            }
        }
        check(done.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Room invalidation-tracker drain did not settle within $DRAIN_TIMEOUT_SECONDS seconds. " +
                "ARCH_DISK_IO_POOL_SIZE is $ARCH_DISK_IO_POOL_SIZE; if androidx.arch.core's " +
                "DefaultTaskExecutor no longer uses a fixed pool of that size, this barrier can " +
                "never trip and the constant is the thing to fix. See " +
                ".scratch/hardening/issues/13-the-suite-is-green-by-luck.md."
        }
    }

    fun resetCarDatabaseSingleton() {
        // The Kotlin compiler places a private companion-object field like
        // `INSTANCE` as a STATIC field on the ENCLOSING class (CarDatabase),
        // not on CarDatabase$Companion - confirmed via javap, not assumed.
        val instanceField = CarDatabase::class.java.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        val existing = instanceField.get(null) as CarDatabase?
        // Drain BEFORE close(): any InvalidationTracker refresh the previous test's writes
        // scheduled needs to finish while the connection it is running against is still open,
        // not race close()'s teardown of that same connection. See the class doc comment.
        if (existing != null) {
            drainArchDiskIoPool()
        }
        existing?.let { runCatching { it.close() } }
        instanceField.set(null, null)
    }
}
