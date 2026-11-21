package com.kevin.legion.service.cascade

import org.json.JSONObject

/**
 * The engine brain's decisions as pure functions (the [CascadeRules] pattern), so a JVM test pins
 * each one. [EngineBrain] and [EngineRelay] only act on what these return.
 */
internal object EngineBrainRules {

    /**
     * What the phone does when the engine IS this conversation's brain (it answered `brain: engine`)
     * and stops being reachable. An attach that fails is not this case: the engine never became the
     * brain, so the phone loop answers that conversation as with the switch off (review A-3).
     */
    enum class WhenUnreachable {
        /** Say so, answer nothing, close the conversation (ADR 0095: no fallback brain). */
        SAY_AND_CLOSE,

        /** Answer the whole conversation on the phone's own loop. */
        PHONE_LOOP,
    }

    /**
     * DECISION K3 (the owner's, default given in plan 1119 section 4b): with the engine as the brain
     * and unreachable, the phone says so and answers nothing. His ruling is a change to this line.
     * It closes only after [FAILURE_BUDGET] failed turns in a row (review B-10): one blip says so and
     * listens again.
     */
    val UNREACHABLE_AT_OPEN = WhenUnreachable.SAY_AND_CLOSE

    /** Failed engine turns in a row before K3 closes the conversation. */
    const val FAILURE_BUDGET = 2

    /** Nothing reached the engine: true as said (review B-9). */
    const val UNREACHABLE_LINE = "I couldn't reach the engine, so nothing was answered."

    /** The turn went out and no answer came: the engine may still be running it (review B-9). */
    const val NO_ANSWER_LINE = "I couldn't get an answer from the engine. It may still finish that."

    /** The engine speaks a newer contract (409 `contract_unsupported`). */
    const val UPDATE_LINE = "The engine needs a newer version of this app, so nothing was answered."

    /** No first event this long after the end frame: unreachable (client contract section 9). */
    const val FIRST_EVENT_MS = 20_000L

    /** A cancel the conversation sent is given this long to reach the engine after a close (review B-8). */
    const val TAIL_MS = 25_000L

    /** How long the limb attach may take before the conversation is called unreachable. */
    const val ATTACH_MS = 6_000L

    /** A continuation not started by the round's last deadline plus this: recover once (section 8.9). */
    const val CONTINUATION_GRACE_MS = 10_000L

    /** A 503 `no_key` on a result post: re-posted once after this (relay contract 5). */
    const val NO_KEY_RETRY_MS = 5_000L

    /** A 429 `throttled` result post is retried after `Retry-After`, at most this many times. */
    const val MAX_THROTTLED_RETRIES = 3

    /** No `Retry-After` on a 429: wait this long. */
    const val DEFAULT_RETRY_AFTER_MS = 2_000L

    /** The engine's `MAX_RESULT_BYTES` for one result (16 KiB). */
    const val MAX_RESULT_BYTES = 16 * 1024

    /** A cut result keeps this much of its message (a quarter of the cap: room for any encoding). */
    const val TRIMMED_MESSAGE_CHARS = MAX_RESULT_BYTES / 4

    /** `words` is kept short: the engine stores it and hands it to the model. */
    const val MAX_WORDS_CHARS = 500

    /** The voice contract this client speaks (wire v1). */
    const val CONTRACT_VERSION = 1

    /**
     * Why the PHONE loop answers this turn though his setting is on (plan 1119 section 4b), or null
     * for the engine. Every reason is a fact the wire cannot carry yet, or a ruling:
     * - [Facts.test]: a test conversation. The engine has no `test_mode` yet (slice E3), so its own
     *   writes would bypass the phone's `TestTurnPolicy`.
     * - not his words: openers, the greeting and check-ins stay phone prompts (4b rule 3).
     * - [Facts.carryMarked]: the turn after a read-through line; the engine has no `carry_tainted`.
     * - [Facts.afterPhoneLine]: the turn right after a line the phone loop spoke; the engine has no
     *   `prior_line`, so "yes" would reach it with no context.
     * - mail words: mail stays on the phone until ticket 05.
     */
    fun phoneKeeps(words: String, facts: Facts): String? = when {
        facts.test -> "test"
        !facts.fromUser -> "prompt"
        facts.carryMarked -> "carry"
        facts.afterPhoneLine -> "after_phone_line"
        MAIL_WORDS.containsMatchIn(words) -> "mail"
        else -> null
    }

