package com.kevin.midnightai.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the gzipped-NDJSON sync codec (S1). No device needed. */
class SyncCodecTest {

    @Test
    fun gzipNdjson_roundTripsRowsIncludingNulls() {
        val rows = listOf(
            JSONObject().put("id", 1L).put("text", "plastic love").put("cost", JSONObject.NULL),
            JSONObject().put("id", 2L).put("v", 3.5),
        )
        val back = SyncCodec.rowsFromGzipNdjson(SyncCodec.gzipNdjson(rows))
        assertEquals(2, back.size)
        assertEquals("plastic love", back[0].getString("text"))
        assertTrue(back[0].isNull("cost"))
        assertEquals(3.5, back[1].getDouble("v"), 0.0001)
    }

    @Test
    fun gzipNdjson_emptyListRoundTripsToEmpty() {
        assertTrue(SyncCodec.rowsFromGzipNdjson(SyncCodec.gzipNdjson(emptyList())).isEmpty())
    }

    @Test
    fun sqlArg_convertsJsonTypesToBindArgs() {
        val r = JSONObject()
            .put("i", 5)
            .put("l", 7L)
            .put("d", 1.5)
            .put("s", "x")
            .put("b", true)
            .put("n", JSONObject.NULL)
        assertEquals(5L, SyncCodec.sqlArg(r, "i"))   // Int -> Long
        assertEquals(7L, SyncCodec.sqlArg(r, "l"))
        assertEquals(1.5, SyncCodec.sqlArg(r, "d"))
        assertEquals("x", SyncCodec.sqlArg(r, "s"))
        assertEquals(1L, SyncCodec.sqlArg(r, "b"))   // Boolean -> 1/0
        assertNull(SyncCodec.sqlArg(r, "n"))
    }
}
