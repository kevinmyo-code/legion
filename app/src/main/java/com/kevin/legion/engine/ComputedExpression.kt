package com.kevin.legion.engine

/**
 * Ticket 04 answer point 1's v1 vocabulary, and deliberately nothing more: "no conditionals, no
 * formula language" is the line that keeps this from becoming one. Every [FieldDef] whose
 * `type == FieldType.COMPUTED` carries exactly one of these two shapes in its `config` JSON,
 * parsed by [FieldConfig.computedExpression].
 */
sealed class ComputedExpression {
    /**
     * Aggregates a field on every CHILD record that references this one, via a
     * [com.kevin.legion.data.local.FieldType.REFERENCE] field living on the child's own record
     * type. This expression is declared on the PARENT'S [com.kevin.legion.data.local.FieldDef];
     * [childRecordTypeId] and [viaFieldId] name where to look, [sourceFieldId] names which field
     * ON THE CHILD to aggregate (null only for [AggregateOp.COUNT], which does not read a value at
     * all).
     */
    data class Aggregate(
        val childRecordTypeId: Long,
        val viaFieldId: Long,
        val op: AggregateOp,
        val sourceFieldId: Long? = null,
    ) : ComputedExpression()

    /**
     * Same-record arithmetic between two fields of the SAME [com.kevin.legion.data.local.RecordType]
     * this expression's own field lives on (ticket 04 answer point 1's "+ - * /").
     */
    data class Arithmetic(
        val leftFieldId: Long,
        val op: ArithmeticOp,
        val rightFieldId: Long,
    ) : ComputedExpression()
}

enum class AggregateOp { SUM, AVG, COUNT, MIN, MAX, LATEST }

enum class ArithmeticOp { PLUS, MINUS, TIMES, DIVIDE }
