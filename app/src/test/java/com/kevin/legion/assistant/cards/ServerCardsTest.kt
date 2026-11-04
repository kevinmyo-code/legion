package com.kevin.legion.assistant.cards

import com.kevin.legion.service.EngineToolAnswer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The engine's own cards, in the snake_case shape `server/engine_mcp/cards.py` writes. */
@RunWith(RobolectricTestRunner::class)
class ServerCardsTest {

    private fun json(text: String) = JSONObject(text)

    @Test
    fun `a car card from next_service reads only the service part`() {
        val card = ServerCards.parse(
            json(
                """{"card":"car","title":"The car","name":"The car","mileage":203000,
                "mileage_as_of":"2026-10-30","next_service":{"label":"Oil Change","estimate":true,
                "due_miles":210450},"recalls":[],"codes":[],
                "note":"Next service is an estimate from the records on file.",
                "parts":{"service":"Next service is an estimate from the records on file."}}""",
            ),
        ) as ConversationCard.Car
        assertEquals(203_000, card.mileage)
        assertEquals("2026-10-30", card.mileageAsOf)
        assertEquals(NextServiceDue("Oil Change", dueMiles = 210_450, estimate = true), card.nextService)
        assertEquals(setOf(CarPart.SERVICE), card.parts.keys)
        assertFalse(card.read(CarPart.RECALLS))
        assertEquals("Next service is an estimate from the records on file.", card.note)
    }

    @Test
    fun `a car card with no parts read every part, and a blank meaning is unknown`() {
        val card = ServerCards.parse(
            json(
                """{"card":"car","title":"Jeep","name":"Jeep","mileage":null,"mileage_as_of":null,
                "next_service":null,"recalls":[{"summary":"Fuel system: A hose may leak."}],
                "codes":[{"code":"P1282","meaning":""}],"note":"Codes are what the dongle logged."}""",
            ),
        ) as ConversationCard.Car
        assertEquals(CarPart.entries.toSet(), card.parts.keys)
        assertNull(card.codes.single().meaning)
        assertEquals("Fuel system: A hose may leak.", card.recalls.single().summary)
        assertEquals("Codes are what the dongle logged.", card.note)
    }

    @Test
    fun `a timeline keeps each item's date and its all-day flag`() {
        val card = ServerCards.parse(
            json(
                """{"card":"timeline","title":"What's on","date":"2026-11-02","zone":"UTC-06:00",
                "items":[{"start":null,"end":null,"all_day":true,"title":"Election Day","kind":"event",
                "date":"2026-11-03"},{"start":"09:30","end":"10:30","all_day":false,"title":"COSC 3334",
                "kind":"event","date":"2026-11-03"}],"more":0,"period":"Mon Nov 2 to Sun Nov 8"}""",
            ),
        ) as ConversationCard.Timeline
        assertTrue(card.items[0].allDay)
        assertEquals(listOf("2026-11-03", "2026-11-03"), card.items.map { it.date })
        assertEquals("UTC-06:00", card.zone)
        assertEquals("Mon Nov 2 to Sun Nov 8", card.period)
    }

    @Test
    fun `steps carry the engine's own step times`() {
        val card = ServerCards.parse(
            json(
                """{"card":"steps","title":"Car check","name":"Car check","items":[
                {"label":"Service","status":"ok","ms":40},{"label":"Recalls","status":"failed","ms":3000},
                {"label":"Say","status":"skipped","ms":0}],"total_ms":3040,"note":"Stopped part way."}""",
            ),
        ) as ConversationCard.Steps
        assertEquals(listOf(40L, 3_000L, 0L), card.items.map { it.ms })
        assertEquals(listOf(StepStatus.OK, StepStatus.FAILED, StepStatus.SKIPPED), card.items.map { it.status })
        assertEquals(3_040L, card.totalMs)
    }

    @Test
    fun `bars and checklist read their own fields`() {
        val bars = ServerCards.parse(
            json(
                """{"card":"bars","title":"Spending by category","period":"October 2026","unit":"usd",
                "points":[{"label":"Food","value":12345,"flag":"unverified"},{"label":"Gas","value":500}],
                "more":2,"note":"Total 128.45."}""",
            ),
        ) as ConversationCard.Bars
        assertEquals(listOf(12_345L, 500L), bars.points.map { it.value })
        assertEquals(listOf(CardFlag.UNVERIFIED, null), bars.points.map { it.flag })
        assertEquals(2, bars.more)
        val list = ServerCards.parse(
            json(
                """{"card":"checklist","title":"gym","list_name":"gym",
                "items":[{"text":"bench","done":true}],"more":0}""",
            ),
        ) as ConversationCard.Checklist
        assertEquals("gym", list.listName)
        assertTrue(list.items.single().done)
    }

    @Test
    fun `an unknown or broken card is no card, and the steps fallback still works without one`() {
        assertNull(ServerCards.parse(json("""{"card":"weather","title":"x"}""")))
        assertNull(ServerCards.parse(json("""{"card":"bars"}""")))
        val structured = json("""{"procedure":"car_check","status":"done","steps":[],"not_run":[]}""")
        val fallback = EngineCards.fromAnswer(EngineToolAnswer("run_procedure", false, structured, null, 12L))
        assertTrue(fallback is ConversationCard.Steps)
        val fromEngine = EngineCards.fromAnswer(
            EngineToolAnswer(
                "next_service", false, null, json("""{"card":"checklist","title":"gym","items":[]}"""), 5L,
            ),
        )
        assertTrue(fromEngine is ConversationCard.Checklist)
    }
}
