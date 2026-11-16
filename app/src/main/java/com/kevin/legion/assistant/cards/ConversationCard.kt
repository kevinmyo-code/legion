package com.kevin.legion.assistant.cards

/**
 * A generative-UI card: what a voice tool read, drawn on screen while the conversation runs
 * (Kevin, 2026-11-04). **Built from the tool's own result data in Kotlin, never from model text**,
 * so a card can only show what a tool actually returned (CLAUDE.md section 7: nothing unobserved).
 *
 * The format is shared with the web assistant's cards and must not drift: [CardJson] writes it.
 * Common fields: [kind], [title], optional [scope], [period], [note] (plain words).
 *
 * Two optional fields the engine added to the format (`server/engine_mcp/cards.py`, 2026-11-04):
 * [TimelineItem.date] for a window of several days, and [Car.parts], the parts of a car (service,
 * codes, recalls) one call actually read, each with its own note. A part that is absent was not
 * looked up, so an empty [Car.recalls] means "none" only when [CarPart.RECALLS] is in [Car.parts].
 */
sealed class ConversationCard {
    abstract val kind: String
    abstract val title: String
    abstract val scope: String?
    abstract val period: String?
    abstract val note: String?

    /**
     * Where the card's data came from and how old it is, ONLY when the tool's result or record
     * carries it (Kevin, 2026-11-10, ADR 0081): a live read stamps its own read time, a ledger
     * report its coverage date. Never inferred from a tool's name; null draws no line. Display
     * only: not part of the shared wire format ([CardJson]).
     */
    abstract val provenance: CardProvenance?

    /** `unit` is "usd" (values are Long cents) or "" (counts). At most [MAX_POINTS] points. */
    data class Bars(
        override val title: String,
        val unit: String,
        val points: List<BarPoint>,
        val more: Int = 0,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
        override val provenance: CardProvenance? = null,
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
        override val provenance: CardProvenance? = null,
    ) : ConversationCard() {
        override val kind: String get() = "timeline"
    }

    data class Car(
        override val title: String,
        val name: String,
        val mileage: Int?,
        val mileageAsOf: String?,
        val nextService: NextServiceDue?,
        val recalls: List<Recall>,
        val codes: List<TroubleCode>,
        /** The parts this card's reads covered, each with that part's note (may be blank). */
        val parts: Map<CarPart, String>,
        override val scope: String? = null,
        override val period: String? = null,
        /** Words about the card as a whole that belong to no part (the mileage caveat). */
        val extraNote: String? = null,
        override val provenance: CardProvenance? = null,
    ) : ConversationCard() {
        override val kind: String get() = "car"

        fun read(part: CarPart): Boolean = part in parts

        /** Every part's note, then [extraNote]: what a screen that does not split by part says. */
        override val note: String?
            get() = (CarPart.entries.mapNotNull { parts[it]?.takeIf(String::isNotBlank) } + listOfNotNull(extraNote))
                .joinToString(" ").ifBlank { null }
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
        override val provenance: CardProvenance? = null,
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
        override val provenance: CardProvenance? = null,
    ) : ConversationCard() {
        override val kind: String get() = "checklist"
    }

    /**
     * Labelled values with units, a snapshot of measurements (the shared format's `readings`,
     * 2026-11-15). [at] is the "as of" time when the source states one, `yyyy-MM-dd` or
     * `yyyy-MM-ddTHH:mm` on the household's clock. [open] is the screen behind the card, phone
     * only and not on the wire, null for none.
     */
    data class Readings(
        override val title: String,
        val items: List<Reading>,
        val at: String? = null,
        val more: Int = 0,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
        override val provenance: CardProvenance? = null,
        val open: CardDestination? = null,
        /**
         * Phone only, not on the wire: one id per shown item when each row may be forgotten (the
         * memory list). The card then offers Forget, a hard delete; null draws none.
         */
        val forgetIds: List<String>? = null,
        /** The item drawn as the card's headline (the shared format's `lead`); null draws none. */
        val lead: Int? = null,
    ) : ConversationCard() {
        override val kind: String get() = "readings"
    }

    /** Rows with a status said in words (`status_list`). Shape as [Readings]. */
    data class StatusList(
        override val title: String,
        val items: List<StatusRow>,
        val at: String? = null,
        val more: Int = 0,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
        override val provenance: CardProvenance? = null,
        val open: CardDestination? = null,
    ) : ConversationCard() {
        override val kind: String get() = "status_list"
    }

    /**
     * One or two [series] over time sharing one [unit] (`trend`). [guide] is one reference line the
     * source states (a target, a goal, an alert threshold); [style] draws a line or a column a point.
     */
    data class Trend(
        override val title: String,
        val unit: String,
        val series: List<TrendSeries>,
        override val scope: String? = null,
        override val period: String? = null,
        override val note: String? = null,
        override val provenance: CardProvenance? = null,
        val open: CardDestination? = null,
        val guide: CardMark? = null,
        val style: TrendStyle = TrendStyle.LINE,
    ) : ConversationCard() {
        override val kind: String get() = "trend"
    }

    companion object {
        /** Bars and checklist lines shown before the rest become `more`. */
        const val MAX_POINTS = 8

        /** Timeline items shown, the engine's own `TIMELINE_MAX`, so a week fits. */
        const val MAX_TIMELINE = 12

        /** Readings and status rows shown, the engine's `READINGS_MAX` / `STATUS_MAX`. */
        const val MAX_ROWS = 12

        /** Points kept per trend series (the newest), the engine's `TREND_MAX`. */
        const val MAX_TREND = 120

        /** Series on one trend card, the engine's `TREND_SERIES_MAX`. */
        const val MAX_SERIES = 2
    }
}

