package com.kevin.legion.spokenbrief

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ProactiveSetting
import com.kevin.legion.service.ProactiveSettings

/**
 * The two briefs Alfred speaks without being asked (ADR 0082, Kevin 2026-11-10: yes to "spoken
 * brief at wake-up or arriving home"), and the registry the compulsion test reads for them.
 *
 * **A registry for these two only.** The older raises are built inline at their call sites and are
 * not listed here; `SpokenBriefsTest` checks that each entry DECLARES its anchor (clause a) and its
 * one-instruction silence (clause d), and that the silence phrase really does stop it. Clauses (b)
 * and (c) stay human-reviewed (CLAUDE.md section 7), and the prompts forbid absence and engagement
 * in words.
 *
 * Each brief has its own switch, inside the Digest category's (both raise as
 * [com.kevin.legion.service.ProactiveCategory.DIGEST]) and the master's. **Stored as rule-keyed rows
 * in the existing `proactive_settings` table**, beside the category rows, so there is no Room
 * version change: the table is (key, enabled), and [ProactiveSettings] reads only the keys it knows.
 * A missing row means on: the switch that matters on first use is Digest, which ships off.
 */
enum class SpokenBrief(
    /** The raise's rule id, its suppression key. Stable forever once a row exists under it. */
    val ruleId: String,
    /** Settings screen title. */
    val title: String,
    /** One line under it, saying when it speaks. */
    val blurb: String,
    /** Compulsion test (a): the fact he could verify himself. */
    val anchor: String,
    /** Compulsion test (d): the one instruction that silences it forever. */
    val silenceInstruction: String,
    /** Names a spoken "stop the ..." may use for it. */
    val names: List<String>,
) {
    WAKE(
        ruleId = "wake_brief",
        title = "Morning brief",
        blurb = "Said when you dismiss a morning alarm (4 to 11 am), once a day. Brief me on the ring " +
            "screen replays it.",
        anchor = "you dismissed an alarm you set, between 04:00 and 11:00",
        silenceInstruction = "stop the morning brief",
        names = listOf("morning brief", "wake up brief", "wake-up brief", "wakeup brief", "alarm brief", "wake brief"),
    ),
    HOME(
        ruleId = "home_brief",
        title = "Home brief",
        blurb = "A short one when you get to the place tagged home: what is still due today, tomorrow's " +
            "first event, what is waiting. At most once every 6 hours.",
        anchor = "you arrived at the place you tagged home, after leaving it",
        silenceInstruction = "stop the home brief",
        names = listOf("home brief", "arrival brief", "arriving home brief", "welcome home brief"),
    ),
    ;

    /** The `proactive_settings` key. Prefixed so it can never collide with a category key. */
    val settingKey: String get() = "rule:$ruleId"
}

object SpokenBriefs {

    /** On unless switched off. Loads [ProactiveSettings] first so its first-run seed is never skipped. */
    suspend fun isOn(context: Context, brief: SpokenBrief): Boolean {
        ProactiveSettings.load(context)
        return CarDatabase.getDatabase(context).proactiveSettingDao().enabled(brief.settingKey) ?: true
    }

    suspend fun set(context: Context, brief: SpokenBrief, on: Boolean) {
        ProactiveSettings.load(context)
        CarDatabase.getDatabase(context).proactiveSettingDao().put(ProactiveSetting(brief.settingKey, on))
    }

    /**
     * Which brief [spoken] names, or null. Pure: lower-cased, punctuation and "the"/"my" dropped,
     * then matched against each brief's [SpokenBrief.names].
     */
    fun match(spoken: String): SpokenBrief? {
        val norm = spoken.lowercase().replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\b(the|my|your|stop|please)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (norm.isEmpty()) return null
        return SpokenBrief.entries.firstOrNull { b ->
            b.names.any { name -> norm == name.replace("-", " ") || norm.contains(name.replace("-", " ")) }
        }
    }

    /** What a spoken stop did, in words. [ok] is false when nothing was changed. */
    data class StopResult(val ok: Boolean, val message: String)

    /**
     * "stop the morning brief": switches that brief off for good. The same switch as the row on
     * Settings, Proactive speech (ADR 0035's hands path), which is where it comes back on.
     */
    suspend fun stop(context: Context, spoken: String): StopResult {
        val brief = match(spoken) ?: return StopResult(
            false,
            "No spoken line called \"$spoken\". The ones that stop this way are the morning brief and the home " +
                "brief. Nothing was changed.",
        )
        set(context, brief, false)
        return StopResult(
            true,
            "The ${brief.title.lowercase()} is off and will not be said again. It comes back on in Settings, " +
                "Proactive speech.",
        )
    }
}
