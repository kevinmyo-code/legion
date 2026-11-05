package com.kevin.legion.service

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.ui.vault.VaultActivity
import com.kevin.legion.vault.VaultController
import com.kevin.legion.vault.VaultOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `show_vault_item {label}` (memory-and-vault ticket 12, ADR 0076): opens the ID vault screen on one
 * item, for the user to unlock with a fingerprint and read with their own eyes.
 *
 * **This tool never touches a value.** It reads the label list ([VaultController.list], which the
 * engine answers with labels and kinds only) to find the item, opens [VaultActivity], and returns
 * exactly [OPENED]. It never calls a reveal, so nothing it returns, and so nothing the model, the
 * speech, LIVE_AUDIT or the conversation record sees, can hold a value. A label that matches nothing
 * returns the label list (ruling: labels may be given, never values). The hands path is the same
 * screen from Settings (ADR 0035).
 */
object VaultToolbox {

    const val TOOL_NAME = "show_vault_item"

    /** The whole of a successful result's words, by ruling. */
    const val OPENED = "Opened on the phone."

    internal var controllerFactory: (Context) -> VaultController = VaultController::forContext

    /** Test seam: opens the screen and reports whether it came up. */
    internal var opener: suspend (Context, String) -> Boolean = ::openAndWait

    private const val DESCRIPTION =
        "Opens the ID vault on the phone at one item (passport, SSN, NRIC, A-number, bank account). " +
            "The user unlocks and reads it there; you never see or say the value."

    fun declarations(): JSONArray = JSONArray().put(fn(name = "show_vault_item", description = DESCRIPTION))

    // `name = "..."` written out in the call above: tools/voice_guide.py and VoiceGuideDataTest find
    // tool names by scanning for that exact shape.
    private fun fn(name: String, description: String): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("label", JSONObject().put("type", "string")))
                    .put("required", JSONArray(listOf("label"))),
            )

    /** Null when [name] is not this tool. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name != TOOL_NAME) return null
        return show(controllerFactory(context), context, args.optString("label"))
    }

    internal suspend fun show(controller: VaultController, context: Context, label: String): JSONObject {
        val items = when (val outcome = controller.list()) {
            is VaultOutcome.Ok -> outcome.value
            else -> return result(false, VaultController.failed(outcome, "opened"))
        }
        if (items.isEmpty()) {
            return result(
                false,
                "Nothing was opened: the ID vault is empty. Items are added on the phone's vault screen.",
            )
        }
        val item = VaultController.match(items, label)
            ?: return result(
                false,
                "Nothing was opened: no single vault item matches \"${label.trim()}\". The labels are: " +
                    items.joinToString(", ") { it.label } + ".",
            )
        return if (opener(context, item.label)) {
            result(true, OPENED)
        } else {
            result(
                false,
                "Nothing was opened: Android did not let LEGION bring the vault screen up. Open it from Settings.",
            )
        }
    }

    /** Starts the screen and waits briefly to see LEGION in front, the [NavScreenOpener] posture. */
    private suspend fun openAndWait(context: Context, label: String): Boolean {
        if (!VaultActivity.open(context.applicationContext, label)) return false
        return withContext(Dispatchers.Main) {
            var waited = 0L
            while (!visible() && waited < WAIT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            visible()
        }
    }

    private fun visible() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private const val WAIT_MS = 2_000L
    private const val POLL_MS = 100L

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
