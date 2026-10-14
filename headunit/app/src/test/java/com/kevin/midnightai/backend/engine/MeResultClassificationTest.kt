package com.kevin.midnightai.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeResultClassificationTest {

    private val base = "https://engine.example"
    private val okBody = """{"user_id":"u1","email":"k@example.com","device_name":"dash"}"""

    private fun classify(code: Int, body: String = "") = EngineAuth.meResultFor(base, code, body)

    @Test fun twoHundredWithAMembershipBodyIsOk() {
        val r = classify(200, okBody)
        assertTrue(r.toString(), r is MeResult.Ok)
        assertEquals("k@example.com", (r as MeResult.Ok).email)
    }

    @Test fun four01IsRefused() {
        assertTrue(classify(401, """{"detail":"Invalid token."}""") is MeResult.Refused)
    }

    @Test fun four03IsRefused() {
        assertTrue(classify(403, """{"detail":"Not a member."}""") is MeResult.Refused)
    }

    @Test fun refusalCarriesTheEnginesDetail() {
        val r = classify(401, """{"detail":"Token revoked."}""") as MeResult.Refused
        assertTrue(r.message.contains("Token revoked."))
    }

    @Test fun five00IsUnreachableNotRefused() {
        assertTrue(classify(500, "oops") is MeResult.Unreachable)
    }

    @Test fun five02FromCloudRunIsUnreachableNotRefused() {
        val r = classify(502, "<html>Bad Gateway</html>")
        assertTrue(r is MeResult.Unreachable)
        assertTrue((r as MeResult.Unreachable).message.contains("502"))
    }

    @Test fun otherClientErrorsAreNotRefusals() {
        assertTrue(classify(429) is MeResult.Unreachable)
        assertTrue(classify(404) is MeResult.Unreachable)
    }

    @Test fun twoHundredWithJunkIsUnreachable() {
        assertTrue(classify(200, "<html>") is MeResult.Unreachable)
    }
}
