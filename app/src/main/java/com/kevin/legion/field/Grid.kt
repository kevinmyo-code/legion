package com.kevin.legion.field

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A UTM position: [zone] 1-60, [band] the MGRS latitude band letter, metres east and north. */
data class UtmCoord(val zone: Int, val band: Char, val easting: Double, val northing: Double) {
    val north: Boolean get() = band >= 'N'

    /** "14R 552612E 3316541N", the way a paper map's grid reads. */
    fun text(): String = "$zone$band ${easting.toLong()}E ${northing.toLong()}N"
}

/**
 * WGS84 latitude and longitude to UTM and MGRS, on the phone, offline (field navigation, round 5).
 * Written for LEGION from the published USGS formulas (Snyder, *Map Projections: A Working Manual*,
 * USGS Professional Paper 1395, 1987, pp. 57-64) and the MGRS lettering in NGA's grid reference
 * standard (NGA.SIG.0012); no third-party code, so no licence beyond the repo's own.
 *
 * Accurate to well under a metre inside a zone, which is far below GPS error. Covers latitude 80 S
 * to 84 N, the UTM range, with the Norway (32V) and Svalbard (31X-37X) zone exceptions. Outside it
 * the polar UPS grid applies, which this does not do: [utm] and [mgrs] return null there, and the
 * screen says so rather than printing a wrong grid.
 *
 * MGRS digits are TRUNCATED, never rounded, per the standard: "31N AA 66021 00000" names the 1 m
 * square the point is in. `GridTest` checks it against an independent library's output.
 */
object Grid {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257_223_563
    private const val K0 = 0.9996
    private const val FALSE_EASTING = 500_000.0
    private const val FALSE_NORTHING_SOUTH = 10_000_000.0
    private val E2 = F * (2 - F)
    private val EP2 = E2 / (1 - E2)
    private const val DEG = PI / 180.0

    const val MIN_LAT = -80.0
    const val MAX_LAT = 84.0
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private const val ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"
    private val COLUMN_SETS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val HUNDRED_KM = 100_000.0

    fun zoneOf(lat: Double, lon: Double): Int {
        val lonN = normalizeLon(lon)
        var zone = floor((lonN + 180.0) / 6.0).toInt() + 1
        if (zone > 60) zone = 60
        // Norway: 32V is widened west over 3-12 E.
        if (lat >= 56.0 && lat < 64.0 && lonN >= 3.0 && lonN < 12.0) zone = 32
        // Svalbard: only the odd zones 31, 33, 35 and 37 exist in band X.
        if (lat >= 72.0 && lat <= 84.0) {
            zone = when {
                lonN >= 0.0 && lonN < 9.0 -> 31
                lonN >= 9.0 && lonN < 21.0 -> 33
                lonN >= 21.0 && lonN < 33.0 -> 35
                lonN >= 33.0 && lonN < 42.0 -> 37
                else -> zone
            }
        }
        return zone
    }

    fun bandOf(lat: Double): Char? {
        if (lat < MIN_LAT || lat > MAX_LAT) return null
        val index = floor((lat - MIN_LAT) / 8.0).toInt().coerceAtMost(BANDS.length - 1)
        return BANDS[index]
    }

    fun utm(lat: Double, lon: Double): UtmCoord? {
        val band = bandOf(lat) ?: return null
        val zone = zoneOf(lat, lon)
        val lon0 = (zone - 1) * 6.0 - 180.0 + 3.0
        val phi = lat * DEG
        val n = A / sqrt(1 - E2 * sin(phi).pow(2))
        val t = tan(phi).pow(2)
        val c = EP2 * cos(phi).pow(2)
        val a = cos(phi) * (normalizeLon(lon - lon0)) * DEG
        val m = meridianArc(phi)
        val easting = K0 * n * (
            a + (1 - t + c) * a.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a.pow(5) / 120
            ) + FALSE_EASTING
        var northing = K0 * (
            m + n * tan(phi) * (
                a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a.pow(6) / 720
                )
            )
        if (lat < 0) northing += FALSE_NORTHING_SOUTH
        return UtmCoord(zone, band, easting, northing)
    }

    /** Back to latitude and longitude (degrees), for round-trip checks. */
    fun toLatLon(coord: UtmCoord): Pair<Double, Double> {
        val x = coord.easting - FALSE_EASTING
        val y = if (coord.north) coord.northing else coord.northing - FALSE_NORTHING_SOUTH
        val lon0 = (coord.zone - 1) * 6.0 - 180.0 + 3.0
        val m = y / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val n1 = A / sqrt(1 - E2 * sin(phi1).pow(2))
        val t1 = tan(phi1).pow(2)
        val c1 = EP2 * cos(phi1).pow(2)
        val r1 = A * (1 - E2) / (1 - E2 * sin(phi1).pow(2)).pow(1.5)
        val d = x / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (
            d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lon = (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cos(phi1)
        return lat / DEG to normalizeLon(lon0 + lon / DEG)
    }

    /** "31N AA 66021 00000" at [digits] per axis (5 = 1 m, 4 = 10 m ... 1 = 10 km), or null
     * outside 80 S to 84 N. */
    fun mgrs(lat: Double, lon: Double, digits: Int = 5): String? {
        val coord = utm(lat, lon) ?: return null
        require(digits in 1..5) { "MGRS precision is 1 to 5 digits per axis" }
        val column = COLUMN_SETS[(coord.zone - 1) % 3]
        val colIndex = floor(coord.easting / HUNDRED_KM).toInt() - 1
        val rowOffset = if (coord.zone % 2 == 0) 5 else 0
        val rowIndex = (floor(coord.northing / HUNDRED_KM).toInt() + rowOffset).mod(ROW_LETTERS.length)
        if (colIndex !in column.indices) return null
        val divisor = 10.0.pow(5 - digits)
        val e = floor((coord.easting % HUNDRED_KM) / divisor).toLong()
        val n = floor((coord.northing % HUNDRED_KM) / divisor).toLong()
        val fmt = "%0${digits}d"
        return "${coord.zone}${coord.band} ${column[colIndex]}${ROW_LETTERS[rowIndex]} " +
            "${fmt.format(e)} ${fmt.format(n)}"
    }

    private fun meridianArc(phi: Double): Double {
        val e4 = E2 * E2
        val e6 = e4 * E2
        return A * (
            (1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi)
            )
    }

    private fun normalizeLon(lon: Double): Double {
        var x = (lon + 180.0) % 360.0
        if (x < 0) x += 360.0
        val out = x - 180.0
        return if (abs(out + 180.0) < 1e-12 && lon > 0) 180.0 else out
    }
}
