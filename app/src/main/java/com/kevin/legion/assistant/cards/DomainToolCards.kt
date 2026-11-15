package com.kevin.legion.assistant.cards

import java.time.ZoneId

/**
 * The one-line publish calls for the general-kind cards (2026-11-15), beside [ToolCards] and kept
 * apart so neither object grows past its size. Each is a no-op when no conversation is open.
 */
object DomainToolCards {

    /** `claude_session` `check`: each machine's health and its last 24 hours of temperatures. */
    fun laptop(m: com.kevin.legion.claude.ClaudeMachine) {
        val zone = ZoneId.systemDefault()
        DomainCards.laptop(m.name, m.state, m.health, zone)?.let(ConversationCards::publish)
        DomainCards.laptopTemps(m.name, m.health, zone)?.let(ConversationCards::publish)
    }

    /** `trip_status`: the snapshot it worded; never a card when not navigating. */
    fun trip(t: com.kevin.legion.navigation.TripStatus.Navigating) {
        ConversationCards.publish(DomainCards.trip(t.destination.name, t.stops.size, t.guidance, ZoneId.systemDefault()))
    }

    fun weightTrend(result: org.json.JSONObject) {
        BodyCards.weightTrend(result)?.let(ConversationCards::publish)
    }

    fun workout(result: org.json.JSONObject) {
        BodyCards.workout(result)?.let(ConversationCards::publish)
    }

    /** `list_recent_sleep`: each log's date and minutes. */
    fun sleep(nights: List<Pair<Long, Int>>) {
        BodyCards.sleep(nights)?.let(ConversationCards::publish)
    }

    fun groceries(rows: List<com.kevin.legion.data.local.PantryLineItemWithCurrency>) {
        BodyCards.groceries(rows)?.let(ConversationCards::publish)
    }

    /** `email` `draft`: Kevin's own draft, to and subject; the result carries no body. */
    fun draft(to: String, subject: String) = ConversationCards.publish(DomainCards.draft(to, subject))

    /** `memory` `list`: the newest shown, with Forget unless read from the offline copy. */
    fun memories(rows: List<com.kevin.legion.memory.MemberMemory>, total: Int, fromCache: Boolean) =
        ConversationCards.publish(DomainCards.memories(rows, total, fromCache))
}
