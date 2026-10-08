package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Kevin, 2026-10-07: card rows reading "JOHN NAUS MD PA COLLEYVILLE TX" should show as "Walmart".
 * Ledger rows are gate-only and immutable, so the bank's `description` is never rewritten; this
 * household-scoped rule changes what every surface SHOWS. See
 * [com.kevin.legion.ledger.displayDescription] for the one resolution function.
 *
 * **Display only.** Category rules, merchant-key grouping, dedup, transfer detection, pending-log
 * matching and voice `set_category` all keep reading the raw description, on purpose.
 *
 * Shaped exactly like [CategoryRule] (sync columns included) because it rides the same ledger-config
 * sync: [substring] is matched case-insensitively against the raw description, oldest rule first.
 */
@Entity(tableName = "merchant_aliases", indices = [Index(value = ["guid"], unique = true)])
data class MerchantAlias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val substring: String,
    val displayName: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val guid: String = java.util.UUID.randomUUID().toString(),
    val serverId: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
