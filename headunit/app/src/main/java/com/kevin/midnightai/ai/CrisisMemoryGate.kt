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
}
