package com.kevin.legion.finance

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The low-cash floor Kevin sets on the Money screen (2026-11-16, ADR 0094): net cash below it is
 * marked ON SCREEN, in the accent and in words, never by Alfred unprompted. Optional; with none set
 * only below zero is marked. Cents, a whole number of dollars as he types it.
 *
 * **Phone-local, on purpose.** His other phone-side money preferences (the Money view's swipe hint,
 * the retired nominated account) are SharedPreferences, and the engine's synced money settings
 * (`FinancePolicy`) have no field for this: one would need a migration, and the floor only decides
 * how a screen marks a figure the engine already worked out. Open question in the ADR whether it
 * should follow him to the web.
 */
object NetCashFloor {
    private const val PREFS = "net_cash"
    private const val KEY = "floor_cents"

    private val state = MutableStateFlow<Long?>(null)
    private var seeded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The floor in cents, or null with none set. Seeded from disk on first read. */
    fun flow(context: Context): StateFlow<Long?> {
        if (!seeded) {
            val p = prefs(context)
            state.value = if (p.contains(KEY)) p.getLong(KEY, 0L) else null
            seeded = true
        }
        return state.asStateFlow()
    }

    fun get(context: Context): Long? = flow(context).value

    /** Sets the floor, or clears it with null. */
    fun set(context: Context, cents: Long?) {
        val edit = prefs(context).edit()
        if (cents == null) edit.remove(KEY) else edit.putLong(KEY, cents)
        edit.apply()
        state.value = cents
        seeded = true
    }

    /** "250" or "1,200.50" typed in dollars, as cents; null for blank or not a figure. Never a Double. */
    @Suppress("ReturnCount", "MagicNumber")
    fun parse(typed: String): Long? {
        val text = typed.trim().removePrefix("$").replace(",", "")
        if (text.isEmpty()) return null
        val match = Regex("""(\d{1,9})(?:\.(\d{1,2}))?""").matchEntire(text) ?: return null
        val dollars = match.groupValues[1].toLong()
        val cents = match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
        return dollars * 100 + cents
    }
}
