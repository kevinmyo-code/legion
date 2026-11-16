package com.kevin.legion.ui.assistant

import com.kevin.legion.assistant.cards.CardScale
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.StatusMeter
import com.kevin.legion.assistant.cards.TrendStyle
import java.math.BigDecimal

/**
 * Where a card's picture puts things, as fractions of its width or height (2026-11-15). Pure, so
 * every rule here is a JVM test; the Canvas code only multiplies. The floats never reach a word:
 * every figure a card says comes from [CardGeneralWords] on the source's own numbers.
 */
internal object CardGeometry {

    /** [value] on [min]..[max] as 0..1, clamped. */
    fun fraction(value: BigDecimal, min: BigDecimal, max: BigDecimal): Float {
        val span = max - min
        if (span.signum() <= 0) return 0f
        return ((value - min).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    /** A reading's bar on its scale, and its mark there; null for an unread value (no bar at all). */
    fun reading(value: BigDecimal?, scale: CardScale, mark: BigDecimal?): Pair<Float, Float?>? {
        value ?: return null
        return fraction(value, scale.min, scale.max) to mark?.let { fraction(it, scale.min, scale.max) }
    }

    /**
     * A meter scaled to the largest of its value, its limit and its mark, so an amount past its
     * limit is drawn past the limit's tick and never clipped at it.
     */
    data class Meter(val fill: Float, val limit: Float, val mark: Float?, val over: Boolean)

    fun meter(m: StatusMeter): Meter {
        val top = listOfNotNull(m.value, m.max, m.mark?.value).max()
        val zero = BigDecimal.ZERO
        return Meter(
            fill = fraction(m.value, zero, top),
            limit = fraction(m.max, zero, top),
            mark = m.mark?.let { fraction(it.value, zero, top) },
            over = m.value > m.max,
        )
    }

    /** Each part's share of the whole, in order; empty when the parts sum to nothing. */
    fun shares(values: List<BigDecimal>): List<Float> {
        val total = values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.signum() <= 0) return emptyList()
        return values.map { it.toFloat() / total.toFloat() }
    }

    /**
     * A trend's picture. [lines] holds each series as runs of (x, y) points; a run breaks where the
     * series skips more than [GAP_STEPS] times its usual step, so a long silence shows as a gap and
     * never as a line across it. [low] and [high] are the y span drawn (said at the axis); [guide]
     * is the guide line's height, or null when it would squash the data and is said in words only.
     * Bars stand from zero in day slots, so a night with nothing logged is an empty slot.
     */
    data class TrendLayout(
        val low: BigDecimal,
        val high: BigDecimal,
        val lines: List<List<List<Pair<Float, Float>>>>,
        val bars: List<Pair<Float, Float>>,
        val slot: Float,
        val guide: Float?,
        val guideOff: Int,
    )

    fun trend(card: ConversationCard.Trend): TrendLayout {
        val all = card.series.flatMap { it.points }
        val bars = card.style == TrendStyle.BARS
        val span = span(all.map { it.value }, card.guide?.value, bars)
        val axis = TimeAxis(all.map { CardGeneralWords.minutes(it.at) }, bars)
        fun y(v: BigDecimal) = fraction(v, span.low, span.high)
        val lines = card.series.map { s ->
            if (bars) emptyList() else runs(s.points.map { it.at }).map { run -> run.map { i -> s.points[i] } }
        }.map { runs -> runs.map { run -> run.map { axis.x(it.at) to y(it.value) } } }
        val columns = if (bars) card.series.first().points.map { axis.x(it.at) to y(it.value) } else emptyList()
        return TrendLayout(span.low, span.high, lines, columns, axis.slot, span.guide?.let(::y), span.guideOff)
    }

    /**
     * The y span: the data's, joined by the guide when it is near enough not to squash the data
     * (always for bars, which stand from zero). A guide left out is said to be below or above.
     */
    private data class Span(val low: BigDecimal, val high: BigDecimal, val guide: BigDecimal?, val guideOff: Int)

    private fun span(values: List<BigDecimal>, g: BigDecimal?, bars: Boolean): Span {
        val dataLow = values.minOrNull() ?: BigDecimal.ZERO
        val dataHigh = values.maxOrNull() ?: BigDecimal.ZERO
        val reach = (dataHigh - dataLow).max(NEAR_MIN)
        val shown = g?.takeIf { bars || (it >= dataLow - reach && it <= dataHigh + reach) }
        val drawn = values + listOfNotNull(shown)
        val low = if (bars) BigDecimal.ZERO.min(dataLow) else drawn.minOrNull() ?: BigDecimal.ZERO
        val high = drawn.maxOrNull()?.takeIf { it > low } ?: (low + BigDecimal.ONE)
        val off = when {
            g == null || shown != null -> 0
            g < low -> -1
            else -> 1
        }
        return Span(low, high, shown, off)
    }

    /** Time across: a line spans first to last; bars sit in day slots, a missing day an empty one. */
    private class TimeAxis(minutes: List<Long>, private val bars: Boolean) {
        private val x0 = minutes.minOrNull() ?: 0L
        private val x1 = minutes.maxOrNull() ?: 0L
        val slot = if (bars) 1f / ((x1 - x0) / MINUTES_PER_DAY + 1).toInt().coerceAtLeast(1) else 0f

        fun x(at: String): Float {
            val m = CardGeneralWords.minutes(at) - x0
            return when {
                bars -> ((m / MINUTES_PER_DAY).toFloat() + HALF) * slot
                x1 > x0 -> m.toFloat() / (x1 - x0)
                else -> HALF
            }
        }
    }

    /** Indices of [ats] in runs, broken where a step is over [GAP_STEPS] times the median step. */
    fun runs(ats: List<String>): List<List<Int>> {
        if (ats.isEmpty()) return emptyList()
        val ms = ats.map(CardGeneralWords::minutes)
        val steps = ms.zipWithNext { a, b -> b - a }
        val usual = steps.sorted().getOrNull(steps.size / 2) ?: 0L
        val out = mutableListOf(mutableListOf(0))
        steps.forEachIndexed { i, step ->
            if (usual > 0 && step > usual * GAP_STEPS) out.add(mutableListOf())
            out.last().add(i + 1)
        }
        return out
    }

    private const val GAP_STEPS = 3
    private const val MINUTES_PER_DAY = 1440L
    private const val HALF = 0.5f
    private val NEAR_MIN = BigDecimal.ONE
}
