package com.kevin.legion.clock

/** The one stopwatch (ticket 03). Survives process death; across a reboot it counts on the wall clock. */
class StopwatchOps(private val core: ClockCore) {

    fun total(): Long = total(core.read().stopwatch)

    fun start(): ClockResult {
        val sw = core.read().stopwatch
        if (sw.running) return ClockResult(false, "The stopwatch is already running, at ${spoken(sw)}.")
        core.edit { s ->
            s.copy(
                stopwatch = s.stopwatch.copy(
                    running = true,
                    startedAtElapsed = core.time.elapsedMs(),
                    startedAtWallMs = core.time.nowMs(),
                    bootCount = core.time.bootCount(),
                ),
            ) to Unit
        }
        val verb = if (sw.accumulatedMs > 0) "resumed at ${spoken(sw)}" else "started"
        return ClockResult(true, "Stopwatch $verb.")
    }

    fun stop(): ClockResult {
        val sw = core.read().stopwatch
        if (!sw.running) return ClockResult(false, "The stopwatch is not running. It reads ${spoken(sw)}.")
        val stopped = core.edit { s ->
            val next = s.stopwatch.copy(running = false, accumulatedMs = total(s.stopwatch))
            s.copy(stopwatch = next) to next
        }
        return ClockResult(true, "Stopwatch stopped at ${spoken(stopped)}.")
    }

    fun lap(): ClockResult {
        val sw = core.read().stopwatch
        if (!sw.running) return ClockResult(false, "The stopwatch is not running, so no lap was taken.")
        val laps = core.edit { s ->
            val next = s.stopwatch.copy(laps = s.stopwatch.laps + total(s.stopwatch))
            s.copy(stopwatch = next) to next.laps
        }
        val split = laps.last() - (laps.dropLast(1).lastOrNull() ?: 0L)
        val total = AlarmMath.spokenDuration(laps.last())
        return ClockResult(true, "Lap ${laps.size}: ${AlarmMath.spokenDuration(split)}, total $total.")
    }

    fun reset(): ClockResult {
        core.edit { s -> s.copy(stopwatch = Stopwatch()) to Unit }
        return ClockResult(true, "Stopwatch reset to zero.")
    }

    /** After a reboot: banks the running stretch measured on the wall clock and restarts it on this boot. */
    fun rebase() {
        val boot = core.time.bootCount()
        core.edit { s ->
            val sw = s.stopwatch
            if (!sw.running || sw.bootCount == boot) return@edit s to Unit
            s.copy(
                stopwatch = sw.copy(
                    accumulatedMs = total(sw),
                    startedAtElapsed = core.time.elapsedMs(),
                    startedAtWallMs = core.time.nowMs(),
                    bootCount = boot,
                ),
            ) to Unit
        }
    }

    private fun total(sw: Stopwatch): Long =
        AlarmMath.stopwatchTotal(sw, core.time.elapsedMs(), core.time.nowMs(), core.time.bootCount())

    private fun spoken(sw: Stopwatch): String = AlarmMath.spokenDuration(total(sw))
}
