package com.kevin.legion.ui.widgets

import org.json.JSONObject

/**
 * The per-widget parameter blob every [com.kevin.legion.engine.WidgetKind] reads out of
 * [com.kevin.legion.data.local.WidgetInstance.config] (aspect-engine ticket 18). One shared shape
 * rather than eight per-kind ones - every field is optional and a given widget kind only ever reads
 * the subset it needs, matching [com.kevin.legion.engine.FieldConfig]'s own "one place every shape
 * is defined" posture for [com.kevin.legion.data.local.FieldDef.config].
 *
 * - [fieldId]: the field a STAT_TILE sums/averages, or CHART plots on the y-axis. `null` on a
 *   STAT_TILE means "show a live COUNT of the record type" rather than summing a field.
 * - [dateFieldId]: CHART's x-axis field (must be DATE/DATETIME on the same record type).
 * - [recordId]: the single record a SINGLE_RECORD_CARD or PHOTO widget pins to.
 * - [photoFieldId]: which PHOTO-typed field on [recordId] a PHOTO widget reads.
 * - [limit]: how many rows RECORD_LIST/AGENDA render at most - independent of the widget's own
 *   [com.kevin.legion.ui.grid.GridPreset] (a caller may still want a shorter list at a tall preset).
 * - [targetRecordTypeId]: which record type QUICK_ADD's button opens the generated add form for.
 *
 * A widget instance whose config leaves the field(s) its kind actually needs unset is not an error -
 * it is the seeded, "not configured yet" state [com.kevin.legion.engine.DefaultArrangementSeeder]
 * deliberately ships, and every widget composable in [EngineWidgets.kt] renders that state in words
 * rather than crashing or showing a blank.
 */
data class WidgetConfig(
    val fieldId: Long? = null,
    val dateFieldId: Long? = null,
    val recordId: Long? = null,
    val photoFieldId: Long? = null,
    val limit: Int = 5,
    val targetRecordTypeId: Long? = null,
)

object WidgetConfigCodec {
    fun parse(json: String): WidgetConfig {
        if (json.isBlank()) return WidgetConfig()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return WidgetConfig()
        return WidgetConfig(
            fieldId = obj.optLongOrNull("fieldId"),
            dateFieldId = obj.optLongOrNull("dateFieldId"),
            recordId = obj.optLongOrNull("recordId"),
            photoFieldId = obj.optLongOrNull("photoFieldId"),
            limit = if (obj.has("limit")) obj.optInt("limit", 5) else 5,
            targetRecordTypeId = obj.optLongOrNull("targetRecordTypeId"),
        )
    }

    fun serialize(config: WidgetConfig): String {
        val obj = JSONObject()
        config.fieldId?.let { obj.put("fieldId", it) }
        config.dateFieldId?.let { obj.put("dateFieldId", it) }
        config.recordId?.let { obj.put("recordId", it) }
        config.photoFieldId?.let { obj.put("photoFieldId", it) }
        obj.put("limit", config.limit)
        config.targetRecordTypeId?.let { obj.put("targetRecordTypeId", it) }
        return obj.toString()
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}
