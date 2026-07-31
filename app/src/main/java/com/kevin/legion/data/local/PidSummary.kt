package com.kevin.legion.data.local

/**
 * Room projection for [OdbSampleDao.summarize] - min/max/avg/count/span for one
 * PID within a time window. NOT an @Entity: a query projection, not a table, so
 * it must never be added to [CarDatabase]'s entity list.
 */
data class PidSummary(
    val min: Double,
    val max: Double,
    val avg: Double,
    val count: Int,
    val firstMs: Long,
    val lastMs: Long,
)
