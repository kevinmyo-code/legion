package com.kevin.legion.brief

import android.content.Context
import com.kevin.legion.sitrep.SitrepBuilder
import com.kevin.legion.sitrep.SitrepModule
import com.kevin.legion.sitrep.SitrepSettings

/** One phone-side section, as the sitrep code composed it. Deterministic, never stored. */
data class PhoneSection(val title: String, val text: String)

/**
 * The phone's live half of the brief (ticket 02, ruling 7: "followed by the phone's live calendar
 * and weather sections"). A seam so the controller is testable without Room or a GPS fix.
 */
interface BriefPhone {
    /** Calendar and weather, narrowed by [filter] (null means both), each only if switched on. */
    suspend fun sections(filter: Set<SitrepModule>?): List<PhoneSection>

    /** True when the sitrep's Gmail newsletter module is switched on in settings. */
    suspend fun newslettersEnabled(): Boolean

    /**
     * The newsletter summary, fetched now. **Read-through** (CLAUDE.md section 7, ruling 5): the
     * caller shows or speaks it and drops it. It never reaches [BriefCache] or the engine.
     */
    suspend fun newsletters(): String
}

/**
 * [SitrepBuilder]'s own sections, unchanged: the same calendar and weather sentences `get_sitrep`
 * has always spoken. Phone FLEET is not used: the engine's Car section replaces it.
 */
class SitrepBriefPhone(private val context: Context) : BriefPhone {

    override suspend fun sections(filter: Set<SitrepModule>?): List<PhoneSection> {
        val wanted = (filter ?: LIVE).intersect(LIVE)
        if (wanted.isEmpty()) return emptyList()
        val built = SitrepBuilder.sections(context, wanted)
        return LIVE.mapNotNull { module ->
            built[module]?.let { PhoneSection(module.title, it.removePrefix("${module.name} ")) }
        }
    }

    override suspend fun newslettersEnabled(): Boolean {
        SitrepSettings.load(context)
        return SitrepModule.NEWS in SitrepSettings.enabledModules()
    }

    override suspend fun newsletters(): String =
        SitrepBuilder.sections(context, setOf(SitrepModule.NEWS))[SitrepModule.NEWS]
            ?: "Newsletters are switched off in sitrep settings, so none were checked."

    private companion object {
        /** In the order the modal and the voice result show them. */
        val LIVE = linkedSetOf(SitrepModule.CALENDAR, SitrepModule.WEATHER)
    }
}
