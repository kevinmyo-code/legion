package com.kevin.legion.backend.engine

import com.kevin.legion.greeting.FactsParser
import com.kevin.legion.greeting.FactsPack
import com.kevin.legion.greeting.GreetingBackend
import com.kevin.legion.greeting.GreetingOutcome
import org.json.JSONException
import org.json.JSONObject

private const val FACTS_PATH = "/api/greeting/facts"
private const val TOPICS_PATH = "/api/greeting/topics"

/**
 * [GreetingBackend] over `/api/greeting/` (`server/greeting/views.py`) with the device token
 * [EngineHttp] attaches. An engine from before the route answers an HTML 404: that is
 * [GreetingOutcome.Refused], never an error spoken (the greeting falls back to the scripted line).
 */
class DjangoGreetingBackend(private val http: EngineHttp) : GreetingBackend {

    override suspend fun facts(): GreetingOutcome<FactsPack> {
        val result = http.get(FACTS_PATH)
        val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
        return when {
            engineRouteMissing(result) -> GreetingOutcome.Refused(
                "The engine has no greeting facts yet.",
                HTTP_NOT_FOUND,
            )
            result.isSuccess -> try {
                GreetingOutcome.Ok(FactsParser.parse(result.getOrThrow().body))
            } catch (e: JSONException) {
                GreetingOutcome.Unconfirmed("The engine's greeting facts did not decode: ${e.message}")
            }
            failure is EngineFailure.Unreachable -> GreetingOutcome.Unreachable(failure.message)
            failure is EngineFailure.Unauthorized -> GreetingOutcome.Refused(failure.sentence, failure.status)
            failure is EngineFailure.Refused -> GreetingOutcome.Refused(
                engineRefusalSentence(failure.body),
                failure.status,
            )
            failure is EngineFailure.Malformed -> GreetingOutcome.Unconfirmed(failure.sentence)
            else -> GreetingOutcome.Unconfirmed(result.exceptionOrNull()?.message ?: "unknown error")
        }
    }

    override suspend fun recordTopic(topicKey: String, state: String): Boolean {
        val body = JSONObject().put("topic_key", topicKey).put("state", state).toString()
        return http.post(TOPICS_PATH, body).isSuccess
    }
}
