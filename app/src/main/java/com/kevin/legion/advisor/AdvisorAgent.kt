package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.StructuredOutputRequest
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.ai.personaFor
import com.kevin.legion.data.local.AdvisorAdvice
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal

/**
 * Typed outcome of one [AdvisorAgent.ask] exchange - the same "phrase rate-limit vs. bad-key vs.
 * offline distinctly" shape [AgentResult] already gives [SubAgent]'s other callers, plus the two
 * failure modes specific to this harness: a response that came back 200 but didn't parse into
 * [AdvisorAnswer]'s shape ([ParseFailed]), and (not expected in practice, since [AdvisorBrief]
 * always carries a real [DigestBuilder]) nothing this ticket needs to construct directly.
 */
sealed class AdvisorResult {
    /** [answer] is what to speak/show; [adviceId] is the row [AdvisorAgent] just inserted into
     * `advisor_advice`, for a caller that wants to act on [AdvisorAnswer.proposal] afterwards
     * (e.g. mark it accepted once the driver says yes). */
    data class Success(val answer: AdvisorAnswer, val adviceId: Long) : AdvisorResult()
    object RateLimited : AdvisorResult()
    object KeyInvalid : AdvisorResult()
    object Overloaded : AdvisorResult()
    object Offline : AdvisorResult()
    object Failed : AdvisorResult()
    /** The model answered (200 OK, text came back) but the text did not parse into
     * [AdvisorAnswer]'s required shape - see [AdvisorAnswer.parse]. Kept distinct from [Failed] so
     * a caller/log can tell "Gemini declined or errored" apart from "Gemini answered something we
     * could not use", which is the more actionable signal for tightening [HarnessPrompt].
     *
     * **Carries [rawText] deliberately.** The model DID answer, and its prose is usually perfectly
     * good coaching - it is the JSON envelope around it that failed. Throwing that away would turn
     * a formatting problem into a silent loss of the actual advice, which is what ticket 18 means
     * by degrading honestly: say the words, admit there is no concrete proposal behind them. Only
     * the structured half (the proposal, the per-figure `basis` tags) is genuinely unavailable. */
    data class ParseFailed(val rawText: String) : AdvisorResult()
}

/**
 * The one harness wrapping [SubAgent] that all five [AdvisorBrief]s ride (ticket 01 answer call
 * 1). One-shot [SubAgent.askTyped], never [SubAgent.investigate] - Flash cannot combine structured
 * output with tool declarations (traced, [SubAgent]'s own doc comment), and the digest is already
 * precomputed deterministically, so there is nothing left for a tool-calling loop to pull.
 *
 * One POST per question carries: the active persona's tone + [HarnessPrompt]'s rules +
 * [AdvisorAnswer.RESPONSE_SCHEMA] as the `systemInstruction`, and [AdvisorBrief.playbook] (if any)
 * + [AdvisorBrief.synthesisNote] (if any) + the digest + the aspect's current goals + the last
 * advice-log window as the `context`, with the driver's own question as the question proper. See
 * [composeSystemInstruction]/[composeContext] for the exact assembly, kept as separate pure
 * functions from [ask] itself so a unit test can measure/inspect a composed prompt without a
 * Context, Room, or a network call.
 */
