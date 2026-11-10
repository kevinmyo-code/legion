package com.kevin.legion.openloops

import com.kevin.legion.memory.MemoryOutcome
import java.time.LocalDate

/**
 * Open loops on the phone (ADR 0085, `.scratch/open-loops/`): things Kevin said he would do and
 * never set up, flagged by the nightly memory review and stored on the engine as PROPOSALS. Alfred
 * asks about each one ("Track it?"); only his yes makes it a task, his no drops it for good.
 *
 * The phone never decides that something is an open loop and never words one: the engine's rows
 * are the facts, and the brief line ([OpenLoopsBrief]) only hands them to the model to ask about.
 */
data class Proposal(val id: String, val title: String, val dueDate: LocalDate?)

/** `GET /api/open-loops/`. [on] false: off on the engine; [proposing] false: "stop proposing tasks". */
data class Listing(val on: Boolean, val proposing: Boolean, val proposals: List<Proposal>)

/** A yes: what was made ("reminder", "todo", or "existing") and the engine's words. */
data class Tracked(val taskKind: String, val words: String)

/**
 * The seam [OpenLoopsController] talks through; the one implementation is
 * [com.kevin.legion.backend.engine.DjangoOpenLoopsBackend]. Outcomes reuse [MemoryOutcome]: the
 * same four shapes (ok, unreachable, refused in the engine's words, unconfirmed) and the same
 * decoding of a refusal.
 */
interface OpenLoopsBackend {
    suspend fun list(): MemoryOutcome<Listing>

    /** The spoken brief's batch; the engine counts each as carried (at most twice). */
    suspend fun offer(): MemoryOutcome<Listing>

    /** [dueDate] only when he named one in his answer; the engine never invents one. */
    suspend fun track(id: String, dueDate: LocalDate?): MemoryOutcome<Tracked>

    suspend fun dismiss(id: String): MemoryOutcome<String>

    suspend fun setProposing(on: Boolean): MemoryOutcome<String>
}

/** What a tap or a voice answer did, in words. [ok] false: nothing was changed. */
data class LoopAnswer(val ok: Boolean, val words: String)
