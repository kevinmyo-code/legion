package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.books.BookOutcome
import com.kevin.legion.books.BooksController
import com.kevin.legion.books.forContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Books voice surface (ADR 0061, books ticket 02): ONE `books` tool with an `action`, for the
 * same reason `bought_log` is one - [LiveSetupPayloadSizeTest] holds the Live setup under a byte
 * ceiling and each declaration costs its own framing. Lives in its own file, like
 * [PurchaseToolbox], rather than growing [LiveToolbox]; `tools/voice_guide.py` scans this file too.
 *
 * **Every action calls [BooksController], the same controller the Books sheet buttons call** (ADR
 * 0035), and the result is that controller's own sentence: success only after the reader was SEEN
 * to open, and every failure says what did NOT happen (no folder, book not found, CBR, no
 * text-to-speech engine). The model is told to repeat the sentence and nothing more, so it cannot
 * claim a book is open or being read when the reader never came up (CLAUDE.md section 7).
 */
object BooksToolbox {

    const val TOOL_NAME = "books"

    /** Test seam: swapped for a controller over a fake catalog. Production builds the real one. */
    internal var controllerFactory: (Context) -> BooksController = { BooksController.forContext(it) }

    private const val DESCRIPTION =
        "Open a book from the phone's Books folder in the reader. action=open needs title; " +
            "action=continue reopens the last book read. listen=true also reads it aloud (ebooks only). " +
            "Say only the result: opened only if success is true; otherwise nothing was opened."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "books",
            description = DESCRIPTION,
            params = JSONObject()
                .put("action", prop("string", "open or continue", listOf("open", "continue")))
                .put("title", prop("string", "Book name, for open."))
                .put("listen", prop("boolean", "True to read it aloud.")),
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
        val listen = args.optBoolean("listen", false)
        val outcome = when (args.optString("action")) {
            "open" -> controller.openByName(args.optString("title"), listen)
            "continue" -> controller.continueReading(listen)
            else -> return result(false, "Say whether to open a named book or continue the last one. Nothing was opened.")
        }
        return when (outcome) {
            is BookOutcome.Opened -> result(true, outcome.sentence)
            is BookOutcome.Failed -> result(false, outcome.sentence)
        }
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
