package com.kevin.legion.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [SupabaseConfig]'s save/read round trip and shape validation
 * (`.scratch/backend-erp/issues/05-migration-path.md`, Phase 1).
 */
@RunWith(RobolectricTestRunner::class)
class SupabaseConfigTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        SupabaseConfig.clear(context)
    }

    @Test
    fun `isConfigured is false with nothing saved`() {
        assertFalse(SupabaseConfig.isConfigured(context))
        assertEquals("", SupabaseConfig.url(context))
        assertEquals("", SupabaseConfig.anonKey(context))
    }

    @Test
    fun `save round trips a valid url and key`() {
        val saved = SupabaseConfig.save(context, "https://abcxyz.supabase.co", "anon-key-123")

        assertTrue(saved)
        assertTrue(SupabaseConfig.isConfigured(context))
        assertEquals("https://abcxyz.supabase.co", SupabaseConfig.url(context))
        assertEquals("anon-key-123", SupabaseConfig.anonKey(context))
    }

    @Test
    fun `save trims whitespace from both fields`() {
        SupabaseConfig.save(context, "  https://abcxyz.supabase.co  ", "  anon-key-123  ")

        assertEquals("https://abcxyz.supabase.co", SupabaseConfig.url(context))
        assertEquals("anon-key-123", SupabaseConfig.anonKey(context))
    }

    @Test
    fun `save rejects a malformed url and writes nothing`() {
        val saved = SupabaseConfig.save(context, "not-a-url", "anon-key-123")

        assertFalse(saved)
        assertFalse(SupabaseConfig.isConfigured(context))
        assertEquals("", SupabaseConfig.url(context))
    }

    @Test
    fun `save rejects a url that is not a supabase co host`() {
        val saved = SupabaseConfig.save(context, "https://evil.example.com", "anon-key-123")

        assertFalse(saved)
        assertFalse(SupabaseConfig.isConfigured(context))
    }

    @Test
    fun `save rejects a blank anon key and writes nothing`() {
        val saved = SupabaseConfig.save(context, "https://abcxyz.supabase.co", "   ")

        assertFalse(saved)
        assertFalse(SupabaseConfig.isConfigured(context))
        assertEquals("", SupabaseConfig.anonKey(context))
    }

    @Test
    fun `a rejected save does not clobber a previously valid config`() {
        SupabaseConfig.save(context, "https://abcxyz.supabase.co", "anon-key-123")

        val secondSaved = SupabaseConfig.save(context, "garbage", "")

        assertFalse(secondSaved)
        assertTrue(SupabaseConfig.isConfigured(context))
        assertEquals("https://abcxyz.supabase.co", SupabaseConfig.url(context))
    }

    @Test
    fun `clear removes a saved config`() {
        SupabaseConfig.save(context, "https://abcxyz.supabase.co", "anon-key-123")

        SupabaseConfig.clear(context)

        assertFalse(SupabaseConfig.isConfigured(context))
    }
}