class AdvisorAgent(
    /**
     * Builds the [SubAgent] for one exchange, given that exchange's fully-composed
     * `systemInstruction` (persona + [HarnessPrompt] rules + response schema - see
     * [composeSystemInstruction]). A factory, not a fixed instance, because
     * [SubAgent.systemInstruction] is set at construction and persona can change between calls on
     * a long-lived [AdvisorAgent]. The default always constructs a real network-backed [SubAgent]
     * with search OFF - an advisor answers from the digest and playbook it was handed, never from
     * a live web lookup, which would be exactly the kind of unfalsifiable-to-the-record input §7
     * warns against. Injectable so a unit test can substitute a fake without a network call.
     */
    private val subAgentFactory: (systemInstruction: String) -> SubAgent =
        { systemInstruction -> SubAgent(systemInstruction = systemInstruction, useSearch = false) },
) {
    /**
     * Runs one advisor exchange for [brief] and records it. On [AdvisorResult.Success], an
     * `advisor_advice` row has already been inserted with [AdvisorAnswer.spoken] as the gist basis
     * (see [buildGist]) and `outcome` set to `pending` only when [AdvisorAnswer.proposal] is
     * non-null - a purely conversational answer has nothing pending on it, so it is recorded
     * `accepted` immediately (see [outcomeFor]'s doc comment for why that's the right terminal
     * state, not `expired` or a new "n/a" value).
     */
    suspend fun ask(context: Context, brief: AdvisorBrief, question: String): AdvisorResult {
        val db = CarDatabase.getDatabase(context)
        val digest = brief.digestBuilder.build(context)
        val goals = if (brief.aspect == AdvisorAspect.HOME) {
            db.goalDao().allCurrentGoals()
        } else {
            db.goalDao().currentGoals(brief.aspect.key)
        }
        val adviceLog = db.advisorAdviceDao().recent(brief.aspect.key, ADVICE_LOG_WINDOW)
        val personaShortClause = personaFor(CompanionProfile.persona(context)).shortClause

        val systemInstruction = composeSystemInstruction(personaShortClause)
        val promptContext = composeContext(brief, digest, goals, adviceLog)

        val result = subAgentFactory(systemInstruction).askTyped(
            context = promptContext,
            question = question,
            // Ticket 21: the prose contract in the system instruction (AdvisorAnswer.RESPONSE_SCHEMA,
            // via composeSystemInstruction) stays as belt; this is the braces - Gemini's own
            // structured-output enforcement, machine-checked before the text ever reaches parse().
            structuredOutput = StructuredOutputRequest(AdvisorAnswer.responseSchema()),
        )

        return when (result) {
            is AgentResult.Success -> {
                val answer = AdvisorAnswer.parse(result.text)
                    ?: return AdvisorResult.ParseFailed(result.text)
                val proposalPresent = answer.proposal != null
                val id = db.advisorAdviceDao().insert(
                    AdvisorAdvice(
                        aspect = brief.aspect.key,
                        questionText = question,
                        gist = buildGist(answer),
                        adviceText = answer.spoken,
                        proposalJson = answer.proposal,
                        outcome = outcomeFor(proposalPresent),
                    ),
                )
                AdvisorResult.Success(answer, id)
            }
            AgentResult.RateLimited -> AdvisorResult.RateLimited
            AgentResult.KeyInvalid -> AdvisorResult.KeyInvalid
            AgentResult.Overloaded -> AdvisorResult.Overloaded
            AgentResult.Offline -> AdvisorResult.Offline
            AgentResult.Failed -> AdvisorResult.Failed
        }
    }

    companion object {
        /** Ticket 14/11: "limit 3 - measured affordable at 194 tokens". */
        const val ADVICE_LOG_WINDOW = 3

        /**
         * `pending` only when there is a [AdvisorAnswer.proposal] to act on - matching
         * [AdvisorAdvice]'s own doc comment ("`pending` until the driver acts on it"). A purely
         * conversational exchange has nothing pending; recording it `accepted` (rather than
         * inventing a fifth "n/a" outcome, which the ticket explicitly declined) keeps the
         * lifecycle vocabulary at the four states [AdvisorAdvice] already documents
         * (`pending`/`accepted`/`rejected`/`expired`) and reads correctly on any screen that lists
         * "things I said yes to" - a chat-only answer was never something to accept OR reject, so
         * closest true is: nothing was proposed, nothing is outstanding.
         */
        internal fun outcomeFor(proposalPresent: Boolean): String = if (proposalPresent) "pending" else "accepted"

        /** Short gist for the next digest's advice-log window - just the spoken line, trimmed, so
         * [com.kevin.legion.data.local.AdvisorAdviceDao.recent] stays cheap on the wire per the
         * class doc's "cheap thing rides the prompt" split. */
        internal fun buildGist(answer: AdvisorAnswer): String = answer.spoken.take(GIST_MAX_CHARS)
        private const val GIST_MAX_CHARS = 240

        /**
         * The `systemInstruction` for one exchange: persona tone, then [HarnessPrompt]'s rules,
         * then the fixed response schema. Persona comes FIRST and rules SECOND on purpose - if the
         * rules preceded the persona clause, a long persona clause could read as amending them;
         * this order keeps "who is speaking" and "what they may say" visibly separate blocks, and
         * a persona clause can never itself contain a rule that overrides [HarnessPrompt] because
         * the harness's text always has the last word before the schema.
         */
        internal fun composeSystemInstruction(personaShortClause: String): String = buildString {
            append(personaShortClause)
            append("\n\n")
            append(HarnessPrompt.RULES)
            append("\n\n")
            append(AdvisorAnswer.RESPONSE_SCHEMA.trim())
        }

        /**
         * The `context` block for one exchange: playbook (if any) + synthesis note (if any) +
         * digest + goals + advice-log window, in that order - domain rules before domain data,
         * data before history, matching the harness's own "the app computes, you interpret"
         * framing (interpretive material first, factual material after). Returns a block with NO
         * playbook/synthesisNote section at all when [AdvisorBrief.playbook]/
         * [AdvisorBrief.synthesisNote] are null (the HOME case, ticket 09) - never an empty
         * "PLAYBOOK:" header with nothing under it, which would cost tokens and tell the model
         * nothing.
         */
        internal fun composeContext(
            brief: AdvisorBrief,
            digest: String,
            goals: List<Goal>,
            adviceLog: List<AdvisorAdvice>,
        ): String = buildString {
            brief.playbook?.let {
                append("PLAYBOOK:\n").append(it.trim()).append("\n\n")
            }
            brief.synthesisNote?.let {
                append("HOW TO REASON HERE:\n").append(it.trim()).append("\n\n")
            }
            append("DIGEST:\n").append(digest.ifBlank { DigestText.notLogged() }).append("\n\n")
            append("GOALS:\n").append(formatGoals(goals)).append("\n\n")
            append("RECENT ADVICE (last ${adviceLog.size}):\n").append(formatAdviceLog(adviceLog))
        }

        /** One line per goal: statement, plus the number if it's number-tracked - see [Goal]'s doc
         * comment ("prose required, numbers optional"). [DigestText.notLogged] for an empty list,
         * matching every other absent-value rendering in this harness. */
        internal fun formatGoals(goals: List<Goal>): String {
            if (goals.isEmpty()) return DigestText.notLogged()
            return goals.joinToString("\n") { g ->
                val numberPart = if (g.targetValue != null) " (target ${g.targetValue}${g.unit?.let { " $it" } ?: ""})" else ""
                "- [${g.aspect}] ${g.statement}$numberPart"
            }
        }

        /** One line per past exchange: question + gist + outcome (never the full [AdvisorAdvice
         * .adviceText] - see that entity's doc comment on why only the gist rides the prompt).
         * [DigestText.notLogged] for an empty window - a first-ever question for an aspect has no
         * history, which is a fact about the record, not a zero to hide. */
        internal fun formatAdviceLog(rows: List<AdvisorAdvice>): String {
            if (rows.isEmpty()) return DigestText.notLogged()
            return rows.joinToString("\n") { r -> "- Q: ${r.questionText} | A: ${r.gist} | ${r.outcome}" }
        }

        /**
         * A cheap, offline token estimate: chars/4. Ticket 11's real-tokenizer pass measured this
         * heuristic accurate to within ~4% against gemini-3.5-flash-lite's `countTokens` on this
         * exact codebase's prose (LiveToolbox: 56,427 chars -> 13,597 real tokens, ratio 4.15 -
         * chars/4 landed within the ticket's own stated band). `reasoned`, not `measured`, when
         * applied to text this ticket didn't itself run through a real tokenizer - good enough to
         * catch a harness prompt that has obviously blown its ceiling, not a substitute for
         * running the real tokenizer before shipping a final playbook.
         */
        internal fun estimateTokens(text: String): Int = kotlin.math.ceil(text.length / 4.0).toInt()
    }
}
