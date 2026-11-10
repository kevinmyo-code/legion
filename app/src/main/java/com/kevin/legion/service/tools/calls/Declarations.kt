@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.calls

import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The calls tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        // Answering and declining a ringing call by voice (2026-08-21, Kevin: "can i also pick it
        // up or decline via voice?"). Two tools rather than one with a boolean: the live model
        // picks a tool far more reliably than it fills a parameter, and mixing up "answer" and
        // "decline" behind one argument is the one mistake here with a real cost.
        //
        // Both descriptions name the SELF-MANAGED limit outright. Telecom silently ignores
        // WhatsApp/Signal/Teams calls for a non-dialer app, so a tool that promised to answer
        // "a call" would be lying about a whole class of them.
        add(fn(
            name = "answer_call",
            description = "Answer the phone call that is RINGING right now. Use when the user says " +
                "to pick it up, take it, or answer. Only works while a call is actually ringing, " +
                "and only for normal phone calls - Android does not let this app answer WhatsApp, " +
                "Signal, Teams or other in-app calls. Pass speaker=true when the user asks for it on speaker or " +
                "speakerphone in the same breath - do NOT use a separate tool for that.",
            params = obj(
                "speaker" to schema("boolean", "True if the user asked for the call on " +
                    "speakerphone, e.g. \"pick it up and put it on speaker\". Omit otherwise."),
            ),
            required = emptyList(),
        ))

        add(fn(
            name = "decline_call",
            description = "Decline or reject the phone call that is RINGING right now. Use when the " +
                "user says to decline, reject, hang up on it, or ignore it. Only works while a call " +
                "is actually ringing, and not for WhatsApp, Signal, Teams or other in-app calls. " +
                "Cannot end an emergency call.",
            params = obj(),
            required = emptyList(),
        ))

        // place_call (ticket 26, resolved at 05-comms.md): outbound calls only, texts ruled out
        // permanently. ONE tool covers both targets (a contact name or spoken digits) rather than
        // two, because the confirm shape and the failure handling are identical either way and the
        // description below tells the model which argument to fill. The confirmed boolean is the
        // SAME shape as activate_garage/clear_codes: confirmed=false resolves the target and
        // returns the exact sentence to read back - never dials - and only confirmed=true, after
        // the user says yes to that read-back, actually places the call. This is not optional
        // politeness: spoken digits are misheard often enough that the read-back is the only thing
        // standing between a wrong number and a stranger's phone ringing.
        add(fn(
            name = "place_call",
            description = "Place an OUTBOUND phone call, by contact name or by a number the user " +
                "dictates. NEVER for texts or messages - this app cannot send one, say so if asked. " +
                "NEVER for emergency services (911, 112, 999, or any local equivalent) - refuse and " +
                "tell the user to dial it themselves; do not call this tool for one. Pass exactly " +
                "one of 'contact' or 'number'. ALWAYS call this first with confirmed=false: the " +
                "result gives you the exact sentence to read back to the user (the contact's name, " +
                "or the number's digits grouped for speech) - say that sentence, wait for a yes, " +
                "then call this again with confirmed=true. Never skip the read-back or say the call " +
                "was placed unless the result says so. If the name matches nobody, or matches more " +
                "than one person, the result says so and you should ask which one - never guess.",
            params = obj(
                "contact" to schema("string", "A contact name the user asked to call, e.g. \"Mom\" " +
                    "or \"Sam\". Omit if they gave a number instead."),
                "number" to schema("string", "A phone number the user dictated, digits only (e.g. " +
                    "\"5551234567\"). Omit if they named a contact instead."),
                "confirmed" to schema("boolean", "False on the first call to resolve the target and " +
                    "get the read-back sentence. True only after the user has just confirmed it."),
            ),
            required = listOf("confirmed"),
        ))
    }
}
