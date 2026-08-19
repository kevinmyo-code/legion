package com.kevin.legion.car

import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * One diagnostic line: when, which probe wrote it, and what it saw.
 * [timestamp] is `System.currentTimeMillis()` at the moment [CarProbeLog.log]
 * was called, not when it is rendered - the ring buffer can hold entries for
 * a while before [ui.CarProbeScreen] draws them.
 */
data class ProbeEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
)

/**
 * Process-wide, in-memory, append-only diagnostic log for the Android Auto
 * probe waves ([car/LegionMediaLibraryService.kt] wave 1, the self-managed
 * call and OBD-contention probes of later waves).
 *
 * **Why this exists instead of `Log.d`.** The test device (an OPPO A17k) is
 * documented in `memory/MEMORY.md` as filtering this app's own logcat output,
 * so `adb logcat` is not a usable reporting channel on it. Every probe
 * observation - a Telecom callback, a media-session callback, an OBD poll -
 * must land somewhere that is readable ON THE SCREEN instead. This object is
 * that somewhere; [com.kevin.legion.ui.CarProbeScreen] is the surface that
 * renders it.
 *
 * **Thread safety.** Probes fire from wildly different threads: Telecom
 * callbacks, `MediaLibraryService` binder callbacks, and the OBD polling
 * thread in `vehicle/`. [MutableStateFlow.update] is lock-free and safe to
 * call from any of them concurrently - no external synchronization needed.
 *
 * **Bounded, not unbounded.** A probe session can run for a long drive; an
 * unbounded log is a slow leak. [CAP] entries, oldest dropped first, same
 * shape as a ring buffer without the fixed-array bookkeeping - `List.drop(1)`
 * on a 500-element list is cheap enough for a diagnostic log that writes at
 * human/callback cadence, not per-audio-frame.
 */
object CarProbeLog {
    private const val CAP = 500

    private val _entries = MutableStateFlow<List<ProbeEntry>>(emptyList())

    /** Reverse-chronological is [ui.CarProbeScreen]'s job, not this flow's - entries here are append order (oldest first). */
    val entries: StateFlow<List<ProbeEntry>> = _entries

    /** Appends one entry, dropping the oldest if the ring buffer is at [CAP]. Safe to call from any thread. */
    fun log(tag: String, message: String) {
        val entry = ProbeEntry(System.currentTimeMillis(), tag, message)
        _entries.update { current ->
            val next = current + entry
            if (next.size > CAP) next.subList(next.size - CAP, next.size) else next
        }
    }

    /** Empties the log. Wired to the "Clear" button on [ui.CarProbeScreen]. */
    fun clear() {
        _entries.update { emptyList() }
    }

    /**
     * Plain-text export for the "Copy" button's clipboard payload - one line
     * per entry, oldest first (matches [entries]' own order, not the screen's
     * reverse-chronological display), `HH:mm:ss.SSS  TAG  message`.
     */
    fun dump(): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { entry ->
            "${formatter.format(entry.timestamp)}  ${entry.tag}  ${entry.message}"
        }
    }
}
