@file:Suppress("MaxLineLength") // test fixtures and assertions read best on one line

package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SpokenLanguages] and its effect on the guard, the splitter and the tags. Invented lines. */
class SpokenLanguagesTest {
    private val burmese = SpokenLanguages.byName("Burmese")!!
    private val englishOnly = listOf(SpokenLanguages.ENGLISH)
    private val withBurmese = listOf(SpokenLanguages.ENGLISH, burmese)

    /** "What is on my calendar tomorrow?" in Burmese (invented test line). */
    private val burmeseLine = "မနက်ဖြန် ပြက္ခဒိန်မှာ ဘာရှိလဲ။"

    @Test
    fun `the default setting is the English pin`() {
        assertEquals(CloudSpeechWire.STT_INSTRUCTION, SpokenLanguages.sttInstruction(englishOnly))
        assertTrue(SttGuard.verdict("¿Qué pasó con el coche?", 2_000, accepted = englishOnly) is SttGuard.Verdict.Drop)
        assertTrue(SttGuard.verdict("dijo que no hay nada para hoy", 2_000, accepted = englishOnly) is SttGuard.Verdict.Drop)
        assertTrue(SttGuard.verdict(burmeseLine, 2_000, accepted = englishOnly) is SttGuard.Verdict.Drop)
    }

    @Test
    fun `with Burmese added, Burmese speech is kept and the transcriber is told both languages`() {
        val v = SttGuard.verdict(burmeseLine, 2_000, accepted = withBurmese)
        assertEquals(SttGuard.Verdict.Keep(burmeseLine), v)
        val i = SpokenLanguages.sttInstruction(withBurmese)
        assertTrue(i.contains("English, Burmese"))
        assertTrue(i.contains("never any other"))
        assertTrue(SpokenLanguages.sttInstruction(withBurmese, burmese).contains("Burmese, English"))
        // Chinese is still not his: dropped.
        assertTrue(SttGuard.verdict("你好", 1_500, accepted = withBurmese) is SttGuard.Verdict.Drop)
        assertEquals(burmese, SpokenLanguages.writtenIn(burmeseLine, withBurmese))
        assertNull(SpokenLanguages.writtenIn(burmeseLine, englishOnly))
    }

    @Test
    fun `a spoken request switches, English switches back, an unknown language stays English`() {
        assertEquals(SpokenLanguages.Switch.To(burmese), SpokenLanguages.request("can you speak Burmese please", withBurmese))
        assertEquals(SpokenLanguages.Switch.To(burmese), SpokenLanguages.request("let's talk in Myanmar", withBurmese))
        assertEquals(SpokenLanguages.Decision.To(burmese), SpokenLanguages.decide("မြန်မာလို ပြောပါ", withBurmese, SpokenLanguages.ENGLISH, cloud = true))
        assertEquals(SpokenLanguages.Switch.To(SpokenLanguages.ENGLISH), SpokenLanguages.request("speak English", withBurmese))
        assertEquals(SpokenLanguages.Switch.To(SpokenLanguages.ENGLISH), SpokenLanguages.request("English.", withBurmese))
        assertEquals(SpokenLanguages.Switch.NotInSettings("Burmese"), SpokenLanguages.request("speak Burmese", englishOnly))
        assertNull("not a language on the list: no offer", SpokenLanguages.request("switch to French", withBurmese))
        assertNull("food, not the conversation", SpokenLanguages.request("change it to Thai", withBurmese))
        assertEquals(SpokenLanguages.Decision.To(burmese), SpokenLanguages.decide("speak Burmese", withBurmese, SpokenLanguages.ENGLISH, cloud = true))
        assertTrue(SpokenLanguages.decide("speak Burmese", withBurmese, SpokenLanguages.ENGLISH, cloud = false) is SpokenLanguages.Decision.Refuse)
        assertTrue(SpokenLanguages.decide("speak Thai", withBurmese, SpokenLanguages.ENGLISH, cloud = true) is SpokenLanguages.Decision.Refuse)
        assertEquals(SpokenLanguages.Decision.Stay, SpokenLanguages.decide("speak Burmese", withBurmese, burmese, cloud = true))
        assertNull(SpokenLanguages.request("how do you say hello in Spanish", withBurmese))
        assertNull(SpokenLanguages.request("what's on my calendar", withBurmese))
    }

    @Test
    fun `only a whole turn asks for a language, a language inside a longer request is part of it`() {
        val withSpanish = listOf(SpokenLanguages.ENGLISH) + SpokenLanguages.OFFERED
        for (t in listOf("speak Spanish", "Switch to Thai.", "answer in Burmese", "Alfred, speak Spanish please.", "Please switch to Thai, Alfred.", "Hey Alfred, answer in Burmese.", "let's speak in Malay")) {
            assertTrue(t, SpokenLanguages.request(t, withSpanish, "Alfred") is SpokenLanguages.Switch.To)
        }
        for (t in listOf(
            "remind me to ask if the tutor can speak Spanish", "let's switch to Thai food tonight", "find a doctor who can speak Chinese",
            "answer in Burmese and then read my calendar", "Thai.", "Chinese.", "español", "Melayu.",
        )) {
            assertNull(t, SpokenLanguages.request(t, withSpanish, "Alfred"))
            assertEquals(t, SpokenLanguages.Decision.Stay, SpokenLanguages.decide(t, englishOnly, SpokenLanguages.ENGLISH, cloud = true, companionName = "Alfred"))
        }
        assertTrue(SpokenLanguages.notInSettingsLine("French").contains("Settings"))
        assertEquals("", SpokenLanguages.turnNote(SpokenLanguages.ENGLISH))
        assertTrue(SpokenLanguages.turnNote(burmese).contains("reply in Burmese"))
    }

    @Test
    fun `a Burmese reply is spoken sentence by sentence, tags and figures handled`() {
        val s = SentenceSplitter()
        val out = s.feed("ဟုတ်ကဲ့။ မနက်ဖြန် အစည်းအဝေး ရှိတယ်။ ") + listOfNotNull(s.flush())
        assertEquals(2, out.size)
        assertEquals("ဟုတ်ကဲ့။ နေကောင်းလား။", TtsTags.strip("ဟုတ်ကဲ့။ <chuckle> နေကောင်းလား။"))
        assertFalse(TtsTags.hasTag(TtsTags.forSpeech("ဒေါ်လာ 45 ကျန်တယ် <chuckle>။", TtsTags.Budget())))
        assertFalse(MoneyFilter.hasMoney("ဟုတ်ကဲ့"))
    }
}
