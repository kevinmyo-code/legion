package com.kevin.legion.sync

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Pure (Android-free) serialization for the sync snapshots: a table's rows <->
 * gzipped NDJSON (one JSON object per line). Split out from [SyncEngine] so the
 * format round-trips under a plain JVM unit test.
 */
internal object SyncCodec {

    fun gzipNdjson(rows: List<JSONObject>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { w ->
            for (row in rows) { w.write(row.toString()); w.write("\n") }
        }
        return out.toByteArray()
    }

    fun rowsFromGzipNdjson(bytes: ByteArray): List<JSONObject> =
        GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        }

    /** A JSON value -> a SQLite bind arg (null / Long / Double / String). */
    fun sqlArg(row: JSONObject, key: String): Any? {
        if (row.isNull(key)) return null
        return when (val v = row.get(key)) {
            is Int -> v.toLong()
            is Long, is Double, is String -> v
            is Boolean -> if (v) 1L else 0L
            else -> v.toString()
        }
    }
}
