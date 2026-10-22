package com.kevin.legion.dumb

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One app's line in the digest: the package, how many notifications were held, and when the last
 * one was. **Nothing else, ever** (CLAUDE.md section 7: third-party content is read-through only).
 * No title, no text, no sender, and the prototype's per-app gist line does not ship.
 */
data class DigestEntry(val packageName: String, val count: Int, val lastHeldAtMs: Long)

/** What the digest screen reads. [pending] is set when the mode ends and cleared once it has been dealt with. */
data class DigestState(val entries: List<DigestEntry> = emptyList(), val pending: Boolean = false)

interface DigestPersistence {
    fun read(): DigestState
    fun write(state: DigestState)
}

/** The held-notification counter, observable. Thread-safe: the listener posts from its own thread. */
class HeldDigest(private val persistence: DigestPersistence) {
    private val lock = Any()
    private val _state = MutableStateFlow(persistence.read())
    val state: StateFlow<DigestState> = _state.asStateFlow()

    fun record(packageName: String, atMs: Long) = update { s ->
        val existing = s.entries.firstOrNull { it.packageName == packageName }
        val merged = DigestEntry(packageName, (existing?.count ?: 0) + 1, atMs)
        s.copy(entries = s.entries.filterNot { it.packageName == packageName } + merged)
    }

    /** A tapped app leaves the digest. */
    fun remove(packageName: String) = update { s ->
        s.copy(entries = s.entries.filterNot { it.packageName == packageName })
    }

    /** "Clear all": empties the list and ends the pending digest. */
    fun clear() = update { DigestState() }

    /** The mode has just ended: show the digest, if there is anything to show. */
    fun markPending() = update { s -> s.copy(pending = s.entries.isNotEmpty()) }

    /** Dealt with (Back, or Go back to dumb mode) without clearing the counts. */
    fun dismiss() = update { s -> s.copy(pending = false) }

    private fun update(change: (DigestState) -> DigestState) = synchronized(lock) {
        val next = change(_state.value)
        persistence.write(next)
        _state.value = next
    }
}

/**
 * SharedPreferences persistence: one "package|count|lastHeldAt" per line and a pending flag. A line
 * that does not parse is dropped. Package names cannot contain `|` or a newline.
 */
class PrefsDigestPersistence(private val prefs: SharedPreferences) : DigestPersistence {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun read(): DigestState =
        DigestState(parse(prefs.getString(KEY_ENTRIES, "").orEmpty()), prefs.getBoolean(KEY_PENDING, false))

    override fun write(state: DigestState) {
        prefs.edit().putString(KEY_ENTRIES, format(state.entries)).putBoolean(KEY_PENDING, state.pending).apply()
    }

    internal companion object {
        const val PREFS = "dumb_mode_digest"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_PENDING = "pending"
        private const val FIELD_COUNT = 3

        fun format(entries: List<DigestEntry>): String =
            entries.joinToString("\n") { "${it.packageName}|${it.count}|${it.lastHeldAtMs}" }

        fun parse(raw: String): List<DigestEntry> = raw.lines().mapNotNull(::parseLine)

        private fun parseLine(line: String): DigestEntry? {
            val f = line.split('|')
            val count = f.getOrNull(1)?.toIntOrNull()
            val at = f.getOrNull(2)?.toLongOrNull()
            val wellFormed = f.size == FIELD_COUNT && f[0].isNotEmpty()
            return if (wellFormed && count != null && at != null) DigestEntry(f[0], count, at) else null
        }
    }
}
