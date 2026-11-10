package com.kevin.legion.backend.engine

import com.kevin.legion.watches.Watch
import com.kevin.legion.watches.WatchFiring
import com.kevin.legion.watches.WatchKind
import com.kevin.legion.watches.WatchList
import com.kevin.legion.watches.WatchOutcome
import com.kevin.legion.watches.WatchSet
import com.kevin.legion.watches.WatchWording
import com.kevin.legion.watches.WatchesBackend
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val WATCHES_PATH = "/api/watches/"
private const val FIRINGS_PATH = "/api/watches/firings"
private const val ACK_PATH = "/api/watches/firings/ack"
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NO_CONTENT = 204
private const val HTTP_OK = 200

private val watchJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

private fun parseMs(stamp: String): Long = OffsetDateTime.parse(stamp).toInstant().toEpochMilli()

/** One row as `watches/service.watch_dict` renders it (`server/watches/service.py`). */
@Serializable
private data class WatchRow(
    val id: String,
    val kind: String,
    val title: String = "",
    val label: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val repeat: Boolean = true,
    val paused: Boolean = false,
    val status: String = "on",
    val builtin: String? = null,
    @SerialName("evaluated_on") val evaluatedOn: String = "engine",
    @SerialName("last_fired_at") val lastFiredAt: String? = null,
) {
    fun toWatch() = Watch(
        id = id,
        kind = kind,
        title = title,
        label = label,
        params = params,
        repeat = repeat,
        paused = paused,
        status = status,
        builtin = builtin,
        evaluatedOn = evaluatedOn,
        lastFiredAtMs = lastFiredAt?.let(::parseMs),
    )
}

@Serializable
private data class WatchKindRow(
    val kind: String,
    val title: String,
    val says: String = "",
    val needs: String = "",
    @SerialName("evaluated_on") val evaluatedOn: String = "engine",
)

@Serializable
private data class WatchListBody(val results: List<WatchRow> = emptyList(), val kinds: List<WatchKindRow> = emptyList())

@Serializable
private data class WatchCreatedBody(val watch: WatchRow, val note: String? = null)

/** `firing_dict` in `server/watches/service.py`. */
@Serializable
private data class WatchFiringRow(
    val id: String,
    @SerialName("watch_id") val watchId: String,
    @SerialName("watch_label") val watchLabel: String,
    val kind: String,
    val facts: String,
    @SerialName("fired_at") val firedAt: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    fun toFiring() = WatchFiring(id, watchId, watchLabel, kind, facts, parseMs(firedAt), parseMs(expiresAt))
}

@Serializable
private data class WatchFiringsBody(val results: List<WatchFiringRow> = emptyList())

@Serializable
private data class WatchAckedBody(val acked: Int = 0)

/**
 * [WatchesBackend] over the engine's `/api/watches/` routes (ADR 0083) with the device token
 * [EngineHttp] attaches. Where an [EngineHttp] failure becomes one of [WatchOutcome]'s four states:
 * unreachable stays unreachable, a 4xx is the engine's own sentence, a 2xx that does not decode is
 * unconfirmed (never "set").
 */
class DjangoWatchesBackend(private val http: EngineHttp) : WatchesBackend {

    override suspend fun list(): WatchOutcome<WatchList> = watchOutcome(http.get(WATCHES_PATH)) { body ->
        val parsed = watchJson.decodeFromString(WatchListBody.serializer(), body)
        WatchList(
            parsed.results.map { it.toWatch() },
            parsed.kinds.map { WatchKind(it.kind, it.title, it.says, it.needs, it.evaluatedOn) },
        )
    }

    override suspend fun create(
        kind: String,
        params: JsonObject,
        label: String?,
        source: String,
    ): WatchOutcome<WatchSet> {
        val body = JsonObject(
            buildMap {
                put("kind", JsonPrimitive(kind))
                put("params", params)
                put("source", JsonPrimitive(source))
                if (!label.isNullOrBlank()) put("label", JsonPrimitive(label))
            },
        )
        return watchOutcome(http.post(WATCHES_PATH, body.toString())) {
            val made = watchJson.decodeFromString(WatchCreatedBody.serializer(), it)
            WatchSet(made.watch.toWatch(), made.note)
        }
    }

    override suspend fun update(id: String, fields: JsonObject): WatchOutcome<Watch> =
        watchOutcome(http.patch(WATCHES_PATH + id, fields.toString())) {
            watchJson.decodeFromString(WatchRow.serializer(), it).toWatch()
        }

    /** 204: removed (null). 200 with the watch: a built-in, PAUSED instead of removed. */
    override suspend fun delete(id: String): WatchOutcome<Watch?> {
        val result = http.delete(WATCHES_PATH + id)
        return when (val status = result.getOrNull()?.status) {
            null, HTTP_NO_CONTENT -> watchOutcome(result) { null }
            HTTP_OK -> watchOutcome(result) { watchJson.decodeFromString(WatchRow.serializer(), it).toWatch() }
            else -> WatchOutcome.Unconfirmed("The engine answered HTTP $status, not a removal or a pause.")
        }
    }

    override suspend fun pending(): WatchOutcome<List<WatchFiring>> =
        watchOutcome(http.get(FIRINGS_PATH, mapOf("pending" to "1"))) { body ->
            watchJson.decodeFromString(WatchFiringsBody.serializer(), body).results.map { it.toFiring() }
        }

    override suspend fun recent(): WatchOutcome<List<WatchFiring>> =
        watchOutcome(http.get(FIRINGS_PATH)) { body ->
            watchJson.decodeFromString(WatchFiringsBody.serializer(), body).results.map { it.toFiring() }
        }

    override suspend fun ack(ids: List<String>, outcome: String): WatchOutcome<Int> {
        val body = JsonObject(
            mapOf("ids" to JsonArray(ids.map { JsonPrimitive(it) }), "outcome" to JsonPrimitive(outcome)),
        )
        return watchOutcome(http.post(ACK_PATH, body.toString())) {
            watchJson.decodeFromString(WatchAckedBody.serializer(), it).acked
        }
    }
}

private fun <T> watchOutcome(result: Result<EngineOk>, decode: (String) -> T): WatchOutcome<T> {
    val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure
    return when {
        // An engine from before watches (integrate 2026-11-10): said in words, never its HTML page.
        engineRouteMissing(result) -> WatchOutcome.Refused(WatchWording.NOT_ON_ENGINE, HTTP_NOT_FOUND)
        result.isSuccess -> try {
            WatchOutcome.Ok(decode(result.getOrThrow().body))
        } catch (e: SerializationException) {
            WatchOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
        } catch (e: DateTimeParseException) {
            WatchOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
        } catch (e: IllegalArgumentException) {
            WatchOutcome.Unconfirmed("The engine's reply did not decode: ${e.message}")
        }
        failure is EngineFailure.Unreachable -> WatchOutcome.Unreachable(failure.message)
        failure is EngineFailure.Unauthorized -> WatchOutcome.Refused(
            failure.body.takeIf { failure.status == HTTP_FORBIDDEN && it.isNotBlank() }
                ?.let(::engineRefusalSentence) ?: failure.sentence,
            failure.status,
        )
        failure is EngineFailure.Refused -> WatchOutcome.Refused(engineRefusalSentence(failure.body), failure.status)
        failure is EngineFailure.Malformed -> WatchOutcome.Unconfirmed(failure.sentence)
        else -> WatchOutcome.Unconfirmed(result.exceptionOrNull()?.message ?: "unknown error")
    }
}
