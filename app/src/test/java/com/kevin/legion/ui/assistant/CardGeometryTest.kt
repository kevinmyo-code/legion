package com.kevin.legion.ui.assistant

import com.kevin.legion.assistant.cards.CardMark
import com.kevin.legion.assistant.cards.CardScale
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.StatusMeter
import com.kevin.legion.assistant.cards.TrendPoint
import com.kevin.legion.assistant.cards.TrendSeries
import com.kevin.legion.assistant.cards.TrendStyle
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a card's picture puts things (2026-11-15): pure fractions, so every rule is checked here. */
class CardGeometryTest {

    private fun d(v: String) = BigDecimal(v)

    private fun trend(vararg points: Pair<String, String>, guide: String? = null, style: TrendStyle = TrendStyle.LINE) =
        ConversationCard.Trend(
            title = "x",
            unit = "h",
            series = listOf(TrendSeries("a", points.map { TrendPoint(it.first, d(it.second)) })),
            guide = guide?.let { CardMark(d(it), "Target") },
            style = style,
        )

    @Test
    fun `an unread value draws no bar at all, never a bar at zero`() {
        val scale = CardScale(BigDecimal.ZERO, d("100"))
        assertNull(CardGeometry.reading(null, scale, null))
        assertEquals(0f, CardGeometry.reading(BigDecimal.ZERO, scale, null)!!.first)
        assertEquals(0.62f, CardGeometry.reading(d("62"), scale, d("95"))!!.first, 1e-6f)
        assertEquals(0.95f, CardGeometry.reading(d("62"), scale, d("95"))!!.second!!, 1e-6f)
    }

    @Test
    fun `an amount past its limit is drawn past the limit's tick, never clipped at it`() {
        val over = CardGeometry.meter(StatusMeter(d("52000"), d("50000"), "usd", CardMark(d("25000"), "Even pace")))
        assertTrue(over.over)
        assertEquals(1f, over.fill)
        assertEquals(50_000f / 52_000f, over.limit, 1e-6f)
        val under = CardGeometry.meter(StatusMeter(d("9000"), d("20000"), "usd"))
        assertFalse(under.over)
        assertEquals(1f, under.limit)
        assertEquals(0.45f, under.fill, 1e-6f)
    }

    @Test
    fun `split shares follow the parts, and parts summing to nothing draw nothing`() {
        assertEquals(listOf(0.25f, 0.75f), CardGeometry.shares(listOf(d("1"), d("3"))))
        assertTrue(CardGeometry.shares(listOf(BigDecimal.ZERO, BigDecimal.ZERO)).isEmpty())
    }

    @Test
    fun `a long silence breaks a line, a steady series is one run`() {
        assertEquals(listOf(listOf(0, 1, 2)), CardGeometry.runs(listOf("2026-11-01", "2026-11-02", "2026-11-03")))
        assertEquals(
            listOf(listOf(0, 1, 2), listOf(3, 4)),
            CardGeometry.runs(listOf("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-20", "2026-11-21")),
        )
        val temps = listOf("2026-11-15T10:00", "2026-11-15T10:10", "2026-11-15T10:20", "2026-11-15T14:00")
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), CardGeometry.runs(temps))
    }

    @Test
    fun `a nearby guide joins the span, a far one stays out and is said to be off the chart`() {
        val near = CardGeometry.trend(trend("2026-11-01" to "186", "2026-11-02" to "182", guide = "180"))
        assertEquals(0, d("180").compareTo(near.low))
        assertEquals(0f, near.guide!!, 1e-6f)
        val far = CardGeometry.trend(trend("2026-11-01" to "186", "2026-11-02" to "185", guide = "150"))
        assertNull(far.guide)
        assertEquals(-1, far.guideOff)
        assertEquals(0, d("185").compareTo(far.low))
    }

    @Test
    fun `bars stand from zero in day slots, a missed night an empty slot`() {
        val t = CardGeometry.trend(
            trend(
                "2026-11-10" to "7.5", "2026-11-11" to "6", "2026-11-13" to "8",
                guide = "8", style = TrendStyle.BARS,
            ),
        )
        assertEquals(0, BigDecimal.ZERO.compareTo(t.low))
        assertEquals(0.25f, t.slot, 1e-6f)
        // Four day slots (10th to 13th), three columns: the 12th has none.
        assertEquals(listOf(0.125f, 0.375f, 0.875f), t.bars.map { it.first })
        assertEquals(1f, t.guide!!, 1e-6f)
    }
}
