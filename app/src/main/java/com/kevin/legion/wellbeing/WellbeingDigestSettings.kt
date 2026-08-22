package com.kevin.legion.wellbeing

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.WellbeingDigestSchedule

/**
 * The wellbeing digest's stored schedule time - goal-plans ticket 05. Modelled directly on
 * [com.kevin.legion.sitrep.SitrepSettings]'s own schedule half, minus the module-switch machinery
 * that domain needs and this one does not: the wellbeing digest has exactly one thing to configure
 * (when it fires), not a set of independently-togglable sections.
 *
 * **No master on/off of its own.** The real kill switch is
 * [com.kevin.legion.service.ProactiveCategory.WELLBEING]'s own switch inside
 * [com.kevin.legion.service.ProactiveSettings], which every raise through
 * [com.kevin.legion.service.ProactiveBus] already respects - a second boolean here would just be a
 * second way to express "do not speak this," and [com.kevin.legion.service.ProactiveBus]'s own
 * class doc already states settled decision 2's "no exemptions" rule. A missing schedule row is
 * read as "never armed," not as "off" - the same distinction [SitrepSettings.schedule] draws.
 */
object WellbeingDigestSettings {
    /** The stored schedule, or null if Kevin has never set one -
     * [com.kevin.legion.wellbeing.WellbeingDigestScheduler] treats null as "nothing to arm". */
    suspend fun schedule(context: Context): WellbeingDigestSchedule? =
        CarDatabase.getDatabase(context).wellbeingDigestScheduleDao().get()

    /** Writes the schedule time. One row, matching [WellbeingDigestSchedule]'s own singleton
     * shape. */
    suspend fun setSchedule(context: Context, hour: Int, minute: Int) {
        CarDatabase.getDatabase(context).wellbeingDigestScheduleDao()
            .put(WellbeingDigestSchedule(hour = hour, minute = minute))
    }
}
