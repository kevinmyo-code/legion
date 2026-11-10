package com.kevin.legion.ui.assistant

import com.kevin.legion.assistant.cards.BarPoint
import com.kevin.legion.assistant.cards.CarPart
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.assistant.cards.StepItem
import com.kevin.legion.assistant.cards.StepStatus
import com.kevin.legion.assistant.cards.TimelineItem
import com.kevin.legion.assistant.cards.TimelineKind
import com.kevin.legion.brief.BriefWords
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every sentence a [ConversationCardView] draws, pure so the words are pinned by tests. Status is
 * always a word here ("unverified", "estimate", "Failed", "not checked"); the view's colours only
 * reinforce it.
 */
internal object CardWords {

    private const val MS_PER_SECOND = 1_000L

    fun subtitle(card: ConversationCard): String? =
        listOfNotNull(card.period, card.scope).joinToString(", ").ifBlank { null }

    /**
     * "OBD port, just now", "Through 8 Nov": the card's source and age in words, only from what
     * [ConversationCard.provenance] carries. Null (no line) when it carries nothing.
     */
    fun provenance(card: ConversationCard, nowMs: Long, zone: ZoneId): String? {
        val p = card.provenance ?: return null
        val parts = listOfNotNull(
            p.source,
            p.readAtMs?.let { BriefWords.ago(it, nowMs) },
            p.throughMs?.let { "through " + Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(DAY_MONTH) },
        )
        return parts.joinToString(", ").ifBlank { null }?.replaceFirstChar { it.uppercase() }
    }

    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    fun barValue(p: BarPoint, unit: String): String =
        PhoneCards.money(p.value, unit) + (p.flag?.let { " (${it.words})" } ?: "")

    fun timeline(i: TimelineItem): String {
        val time = when {
            i.allDay -> "All day"
            i.start != null && i.end != null -> "${i.start} to ${i.end}"
            i.start != null -> i.start
            else -> "No time"
        }
        val kind = when (i.kind) {
            TimelineKind.EVENT -> ""
            TimelineKind.TODO -> " (to-do)"
            TimelineKind.SUGGESTION -> " (suggestion, not a plan)"
        }
        return "$time: ${i.title}$kind"
    }

    fun mileage(card: ConversationCard.Car): String =
        card.mileage
            ?.let { m -> "Mileage: ${"%,d".format(m)} mi" + (card.mileageAsOf?.let { " (last reading $it)" } ?: "") }
            ?: "Mileage: none on file"

    fun service(card: ConversationCard.Car): String {
        val n = card.nextService
        return when {
            !card.read(CarPart.SERVICE) -> "Next service: not checked"
            n == null -> "Next service: nothing to time"
            else -> {
                val at = listOfNotNull(n.dueMiles?.let { "at ${"%,d".format(it)} mi" }, n.dueDate?.let { "by $it" })
                    .joinToString(" or ")
                "Next service: ${n.label}" + (if (at.isBlank()) "" else " $at") + if (n.estimate) " (estimate)" else ""
            }
        }
    }

    fun recalls(card: ConversationCard.Car): String = when {
        !card.read(CarPart.RECALLS) -> "Recalls: not checked"
        card.recalls.isEmpty() -> "Recalls: none on file"
        else -> "Recalls (${card.recalls.size}): " + card.recalls.joinToString("; ") { it.summary }
    }

    fun codes(card: ConversationCard.Car): String = when {
        !card.read(CarPart.CODES) -> "Trouble codes: not checked"
        card.codes.isEmpty() -> "Trouble codes: none stored"
        else -> "Trouble codes: " +
            card.codes.joinToString("; ") { c -> c.code + (c.meaning?.let { " $it" } ?: ", meaning unknown") }
    }

    fun car(card: ConversationCard.Car): List<String> =
        listOf(mileage(card), service(card), recalls(card), codes(card))

    fun step(s: StepItem): String {
        val time = s.ms?.let { ", ${seconds(it)}" } ?: if (s.status == StepStatus.SKIPPED) "" else ", time not reported"
        return "${s.status.words}: ${s.label}$time"
    }

    fun check(l: ChecklistLine): String = (if (l.done) "Done: " else "To do: ") + l.text

    fun seconds(ms: Long): String = if (ms < MS_PER_SECOND) "$ms ms" else "%.1f s".format(ms / MS_PER_SECOND.toDouble())
}

private fun others(n: Int): String = if (n > 0) ", and $n more" else ""

/** The one line a collapsed card shows: the most telling line of what the tool read. */
internal fun collapsedLine(card: ConversationCard): String = when (card) {
    is ConversationCard.Bars -> card.points.firstOrNull()
        ?.let { "${it.label} ${CardWords.barValue(it, card.unit)}" + others(card.points.size - 1 + card.more) }
        ?: (card.note ?: "Nothing to show.")
    is ConversationCard.Timeline -> card.items.firstOrNull()
        ?.let { CardWords.timeline(it) + others(card.items.size - 1 + card.more) }
        ?: (card.note ?: "Nothing on.")
    is ConversationCard.Car -> collapsedCar(card)
    is ConversationCard.Steps -> collapsedSteps(card)
    is ConversationCard.Checklist -> card.note ?: "${card.items.size} items"
}

/** Codes, then recalls, then the service, then whatever was checked and came back empty. */
private fun collapsedCar(card: ConversationCard.Car): String = when {
    card.codes.isNotEmpty() -> CardWords.codes(card)
    card.recalls.isNotEmpty() -> CardWords.recalls(card)
    card.nextService != null -> CardWords.service(card)
    card.read(CarPart.RECALLS) -> CardWords.recalls(card)
    card.read(CarPart.CODES) -> CardWords.codes(card)
    else -> CardWords.mileage(card)
}

private fun collapsedSteps(card: ConversationCard.Steps): String {
    val failed = card.items.count { it.status == StepStatus.FAILED }
    return "${card.items.count { it.status == StepStatus.OK }} of ${card.items.size} steps done" +
        if (failed > 0) ", $failed failed" else ""
}

private val DAY_HEADING = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US)

/** "Wed Nov 4" from a timeline item's `yyyy-MM-dd`; the raw text if it does not parse. */
internal fun dayHeading(date: String): String =
    runCatching { java.time.LocalDate.parse(date).format(DAY_HEADING) }.getOrDefault(date)
