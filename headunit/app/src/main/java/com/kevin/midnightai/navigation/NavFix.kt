package com.kevin.midnightai.navigation

import android.location.Location

/** Which fix a route may start from (review NAV-2). */
object NavFix {
    /** The same 3 s staleness the CAR face uses (CruiseLayoutLogic.STALE_MS). */
    const val FRESH_MS = 3_000L

    /** The route origin: [fix] only when it is under [FRESH_MS] old on the monotonic clock, else null. */
    fun freshOrigin(fix: Location?, nowNanos: Long): GeoPoint? {
        fix ?: return null
        val ageMs = (nowNanos - fix.elapsedRealtimeNanos) / 1_000_000L
        return if (ageMs in 0..FRESH_MS) GeoPoint(fix.latitude, fix.longitude) else null
    }
}