/** The full screen behind a card: what its "Open" link leads to, in words. */
enum class CardDestination(val words: String) {
    TODAY("Open Today"),
    MONEY("Open Money"),
    CAR("Open Car"),
    LISTS("Open Lists"),
    GRADES("Open Grades"),
    BODY("Open Body"),
    PANTRY("Open Pantry"),
    CONDITIONS("Open Conditions"),
    MEMORY("Open Memory"),
    LAPTOP("Open Claude"),
    NAVIGATION("Open Navigation"),
}

/**
 * One reading. [value] is null when it could not be read ("not read", never zero). With [unit]
 * "usd" every value is integer cents (CLAUDE.md section 4: never a Double for money); BigDecimal
 * keeps every other value exactly as the source gave it. [was] is an earlier value (before/after).
 */
data class Reading(
    val label: String,
    val value: java.math.BigDecimal?,
    val unit: String,
    val flag: CardFlag? = null,
    val was: java.math.BigDecimal? = null,
    val note: String? = null,
    /** A value that is words, not a number ("Closed loop", a VIN); [value] is then null. */
    val text: String? = null,
    /** The span the value is drawn on as a bar; null draws none. */
    val scale: CardScale? = null,
    /** One reference on [scale] ("Alert at 95%"); only with a scale. */
    val mark: CardMark? = null,
    /** The value split into parts (macros in grams), drawn as one stacked bar; [flag] covers them. */
    val split: CardSplit? = null,
    /** The source says this value needs attention: said in words, drawn in the accent. */
    val attention: Boolean = false,
)

/**
 * The optional picture fields of the shared format (2026-11-15). Each carries a figure the source
 * states, in the reading's own unit; a screen never works one out. [CardScale] is the span a value
 * is drawn on, [CardMark] one reference on it (or a trend's guide line), [CardSplit] one whole in
 * 2 to 4 parts, [StatusMeter] an amount against its limit, [StatusLevel] a rank on a named scale.
 */
data class CardScale(val min: java.math.BigDecimal, val max: java.math.BigDecimal)

data class CardMark(val value: java.math.BigDecimal, val label: String)

data class SplitPart(val label: String, val value: java.math.BigDecimal)

data class CardSplit(val unit: String, val parts: List<SplitPart>)

data class StatusMeter(
    val value: java.math.BigDecimal,
    val max: java.math.BigDecimal,
    val unit: String,
    val mark: CardMark? = null,
)

data class StatusLevel(val value: Int, val of: Int)

/** A trend drawn as a line (the default) or as a column a point from zero (`bars`). */
enum class TrendStyle(val wire: String) {
    LINE("line"),
    BARS("bars"),
    ;

    companion object {
        fun of(wire: String?): TrendStyle? = when (wire) {
            null -> LINE
            else -> entries.firstOrNull { it.wire == wire }
        }
    }
}

/** A status, always drawn in [words]; colour may only repeat the word. */
enum class RowStatus(val wire: String, val words: String) {
    OK("ok", "OK"),
    ATTENTION("attention", "Needs attention"),
    FAILED("failed", "Failed"),
    UNKNOWN("unknown", "Unknown"),
    NOT_REACHED("not_reached", "Not reached"),
    ;

    companion object {
        fun of(wire: String?): RowStatus? = entries.firstOrNull { it.wire == wire }
    }
}

data class StatusRow(
    val name: String,
    val status: RowStatus,
    val note: String? = null,
    val meter: StatusMeter? = null,
    val level: StatusLevel? = null,
)

/** [at] is `yyyy-MM-dd` or `yyyy-MM-ddTHH:mm`; points oldest first. */
data class TrendPoint(val at: String, val value: java.math.BigDecimal)

data class TrendSeries(val name: String, val points: List<TrendPoint>, val flag: CardFlag? = null)

/**
 * A card's provenance, each part only when the data carries it: [source] names where it was read
 * ("OBD port"), [readAtMs] is when a live read ran, [throughMs] the date the data covers up to.
 */
data class CardProvenance(val source: String? = null, val readAtMs: Long? = null, val throughMs: Long? = null)

/** Why a figure is not plain fact. Always rendered in words, never colour alone. */
enum class CardFlag(val wire: String, val words: String) {
    UNVERIFIED("unverified", "unverified"),
    ESTIMATE("estimate", "estimate"),
}

data class BarPoint(val label: String, val value: Long, val flag: CardFlag? = null)

enum class TimelineKind(val wire: String) { EVENT("event"), TODO("todo"), SUGGESTION("suggestion") }

/** [start]/[end] are "HH:MM" local, null when [allDay] or not stated. [date] (`yyyy-MM-dd`) is set
 * only on a card that spans several days, and the view groups items under day headings by it. */
data class TimelineItem(
    val start: String?,
    val end: String?,
    val allDay: Boolean,
    val title: String,
    val kind: TimelineKind,
    val date: String? = null,
)

/** The parts of a car card, by their wire names in the shared format's `parts`. */
enum class CarPart(val wire: String) {
    SERVICE("service"),
    CODES("codes"),
    RECALLS("recalls"),
    ;

    companion object {
        fun of(wire: String): CarPart? = entries.firstOrNull { it.wire == wire }
    }
}

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
