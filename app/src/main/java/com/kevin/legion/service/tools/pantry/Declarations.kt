@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.pantry

import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The pantry tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
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
            name = "import_receipt",
            description = "Open the camera or gallery picker to log a grocery receipt into the " +
                "pantry. Use when the user asks to log, add, or scan a grocery receipt.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "list_recent_groceries",
            description = "Read back recently logged grocery items. Calorie/protein/carb/fat figures " +
                "are ESTIMATES the model guessed at ingestion time, never measured - always phrase " +
                "them as estimates, never as fact.",
            params = obj("count" to schema("integer", "How many items to return. Default 10.")),
            required = listOf(),
        ))

        add(fn(
            name = "get_grocery_spend",
            description = "Report total logged grocery spend across all receipts, broken out PER " +
                "CURRENCY - never add SGD and USD receipts into one number, state each currency's " +
                "total separately.",
            params = obj(),
            required = listOf(),
        ))
    }
}
