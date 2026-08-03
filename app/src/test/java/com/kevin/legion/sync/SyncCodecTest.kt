package com.kevin.legion.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [SyncCodec] directly - the gzipped-NDJSON wire format every
 * [SyncEngine] table snapshot is serialized through. Plain JUnit; [SyncCodec]
 * is declared Android-free in its own doc comment and `org.json:json` is a
 * `testImplementation` (see `SyncMergeTest`). This only proves the FORMAT
 * round-trips - it says nothing about the Drive upload/download path or the
 * SQLite bind side of [SyncCodec.sqlArg], neither of which is exercised here.
 */
class SyncCodecTest {

    // -------------------------------------------------------------- round-trip

    @Test
    fun `rows survive a gzip-ndjson round trip with their values and types intact`() {
        val rows = listOf(
            JSONObject().put("syncId", "a").put("amountCents", 12345L).put("lat", 1.5).put("active", true),
            JSONObject().put("syncId", "b").put("amountCents", -500L).put("lat", -0.25).put("active", false),
        )

        val decoded = SyncCodec.rowsFromGzipNdjson(SyncCodec.gzipNdjson(rows))

        assertEquals(2, decoded.size)
        assertEquals("a", decoded[0].getString("syncId"))
        assertEquals(12345L, decoded[0].getLong("amountCents"))
        assertEquals(1.5, decoded[0].getDouble("lat"), 0.0)
        assertEquals(true, decoded[0].getBoolean("active"))
        assertEquals("b", decoded[1].getString("syncId"))
        assertEquals(-500L, decoded[1].getLong("amountCents"))
    }

    @Test
    fun `an explicit JSON null value round trips as null, not as a missing key`() {
        val rows = listOf(JSONObject().put("syncId", "a").put("description", JSONObject.NULL))

        val decoded = SyncCodec.rowsFromGzipNdjson(SyncCodec.gzipNdjson(rows))

        assertTrue(decoded.single().has("description"))
        assertTrue(decoded.single().isNull("description"))
    }

    @Test
    fun `an empty row list round trips to an empty list, not a crash on an empty gzip stream`() {
        val decoded = SyncCodec.rowsFromGzipNdjson(SyncCodec.gzipNdjson(emptyList()))

        assertTrue(decoded.isEmpty())
    }

    /**
     * [SyncCodec.rowsFromGzipNdjson] filters `isNotBlank()` before parsing, so
     * a blank line (e.g. a stray trailing newline) is skipped rather than
     * thrown as a JSON parse error.
     */
    @Test
    fun `blank lines between rows are skipped rather than failing to parse`() {
        val bytes = gzipRawLines(
            JSONObject().put("syncId", "a").toString(),
            "",
            "   ",
            JSONObject().put("syncId", "b").toString(),
        )

        val decoded = SyncCodec.rowsFromGzipNdjson(bytes)

        assertEquals(2, decoded.size)
        assertEquals("a", decoded[0].getString("syncId"))
        assertEquals("b", decoded[1].getString("syncId"))
    }

    @Test
    fun `unicode values survive the round trip byte for byte`() {
        val rows = listOf(JSONObject().put("syncId", "a").put("description", "coffee ☕ café 日本語"))

        val decoded = SyncCodec.rowsFromGzipNdjson(SyncCodec.gzipNdjson(rows))

        assertEquals("coffee ☕ café 日本語", decoded.single().getString("description"))
    }

    /**
     * NDJSON is one JSON object per LINE, so a raw `\n` inside a string value
     * would corrupt the format if it were written literally - it would look
     * like a second, malformed line. It does NOT corrupt the format here,
     * because [gzipNdjson] writes `JSONObject.toString()`, and `org.json`'s
     * serializer escapes an embedded newline as the two characters `\` `n`
     * (see `JSONObject.quote`) rather than emitting a literal line break.
     * Pinning that this holds through the actual round trip, not just
     * asserting it from the doc.
     */
    @Test
    fun `a newline embedded in a string value does not corrupt the ndjson format`() {
        val rows = listOf(JSONObject().put("syncId", "a").put("description", "line one\nline two"))

        val bytes = SyncCodec.gzipNdjson(rows)
        val decoded = SyncCodec.rowsFromGzipNdjson(bytes)

        assertEquals(1, decoded.size)
        assertEquals("line one\nline two", decoded.single().getString("description"))
    }

    // -------------------------------------------------------------------- sqlArg

    @Test
    fun `sqlArg maps a null value to null`() {
        val row = JSONObject().put("x", JSONObject.NULL)
        assertNull(SyncCodec.sqlArg(row, "x"))
    }

    @Test
    fun `sqlArg widens an Int to a Long`() {
        // org.json stores small whole numbers as java.lang.Integer, and SQLite
        // bind args need a consistent integer type - sqlArg widens Int -> Long.
        val row = JSONObject("""{"x": 42}""")
        val arg = SyncCodec.sqlArg(row, "x")
        assertEquals(42L, arg)
        assertTrue(arg is Long)
    }

    @Test
    fun `sqlArg passes a Long through unchanged`() {
        val row = JSONObject().put("x", 4294967296L) // outside Int range, forces org.json to store a Long
        assertEquals(4294967296L, SyncCodec.sqlArg(row, "x"))
    }

    @Test
    fun `sqlArg passes a Double through unchanged`() {
        val row = JSONObject().put("x", 1.5)
        assertEquals(1.5, SyncCodec.sqlArg(row, "x"))
    }

    @Test
    fun `sqlArg passes a String through unchanged`() {
        val row = JSONObject().put("x", "hello")
        assertEquals("hello", SyncCodec.sqlArg(row, "x"))
    }

    /**
     * SQLite has no boolean column type, so `sqlArg` maps a JSON boolean to
     * the conventional 1/0 Long rather than passing a `Boolean` bind arg
     * through - pinning the exact mapping (`true` -> `1L`, `false` -> `0L`),
     * which is the same convention `car_tasks`/`places`' `deleted` column
     * uses as a Long, not a Boolean.
     */
    @Test
    fun `sqlArg maps a boolean to 1 or 0 as a Long`() {
        val row = JSONObject().put("t", true).put("f", false)
        assertEquals(1L, SyncCodec.sqlArg(row, "t"))
        assertEquals(0L, SyncCodec.sqlArg(row, "f"))
    }

    /** Writes raw (unescaped) NDJSON lines through gzip, bypassing [SyncCodec.gzipNdjson], to test malformed input shapes directly. */
    private fun gzipRawLines(vararg lines: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { w ->
            for (line in lines) { w.write(line); w.write("\n") }
        }
        return out.toByteArray()
    }
}
