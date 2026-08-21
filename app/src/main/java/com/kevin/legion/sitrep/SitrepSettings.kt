package com.kevin.legion.sitrep

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.SitrepModuleSetting
import com.kevin.legion.data.local.SitrepSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sitrep's module switches and its schedule (ticket 22) - same load-on-first-read, seed-once
 * shape as [com.kevin.legion.service.ProactiveSettings], which this file is modelled on directly
 * rather than reusing (that object is typed to [com.kevin.legion.service.ProactiveCategory] and
 * carries the master-switch/quiet-hours vocabulary this domain has no use for - see
 * [SitrepModule]'s own doc for why a parallel, smaller object was preferred to widening that one).
 *
 * **No master kill switch here.** The sitrep's OFF state is "every module disabled", which
 * [SitrepBuilder.build] already reads as "nothing to report" - a second boolean layered on top
 * would just be a second way to express the same state. The actual on-air kill switch for the
 * SCHEDULED delivery is [com.kevin.legion.service.ProactiveCategory.DIGEST]'s own master-ANDed
 * switch in [com.kevin.legion.service.ProactiveSettings] (ticket 08 §1: "This fills the Digest
 * category ... the master kill switch, quiet hours, the daily cap ... apply to it for free") - the
 * ASKABLE-any-hour path in [SitrepBuilder] never goes through that gate at all, by design, the same
 * way [com.kevin.legion.service.ProactiveBus.speakSolicited] is never gated.
 */
object SitrepSettings {

    private val _modules = MutableStateFlow<Map<SitrepModule, Boolean>>(
        SitrepModule.entries.associateWith { it in SitrepModule.DEFAULT_ON }
    )
    val modules: StateFlow<Map<SitrepModule, Boolean>> = _modules.asStateFlow()

    @Volatile private var loaded = false

    /** Loads the module switches, seeding [SitrepModule.DEFAULT_ON] on the very first run. Safe to
     * call more than once; only the first call touches the database. */
    suspend fun load(context: Context) {
        if (loaded) return
        val dao = CarDatabase.getDatabase(context).sitrepModuleSettingDao()
        var rows = dao.all()
        if (rows.isEmpty()) {
            dao.putAll(SitrepModule.entries.map { SitrepModuleSetting(it.key, it in SitrepModule.DEFAULT_ON) })
            rows = dao.all()
        }
        val byKey = rows.associateBy { it.key }
        _modules.value = SitrepModule.entries.associateWith { byKey[it.key]?.enabled == true }
        loaded = true
    }

    suspend fun setModule(context: Context, module: SitrepModule, on: Boolean) {
        CarDatabase.getDatabase(context).sitrepModuleSettingDao().put(SitrepModuleSetting(module.key, on))
        _modules.value = _modules.value + (module to on)
    }

    /** Every module currently switched on, per the in-memory flow - [load] is what makes this
     * true; a caller that skips it sees the class-default seed instead of the stored value. */
    fun enabledModules(): Set<SitrepModule> = _modules.value.filterValues { it }.keys

    /** The stored schedule, or null if Kevin has never set one (the settings row has never been
     * written) - [com.kevin.legion.sitrep.SitrepScheduler] treats null as "nothing to arm". */
    suspend fun schedule(context: Context): SitrepSchedule? =
        CarDatabase.getDatabase(context).sitrepScheduleDao().get()

    /** Newsletter sender addresses/domains, parsed from [SitrepSchedule.senders]' comma-separated
     * storage form - blank entries (a stray leading/trailing comma) are dropped rather than
     * passed through as an empty Gmail `from:()` term. Empty when no schedule row exists yet. */
    suspend fun newsletterSenders(context: Context): List<String> =
        schedule(context)?.senders.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }

    /**
     * Writes the schedule time and sender list in one row (ticket 22's own call to keep them
     * together - see [SitrepSchedule]'s doc). [senders] is joined back into the comma-separated
     * storage form here so every OTHER caller only ever deals in a real list.
     */
    suspend fun setSchedule(context: Context, hour: Int, minute: Int, senders: List<String>) {
        CarDatabase.getDatabase(context).sitrepScheduleDao()
            .put(SitrepSchedule(hour = hour, minute = minute, senders = senders.joinToString(",")))
    }

    /** Test seam: drops the loaded flag and the cached flow back to the fresh-install default. */
    internal fun resetForTest() {
        loaded = false
        _modules.value = SitrepModule.entries.associateWith { it in SitrepModule.DEFAULT_ON }
    }
}
