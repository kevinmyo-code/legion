package com.kevin.legion.service.cascade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** The production [WireConnector]: an OkHttp WebSocket carrying the config's headers on the upgrade. */
class OkHttpWire(private val client: OkHttpClient = DEFAULT_CLIENT) : WireConnector {
    override fun connect(url: String, headers: List<Pair<String, String>>, listener: WireListener): WireSocket {
        val request = Request.Builder().url(url).apply { headers.forEach { (n, v) -> header(n, v) } }.build()
        val closed = AtomicBoolean(false)
        fun closedOnce(code: Int, reason: String) {
            if (closed.compareAndSet(false, true)) listener.onClosed(code, reason)
        }
        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    closedOnce(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closedOnce(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    closedOnce(response?.code ?: FAILURE_CODE, t.javaClass.simpleName)
            },
        )
        return object : WireSocket {
            override fun sendText(text: String): Boolean = ws.send(text)
            override fun sendBinary(bytes: ByteArray): Boolean = ws.send(bytes.toByteString())
            override fun close(code: Int, reason: String) {
                ws.close(code, reason)
            }
        }
    }

    companion object {
        /** Not an HTTP status: the socket failed with no response (refused, reset, TLS). */
        const val FAILURE_CODE = -1
        private const val CONNECT_TIMEOUT_MS = 3_000L

        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // a WebSocket waits on the user; pings keep it alive
                .build()
        }
    }
}

/**
 * `GET /health` on the laptop voice service, bounded by one timeout (contract section 2). The
 * answer decides, once per conversation, whether it runs on the laptop or on Gemini Live.
 */
object LaptopProbe {
    sealed interface Result {
        val token: String

        data class Ready(val ms: Long) : Result { override val token = "ready" }
        data class Busy(val status: String, val ms: Long) : Result { override val token = "busy_$status" }
        data object NotConfigured : Result { override val token = "not_configured" }
        data object Unauthorized : Result { override val token = "unauthorized" }
        data class WrongProtocol(val protocol: Int) : Result { override val token = "protocol_$protocol" }
        data class Unreachable(val reason: String, val ms: Long) : Result { override val token = "unreachable_$reason" }
    }

    /** The decision from a `/health` reply. Pure, so a test pins it. */
    fun interpret(code: Int, body: String?, ms: Long): Result {
        if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) return Result.Unauthorized
        if (code != HTTP_OK) return Result.Unreachable("http_$code", ms)
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return Result.Unreachable("bad_body", ms)
        val protocol = json.optInt("protocol", -1)
        if (protocol != LaptopVoiceConnection.PROTOCOL) return Result.WrongProtocol(protocol)
        return when (val status = json.optString("status")) {
            "ready" -> Result.Ready(ms)
            else -> Result.Busy(status.ifBlank { "unknown" }, ms)
        }
    }

    suspend fun probe(
        config: LaptopVoiceConfig,
        timeoutMs: Long,
        client: OkHttpClient = OkHttpWire.DEFAULT_CLIENT,
    ): Result {
        if (!config.isComplete) return Result.NotConfigured
        val started = System.currentTimeMillis()
        val bounded = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder().url(config.healthUrl).get()
            .apply { config.requestHeaders().forEach { (n, v) -> header(n, v) } }
            .build()
        return withContext(Dispatchers.IO) {
            val call = bounded.newCall(request)
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val ms = System.currentTimeMillis() - started
                        val reason = if (ms >= timeoutMs) "timeout" else e.javaClass.simpleName
                        if (cont.isActive) cont.resume(Result.Unreachable(reason, ms))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = response.use {
                            interpret(it.code, runCatching { it.body?.string() }.getOrNull(), System.currentTimeMillis() - started)
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                })
            }
        }
    }

    private const val HTTP_OK = 200
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
}
