package com.kevin.legion.service.cascade

import android.content.Context
import com.kevin.legion.service.ListenCue
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * How Alfred answers a call (Kevin, 2026-11-24: Alfred answers like a person, "Hello, Alfred here",
 * varied, never the same greeting twice in a row).
 *
 * A pickup is scripted, never a model turn: a model-written greeting measured a median 2.3 s to
 * first audio on the 11-19 drive, and someone who takes two seconds to say hello is not answering a
 * call. The lines are short and dry, picked by [ListenCue]'s own picker, which already holds Kevin's
 * rules: a time-of-day line at most once per window per day, otherwise a random any-time line, and
 * never the same line twice in a row, remembered across process death in this pool's own prefs file
 * ([POOL_BUNDLE], `call_pickup`), so it can never silence the opening cue's pool.
 *
 * **The name is never hardcoded in what he says.** `{name}` is filled from the persona's own name at
 * pick time. A renamed persona has no rendered audio for a line that says the name, so such a line is
 * offered only while its audio is in hand; with none in hand the pool narrows to the lines that are
 * true under any name, instead of saying the wrong one.
 */
internal object CallPickup {

    /** This pool's own never-the-same-twice-in-a-row state (no clips are bundled for it yet). */
    val POOL_BUNDLE = ListenCue.Bundle(dir = "call_pickup", prefs = "call_pickup")

    /** One pickup line. [named] means the line says the persona's name, so a rename changes its audio. */
    data class Entry(val id: String, val text: String, val named: Boolean, val window: ListenCue.Window? = null)

    private const val NAME = "{name}"

    /** Early, so he is greeted as the morning rather than as any hour. */
    private val MORNING = ListenCue.Window.parse("05:00-10:59")

    /** Evening, kept clear of [NIGHT] so one call never sits in two windows. */
    private val EVENING = ListenCue.Window.parse("18:00-21:59")

    /** Late: a window may not wrap midnight ([ListenCue.Window]), so it ends with the day. */
    private val NIGHT = ListenCue.Window.parse("22:00-23:59")

    /**
     * The starting set, Kevin's to cut (plan 2.2). No exclamation, nothing like "How can I help you
     * today", and no em dash (checks.yml check (d)).
     */
    val POOL: List<Entry> = listOf(
        Entry("name_here", "$NAME here.", named = true),
        Entry("hello_name", "Hello. $NAME here.", named = true),
        Entry("name_speaking", "$NAME speaking.", named = true),
        Entry("yes_hello", "Yes, hello.", named = false),
        Entry("go_ahead", "Hello. Go ahead.", named = false),
        Entry("im_here", "I'm here.", named = false),
        Entry("morning_name", "Morning. $NAME here.", named = true, window = MORNING),
        Entry("evening_name", "Good evening. $NAME here.", named = true, window = EVENING),
        Entry("late_name", "$NAME here. Up late?", named = true, window = NIGHT),
    )

    /**
     * Every line of the pool under [name]. [ScriptedLines.KEEP] holds these for the register's own
     * name, and membership of that set is what lets the cloud voice keep a line's audio at all, so a
     * pickup costs one voice fetch the first time it comes up and plays from memory after that.
     */
    fun warmable(name: String): List<String> = POOL.map { fill(it.text, name) }

    /**
     * The line to say, with the pool's state remembered. [name] is the persona's live name
     * ([com.kevin.legion.ai.CompanionProfile.name]), [defaultName] the register's own
     * ([com.kevin.legion.ai.Personas]), [inHand] whether the voice can say a line at once from audio
     * it already keeps.
     */
    fun pick(
        context: Context,
        name: String,
        defaultName: String,
        inHand: (String) -> Boolean,
        now: LocalDateTime = LocalDateTime.now(),
        random: Random = Random.Default,
    ): String {
        val lines = offered(name, defaultName, inHand).map { ListenCue.Line(it.id, fill(it.text, name), it.window) }
        return ListenCue.pickAndRemember(context, POOL_BUNDLE, lines, now, random).text
    }

    /**
     * The lines this phone may say now: all of them under the register's own name; under a rename,
     * the ones that do not say the name plus any named line whose audio is already in hand.
     */
    fun offered(name: String, defaultName: String, inHand: (String) -> Boolean): List<Entry> {
        if (name.equals(defaultName, ignoreCase = true)) return POOL
        val kept = POOL.filter { !it.named || inHand(fill(it.text, name)) }
        return kept.ifEmpty { POOL.filter { !it.named } }
    }

    private fun fill(text: String, name: String): String = text.replace(NAME, name)
}
