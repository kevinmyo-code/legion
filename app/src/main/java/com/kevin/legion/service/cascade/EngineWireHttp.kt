package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [EngineWire] over OkHttp (already a dependency; no new one). The token goes only into the
 * `Authorization` header and is never logged.
 *
 * The streamed utterance is one HTTP/1.1 chunked POST whose body is written while he speaks: each
 * frame is written and flushed as it is queued, and the body ends right after `E`. The engine's
 * answer (Server-Sent Events) starts only after `E`, so OkHttp's ordinary request-then-response
 * call fits; the response is read line by line into a channel.
 */
internal class EngineWireHttp(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = SHARED,
) : EngineWire {

    override suspend fun attach(manifest: JSONObject): WireReply = call(post(LIMB, manifest))

    override fun openStream(meta: JSONObject): EngineUpload = StreamUpload(meta)

    override suspend fun turnClip(fields: Map<String, String>, wav: ByteArray): WireReply {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> form.addFormDataPart(k, v) }
        form.addFormDataPart("audio", "turn.wav", wav.toRequestBody(WAV))
        return call(request(TURN).post(form.build()).build())
    }

    override suspend fun turnText(body: JSONObject): WireReply = call(post(TURN, body))

    override suspend fun toolResult(turnId: String, body: JSONObject): WireReply =
        call(post("$TURN/$turnId/tool_result", body))

    override suspend fun cancel(turnId: String): WireReply = call(post("$TURN/$turnId/cancel", JSONObject()))

    override suspend fun status(turnId: String): WireReply = call(request("$TURN/$turnId").get().build())

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(baseUrl().trimEnd('/') + path)
        token()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Token $it") }
        return b
    }

    private fun post(path: String, body: JSONObject): Request =
        request(path).post(body.toString().toRequestBody(JSON)).build()

    private suspend fun call(req: Request): WireReply = withContext(Dispatchers.IO) {
        try {
            EngineResponses.shape(client.newCall(req).execute(), scope)
        } catch (e: IOException) {
            WireReply.Unreachable(e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            // No usable engine address saved.
            WireReply.Unreachable("bad_address_${e.javaClass.simpleName}")
        }
    }

    /** The streamed utterance: frames queued by the mic thread, written by OkHttp's call thread. */
    private inner class StreamUpload(meta: JSONObject) : EngineUpload {
        private val frames = LinkedBlockingQueue<ByteArray>()
        private val answer = CompletableDeferred<WireReply>()
        @Volatile private var closed = false
        private val call: Call

        init {
            frames.add(EngineFrames.meta(meta.toString()))
            call = client.newCall(request(STREAM).post(body()).build())
            scope.launch(Dispatchers.IO) {
                val r = try {
                    EngineResponses.shape(call.execute(), scope)
                } catch (e: IOException) {
                    WireReply.Unreachable(e.javaClass.simpleName)
                } catch (e: IllegalArgumentException) {
                    WireReply.Unreachable("bad_address_${e.javaClass.simpleName}")
                }
                answer.complete(r)
            }
        }

        private fun body() = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = -1L
            override fun writeTo(sink: BufferedSink) {
                var frame = frames.take()
                while (frame !== EOF) {
                    sink.write(frame)
                    sink.flush()
                    frame = frames.take()
                }
            }
        }

        override fun send(frame: ByteArray): Boolean = !closed && frames.offer(frame)

        override fun finish() {
            closed = true
            frames.offer(EOF)
        }

        override fun abort() {
            closed = true
            frames.offer(EOF)
            call.cancel()
        }

        override suspend fun reply(): WireReply = answer.await()
    }

    companion object {
        private const val LIMB = "/api/agents/limb"
        private const val TURN = "/api/agents/turn"
        private const val STREAM = "/api/agents/turn/stream"
        private val JSON = "application/json".toMediaType()
        private val WAV = "audio/wav".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val EOF = ByteArray(0)
        private const val CONNECT_S = 10L
        private const val READ_S = 30L

        /** One client for the process; a turn's stream may sit quiet up to [READ_S] while the engine works. */
        private val SHARED: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
                .readTimeout(READ_S, TimeUnit.SECONDS)
                .writeTimeout(READ_S, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** An engine answer as a [WireReply]: an event stream read into a channel, or the JSON answer. */
internal object EngineResponses {
    private const val SSE = "text/event-stream"
    private const val DETAIL_CHARS = 300

    fun shape(response: Response, scope: CoroutineScope): WireReply {
        val type = response.header("Content-Type").orEmpty()
        return if (response.isSuccessful && type.startsWith(SSE)) events(response, scope) else json(response)
    }

    private fun json(response: Response): WireReply.Json = response.use {
        val text = it.body?.string().orEmpty()
        val body = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject().put("detail", text.take(DETAIL_CHARS))
        WireReply.Json(it.code, body, it.header("Retry-After")?.trim()?.toLongOrNull())
    }

    private fun events(response: Response, scope: CoroutineScope): WireReply.Events {
        val out = Channel<EngineEvent>(Channel.UNLIMITED)
        scope.launch(Dispatchers.IO) {
            val reader = EngineSseReader()
            try {
                val source = response.body?.source()
                var line = source?.readUtf8Line()
                while (line != null) {
                    reader.line(line)?.let { out.trySend(it) }
                    line = source?.readUtf8Line()
                }
            } catch (ignored: IOException) {
                // A dropped or closed stream: the channel ends with no `done` (closed without a cause,
                // so no reader rethrows it), and the relay recovers or the turn ends in words.
                out.close()
            } finally {
                out.close()
                response.close()
            }
        }
        return WireReply.Events(out) { response.close() }
    }
}
