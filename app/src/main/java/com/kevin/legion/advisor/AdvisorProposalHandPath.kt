package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.data.local.AdvisorAdvice
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.util.shortDate

/**
 * The hands path for `accept_proposal` (ADR 0035, command-center ticket 11). Traced from
 * `service/LiveToolbox.kt`'s `acceptProposalTool` before writing this: that dispatch is `private`
 * inside `LiveToolbox`, which this agent's territory brief holds strictly READ-ONLY (another agent
 * is mid-refactor on that file), so this file cannot simply call it. What it CAN call, and does,
 * is the exact same underlying pieces `acceptProposalTool` itself calls -
 * [AdvisorProposalExecutor.execute] (the one real write) and [com.kevin.legion.data.local.AdvisorAdviceDao]'s
 * claim/settle/revert queries - so the WRITE is identical, never a second implementation of it.
 * Only the thin orchestration around that write (TTL check, claim-before-execute, settle-after) is
 * re-stated here, the same "thin Context wrapper" layer `LiveToolbox.acceptProposalTool` itself is
 * one level up from [AdvisorProposalExecutor] - this is that same layer, duplicated because its
 * owner is off limits this session, not because two paths were wanted.
 *
 * **[dismissPendingProposal] has no voice-tool counterpart at all.** `service/LiveToolbox.kt`
 * declares no `reject_proposal`/`dismiss_proposal` tool - a live conversation can only accept or
 * simply not act. A driver looking at a list on screen needs a way to clear one down without
 * accepting it, so this calls [com.kevin.legion.data.local.AdvisorAdviceDao.markOutcome] directly
 * with `"rejected"` - the same terminal-state write [AdvisorAdvice.outcome]'s own class doc already
 * documents as legitimate, just reached from a new caller.
 */
object AdvisorProposalHandPath {

    /**
     * Reads `LiveToolbox.PROPOSAL_TTL_MS` directly - widened to internal for exactly this, so the
     * voice path and this hands path can never expire the same row at two different ages. The copy
     * that used to live here (with a drift warning in place of a guarantee) is gone; see that
     * constant's own doc for why 24h and not a conversation-scoped window.
     */
    private val PROPOSAL_TTL_MS get() = com.kevin.legion.service.LiveToolbox.PROPOSAL_TTL_MS

    /** What accepting or dismissing did, for the caller to show on screen - same shape as every
     * other hands-path dialog in this codebase (`ui/body/BodyWriteDialogs.kt`'s own `result: String?`). */
    data class Outcome(val success: Boolean, val message: String)

    /** Every still-pending proposal for [aspect] - the list a screen renders, newest first. */
    suspend fun pendingProposals(context: Context, aspect: AdvisorAspect): List<AdvisorAdvice> =
        CarDatabase.getDatabase(context).advisorAdviceDao().pendingForAspect(aspect.key)

    /**
     * Accepts one stored proposal by [id], running the SAME checks and the SAME write
     * `acceptProposalTool` runs: outcome must still be `pending`, must not have aged past
     * [PROPOSAL_TTL_MS] (expires it in place otherwise, exactly as the voice path does), must
     * belong to a recognised [AdvisorAspect], and is claimed atomically via
     * [com.kevin.legion.data.local.AdvisorAdviceDao.claimIfPending] before
     * [AdvisorProposalExecutor.execute] ever runs - closing the same double-tap race the voice
     * path's own doc comment describes, for the same reason (a fast double-tap on a phone screen
     * is at least as likely as two overlapping live-model calls).
     */
    suspend fun acceptPendingProposal(context: Context, id: Long): Outcome {
        val db = CarDatabase.getDatabase(context)
        val dao = db.advisorAdviceDao()
        val advice = dao.pending(id) ?: return Outcome(false, "I don't have a proposal like that on file.")

        if (advice.outcome != "pending" || advice.proposalJson == null) {
            return Outcome(false, "That one's already ${advice.outcome} - there's nothing left to accept.")
        }

        val age = System.currentTimeMillis() - advice.createdAt
        if (age > PROPOSAL_TTL_MS) {
            dao.markOutcome(id, "expired", System.currentTimeMillis())
            return Outcome(false, "That was from ${shortDate(advice.createdAt)} - it's aged out, ask the advisor again for a fresh one.")
        }

        val aspect = AdvisorAspect.fromKey(advice.aspect)
            ?: return Outcome(false, "I don't recognise which advisor that proposal came from.")
        val brief = AdvisorBriefs.forAspect(aspect)

        val claimed = dao.claimIfPending(id, "accepting", System.currentTimeMillis())
        if (claimed == 0) {
            return Outcome(false, "That one's already being actioned - nothing left to do.")
        }

        return when (val outcome = AdvisorProposalExecutor.execute(context, brief, advice.proposalJson)) {
            is AdvisorProposalExecutor.ExecuteResult.Ok -> {
                dao.markOutcome(id, "accepted", System.currentTimeMillis())
                Outcome(true, outcome.message)
            }
            is AdvisorProposalExecutor.ExecuteResult.Refused -> {
                dao.revertToPending(id)
                Outcome(false, outcome.message)
            }
            is AdvisorProposalExecutor.ExecuteResult.WriteFailed -> {
                dao.revertToPending(id)
                Outcome(false, outcome.message)
            }
        }
    }

    /** Clears one proposal without accepting it - see class doc for why this has no voice-tool
     * counterpart to call through. Marks `rejected` unconditionally; a proposal already settled
     * (accepted/expired/rejected) is left as-is by the same `WHERE`-less update `markOutcome`
     * already is elsewhere - this function only exists to be called on a row the screen is
     * currently showing as pending. */
    suspend fun dismissPendingProposal(context: Context, id: Long) {
        CarDatabase.getDatabase(context).advisorAdviceDao().markOutcome(id, "rejected", System.currentTimeMillis())
    }
}
