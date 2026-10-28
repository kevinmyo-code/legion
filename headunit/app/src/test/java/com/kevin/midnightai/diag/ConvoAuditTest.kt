package com.kevin.midnightai.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoAuditTest {

    @Test
    fun `short text is kept whole on one line`() {
        assertEquals("how much did I spend this month", ConvoAudit.clip(" how much did I\nspend this month "))
    }

    @Test
    fun `long text is cut and says it was cut`() {
        val long = "a".repeat(ConvoAudit.MAX_CHARS + 50)
        val out = ConvoAudit.clip(long)
        assertTrue(out.startsWith("a".repeat(ConvoAudit.MAX_CHARS)))
        assertTrue(out.endsWith("[cut, ${ConvoAudit.MAX_CHARS + 50} chars]"))
    }

    @Test
    fun `the periodic reason is the engine's wire string`() {
        assertEquals("periodic", UploadReason.PERIODIC.wire)
    }
}
