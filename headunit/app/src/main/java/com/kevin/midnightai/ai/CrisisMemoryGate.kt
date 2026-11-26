package com.kevin.midnightai.ai

import com.kevin.midnightai.data.local.EpisodicTurn

/**
 * A crisis conversation leaves nothing behind that Moose can say back to him later (CLAUDE.md
 * sec 9.1, phone parity, ticket #458).
 *
 * [com.kevin.midnightai.service.RewakeCarry] already closes the one-minute replay window. This
 * closes the long one: the raw [EpisodicTurn] buffer is distilled by [MemoryConsolidator] into
 * durable `companion_memories` rows, which [AriaBrain] `recallMemories` pulls back into a prompt on
 * demand for the life of the car. A distress exchange that reached that table would be replayed at
 * the driver weeks later, which is the same harm the carry's wipe exists to stop, with a far longer
 * life.
 *
 * **The phone is the model.** It records no crisis turn at all: `CascadeSession` detects the phrase
 * on the completed text, discards the draft and shuts the session down before anything is persisted
 * (and its episodic capture is retired outright, ADR 0075). The head unit still keeps a raw buffer,
 * so it needs the rule written down instead.
 *
 * **Two layers, and both are needed.** [recordsTurn] is the write side, which keeps the pair out of
 * Room in the first place. [forgetsSession] is the read side, which drops the whole conversation on
 * the way past: it is what clears the turns said BEFORE the crisis phrase (they are the same
 * exchange), and it is the only layer that reaches rows already sitting on a unit from a build
 * without the write gate.
 */
object CrisisMemoryGate {

    /**
     * Whether a completed exchange may enter the raw episodic buffer.
     *
     * Decided from the driver's own completed text, exactly as the phone decides it, rather than
     * from the session's mid-turn crisis flag: that flag is reset at the top of `turnComplete`,
     * before the transcript is read, so a gate reading it there would never fire. A closed session
     * is refused for the same reason `carryTurn` refuses one, a graceful socket close keeps
     * delivering frames after the teardown has begun.
     */
    fun recordsTurn(driverText: String, sessionClosed: Boolean): Boolean =
        !sessionClosed && driverText.isNotBlank() && !CrisisDetector.detect(driverText)

    /**
     * Whether a pending session's raw turns must be dropped rather than distilled.
     *
     * Reads every turn, not only the driver's, because a reply that carries the crisis line is as
     * much a memory of the distress as the line that prompted it. A false positive here costs one
     * drive's memories and nothing else, which is the right direction to err in.
     */
    fun forgetsSession(turns: List<EpisodicTurn>): Boolean =
        turns.any { CrisisDetector.detect(it.text) }

    /**
     * Whether a voice tool call saves a memory, so it is refused for the rest of a socket on which
     * the detector fired (ticket #462).
     *
     * The third layer, and it covers what the other two cannot. [recordsTurn] and [forgetsSession]
     * guard the raw buffer and its distillation; neither sees `remember`, which writes the legacy
     * explicit `memories` table through [AriaBrain] and was still ungated (the #458 review's
     * finding 4). Unlike a captured turn, a `remember` arrives DURING the crisis turn: the
     * detection does not stop the model generating, so the tool call comes off the same socket
     * after the phrase.
     *
     * Keyed on the tool NAME, not on text, because `remember`'s argument is the model's paraphrase
     * of what was said rather than the driver's own words, so re-detecting on it the way
     * [recordsTurn] does would decide nothing.
     *
     * `recall_memory` is deliberately NOT here: reading old memories back is not saving this
     * exchange, and the crisis card is the answer to the exchange either way.
     */
    fun savesAMemory(toolName: String): Boolean = toolName == "remember"

    /**
     * What `remember` says instead, once the detector has fired on this socket. Says what did NOT
     * happen, in words, because the tool's own contract cannot report a refusal
     * ([com.kevin.midnightai.service.LiveToolbox] hardcodes success over it) and because an
     * in-character acknowledgement of a save that never happened is the failure to avoid.
     */
    const val REFUSAL = "Nothing was saved. Nothing from this conversation is kept as a memory. " +
        "Say that nothing was saved; do not offer to save it."
}
