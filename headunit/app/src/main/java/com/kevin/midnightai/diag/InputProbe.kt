package com.kevin.midnightai.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * HU-1 T2 (volume probe) and T9 (steering-wheel key log). The head unit has no ADB, so what the
 * volume keys and the wheel keys actually deliver can only be learned from the drive log after a
 * drive. Both write one line per event to the drive log (tags VOLPROBE and WHEELKEY) and keep the
 * last [PANEL_LINES] for the live panels in Settings > System > Developer tools.
 *
 * This is the probe only. The volume fix and a wheel-key mapping setting come after one drive's
 * log, by OTA.
 */

/** One probe line and when it happened (wall clock, for the panel's time column). */
data class ProbeLine(val atMs: Long, val text: String)

/** Bounded, newest-last list of probe lines. Pure; synchronized because keys and broadcasts arrive on different threads. */
class ProbeRing(private val capacity: Int = PANEL_LINES) {
    private val lines = ArrayDeque<ProbeLine>()

    @Synchronized
    fun add(line: ProbeLine) {
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<ProbeLine> = lines.toList()
}

/** The probe's wording. Plain ints in, strings out, so every line shape is JVM-tested. */
object ProbeFormat {
    /** `KeyEvent.ACTION_DOWN` / `ACTION_UP`, copied so this object needs no Android runtime. */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    /**
     * The first DOWN and the UP of each press. A held key auto-repeats about 20 times a second,
     * which would flood a ~2 MB log in minutes and say nothing new; the UP carries the hold time.
     */
    fun shouldLogKey(action: Int, repeatCount: Int): Boolean = action != ACTION_DOWN || repeatCount == 0

    fun keyLine(
        name: String,
        keyCode: Int,
        scanCode: Int,
        source: Int,
        action: Int,
        repeatCount: Int,
        heldMs: Long?,
        device: String?,
        handled: Boolean,
    ): String = buildString {
        append("$name code=$keyCode scan=$scanCode src=0x${Integer.toHexString(source)} ")
        append(
            when (action) {
                ACTION_DOWN -> if (repeatCount > 0) "down repeat=$repeatCount" else "down"
                ACTION_UP -> "up" + (heldMs?.let { " held=${it}ms" } ?: "")
                else -> "action=$action"
            },
        )
        append(" dev=${device?.takeIf { it.isNotBlank() } ?: "?"}")
        append(if (handled) " handled" else " passed-on")
    }

    /** An unchanged index is noise: the Settings.System observer fires on every settings write. */
    fun shouldLogObservation(reason: String): Boolean = reason != "UNCHANGED"

    fun observedLine(via: String, index: Int, max: Int, decision: String, reason: String): String =
        "observed via=$via index=$index/$max -> $decision ($reason)"

    /** A writer whose lines are dropped: a held volume key's auto-repeats (the first press is logged). */
    const val QUIET_WRITER = "key-repeat"

    fun shouldLogWrite(by: String): Boolean = by != QUIET_WRITER

    /** The app moved STREAM_MUSIC itself: [by] who (screen, key, voice, panel), [what] call. */
    fun writeLine(by: String, what: String, before: Int, after: Int, max: Int): String =
        "write by=$by $what index $before->$after/$max"
}

/** Lines the Developer tools panels keep. */
const val PANEL_LINES = 20

/** The process-wide probe: drive log + live panel state. */
object InputProbe {
    private val volumeRing = ProbeRing()
    private val wheelRing = ProbeRing()
    private val _volume = MutableStateFlow<List<ProbeLine>>(emptyList())
    private val _wheel = MutableStateFlow<List<ProbeLine>>(emptyList())

    /** The Volume check panel's last [PANEL_LINES] lines. */
    val volume: StateFlow<List<ProbeLine>> = _volume.asStateFlow()

    /** The Wheel keys panel's last [PANEL_LINES] lines. */
    val wheel: StateFlow<List<ProbeLine>> = _wheel.asStateFlow()

    fun volume(text: String) {
        volumeRing.add(ProbeLine(System.currentTimeMillis(), text))
        _volume.value = volumeRing.snapshot()
        DriveLog.breadcrumb("VOLPROBE $text")
    }

    fun wheel(text: String) {
        wheelRing.add(ProbeLine(System.currentTimeMillis(), text))
        _wheel.value = wheelRing.snapshot()
        DriveLog.breadcrumb("WHEELKEY $text")
    }
}
