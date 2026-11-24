package com.kevin.midnightai.backend

import com.kevin.midnightai.backend.engine.EngineConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recheck 2026-11-20: the unit's Android may not block cleartext, so manual engine entry refuses
 * http except to a private LAN address (and the screen then says "not encrypted").
 */
class EngineUrlPolicyTest {
    @Test fun httpToAPublicHostIsRefused() {
        assertFalse(EngineConfig.isValidBaseUrl("http://engine.example"))
        assertFalse(EngineConfig.isValidBaseUrl("http://8.8.8.8:8000"))
    }

    /** Review P3: only canonical dotted-decimal octets count (a resolver with inet_aton rules reads 0172 as octal). */
    @Test fun nonCanonicalOctetsAreRefused() {
        assertFalse(EngineConfig.isValidBaseUrl("http://0172.16.0.1"))
        assertFalse(EngineConfig.isValidBaseUrl("http://10.0.0.01"))
        assertFalse(EngineConfig.isValidBaseUrl("http://192.168.001.5"))
        assertFalse(EngineConfig.isValidBaseUrl("http://010.0.0.1"))
        assertTrue(EngineConfig.isValidBaseUrl("http://10.0.0.0"))
        assertTrue(EngineConfig.isValidBaseUrl("http://192.168.100.250:8000"))
    }

    @Test fun lookalikeHostsAreRefused() {
        assertFalse(EngineConfig.isValidBaseUrl("http://192.168.1.5.example.com"))
        assertFalse(EngineConfig.isValidBaseUrl("http://10.0.0.2@example.com/"))
        assertFalse(EngineConfig.isValidBaseUrl("http://10.0.0.2:80@example.com/"))
        assertFalse(EngineConfig.isValidBaseUrl("http://172.32.0.1"))
        assertFalse(EngineConfig.isValidBaseUrl("http://172.15.0.1"))
        assertFalse(EngineConfig.isValidBaseUrl("https://user:pw@engine.example"))
    }

    @Test fun httpsAndHttpOnAPrivateLanAreAllowed() {
        assertTrue(EngineConfig.isValidBaseUrl("https://engine.example"))
        assertTrue(EngineConfig.isValidBaseUrl("http://192.168.1.5:8000"))
        assertTrue(EngineConfig.isValidBaseUrl("http://10.0.0.2"))
        assertTrue(EngineConfig.isValidBaseUrl("http://172.20.10.3:8000/"))
    }
}
