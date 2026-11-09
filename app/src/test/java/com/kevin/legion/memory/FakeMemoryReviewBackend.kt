package com.kevin.legion.memory

/**
 * A [MemoryReviewBackend] whose answers a test sets, recording every call. [pendingChanges] stands
 * in for the engine's pending list: accept and dismiss take a change off it; [staleIds] answer 409
 * the way the engine does when the memories moved since the review.
 */
class FakeMemoryReviewBackend(
    val pendingChanges: MutableList<ReviewChange> = mutableListOf(),
    val retiredRows: MutableList<MemberMemory> = mutableListOf(),
) : MemoryReviewBackend {
    var failWith: MemoryOutcome<Nothing>? = null
    val staleIds = mutableSetOf<Long>()
    val calls = mutableListOf<String>()

    override suspend fun pending(): MemoryOutcome<List<ReviewChange>> {
        calls += "pending"
        failWith?.let { return it }
        return MemoryOutcome.Ok(pendingChanges.toList())
    }

    override suspend fun accept(id: Long): MemoryOutcome<String> = answer("accept:$id", id) {
        "Done. Your memory is updated."
    }

    override suspend fun dismiss(id: Long): MemoryOutcome<String> = answer("dismiss:$id", id) {
        "Dismissed. Nothing was changed."
    }

    override suspend fun revert(id: Long): MemoryOutcome<String> {
        calls += "revert:$id"
        failWith?.let { return it }
        retiredRows.removeAll { it.retiredChange == id }
        return MemoryOutcome.Ok("Undone. 1 memory restored.")
    }

    override suspend fun acceptCluster(cluster: String): MemoryOutcome<String> {
        calls += "cluster:$cluster"
        failWith?.let { return it }
        val n = pendingChanges.count { it.cluster == cluster }
        pendingChanges.removeAll { it.cluster == cluster }
        return MemoryOutcome.Ok("Accepted $n.")
    }

    override suspend fun retired(limit: Int): MemoryOutcome<List<MemberMemory>> {
        calls += "retired"
        failWith?.let { return it }
        return MemoryOutcome.Ok(retiredRows.toList())
    }

    private fun answer(call: String, id: Long, words: () -> String): MemoryOutcome<String> {
        calls += call
        pendingChanges.removeAll { failWith == null && it.id == id }
        return failWith ?: if (id in staleIds) MemoryOutcome.Refused(STALE, 409) else MemoryOutcome.Ok(words())
    }

    companion object {
        const val STALE = "Nothing was changed. Those memories changed since the review."

        fun change(id: Long, cluster: String = "1.$id", action: String = "merge") = ReviewChange(
            id = id,
            action = action,
            status = "pending",
            cluster = cluster,
            before = listOf(ReviewBefore("a$id", "Sam's pharmacy is Maple Pharmacy"), ReviewBefore("b$id", null)),
            afterWords = "Sam's pharmacy is Maple Pharmacy",
            reason = "Two rows name the same pharmacy.",
            actionWords = "These say the same thing; keep one.",
            evidenceDate = "2026-11-07",
        )
    }
}
