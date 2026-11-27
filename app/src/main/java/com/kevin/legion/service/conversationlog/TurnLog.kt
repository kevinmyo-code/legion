package com.kevin.legion.service.conversationlog

import android.content.Context
import android.util.Log
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Each finished turn goes to the engine the moment it ends (issue #450, Kevin 2026-11-25: a new
 * chat asked "what did we talk about today?" knew nothing of a chat from minutes earlier, because
 * the conversation reached the engine as a 3-minute batch and memory an hour later).
 *
 * Rides the same switch as the conversation record ([ConversationLog.keepAndSend], "Keep the
 * conversation log"): off sends no turn. A forget is always sent, so a crisis conversation is
 * removed whatever the switch says now. Not initialised means silent: JVM tests and anything
 * before `MidnightApplication.onCreate` submit into nothing.
 */
object TurnLog {
    private const val TAG = "TURN_LOG"

    @Volatile private var queue: Channel<TurnAction>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun init(context: Context) {
        if (queue != null) return
        val app = context.applicationContext
        val sender = TurnSender(EngineTurnTransport(EngineHttp(EngineConfig(app))))
        val channel = Channel<TurnAction>(Channel.UNLIMITED)
        queue = channel
        scope.launch {
            for (action in channel) {
                runCatching { sender.send(action) }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "a turn was not sent: ${it::class.simpleName}")
                }
            }
        }
    }

    internal fun submit(action: TurnAction) {
        val channel = queue ?: return
        if (action is TurnAction.Save && !ConversationLog.keepAndSend) return
        channel.trySend(action)
    }
}
