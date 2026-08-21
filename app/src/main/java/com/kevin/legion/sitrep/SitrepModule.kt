package com.kevin.legion.sitrep

/**
 * The four sections the sitrep can compose (ticket 22,
 * `.scratch/hands-and-senses/issues/22-build-the-sitrep.md`). **Ship-list decided at build time**
 * (the ticket's own "decide while building" note): calendar, weather and fleet are all already
 * built as reads elsewhere in the app, and ledger anomalies / pantry lows are NOT - inventing
 * either here would be a second scoping effort the ticket explicitly declined to fold in.
 *
 * Mirrors [com.kevin.legion.service.ProactiveCategory]'s shape (a stable storage [key] plus a
 * settings-screen [title]/[blurb]) rather than reinventing a second vocabulary for "a switch with
 * a label" - see that enum's own doc comment for why key/value Room over SharedPreferences, and
 * [com.kevin.legion.data.local.SitrepModuleSetting] for the entity these keys are stored under.
 *
 * **All four sections are DETERMINISTIC except [NEWS]** (ticket 08's resolution §3: "a model
 * choosing what to omit from fleet/ledger facts is a model deciding what Kevin does not hear").
 * [NEWS] is the one exception because a newsletter body is genuinely prose that needs summarizing,
 * not a figure that could instead just be stated - see [SitrepBuilder]'s own class doc.
 */
enum class SitrepModule(
    /** Stable storage key. Written to `sitrep_modules.key` - never rename once a row exists. */
    val key: String,
    /** Row title on the settings screen. */
    val title: String,
    /** One line under the title saying what this module actually reports. */
    val blurb: String,
) {
    CALENDAR("calendar", "Calendar", "What's on your calendar in the next 24 hours."),
    WEATHER("weather", "Weather", "Current conditions at your location."),
    FLEET("fleet", "Fleet", "Maintenance due, open trouble codes, and the odometer."),
    /** The only module an LLM ever touches - see this enum's own class doc and
     * [SitrepBuilder]'s. Off by default until Kevin curates [com.kevin.legion.data.local
     * .SitrepSchedule.senders] himself (ticket 08 §6: "curated by Kevin, by hand"). */
    NEWS("news", "Newsletters", "A short summary of your newsletters from the last day."),
    ;

    companion object {
        fun fromKey(key: String): SitrepModule? = entries.firstOrNull { it.key == key }

        /** What a FRESH install seeds - CALENDAR/WEATHER/FLEET on (all three are read-only, local
         * or keyless, and cost nothing to leave on), NEWS off because it has no sender list yet
         * and would otherwise silently run a Gmail fetch nobody configured. */
        val DEFAULT_ON = setOf(CALENDAR, WEATHER, FLEET)
    }
}
