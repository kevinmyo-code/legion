package com.kevin.legion.brief

import android.content.Context
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.HTTP_NOT_FOUND
import com.kevin.legion.backend.engine.translatingEngineCall

/** What `GET /api/brief/latest` answered, when it answered at all. */
sealed interface BriefReply {
    /** A brief, plus the exact body it was read from (the offline cache stores the body, not a re-encoding). */
    data class Got(val brief: ServerBrief, val raw: String) : BriefReply

    /** 404: the engine has built no brief for this member yet. [sentence] is the engine's own. */
    data class NoneYet(val sentence: String) : BriefReply
}

/**
 * The engine's two brief endpoints. A seam so the controller can be tested with a fake.
 * Every failure is a `Result.failure` carrying an [EngineHttpException], so the caller reads the
 * [EngineFailure] branch (409 / 503 / 429 arrive as [EngineFailure.Refused] with their status).
 */
interface BriefBackend {
    suspend fun latest(): Result<BriefReply>

    /** `POST /api/brief/refresh`. Builds one now, on the household's Gemini key. */
    suspend fun refresh(): Result<BriefReply.Got>
}

class DjangoBriefBackend(private val http: EngineHttp) : BriefBackend {

    override suspend fun latest(): Result<BriefReply> {
        val reply = http.get(LATEST)
        val failure = (reply.exceptionOrNull() as? EngineHttpException)?.failure
        if (failure is EngineFailure.Refused && failure.status == HTTP_NOT_FOUND) {
            val sentence = briefDetail(failure.body) ?: "The engine has no brief for you yet."
            return Result.success(BriefReply.NoneYet(sentence))
        }
        return translatingEngineCall("read the brief") {
            val body = reply.getOrThrow().body
            BriefReply.Got(parseBrief(body), body)
        }
    }

    override suspend fun refresh(): Result<BriefReply.Got> =
        translatingEngineCall("refresh the brief") {
            val body = http.post(REFRESH, "{}").getOrThrow().body
            BriefReply.Got(parseBrief(body), body)
        }

    private companion object {
        const val LATEST = "/api/brief/latest"
        const val REFRESH = "/api/brief/refresh"
    }
}

/** The backend for this device's engine, or null with no engine address or no signed-in token. */
fun djangoBriefBackendOrNull(context: Context): BriefBackend? {
    val http = EngineHttp(EngineConfig(context.applicationContext))
    return if (http.isUsable()) DjangoBriefBackend(http) else null
}
