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
 * it matches more than one car and the car is unknown (he is asked). Live values never go in.
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
            .filter { it.startsWith("4100") && it.length >= BITMASK_LINE && it.all { c -> c.isLetterOrDigit() } }
            .map { it.substring(0, BITMASK_LINE) }
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

    /**
     * [prints] after he says this connection is [carId]: the print becomes [carId]'s (replacing its
     * own older one). Another car that already has the same print keeps it: the print is then
     * AMBIGUOUS ([match] finds two cars and returns null), so twins are always asked, never stolen
     * from one another (2026-11-17 review).
     */
    fun learn(prints: Map<String, String>, carId: String, fp: String?): Map<String, String> {
        if (fp == null) return prints
        return prints + (carId to fp)
    }

    /** "4100" + four data bytes. */
    private const val BITMASK_LINE = 12

    // ---- the store: per car, on this phone (never synced; no Room schema change) -------------

    private const val PREFS = "car_fingerprint"

    fun all(context: Context): Map<String, String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun save(context: Context, carId: String, fp: String?) {
        if (fp == null) return
        val next = learn(all(context), carId, fp)
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        next.forEach { (k, v) -> edit.putString(k, v) }
        edit.apply()
    }
}
