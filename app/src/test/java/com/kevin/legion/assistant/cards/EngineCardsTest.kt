package com.kevin.legion.assistant.cards

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** run_procedure's structured summary to a steps card, and the shared JSON format it writes. */
@RunWith(RobolectricTestRunner::class)
class EngineCardsTest {

    private fun step(id: String, tool: String, outcome: String, ms: Long? = null) =
        JSONObject().put("step_id", id).put("kind", if (tool.isBlank()) "compute" else "tool")
            .put("tool", tool).put("outcome", outcome).apply { ms?.let { put("ms", it) } }

    private fun summary(status: String, vararg steps: JSONObject, notRun: List<String> = emptyList()) =
        JSONObject().put("status", status).put("procedure", "car_check")
            .put("steps", JSONArray(steps.toList())).put("not_run", JSONArray(notRun))

    @Test
    fun `a finished run lists each step with its status in words and the phone's round trip`() {
        val card = EngineCards.fromTool(
            "run_procedure",
            summary("done", step("service", "next_service", "ok"), step("lead", "", "ok")),
            2_300L,
        ) as ConversationCard.Steps
        assertEquals("car_check", card.name)
        assertEquals("Ran car check", card.title)
        assertEquals(listOf("Service (next_service)", "Lead"), card.items.map { it.label })
        assertEquals(listOf(StepStatus.OK, StepStatus.OK), card.items.map { it.status })
        assertEquals(listOf(null, null), card.items.map { it.ms })
        assertEquals(2_300L, card.totalMs)
        assertTrue(card.note!!, card.note!!.contains("did not report step times"))
    }

    @Test
    fun `a failed step and the steps that never ran are shown, and the status is said`() {
        val card = EngineCards.fromTool(
            "run_procedure",
            summary(
                "partial",
                step("service", "next_service", "ok", ms = 40),
                step("recalls", "vehicle_recalls", "failed", ms = 3_000),
                notRun = listOf("say")),
            3_500L,
        ) as ConversationCard.Steps
        assertEquals(listOf(StepStatus.OK, StepStatus.FAILED, StepStatus.SKIPPED), card.items.map { it.status })
        assertEquals(listOf(40L, 3_000L, null), card.items.map { it.ms })
        assertTrue(card.note!!, card.note!!.contains("Did not finish"))
    }

    @Test
    fun `other tools and a summary with no procedure make no card`() {
        assertNull(EngineCards.fromTool("list_events", JSONObject(), 10L))
        assertNull(EngineCards.fromTool("run_procedure", null, 10L))
        assertNull(EngineCards.fromTool("run_procedure", JSONObject().put("steps", JSONArray()), 10L))
    }

    @Test
    fun `cards write the shared format`() {
        val steps = CardJson.write(
            EngineCards.fromTool("run_procedure", summary("done", step("service", "next_service", "ok")), 5L)!!,
        )
        assertEquals("steps", steps.getString("kind"))
        assertEquals("ok", steps.getJSONArray("items").getJSONObject(0).getString("status"))
        assertTrue(steps.getJSONArray("items").getJSONObject(0).isNull("ms"))
        assertEquals(5L, steps.getLong("totalMs"))

        val bars = CardJson.write(
            ConversationCard.Bars(
                "Spending", "usd", listOf(BarPoint("Gas", 1_999L, CardFlag.UNVERIFIED), BarPoint("Food", 5L)),
            ),
        )
        val points = bars.getJSONArray("points")
        assertEquals(1_999L, points.getJSONObject(0).getLong("value"))
        assertEquals("unverified", points.getJSONObject(0).getString("flag"))
        assertTrue(points.getJSONObject(1).isNull("flag"))
        assertEquals(0, bars.getInt("more"))

        val car = CardJson.write(
            ConversationCard.Car("Jeep", "Jeep", 1, null, NextServiceDue("Oil", dueMiles = 9), null, null),
        )
        assertEquals(0, car.getJSONArray("recalls").length())
        assertTrue(car.getJSONObject("nextService").getBoolean("estimate"))
        assertEquals(9, car.getJSONObject("nextService").getInt("dueMiles"))

        val timeline = CardJson.write(
            ConversationCard.Timeline(
                "Calendar", "2026-11-04", "UTC-06:00",
                listOf(TimelineItem(null, null, true, "Holiday", TimelineKind.EVENT)),
            ),
        )
        assertEquals("UTC-06:00", timeline.getString("zone"))
        assertTrue(timeline.getJSONArray("items").getJSONObject(0).getBoolean("allDay"))
    }
}
