package com.kevin.legion.engine

/**
 * What a computed field materializes to. A tagged union on purpose - [Empty] ("no children yet, so
 * MIN/MAX/LATEST genuinely has nothing to report") must never be confused with [Error] (ticket 04
 * answer point 4 / CLAUDE.md §4 rule 6's "never a silent zero", applied to arithmetic instead of
 * ingestion), and neither may be silently collapsed into a real numeric 0 by whatever renders them.
 */
sealed class ComputedValue {
    data class MoneyCents(val cents: Long) : ComputedValue()
    data class Number(val value: Double) : ComputedValue()
    data class Count(val count: Int) : ComputedValue()

    /** MIN/MAX/LATEST over zero contributing children - a true "nothing to report", not a 0 and
     * not an [Error]. SUM/AVG over zero children DO resolve to a real, correct numeric 0 (there is
     * nothing to sum and no field was deleted), so they never produce this. */
    object Empty : ComputedValue()

    /** [message] is rendered in words on every surface (ticket 04 answer point 4) - never
     * collapsed to 0, never swallowed. Produced by a source field that no longer exists, or a
     * same-record arithmetic divide-by-zero. */
    data class Error(val message: String) : ComputedValue()
}
