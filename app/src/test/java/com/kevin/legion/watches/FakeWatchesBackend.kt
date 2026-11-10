package com.kevin.legion.watches

import kotlinx.serialization.json.JsonObject

/** An in-memory engine for the watches tests. Made-up labels and facts only. */
@Suppress("ReturnCount") // a fake's guard clauses: offline, refused, then the answer
class FakeWatchesBackend : WatchesBackend {
    var offline: WatchOutcome<Nothing>? = null
    val watches = mutableListOf<Watch>()
    val pendingFirings = mutableListOf<WatchFiring>()
    val acks = mutableListOf<Pair<List<String>, String>>()
    val created = mutableListOf<Pair<String, JsonObject>>()
    var ackFails = false
    var refuseCreate: String? = null

    override suspend fun list(): WatchOutcome<WatchList> =
        offline ?: WatchOutcome.Ok(WatchList(watches.toList(), emptyList()))

    override suspend fun create(
        kind: String,
        params: JsonObject,
        label: String?,
        source: String,
    ): WatchOutcome<WatchSet> {
        offline?.let { return it }
        refuseCreate?.let { return WatchOutcome.Refused(it, 400) }
        created += kind to params
        val made = watch("w${watches.size + 1}", label ?: "A $kind watch", kind = kind)
        watches += made
        return WatchOutcome.Ok(WatchSet(made, null))
    }

    override suspend fun update(id: String, fields: JsonObject): WatchOutcome<Watch> {
        offline?.let { return it }
        val i = watches.indexOfFirst { it.id == id }
        if (i < 0) return WatchOutcome.Refused("No such watch. Nothing was changed.", 404)
        val paused = fields["paused"].toString() == "true"
        watches[i] = watches[i].copy(paused = paused, status = if (paused) "paused" else "on")
        return WatchOutcome.Ok(watches[i])
    }

    override suspend fun delete(id: String): WatchOutcome<Unit> {
        offline?.let { return it }
        return if (watches.removeAll { it.id == id }) WatchOutcome.Ok(Unit)
        else WatchOutcome.Refused("No such watch. Nothing was changed.", 404)
    }

    override suspend fun pending(): WatchOutcome<List<WatchFiring>> =
        offline ?: WatchOutcome.Ok(pendingFirings.toList())

    override suspend fun recent(): WatchOutcome<List<WatchFiring>> = WatchOutcome.Ok(emptyList())

    override suspend fun ack(ids: List<String>, outcome: String): WatchOutcome<Int> {
        if (ackFails) return WatchOutcome.Unreachable("The engine could not be reached.")
        acks += ids to outcome
        pendingFirings.removeAll { it.id in ids }
        return WatchOutcome.Ok(ids.size)
    }

    companion object {
        fun watch(id: String, label: String, kind: String = KIND_CHARGE_OVER) = Watch(
            id = id,
            kind = kind,
            title = "A title",
            label = label,
            params = JsonObject(emptyMap()),
            repeat = true,
            paused = false,
            status = "on",
            builtin = null,
            evaluatedOn = if (kind == KIND_MAIL_REPLY) "phone" else "engine",
            lastFiredAtMs = null,
        )

        fun firing(id: String, facts: String, expiresAtMs: Long = Long.MAX_VALUE) = WatchFiring(
            id = id,
            watchId = "w1",
            watchLabel = "A charge of \$500.00 or more",
            kind = KIND_CHARGE_OVER,
            facts = facts,
            firedAtMs = 0L,
            expiresAtMs = expiresAtMs,
        )
    }
}

class MapSaidFirings : SaidFirings {
    val held = mutableMapOf<String, String>()
    override fun outcomeOf(id: String): String? = held[id]
    override fun remember(ids: List<String>, outcome: String) = ids.forEach { held[it] = outcome }
    override fun forget(ids: List<String>) = ids.forEach { held.remove(it) }
}
