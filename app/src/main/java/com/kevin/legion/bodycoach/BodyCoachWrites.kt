package com.kevin.legion.bodycoach

import android.content.Context
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.finance.FinanceWords
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What a hand write did, in words. [ok] is only ever set from the engine's own 2xx. */
data class CoachWrite(val ok: Boolean, val sentence: String)

/** One write against the engine. A seam so the writes are tested with a fake. */
fun interface CoachWriteBackend {
    suspend fun send(method: String, path: String, json: String?): Result<String>
}

/**
 * The Body screen's hands paths onto the engine's body-coach writes (`.scratch/fat-loss/` ticket
 * 21, ADR 0035): profile, waist, saved meals, and accepting or dismissing a proposal. **The same
 * REST views the voice tools reach** (`server/engine_mcp/tools.py` writes through `call_route`), so
 * a tap and a sentence run one implementation.
 *
 * These tables live on the engine only (map, "Not yet specified"), so **a write here needs the
 * engine**: with it unreachable the sentence says nothing was saved, never "saved". Logging a saved
 * meal is NOT here: it goes through [com.kevin.legion.meals.MealController.logSavedMeal], the path
 * `log_meal` uses, which queues offline.
 */
class BodyCoachWrites(private val backend: () -> CoachWriteBackend?) {

    suspend fun putProfile(fields: JsonObject): CoachWrite =
        send("PUT", "$ROOT/profile/", fields.toString(), "Profile saved.")

    /**
     * Copies a weight goal onto the engine profile (fat-loss ticket 14, G1). The phone's goal rows do
     * not reach the engine, so the profile's `goal_weight_lbs` is what the engine's milestone and
     * protein read. Best effort: the caller never claims it.
     */
    suspend fun putGoalWeight(lbs: Double?): CoachWrite =
        putProfile(buildJsonObject { if (lbs == null) put("goal_weight_lbs", null as Double?) else put("goal_weight_lbs", lbs) })

    suspend fun logWaist(waistIn: Double): CoachWrite =
        send("POST", "$ROOT/waist/", buildJsonObject { put("waist_in", waistIn) }.toString(), "Waist logged.")

    suspend fun saveMeal(name: String, kcal: Int, proteinG: Double, description: String?): CoachWrite {
        val body = buildJsonObject {
            put("name", name)
            put("calories_kcal", kcal)
            put("protein_g", proteinG)
            if (!description.isNullOrBlank()) put("description", description)
        }
        return send("POST", "$ROOT/saved-meals/", body.toString(), "Saved meal added; its figures are an estimate.")
    }

    suspend fun deleteSavedMeal(id: String): CoachWrite =
        send("DELETE", "$ROOT/saved-meals/$id/", null, "Saved meal deleted.")

    suspend fun acceptProposal(id: String): CoachWrite =
        send("POST", "$ROOT/proposals/$id/accept/", "{}", "Target changed.")

    suspend fun dismissProposal(id: String): CoachWrite =
        send("POST", "$ROOT/proposals/$id/dismiss/", "{}", "Proposal dismissed; the target is unchanged.")

    @Suppress("ReturnCount")
    private suspend fun send(method: String, path: String, json: String?, done: String): CoachWrite {
        val engine = backend() ?: return CoachWrite(false, "Nothing was saved. " + FinanceWords.NO_ENGINE)
        val reply = engine.send(method, path, json)
        reply.getOrNull()?.let { body -> return CoachWrite(true, wordsOf(body) ?: done) }
        val failure = (reply.exceptionOrNull() as? EngineHttpException)?.failure
        if (failure is EngineFailure.Refused && failure.status in CLIENT_ERRORS) {
            val detail = detailOf(failure.body) ?: FinanceWords.refused(failure.status)
            return CoachWrite(false, if (detail.startsWith("Nothing was")) detail else "Nothing was saved: $detail")
        }
        return CoachWrite(false, "Nothing was saved. " + FinanceWords.why(failure))
    }

    companion object {
        private const val ROOT = "/api/body/coach"
        private val CLIENT_ERRORS = 400..499

        fun forApp(context: Context): BodyCoachWrites {
            val app = context.applicationContext
            return BodyCoachWrites {
                val http = EngineHttp(EngineConfig(app))
                if (!http.isUsable()) {
                    null
                } else {
                    CoachWriteBackend { method, path, json ->
                        when (method) {
                            "PUT" -> http.put(path, json ?: "{}")
                            "DELETE" -> http.delete(path)
                            else -> http.post(path, json ?: "{}")
                        }.map { it.body }
                    }
                }
            }
        }

        /** The engine's own `words` on a 2xx, when it sent one. */
        internal fun wordsOf(body: String): String? = runCatching {
            bodyCoachJson.parseToJsonElement(body).jsonObject["words"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }

        /** A 4xx's `detail`, or the first field error DRF names. */
        internal fun detailOf(body: String): String? = runCatching {
            val obj = bodyCoachJson.parseToJsonElement(body).jsonObject
            (obj["detail"] as? JsonPrimitive)?.content
                ?: obj.values.firstOrNull()?.let { v ->
                    (v as? JsonPrimitive)?.content ?: v.toString().trim('[', ']', '"')
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
