package com.kevin.legion.assistant.cards

import android.content.Context
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.VinDecoder
import com.kevin.legion.vehicle.VehicleController
import java.time.ZoneId
import org.json.JSONArray
import com.kevin.legion.service.EngineToolAnswer

/**
 * The one-line calls `LiveToolbox`'s tools make to publish a card from their own result data. Kept
 * here, not in that file, so each tool grows by one line and the lookups a card needs beyond the
 * tool's data (which car a code was read from) live beside the builders. Every call is a no-op
 * when no conversation, voice or typed, is open ([ConversationCards.publish]).
 */
object ToolCards {

    fun publish(card: ConversationCard) = ConversationCards.publish(card)

    fun nextService(vehicle: Vehicle, next: VehicleController.NextService?) =
        publish(CarCards.service(VehicleController.label(vehicle), vehicle, next, ZoneId.systemDefault()))

    fun recalls(vehicle: Vehicle, recalls: List<VinDecoder.Recall>) =
        publish(
            CarCards.recalls(
                VehicleController.label(vehicle), vehicle, recalls.map { it.summary }, ZoneId.systemDefault(),
            ),
        )

    /** get_code_history's events for [vehicle], as the tool returns them. */
    fun codeHistory(vehicle: Vehicle, events: JSONArray) =
        publish(CarCards.codeHistory(VehicleController.label(vehicle), vehicle, events, ZoneId.systemDefault()))

    /** An `ask_engine` tool result ([com.kevin.legion.service.AskEngine]'s card hook). */
    fun engineResult(answer: EngineToolAnswer) {
        EngineCards.fromAnswer(answer)?.let(::publish)
    }

    /** [codes] as read off the port; [meanings] the same dictionary the tool's text used. The car
     * is the one the dongle is in ([connectedMac]), the selected car only when that is unknown. */
    suspend fun codes(
        context: Context,
        connectedMac: String?,
        codes: List<String>,
        meanings: Map<String, Pair<String, String>>,
    ) {
        if (!ConversationCards.state.value.open) return
        val vehicle = connectedMac?.let { mac -> FleetEngineStore.getAll(context).firstOrNull { it.obdMac == mac } }
            ?: VehicleController.currentVehicle(context)
        publish(
            CarCards.codes(
                VehicleController.label(vehicle),
                vehicle,
                codes.map { it to meanings[it]?.first },
                ZoneId.systemDefault(),
            ),
        )
    }

    fun checklist(listName: String, lines: List<ChecklistLine>) = publish(PhoneCards.checklist(listName, lines))
}
