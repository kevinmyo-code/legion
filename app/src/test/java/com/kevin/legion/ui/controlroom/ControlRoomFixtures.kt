package com.kevin.legion.ui.controlroom

import com.kevin.legion.board.ActionRecord
import com.kevin.legion.board.BacklogIdea
import com.kevin.legion.board.BoardOutcome
import com.kevin.legion.board.BoardQuestion
import com.kevin.legion.board.BoardQueue
import com.kevin.legion.board.BoardView
import com.kevin.legion.board.ControlRoomBackend

/** A [ControlRoomBackend] that answers from fields and records every call. Made-up data only. */
class FakeControlRoomBackend : ControlRoomBackend {
    var boardOut: BoardOutcome<BoardView> = BoardOutcome.Ok(BOARD)
    var actionsOut: BoardOutcome<List<ActionRecord>> = BoardOutcome.Ok(listOf(DEPLOY))
    var queueOut: BoardOutcome<BoardQueue> = BoardOutcome.Ok(QUEUE)
    var answerOut: BoardOutcome<String> = BoardOutcome.Ok("Sent to the laptop.")
    val calls = mutableListOf<String>()

    override suspend fun board() = boardOut
    override suspend fun answer(cardId: String, askSha256: String, choice: String, text: String): BoardOutcome<String> {
        calls += "answer:$cardId:$askSha256:$choice:$text"
        return answerOut
    }

    override suspend fun vote(cardId: String, vote: String): BoardOutcome<String> {
        calls += "vote:$cardId:$vote"
        return BoardOutcome.Ok("Vote saved.")
    }

    override suspend fun actions() = actionsOut
    override suspend fun decide(actionId: String, recordSha256: String, allow: Boolean): BoardOutcome<String> {
        calls += "${if (allow) "allow" else "decline"}:$actionId:$recordSha256"
        return BoardOutcome.Ok(if (allow) "Allowed." else "Declined.")
    }

    override suspend fun queue() = queueOut

    companion object {
        val TEAPOT = BoardQuestion(
            id = "teapot", title = "Pick a teapot", col = "needs_kevin", status = "", ask = "Blue or green?",
            options = listOf("blue", "green"), freeText = true, askSha256 = "a".repeat(64), answerable = true,
            answerPending = false, answered = null, ageMin = 4, stale = false,
        )
        val SHIP_IT = TEAPOT.copy(id = "kettle", title = "Kettle firmware", ask = "Ship it?", answerable = false)
        val WORKING = TEAPOT.copy(
            id = "scones", title = "Scone recipe", col = "working", ask = "", options = emptyList(),
            status = "Reading notes", askSha256 = "", answerable = false,
        )
        val BOARD = BoardView(
            sent = true, live = true, ageS = 60, laptopOn = true,
            cards = listOf(TEAPOT, SHIP_IT, WORKING),
            backlog = listOf(
                BacklogIdea("idea-000000000a", "A quieter kettle alarm", 12.0, "YELLOW", "p1"),
                BacklogIdea("idea-000000000b", "Tea timer on the watch", null, "", ""),
            ),
            backlogTotal = 5,
        )
        val DEPLOY = ActionRecord(
            id = "rec-1", kind = "deploy_engine", sha = "1a2b3c4d".repeat(5), tier = "RED",
            reviewOkSha = "1a2b3c4d".repeat(5), ciGreen = true, flags = emptyList(), amountCents = null,
            payee = "", purpose = "", recordSha256 = "b".repeat(64), status = "pending",
            expiresAt = "2031-04-02T10:30:00+00:00",
        )
        val SPEND = DEPLOY.copy(id = "rec-2", kind = "spend", sha = "", reviewOkSha = "", tier = "", ciGreen = false,
            amountCents = 1999, payee = "Tea Supply Co", purpose = "Kettle descaler")
        val QUEUE = BoardQueue(1, 2, 1, "Needs you: 1 session, 2 approvals, 1 decision.",
            listOf("Needs you: 1 session, 2 approvals, 1 decision."),
            listOf(
                "sessions" to "session:s-1@1",
                "approvals" to "action:rec-1",
                "decisions" to "decision:teapot:aaaaaaaaaaaa",
            ),
        )
    }
}

/** A confirm that says yes or no as told, and records each title it was shown. */
class FakeConfirmer(var yes: Boolean) : ActionConfirmer {
    val shown = mutableListOf<String>()
    override fun confirm(title: String, onConfirmed: () -> Unit, onRefused: (String) -> Unit) {
        shown += title
        if (yes) onConfirmed() else onRefused(BiometricActionConfirmer.CANCELLED)
    }
}
