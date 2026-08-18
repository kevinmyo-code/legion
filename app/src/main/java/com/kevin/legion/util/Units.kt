package com.kevin.legion.util

import android.content.Context

/**
 * The one place a stored temperature turns into text (2026-08-18,
 * `.scratch/drive-ui/issues/07-temperature-units.md`, amended by Kevin the same day to make the
 * unit a setting rather than a fixed Celsius).
 *
 * **The bug this exists to end.** The same coolant reading rendered three ways on three surfaces,
 * verified on-device 2026-08-16: `81 C` on the UPLINK gauge, `45 C` on the FAULTS freeze frame,
 * `177 F` on the DRIVE MODE pod, and the assistant spoke Fahrenheit over the top of a Celsius
 * screen. Every one of those sites did its own `* 9 / 5 + 32` inline, which is precisely how three
 * answers to one question happened. The ticket's answer point 5 is the rule this file enforces:
 * the conversion moves out of the call sites and one formatter owns it.
 *
 * **Storage never changes.** Every temperature in Room, in `OdbSample`, in a freeze frame, and in
 * [com.kevin.legion.vehicle.PidSpec] stays in Celsius, because Celsius is what the PID itself
 * reports - converting at the storage layer would mean a row's meaning depended on the setting in
 * force when it was written, which is unfixable after the fact. The setting is a RENDERING choice
 * and applies at the last possible moment.
 *
 * **Spoken output uses this too, not a parallel path.** Ticket answer point 4: the assistant
 * contradicting the screen out loud is the same defect as two screens contradicting each other.
 * Anything the model is handed, or that reaches the speaker, formats through here.
 *
 * **Not swept: distance and speed.** Ticket answer point 3 keeps them imperial - miles and mph -
 * matching the odometer, DRIVES, the recaps and a US driver. Celsius-by-default beside imperial
 * distance is a deliberate mixed system, not an oversight, and it is why this file is about
 * temperature specifically rather than a general unit system.
 */
enum class TempUnit(
    /** Stable storage key. Never [name] or [ordinal] - a reordered enum must not silently
     * re-interpret a saved preference. */
    val key: String,
    /** What a rendered figure ends with, e.g. `82°C`. */
    val symbol: String,
    /** How the unit is said rather than shown, for a sentence the assistant speaks. */
    val spokenWord: String,
) {
    CELSIUS("c", "°C", "Celsius"),
    FAHRENHEIT("f", "°F", "Fahrenheit"),
    ;

    companion object {
        /**
         * Celsius is the default, and the reason is the ticket's answer point 2: it already
         * matched two of the three disagreeing surfaces and it is the raw PID value, so it is the
         * smallest correct change. A US driver who wants Fahrenheit now has a switch instead of a
         * rebuild.
         */
        val DEFAULT = CELSIUS

        /** Resolves a stored [key], falling back to [DEFAULT] for anything unrecognised - a
         * corrupted preference must render a temperature, not crash a gauge. */
        fun fromKey(key: String?): TempUnit = values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * The driver's chosen temperature unit, and every conversion and rendering that depends on it.
 *
 * **Device-local, in its own SharedPreferences file**, the same shape and for the same reason as
 * [com.kevin.legion.ai.ActiveCompanionProfile]: two phones share one Google account, and which
 * unit one driver reads a gauge in is not a fact about the cars that should follow the data to the
 * other phone.
 */
object Temp {
    private const val PREFS = "unit_preferences"
    private const val KEY_TEMP_UNIT = "temp_unit"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The unit every surface renders in right now. */
    fun unit(context: Context): TempUnit =
        TempUnit.fromKey(prefs(context).getString(KEY_TEMP_UNIT, null))

    /** Switches the unit for every surface at once. Takes effect on the next render or reading;
     * nothing stored is rewritten, because nothing stored was ever in the display unit. */
    fun setUnit(context: Context, unit: TempUnit) {
        prefs(context).edit().putString(KEY_TEMP_UNIT, unit.key).apply()
    }

    /**
     * A Celsius reading converted into [unit]. Pure, and public so a caller that needs the NUMBER
     * (a gauge scale, a chart axis, a threshold comparison) uses the same arithmetic as the caller
     * that needs the string, rather than re-deriving it.
     */
    fun convert(celsius: Double, unit: TempUnit): Double =
        if (unit == TempUnit.FAHRENHEIT) celsius * 9.0 / 5.0 + 32.0 else celsius

    /** [convert] against the driver's current setting. */
    fun convert(context: Context, celsius: Double): Double = convert(celsius, unit(context))

    /**
     * A Celsius reading as glanceable text in the driver's unit: `82°C`, `180°F`.
     *
     * [decimals] defaults to 0 because every temperature surface in the app today is a whole
     * number - a tenth of a degree of coolant is noise, not signal.
     */
    fun text(context: Context, celsius: Double, decimals: Int = 0): String =
        text(celsius, unit(context), decimals)

    /** [text] against an explicit unit - the form a unit test and a preview can call without a
     * SharedPreferences read. */
    fun text(celsius: Double, unit: TempUnit, decimals: Int = 0): String =
        "%.${decimals}f%s".format(convert(celsius, unit), unit.symbol)

    /**
     * A Celsius reading as words, for a sentence the assistant says or hands to the model:
     * `82 degrees Celsius`. Same number as [text] by construction - they share [convert] - so the
     * assistant can never speak a figure that disagrees with the one on screen, which is the
     * failure ticket 07 answer point 4 names.
     */
    fun spoken(context: Context, celsius: Double, decimals: Int = 0): String =
        spoken(celsius, unit(context), decimals)

    /** [spoken] against an explicit unit. */
    fun spoken(celsius: Double, unit: TempUnit, decimals: Int = 0): String =
        "%.${decimals}f degrees %s".format(convert(celsius, unit), unit.spokenWord)

    /**
     * True when [unitLabel] - a stored `OdbSample.unit` or a [com.kevin.legion.vehicle.PidSpec]
     * unit string - names a temperature and therefore needs converting before it is rendered.
     *
     * Both spellings are accepted because both are in the tree: `PidSpec` and `TelemetryRecorder`
     * write `°C`, while older sample rows and one freeze-frame path carry a bare `C`. A label this
     * does not recognise is left alone, so a non-temperature series (rpm, V, g/s) passes through
     * untouched rather than being silently scaled.
     */
    fun isCelsiusLabel(unitLabel: String?): Boolean =
        unitLabel?.trim()?.uppercase() in setOf("°C", "C", "DEG C", "DEGC")

    /** The label a converted series should carry, for a chart axis or a legend. Returns
     * [unitLabel] unchanged when it is not a temperature. */
    fun labelFor(context: Context, unitLabel: String?): String =
        if (isCelsiusLabel(unitLabel)) unit(context).symbol else unitLabel.orEmpty()
}
