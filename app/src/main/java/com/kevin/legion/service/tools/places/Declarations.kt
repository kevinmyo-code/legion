@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.places

import com.kevin.legion.location.PlaceController
import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The places tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        add(fn(
            name = "set_reminder",
            // Note added one-today ticket 10 slice C, 2026-09-05: this already only ever created a
            // place-triggered REMINDER, never a plain todo, so no behavioural change - just the
            // redirect clause manage_item/read_list also carry now.
            description = "Save a reminder tied to one of the user's saved places, surfaced when " +
                "they next arrive there. Use for 'remind me to X when I get to / when I'm at the Y', " +
                "e.g. 'remind me to grab my gym bag when I get to the gym'. NOT for a plain to-do " +
                "with no place attached to it - use manage_checklist (list=Todo) for that instead.",
            params = obj(
                "place" to schema("string", "The saved place the reminder is for, e.g. home, work, gym, walmart."),
                "text" to schema("string", "What to remind the user about, in their own words, e.g. 'grab your gym bag'."),
            ),
            required = listOf("place", "text"),
        ))

        // Places by address (Kevin, 2026-10-09) and no silent destruction (voice audit finding 2).
        // `tag_place` grew an optional `address` rather than a second save tool. The descriptions are
        // deliberately short (LiveSetupPayloadSizeTest): the confirm and pick-one protocol is carried
        // by the tool RESULT, which says in words what was not saved and what to do next, and the
        // confirm itself is enforced in PlaceController, not by the model reading a description.
        add(fn(
            name = "tag_place",
            description = "Save a place under a label. With address: looks it up and saves there. " +
                "Without: saves the current location. Repeat the address the result says was saved. " +
                "If the result asks to confirm or choose, ask the user, then do as it says.",
            params = obj(
                "label" to schema("string", "e.g. home, work, gym."),
                "address" to schema("string", "Street address as said. Omit for the current location."),
                "confirmed" to schema("boolean", "True only after the user agreed to replace."),
            ),
            required = listOf("label"),
        ))

        add(fn(
            name = "rename_place",
            description = "Rename a saved place, keeping its location. Use for every rename.",
            params = obj("from" to schema("string", "Current label."), "to" to schema("string", "New label.")),
            required = listOf("from", "to"),
        ))

        add(fn(
            name = "forget_place",
            description = "Delete a saved place. Unconfirmed, deletes nothing and says what would be lost.",
            params = obj(
                "label" to schema("string", "e.g. work."),
                "confirmed" to schema("boolean", "True only after the user said yes."),
            ),
            required = listOf("label"),
        ))

        add(fn(
            name = "show_saved_places",
            description = "Show or hide the on-screen list of the user's saved places.",
            params = obj("visible" to schema("boolean", "True to show the list, false to hide it.")),
            required = listOf("visible"),
        ))
    }
}
