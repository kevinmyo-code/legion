package com.kevin.legion.vehicle

import android.content.Context

/**
 * A car with no VIN, recognised by what its own computer answers at connect (Kevin, 2026-11-17: "i
 * have one adapter that i move between cars. so we need a way to handle that."). The binding of the
 * adapter to a car belongs to the CONNECTION; nothing is remembered about the adapter. What is
 * remembered is about each CAR: the answers its ECUs give at every connect that do not change between
 * drives, so the next connect can say which car this is without asking.
 *
 * What the handshake already reads, before the car is decided ([ObdBluetoothManager]):
 * - `ATDPN`: the OBD protocol the adapter found (an older car on J1850 or ISO 9141 and a newer one on
 *   CAN differ here);
 * - `0100`: each answering ECU's supported-PID bitmask for PIDs 01 to 20 (a fixed property of that
 *   ECU's software; several ECUs answer with one line each).
 * Together: `p<protocol>|<bitmask lines, sorted>`. Two cars of one model and year may share it; then
 * it matches more than one car and the car is unknown (he is asked). Live values never go in. Adapter
 * chatter before an answer on the same line ("SEARCHING...", "BUS INIT: ...OK") is skipped.
 *
 * Known limit, kept strict on purpose (review A6, 2026-11-19): the print is the exact SET of ECUs that
 * answered. If a module answers on one connect and not the next (key on versus engine running, a
 * module asleep, a clone adapter that returns only the first answer), the print differs: the car is
 * not recognised that time and he is asked again (never a wrong car). Without headers (ATH1) the
 * engine ECU's line cannot be told apart from the others, so a looser match was not cheap.
 *
 * Not read today, the follow-up for a stronger print: the further PID windows (read after the
 * decision), Mode 09 calibration IDs and ECU names (09 04 / 09 0A; many pre-2005 cars do not answer),
 * and the answering ECU addresses (headers off by default, `ATH1` would show them).
 */
object CarFingerprint {
    /** The fingerprint for one connect, or null when the car did not really answer. */
    @Suppress("ReturnCount") // no protocol, no answer, or the print
    fun of(protocolCode: String?, supported0100: String?): String? {
        val proto = protocolCode?.trim()?.uppercase()?.removePrefix("A").orEmpty()
        if (proto.isEmpty() || proto == "0" || proto.any { !it.isLetterOrDigit() }) return null
        val lines = supported0100.orEmpty().uppercase().split('\r', '\n')
            .map { it.replace(" ", "").trim() }
            .map { it.substring(it.indexOf("4100").coerceAtLeast(0)) }
            .filter { it.startsWith("4100") && it.length >= BITMASK_LINE }
            .map { it.substring(0, BITMASK_LINE) }
            .filter { line -> line.all { it in '0'..'9' || it in 'A'..'F' } }
            .distinct()
            .sorted()
        if (lines.isEmpty()) return null
        return "p$proto|${lines.joinToString(",")}"
    }

    /** The one car whose learned print is exactly [fp], or null (none, or several: ask). */
    fun match(prints: Map<String, String>, fp: String?, onFile: Set<String>): String? {
        if (fp == null) return null
        return prints.filter { (car, p) -> p == fp && car in onFile }.keys.singleOrNull()
    }


    /** "4100" + four data bytes. */
    private const val BITMASK_LINE = 12

    // ---- the store: per car, on this phone (never synced; no Room schema change) -------------

    private const val PREFS = "car_fingerprint"

    fun all(context: Context): Map<String, String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [carId] learned [fp] (replacing its own older print; another car keeps its own): one key written. */
    fun save(context: Context, carId: String, fp: String?) {
        if (fp == null) return
        prefs(context).edit().putString(carId, fp).apply()
    }

    /**
     * His correction ("no, it's the B") of a connection recognised or named as [fromCarId]: that car
     * gives up [fp], so the print moves to the car he named rather than being copied (review A7).
     */
    fun takeFrom(context: Context, fromCarId: String, fp: String?) {
        val p = prefs(context)
        if (fp != null && p.getString(fromCarId, null) == fp) p.edit().remove(fromCarId).apply()
    }

    /** Forgets [carId]'s learned print (the car is archived: sold or gone). */
    fun forget(context: Context, carId: String) {
        prefs(context).edit().remove(carId).apply()
    }
}
