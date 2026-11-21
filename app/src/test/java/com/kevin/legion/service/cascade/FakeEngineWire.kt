package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake engine behind [EngineWire] for the engine-brain JVM tests: each route answers from a
 * queue the test fills, as raw Server-Sent Event text (parsed by the real [EngineSseReader]) or
 * JSON. Every request is recorded. Invented data only.
 */
internal class FakeEngineWire : EngineWire {
    val posts = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    val cancels = CopyOnWriteArrayList<String>()
    val statusAsked = CopyOnWriteArrayList<String>()
    val clips = CopyOnWriteArrayList<Map<String, String>>()
    val texts = CopyOnWriteArrayList<JSONObject>()
    val uploads = CopyOnWriteArrayList<FakeUpload>()
    var manifest: JSONObject? = null

    var attachAnswer: WireReply = json(200, """{"brain": "engine", "limb_id": "l1"}""")
    val streamAnswers = ArrayDeque<WireReply>()
    val clipAnswers = ArrayDeque<WireReply>()
    val textAnswers = ArrayDeque<WireReply>()
    val resultAnswers = ArrayDeque<WireReply>()
    val statusAnswers = ArrayDeque<WireReply>()

    override suspend fun attach(manifest: JSONObject): WireReply {
        this.manifest = manifest
        return attachAnswer
    }

    override fun openStream(meta: JSONObject): EngineUpload =
        FakeUpload(meta, streamAnswers.removeFirstOrNull() ?: WireReply.Unreachable("no answer queued")).also {
            uploads.add(it)
        }

    override suspend fun turnClip(fields: Map<String, String>, wav: ByteArray): WireReply {
        clips.add(fields)
        return clipAnswers.removeFirstOrNull() ?: WireReply.Unreachable("no answer queued")
    }

    override suspend fun turnText(body: JSONObject): WireReply {
        texts.add(body)
        return textAnswers.removeFirstOrNull() ?: WireReply.Unreachable("no answer queued")
    }

    override suspend fun toolResult(turnId: String, body: JSONObject): WireReply {
        posts.add(turnId to body)
        return resultAnswers.removeFirstOrNull() ?: json(200, """{"recorded": true, "status": "late"}""")
    }

    val decisions = CopyOnWriteArrayList<String>()

    override suspend fun decide(turnId: String, go: Boolean): WireReply {
        decisions.add("${if (go) "go" else "take"}:$turnId")
        return json(200, """{"decision": "${if (go) "go" else "take"}"}""")
    }

    override suspend fun cancel(turnId: String): WireReply {
        cancels.add(turnId)
        return json(200, """{"detail": "Cancelled."}""")
    }

    override suspend fun status(turnId: String): WireReply {
        statusAsked.add(turnId)
        return statusAnswers.removeFirstOrNull() ?: json(404, """{"code": "no_turn"}""")
    }

    /** One streamed upload: every frame the phone queued, in order. */
    class FakeUpload(val meta: JSONObject, private val answer: WireReply) : EngineUpload {
        val body = ByteArrayOutputStream()
        var finished = false
        var aborted = false
        val sent = CompletableDeferred<Unit>()

        init {
            body.write(EngineFrames.meta(meta.toString()))
        }

        override fun send(frame: ByteArray): Boolean {
            if (finished || aborted) return false
            body.write(frame)
            return true
        }

        override fun finish() {
            finished = true
            sent.complete(Unit)
        }

        override fun abort() {
            aborted = true
            sent.complete(Unit)
        }

        override suspend fun reply(): WireReply = answer
    }

    companion object {
        fun json(status: Int, body: String, retryAfterS: Long? = null): WireReply.Json =
            WireReply.Json(status, JSONObject(body), retryAfterS)

        /** A whole event stream from SSE text, read by the real parser. */
        fun sse(text: String): WireReply.Events {
            val out = Channel<EngineEvent>(Channel.UNLIMITED)
            val reader = EngineSseReader()
            (text.trimIndent() + "\n\n").lines().forEach { line -> reader.line(line)?.let { out.trySend(it) } }
            out.close()
            return WireReply.Events(out) {}
        }

        fun event(type: String, data: String): String = "event: $type\ndata: $data\n"
    }
}
