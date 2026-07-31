package com.kevin.legion.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON assembly and parsing for the Gemini v1beta `generateContent`
 * function-calling loop [SubAgent.investigate] runs. No Android imports, so it
 * is unit-testable against canned response strings - the part most prone to
 * subtle protocol bugs (verbatim model-turn echo, parallel calls, the
 * functionResponse role/wrapper shape).
 *
 * Protocol facts baked in here (verified against the current v1beta docs):
 *  - Declarations use camelCase `functionDeclarations`.
 *  - A response's `candidates[0].content.parts` may hold SEVERAL functionCall
 *    parts (parallel calls) interleaved with text/thought parts - scan all.
 *  - The model turn must be echoed back verbatim (parts can carry a
 *    `thoughtSignature` that must survive), so [modelContent] returns the raw
 *    content object.
 *  - Each functionResponse's `response` must be a JSON OBJECT, and every issued
 *    call needs a matching response (order preserved, id echoed if present).
 */
object AgentProtocol {
    data class FunctionCall(val name: String, val args: JSONObject, val id: String?)
    data class ToolResult(val name: String, val id: String?, val response: JSONObject)

    /** `{"functionDeclarations":[...]}` from [tools] (camelCase, verified). */
    fun declarations(tools: List<AgentTool>): JSONObject {
        val decls = JSONArray()
        for (t in tools) {
            decls.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", t.params)
                            .put("required", JSONArray(t.required)),
                    ),
            )
        }
        return JSONObject().put("functionDeclarations", decls)
    }

    /**
     * ALL functionCall parts across `candidates[0].content.parts`, in order.
     * Text and thought parts are ignored. Empty if none (a text-only answer).
     */
    fun functionCalls(responseJson: String): List<FunctionCall> {
        val out = mutableListOf<FunctionCall>()
        val parts = partsOf(responseJson) ?: return out
        for (i in 0 until parts.length()) {
            val fc = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
            val name = fc.optString("name").takeIf { it.isNotBlank() } ?: continue
            val args = fc.optJSONObject("args") ?: JSONObject()
            val id = fc.optString("id").takeIf { it.isNotBlank() }
            out.add(FunctionCall(name, args, id))
        }
        return out
    }

    /** `candidates[0].content` verbatim (raw JSONObject) or null. */
    fun modelContent(responseJson: String): JSONObject? = try {
        JSONObject(responseJson)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
    } catch (e: Exception) {
        null
    }

    /** Concatenated text parts of `candidates[0].content`, or null if blank/absent. */
    fun answerText(responseJson: String): String? {
        val parts = partsOf(responseJson) ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().ifBlank { null }
    }

    /** `promptFeedback.blockReason` or null. */
    fun blockReason(responseJson: String): String? = try {
        JSONObject(responseJson)
            .optJSONObject("promptFeedback")
            ?.optString("blockReason")
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * ONE role-"user" content carrying all functionResponse parts in [results]
     * order; if [finalNudge] != null, append a trailing `{"text": finalNudge}`
     * part (used on the forced-answer close-out round).
     */
    fun functionResponseContent(results: List<ToolResult>, finalNudge: String? = null): JSONObject {
        val parts = JSONArray()
        for (r in results) {
            val fr = JSONObject().put("name", r.name)
            if (r.id != null) fr.put("id", r.id)
            fr.put("response", r.response)
            parts.put(JSONObject().put("functionResponse", fr))
        }
        if (finalNudge != null) parts.put(JSONObject().put("text", finalNudge))
        return JSONObject().put("role", "user").put("parts", parts)
    }

    private fun partsOf(responseJson: String): JSONArray? = try {
        JSONObject(responseJson)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
    } catch (e: Exception) {
        null
    }
}
