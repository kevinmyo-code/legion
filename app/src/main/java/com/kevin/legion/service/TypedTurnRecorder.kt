package com.kevin.legion.service

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.EpisodicTurn
import com.kevin.legion.data.local.auditContent
import com.kevin.legion.data.local.record
import com.kevin.legion.vehicle.ActiveVehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * What a typed turn leaves behind, now that it no longer rides the Live socket (2026-10-27).
 *
 * Before the move, a typed turn reached episodic memory and the conversation audit trail through
 * `GeminiLiveSession`'s turnComplete like any spoken one. Leaving it unrecorded would silently drop
 * typed chat from memory, so this writes the same rows with the same rules:
 *
 * - **Episodic memory** ([EpisodicTurn], driver line plus reply): skipped WHOLE when a read-through
 *   tool ran this turn (CLAUDE.md sec 7: mail and anything of that shape is never stored), exactly
 *   as `captureEpisodicTurn` does. A failed turn with no reply still records the user's line, as a
 *   spoken turn the model never answered does.
 * - **Conversation audit** ([ConversationAudit] USER and COMPANION rows): the reply is redacted
 *   when a read-through tool ran, the user's own words never are, matching `auditConversationTurn`.
 *   TOOL_RESULT rows come from the controller's shared tool runner, under the same [TypedTurnRecord.turnSeq].
 * - **[ProactiveBus.noteReply]**: a typed brush-off of a nudge suppresses it like a spoken one.
 *
 * Kept apart from `GeminiLiveSession`'s private writers rather than extracted from them, to stay
 * out of that heavily edited file; the two are short and name each other. Folding both onto one
 * writer is owed.
 *
 * Episodic rows are grouped under one session id per typed conversation, minted here and renewed
 * by [newConversation].
 */
object TypedTurnRecorder {
    private const val TAG = "TypedTurnRecorder"
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var sessionId: String = UUID.randomUUID().toString()

    /** "New conversation": later typed turns group under a fresh episodic session. */
    fun newConversation() {
        sessionId = UUID.randomUUID().toString()
    }

    fun record(context: Context, turn: TypedTurnRecord) {
        val app = context.applicationContext
        val sid = sessionId
        io.launch {
            val db = CarDatabase.getDatabase(app)
            val vehicleId = ActiveVehicle.current(app)
            runCatching {
                val uploadedThrough = com.kevin.legion.backend.conversationAuditUploadedThroughId(app)
                val dao = db.conversationAuditDao()
                dao.record(
                    turnSeq = turn.turnSeq,
                    kind = ConversationAudit.Kind.USER,
                    content = turn.userText,
                    vehicleId = vehicleId,
                    uploadedThroughId = uploadedThrough,
                )
                if (turn.replyText.isNotBlank()) {
                    dao.record(
                        turnSeq = turn.turnSeq,
                        kind = ConversationAudit.Kind.COMPANION,
                        content = auditContent(turn.replyText, turn.readThrough),
                        redacted = turn.readThrough,
                        vehicleId = vehicleId,
                        uploadedThroughId = uploadedThrough,
                    )
                }
            }.onFailure { Log.w(TAG, "typed turn audit failed: ${it.message}") }
            if (!turn.readThrough) {
                runCatching {
                    val dao = db.episodicTurnDao()
                    val now = System.currentTimeMillis()
                    dao.insert(
                        EpisodicTurn(
                            sessionId = sid, vehicleId = vehicleId,
                            role = EpisodicTurn.Role.DRIVER, text = turn.userText, timestamp = now,
                        ),
                    )
                    if (turn.replyText.isNotBlank()) {
                        dao.insert(
                            EpisodicTurn(
                                sessionId = sid, vehicleId = vehicleId,
                                role = EpisodicTurn.Role.COMPANION, text = turn.replyText, timestamp = now,
                            ),
                        )
                    }
                }.onFailure { Log.w(TAG, "typed turn episodic capture failed: ${it.message}") }
            }
            runCatching { ProactiveBus.noteReply(app, turn.userText) }
        }
    }
}
