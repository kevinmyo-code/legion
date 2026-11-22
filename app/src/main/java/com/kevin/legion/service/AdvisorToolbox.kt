package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.advisor.AdvisorAgent
import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.advisor.AdvisorBriefs
import com.kevin.legion.advisor.AdvisorProposalExecutor
import com.kevin.legion.advisor.AdvisorResult
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.util.shortDate
import org.json.JSONObject

/**
 * The advisor tools (`ask_advisor`, `accept_proposal`), split out of [LiveToolbox] (shrink pass,
 * 2026-11-22) along the [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards,
 * then asks this object, which answers the names it owns and returns null for every other. Goals
 * and goal plans are [GoalsToolbox]; the declarations stay in `service/tools/`.
 */
object AdvisorToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "ask_advisor" -> askAdvisorTool(context, args)
        "accept_proposal" -> acceptProposalTool(context, args)
        else -> null
    }

    // --- Advisors (ticket 18) -------------------------------------------------------------------

    /** How long a stored proposal stays valid before `accept_proposal` refuses it and marks the
     * row `expired` - ticket 03 answer call 5's "24h is the starting number, the build may tune
     * it". `reasoned`, not measured: this build did not have grounds to pick a different number,
     * so it ships the ticket's own starting figure rather than inventing one. Deliberately NOT
     * conversation-scoped (the answer's literal "the conversation they were made in plus a short
     * TTL") - LiveToolbox is a stateless dispatch object with no notion of "this call's
     * conversation", so a flat wall-clock TTL off [com.kevin.legion.data.local.AdvisorAdvice
     * .createdAt] is the buildable approximation: never LESS permissive than the conversation-
     * scoped version (a same-conversation accept is always well under 24h), only more permissive
     * for a proposal accepted in a later conversation within the same day - a gap flagged here
     * rather than silently narrowed to something the ticket didn't ask for.
     */
    // Internal, not private: AdvisorProposalHandPath (the ADR 0035 hands path for accepting a
    // proposal) enforces the SAME expiry, and a copied constant is how the two paths would one day
    // expire the same row at two different ages. Same widen-to-internal pattern as
    // resolveCurrentLocation and controlMusicTransport.
    internal const val PROPOSAL_TTL_MS = 24L * 60 * 60 * 1000

    /** `ask_advisor`. Resolves `aspect` to its [AdvisorBriefs] entry and runs one [AdvisorAgent]
     * exchange. The tool response always carries `adviceId` on a [AdvisorResult.Success] (even a
     * purely conversational answer with no proposal gets one - see [AdvisorAgent.outcomeFor]) so a
     * later `accept_proposal` call always has a real id to name, never a value the live model has
     * to invent or recall from the transcript. */
    private suspend fun askAdvisorTool(context: Context, args: JSONObject): JSONObject {
        val aspect = AdvisorAspect.fromKey(args.optString("aspect").trim().lowercase())
            ?: return result(false, "Which advisor - body, notes, fleet, money, or overall?")
        val question = args.optString("question").trim()
        if (question.isBlank()) return result(false, "What's the question?")

        if (!GeminiKeyProvider.hasKey()) {
            return result(false, "No Gemini key is set, so this cannot run. One can be added in Setup.")
        }

        val brief = AdvisorBriefs.forAspect(aspect)
        return mapAdvisorResult(AdvisorAgent().ask(context, brief, question))
    }

    /**
     * Maps one [AdvisorResult] to the tool-response JSON `ask_advisor` hands back to the live
     * model. Split out from [askAdvisorTool] itself (no `Context`, no network) so a unit test can
     * hit every branch - most importantly [AdvisorResult.ParseFailed] - directly, `internal` for
     * that reason.
     *
     * Ticket 18's "known weakness", narrowed by ticket 21 but not eliminated: `AdvisorAgent` now
     * hands `SubAgent.askTyped` a real `responseSchema` (machine-enforced), on top of
     * [com.kevin.legion.advisor.AdvisorAnswer.RESPONSE_SCHEMA]'s prose copy in the system
     * instruction (belt and braces - see that constant's doc comment for why both stay). A schema
     * makes malformed output less likely, not impossible, so the model's reply can still fail to
     * parse into [com.kevin.legion.advisor.AdvisorAnswer]'s shape. **The prose is still good
     * coaching - it is the JSON envelope that failed** - so [AdvisorResult.ParseFailed] carries the
     * raw text and this branch RELAYS it, with a plain caveat that there is no concrete proposal
     * behind it. Discarding the words because their wrapper was malformed would turn a formatting
     * problem into a silent loss of the advice itself. What is genuinely lost is only the
     * structured half: no proposal to accept, and no per-figure `basis` tags - which is why the
     * caveat says so rather than implying a normal answer.
     */
    internal fun mapAdvisorResult(outcome: AdvisorResult): JSONObject = when (outcome) {
        is AdvisorResult.Success -> {
            KeyHealth.noteOk()
            result(true, outcome.answer.spoken).put("adviceId", outcome.adviceId).apply {
                if (outcome.answer.proposal != null) put("hasProposal", true)
            }
        }
        AdvisorResult.RateLimited -> {
            KeyHealth.noteRateLimited()
            result(false, "The Gemini key hit its rate limit. Try again in a minute.")
        }
        AdvisorResult.KeyInvalid -> {
            KeyHealth.noteInvalid()
            result(false, "The Gemini key was rejected. It can be checked in Setup.")
        }
        AdvisorResult.Overloaded -> result(false, "The advisor is overloaded. Try again shortly.")
        AdvisorResult.Offline -> result(false, "No network connection. Try again once online.")
        AdvisorResult.Failed -> result(false, "Could not reach the advisor. Try again shortly.")
        is AdvisorResult.ParseFailed -> {
            KeyHealth.noteOk()
            val prose = outcome.rawText.trim()
            if (prose.isEmpty()) {
                result(
                    true,
                    "The advisor returned thoughts but no clean, concrete answer. Ask " +
                        "again for another pass.",
                )
            } else {
                result(
                    true,
                    prose + "\n\n(Say this as advice only - I couldn't put a concrete proposal " +
                        "together from it, so there's nothing to accept yet.)",
                )
            }
        }
    }

    /** `accept_proposal`. Reads the STORED `advisor_advice` row by id and executes its
     * `proposalJson` VERBATIM via [AdvisorProposalExecutor] - the live model supplies only the id,
     * never a value, so nothing can drift between what was read aloud and what lands (ticket 03
     * answer call 1). Refuses, in words, for: an unknown id, a row that already left `pending`
     * (already accepted/rejected/expired - nothing to do twice), a row older than
     * [PROPOSAL_TTL_MS] (marked `expired` here, not left silently stale), an op outside that
     * aspect's own [com.kevin.legion.advisor.AdvisorBrief.writableOps] (never marked - a proposal
     * that was never writable can't become writable by asking again with the same id, so there is
     * nothing to invalidate), and a row someone else already claimed (see the atomic claim below).
     *
     * **Claim before executing, settle after.** The read-then-check above is NOT the mutual-exclusion
     * point - two concurrent calls for the same id (a double-tap, or a model retry racing the
     * original past `TOOL_TIMEOUT_MS` while `handleToolCall`'s orphaned coroutine keeps running) can
     * both pass every check above and both reach here. [AdvisorAdviceDao.claimIfPending] is an
     * `UPDATE ... WHERE outcome = 'pending'`, the actual atomic gate: only one caller's claim can
     * flip the row, so only one caller ever executes. The loser's `claimed == 0` is reported honestly
     * as already-actioned, never silently retried. A claimed row settles to `accepted` on a verified
     * [AdvisorProposalExecutor.ExecuteResult.Ok], or back to `pending` via
     * [AdvisorAdviceDao.revertToPending] on [AdvisorProposalExecutor.ExecuteResult.Refused] /
     * [AdvisorProposalExecutor.ExecuteResult.WriteFailed] so the SAME id remains retryable - a
     * verified-not-written proposal must never read as `accepted` (that row could then never be
     * retried, since it no longer satisfies `outcome == "pending"`, and the advice log would show a
     * failed write as a success forever). */
    private suspend fun acceptProposalTool(context: Context, args: JSONObject): JSONObject {
        if (!args.has("id")) return result(false, "Which proposal? I need its id.")
        val id = args.optLong("id", -1L)
        if (id < 0) return result(false, "Which proposal? I need its id.")

        val db = CarDatabase.getDatabase(context)
        val advice = db.advisorAdviceDao().pending(id)
            ?: return result(false, "I don't have a proposal like that on file.")

        if (advice.outcome != "pending" || advice.proposalJson == null) {
            return result(false, "That one's already ${advice.outcome} - there's nothing left to accept.")
        }

        val age = System.currentTimeMillis() - advice.createdAt
        if (age > PROPOSAL_TTL_MS) {
            db.advisorAdviceDao().markOutcome(id, "expired", System.currentTimeMillis())
            return result(
                false,
                "That was from ${shortDate(advice.createdAt)} - it's aged out, let me re-check and propose it fresh.",
            )
        }

        val aspect = AdvisorAspect.fromKey(advice.aspect)
            ?: return result(false, "I don't recognise which advisor that proposal came from.")
        val brief = AdvisorBriefs.forAspect(aspect)

        val claimed = db.advisorAdviceDao().claimIfPending(id, "accepting", System.currentTimeMillis())
        if (claimed == 0) {
            return result(false, "That one's already being actioned - nothing left for me to do.")
        }

        return when (val outcome = AdvisorProposalExecutor.execute(context, brief, advice.proposalJson)) {
            is AdvisorProposalExecutor.ExecuteResult.Ok -> {
                db.advisorAdviceDao().markOutcome(id, "accepted", System.currentTimeMillis())
                result(true, outcome.message)
            }
            is AdvisorProposalExecutor.ExecuteResult.Refused -> {
                db.advisorAdviceDao().revertToPending(id)
                result(false, outcome.message)
            }
            is AdvisorProposalExecutor.ExecuteResult.WriteFailed -> {
                db.advisorAdviceDao().revertToPending(id)
                result(false, outcome.message)
            }
        }
    }
}
