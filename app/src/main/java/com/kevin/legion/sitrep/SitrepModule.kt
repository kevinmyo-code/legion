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
     * [SitrepBuilder]'s. [com.kevin.legion.data.local.SitrepSchedule.senders] remains an OVERRIDE
     * a driver can curate by hand (ticket 08 §6), but is no longer a PREREQUISITE - command-center
     * ticket 12 (Kevin: "take from my gmail > summarize") gave [SitrepBuilder] a safe no-config
     * default query, which is what makes NEWS default-on below correct now where it once was not. */
    NEWS("news", "Newsletters", "A short summary of your newsletters from the last day."),
    ;

    companion object {
        fun fromKey(key: String): SitrepModule? = entries.firstOrNull { it.key == key }

        /** What a FRESH install seeds - all four modules on. NEWS joined the other three
         * (command-center ticket 12) once [SitrepBuilder]'s no-config default query existed: the
         * old reasoning for leaving it off - "no sender list yet, would otherwise silently run a
         * Gmail fetch nobody configured" - no longer applies, because NEWS only ever runs from an
         * explicit ask (a tap on [com.kevin.legion.ui.TodayScreen]'s newsletters card, a spoken
         * `get_sitrep`, or a schedule Kevin himself set), never a silent background poll, with or
         * without a curated sender list. */
        val DEFAULT_ON = setOf(CALENDAR, WEATHER, FLEET, NEWS)
    }
}
