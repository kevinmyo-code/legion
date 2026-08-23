package com.kevin.legion.engine

import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.EngineRecordDao
import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldDefDao
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.data.local.RecordTypeDao
import org.json.JSONObject

/** Thirty days, the purge window ticket 03 answer point 4 locked for both aspect archive and
 * record trash. A named constant so [RecordStore.purgeExpiredTrash] and any future aspect-purge
 * job read the exact same number rather than two independently-typed "30 days" literals drifting. */
const val TRASH_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * **The single write door for every [EngineRecord]** (ticket 03 answer point 3, ticket 11 answer
 * point 2). Nothing else - not a meta-tool, not a generated form, not the xlsx import gate, not a
 * capability plugin - writes `records` directly; everything calls [create]/[update]/[delete]/
 * [restore] here instead. What that buys, in one place:
 *
 * - **Reference existence on write** ([validateReferences]) - a [FieldType.REFERENCE] field can
 *   never point at a record that does not exist, is a different type than declared, or is trashed.
 * - **Per-field delete policy on delete, validated across the WHOLE descendant tree before
 *   anything is touched** ([buildDeletePlan]) - BLOCK anywhere in the tree refuses the entire
 *   delete, CASCADE trashes every referencing child recursively, NULLIFY clears the reference on
 *   every referencing child. Enforced here, never in SQL (ticket 03 answer point 3 - SQLite
 *   foreign keys cannot express "quarantine" or "detach a plugin" the way this needs to). Senior
 *   review, 2026-08-23 (BLOCKING): the first version of this applied CASCADE recursively as it
 *   went, one child at a time, so a CASCADE child that was itself BLOCK-guarded three levels down
 *   returned `Blocked` for ITS OWN delete call while the outer call had already trashed the
 *   top-level record and reported `Trashed` overall - a live record left referencing a trashed
 *   parent, the exact thing this class's own doc claims never happens. [delete] now walks the
 *   ENTIRE descendant tree first, collects every blocker anywhere in it, and only applies any
 *   write at all once the whole tree is confirmed clear.
 * - **Computed materialization on write** ([materializeArithmetic]/[invalidateParentAggregates]) -
 *   same-record arithmetic resolves immediately; a referencing child's write/delete recomputes any
 *   aggregate on the parent it points at. Ticket 04 answer point 2: "materialized on write...
 *   dashboard reads are instant."
 * - **Provenance tagging** ([EngineRecord.provenance]) - always required on [create], never
 *   inferred.
 *
 * Deliberately reads the WHOLE table of active children when recomputing an aggregate rather than
 * a SQL `SUM`/`COUNT` - [EngineRecord.payload] is opaque JSON, not queryable columns, so there is
 * no SQL aggregate to write against a JSON-keyed value without SQLite's JSON1 extension, which is
 * not reliably present across Android/OEM SQLite builds. This is the same "simple and inspectable
 * over clever" tradeoff CLAUDE.md §10 already accepts for `LedgerController`/`PantryController`'s
 * untested DB-write paths, and it is bounded by the same fact that makes it acceptable there: this
 * is a personal household app's data volume, not an enterprise table scan.
 */
