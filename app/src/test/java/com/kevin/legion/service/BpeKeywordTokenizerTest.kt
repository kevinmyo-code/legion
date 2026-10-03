package com.kevin.legion.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 18: keywords-file generation from a name. The tokenizer is checked against the model's
 * OWN shipped pairs (`keywords_raw.txt` -> `keywords.txt`, produced by real SentencePiece), so a
 * wrong merge rule fails here rather than silently spotting a different phrase on the phone.
 */
class BpeKeywordTokenizerTest {
    private val tokenizer = BpeKeywordTokenizer.fromModel(File("src/main/assets/wake-kws/bpe.model").readBytes())

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/wake-kws/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }

    @Test
    fun `matches the model's own reference tokenisation`() {
        val raw = resource("keywords_raw.txt")
        val expected = resource("keywords.txt")
        assertEquals(expected.size, raw.size)
        raw.zip(expected).forEach { (phrase, tokens) ->
            assertEquals(phrase, tokens, tokenizer.tokensOf(phrase)!!.joinToString(" "))
        }
    }

    @Test
    fun `a name becomes a hey line and the fixed phrase stays`() {
        val r = WakeKeywords.build("Alfred", tokenizer) as WakeKeywords.Result.Ok
        val lines = r.fileText.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("@excelsior"))
        assertTrue(lines[1].endsWith("@hey_alfred"))
        assertTrue(lines[1].startsWith("▁HE"))
    }

    @Test
    fun `excelsior is representable and printed for the A25 log`() {
        val tokens = tokenizer.tokensOf("excelsior")
        assertNotNull(tokens)
        println("EXCELSIOR_TOKENS=" + tokens!!.joinToString(" "))
        assertTrue(tokens.none { it == "<unk>" })
    }

    @Test
    fun `a blank name still yields the fixed phrase and never an empty file`() {
        val r = WakeKeywords.build("  ", tokenizer) as WakeKeywords.Result.Ok
        assertEquals(1, r.fileText.trim().lines().size)
    }

    @Test
    fun `a name with a character the model lacks is refused in words`() {
        assertNull(tokenizer.tokensOf("hey éé"))
        val r = WakeKeywords.build("éé", tokenizer)
        assertTrue(r is WakeKeywords.Result.Refused)
        assertNotNull((r as WakeKeywords.Result.Refused).reason)
    }
}
