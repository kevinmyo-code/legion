package com.kevin.legion.service.cascade

import kotlinx.coroutines.channels.ReceiveChannel
import org.json.JSONObject

/**
 * How one engine request came back. Every route of the relay answers one of these three ways, so
 * the turn logic ([EngineRelay]) branches on the shape, never on a transport exception.
 */
internal sealed interface WireReply {
    /** `text/event-stream`: the events in order; the channel closes when the stream ends. */
    class Events(val events: ReceiveChannel<EngineEvent>, val close: () -> Unit) : WireReply

    /** Any other answer (a 2xx JSON, or a refusal): [body] is `{}` when it was not JSON. */
    data class Json(val status: Int, val body: JSONObject, val retryAfterS: Long? = null) : WireReply {
        val code: String get() = body.optString("code")
        val detail: String get() = body.optString("detail")
    }

    /** Nothing came back: offline, refused connection, a dropped socket. [reason] is a log token. */
    data class Unreachable(val reason: String) : WireReply
}

/** One streamed utterance's upload, open while he speaks (`POST /api/agents/turn/stream`). */
internal interface EngineUpload {
    /** Queues one frame; false once the upload failed or was closed (nothing more goes). */
    fun send(frame: ByteArray): Boolean

    /** Ends the body (right after the `E` frame). */
    fun finish()

    /** Drops the request: nothing runs on the engine (no `E` frame was sent). */
    fun abort()

    /** The engine's answer, once the body ended. */
    suspend fun reply(): WireReply
}

/**
 * The engine's agents routes as the phone uses them (relay contract, engine report section 11).
 * The production transport is [EngineWireHttp]; JVM tests use a fake engine behind this seam.
 */
internal interface EngineWire {
    /** `POST /api/agents/limb`: the manifest, answered with the brain that answers his turns. */
    suspend fun attach(manifest: JSONObject): WireReply

    /** `POST /api/agents/turn/stream`, opened with its `M` frame already queued. */
    fun openStream(meta: JSONObject): EngineUpload

    /** `POST /api/agents/turn` (multipart): one whole clip, the stream route's fallback. */
    suspend fun turnClip(fields: Map<String, String>, wav: ByteArray): WireReply

    /** `POST /api/agents/turn` (JSON): one transcript, for a voice whose words the phone already has. */
    suspend fun turnText(body: JSONObject): WireReply

    /** `POST /api/agents/turn/<id>/tool_result`. */
    suspend fun toolResult(turnId: String, body: JSONObject): WireReply

    /** `POST /api/agents/turn/<id>/cancel`. */
    suspend fun cancel(turnId: String): WireReply

    /** `GET /api/agents/turn/<id>`: the calls still waited for, after a dropped stream. */
    suspend fun status(turnId: String): WireReply
}
