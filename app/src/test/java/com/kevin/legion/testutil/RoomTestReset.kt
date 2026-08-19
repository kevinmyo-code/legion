package com.kevin.legion.testutil

import com.kevin.legion.data.local.CarDatabase

/**
 * Forces [CarDatabase.getDatabase]'s process-static singleton to rebuild on
 * its next call.
 *
 * **Why this exists.** Robolectric resets its native SQLite shadow layer
 * per @Test METHOD, but [CarDatabase]'s `INSTANCE` is a plain Kotlin object
 * singleton that survives across methods within a class run. Reusing a
 * [CarDatabase] opened against a PREVIOUS method's now-torn-down shadow layer
 * throws `IllegalStateException: Illegal connection pointer N` the moment any
 * DAO touches it - not a real bug in [CarDatabase], just a JVM-static
 * singleton meeting a per-method-reset test double. Call this from an
 * `@Before` in any Robolectric test that reaches [CarDatabase.getDatabase]
 * (directly, or transitively via a controller), so each test method gets a
 * genuinely fresh instance built against ITS OWN Robolectric application.
 */
object RoomTestReset {
    fun resetCarDatabaseSingleton() {
        // The Kotlin compiler places a private companion-object field like
        // `INSTANCE` as a STATIC field on the ENCLOSING class (CarDatabase),
        // not on CarDatabase$Companion - confirmed via javap, not assumed.
        val instanceField = CarDatabase::class.java.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        val existing = instanceField.get(null) as CarDatabase?
        existing?.let { runCatching { it.close() } }
        instanceField.set(null, null)
    }
}
