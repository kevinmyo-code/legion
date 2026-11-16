package com.kevin.legion.ui.assistant

import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.Reading
import com.kevin.legion.assistant.cards.RowStatus
import com.kevin.legion.assistant.cards.StatusLevel
import com.kevin.legion.assistant.cards.StatusMeter

/**
 * The words beside a card's pictures (2026-11-15): its headline, a split's parts, a meter's amount
 * and a level, the same sentences the web says (`types.ts`) and `shared/cards/fixtures.json` pins.
 */
internal object CardPictureWords {

    private val AT = Regex("""^(\d{4}-\d{2}-\d{2})(?:T(\d{2}:\d{2}))?$""")

    /** A card's big line: what it answers at a glance. [attention] sets it in the accent. */
    data class Headline(val label: String?, val value: String, val attention: Boolean = false)

    /** The lead item's label and words; null when the card names no lead. */
    fun headline(card: ConversationCard.Readings): Headline? {
        val r = card.lead?.let { card.items.getOrNull(it) } ?: return null
        return Headline(r.label, CardGeneralWords.reading(r), r.attention)
    }

    /**
     * How many rows need attention ("4 need attention", a failure counted too), else "All OK", else
     * how many are not known. A single row's headline is its own status word.
     */
    fun headline(card: ConversationCard.StatusList): Headline? {
        val rows = card.items.takeIf { it.isNotEmpty() } ?: return null
        val flagged = rows.count { it.status == RowStatus.ATTENTION || it.status == RowStatus.FAILED }
        val unknown = rows.count { it.status == RowStatus.UNKNOWN || it.status == RowStatus.NOT_REACHED }
        val words = when {
            rows.size == 1 -> rows.single().status.words
            flagged == 1 -> "1 needs attention"
            flagged > 1 -> "$flagged need attention"
            unknown == 0 -> "All OK"
            else -> "$unknown of ${rows.size} not known"
        }
        return Headline(null, words, attention = flagged > 0)
    }

    /** The first series' latest point: "Morning weight, Nov 14" over "182.4 lb"; null with none. */
    fun headline(card: ConversationCard.Trend): Headline? {
        val s = card.series.firstOrNull { it.points.isNotEmpty() } ?: return null
        val last = s.points.last()
        val flag = s.flag?.let { " (${it.words})" } ?: ""
        return Headline("${s.name}, ${pointDay(last.at)}", CardGeneralWords.withUnit(last.value, card.unit) + flag)
    }

    /** "Protein 13 g, Carbs 68 g, Fat 7 g (estimate)": a split's parts in their drawn order. */
    fun split(r: Reading): String? {
        val s = r.split ?: return null
        val flag = r.flag?.let { " (${it.words})" } ?: ""
        return s.parts.joinToString(", ") { "${it.label} ${CardGeneralWords.withUnit(it.value, s.unit)}" } + flag
    }

    /** "$520.00 of $500.00". */
    fun meter(m: StatusMeter): String =
        "${CardGeneralWords.withUnit(m.value, m.unit)} of ${CardGeneralWords.withUnit(m.max, m.unit)}"

    /** "Level 2 of 4". */
    fun level(l: StatusLevel): String = "Level ${l.value} of ${l.of}"

    /**
     * An end of a trend's time axis: "14:20" for a time of day, "Nov 14, 14:20" when the axis
     * spans more than one day ([withDay]), else "Nov 14".
     */
    fun axisTime(at: String, withDay: Boolean = false): String {
        val clock = AT.matchEntire(at)?.groupValues?.get(2).orEmpty()
        return if (clock.isBlank() || withDay) pointDay(at) else clock
    }

    /** "Nov 14", or "Nov 14, 14:20" for a point with a time. */
    private fun pointDay(at: String): String {
        val m = AT.matchEntire(at) ?: return at
        val clock = m.groupValues[2]
        return if (clock.isBlank()) CardGeneralWords.shortDay(at) else "${CardGeneralWords.shortDay(at)}, $clock"
    }
}
