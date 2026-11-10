@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.mail

import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The mail tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        // Gmail, ticket 15 (google-account-integration). Two tools, descriptions copied
        // VERBATIM from ticket 05's Answer table - a description is the only thing the model
        // ever reads, so this repo does not paraphrase or "improve" a description that was
        // already decided. Net +2 against a budget of 69 (-> 71); see that ticket for why this
        // is the one domain landing without retiring anything to pay for it.
        add(fn(
            name = "search_mail",
            description = "Search Kevin's Gmail. `query` uses Gmail search syntax; plain words " +
                "search full text. Returns sender, subject, date and a one-line snippet - never " +
                "the full message. Call with no query for a briefing of unread mail from the " +
                "last two days. Read-only; you cannot send, reply to, or delete mail. The result's " +
                "`query` field is the search that actually ran - if you translated Kevin's words " +
                "into it yourself, say that query back to him so a bad translation is visible " +
                "rather than a confident wrong answer.",
            params = obj(
                "query" to schema("string", "Gmail search syntax (from:, subject:, after:, plain " +
                    "words for full text). Omit entirely for the unread briefing."),
                "limit" to schema("integer", "Optional: how many results to return. The app " +
                    "enforces its own hard cap regardless of what's asked for."),
            ),
            required = listOf(),
        ))

        add(fn(
            name = "read_mail",
            description = "Fetch the full text of ONE message by the id returned from " +
                "`search_mail`. Only call this when Kevin asks about a specific message; say " +
                "that you are opening it.",
            params = obj(
                "id" to schema("string", "The message id from a previous search_mail result."),
            ),
            required = listOf("id"),
        ))

        // Ticket 25 (hands-and-senses, building ticket 18's resolution): "where's my package" /
        // "when's my flight", answered from mail LEGION can already read. Declared DIRECTLY here
        // rather than folded into `search_mail`/`read_mail` or routed through `ask_mail`'s generic
        // dispatcher - see `mailExtraction`'s own doc comment (this file, mail-tools section) for
        // why letting a free-form sub-agent compose the whole answer was the wrong shape for a §4
        // rule 5 extraction with no printed total to check it against. The four rules from ticket
        // 18's resolution are spelled out below IN the description because this is the one place
        // the live model actually reads before deciding whether to call the tool at all - a rule
        // that only lived in code the model never sees is not a rule the model can obey.
        add(fn(
            name = "track_package",
            description = "Where's my package? Reads Kevin's most recent shipping/order-confirmation " +
                "email for carrier, tracking number, and delivery status EXACTLY as that email " +
                "states them - mail only, never a live carrier lookup, so it can be stale. The " +
                "result always names which email it read and when it was sent, and always says " +
                "the answer is an ESTIMATE of what the mail says, never a confirmed live status - " +
                "say both of those things out loud, do not drop them. If no shipping mail is " +
                "found, or mail cannot be reached, the result says which of those happened.",
            params = obj(),
            required = emptyList(),
        ))

        add(fn(
            name = "flight_status",
            description = "When's my flight? CHECK `read_calendar` FIRST for the trip - a flight " +
                "the user (or Alfred) put on the calendar shows up there exactly, with no " +
                "guessing. Only call this tool when `read_calendar` " +
                "has nothing for the trip, or Kevin is asking about a hotel/flight that was never " +
                "added to the calendar. " +
                "Reads Kevin's most recent travel-confirmation email for the airline/hotel, flight " +
                "or confirmation number, and date/time EXACTLY as that email states them - mail " +
                "only, so it can be stale or wrong if the trip changed since. The result always " +
                "names which email it read and when it was sent, and always says the answer is an " +
                "ESTIMATE of what the mail says, never a confirmed schedule - say both of those " +
                "things out loud, do not drop them. If no travel mail is found, or mail cannot be " +
                "reached, the result says which of those happened.",
            params = obj(),
            required = emptyList(),
        ))
    }
}
