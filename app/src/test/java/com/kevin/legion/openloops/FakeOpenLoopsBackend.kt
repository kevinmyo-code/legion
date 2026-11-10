package com.kevin.legion.openloops

import com.kevin.legion.memory.MemoryOutcome
import java.time.LocalDate

/** An in-memory engine for proposed to-dos. Made-up tasks only. Records every call. */
@Suppress("ReturnCount") // a fake: offline, unknown id, then the answer
class FakeOpenLoopsBackend(
    val proposals: MutableList<Proposal> = mutableListOf(),
    var on: Boolean = true,
    var proposing: Boolean = true,
    var down: Boolean = false,
) : OpenLoopsBackend {
    val calls = mutableListOf<String>()
    val tracked = mutableListOf<Pair<String, LocalDate?>>()
    val dismissed = mutableListOf<String>()

    private fun listing() = Listing(on, proposing, if (on && proposing) proposals.toList() else emptyList())

    override suspend fun list(): MemoryOutcome<Listing> {
        calls += "list"
        return if (down) MemoryOutcome.Unreachable("offline") else MemoryOutcome.Ok(listing())
    }

    override suspend fun offer(): MemoryOutcome<Listing> {
        calls += "offer"
        return if (down) MemoryOutcome.Unreachable("offline") else MemoryOutcome.Ok(listing())
    }

    override suspend fun track(id: String, dueDate: LocalDate?): MemoryOutcome<Tracked> {
        calls += "track $id"
        if (down) return MemoryOutcome.Unreachable("offline")
        val p = proposals.firstOrNull { it.id == id }
            ?: return MemoryOutcome.Refused(
                "Nothing was changed. There is no proposed to-do with that id that you can see.",
                404,
            )
        proposals.remove(p)
        tracked += id to dueDate
        val day = dueDate ?: p.dueDate
        return MemoryOutcome.Ok(
            Tracked(
                if (day != null) "reminder" else "todo",
                "Tracked: ${p.title}" + if (day != null) " for $day." else " on your Todo list, no date.",
            ),
        )
    }

    override suspend fun dismiss(id: String): MemoryOutcome<String> {
        calls += "dismiss $id"
        if (down) return MemoryOutcome.Unreachable("offline")
        val p = proposals.firstOrNull { it.id == id } ?: return MemoryOutcome.Refused("Nothing was changed.", 404)
        proposals.remove(p)
        dismissed += id
        return MemoryOutcome.Ok("Dropped: ${p.title}. It won't be proposed again.")
    }

    override suspend fun setProposing(on: Boolean): MemoryOutcome<String> {
        calls += "proposing $on"
        if (down) return MemoryOutcome.Unreachable("offline")
        proposing = on
        return MemoryOutcome.Ok(if (on) "On again." else "Alfred will not propose to-dos any more.")
    }

    companion object {
        val DENTIST = Proposal("p1", "Call the dentist", null)
        val REGISTRATION = Proposal("p2", "Renew the registration by November 20", LocalDate.of(2026, 11, 20))
        val DENTIST_BILLING = Proposal("p3", "Call the dentist billing desk", null)
    }
}
