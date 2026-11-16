package com.kevin.legion.vehicle.diag

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One read-only scan of every module the adapter can reach (brand-neutral core, 2026-11-15).
 *
 * Order: adapter identity, a generic engine request (no answer = ignition off, said so), the bus,
 * the moving check, then either the legacy path (pre-CAN: generic codes only, said in words) or the
 * CAN path (generic engine codes at the functional address, then each candidate address probed with
 * TesterPresent, and every module that answers read for codes and identity), then the make's pack
 * readings. Bounded by [Options.budgetMs]; a scan that runs out says how far it got. A pause of
 * [Options.pauseMs] between modules keeps the request rate polite.
 */
class ModuleScanner(
    private val link: DiagLink,
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val onProgress: (Progress) -> Unit = {},
) {
    data class Options(
        val make: String? = null,
        val full: Boolean = false,
        val budgetMs: Long = QUICK_BUDGET_MS,
        val packs: List<PackReading> = emptyList(),
        val pauseMs: Long = POLITE_PAUSE_MS,
    )

    data class Progress(val probed: Int, val planned: Int, val found: Int)

    suspend fun scan(o: Options): ScanReport {
        val started = clock()
        val adapter = ElmSetup.identify(link)
        val ignition = link.send("0100", LEGACY_TIMEOUT_MS)
        val bus = BusKind.fromDpn(link.send("ATDPN", ElmSetup.AT_TIMEOUT_MS))
        val base = ScanReport(
            startedAtMs = started, finishedAtMs = started, adapter = adapter, bus = bus, make = o.make,
            coverage = Coverage(
                if (o.full) "full" else "quick", 0, 0, ModuleCandidates.rangesWords(bus, o.make, o.full),
            ),
        )
        val stop = when {
            ElmReply.failure(ElmReply.lines(ignition)) != null -> IGNITION_WORDS
            (GenericPid.speedKmh(link.send("010D", LEGACY_TIMEOUT_MS)) ?: 0) > 0 -> MOVING_WORDS
            else -> null
        }
        if (stop != null) return base.copy(finishedAtMs = clock(), stopped = stop)
        if (!bus.isCan) return legacy(base, bus)
        return canScan(base, bus, o, started + o.budgetMs)
    }

    private suspend fun legacy(base: ScanReport, bus: BusKind): ScanReport {
        val engine = EngineCodes(link, bus).read(LEGACY_TIMEOUT_MS)
        return base.copy(finishedAtMs = clock(), engine = engine, notes = listOf(preCanWords(bus)))
    }

    private suspend fun canScan(base: ScanReport, bus: BusKind, o: Options, deadline: Long): ScanReport {
        val setup = ElmSetup(link, bus)
        try {
            if (!setup.begin()) return base.copy(finishedAtMs = clock(), stopped = HEADERS_REFUSED)
            val engine = if (setup.functional()) EngineCodes(link, bus).read(CAN_TIMEOUT_MS) else null
            val request = ModuleRequest(link, setup, bus, CAN_TIMEOUT_MS)
            val plan = ModuleCandidates.plan(bus, o.make, o.full)
            val walk = walk(plan, setup, ModuleReader(request), o, deadline)
            val packTime = bus == BusKind.CAN_11 && clock() < deadline
            val readings = if (packTime) PackReader(setup, request).read(o.packs) else emptyList()
            val notes = buildList {
                if (!setup.manualFlowControl) add(FLOW_CONTROL_NOTE)
                if (engine == null) add(NO_BROADCAST)
                add(SECOND_NETWORK_NOTE)
            }
            return base.copy(
                finishedAtMs = clock(),
                engine = engine,
                modules = walk.first,
                coverage = base.coverage.copy(
                    planned = plan.size,
                    probed = walk.second,
                    timeBudgetHit = walk.second < plan.size,
                ),
                readings = readings,
                notes = notes,
            )
        } finally {
            withContext(NonCancellable) { setup.restore() }
        }
    }

    /** Probes [plan] in order until [deadline]; returns the modules found and how many were probed. */
    private suspend fun walk(
        plan: List<Candidate>,
        setup: ElmSetup,
        reader: ModuleReader,
        o: Options,
        deadline: Long,
    ): Pair<List<ModuleReport>, Int> {
        val known = ModuleCandidates.known(o.make).associateBy { it.requestId }
        val found = mutableListOf<ModuleReport>()
        var probed = 0
        for (c in plan) {
            if (clock() >= deadline) break
            probed++
            if (setup.address(c) && reader.present(c)) {
                val codes = reader.codes(c)
                val ids = reader.ids(c) { clock() < deadline }
                found += ModuleNaming.report(c, codes, ids, known[c.requestId], o.make)
            }
            onProgress(Progress(probed, plan.size, found.size))
            pause(o.pauseMs)
        }
        return found to probed
    }

    companion object {
        const val QUICK_BUDGET_MS = 60_000L
        const val FULL_BUDGET_MS = 240_000L
        const val POLITE_PAUSE_MS = 30L
        const val LEGACY_TIMEOUT_MS = 8_000L
        const val CAN_TIMEOUT_MS = 3_000L

        const val IGNITION_WORDS = "Nothing answered the engine request. The key is probably off: turn it to ON " +
            "(engine running or not) and scan again."
        const val MOVING_WORDS = "The car is moving. A module scan runs only when parked."
        const val HEADERS_REFUSED = "The adapter refused the commands a module scan needs (headers on), so only " +
            "the engine's generic codes can be read with it."
        const val FLOW_CONTROL_NOTE = "The adapter refused manual flow control, so long replies relied on its " +
            "automatic flow control; a reply marked cut off may be the adapter, not the module."
        const val NO_BROADCAST = "The adapter refused the broadcast header, so generic engine codes were not read."
        const val SECOND_NETWORK_NOTE = "Only the main CAN network (pins 6 and 14) was read. Modules that live only " +
            "on a second network (on many Fords, the medium-speed one) are not reachable this way."

        fun preCanWords(bus: BusKind) = "This car talks ${bus.words}, an older bus. With this dongle only the " +
            "engine's generic codes are reachable; the body, airbag, ABS and transmission computers of this era " +
            "use buses an ELM327 cannot speak, so they were not read and are not known to be clear."
    }
}

/** Mode 01 single-byte reads the scanner needs before it touches headers. */
object GenericPid {
    private const val SPEED_PID = 0x0D
    private const val MODE_01_REPLY = 0x41

    fun speedKmh(raw: String): Int? =
        ElmReply.dataLines(raw).firstOrNull { it.size > 2 && it[0] == MODE_01_REPLY && it[1] == SPEED_PID }?.get(2)
}
