package com.kevin.legion.ai

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The three user-facing reset scopes offered in the control panel. Each is
 * destructive and irreversible; the UI confirms before calling. The companion
 * and factory resets leave the app needing onboarding again (the caller flips
 * [com.kevin.legion.ui.OnboardingState]).
 */
object CompanionReset {
    /** Clears only the long-term memories; keeps the persona, voice, and all other data. */
    suspend fun resetMemories(context: Context) {
        val db = CarDatabase.getDatabase(context)
        db.memoryDao().deleteAll()
        // Companion-memory ticket 01 (2026-07-22): the consolidated/reflected
        // table and the raw unconsolidated buffer are both "what it remembers"
        // from the driver's point of view, even though neither is the legacy
        // MemoryEntry table this reset originally targeted - "forget
        // everything" must clear all three or it's a lie.
        db.companionMemoryDao().deleteAll()
        db.episodicTurnDao().deleteAll()
    }

    /**
     * Wipes the companion's identity (name, personality, voice) and the
     * driver's About-You profile, so onboarding can build a fresh persona. Keeps
     * memories, saved places, the to-do list, and per-car maintenance history.
     *
     * No avatar/background/car-image deletion anymore - AvatarStudio (generated
     * art) was retired with the city-pop design language in the 2026-07-31 pivot.
     */
    suspend fun resetCompanion(context: Context) = withContext(Dispatchers.IO) {
        CompanionProfile.clear(context)
        DriverProfile.clear(context)
    }

    /**
     * Full factory reset: the companion reset above plus every Room table
     * (memories, saved places, to-do, and all vehicles' maintenance/odometer/
     * service history) - the app as if freshly installed.
     */
    suspend fun factoryReset(context: Context) = withContext(Dispatchers.IO) {
        resetCompanion(context)
        CarDatabase.getDatabase(context).clearAllTables()
    }
}