    data class Facts(
        val test: Boolean,
        val fromUser: Boolean,
        val carryMarked: Boolean,
        val afterPhoneLine: Boolean,
    )

    /**
     * His words about mail and what the phone reads from mail (the brief's mail sections, parcels
     * and flights). Whole words; "mailbox" counts, "e-mail" counts.
     */
    val MAIL_WORDS = Regex(
        "\\b(e-?mails?|g-?mail|mail(s|box)?|inbox|unread|brief(ing)?|sitrep|packages?|parcels?|" +
            "deliver(y|ies)|shipping|flights?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** What the phone can offer a call's `needs` right now. */
    data class DeviceState(val locked: Boolean, val screenOn: Boolean)

    /**
     * The refusal words for a call whose `needs` the phone cannot meet now, else null. Answered at
     * once, never held to the deadline (relay contract 3). `parked` is left to the tool's own guard
     * (the clear-codes speed check), which refuses in words itself.
     */
    fun needsRefusal(needs: List<String>, state: DeviceState): String? = when {
        "screen" in needs && !state.screenOn -> "Not done: the phone's screen is off."
        ("unlock" in needs || "screen" in needs) && state.locked -> "Not done: the phone is locked."
        else -> null
    }

    /**
     * A tool's response as the outcome posted for it. `success: true` is `ok`; the phone's own
     * guards (the read-through write guard, a test turn's refusal) are `refused`; anything else is
     * `failed`, which a phone tool only says when nothing happened.
     */
    fun outcomeOf(response: JSONObject, ms: Long): CallOutcome {
        val ok = response.optBoolean("success", !response.has("error"))
        val guarded = response.optBoolean("read_through") || response.optBoolean("test_mode")
        val outcome = when {
            ok -> CallOutcome.OK
            guarded -> CallOutcome.REFUSED
            else -> CallOutcome.FAILED
        }
        val said = response.optString("message").ifBlank { response.optString("error") }
        val words = said.ifBlank { if (ok) "Done." else "It did not run." }.take(MAX_WORDS_CHARS)
        return CallOutcome(outcome, boundedResult(response), words, ms)
    }

    /** [response] as the posted `result`, or its message alone when it is over the engine's cap. */
    fun boundedResult(response: JSONObject): JSONObject {
        if (response.toString().toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) return response
        val message = response.optString("message").take(TRIMMED_MESSAGE_CHARS)
        return JSONObject().put("success", response.optBoolean("success")).put("trimmed", true).put("message", message)
    }

    /** What a result post's answer means next (relay contract section 5). */
    enum class AfterPost {
        /** The rest of the turn streams back. */
        STREAM,

        /** 202: other calls of the round are still open. */
        WAITING,

        /** 200 recorded, no stream follows (`cancelled`, `late`, `running`): do not retry. */
        RECORDED,

        /** 503 `no_key`, recorded: say so, re-post once after [NO_KEY_RETRY_MS]. */
        NO_KEY,

        /** 429 `member_cap`, recorded, the turn closed: say the engine's words. */
        MEMBER_CAP,

        /** 429 throttled, NOT recorded: retry after Retry-After, at most [MAX_THROTTLED_RETRIES]. */
        THROTTLED,

        /** 401: the token was revoked mid-turn. End the turn in words. */
        REVOKED,

        /** 404, 409, 411, 413, 400: not recorded. Never the same post again. */
        NOT_RECORDED,

        /** Nothing came back. */
        UNREACHABLE,
    }

    fun afterPost(reply: WireReply): AfterPost = when (reply) {
        is WireReply.Events -> AfterPost.STREAM
        is WireReply.Unreachable -> AfterPost.UNREACHABLE
        is WireReply.Json -> afterJson(reply)
    }

    private fun afterJson(reply: WireReply.Json): AfterPost {
        val recorded = reply.body.optBoolean("recorded")
        return when {
            reply.status == HTTP_ACCEPTED -> AfterPost.WAITING
            reply.status == HTTP_UNAVAILABLE && recorded -> AfterPost.NO_KEY
            reply.status == HTTP_TOO_MANY && (recorded || reply.code == "member_cap") -> AfterPost.MEMBER_CAP
            reply.status == HTTP_TOO_MANY -> AfterPost.THROTTLED
            reply.status == HTTP_UNAUTHORIZED || reply.status == HTTP_FORBIDDEN -> AfterPost.REVOKED
            reply.status in HTTP_OK_RANGE -> AfterPost.RECORDED
            reply.status >= HTTP_SERVER_ERROR -> AfterPost.UNREACHABLE
            else -> AfterPost.NOT_RECORDED
        }
    }

    /**
     * How long to wait before posting the same result again, or null for no retry: a 429
     * `throttled` after its `Retry-After` while [tries] is under [MAX_THROTTLED_RETRIES]; a 503
     * `no_key` once.
     */
    fun retryWaitMs(reply: WireReply, tries: Int): Long? = when (afterPost(reply)) {
        AfterPost.THROTTLED -> if (tries < MAX_THROTTLED_RETRIES) {
            (reply as? WireReply.Json)?.retryAfterS?.let { it * MS_PER_S } ?: DEFAULT_RETRY_AFTER_MS
        } else {
            null
        }
        AfterPost.NO_KEY -> NO_KEY_RETRY_MS.takeIf { tries == 0 }
        else -> null
    }

    /** A non-stream answer where the turn's stream was due, as the words he hears (null: say nothing more). */
    fun wordsFor(reply: WireReply): String? = if ((reply as? WireReply.Json)?.code == "contract_unsupported") {
        UPDATE_LINE
    } else {
        when (afterPost(reply)) {
            AfterPost.RECORDED -> null
            AfterPost.MEMBER_CAP -> (reply as? WireReply.Json)?.detail?.ifBlank { null } ?: STOPPED_LINE
            AfterPost.REVOKED -> REVOKED_LINE
            AfterPost.UNREACHABLE, AfterPost.NO_KEY -> UNREACHABLE_LINE
            else -> STOPPED_LINE
        }
    }

    private const val MS_PER_S = 1000L

    /** Words for the end of a turn the engine could not finish (each is said, never invented). */
    const val REVOKED_LINE = "The engine signed this phone out, so I couldn't finish that."
    const val STOPPED_LINE = "The engine stopped answering. A change may or may not have gone through."

    /**
     * Whether a stream-route refusal sends the held clip to the batch route in the same turn: the
     * stream switch is off (404), not all the audio arrived (`audio_gap`), the body broke before `E`
     * (`hung_up`, `stalled`), or the member's one stream is still held (`stream_busy`). A lost answer
     * after `E` is not one of these: [EngineUtterance] never posts that clip again.
     */
    fun streamFallsBack(reply: WireReply): Boolean = when (reply) {
        is WireReply.Unreachable -> true
        is WireReply.Events -> false
        is WireReply.Json -> reply.status == HTTP_NOT_FOUND || reply.code in STREAM_FALLBACK_CODES
    }

    /** Stream refusals the batch route answers instead: a gap, a broken upload, the one stream busy. */
    private val STREAM_FALLBACK_CODES = setOf("audio_gap", "hung_up", "stalled", "stream_busy")

    /** The engine's turn routes are switched off (404): this conversation goes back to the phone loop. */
    fun switchedOff(reply: WireReply): Boolean = reply is WireReply.Json && reply.status == HTTP_NOT_FOUND

    const val HTTP_ACCEPTED = 202
    const val HTTP_UNAUTHORIZED = 401
    const val HTTP_FORBIDDEN = 403
    const val HTTP_NOT_FOUND = 404
    const val HTTP_CONFLICT = 409
    const val HTTP_TOO_MANY = 429
    const val HTTP_SERVER_ERROR = 500
    const val HTTP_UNAVAILABLE = 503
    const val HTTP_OK = 200
    const val HTTP_OK_LAST = 299
    private val HTTP_OK_RANGE = HTTP_OK..HTTP_OK_LAST
}
