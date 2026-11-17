package com.kevin.legion.service.checkin

import android.content.Context
import kotlin.random.Random

/**
 * What Alfred says when he asked a check-in question and heard nothing back (Kevin, 2026-11-15:
 * *"use a closing line. Can be scripted idk. Like, 'ok sure, I'll get out of ur way' or something
 * witty like that"*).
 *
 * Scripted, never generated: no model call. Each line is in
 * [com.kevin.legion.service.cascade.ScriptedLines.KEEP], so either voice keeps its audio on disk
 * once said ([com.kevin.legion.service.cascade.KeptLineAudio]); from the second time per voice it
 * costs nothing and plays at once. Never the same line twice in a row, remembered across app starts.
 *
 * The register (CLAUDE.md section 1, `ai/Personas.kt`): dry, short, a butler stepping back. Never a
 * name (the persona's name is swappable), never a promise ("I'll ask later" would be an outcome he
 * cannot check), never a word about being ignored or about his silence (compulsion test, clause c).
 */
object ClosingLines {
    val LINES: List<String> = listOf(
        "Very good. I'll get out of your way.",
        "Another time, then. <short pause>",
        "Quite all right. As you were.",
        "No matter. Carry on.",
        "Understood. I'll leave you to it.",
        "Right. <chuckle> I'll make myself scarce.",
    )

    /** Any line but [last]. */
    fun pick(last: String?, random: Random = Random.Default, lines: List<String> = LINES): String {
        val choices = lines.filter { it != last }.ifEmpty { lines }
        return choices[random.nextInt(choices.size)]
    }

    private const val PREFS = "check_in_closing"
    private const val KEY_LAST = "last"

    /** The next line, remembering it so the one after differs. */
    fun next(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = pick(prefs.getString(KEY_LAST, null))
        prefs.edit().putString(KEY_LAST, line).apply()
        return line
    }
}
