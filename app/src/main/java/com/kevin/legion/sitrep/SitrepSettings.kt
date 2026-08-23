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
 * would just be a second way to express the same state. The sitrep is never gated by
 * [com.kevin.legion.service.ProactiveBus] at all, by design, the same way
 * [com.kevin.legion.service.ProactiveBus.speakSolicited] is never gated: ticket 32 (Kevin,
 * "sitreps stay tap only or via voice activation only") retired the scheduled alarm that used to
 * be the one path routed through [com.kevin.legion.service.ProactiveCategory.DIGEST], so every
 * sitrep now happens the same way - asked for, this turn, never queued.
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

    /** The stored row, or null if Kevin has never curated a sender list (the row has never been
     * written). **`hour`/`minute` on the row are vestigial** - they belonged to the scheduled
     * alarm ticket 32 retired, and stay only because the row is a Room entity with non-null
     * columns and dropping them would need a real migration (CLAUDE.md §5: "an unused table is
     * cheaper than a destructive one" - the same call applies to unused columns on a table that
     * IS still used, here for its `senders` column). Nothing reads `hour`/`minute` anymore;
     * [setNewsletterSenders] writes `0`/`0` and no caller looks at what comes back. */
    private suspend fun row(context: Context): SitrepSchedule? =
        CarDatabase.getDatabase(context).sitrepScheduleDao().get()

    /** Newsletter sender addresses/domains, parsed from [SitrepSchedule.senders]' comma-separated
     * storage form - blank entries (a stray leading/trailing comma) are dropped rather than
     * passed through as an empty Gmail `from:()` term. Empty when no row exists yet. */
    suspend fun newsletterSenders(context: Context): List<String> =
        row(context)?.senders.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }

    /**
     * Writes the curated newsletter sender list - the only thing left in [SitrepSchedule] worth
     * writing since ticket 32 (Kevin: "sitreps stay tap only or via voice activation only")
     * retired the scheduled alarm [SitrepSchedule.hour]/[SitrepSchedule.minute] used to arm.
     * [senders] is joined back into the comma-separated storage form here so every OTHER caller
     * only ever deals in a real list.
     */
    suspend fun setNewsletterSenders(context: Context, senders: List<String>) {
        CarDatabase.getDatabase(context).sitrepScheduleDao()
            .put(SitrepSchedule(hour = 0, minute = 0, senders = senders.joinToString(",")))
    }

    /** Test seam: drops the loaded flag and the cached flow back to the fresh-install default. */
    internal fun resetForTest() {
        loaded = false
        _modules.value = SitrepModule.entries.associateWith { it in SitrepModule.DEFAULT_ON }
    }
}
