package com.kevin.legion.memory

/**
 * A [MemberMemoryBackend] whose answers a test sets, recording every call. [store] stands in for
 * the engine's table: list and recall read it, create/update/delete change it (delete is a hard
 * delete, like the engine's).
 */
class FakeMemberMemoryBackend(
    val store: MutableList<MemberMemory> = mutableListOf(),
) : MemberMemoryBackend {
    /** Set to make every call answer this instead of touching [store]. */
    var failWith: MemoryOutcome<Nothing>? = null
    /** Set to make only writes answer this. */
    var failWritesWith: MemoryOutcome<Nothing>? = null
    val calls = mutableListOf<String>()
    private var next = 0

    override suspend fun list(limit: Int): MemoryOutcome<List<MemberMemory>> {
        calls += "list"
        failWith?.let { return it }
        return MemoryOutcome.Ok(store.sortedByDescending { it.createdAtMs }.take(limit))
    }

    override suspend fun recall(query: String, limit: Int): MemoryOutcome<List<MemberMemory>> {
        calls += "recall:$query"
        failWith?.let { return it }
        return MemoryOutcome.Ok(MemoryScore.best(store, query, NOW, limit))
    }

    override suspend fun create(text: String, shared: Boolean, source: String): MemoryOutcome<MemberMemory> {
        calls += "create:$source:$shared"
        (failWith ?: failWritesWith)?.let { return it }
        val made = memory(id = "new-${next++}", text = text, shared = shared, source = source, createdAtMs = NOW)
        store += made
        return MemoryOutcome.Ok(made)
    }

    override suspend fun update(id: String, edit: MemoryEdit): MemoryOutcome<MemberMemory> {
        calls += "update:$id"
        (failWith ?: failWritesWith)?.let { return it }
        val i = store.indexOfFirst { it.id == id }
        if (i < 0) return MemoryOutcome.Refused(NOT_FOUND, 404)
        val changed = store[i].copy(text = edit.text ?: store[i].text, shared = edit.shared ?: store[i].shared)
        store[i] = changed
        return MemoryOutcome.Ok(changed)
    }

    override suspend fun delete(id: String): MemoryOutcome<Unit> {
        calls += "delete:$id"
        (failWith ?: failWritesWith)?.let { return it }
        if (store.none { it.id == id }) {
            return MemoryOutcome.Refused(NOT_FOUND, 404)
        }
        store.removeAll { it.id == id }
        return MemoryOutcome.Ok(Unit)
    }

    companion object {
        const val NOW = 1_800_000_000_000L
        const val NOT_FOUND = "Nothing was changed. There is no memory with that id that you can see."
        const val HOUR = 3_600_000L

        fun memory(
            id: String,
            text: String,
            shared: Boolean = false,
            source: String = "phone",
            createdAtMs: Long = NOW - HOUR,
            aboutYou: Boolean = true,
            importance: Int = 5,
            decayClass: String = "durable",
            fromConversation: String? = null,
        ) = MemberMemory(
            id = id, text = text, kind = "fact", importance = importance, decayClass = decayClass,
            source = source, shared = shared, aboutYou = aboutYou, createdAtMs = createdAtMs,
            fromConversation = fromConversation,
        )
    }
}
