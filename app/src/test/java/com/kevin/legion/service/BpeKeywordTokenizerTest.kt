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
    fun `a name becomes the single hey line`() {
        val r = WakeKeywords.build("Alfred", tokenizer) as WakeKeywords.Result.Ok
        val lines = r.fileText.trim().lines()
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith("@hey_alfred"))
        assertTrue(lines[0].startsWith("▁HE"))
    }

    @Test
    fun `hey alfred is representable`() {
        val tokens = tokenizer.tokensOf("hey alfred")
        assertNotNull(tokens)
        assertTrue(tokens!!.none { it == "<unk>" })
    }

    @Test
    fun `a blank name is refused in words and never an empty file`() {
        val r = WakeKeywords.build("  ", tokenizer)
        assertTrue(r is WakeKeywords.Result.Refused)
        assertTrue((r as WakeKeywords.Result.Refused).reason.contains("no companion name"))
    }

    @Test
    fun `a name with a character the model lacks is refused in words`() {
        assertNull(tokenizer.tokensOf("hey éé"))
        val r = WakeKeywords.build("éé", tokenizer)
        assertTrue(r is WakeKeywords.Result.Refused)
        assertNotNull((r as WakeKeywords.Result.Refused).reason)
    }
}
