package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.purchases.PurchaseFailures
import com.kevin.legion.purchases.PurchaseOutcome
import com.kevin.legion.purchases.PurchaseWording
import com.kevin.legion.purchases.PurchasesController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The household bought log's voice surface (purchase-log ticket 08), as ONE `bought_log` tool with
 * an `action` rather than three, because [LiveSetupPayloadSizeTest] holds the Live setup message
 * under a byte ceiling with almost no headroom and each declaration costs its own framing - the
 * same trade `manage_checklist` made. Lives in its own file, like [EngineToolbox], rather than
 * growing [LiveToolbox] further; `tools/voice_guide.py` and `VoiceGuideDataTest` scan this file too.
 *
 * **Every action calls [PurchasesController], the same controller the screens call** (ADR 0035), and
 * every result says in words what did and did not happen (CLAUDE.md section 7's outcome-verb rule):
 * `log` is reported done ONLY from a 2xx ([PurchaseOutcome.Ok]); an unreachable engine says nothing
 * was logged; a no-match answer says "no record", never "never bought" (ticket 02). The log is
 * online-only (ticket 04), so with the engine down this answers in words and never with an empty
 * list.
 *
 * This is not third-party content: the household's own entries, logged by its own members, so the
 * read-through rule (section 7) does not apply. Entries Mia marked private are filtered by the
 * engine for Kevin, not here.
 */
object PurchaseToolbox {

    const val TOOL_NAME = "bought_log"

    /** Test seam: swapped for a controller over a fake backend. Production builds the real one. */
    internal var controllerFactory: (Context) -> PurchasesController = PurchasesController::forContext

    private const val DESCRIPTION =
        "Household bought log. action=log records a purchase (today unless dated); last answers " +
            "'when did we last buy X', naming the matched entry; recent lists the newest. Report " +
            "only the result: logged only if success is true, unreachable means nothing was " +
            "logged. No match is no record, never 'never bought'. Price is typed by hand."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "bought_log",
            description = DESCRIPTION,
            params = JSONObject()
                .put("action", prop("string", "log, last or recent", listOf("log", "last", "recent")))
                .put("item", prop("string", "What was bought, or looked for."))
                .put("date", prop("string", "YYYY-MM-DD, if not today."))
                .put("store", prop("string"))
                .put("price_cents", prop("integer", "Cents (4.99 is 499), if said."))
                .put("note", prop("string"))
                .put("private", prop("boolean", "True only if asked.")),
            required = listOf("action"),
        ),
    )

    // `name = "..."` written out in the call above, not via TOOL_NAME: tools/voice_guide.py and
    // VoiceGuideDataTest find tool names by scanning for that exact shape.
    private fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)),
            )

    private fun prop(type: String, description: String? = null, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type)
            .apply { if (description != null) put("description", description) }
            .apply { if (enum != null) put("enum", JSONArray(enum)) }

    /** Null when [name] is not this tool, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name != TOOL_NAME) return null
        val controller = controllerFactory(context)
        return when (args.optString("action")) {
            "log" -> log(controller, args)
            "last" -> last(controller, args.optString("item"))
            "recent" -> recent(controller)
            else -> result(false, "Say whether to log, look up or list purchases. Nothing was changed.")
        }
    }

    private suspend fun log(controller: PurchasesController, args: JSONObject): JSONObject {
        val item = args.optString("item").trim()
        val dateText = args.optString("date").trim()
        val boughtOn = if (dateText.isEmpty()) null else parseDay(dateText)
        if (dateText.isNotEmpty() && boughtOn == null) {
            return result(false, "I couldn't read \"$dateText\" as a date, so I didn't log ${item.ifEmpty { "that" }}.")
        }
        val price = if (args.has("price_cents") && !args.isNull("price_cents")) args.optLong("price_cents") else null
        val outcome = controller.log(
            item = item,
            boughtOn = boughtOn,
            store = args.optString("store").ifBlank { null },
            priceCents = price,
            note = args.optString("note").ifBlank { null },
            isPrivate = args.optBoolean("private", false),
        )
        return when (outcome) {
            is PurchaseOutcome.Ok -> result(true, PurchaseWording.logged(outcome.value, controller.today()))
            is PurchaseOutcome.Refused -> if (item.isEmpty()) {
                result(false, outcome.sentence)
            } else {
                result(false, PurchaseFailures.logFailed(outcome, item))
            }
            else -> result(false, PurchaseFailures.logFailed(outcome, item))
        }
    }

    private suspend fun last(controller: PurchasesController, item: String): JSONObject =
        when (val outcome = controller.lastBought(item)) {
            is PurchaseOutcome.Ok ->
                result(true, PurchaseWording.lastBoughtAnswer(item.trim(), outcome.value, controller.today()))
            // A blank item is refused by the controller in its own words, which already say what
            // happened; every other failure gets the shared wording.
            is PurchaseOutcome.Refused -> result(
                false,
                if (item.isBlank()) outcome.sentence else PurchaseFailures.readFailed(outcome, lookingUp(item)),
            )
            else -> result(false, PurchaseFailures.readFailed(outcome, lookingUp(item)))
        }

    private fun lookingUp(item: String) = "look up ${item.trim()}"

    private suspend fun recent(controller: PurchasesController): JSONObject =
        when (val outcome = controller.recent()) {
            is PurchaseOutcome.Ok -> result(true, PurchaseWording.recentAnswer(outcome.value, controller.today()))
            else -> result(false, PurchaseFailures.readFailed(outcome, "read the recent purchases"))
        }

    /** The ISO date as a local epoch day, or null when it does not parse. */
    private fun parseDay(text: String): Int? = try {
        LocalDate.parse(text).toEpochDay().toInt()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