class RecordStore(
    private val engineRecordDao: EngineRecordDao,
    private val fieldDefDao: FieldDefDao,
    private val recordTypeDao: RecordTypeDao,
) {

    /** What [create]/[update] give back - never a bare `Long`/`Unit`, so a reference-validation
     * failure is a real, worded [Failure] the caller can surface rather than a thrown exception a
     * voice tool would have to translate after the fact (CLAUDE.md §7's "a new tool's failure
     * result says in words what did NOT happen"). */
    sealed class WriteResult {
        data class Success(val recordId: Long) : WriteResult()
        data class Failure(val reason: String) : WriteResult()
    }

    sealed class DeleteResult {
        object Trashed : DeleteResult()
        object NotFound : DeleteResult()
        object AlreadyTrashed : DeleteResult()
        /** [blockers] names, in words, every referencing record ANYWHERE in the descendant tree
         * that stopped the delete (BLOCK policy) - not just a direct child's own BLOCK, but one
         * discovered several CASCADE hops down. Nothing is written when this is returned. */
        data class Blocked(val blockers: List<String>) : DeleteResult()
    }

    /**
     * Creates one record of [recordTypeId]. [fieldValues] maps [FieldDef.id] to a raw Kotlin value
     * matching [PayloadCodec.write]'s expectations for that field's [FieldType] - a
     * [FieldType.COMPUTED] entry in [fieldValues] is IGNORED (computed fields are never
     * driver-supplied, only materialized here).
     */
    suspend fun create(
        recordTypeId: Long,
        fieldValues: Map<Long, Any?>,
        provenance: RecordProvenance,
        now: Long = System.currentTimeMillis(),
    ): WriteResult {
        val fieldDefs = fieldDefDao.forRecordType(recordTypeId)
        val recordType = recordTypeDao.getById(recordTypeId)
            ?: return WriteResult.Failure("record type $recordTypeId does not exist")

        validateReferences(fieldDefs, fieldValues)?.let { return WriteResult.Failure(it) }

        val payload = JSONObject()
        for (fd in fieldDefs) {
            if (fd.type == FieldType.COMPUTED) continue
            PayloadCodec.write(payload, fd, fieldValues[fd.id])
        }
        materializeArithmetic(fieldDefs, payload)

        val record = EngineRecord(
            recordTypeId = recordTypeId,
            createdAt = now,
            updatedAt = now,
            dueAt = promotedLong(recordType.primaryDueDateFieldId, payload),
            amountCents = promotedLong(recordType.primaryAmountFieldId, payload),
            searchText = PayloadCodec.buildSearchText(fieldDefs, payload),
            provenance = provenance,
            payload = payload.toString(),
            deletedAt = null,
        )
        val id = engineRecordDao.insert(record)
        materializeOwnAggregates(id, fieldDefs, now)
        invalidateParentAggregates(recordTypeId, fieldDefs, payload, now)
        return WriteResult.Success(id)
    }

    /**
     * Updates an existing, non-trashed record. [fieldValues] is a PARTIAL map - only the keys
     * present are changed; everything else in the record's current payload is preserved. Reruns
     * reference validation, arithmetic materialization, and parent-aggregate invalidation exactly
     * as [create] does, since any of those can change on an update just as easily as on a create.
     */
    suspend fun update(
        recordId: Long,
        fieldValues: Map<Long, Any?>,
        now: Long = System.currentTimeMillis(),
    ): WriteResult {
        val existing = engineRecordDao.getById(recordId)
            ?: return WriteResult.Failure("record $recordId does not exist")
        if (existing.deletedAt != null) return WriteResult.Failure("record $recordId is in trash - restore it first")

        val fieldDefs = fieldDefDao.forRecordType(existing.recordTypeId)
        val recordType = recordTypeDao.getById(existing.recordTypeId)
            ?: return WriteResult.Failure("record type ${existing.recordTypeId} does not exist")

        validateReferences(fieldDefs, fieldValues)?.let { return WriteResult.Failure(it) }

        val payload = JSONObject(existing.payload)
        for (fd in fieldDefs) {
            if (fd.type == FieldType.COMPUTED) continue
            if (fieldValues.containsKey(fd.id)) PayloadCodec.write(payload, fd, fieldValues[fd.id])
        }
        materializeArithmetic(fieldDefs, payload)

        val updated = existing.copy(
            updatedAt = now,
            dueAt = promotedLong(recordType.primaryDueDateFieldId, payload),
            amountCents = promotedLong(recordType.primaryAmountFieldId, payload),
            searchText = PayloadCodec.buildSearchText(fieldDefs, payload),
            payload = payload.toString(),
        )
        engineRecordDao.update(updated)
        invalidateParentAggregates(existing.recordTypeId, fieldDefs, payload, now)
        return WriteResult.Success(recordId)
    }

    /**
     * Trashes a record (ticket 03 answer point 4: "record delete = trash, same 30-day restore").
     *
     * **Two strict passes, never interleaved** (senior review, 2026-08-23 BLOCKING fix - see this
     * class's own doc comment for the defect this replaced). Pass one, [buildDeletePlan], walks the
     * FULL descendant tree reachable through CASCADE-policy references - not just [recordId]'s
     * direct referrers, but every level a CASCADE chain reaches - and returns every BLOCK-guarded
     * referrer found ANYWHERE in that tree, worded, without writing anything. If that list is
     * non-empty the whole delete is refused via [DeleteResult.Blocked] and NOTHING changes,
     * including [recordId] itself. Only once the entire tree is confirmed clear does pass two run:
     * trash [recordId], trash every CASCADE descendant the walk found, clear every NULLIFY
     * reference, then invalidate whatever aggregates were watching any of the now-changed records.
     */
    suspend fun delete(recordId: Long, now: Long = System.currentTimeMillis()): DeleteResult {
        val record = engineRecordDao.getById(recordId) ?: return DeleteResult.NotFound
        if (record.deletedAt != null) return DeleteResult.AlreadyTrashed

        val blockers = mutableListOf<String>()
        val cascadeRecords = mutableListOf<EngineRecord>()
        val nullifyTargets = mutableListOf<Pair<EngineRecord, FieldDef>>()
        buildDeletePlan(
            recordId = record.id,
            recordTypeId = record.recordTypeId,
            visited = mutableSetOf(),
            blockers = blockers,
            cascadeRecords = cascadeRecords,
            nullifyTargets = nullifyTargets,
        )

        if (blockers.isNotEmpty()) return DeleteResult.Blocked(blockers)

        // Whole tree confirmed clear - now, and only now, write anything.
        engineRecordDao.trash(record.id, now)
        for (child in cascadeRecords) engineRecordDao.trash(child.id, now)
        for ((child, refField) in nullifyTargets) {
            val childPayload = JSONObject(child.payload)
            childPayload.put(PayloadCodec.key(refField.id), JSONObject.NULL)
            engineRecordDao.update(child.copy(updatedAt = now, payload = childPayload.toString()))
        }

        // Invalidate any aggregate watching the root record OR any record that was just cascaded
        // away - a cascaded child can itself be someone else's aggregated child.
        for (changed in listOf(record) + cascadeRecords) {
            val fieldDefs = fieldDefDao.forRecordType(changed.recordTypeId)
            invalidateParentAggregates(changed.recordTypeId, fieldDefs, JSONObject(changed.payload), now)
        }
        return DeleteResult.Trashed
    }

    /** Clears a record's trash tombstone - the record's data was never touched by [delete] in the
     * first place, so this is exactly [EngineRecordDao.restore], nothing more. */
    suspend fun restore(recordId: Long, now: Long = System.currentTimeMillis()): Boolean =
        engineRecordDao.restore(recordId, now) > 0

    /** The 30-day hard purge (ticket 03 answer point 4). Only ever reaches a record trashed at
     * least [TRASH_RETENTION_MS] ago - restoring before that window elapses is always possible. */
    suspend fun purgeExpiredTrash(now: Long = System.currentTimeMillis()): Int =
        engineRecordDao.purgeDeletedBefore(now - TRASH_RETENTION_MS)

    // ---- reference existence on write ---------------------------------------------------------

    /** Returns a worded failure reason, or null if every [FieldType.REFERENCE] in [fieldValues]
     * points at a real, live, correctly-typed record. */
    private suspend fun validateReferences(fieldDefs: List<FieldDef>, fieldValues: Map<Long, Any?>): String? {
        for (fd in fieldDefs) {
            if (fd.type != FieldType.REFERENCE) continue
            if (!fieldValues.containsKey(fd.id)) continue
            val raw = fieldValues[fd.id] ?: continue // null is a legal "no reference set"
            val targetId = (raw as? Number)?.toLong()
                ?: return "field '${fd.name}' needs a record id, not ${raw::class.simpleName}"
            val refConfig = FieldConfig.referenceConfig(fd.config)
                ?: return "field '${fd.name}' has no reference target configured"
            val target = engineRecordDao.getById(targetId)
            if (target == null) return "field '${fd.name}' points at record $targetId, which does not exist"
            if (target.deletedAt != null) return "field '${fd.name}' points at record $targetId, which is in trash"
            if (target.recordTypeId != refConfig.targetRecordTypeId) {
                return "field '${fd.name}' points at record $targetId, which is the wrong record type"
            }
        }
        return null
    }

    // ---- per-field delete policy: full-tree pre-flight plan --------------------------------------

    /**
     * Walks every [FieldType.REFERENCE] field (on ANY record type) whose config targets
     * [recordTypeId], finds every currently-active record of that field's owning type whose stored
     * reference value is [recordId], and sorts each into [blockers] (BLOCK), [cascadeRecords]
     * (CASCADE - and then recurses INTO that child, since it is about to be trashed too and its own
     * referrers need the same treatment), or [nullifyTargets] (NULLIFY - not recursed into, since a
     * nullified child stays alive and is never itself deleted by this walk).
     *
     * **Writes nothing.** This function only ever reads and appends to the three output lists -
     * [delete] is the sole place any of this plan is applied, and only after confirming [blockers]
     * is empty for the WHOLE tree, not just [recordId]'s own direct referrers (the BLOCKING defect
     * this replaced: a three-level-deep BLOCK used to surface only when that deeper record's own
     * recursive [delete] call ran, by which point the top-level trash had already happened).
     *
     * [visited] is the cycle guard - `visited.add(recordId)` returning `false` means this exact
     * record id was already walked earlier in the SAME [delete] call (a cyclic CASCADE reference,
     * e.g. A cascades to B and B cascades back to A), so the walk stops immediately rather than
     * recursing forever. A record already in [visited] is also never re-added to [cascadeRecords],
     * so a diamond-shaped CASCADE graph (A cascades to both B and C, and both B and C cascade to D)
     * still only queues D for trashing once.
     */
    private suspend fun buildDeletePlan(
        recordId: Long,
        recordTypeId: Long,
        visited: MutableSet<Long>,
        blockers: MutableList<String>,
        cascadeRecords: MutableList<EngineRecord>,
        nullifyTargets: MutableList<Pair<EngineRecord, FieldDef>>,
    ) {
        if (!visited.add(recordId)) return // already walked this record in this call - cycle guard

        val referenceFields = fieldDefDao.allReferenceFields()
        for (refField in referenceFields) {
            val refConfig = FieldConfig.referenceConfig(refField.config) ?: continue
            if (refConfig.targetRecordTypeId != recordTypeId) continue

            val children = engineRecordDao.activeByRecordType(refField.recordTypeId)
            for (child in children) {
                val childPayload = JSONObject(child.payload)
                val pointsAtThisRecord = PayloadCodec.readReferenceId(childPayload, refField.id) == recordId
                if (!pointsAtThisRecord) continue
                when (refConfig.deletePolicy) {
                    DeletePolicy.BLOCK ->
                        blockers += "record #${child.id} still references record #$recordId through '${refField.name}'"
                    DeletePolicy.CASCADE -> {
                        if (child.id !in visited) {
                            cascadeRecords += child
                            buildDeletePlan(child.id, child.recordTypeId, visited, blockers, cascadeRecords, nullifyTargets)
                        }
                    }
                    DeletePolicy.NULLIFY -> nullifyTargets += child to refField
                }
            }
        }
    }

    // ---- computed materialization on write -----------------------------------------------------

    /** Resolves every [FieldType.COMPUTED] field on [fieldDefs] whose expression is
     * [ComputedExpression.Arithmetic] against the OTHER values already sitting in [payload], and
     * writes the result back into [payload] in place. Aggregate expressions are NOT touched here -
     * a record's own arithmetic never depends on its children, only [invalidateParentAggregates]
     * (called on the CHILD side) ever recomputes those. */
    private fun materializeArithmetic(fieldDefs: List<FieldDef>, payload: JSONObject) {
        val byId = fieldDefs.associateBy { it.id }
        for (fd in fieldDefs) {
            if (fd.type != FieldType.COMPUTED) continue
            val expr = FieldConfig.computedExpression(fd.config) as? ComputedExpression.Arithmetic ?: continue
            val left = byId[expr.leftFieldId]
            val right = byId[expr.rightFieldId]
            val missing = when {
                left == null -> "a source field for '${fd.name}' was deleted"
                right == null -> "a source field for '${fd.name}' was deleted"
                else -> null
            }
            val value = when {
                missing != null -> ComputedValue.Error(missing)
                left!!.type == FieldType.MONEY_CENTS && right!!.type == FieldType.MONEY_CENTS ->
                    ComputedEvaluator.arithmeticMoneyCents(
                        expr.op,
                        PayloadCodec.readLong(payload, left.id),
                        PayloadCodec.readLong(payload, right.id),
                        null,
                    )
                left!!.type == FieldType.NUMBER || left.type == FieldType.RATING ->
                    ComputedEvaluator.arithmeticNumeric(
                        expr.op,
                        PayloadCodec.readDouble(payload, left.id),
                        PayloadCodec.readDouble(payload, right!!.id),
                        null,
                    )
                else -> ComputedValue.Error("'${fd.name}' cannot be computed from non-numeric fields")
            }
            PayloadCodec.writeComputed(payload, fd, value)
        }
    }

    /**
     * A brand-new record can never already have any children referencing it - a child's own create
     * validates its reference against an EXISTING record id ([validateReferences]), so nothing can
     * point at [recordId] before this very call returns it. That makes a fresh record's own
     * aggregate fields deterministic at creation time without waiting for a child write to trigger
     * [invalidateParentAggregates]: SUM/AVG/COUNT read as a real 0, MIN/MAX/LATEST as
     * [ComputedValue.Empty] - never simply absent from the payload, which would read to a caller
     * as "not computed yet" rather than "computed, and there is nothing to report" (ticket 04
     * answer point 4's "never a silent zero" cuts the other way here too: a MISSING key is exactly
     * as wrong as a fabricated one). Reuses [recomputeAggregate] itself rather than duplicating its
     * empty-list logic, since a genuinely childless aggregate is just [recomputeAggregate]'s normal
     * path with an empty `children` list - not a special case.
     */
    private suspend fun materializeOwnAggregates(recordId: Long, fieldDefs: List<FieldDef>, now: Long) {
        for (fd in fieldDefs) {
            if (fd.type != FieldType.COMPUTED) continue
            val expr = FieldConfig.computedExpression(fd.config) as? ComputedExpression.Aggregate ?: continue
            recomputeAggregate(recordId, fd, expr, now)
        }
    }

    /**
     * Called after a child record's write/delete settles. For every [FieldType.REFERENCE] field on
     * [childRecordTypeId] present in [childPayload], finds any [FieldType.COMPUTED] aggregate field
     * (on any OTHER record type) whose expression watches this exact (child type, reference field)
     * pair, and recomputes it onto the specific parent [childPayload] points at.
     */
    private suspend fun invalidateParentAggregates(
        childRecordTypeId: Long,
        childFieldDefs: List<FieldDef>,
        childPayload: JSONObject,
        now: Long,
    ) {
        val referenceFields = childFieldDefs.filter { it.type == FieldType.REFERENCE }
        if (referenceFields.isEmpty()) return

        val allComputed = fieldDefDao.allComputedFields()
        for (refField in referenceFields) {
            val parentId = PayloadCodec.readReferenceId(childPayload, refField.id) ?: continue
            val watchingAggregates = allComputed.mapNotNull { fd ->
                val expr = FieldConfig.computedExpression(fd.config) as? ComputedExpression.Aggregate
                if (expr != null && expr.childRecordTypeId == childRecordTypeId && expr.viaFieldId == refField.id) {
                    fd to expr
                } else {
                    null
                }
            }
            for ((aggFieldDef, expr) in watchingAggregates) {
                recomputeAggregate(parentId, aggFieldDef, expr, now)
            }
        }
    }

    /** Recomputes one [ComputedExpression.Aggregate] field and writes the result onto the parent
     * record at [parentId] - including re-deriving [EngineRecord.amountCents] if that record type's
     * [RecordType.primaryAmountFieldId] happens to be this exact aggregate field. [now] is threaded
     * through from the caller's own write ([create]/[update]/[delete]'s `now` parameter) rather than
     * read fresh here, so every row a single logical write touches - the record itself plus every
     * aggregate it invalidates - carries the SAME `updatedAt`, matching the rest of this class's
     * now-injection convention (and keeping this function trivially testable with a fixed clock,
     * same reasoning as [create]/[update]/[delete]'s own `now` defaults). */
    private suspend fun recomputeAggregate(
        parentId: Long,
        aggFieldDef: FieldDef,
        expr: ComputedExpression.Aggregate,
        now: Long,
    ) {
        val parent = engineRecordDao.getById(parentId) ?: return // parent may itself be gone/trashed
        val parentRecordType = recordTypeDao.getById(parent.recordTypeId) ?: return
        val childFieldDefs = fieldDefDao.forRecordType(expr.childRecordTypeId)
        val sourceFieldDef = expr.sourceFieldId?.let { id -> childFieldDefs.find { it.id == id } }

        val children = engineRecordDao.activeByRecordType(expr.childRecordTypeId)
            .filter { PayloadCodec.readReferenceId(JSONObject(it.payload), expr.viaFieldId) == parentId }
            .sortedBy { it.updatedAt } // LATEST reads the most-recently-updated child last

        val value: ComputedValue = when {
            expr.op == AggregateOp.COUNT -> ComputedValue.Count(children.size)
            sourceFieldDef == null -> ComputedValue.Error("the aggregated field on '${aggFieldDef.name}' was deleted")
            sourceFieldDef.type == FieldType.MONEY_CENTS -> ComputedEvaluator.aggregateMoneyCents(
                expr.op,
                children.mapNotNull { PayloadCodec.readLong(JSONObject(it.payload), sourceFieldDef.id) },
            )
            sourceFieldDef.type == FieldType.NUMBER || sourceFieldDef.type == FieldType.RATING ->
                ComputedEvaluator.aggregateNumeric(
                    expr.op,
                    children.mapNotNull { PayloadCodec.readDouble(JSONObject(it.payload), sourceFieldDef.id) },
                )
            else -> ComputedValue.Error("'${sourceFieldDef.name}' is not an aggregatable field type")
        }

        val payload = JSONObject(parent.payload)
        PayloadCodec.writeComputed(payload, aggFieldDef, value)
        val updated = parent.copy(
            updatedAt = now,
            payload = payload.toString(),
            amountCents = if (parentRecordType.primaryAmountFieldId == aggFieldDef.id) {
                (value as? ComputedValue.MoneyCents)?.cents
            } else {
                parent.amountCents
            },
        )
        engineRecordDao.update(updated)
    }

    // ---- promoted-column resolution -------------------------------------------------------------

    private fun promotedLong(fieldId: Long?, payload: JSONObject): Long? =
        fieldId?.let { PayloadCodec.readLong(payload, it) }
}
