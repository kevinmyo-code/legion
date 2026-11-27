package com.kevin.legion.service.conversationlog

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import java.net.URLEncoder
import kotlinx.serialization.json.Json

/** How one send went: kept off the queue ([SENT], [DROP]) or left at its head for the next try. */
internal enum class TurnSendResult { SENT, DROP, RETRY }

/** The engine calls [TurnSender] makes, transport-neutral so a test needs no network. */
internal interface TurnTransport {
    suspend fun save(json: String): TurnSendResult
    suspend fun forget(session: String): TurnSendResult
}

/**
 * Sends each [TurnAction] in order, one at a time. A turn that could not go (offline, the engine
 * down, no sign-in yet) stays at the head of a bounded queue and goes before the next one; a
 * refusal the engine gave in words is dropped, never retried. A forget first removes any queued
 * save of the same conversation, so nothing it forgets can arrive after it.
 *
 * The queue lives in memory only: the conversation record ([ConversationLog]) is still the durable
 * path, and the hourly memory job still reads it.
 */
internal class TurnSender(
    private val transport: TurnTransport,
    private val maxPending: Int = MAX_PENDING,
) {
    private val pending = ArrayDeque<TurnAction>()

    val waiting: Int get() = pending.size

    suspend fun send(action: TurnAction) {
        if (action is TurnAction.Forget) {
            pending.removeAll { it is TurnAction.Save && it.body.session == action.session }
        }
        pending.addLast(action)
        while (pending.size > maxPending) pending.removeFirst()
        drain()
    }

    private suspend fun drain() {
        while (pending.isNotEmpty()) {
            val result = when (val head = pending.first()) {
                is TurnAction.Save -> transport.save(turnJson.encodeToString(TurnBody.serializer(), head.body))
                is TurnAction.Forget -> transport.forget(head.session)
            }
            if (result == TurnSendResult.RETRY) return
            pending.removeFirst()
        }
    }

    companion object {
        const val MAX_PENDING = 50
    }
}

internal val turnJson = Json { encodeDefaults = true }

/** Production [TurnTransport]: the phone's engine sign-in through [EngineHttp]. */
internal class EngineTurnTransport(private val http: EngineHttp) : TurnTransport {
    override suspend fun save(json: String): TurnSendResult = classify(http.post(PATH, json))

    override suspend fun forget(session: String): TurnSendResult =
        classify(http.delete("$PATH?session=${URLEncoder.encode(session, "UTF-8")}"))

    private fun classify(result: Result<*>): TurnSendResult =
        result.fold(onSuccess = { TurnSendResult.SENT }, onFailure = { resultOf(it) })

    companion object {
        const val PATH = "/api/member-memories/turns"
        private const val UNAUTHORIZED = 401
        private const val TIMEOUT = 408
        private const val TOO_MANY = 429
        private val CLIENT_ERROR = 400..499

        /** Not reachable, not signed in yet, or the engine failed on its side: try again later. */
        fun resultOf(e: Throwable): TurnSendResult = when (val failure = (e as? EngineHttpException)?.failure) {
            is EngineFailure.Unreachable -> TurnSendResult.RETRY
            is EngineFailure.Unauthorized ->
                if (failure.status == UNAUTHORIZED) TurnSendResult.RETRY else TurnSendResult.DROP
            is EngineFailure.Refused -> when (failure.status) {
                TIMEOUT, TOO_MANY -> TurnSendResult.RETRY
                in CLIENT_ERROR -> TurnSendResult.DROP
                else -> TurnSendResult.RETRY
            }
            is EngineFailure.Malformed -> TurnSendResult.DROP
            null -> TurnSendResult.RETRY
        }
    }
}
