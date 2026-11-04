package com.kevin.legion.assistant.cards

/**
 * A generative-UI card: what a voice tool read, drawn on screen while the conversation runs
 * (Kevin, 2026-11-04). **Built from the tool's own result data in Kotlin, never from model text**,
 * so a card can only show what a tool actually returned (CLAUDE.md section 7: nothing unobserved).
 *
 * The format is shared with the web assistant's cards and must not drift: [CardJson] writes it.
 * Common fields: [kind], [title], optional [scope], [period], [note] (plain words).
 *
 * One relaxation, internal to the phone and stated: [Car.recalls] and [Car.codes] are nullable
 * here, meaning "not looked up in this conversation", because three tools feed one car card and an
 * empty list would claim "no recalls" when nobody checked. [CardJson] writes a null list as `[]`
 * and says it was not checked in `note`, so the shared shape holds.
 */
sealed class ConversationCard {
    abstract val kind: String
    abstract val title: String
    abstract val scope: String?
    abstract val period: String?
    abstract val note: String?

    /** `unit` is "usd" (values are Long cents) or "" (counts). At most [MAX_POINTS] points. */
    data class Bars(
        override val title: String,
        val unit: String,
        val points: List<BarPoint>,
        val more: Int = 0,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
    ) : ConversationCard() {
        override val kind: String get() = "bars"
    }

    /** One household-local [date] (`yyyy-MM-dd`), its [zone] as "UTC-06:00" (never an IANA id). */
    data class Timeline(
        override val title: String,
        val date: String,
        val zone: String,
        val items: List<TimelineItem>,
        val more: Int = 0,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
    ) : ConversationCard() {
        override val kind: String get() = "timeline"
    }

    data class Car(
        override val title: String,
        val name: String,
        val mileage: Int?,
        val mileageAsOf: String?,
        val nextService: NextServiceDue?,
        val recalls: List<Recall>?,
        val codes: List<TroubleCode>?,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
    ) : ConversationCard() {
        override val kind: String get() = "car"
    }

    /** [StepItem.ms] and [totalMs] are null when the source did not report a time. */
    data class Steps(
        override val title: String,
        val name: String,
        val items: List<StepItem>,
        val totalMs: Long?,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
    ) : ConversationCard() {
        override val kind: String get() = "steps"
    }

    data class Checklist(
        override val title: String,
        val listName: String,
        val items: List<ChecklistLine>,
        val more: Int = 0,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
    ) : ConversationCard() {
        override val kind: String get() = "checklist"
    }

    companion object {
        /** Bars, timeline items and checklist lines shown before the rest become `more`. */
        const val MAX_POINTS = 8
    }
}

/** Why a figure is not plain fact. Always rendered in words, never colour alone. */
enum class CardFlag(val wire: String, val words: String) {
    UNVERIFIED("unverified", "unverified"),
    ESTIMATE("estimate", "estimate"),
}

data class BarPoint(val label: String, val value: Long, val flag: CardFlag? = null)

enum class TimelineKind(val wire: String) { EVENT("event"), TODO("todo"), SUGGESTION("suggestion") }

/** [start]/[end] are "HH:MM" local, null when [allDay] or not stated. */
data class TimelineItem(
    val start: String?,
    val end: String?,
    val allDay: Boolean,
    val title: String,
    val kind: TimelineKind,
)

/** [estimate] is always true: a due point is computed from intervals, never a stated fact. */
data class NextServiceDue(
    val label: String,
    val dueMiles: Int? = null,
    val dueDate: String? = null,
    val estimate: Boolean = true,
)

data class Recall(val summary: String)

data class TroubleCode(val code: String, val meaning: String?)

enum class StepStatus(val wire: String, val words: String) {
    OK("ok", "Done"),
    FAILED("failed", "Failed"),
    SKIPPED("skipped", "Skipped"),
}

data class StepItem(val label: String, val status: StepStatus, val ms: Long?)

data class ChecklistLine(val text: String, val done: Boolean)
